/*
 * Copyright 2024 OTelFeature
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package io.otelfeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("ErrorRateReader: the service's HTTP error rate from its own metrics")
class ErrorRateReaderTest {

    private static final AttributeKey<String> METHOD = AttributeKey.stringKey("http.request.method");
    private static final AttributeKey<Long> STATUS = AttributeKey.longKey("http.response.status_code");
    private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");

    private final List<Double> published = new ArrayList<>();
    private SdkMeterProvider meterProvider;

    @AfterEach
    void tearDown() {
        if (meterProvider != null) {
            meterProvider.shutdown();
        }
    }

    /** A reader that only collects when the test says so (an hour's interval). */
    private ErrorRateReader reader(long minRequests) {
        return new ErrorRateReader(published::add, Duration.ofHours(1), minRequests);
    }

    private DoubleHistogram histogram(ErrorRateReader reader) {
        meterProvider = SdkMeterProvider.builder().registerMetricReader(reader).build();
        return histogram(meterProvider);
    }

    private static DoubleHistogram histogram(SdkMeterProvider provider) {
        return provider.get("io.opentelemetry.tomcat-10.0").histogramBuilder(ErrorRateReader.METRIC)
                .setUnit("s").build();
    }

    /** What the HTTP server instrumentation records: error.type only on failures. */
    private static void requests(DoubleHistogram histogram, int ok, int failed) {
        for (int i = 0; i < ok; i++) {
            histogram.record(0.01, Attributes.of(METHOD, "GET", STATUS, 200L));
        }
        for (int i = 0; i < failed; i++) {
            histogram.record(0.2, Attributes.of(METHOD, "GET", STATUS, 503L, ERROR_TYPE, "503"));
        }
    }

    @ParameterizedTest
    @CsvSource({"100,0", "95,5", "90,10", "0,20"})
    @DisplayName("N requests, k of them 5xx: the next collection publishes k/N")
    void publishesKOverN(int ok, int failed) {
        ErrorRateReader reader = reader(10);
        requests(histogram(reader), ok, failed);

        reader.collect();

        assertEquals(List.of((double) failed / (ok + failed)), published);
    }

    @Test
    @DisplayName("delta: each collection is its own interval")
    void eachCollectionIsAnInterval() {
        ErrorRateReader reader = reader(10);
        DoubleHistogram histogram = histogram(reader);

        requests(histogram, 50, 50);
        reader.collect();
        requests(histogram, 100, 0);
        reader.collect();
        reader.collect(); // nothing since the last one

        assertEquals(0.5, published.get(0));
        assertEquals(0.0, published.get(1), "the earlier failures must not leak into this interval");
        assertNull(published.get(2), "an interval with no requests has no rate");
    }

    @Test
    @DisplayName("below the minimum number of requests, the key is left out")
    void belowMinimumNoRate() {
        ErrorRateReader reader = reader(10);
        DoubleHistogram histogram = histogram(reader);

        requests(histogram, 1, 8); // 9 < 10
        reader.collect();
        requests(histogram, 2, 8); // 10
        reader.collect();

        assertNull(published.get(0), "8 of 9 failing is not a rate worth acting on");
        assertEquals(0.8, published.get(1));
    }

    @Test
    @DisplayName("without error.type, a 5xx status counts as an error")
    void statusFallback() {
        ErrorRateReader reader = reader(1);
        DoubleHistogram histogram = histogram(reader);

        histogram.record(0.1, Attributes.of(STATUS, 500L));
        histogram.record(0.1, Attributes.of(STATUS, 404L));
        histogram.record(0.1, Attributes.of(STATUS, 200L));
        histogram.record(0.1, Attributes.of(STATUS, 200L));
        reader.collect();

        assertEquals(List.of(0.25), published, "404 is the client's error, not the service's");
    }

    @Test
    @DisplayName("other metrics are ignored")
    void otherMetricsIgnored() {
        ErrorRateReader reader = reader(1);
        meterProvider = SdkMeterProvider.builder().registerMetricReader(reader).build();
        meterProvider.get("test").histogramBuilder("http.client.request.duration").build()
                .record(0.1, Attributes.of(ERROR_TYPE, "timeout"));
        reader.collect();

        assertEquals(1, published.size());
        assertNull(published.get(0));
    }

    @Test
    @DisplayName("the OTLP side is unchanged: another reader sees the same data with or without this one")
    void otherReadersUnchanged() {
        InMemoryMetricReader alone = InMemoryMetricReader.create();
        SdkMeterProvider withoutUs = SdkMeterProvider.builder().registerMetricReader(alone).build();
        InMemoryMetricReader beside = InMemoryMetricReader.create();
        ErrorRateReader ours = reader(1);
        meterProvider = SdkMeterProvider.builder().registerMetricReader(beside).registerMetricReader(ours).build();

        for (int round = 0; round < 3; round++) {
            requests(histogram(withoutUs), 7, 3);
            requests(histogram(meterProvider), 7, 3);
            ours.collect(); // our delta collections must not reset the other reader's cumulative data
            assertEquals(summary(alone.collectAllMetrics()), summary(beside.collectAllMetrics()),
                    "round " + round);
        }
        withoutUs.shutdown();
    }

    /** Count and sum per attribute set: what reaches the collector, without timestamps. */
    private static List<String> summary(Collection<MetricData> metrics) {
        return metrics.stream()
                .flatMap(m -> m.getHistogramData().getPoints().stream()
                        .map(p -> m.getName() + " " + m.getHistogramData().getAggregationTemporality()
                                + " " + p.getAttributes() + " count=" + p.getCount() + " sum=" + p.getSum()))
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("sampling the spans down to 0 doesn't change the measured error rate")
    void spanSamplingIrrelevant() {
        ErrorRateReader reader = reader(1);
        DoubleHistogram histogram = histogram(reader);
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder().setSampler(Sampler.alwaysOff()).build();
        Tracer tracer = tracerProvider.get("io.opentelemetry.tomcat-10.0");

        // What the instrumentation does per request: a span (here never
        // sampled, so it never records a status) and a histogram point.
        for (int i = 0; i < 40; i++) {
            boolean failed = i % 4 == 0;
            tracer.spanBuilder("GET /weather").startSpan().end();
            requests(histogram, failed ? 0 : 1, failed ? 1 : 0);
        }
        reader.collect();
        tracerProvider.shutdown();

        assertEquals(List.of(0.25), published);
    }

    @Test
    @DisplayName("errorRate on its own: counts summed over every attribute set")
    void errorRateFromPoints() {
        InMemoryMetricReader memory = InMemoryMetricReader.createDelta();
        SdkMeterProvider fresh = SdkMeterProvider.builder().registerMetricReader(memory).build();
        DoubleHistogram histogram = histogram(fresh);
        requests(histogram, 6, 1);
        histogram.record(0.3, Attributes.of(METHOD, "POST", STATUS, 500L, ERROR_TYPE, "500"));

        assertEquals(0.25, ErrorRateReader.errorRate(memory.collectAllMetrics(), 1));
        fresh.shutdown();
    }

    @Test
    @DisplayName("registered with a meter provider, it collects on its own every interval")
    void collectsOnSchedule() throws InterruptedException {
        List<Double> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        ErrorRateReader reader = new ErrorRateReader(seen::add, Duration.ofMillis(50), 1);
        meterProvider = SdkMeterProvider.builder().registerMetricReader(reader).build();
        requests(histogram(meterProvider), 3, 1);

        long deadline = System.currentTimeMillis() + 5_000;
        while (!seen.contains(0.25) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(true, seen.contains(0.25), "scheduled collections published " + seen);
    }

    @ParameterizedTest
    @CsvSource({"10s,10000", "500ms,500", "2m,120000", "15,15000", "' 5S ',5000", "bogus,10000", "0,10000",
            "-3s,10000"})
    @DisplayName("OTELFEATURE_ERROR_RATE_INTERVAL accepts ms, s, m or plain seconds")
    void parsesIntervals(String value, long expectedMillis) {
        assertEquals(expectedMillis,
                OtelfeatureCustomizer.parseDuration(value, Duration.ofSeconds(10), "TEST").toMillis());
    }
}

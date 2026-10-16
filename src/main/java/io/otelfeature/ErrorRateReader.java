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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.data.MetricDataType;
import io.opentelemetry.sdk.metrics.export.CollectionRegistration;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns the service's own {@code http.server.request.duration} histogram into
 * an error rate for the evaluation context: the input to Demo 3's control loop,
 * "errors go up, a rule turns sampling up for that service".
 *
 * <p>A second, in-process {@link MetricReader} next to the OTLP one, with
 * <b>delta</b> temporality: each collection is one interval's worth of
 * requests, so the rate needs no window and no smoothing -
 * {@code error_rate = requests with error.type / all requests}. A request
 * counts as an error when its point carries {@code error.type}, or failing
 * that an {@code http.response.status_code} of 500 or above.
 *
 * <p>Metrics, not spans: a sampled-out span never records a status, but the
 * HTTP instrumentation records the histogram whatever the sampling does. And
 * readers are independent: this one has its own delta storage, so what the
 * OTLP reader exports is unchanged.
 *
 * <p>An interval with fewer than {@code minRequests} requests publishes no
 * rate at all: one failure out of two requests is not a 50% error rate worth
 * turning sampling up for.
 */
final class ErrorRateReader implements MetricReader {

    private static final Logger log = Logger.getLogger(ErrorRateReader.class.getName());

    static final String METRIC = "http.server.request.duration";

    /** For log lines: the context key this reader feeds. */
    static final String ERROR_RATE_KEY_DOC = EvaluationContextFactory.ERROR_RATE_KEY;
    private static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");
    private static final AttributeKey<Long> STATUS_CODE = AttributeKey.longKey("http.response.status_code");

    private final Consumer<Double> publish;
    private final Duration interval;
    private final long minRequests;

    private volatile CollectionRegistration registration = CollectionRegistration.noop();
    private volatile ScheduledExecutorService executor;

    /**
     * @param publish receives each interval's rate, or {@code null} for "no
     *     rate" (too few requests, or none at all)
     * @param interval how often to collect
     * @param minRequests the fewest requests an interval needs for a rate
     */
    ErrorRateReader(Consumer<Double> publish, Duration interval, long minRequests) {
        this.publish = publish;
        this.interval = interval;
        this.minRequests = minRequests;
    }

    @Override
    public void register(CollectionRegistration registration) {
        // Registered twice (a second meter provider): one scheduler, not two.
        shutdownScheduler();
        this.registration = registration;
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "otelfeature-error-rate");
            thread.setDaemon(true);
            return thread;
        });
        long millis = Math.max(1, interval.toMillis());
        scheduler.scheduleAtFixedRate(this::collectSafely, millis, millis, TimeUnit.MILLISECONDS);
        this.executor = scheduler;
    }

    @Override
    public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
        return AggregationTemporality.DELTA;
    }

    /** Collects one interval now and publishes its rate. Visible for tests. */
    void collect() {
        publish.accept(errorRate(registration.collectAllMetrics(), minRequests));
    }

    /** Collects one interval, never throwing. Visible for tests. */
    void collectSafely() {
        try {
            collect();
        } catch (Throwable t) {
            // A failure here must not kill the scheduler, nor reach the app -
            // and must not leave the last rate published forever: withdraw it,
            // so a rule on it falls back to its default.
            publish.accept(null);
            log.log(Level.FINE, "otelfeature-java-extension: error-rate collection failed", t);
        }
    }

    /**
     * Only the request-duration histogram is read; everything else is
     * dropped, so this reader keeps no storage for instruments it ignores.
     * (Asynchronous instruments' callbacks still run on every collection: a
     * meter provider invokes them for each reader.)
     */
    @Override
    public io.opentelemetry.sdk.metrics.Aggregation getDefaultAggregation(InstrumentType instrumentType) {
        return instrumentType == InstrumentType.HISTOGRAM
                ? io.opentelemetry.sdk.metrics.Aggregation.defaultAggregation()
                : io.opentelemetry.sdk.metrics.Aggregation.drop();
    }

    /**
     * The error rate over one delta collection.
     *
     * @param metrics what the collection returned
     * @param minRequests the fewest requests for a rate
     * @return errors / requests, or {@code null} below {@code minRequests}
     */
    static Double errorRate(Collection<MetricData> metrics, long minRequests) {
        long requests = 0;
        long errors = 0;
        for (MetricData metric : metrics) {
            if (!METRIC.equals(metric.getName())) {
                continue;
            }
            // A view may turn the histogram exponential; it applies to every
            // reader, ours included. Either way only counts are read.
            if (metric.getType() == MetricDataType.HISTOGRAM) {
                for (HistogramPointData point : metric.getHistogramData().getPoints()) {
                    requests += point.getCount();
                    errors += isError(point.getAttributes()) ? point.getCount() : 0;
                }
            } else if (metric.getType() == MetricDataType.EXPONENTIAL_HISTOGRAM) {
                for (io.opentelemetry.sdk.metrics.data.ExponentialHistogramPointData point
                        : metric.getExponentialHistogramData().getPoints()) {
                    requests += point.getCount();
                    errors += isError(point.getAttributes()) ? point.getCount() : 0;
                }
            }
        }
        if (requests == 0 || requests < minRequests) {
            // requests == 0 matters when minRequests is 0: no data is no rate.
            return null;
        }
        return (double) errors / requests;
    }

    private static boolean isError(Attributes attributes) {
        if (attributes.get(ERROR_TYPE) != null) {
            return true;
        }
        Long status = attributes.get(STATUS_CODE);
        return status != null && status >= 500;
    }

    /**
     * A no-op: a flush (a Lambda handler finishing, a shutdown hook) would
     * otherwise cut the current interval short and publish a rate over a
     * partial window - possibly racing the scheduler and landing last.
     */
    @Override
    public CompletableResultCode forceFlush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        shutdownScheduler();
        return CompletableResultCode.ofSuccess();
    }

    private void shutdownScheduler() {
        ScheduledExecutorService scheduler = executor;
        if (scheduler != null) {
            scheduler.shutdownNow();
            executor = null;
        }
    }

    @Override
    public String toString() {
        return "ErrorRateReader{metric=" + METRIC + ", interval=" + interval + ", minRequests=" + minRequests + "}";
    }
}

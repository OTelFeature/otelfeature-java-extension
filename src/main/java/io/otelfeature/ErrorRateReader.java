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

    private void collectSafely() {
        try {
            collect();
        } catch (Throwable t) {
            // A failure here must not kill the scheduler, nor reach the app.
            log.log(Level.FINE, "otelfeature-java-extension: error-rate collection failed", t);
        }
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
            if (!METRIC.equals(metric.getName()) || metric.getType() != MetricDataType.HISTOGRAM) {
                continue;
            }
            for (HistogramPointData point : metric.getHistogramData().getPoints()) {
                requests += point.getCount();
                if (isError(point.getAttributes())) {
                    errors += point.getCount();
                }
            }
        }
        if (requests == 0 || requests < minRequests) {
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

    @Override
    public CompletableResultCode forceFlush() {
        collectSafely();
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        ScheduledExecutorService scheduler = executor;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public String toString() {
        return "ErrorRateReader{metric=" + METRIC + ", interval=" + interval + ", minRequests=" + minRequests + "}";
    }
}

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.context.Context;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FilteringSpanProcessor} using the real OTel SDK
 * with an in-memory exporter (no Mockito required).
 */
@DisplayName("FilteringSpanProcessor")
class FilteringSpanProcessorTest {

    /** A simple in-memory span exporter for testing. */
    static class InMemoryExporter implements SpanExporter {
        final List<SpanData> spans = new ArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            this.spans.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }

        void clear() { spans.clear(); }
        int count() { return spans.size(); }
    }

    /** A test FlagdClient that doesn't make HTTP calls. */
    static class TestFlagdClient extends FlagdClient {
        private volatile boolean suppress = false;

        TestFlagdClient() {
            super(true); // skip background polling
        }

        @Override
        public boolean shouldSuppressInternal() {
            return suppress;
        }

        void setSuppress(boolean s) { this.suppress = s; }

        @Override
        public void shutdown() { /* no-op */ }
    }

    /** A counting SpanProcessor to verify delegation. */
    static class CountingProcessor implements SpanProcessor {
        final AtomicInteger onStartCount = new AtomicInteger(0);
        final AtomicInteger onEndCount = new AtomicInteger(0);

        @Override
        public void onStart(Context parentContext, io.opentelemetry.sdk.trace.ReadWriteSpan span) {
            onStartCount.incrementAndGet();
        }

        @Override
        public void onEnd(ReadableSpan span) {
            onEndCount.incrementAndGet();
        }

        @Override
        public boolean isStartRequired() { return true; }

        @Override
        public boolean isEndRequired() { return true; }

        @Override
        public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }

        @Override
        public CompletableResultCode forceFlush() { return CompletableResultCode.ofSuccess(); }
    }

    private TestFlagdClient flagdClient;
    private InMemoryExporter exporter;
    private SdkTracerProvider tracerProvider;
    private Tracer tracer;

    private void setUpPipeline() {
        exporter = new InMemoryExporter();
        flagdClient = new TestFlagdClient();

        SpanProcessor simpleProcessor = SimpleSpanProcessor.create(exporter);
        FilteringSpanProcessor filterProcessor = new FilteringSpanProcessor(simpleProcessor, flagdClient);

        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(filterProcessor)
                .build();

        tracer = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build()
                .getTracer("test");
    }

    private void tearDown() {
        tracerProvider.shutdown();
    }

    @Test
    @DisplayName("exports all spans when suppression is inactive")
    void exportsAllWhenInactive() {
        setUpPipeline();
        flagdClient.setSuppress(false);

        tracer.spanBuilder("server-span").setSpanKind(SpanKind.SERVER).startSpan().end();
        tracer.spanBuilder("client-span").setSpanKind(SpanKind.CLIENT).startSpan().end();
        tracer.spanBuilder("internal-span").setSpanKind(SpanKind.INTERNAL).startSpan().end();

        assertEquals(3, exporter.count());
        assertEquals("server-span", exporter.spans.get(0).getName());
        assertEquals("client-span", exporter.spans.get(1).getName());
        assertEquals("internal-span", exporter.spans.get(2).getName());

        tearDown();
    }

    @Test
    @DisplayName("drops INTERNAL spans when suppression is active")
    void dropsInternalWhenActive() {
        setUpPipeline();
        flagdClient.setSuppress(true);

        tracer.spanBuilder("server-span").setSpanKind(SpanKind.SERVER).startSpan().end();
        tracer.spanBuilder("client-span").setSpanKind(SpanKind.CLIENT).startSpan().end();
        tracer.spanBuilder("internal-span").setSpanKind(SpanKind.INTERNAL).startSpan().end();
        tracer.spanBuilder("another-internal").setSpanKind(SpanKind.INTERNAL).startSpan().end();

        assertEquals(2, exporter.count());
        assertEquals("server-span", exporter.spans.get(0).getName());
        assertEquals("client-span", exporter.spans.get(1).getName());
        assertTrue(exporter.spans.stream().noneMatch(s -> s.getKind() == SpanKind.INTERNAL));

        tearDown();
    }

    @Test
    @DisplayName("children of suppressed INTERNAL spans are still exported")
    void childrenOfSuppressedAreExported() {
        setUpPipeline();
        flagdClient.setSuppress(true);

        var parent = tracer.spanBuilder("parent-internal")
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();

        try (var scope = parent.makeCurrent()) {
            tracer.spanBuilder("child-server")
                    .setSpanKind(SpanKind.SERVER)
                    .startSpan()
                    .end();
        } finally {
            parent.end();
        }

        assertEquals(1, exporter.count());
        assertEquals("child-server", exporter.spans.get(0).getName());
        assertEquals(SpanKind.SERVER, exporter.spans.get(0).getKind());

        tearDown();
    }

    @Test
    @DisplayName("flag toggle takes effect on subsequent spans")
    void flagToggleTakesEffect() {
        setUpPipeline();

        // Suppression off → INTERNAL exported
        flagdClient.setSuppress(false);
        tracer.spanBuilder("internal-1").setSpanKind(SpanKind.INTERNAL).startSpan().end();
        assertEquals(1, exporter.count());

        // Toggle on → INTERNAL dropped
        flagdClient.setSuppress(true);
        tracer.spanBuilder("internal-2").setSpanKind(SpanKind.INTERNAL).startSpan().end();
        assertEquals(1, exporter.count()); // still 1

        // Toggle off → INTERNAL exported again
        flagdClient.setSuppress(false);
        tracer.spanBuilder("internal-3").setSpanKind(SpanKind.INTERNAL).startSpan().end();
        assertEquals(2, exporter.count());

        tearDown();
    }

    @Test
    @DisplayName("all non-INTERNAL span kinds are exported when suppression is active")
    void allNonInternalKindsExported() {
        setUpPipeline();
        flagdClient.setSuppress(true);

        for (SpanKind kind : SpanKind.values()) {
            exporter.clear();
            String name = "span-" + kind.name();
            tracer.spanBuilder(name).setSpanKind(kind).startSpan().end();

            if (kind == SpanKind.INTERNAL) {
                assertEquals(0, exporter.count(), kind + " should be dropped");
            } else {
                assertEquals(1, exporter.count(), kind + " should be exported");
                assertEquals(name, exporter.spans.get(0).getName());
            }
        }

        tearDown();
    }

    @Test
    @DisplayName("isStartRequired delegates to delegate")
    void isStartRequiredDelegates() {
        CountingProcessor delegate = new CountingProcessor();
        FilteringSpanProcessor processor = new FilteringSpanProcessor(delegate, new TestFlagdClient());

        assertTrue(processor.isStartRequired());
    }

    @Test
    @DisplayName("isEndRequired always returns true")
    void isEndRequiredAlwaysTrue() {
        CountingProcessor delegate = new CountingProcessor();
        FilteringSpanProcessor processor = new FilteringSpanProcessor(delegate, new TestFlagdClient());

        assertTrue(processor.isEndRequired());
    }

    @Test
    @DisplayName("shutdown delegates to delegate")
    void shutdownDelegates() {
        CountingProcessor delegate = new CountingProcessor();
        FilteringSpanProcessor processor = new FilteringSpanProcessor(delegate, new TestFlagdClient());

        CompletableResultCode result = processor.shutdown();
        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("forceFlush delegates to delegate")
    void forceFlushDelegates() {
        CountingProcessor delegate = new CountingProcessor();
        FilteringSpanProcessor processor = new FilteringSpanProcessor(delegate, new TestFlagdClient());

        CompletableResultCode result = processor.forceFlush();
        assertTrue(result.isSuccess());
    }
}

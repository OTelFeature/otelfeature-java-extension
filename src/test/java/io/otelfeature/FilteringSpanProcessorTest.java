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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.trace.Span;
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
 * Unit tests for {@link FilteringSpanProcessor} and {@link ReparentingSpanExporter}
 * using the real OTel SDK with an in-memory exporter.
 */
@DisplayName("FilteringSpanProcessor + ReparentingSpanExporter")
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
            super(true);
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
    private SuppressedSpanRegistry registry;
    private InMemoryExporter inMemoryExporter;
    private ReparentingSpanExporter reparentingExporter;
    private SdkTracerProvider tracerProvider;
    private Tracer tracer;

    private void setUpPipeline() {
        inMemoryExporter = new InMemoryExporter();
        registry = new SuppressedSpanRegistry();
        flagdClient = new TestFlagdClient();
        reparentingExporter = new ReparentingSpanExporter(inMemoryExporter, registry);

        SpanProcessor simpleProcessor = SimpleSpanProcessor.create(reparentingExporter);
        FilteringSpanProcessor filterProcessor =
                new FilteringSpanProcessor(simpleProcessor, flagdClient, registry);

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

        assertEquals(3, inMemoryExporter.count());
        assertEquals("server-span", inMemoryExporter.spans.get(0).getName());
        assertEquals("client-span", inMemoryExporter.spans.get(1).getName());
        assertEquals("internal-span", inMemoryExporter.spans.get(2).getName());

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

        assertEquals(2, inMemoryExporter.count());
        assertEquals("server-span", inMemoryExporter.spans.get(0).getName());
        assertEquals("client-span", inMemoryExporter.spans.get(1).getName());
        assertTrue(inMemoryExporter.spans.stream().noneMatch(s -> s.getKind() == SpanKind.INTERNAL));

        tearDown();
    }

    @Test
    @DisplayName("children of suppressed INTERNAL spans are re-parented to grandparent")
    void childrenReparentedToGrandparent() {
        setUpPipeline();
        flagdClient.setSuppress(true);

        // Create: SERVER → INTERNAL (suppressed) → CLIENT (should be re-parented to SERVER)
        Span server = tracer.spanBuilder("server").setSpanKind(SpanKind.SERVER).startSpan();
        Span internal;
        Span client;

        try (var scope1 = server.makeCurrent()) {
            internal = tracer.spanBuilder("internal").setSpanKind(SpanKind.INTERNAL).startSpan();
            try (var scope2 = internal.makeCurrent()) {
                client = tracer.spanBuilder("client").setSpanKind(SpanKind.CLIENT).startSpan();
                client.end();
            }
            internal.end();
        }
        server.end();

        // Only server and client should be exported (internal dropped)
        assertEquals(2, inMemoryExporter.count());
        assertEquals("server", inMemoryExporter.spans.get(0).getName());
        assertEquals("client", inMemoryExporter.spans.get(1).getName());

        // The client's parent should be the server, not the internal span
        SpanData serverData = inMemoryExporter.spans.get(0);
        SpanData clientData = inMemoryExporter.spans.get(1);

        assertEquals(serverData.getSpanId(), clientData.getParentSpanContext().getSpanId(),
                "client should be re-parented to server");
        assertNotEquals(internal.getSpanContext().getSpanId(),
                        clientData.getParentSpanContext().getSpanId(),
                        "client should NOT reference the suppressed internal span");

        tearDown();
    }

    @Test
    @DisplayName("nested INTERNAL spans: child re-parented to nearest non-suppressed ancestor")
    void nestedInternalSpansReparented() {
        setUpPipeline();
        flagdClient.setSuppress(true);

        // Create: SERVER → INTERNAL_A (suppressed) → INTERNAL_B (suppressed) → CLIENT
        // CLIENT should be re-parented to SERVER
        Span server = tracer.spanBuilder("server").setSpanKind(SpanKind.SERVER).startSpan();
        Span internalA;
        Span internalB;
        Span client;

        try (var s1 = server.makeCurrent()) {
            internalA = tracer.spanBuilder("internal-a").setSpanKind(SpanKind.INTERNAL).startSpan();
            try (var s2 = internalA.makeCurrent()) {
                internalB = tracer.spanBuilder("internal-b").setSpanKind(SpanKind.INTERNAL).startSpan();
                try (var s3 = internalB.makeCurrent()) {
                    client = tracer.spanBuilder("client").setSpanKind(SpanKind.CLIENT).startSpan();
                    client.end();
                }
                internalB.end();
            }
            internalA.end();
        }
        server.end();

        // Only server and client should be exported
        assertEquals(2, inMemoryExporter.count());
        SpanData serverData = inMemoryExporter.spans.get(0);
        SpanData clientData = inMemoryExporter.spans.get(1);

        assertEquals(serverData.getSpanId(), clientData.getParentSpanContext().getSpanId(),
                "client should be re-parented to server (skipping both internal spans)");

        tearDown();
    }

    @Test
    @DisplayName("flag toggle takes effect on subsequent spans")
    void flagToggleTakesEffect() {
        setUpPipeline();

        flagdClient.setSuppress(false);
        tracer.spanBuilder("internal-1").setSpanKind(SpanKind.INTERNAL).startSpan().end();
        assertEquals(1, inMemoryExporter.count());

        flagdClient.setSuppress(true);
        tracer.spanBuilder("internal-2").setSpanKind(SpanKind.INTERNAL).startSpan().end();
        assertEquals(1, inMemoryExporter.count());

        flagdClient.setSuppress(false);
        tracer.spanBuilder("internal-3").setSpanKind(SpanKind.INTERNAL).startSpan().end();
        assertEquals(2, inMemoryExporter.count());

        tearDown();
    }

    @Test
    @DisplayName("all non-INTERNAL span kinds are exported when suppression is active")
    void allNonInternalKindsExported() {
        setUpPipeline();
        flagdClient.setSuppress(true);

        for (SpanKind kind : SpanKind.values()) {
            inMemoryExporter.clear();
            String name = "span-" + kind.name();
            tracer.spanBuilder(name).setSpanKind(kind).startSpan().end();

            if (kind == SpanKind.INTERNAL) {
                assertEquals(0, inMemoryExporter.count(), kind + " should be dropped");
            } else {
                assertEquals(1, inMemoryExporter.count(), kind + " should be exported");
                assertEquals(name, inMemoryExporter.spans.get(0).getName());
            }
        }

        tearDown();
    }

    @Test
    @DisplayName("isStartRequired delegates to delegate")
    void isStartRequiredDelegates() {
        CountingProcessor delegate = new CountingProcessor();
        FilteringSpanProcessor processor =
                new FilteringSpanProcessor(delegate, new TestFlagdClient(), new SuppressedSpanRegistry());
        assertTrue(processor.isStartRequired());
    }

    @Test
    @DisplayName("isEndRequired always returns true")
    void isEndRequiredAlwaysTrue() {
        CountingProcessor delegate = new CountingProcessor();
        FilteringSpanProcessor processor =
                new FilteringSpanProcessor(delegate, new TestFlagdClient(), new SuppressedSpanRegistry());
        assertTrue(processor.isEndRequired());
    }

    @Test
    @DisplayName("shutdown delegates to delegate")
    void shutdownDelegates() {
        CountingProcessor delegate = new CountingProcessor();
        FilteringSpanProcessor processor =
                new FilteringSpanProcessor(delegate, new TestFlagdClient(), new SuppressedSpanRegistry());
        assertTrue(processor.shutdown().isSuccess());
    }

    @Test
    @DisplayName("forceFlush delegates to delegate")
    void forceFlushDelegates() {
        CountingProcessor delegate = new CountingProcessor();
        FilteringSpanProcessor processor =
                new FilteringSpanProcessor(delegate, new TestFlagdClient(), new SuppressedSpanRegistry());
        assertTrue(processor.forceFlush().isSuccess());
    }

    @Test
    @DisplayName("ReparentingSpanExporter is transparent when registry is empty")
    void reparentingExporterTransparentWhenEmpty() {
        InMemoryExporter delegate = new InMemoryExporter();
        SuppressedSpanRegistry emptyRegistry = new SuppressedSpanRegistry();
        ReparentingSpanExporter exporter = new ReparentingSpanExporter(delegate, emptyRegistry);

        List<SpanData> spans = List.of(
                io.opentelemetry.sdk.trace.data.SpanData.builder()
                        .setName("test").setSpanId("0123456789abcdef0")
                        .setTraceId("0123456789abcdef0123456789abcdef")
                        .setKind(SpanKind.SERVER)
                        .setStartEpochNanos(0).setEndEpochNanos(1)
                        .build());

        exporter.export(spans);

        assertEquals(1, delegate.count());
        assertEquals("test", delegate.spans.get(0).getName());
    }
}

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

    /** A telemetry level that is whatever the test says it is. */
    static class TestTelemetryLevel implements TelemetryLevelSource {
        private volatile boolean suppress = false;

        @Override
        public boolean shouldSuppressInternal() {
            return suppress;
        }

        void setSuppress(boolean s) { this.suppress = s; }
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

    private TestTelemetryLevel telemetryLevel;
    private SuppressedSpanRegistry registry;
    private InMemoryExporter inMemoryExporter;
    private ReparentingSpanExporter reparentingExporter;
    private SdkTracerProvider tracerProvider;
    private Tracer tracer;

    private void setUpPipeline() {
        inMemoryExporter = new InMemoryExporter();
        registry = new SuppressedSpanRegistry();
        telemetryLevel = new TestTelemetryLevel();
        reparentingExporter = new ReparentingSpanExporter(inMemoryExporter, registry);

        SpanProcessor simpleProcessor = SimpleSpanProcessor.create(reparentingExporter);
        FilteringSpanProcessor filterProcessor =
                new FilteringSpanProcessor(simpleProcessor, telemetryLevel, registry);

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
        telemetryLevel.setSuppress(false);

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
        telemetryLevel.setSuppress(true);

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
        telemetryLevel.setSuppress(true);

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

        // Only server and client should be exported (internal dropped).
        // SimpleSpanProcessor exports synchronously on end(), so child spans
        // (client) appear before their parents (server) in the export order.
        assertEquals(2, inMemoryExporter.count());
        assertEquals("client", inMemoryExporter.spans.get(0).getName());
        assertEquals("server", inMemoryExporter.spans.get(1).getName());

        // The client's parent should be the server, not the internal span
        SpanData clientData = inMemoryExporter.spans.get(0);
        SpanData serverData = inMemoryExporter.spans.get(1);

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
        telemetryLevel.setSuppress(true);

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

        // Only server and client should be exported.
        // SimpleSpanProcessor exports synchronously on end(), so client
        // (ended first) appears before server (ended last).
        assertEquals(2, inMemoryExporter.count());
        SpanData clientData = inMemoryExporter.spans.get(0);
        SpanData serverData = inMemoryExporter.spans.get(1);

        assertEquals(serverData.getSpanId(), clientData.getParentSpanContext().getSpanId(),
                "client should be re-parented to server (skipping both internal spans)");

        tearDown();
    }

    @Test
    @DisplayName("flag toggle takes effect on subsequent spans")
    void flagToggleTakesEffect() {
        setUpPipeline();

        telemetryLevel.setSuppress(false);
        tracer.spanBuilder("internal-1").setSpanKind(SpanKind.INTERNAL).startSpan().end();
        assertEquals(1, inMemoryExporter.count());

        telemetryLevel.setSuppress(true);
        tracer.spanBuilder("internal-2").setSpanKind(SpanKind.INTERNAL).startSpan().end();
        assertEquals(1, inMemoryExporter.count());

        telemetryLevel.setSuppress(false);
        tracer.spanBuilder("internal-3").setSpanKind(SpanKind.INTERNAL).startSpan().end();
        assertEquals(2, inMemoryExporter.count());

        tearDown();
    }

    @Test
    @DisplayName("all non-INTERNAL span kinds are exported when suppression is active")
    void allNonInternalKindsExported() {
        setUpPipeline();
        telemetryLevel.setSuppress(true);

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
    @DisplayName("isStartRequired always returns true")
    void isStartRequiredAlwaysTrue() {
        CountingProcessor delegate = new CountingProcessor();
        FilteringSpanProcessor processor =
                new FilteringSpanProcessor(delegate, new TestTelemetryLevel(), new SuppressedSpanRegistry());
        assertTrue(processor.isStartRequired());
    }

    @Test
    @DisplayName("isEndRequired always returns true")
    void isEndRequiredAlwaysTrue() {
        CountingProcessor delegate = new CountingProcessor();
        FilteringSpanProcessor processor =
                new FilteringSpanProcessor(delegate, new TestTelemetryLevel(), new SuppressedSpanRegistry());
        assertTrue(processor.isEndRequired());
    }

    @Test
    @DisplayName("shutdown delegates to delegate")
    void shutdownDelegates() {
        CountingProcessor delegate = new CountingProcessor();
        FilteringSpanProcessor processor =
                new FilteringSpanProcessor(delegate, new TestTelemetryLevel(), new SuppressedSpanRegistry());
        assertTrue(processor.shutdown().isSuccess());
    }

    @Test
    @DisplayName("forceFlush delegates to delegate")
    void forceFlushDelegates() {
        CountingProcessor delegate = new CountingProcessor();
        FilteringSpanProcessor processor =
                new FilteringSpanProcessor(delegate, new TestTelemetryLevel(), new SuppressedSpanRegistry());
        assertTrue(processor.forceFlush().isSuccess());
    }

    @Test
    @DisplayName("ReparentingSpanExporter is transparent when registry is empty")
    void reparentingExporterTransparentWhenEmpty() {
        setUpPipeline();
        // No suppression active → registry empty → exporter should passthrough
        telemetryLevel.setSuppress(false);
        tracer.spanBuilder("server-span").setSpanKind(SpanKind.SERVER).startSpan().end();

        assertEquals(1, inMemoryExporter.count());
        assertEquals("server-span", inMemoryExporter.spans.get(0).getName());

        tearDown();
    }

    @Test
    @DisplayName("a span's fate is decided at onStart, not re-decided at onEnd")
    void decisionIsStableAcrossTheSpanLifetime() {
        setUpPipeline();

        // Suppressed when it starts, no longer suppressed when it ends. The
        // span must still be dropped: its children were already re-parented
        // past it, so exporting it now would contradict them.
        telemetryLevel.setSuppress(true);
        Span internal = tracer.spanBuilder("internal").setSpanKind(SpanKind.INTERNAL).startSpan();
        telemetryLevel.setSuppress(false);
        internal.end();

        assertEquals(0, inMemoryExporter.count(), "span suppressed at start must stay suppressed");

        // And the reverse: started while visible, ended after the flag flipped.
        telemetryLevel.setSuppress(false);
        Span visible = tracer.spanBuilder("visible").setSpanKind(SpanKind.INTERNAL).startSpan();
        telemetryLevel.setSuppress(true);
        visible.end();

        assertEquals(1, inMemoryExporter.count(), "span visible at start must stay visible");
        assertEquals("visible", inMemoryExporter.spans.get(0).getName());

        tearDown();
    }

    @Test
    @DisplayName("children of a suppressed root INTERNAL span become roots")
    void childrenOfSuppressedRootBecomeRoots() {
        setUpPipeline();
        telemetryLevel.setSuppress(true);

        // INTERNAL (root, suppressed) → CLIENT. The client has no surviving
        // ancestor, so it must not keep pointing at a span nobody exported.
        Span root = tracer.spanBuilder("root-internal").setSpanKind(SpanKind.INTERNAL).startSpan();
        Span client;
        try (var scope = root.makeCurrent()) {
            client = tracer.spanBuilder("client").setSpanKind(SpanKind.CLIENT).startSpan();
            client.end();
        }
        root.end();

        assertEquals(1, inMemoryExporter.count());
        SpanData clientData = inMemoryExporter.spans.get(0);
        assertEquals("client", clientData.getName());
        assertFalse(clientData.getParentSpanContext().isValid(),
                "client should be a root, not a child of the suppressed span");

        tearDown();
    }
}

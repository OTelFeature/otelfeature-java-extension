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
import io.opentelemetry.api.trace.SpanContext;
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

    /** A policy that is whatever the test says it is. */
    static class TestPolicy implements TelemetryPolicySource {
        private volatile TelemetryPolicy policy = TelemetryPolicy.FULL;

        @Override
        public TelemetryPolicy policy(SpanStart span) {
            return policy;
        }

        /** {@code true} suppresses INTERNAL spans ({@code IO}), {@code false} is {@code FULL}. */
        void setSuppress(boolean s) { this.policy = s ? TelemetryPolicy.IO : TelemetryPolicy.FULL; }

        void setPolicy(TelemetryPolicy policy) { this.policy = policy; }
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

    private TestPolicy policy;
    private SuppressedSpanRegistry registry;
    private InMemoryExporter inMemoryExporter;
    private ReparentingSpanExporter reparentingExporter;
    private SdkTracerProvider tracerProvider;
    private Tracer tracer;

    private void setUpPipeline() {
        setUpPipeline(new SuppressedSpanRegistry());
    }

    private void setUpPipeline(SuppressedSpanRegistry registry) {
        inMemoryExporter = new InMemoryExporter();
        this.registry = registry;
        policy = new TestPolicy();
        reparentingExporter = new ReparentingSpanExporter(inMemoryExporter, registry);

        SpanProcessor simpleProcessor = SimpleSpanProcessor.create(reparentingExporter);
        FilteringSpanProcessor filterProcessor =
                new FilteringSpanProcessor(simpleProcessor, policy, registry);

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
        policy.setSuppress(false);

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
        policy.setSuppress(true);

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
        policy.setSuppress(true);

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
        policy.setSuppress(true);

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

        policy.setSuppress(false);
        tracer.spanBuilder("internal-1").setSpanKind(SpanKind.INTERNAL).startSpan().end();
        assertEquals(1, inMemoryExporter.count());

        policy.setSuppress(true);
        tracer.spanBuilder("internal-2").setSpanKind(SpanKind.INTERNAL).startSpan().end();
        assertEquals(1, inMemoryExporter.count());

        policy.setSuppress(false);
        tracer.spanBuilder("internal-3").setSpanKind(SpanKind.INTERNAL).startSpan().end();
        assertEquals(2, inMemoryExporter.count());

        tearDown();
    }

    @Test
    @DisplayName("all non-INTERNAL span kinds are exported when suppression is active")
    void allNonInternalKindsExported() {
        setUpPipeline();
        policy.setSuppress(true);

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
                new FilteringSpanProcessor(delegate, new TestPolicy(), new SuppressedSpanRegistry());
        assertTrue(processor.isStartRequired());
    }

    @Test
    @DisplayName("isEndRequired always returns true")
    void isEndRequiredAlwaysTrue() {
        CountingProcessor delegate = new CountingProcessor();
        FilteringSpanProcessor processor =
                new FilteringSpanProcessor(delegate, new TestPolicy(), new SuppressedSpanRegistry());
        assertTrue(processor.isEndRequired());
    }

    @Test
    @DisplayName("shutdown delegates to delegate")
    void shutdownDelegates() {
        CountingProcessor delegate = new CountingProcessor();
        FilteringSpanProcessor processor =
                new FilteringSpanProcessor(delegate, new TestPolicy(), new SuppressedSpanRegistry());
        assertTrue(processor.shutdown().isSuccess());
    }

    @Test
    @DisplayName("forceFlush delegates to delegate")
    void forceFlushDelegates() {
        CountingProcessor delegate = new CountingProcessor();
        FilteringSpanProcessor processor =
                new FilteringSpanProcessor(delegate, new TestPolicy(), new SuppressedSpanRegistry());
        assertTrue(processor.forceFlush().isSuccess());
    }

    @Test
    @DisplayName("ReparentingSpanExporter is transparent when registry is empty")
    void reparentingExporterTransparentWhenEmpty() {
        setUpPipeline();
        // No suppression active → registry empty → exporter should passthrough
        policy.setSuppress(false);
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
        policy.setSuppress(true);
        Span internal = tracer.spanBuilder("internal").setSpanKind(SpanKind.INTERNAL).startSpan();
        policy.setSuppress(false);
        internal.end();

        assertEquals(0, inMemoryExporter.count(), "span suppressed at start must stay suppressed");

        // And the reverse: started while visible, ended after the flag flipped.
        policy.setSuppress(false);
        Span visible = tracer.spanBuilder("visible").setSpanKind(SpanKind.INTERNAL).startSpan();
        policy.setSuppress(true);
        visible.end();

        assertEquals(1, inMemoryExporter.count(), "span visible at start must stay visible");
        assertEquals("visible", inMemoryExporter.spans.get(0).getName());

        tearDown();
    }

    @Test
    @DisplayName("children of a suppressed root INTERNAL span become roots")
    void childrenOfSuppressedRootBecomeRoots() {
        setUpPipeline();
        policy.setSuppress(true);

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

    // ----------------------------------------------------------------------
    // OFF
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("OFF drops every span kind")
    void offDropsEveryKind() {
        setUpPipeline();
        policy.setPolicy(TelemetryPolicy.DISABLED);

        for (SpanKind kind : SpanKind.values()) {
            tracer.spanBuilder("span-" + kind.name()).setSpanKind(kind).startSpan().end();
        }

        assertEquals(0, inMemoryExporter.count(), "nothing may be exported while OFF");

        tearDown();
    }

    @Test
    @DisplayName("OFF drops a whole trace, root and children alike")
    void offDropsWholeTrace() {
        setUpPipeline();
        policy.setPolicy(TelemetryPolicy.DISABLED);

        Span server = tracer.spanBuilder("server").setSpanKind(SpanKind.SERVER).startSpan();
        try (var s1 = server.makeCurrent()) {
            Span internal = tracer.spanBuilder("internal").setSpanKind(SpanKind.INTERNAL).startSpan();
            try (var s2 = internal.makeCurrent()) {
                tracer.spanBuilder("client").setSpanKind(SpanKind.CLIENT).startSpan().end();
            }
            internal.end();
        }
        server.end();

        assertEquals(0, inMemoryExporter.count());

        tearDown();
    }

    @Test
    @DisplayName("switching OFF and back to FULL takes effect on the next span")
    void offAndBackOn() {
        setUpPipeline();

        policy.setPolicy(TelemetryPolicy.DISABLED);
        tracer.spanBuilder("dropped").setSpanKind(SpanKind.SERVER).startSpan().end();
        assertEquals(0, inMemoryExporter.count());

        policy.setPolicy(TelemetryPolicy.FULL);
        tracer.spanBuilder("kept").setSpanKind(SpanKind.SERVER).startSpan().end();
        assertEquals(1, inMemoryExporter.count());
        assertEquals("kept", inMemoryExporter.spans.get(0).getName());

        tearDown();
    }

    @Test
    @DisplayName("under OFF a SERVER span's fate is still decided at onStart")
    void offDecisionIsStableAcrossTheSpanLifetime() {
        setUpPipeline();

        // Started while OFF, ended after tracing came back: stays dropped.
        policy.setPolicy(TelemetryPolicy.DISABLED);
        Span dropped = tracer.spanBuilder("dropped").setSpanKind(SpanKind.SERVER).startSpan();
        policy.setPolicy(TelemetryPolicy.FULL);
        dropped.end();
        assertEquals(0, inMemoryExporter.count(), "span suppressed at start must stay suppressed");

        // Started while FULL, ended after the switch went OFF: stays exported.
        Span kept = tracer.spanBuilder("kept").setSpanKind(SpanKind.SERVER).startSpan();
        policy.setPolicy(TelemetryPolicy.DISABLED);
        kept.end();
        assertEquals(1, inMemoryExporter.count(), "span visible at start must stay visible");
        assertEquals("kept", inMemoryExporter.spans.get(0).getName());

        tearDown();
    }

    @Test
    @DisplayName("a trace that resumes after OFF re-parents past the spans dropped while OFF")
    void traceResumesAfterOff() {
        setUpPipeline();

        // SERVER started while FULL; then OFF drops the INTERNAL started under
        // it; tracing comes back before the CLIENT starts. The client must hang
        // off the server, not off the span nobody exported.
        Span server = tracer.spanBuilder("server").setSpanKind(SpanKind.SERVER).startSpan();
        try (var s1 = server.makeCurrent()) {
            policy.setPolicy(TelemetryPolicy.DISABLED);
            Span internal = tracer.spanBuilder("internal").setSpanKind(SpanKind.INTERNAL).startSpan();
            policy.setPolicy(TelemetryPolicy.FULL);
            try (var s2 = internal.makeCurrent()) {
                tracer.spanBuilder("client").setSpanKind(SpanKind.CLIENT).startSpan().end();
            }
            internal.end();
        }
        server.end();

        assertEquals(2, inMemoryExporter.count());
        SpanData clientData = inMemoryExporter.spans.get(0);
        SpanData serverData = inMemoryExporter.spans.get(1);
        assertEquals("client", clientData.getName());
        assertEquals(serverData.getSpanId(), clientData.getParentSpanContext().getSpanId(),
                "client should be re-parented to server");

        tearDown();
    }

    // ----------------------------------------------------------------------
    // Registry bound under OFF
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("under OFF a long-running span stays dropped however many spans pass by")
    void longRunningSpanSurvivesRegistrySweeps() {
        // Tiny limits, so the 1000 spans below force dozens of sweeps.
        setUpPipeline(new SuppressedSpanRegistry(10, 10_000));
        policy.setPolicy(TelemetryPolicy.DISABLED);

        Span longRunning = tracer.spanBuilder("long-running").setSpanKind(SpanKind.SERVER).startSpan();
        for (int i = 0; i < 1000; i++) {
            tracer.spanBuilder("short-" + i).setSpanKind(SpanKind.CLIENT).startSpan().end();
        }
        longRunning.end();

        assertEquals(0, inMemoryExporter.count(), "nothing may leak while OFF");
        assertTrue(registry.size() < 30,
                "ended spans should have been swept, size was " + registry.size());

        tearDown();
    }

    @Test
    @DisplayName("a sweep drops ended entries and keeps in-flight ones")
    void sweepKeepsInFlightEntries() {
        SuppressedSpanRegistry registry = new SuppressedSpanRegistry(3, 1000);
        SpanContext root = SpanContext.getInvalid();

        registry.record("inflight", root);
        registry.record("ended-1", root);
        registry.record("ended-2", root);
        registry.markEnded("ended-1");
        registry.markEnded("ended-2");

        registry.record("new", root); // size 3 reaches sweepAt: sweeps first

        assertTrue(registry.isSuppressed("inflight"));
        assertTrue(registry.isSuppressed("new"));
        assertFalse(registry.isSuppressed("ended-1"));
        assertFalse(registry.isSuppressed("ended-2"));
        assertEquals(2, registry.size());
    }

    @Test
    @DisplayName("only spans that never end make the registry clear itself")
    void hardMaxClearsAsLastResort() {
        SuppressedSpanRegistry registry = new SuppressedSpanRegistry(2, 4);
        SpanContext root = SpanContext.getInvalid();

        for (int i = 0; i < 4; i++) {
            registry.record("leaked-" + i, root);
        }
        assertEquals(4, registry.size(), "in-flight entries survive sweeps below hardMax");

        registry.record("next", root); // 4 in flight reaches hardMax: cleared

        assertEquals(1, registry.size());
        assertTrue(registry.isSuppressed("next"));
    }

    // ----------------------------------------------------------------------
    // What reaches the policy
    // ----------------------------------------------------------------------

    /** Records the SpanStart it is asked about; optionally answers from cache. */
    static final class CapturingPolicy implements TelemetryPolicySource {
        final List<SpanStart> seen = new ArrayList<>();
        final TelemetryPolicy cached;

        CapturingPolicy(TelemetryPolicy cached) {
            this.cached = cached;
        }

        @Override
        public TelemetryPolicy policy(SpanStart span) {
            seen.add(span);
            return TelemetryPolicy.FULL;
        }

        @Override
        public TelemetryPolicy cachedPolicy() {
            return cached;
        }
    }

    @Test
    @DisplayName("an evaluated span reaches the policy with its trace id, name, kind, attributes and scope")
    void spanStartReachesThePolicy() {
        CapturingPolicy capturing = new CapturingPolicy(null);
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(new FilteringSpanProcessor(new CountingProcessor(), capturing,
                        new SuppressedSpanRegistry()))
                .build();
        Span span = provider.tracerBuilder("io.opentelemetry.tomcat-10.0").setInstrumentationVersion("2.10.0")
                .build().spanBuilder("GET /health").setSpanKind(SpanKind.SERVER)
                .setAttribute("url.path", "/health").startSpan();
        span.end();
        provider.shutdown();

        assertEquals(1, capturing.seen.size());
        SpanStart start = capturing.seen.get(0);
        assertEquals(span.getSpanContext().getTraceId(), start.traceId());
        assertEquals("GET /health", start.name());
        assertEquals(SpanKind.SERVER, start.kind());
        assertEquals("/health", start.attributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("url.path")));
        assertEquals("io.opentelemetry.tomcat-10.0", start.scope().getName());
        assertEquals("2.10.0", start.scope().getVersion());
    }

    @Test
    @DisplayName("a span-independent policy is used without building a SpanStart")
    void cachedPolicySkipsTheSpan() {
        CapturingPolicy capturing = new CapturingPolicy(TelemetryPolicy.IO);
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(new FilteringSpanProcessor(new CountingProcessor(), capturing,
                        new SuppressedSpanRegistry()))
                .build();
        provider.get("test").spanBuilder("x").startSpan().end();
        provider.shutdown();

        assertTrue(capturing.seen.isEmpty(), "policy(SpanStart) must not be asked when cachedPolicy answers");
    }
}

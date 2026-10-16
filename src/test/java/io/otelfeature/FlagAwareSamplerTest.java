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

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link FlagAwareSampler}, and the two enforcement modes side by side.
 *
 * <p>The pipeline is wired the way {@link OtelfeatureCustomizer} wires it for
 * each {@link Enforcement}, over the real SDK: a parent-based sampler (the
 * agent's default), {@link FilteringSpanProcessor} and
 * {@link ReparentingSpanExporter} in front of an in-memory exporter, plus a
 * second, bare processor that counts every {@code onStart} it sees.
 */
@DisplayName("FlagAwareSampler and the enforcement modes")
class FlagAwareSamplerTest {

    private static final AttributeKey<String> DB_SYSTEM = AttributeKey.stringKey("db.system.name");

    private FilteringSpanProcessorTest.TestPolicy policy;
    private FilteringSpanProcessorTest.InMemoryExporter exporter;
    private FilteringSpanProcessorTest.CountingProcessor counting;
    private SdkTracerProvider tracerProvider;
    private Tracer tracer;

    private void setUp(Enforcement mode) {
        setUp(mode, mode == Enforcement.SAMPLER);
    }

    /**
     * @param mode the configured enforcement
     * @param installSampler whether the agent actually applied the sampler
     *     customizer (only meaningful in sampler mode)
     */
    private void setUp(Enforcement mode, boolean installSampler) {
        policy = new FilteringSpanProcessorTest.TestPolicy();
        exporter = new FilteringSpanProcessorTest.InMemoryExporter();
        counting = new FilteringSpanProcessorTest.CountingProcessor();
        SuppressedSpanRegistry registry = new SuppressedSpanRegistry();

        Sampler agentDefault = Sampler.parentBased(Sampler.alwaysOn());
        Sampler sampler = installSampler ? new FlagAwareSampler(agentDefault, policy) : agentDefault;

        tracerProvider = SdkTracerProvider.builder()
                .setSampler(sampler)
                .addSpanProcessor(new FilteringSpanProcessor(
                        SimpleSpanProcessor.create(new ReparentingSpanExporter(exporter, registry)),
                        OtelfeatureCustomizer.processorPolicy(policy, mode, () -> installSampler),
                        registry))
                .addSpanProcessor(counting)
                .build();
        tracer = tracerProvider.get("test");
    }

    @AfterEach
    void tearDown() {
        if (tracerProvider != null) {
            tracerProvider.shutdown();
        }
    }

    private List<String> exportedNames() {
        return exporter.spans.stream().map(SpanData::getName).collect(Collectors.toList());
    }

    /** A health check: a SERVER root, nothing under it. */
    private void healthCheck() {
        tracer.spanBuilder("GET /health").setSpanKind(SpanKind.SERVER).startSpan().end();
    }

    /** A request with a DB call as its leaf. */
    private void requestWithDbLeaf() {
        Span server = tracer.spanBuilder("GET /orders").setSpanKind(SpanKind.SERVER).startSpan();
        try (var s = server.makeCurrent()) {
            tracer.spanBuilder("SELECT orders")
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute(DB_SYSTEM, "postgresql")
                    .startSpan()
                    .end();
        }
        server.end();
    }

    // ----------------------------------------------------------------------
    // The same behaviour in both modes
    // ----------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Enforcement.class)
    @DisplayName("FULL: health checks and DB leaves are exported")
    void fullExportsEverything(Enforcement mode) {
        setUp(mode);

        healthCheck();
        requestWithDbLeaf();

        assertEquals(List.of("GET /health", "SELECT orders", "GET /orders"), exportedNames());
    }

    @ParameterizedTest
    @EnumSource(Enforcement.class)
    @DisplayName("OFF: no health check root and no DB leaf is exported")
    void offExportsNothing(Enforcement mode) {
        setUp(mode);
        policy.setPolicy(TelemetryPolicy.DISABLED);

        healthCheck();
        requestWithDbLeaf();

        assertEquals(List.of(), exportedNames());
    }

    @ParameterizedTest
    @EnumSource(Enforcement.class)
    @DisplayName("OFF: an incoming request (remote parent) is dropped too")
    void offDropsRemoteParentedRequests(Enforcement mode) {
        setUp(mode);
        policy.setPolicy(TelemetryPolicy.DISABLED);

        SpanContext upstream = SpanContext.createFromRemoteParent(
                "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331",
                TraceFlags.getSampled(), TraceState.getDefault());
        Context incoming = Context.root().with(Span.wrap(upstream));
        tracer.spanBuilder("GET /orders").setSpanKind(SpanKind.SERVER).setParent(incoming).startSpan().end();

        assertEquals(List.of(), exportedNames());
    }

    @ParameterizedTest
    @EnumSource(Enforcement.class)
    @DisplayName("IO: INTERNAL spans are dropped and their children re-parented, in both modes")
    void ioIsTheProcessorsJobInBothModes(Enforcement mode) {
        setUp(mode);
        policy.setPolicy(TelemetryPolicy.IO);

        Span server = tracer.spanBuilder("server").setSpanKind(SpanKind.SERVER).startSpan();
        try (var s1 = server.makeCurrent()) {
            Span internal = tracer.spanBuilder("controller").setSpanKind(SpanKind.INTERNAL).startSpan();
            try (var s2 = internal.makeCurrent()) {
                tracer.spanBuilder("db").setSpanKind(SpanKind.CLIENT).startSpan().end();
            }
            internal.end();
        }
        server.end();

        assertEquals(List.of("db", "server"), exportedNames());
        assertEquals(exporter.spans.get(1).getSpanId(), exporter.spans.get(0).getParentSpanId(),
                "db should be re-parented to server");
        assertEquals(3, counting.onStartCount.get(), "IO never drops at the sampler");
    }

    // ----------------------------------------------------------------------
    // What only the sampler does
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("sampler mode: a dropped span never reaches any processor's onStart")
    void droppedSpanNeverStarts() {
        setUp(Enforcement.SAMPLER);
        policy.setPolicy(TelemetryPolicy.DISABLED);

        healthCheck();
        requestWithDbLeaf();

        assertEquals(0, counting.onStartCount.get(), "OFF in sampler mode: zero spans reach processors");
        assertEquals(0, counting.onEndCount.get());
    }

    @Test
    @DisplayName("processor mode, for contrast: OFF spans are still started, then dropped")
    void processorModeStillStartsSpans() {
        setUp(Enforcement.PROCESSOR);
        policy.setPolicy(TelemetryPolicy.DISABLED);

        requestWithDbLeaf();

        assertEquals(2, counting.onStartCount.get());
        assertEquals(List.of(), exportedNames());
    }

    @Test
    @DisplayName("sampler mode: a dropped root sends sampled=0 to whatever it calls")
    void droppedRootPropagatesNotSampled() {
        setUp(Enforcement.SAMPLER);
        policy.setPolicy(TelemetryPolicy.DISABLED);

        Span root = tracer.spanBuilder("GET /orders").setSpanKind(SpanKind.SERVER).startSpan();
        SpanContext context = root.getSpanContext();
        root.end();

        assertTrue(context.isValid(), "the context still propagates");
        assertFalse(context.isSampled(), "but tells downstream not to sample");
    }

    @Test
    @DisplayName("sampler mode: a trace sampled before the flip finishes whole")
    void sampledTraceFinishesWhole() {
        setUp(Enforcement.SAMPLER);

        Span server = tracer.spanBuilder("server").setSpanKind(SpanKind.SERVER).startSpan();
        policy.setPolicy(TelemetryPolicy.DISABLED);
        try (var s = server.makeCurrent()) {
            tracer.spanBuilder("db").setSpanKind(SpanKind.CLIENT).startSpan().end();
        }
        server.end();

        assertEquals(List.of("db", "server"), exportedNames(),
                "a local parent decides for its children, whatever the flag says now");
    }

    @Test
    @DisplayName("sampler mode: a trace dropped before the flip stays dropped")
    void droppedTraceStaysDropped() {
        setUp(Enforcement.SAMPLER);
        policy.setPolicy(TelemetryPolicy.DISABLED);

        Span server = tracer.spanBuilder("server").setSpanKind(SpanKind.SERVER).startSpan();
        policy.setPolicy(TelemetryPolicy.FULL);
        try (var s = server.makeCurrent()) {
            tracer.spanBuilder("db").setSpanKind(SpanKind.CLIENT).startSpan().end();
        }
        server.end();

        assertEquals(List.of(), exportedNames(), "no orphaned child of a root nobody exported");
        tracer.spanBuilder("next").setSpanKind(SpanKind.SERVER).startSpan().end();
        assertEquals(List.of("next"), exportedNames(), "the next trace is sampled again");
    }

    @Test
    @DisplayName("sampler mode: children of a dropped root stay dropped with a non-parent-based delegate")
    void childrenStayDroppedWithAlwaysOnDelegate() {
        policy = new FilteringSpanProcessorTest.TestPolicy();
        exporter = new FilteringSpanProcessorTest.InMemoryExporter();
        counting = new FilteringSpanProcessorTest.CountingProcessor();
        // OTEL_TRACES_SAMPLER=always_on: the delegate ignores the parent.
        tracerProvider = SdkTracerProvider.builder()
                .setSampler(new FlagAwareSampler(Sampler.alwaysOn(), policy))
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .addSpanProcessor(counting)
                .build();
        tracer = tracerProvider.get("test");
        policy.setPolicy(TelemetryPolicy.DISABLED);

        requestWithDbLeaf();

        assertEquals(0, counting.onStartCount.get(), "the DB leaf must not be sampled as an orphan");
    }

    @Test
    @DisplayName("sampler mode: flipping IO to OFF mid-trace never exports more than IO did")
    void ioToOffMidTraceKeepsInternalSuppressed() {
        setUp(Enforcement.SAMPLER);
        policy.setPolicy(TelemetryPolicy.IO);

        Span server = tracer.spanBuilder("server").setSpanKind(SpanKind.SERVER).startSpan();
        policy.setPolicy(TelemetryPolicy.DISABLED);
        try (var s1 = server.makeCurrent()) {
            Span internal = tracer.spanBuilder("controller").setSpanKind(SpanKind.INTERNAL).startSpan();
            try (var s2 = internal.makeCurrent()) {
                tracer.spanBuilder("db").setSpanKind(SpanKind.CLIENT).startSpan().end();
            }
            internal.end();
        }
        server.end();

        assertEquals(List.of("db", "server"), exportedNames(),
                "the in-flight trace finishes, but its INTERNAL span stays suppressed");
    }

    @Test
    @DisplayName("sampler mode without the sampler installed: the processor still enforces OFF")
    void processorEnforcesOffWhenSamplerMissing() {
        setUp(Enforcement.SAMPLER, false);
        policy.setPolicy(TelemetryPolicy.DISABLED);

        healthCheck();
        requestWithDbLeaf();

        assertEquals(List.of(), exportedNames(), "OFF must not silently do nothing");
    }

    @ParameterizedTest
    @EnumSource(Enforcement.class)
    @DisplayName("the processor's policy read fails open")
    void processorPolicyFailsOpen(Enforcement mode) {
        TelemetryPolicySource throwing = span -> {
            throw new IllegalStateException("provider exploded");
        };

        assertEquals(TelemetryPolicy.FULL,
                OtelfeatureCustomizer.processorPolicy(throwing, mode, () -> true).policy(null));
    }

    @Test
    @DisplayName("sampler mode, enabled: a sampled incoming request is sampled")
    void sampledRemoteParentIsSampledWhileEnabled() {
        setUp(Enforcement.SAMPLER);

        SpanContext upstream = SpanContext.createFromRemoteParent(
                "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331",
                TraceFlags.getSampled(), TraceState.getDefault());
        tracer.spanBuilder("GET /orders").setSpanKind(SpanKind.SERVER)
                .setParent(Context.root().with(Span.wrap(upstream))).startSpan().end();

        assertEquals(List.of("GET /orders"), exportedNames());
        assertEquals("b7ad6b7169203331", exporter.spans.get(0).getParentSpanId());
    }

    // ----------------------------------------------------------------------
    // The sampler on its own
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("a drop keeps the parent's trace state")
    void dropKeepsTraceState() {
        FlagAwareSampler sampler = new FlagAwareSampler(Sampler.alwaysOn(), span -> TelemetryPolicy.DISABLED);
        TraceState state = TraceState.builder().put("vendor", "value").build();
        SpanContext upstream = SpanContext.createFromRemoteParent(
                "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331", TraceFlags.getSampled(), state);

        SamplingResult result = sampler.shouldSample(
                Context.root().with(Span.wrap(upstream)), upstream.getTraceId(), "GET /orders",
                SpanKind.SERVER, Attributes.empty(), List.of());

        assertEquals(SamplingDecision.DROP, result.getDecision());
        assertEquals(state, result.getUpdatedTraceState(state));
    }

    @Test
    @DisplayName("links are handed to the delegate unchanged")
    void linksReachTheDelegate() {
        List<LinkData> seen = new java.util.ArrayList<>();
        Sampler recording = new Sampler() {
            @Override
            public SamplingResult shouldSample(Context parentContext, String traceId, String name,
                    SpanKind spanKind, Attributes attributes, List<LinkData> parentLinks) {
                seen.addAll(parentLinks);
                return SamplingResult.recordAndSample();
            }

            @Override
            public String getDescription() {
                return "recording";
            }
        };
        LinkData link = LinkData.create(SpanContext.create(
                "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331",
                TraceFlags.getSampled(), TraceState.getDefault()));

        new FlagAwareSampler(recording, span -> TelemetryPolicy.FULL).shouldSample(
                Context.root(), "0af7651916cd43dd8448eb211c80319c", "root", SpanKind.CONSUMER,
                Attributes.empty(), List.of(link));

        assertEquals(List.of(link), seen);
    }

    @Test
    @DisplayName("a policy that cannot be read delegates (fail open)")
    void unreadablePolicyDelegates() {
        FlagAwareSampler sampler = new FlagAwareSampler(Sampler.alwaysOn(), span -> {
            throw new IllegalStateException("provider exploded");
        });

        assertEquals(SamplingDecision.RECORD_AND_SAMPLE, sampler.shouldSample(
                Context.root(), "0af7651916cd43dd8448eb211c80319c", "root", SpanKind.SERVER,
                Attributes.empty(), List.of()).getDecision());
    }

    @Test
    @DisplayName("enabled: the delegate decides, including to drop")
    void enabledDelegates() {
        FlagAwareSampler sampler = new FlagAwareSampler(Sampler.alwaysOff(), span -> TelemetryPolicy.FULL);

        assertEquals(SamplingDecision.DROP, sampler.shouldSample(
                Context.root(), "0af7651916cd43dd8448eb211c80319c", "root", SpanKind.SERVER,
                Attributes.empty(), List.of()).getDecision());
    }

    @Test
    @DisplayName("getDescription names the delegate")
    void description() {
        FlagAwareSampler sampler = new FlagAwareSampler(Sampler.alwaysOn(), span -> TelemetryPolicy.FULL);

        assertEquals("FlagAwareSampler{delegate=AlwaysOnSampler}", sampler.getDescription());
    }

    @ParameterizedTest
    @CsvSource({"processor,PROCESSOR", "PROCESSOR,PROCESSOR", "sampler,SAMPLER", "' Sampler ',SAMPLER",
            "'',PROCESSOR", "both,PROCESSOR"})
    @DisplayName("OTELFEATURE_ENFORCEMENT parses case-insensitively, defaulting to processor")
    void parsesEnforcement(String value, Enforcement expected) {
        assertEquals(expected, Enforcement.parse(value));
    }

    @Test
    @DisplayName("an unset OTELFEATURE_ENFORCEMENT is processor")
    void unsetEnforcementIsProcessor() {
        assertEquals(Enforcement.PROCESSOR, Enforcement.parse(null));
    }

    @Test
    @DisplayName("a root decision reaches the policy with trace id, name, kind and attributes (no scope)")
    void samplerHandsTheSpanToThePolicy() {
        FilteringSpanProcessorTest.CapturingPolicy capturing = new FilteringSpanProcessorTest.CapturingPolicy(null);
        Attributes start = Attributes.of(AttributeKey.stringKey("url.path"), "/health");

        new FlagAwareSampler(Sampler.alwaysOn(), capturing).shouldSample(
                Context.root(), "0af7651916cd43dd8448eb211c80319c", "GET /health", SpanKind.SERVER,
                start, List.of());

        assertEquals(1, capturing.seen.size());
        SpanStart span = capturing.seen.get(0);
        assertEquals("0af7651916cd43dd8448eb211c80319c", span.traceId());
        assertEquals("GET /health", span.name());
        assertEquals(SpanKind.SERVER, span.kind());
        assertEquals(start, span.attributes());
        assertEquals(null, span.scope(), "a Sampler is not told the scope");
    }

    @Test
    @DisplayName("a span-independent policy answers the sampler without a SpanStart")
    void samplerUsesCachedPolicy() {
        FilteringSpanProcessorTest.CapturingPolicy capturing =
                new FilteringSpanProcessorTest.CapturingPolicy(TelemetryPolicy.DISABLED);

        assertEquals(SamplingDecision.DROP, new FlagAwareSampler(Sampler.alwaysOn(), capturing).shouldSample(
                Context.root(), "0af7651916cd43dd8448eb211c80319c", "root", SpanKind.SERVER,
                Attributes.empty(), List.of()).getDecision());
        assertTrue(capturing.seen.isEmpty());
    }

    @Test
    @DisplayName("only a CLIENT span with a DB system counts as a non-propagating leaf")
    void databaseLeaf() {
        Attributes db = Attributes.of(AttributeKey.stringKey("db.system.name"), "postgresql");
        Attributes oldDb = Attributes.of(AttributeKey.stringKey("db.system"), "postgresql");
        assertTrue(FlagAwareSampler.isDatabaseLeaf(SpanKind.CLIENT, db));
        assertTrue(FlagAwareSampler.isDatabaseLeaf(SpanKind.CLIENT, oldDb));
        assertFalse(FlagAwareSampler.isDatabaseLeaf(SpanKind.INTERNAL, db));
        assertFalse(FlagAwareSampler.isDatabaseLeaf(SpanKind.CLIENT, Attributes.empty()));
        assertFalse(FlagAwareSampler.isDatabaseLeaf(SpanKind.CLIENT, null));
    }
}

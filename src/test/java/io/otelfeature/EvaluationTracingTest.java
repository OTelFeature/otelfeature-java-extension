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

import dev.openfeature.contrib.providers.flagd.Config;
import dev.openfeature.contrib.providers.flagd.FlagdOptions;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import dev.openfeature.sdk.OpenFeatureAPI;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The opt-in OpenFeature OpenTelemetry hook for the extension's own
 * evaluations (UC-007 BR-002 to BR-004, TC-005 item 10): off by default, on
 * only for {@code true}, on the extension's isolated OpenFeature API only, and
 * never recording exceptions. Driven through a real flagd provider
 * ({@code FILE} resolver) and the processor pipeline.
 */
@DisplayName("OTELFEATURE_OPENFEATURE_TRACING_HOOK")
class EvaluationTracingTest {

    private static final long TIMEOUT_MILLIS = 15_000;

    /** The 1.28 names {@code dev.openfeature.contrib.hooks:otel} 3.3.1 writes (UC-007 BR-004). */
    private static final String EVENT = "feature_flag";
    private static final AttributeKey<String> FLAG_KEY = AttributeKey.stringKey("feature_flag.key");
    private static final AttributeKey<String> VARIANT = AttributeKey.stringKey("feature_flag.variant");
    private static final AttributeKey<String> PROVIDER = AttributeKey.stringKey("feature_flag.provider_name");

    /** otelEnabled and telemetryLevel static, recordSpan targeted: one evaluation per span. */
    private static final String TARGETED_RECORD_SPAN = """
            {
              "flags": {
                "otelEnabled": { "state": "ENABLED", "defaultVariant": "on",
                  "variants": { "on": true, "off": false } },
                "telemetryLevel": { "state": "ENABLED", "defaultVariant": "full",
                  "variants": { "full": "FULL", "io": "IO", "off": "OFF" } },
                "recordSpan": { "state": "ENABLED", "defaultVariant": "on",
                  "variants": { "on": true, "off": false },
                  "targeting": { "if": [ { "==": [ { "var": "url.path" }, "/never" ] }, "off", null ] } }
              }
            }
            """;

    /** recordSpan with string variants: every evaluation fails with a type mismatch. */
    private static final String BROKEN_RECORD_SPAN = """
            {
              "flags": {
                "otelEnabled": { "state": "ENABLED", "defaultVariant": "on",
                  "variants": { "on": true, "off": false } },
                "telemetryLevel": { "state": "ENABLED", "defaultVariant": "full",
                  "variants": { "full": "FULL", "io": "IO", "off": "OFF" } },
                "recordSpan": { "state": "ENABLED", "defaultVariant": "on",
                  "variants": { "on": "yes", "off": "no" } }
              }
            }
            """;

    @TempDir
    Path tempDir;

    private FlagdTelemetryPolicy policy;
    private SdkTracerProvider tracerProvider;
    private InMemorySpanExporter exporter;

    @AfterEach
    void tearDown() {
        if (tracerProvider != null) {
            tracerProvider.shutdown();
        }
        if (policy != null) {
            policy.shutdown();
        }
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "false", "1", "yes", "on", "truee"})
    @DisplayName("is off unless the value is true")
    void offUnlessTrue(String value) {
        assertFalse(EvaluationTracing.enabled(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", " True "})
    @DisplayName("is on for true, ignoring case and surrounding whitespace")
    void onForTrue(String value) {
        assertTrue(EvaluationTracing.enabled(value));
    }

    @Test
    @DisplayName("off: a targeted evaluation adds no event")
    void offRecordsNothing() throws IOException {
        start(TARGETED_RECORD_SPAN, false);

        request();

        assertTrue(exporter.getFinishedSpanItems().stream().allMatch(s -> s.getEvents().isEmpty()),
                "no span may carry an event without the opt-in");
    }

    @Test
    @DisplayName("on: a child span's evaluation lands on its parent, with the 1.28 names")
    void onRecordsOnTheParent() throws IOException {
        start(TARGETED_RECORD_SPAN, true);

        request();

        Map<String, SpanData> spans = byName();
        List<EventData> events = flagEvents(spans.get("GET /weather"));
        assertEquals(1, events.size(), "one recordSpan evaluation, for the child: " + events);
        Attributes attributes = events.get(0).getAttributes();
        assertEquals("recordSpan", attributes.get(FLAG_KEY));
        assertEquals("on", attributes.get(VARIANT));
        assertEquals("flagd", attributes.get(PROVIDER));
        assertTrue(spans.get("SELECT forecast").getEvents().isEmpty(), "the child itself gets nothing");
    }

    @Test
    @DisplayName("on: a new root records nothing (no current span at evaluation)")
    void onRecordsNothingForRoots() throws IOException {
        start(TARGETED_RECORD_SPAN, true);

        for (int i = 0; i < 5; i++) {
            tracer().spanBuilder("GET /health").setSpanKind(SpanKind.SERVER).setNoParent().startSpan().end();
        }

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(5, spans.size());
        assertTrue(spans.stream().allMatch(s -> s.getEvents().isEmpty()));

        // Positive control: the hook is attached and roots are evaluated - a
        // root started while another span is current puts its event there.
        exporter.reset();
        Span outer = tracer().spanBuilder("outer").setSpanKind(SpanKind.SERVER).setNoParent().startSpan();
        try (var s = outer.makeCurrent()) {
            tracer().spanBuilder("inner-root").setNoParent().startSpan().end();
        }
        outer.end();
        assertEquals(List.of("recordSpan"), flagEvents(byName().get("outer")).stream()
                .map(e -> e.getAttributes().get(FLAG_KEY)).collect(Collectors.toList()));
    }

    @Test
    @DisplayName("on: a failing evaluation records no exception on the application's span")
    void onRecordsNoExceptions() throws IOException {
        CountingHook hook = new CountingHook();
        start(BROKEN_RECORD_SPAN, hook);
        assertTrue(waitFor(() -> policy.recordEvaluatedPerSpan()), "recordSpan should be evaluated per span");
        int before = hook.errors.get();

        request();

        assertTrue(hook.errors.get() - before >= 2, "the SDK must have reached error() for both spans");
        Map<String, SpanData> spans = byName();
        assertEquals(2, spans.size(), "the failing flag falls back to recording");
        assertTrue(spans.values().stream().allMatch(s -> s.getEvents().isEmpty()),
                "no exception and no evaluation event: " + spans.get("GET /weather").getEvents());
    }

    @Test
    @DisplayName("the error callback leaves the current span alone")
    void errorCallbackIsANoOp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
        Span span = tracer().spanBuilder("app").startSpan();
        try (var s = span.makeCurrent()) {
            new EvaluationTracing.ExtensionTracesHook().error(null, new IllegalStateException("flagd down"), Map.of());
        }
        span.end();

        assertTrue(exporter.getFinishedSpanItems().get(0).getEvents().isEmpty());
    }

    @Test
    @DisplayName("on: the application's OpenFeature API gets no hook and records nothing")
    void onLeavesTheApplicationsApiAlone() throws IOException {
        start(TARGETED_RECORD_SPAN, true);
        OpenFeatureAPI application = OpenFeatureAPI.getInstance();

        assertTrue(application.getHooks().isEmpty(), "no API-wide hook on the application's OpenFeature");
        Span span = tracer().spanBuilder("app").setNoParent().startSpan();
        try (var s = span.makeCurrent()) {
            application.getClient().getBooleanValue("weatherUpstreamFailure", false);
        }
        span.end();

        SpanData app = byName().get("app");
        assertTrue(app.getEvents().isEmpty(), "the application's own evaluation must not be recorded by us: "
                + app.getEvents());
    }

    /** GET /weather (SERVER), with a JDBC child started while it is current. */
    private void request() {
        Span server = tracer().spanBuilder("GET /weather").setSpanKind(SpanKind.SERVER)
                .setAttribute("url.path", "/weather").setNoParent().startSpan();
        try (var s = server.makeCurrent()) {
            tracer().spanBuilder("SELECT forecast").setSpanKind(SpanKind.CLIENT).startSpan().end();
        }
        server.end();
    }

    private void start(String flags, boolean tracingHook) throws IOException {
        start(flags, tracingHook ? EvaluationTracing.hook() : null);
    }

    private void start(String flags, Hook<?> tracingHook) throws IOException {
        Path file = tempDir.resolve("flags.json");
        Files.writeString(file, flags, StandardCharsets.UTF_8);
        policy = new FlagdTelemetryPolicy("telemetryLevel", "otelEnabled", "recordSpan", "traceSampleRatio",
                TelemetryPolicy.FULL,
                FlagdOptions.builder()
                        .resolverType(Config.Resolver.FILE)
                        .offlineFlagSourcePath(file.toString())
                        .offlinePollIntervalMs(50)
                        .build(),
                tracingHook);
        policy.setResource(Resource.create(Attributes.builder().put("service.name", "weather").build()));
        assertTrue(waitFor(() -> policy.providerReady()), "provider never became ready");

        exporter = InMemorySpanExporter.create();
        SuppressedSpanRegistry registry = new SuppressedSpanRegistry();
        tracerProvider = SdkTracerProvider.builder()
                .setSampler(Sampler.parentBased(Sampler.alwaysOn()))
                .addSpanProcessor(new FilteringSpanProcessor(
                        SimpleSpanProcessor.create(new ReparentingSpanExporter(exporter, registry)),
                        OtelfeatureCustomizer.processorPolicy(policy, Enforcement.PROCESSOR, () -> false),
                        registry))
                .build();
    }

    /** The extension's hook, counting the error callbacks the SDK makes. */
    @SuppressWarnings("rawtypes")
    private static final class CountingHook extends EvaluationTracing.ExtensionTracesHook {
        final AtomicInteger errors = new AtomicInteger();

        @Override
        public void error(HookContext ctx, Exception error, Map hints) {
            errors.incrementAndGet();
            super.error(ctx, error, hints);
        }
    }

    private Tracer tracer() {
        return tracerProvider.get("test");
    }

    private Map<String, SpanData> byName() {
        return exporter.getFinishedSpanItems().stream().collect(Collectors.toMap(SpanData::getName, s -> s));
    }

    private static List<EventData> flagEvents(SpanData span) {
        return span.getEvents().stream().filter(e -> EVENT.equals(e.getName())).collect(Collectors.toList());
    }

    private static boolean waitFor(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}

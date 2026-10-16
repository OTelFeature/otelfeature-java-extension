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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openfeature.contrib.providers.flagd.Config;
import dev.openfeature.contrib.providers.flagd.FlagdOptions;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@code recordSpan} end to end: a real flagd provider ({@code FILE} resolver),
 * the real SDK, and the pipeline wired as {@link OtelfeatureCustomizer} wires it
 * for each {@link Enforcement}.
 */
@DisplayName("recordSpan against a real flagd provider, in both modes")
class RecordSpanIntegrationTest {

    private static final long TIMEOUT_MILLIS = 15_000;

    private static final String GLOBAL_ON = """
                "otelEnabled": {
                  "state": "ENABLED", "defaultVariant": "on",
                  "variants": { "on": true, "off": false }
                },
                "telemetryLevel": {
                  "state": "ENABLED", "defaultVariant": "full",
                  "variants": { "full": "FULL", "io": "IO", "off": "OFF" }
                }""";

    /**
     * @param recordTargeting the recordSpan targeting rule, or {@code null} for
     *     a static recordSpan
     */
    private static String flags(String globals, String recordTargeting) {
        String targeting = recordTargeting == null ? "" : ",\n      \"targeting\": " + recordTargeting;
        return """
                {
                  "$schema": "https://flagd.dev/schema/v0/flags.json",
                  "flags": {
                    %s,
                    "recordSpan": {
                      "state": "ENABLED", "defaultVariant": "on",
                      "variants": { "on": true, "off": false }%s
                    }
                  }
                }
                """.formatted(globals, targeting);
    }

    /** The example rule from #27: weather's health checks go dark. */
    private static final String HEALTH = """
            { "if": [ { "and": [
                { "==": [ { "var": "service.name" }, "weather" ] },
                { "==": [ { "var": "url.path" }, "/health" ] } ] }, "off", null ] }""";

    /** Demo 2's DB rule, on the JDBC instrumentation scope. */
    private static final String JDBC_SCOPE = """
            { "if": [ { "==": [ { "var": "otel.scope.name" }, "io.opentelemetry.jdbc" ] }, "off", null ] }""";

    /** The same intent, on a key the sampler can see. */
    private static final String DB_SYSTEM = """
            { "if": [ { "==": [ { "var": "db.system.name" }, "postgresql" ] }, "off", null ] }""";

    @TempDir
    Path tempDir;

    private FlagdTelemetryPolicy policy;
    private SdkTracerProvider tracerProvider;
    private FilteringSpanProcessorTest.InMemoryExporter exporter;
    private FilteringSpanProcessorTest.CountingProcessor counting;

    private Path file;
    private long lastModified;

    /** Rewrites the flag file, visibly newer than the last write (see FlagdTelemetryPolicyIntegrationTest). */
    private void write(String json) throws IOException {
        Files.writeString(file, json, StandardCharsets.UTF_8);
        lastModified = Math.max(System.currentTimeMillis(), lastModified + 1000);
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(lastModified));
    }

    private void start(Enforcement mode, String flagFile) throws IOException {
        file = tempDir.resolve("flags.json");
        write(flagFile);
        policy = new FlagdTelemetryPolicy("telemetryLevel", "otelEnabled", "recordSpan", TelemetryPolicy.FULL,
                FlagdOptions.builder()
                        .resolverType(Config.Resolver.FILE)
                        .offlineFlagSourcePath(file.toString())
                        .offlinePollIntervalMs(50)
                        .build());
        policy.setResource(Resource.create(Attributes.builder().put("service.name", "weather").build()));
        assertTrue(waitFor(() -> policy.cachedEnabled() != null), "provider never became ready");

        exporter = new FilteringSpanProcessorTest.InMemoryExporter();
        counting = new FilteringSpanProcessorTest.CountingProcessor();
        SuppressedSpanRegistry registry = new SuppressedSpanRegistry();
        Sampler agentDefault = Sampler.parentBased(Sampler.alwaysOn());
        tracerProvider = SdkTracerProvider.builder()
                .setSampler(mode == Enforcement.SAMPLER ? new FlagAwareSampler(agentDefault, policy) : agentDefault)
                .addSpanProcessor(new FilteringSpanProcessor(
                        SimpleSpanProcessor.create(new ReparentingSpanExporter(exporter, registry)),
                        OtelfeatureCustomizer.processorPolicy(policy, mode, () -> mode == Enforcement.SAMPLER),
                        registry))
                .addSpanProcessor(counting)
                .build();
    }

    @AfterEach
    void tearDown() {
        if (tracerProvider != null) {
            tracerProvider.shutdown();
        }
        if (policy != null) {
            policy.shutdown();
        }
    }

    private Tracer tracer(String scope) {
        return tracerProvider.tracerBuilder(scope).build();
    }

    private List<String> exported() {
        return exporter.spans.stream().map(SpanData::getName).collect(Collectors.toList());
    }

    private Map<String, SpanData> byName() {
        return exporter.spans.stream().collect(Collectors.toMap(SpanData::getName, s -> s));
    }

    /** GET /weather: SERVER, then an outgoing HTTP call and a DB call under it. */
    private void weatherRequest() {
        Span server = tracer("io.opentelemetry.tomcat-10.0").spanBuilder("GET /weather")
                .setSpanKind(SpanKind.SERVER).setAttribute("url.path", "/weather").startSpan();
        try (var s = server.makeCurrent()) {
            tracer("io.opentelemetry.java-http-client").spanBuilder("GET geocode")
                    .setSpanKind(SpanKind.CLIENT).setAttribute("server.address", "geocode").startSpan().end();
            tracer("io.opentelemetry.jdbc").spanBuilder("SELECT forecast")
                    .setSpanKind(SpanKind.CLIENT).setAttribute("db.system.name", "postgresql").startSpan().end();
        }
        server.end();
    }

    private void healthCheck() {
        tracer("io.opentelemetry.tomcat-10.0").spanBuilder("GET /health")
                .setSpanKind(SpanKind.SERVER).setAttribute("url.path", "/health").startSpan().end();
    }

    @ParameterizedTest
    @EnumSource(Enforcement.class)
    @DisplayName("health-check rule: no GET /health spans exported, the rest untouched")
    void healthCheckRule(Enforcement mode) throws IOException {
        start(mode, flags(GLOBAL_ON, HEALTH));

        healthCheck();
        weatherRequest();

        assertEquals(List.of("GET geocode", "SELECT forecast", "GET /weather"), exported());
        if (mode == Enforcement.SAMPLER) {
            assertEquals(3, counting.onStartCount.get(), "the health check must never reach onStart");
        }
    }

    @ParameterizedTest
    @EnumSource(Enforcement.class)
    @DisplayName("JDBC scope rule: DB spans gone, server and HTTP client kept and correctly parented")
    void jdbcScopeRule(Enforcement mode) throws IOException {
        start(mode, flags(GLOBAL_ON, JDBC_SCOPE));

        weatherRequest();

        assertEquals(List.of("GET geocode", "GET /weather"), exported());
        Map<String, SpanData> spans = byName();
        assertEquals(spans.get("GET /weather").getSpanId(), spans.get("GET geocode").getParentSpanId());
    }

    @Test
    @DisplayName("sampler mode: a DB rule on db.system.name drops the leaf before it exists")
    void dbLeafDroppedAtTheSampler() throws IOException {
        start(Enforcement.SAMPLER, flags(GLOBAL_ON, DB_SYSTEM));

        weatherRequest();

        assertEquals(List.of("GET geocode", "GET /weather"), exported());
        assertEquals(2, counting.onStartCount.get(), "the DB span must never reach onStart");
    }

    @Test
    @DisplayName("sampler mode: a mid-trace span that propagates is suppressed by the processor, not dropped")
    void midTraceSpanIsNotDroppedAtTheSampler() throws IOException {
        start(Enforcement.SAMPLER, flags(GLOBAL_ON, """
                { "if": [ { "==": [ { "var": "server.address" }, "geocode" ] }, "off", null ] }"""));

        Span[] client = new Span[1];
        Span server = tracer("tomcat").spanBuilder("GET /weather").setSpanKind(SpanKind.SERVER).startSpan();
        try (var s = server.makeCurrent()) {
            client[0] = tracer("http").spanBuilder("GET geocode").setSpanKind(SpanKind.CLIENT)
                    .setAttribute("server.address", "geocode").startSpan();
            client[0].end();
        }
        server.end();

        assertTrue(client[0].getSpanContext().isSampled(),
                "the call downstream must not carry sampled=0");
        assertEquals(List.of("GET /weather"), exported(), "but the span itself is not exported");
    }

    @ParameterizedTest
    @EnumSource(Enforcement.class)
    @DisplayName("recordSpan=true never re-enables what otelEnabled turned off")
    void globalSwitchWins(Enforcement mode) throws IOException {
        start(mode, flags(GLOBAL_ON.replace("\"defaultVariant\": \"on\"", "\"defaultVariant\": \"off\""),
                null));
        assertTrue(waitFor(() -> Boolean.FALSE.equals(policy.cachedEnabled())));

        weatherRequest();

        assertEquals(List.of(), exported());
    }

    @Test
    @DisplayName("a static recordSpan adds no per-span evaluation")
    void staticRecordSpanIsCached() throws IOException {
        start(Enforcement.PROCESSOR, flags(GLOBAL_ON, null));
        assertTrue(waitFor(() -> Boolean.TRUE.equals(policy.cachedRecord()) && policy.cachedPolicy() != null),
                "recordSpan never cached");

        for (int i = 0; i < 100; i++) {
            weatherRequest();
        }

        assertEquals(TelemetryPolicy.FULL, policy.cachedPolicy());
        assertEquals(0, policy.contexts().builds(), "a static recordSpan must not evaluate per span");
    }

    @Test
    @DisplayName("a ruleset without recordSpan records everything, with no per-span evaluation")
    void missingRecordSpanFailsOpen() throws IOException {
        start(Enforcement.PROCESSOR, """
                {
                  "$schema": "https://flagd.dev/schema/v0/flags.json",
                  "flags": {
                    %s
                  }
                }
                """.formatted(GLOBAL_ON));
        assertTrue(waitFor(() -> Boolean.TRUE.equals(policy.cachedRecord())),
                "a missing recordSpan should be kept as its fallback, true");

        weatherRequest();

        assertEquals(3, exported().size());
        assertEquals(0, policy.contexts().builds());
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

    private static final String API_WEATHER = """
            { "if": [ { "==": [ { "var": "url.path" }, "/api/weather" ] }, "off", null ] }""";

    private static io.opentelemetry.context.Context incoming(boolean sampled) {
        io.opentelemetry.api.trace.SpanContext upstream = io.opentelemetry.api.trace.SpanContext.createFromRemoteParent(
                "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331",
                sampled ? io.opentelemetry.api.trace.TraceFlags.getSampled()
                        : io.opentelemetry.api.trace.TraceFlags.getDefault(),
                io.opentelemetry.api.trace.TraceState.getDefault());
        return io.opentelemetry.context.Context.root().with(Span.wrap(upstream));
    }

    /** A health check behind a proxy that forwards its sampled traceparent. */
    private Span[] proxiedHealthCheck() {
        Span server = tracer("tomcat").spanBuilder("GET /health").setSpanKind(SpanKind.SERVER)
                .setParent(incoming(true)).setAttribute("url.path", "/health").startSpan();
        Span internal;
        Span client;
        try (var s1 = server.makeCurrent()) {
            internal = tracer("spring").spanBuilder("HealthController.check").startSpan();
            try (var s2 = internal.makeCurrent()) {
                client = tracer("jdbc").spanBuilder("SELECT 1").setSpanKind(SpanKind.CLIENT)
                        .setAttribute("db.system.name", "postgresql").startSpan();
                client.end();
            }
            internal.end();
        }
        server.end();
        return new Span[] {server, internal, client};
    }

    @ParameterizedTest
    @EnumSource(Enforcement.class)
    @DisplayName("a proxied health check (sampled incoming traceparent) leaves no spans in this service")
    void proxiedHealthCheckLeavesNoSpans(Enforcement mode) throws IOException {
        start(mode, flags(GLOBAL_ON, HEALTH));

        Span[] spans = proxiedHealthCheck();

        assertEquals(List.of(), exported(), "the whole request goes, subtree included");
        if (mode == Enforcement.SAMPLER) {
            assertEquals(0, counting.onStartCount.get(), "the entry span is DROPped: nothing is recorded");
            assertTrue(!spans[2].getSpanContext().isSampled(),
                    "the trade-off: the trace ends at the caller, downstream gets sampled=0");
        }
    }

    @Test
    @DisplayName("processor mode: a dropped root takes its whole local subtree, not just itself")
    void processorModeDropsTheSubtree() throws IOException {
        start(Enforcement.PROCESSOR, flags(GLOBAL_ON, HEALTH));

        Span server = tracer("tomcat").spanBuilder("GET /health").setSpanKind(SpanKind.SERVER)
                .setAttribute("url.path", "/health").startSpan();
        try (var s = server.makeCurrent()) {
            tracer("jdbc").spanBuilder("SELECT 1").setSpanKind(SpanKind.CLIENT)
                    .setAttribute("db.system.name", "postgresql").startSpan().end();
        }
        server.end();
        weatherRequest();

        assertEquals(List.of("GET geocode", "SELECT forecast", "GET /weather"), exported(),
                "nothing of the health check, not even its children; other requests untouched");
    }

    @Test
    @DisplayName("sampler mode: an INTERNAL span with a remote parent is not a root: left to the processor")
    void remoteParentedInternalSpanGoesToTheProcessor() throws IOException {
        start(Enforcement.SAMPLER, flags(GLOBAL_ON, API_WEATHER));

        Span internal = tracer("worker").spanBuilder("process").setSpanKind(SpanKind.INTERNAL)
                .setParent(incoming(true)).setAttribute("url.path", "/api/weather").startSpan();
        internal.end();

        assertTrue(internal.getSpanContext().isSampled(), "not DROPped: it is not an entry span");
        assertEquals(List.of(), exported(), "but suppressed by the processor");
    }

    @Test
    @DisplayName("sampler mode: a CONSUMER span with a remote parent is an entry span, and is DROPped")
    void remoteParentedConsumerIsARoot() throws IOException {
        start(Enforcement.SAMPLER, flags(GLOBAL_ON, API_WEATHER));

        tracer("kafka").spanBuilder("weather.requests process").setSpanKind(SpanKind.CONSUMER)
                .setParent(incoming(true)).setAttribute("url.path", "/api/weather").startSpan().end();

        assertEquals(0, counting.onStartCount.get());
    }

    @ParameterizedTest
    @EnumSource(Enforcement.class)
    @DisplayName("a recordSpan of the wrong type records everything (fail open)")
    void wrongTypeRecordSpanFailsOpen(Enforcement mode) throws IOException {
        start(mode, """
                {
                  "$schema": "https://flagd.dev/schema/v0/flags.json",
                  "flags": {
                    %s,
                    "recordSpan": {
                      "state": "ENABLED", "defaultVariant": "on",
                      "variants": { "on": "yes", "off": "no" }
                    }
                  }
                }
                """.formatted(GLOBAL_ON));

        healthCheck();
        weatherRequest();

        assertEquals(4, exported().size());
        assertEquals(null, policy.cachedRecord(), "a type mismatch is not a cacheable answer");
    }

    @ParameterizedTest
    @EnumSource(Enforcement.class)
    @DisplayName("adding a recordSpan rule to a ruleset that had none takes effect")
    void addingRecordSpanLaterTakesEffect(Enforcement mode) throws IOException {
        start(mode, """
                {
                  "$schema": "https://flagd.dev/schema/v0/flags.json",
                  "flags": {
                    %s
                  }
                }
                """.formatted(GLOBAL_ON));
        assertTrue(waitFor(() -> Boolean.TRUE.equals(policy.cachedRecord())), "missing recordSpan not cached");

        write(flags(GLOBAL_ON, HEALTH));
        assertTrue(waitFor(() -> policy.cachedRecord() == null), "the added rule never reached the extension");

        healthCheck();
        weatherRequest();

        assertEquals(List.of("GET geocode", "SELECT forecast", "GET /weather"), exported());
    }
}

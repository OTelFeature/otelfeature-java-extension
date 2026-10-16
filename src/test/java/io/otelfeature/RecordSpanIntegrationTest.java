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

    private void start(Enforcement mode, String flagFile) throws IOException {
        Path file = tempDir.resolve("flags.json");
        Files.writeString(file, flagFile, StandardCharsets.UTF_8);
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
        assertTrue(waitFor(() -> Boolean.TRUE.equals(policy.cachedRecord())), "recordSpan never cached");

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
}

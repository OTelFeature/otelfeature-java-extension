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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openfeature.contrib.providers.flagd.Config;
import dev.openfeature.contrib.providers.flagd.FlagdOptions;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The evaluation context against a real flagd provider ({@code FILE} resolver):
 * targeting rules written in semconv names, {@code fractional} on the trace id,
 * and the static path building nothing.
 */
@DisplayName("Evaluation context against a real flagd provider")
class FlagdEvaluationContextIntegrationTest {

    private static final long TIMEOUT_MILLIS = 15_000;
    private static final InstrumentationScopeInfo TOMCAT =
            InstrumentationScopeInfo.builder("io.opentelemetry.tomcat-10.0").setVersion("2.10.0").build();

    private static final String OTEL_ENABLED = """
            "otelEnabled": {
              "state": "ENABLED",
              "defaultVariant": "on",
              "variants": { "on": true, "off": false }
            }""";

    /** Health checks of the weather service go dark; everything else is traced. */
    private static final String HEALTH_RULE = """
            {
              "$schema": "https://flagd.dev/schema/v0/flags.json",
              "flags": {
                %s,
                "telemetryLevel": {
                  "state": "ENABLED",
                  "defaultVariant": "full",
                  "variants": { "full": "FULL", "io": "IO", "off": "OFF" },
                  "targeting": {
                    "if": [
                      { "and": [
                        { "==": [{ "var": "service.name" }, "weather"] },
                        { "==": [{ "var": "span.kind" }, "server"] },
                        { "==": [{ "var": "url.path" }, "/health"] }
                      ] },
                      "off",
                      "full"
                    ]
                  }
                }
              }
            }
            """.formatted(OTEL_ENABLED);

    /** Half the traces at IO, half at FULL, bucketed on targetingKey. */
    private static final String FRACTIONAL = """
            {
              "$schema": "https://flagd.dev/schema/v0/flags.json",
              "flags": {
                %s,
                "telemetryLevel": {
                  "state": "ENABLED",
                  "defaultVariant": "full",
                  "variants": { "full": "FULL", "io": "IO" },
                  "targeting": { "fractional": [["io", 50], ["full", 50]] }
                }
              }
            }
            """.formatted(OTEL_ENABLED);

    private static final String STATIC = """
            {
              "$schema": "https://flagd.dev/schema/v0/flags.json",
              "flags": {
                %s,
                "telemetryLevel": {
                  "state": "ENABLED",
                  "defaultVariant": "io",
                  "variants": { "full": "FULL", "io": "IO" }
                }
              }
            }
            """.formatted(OTEL_ENABLED);

    @TempDir
    Path tempDir;

    private FlagdTelemetryPolicy policy;

    private void start(String flags) throws IOException {
        Path file = tempDir.resolve("flags-" + System.nanoTime() + ".json");
        Files.writeString(file, flags, StandardCharsets.UTF_8);
        policy = new FlagdTelemetryPolicy("telemetryLevel", "otelEnabled", TelemetryPolicy.FULL,
                FlagdOptions.builder()
                        .resolverType(Config.Resolver.FILE)
                        .offlineFlagSourcePath(file.toString())
                        .offlinePollIntervalMs(50)
                        .build());
        policy.setResource(Resource.create(Attributes.builder()
                .put("service.name", "weather")
                .put("deployment.environment.name", "prod")
                .put("telemetry.sdk.language", "java")
                .build()));
        // Not a cached value: a fallback cached before READY satisfies that too.
        assertTrue(waitFor(() -> policy.providerReady()), "provider never became ready");
    }

    @AfterEach
    void tearDown() {
        if (policy != null) {
            policy.shutdown();
        }
    }

    private static String traceId() {
        return String.format(Locale.ROOT, "%016x%016x",
                ThreadLocalRandom.current().nextLong(), ThreadLocalRandom.current().nextLong());
    }

    private static SpanStart server(String traceId, String path) {
        return new SpanStart(traceId, "GET " + path, SpanKind.SERVER,
                Attributes.of(AttributeKey.stringKey("url.path"), path,
                        AttributeKey.stringKey("http.request.method"), "GET"),
                TOMCAT);
    }

    @Test
    @DisplayName("a semconv rule tells a /health span from a /weather span")
    void ruleSeparatesHealthFromWeather() throws IOException {
        start(HEALTH_RULE);

        assertEquals(TelemetryPolicy.DISABLED, policy.policy(server(traceId(), "/health")));
        assertEquals(TelemetryPolicy.FULL, policy.policy(server(traceId(), "/weather")));
        assertEquals(TelemetryPolicy.FULL, policy.policy(new SpanStart(traceId(), "GET /health",
                        SpanKind.CLIENT, Attributes.of(AttributeKey.stringKey("url.path"), "/health"), TOMCAT)),
                "an outgoing call to /health is not the weather service's own health check");
    }

    @Test
    @DisplayName("fractional on targetingKey puts every span of a trace in the same bucket")
    void fractionalBucketsWholeTraces() throws IOException {
        start(FRACTIONAL);

        Set<TelemetryPolicy> seen = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            String trace = traceId();
            TelemetryPolicy root = policy.policy(server(trace, "/weather"));
            TelemetryPolicy child = policy.policy(new SpanStart(trace, "SELECT forecast", SpanKind.CLIENT,
                    Attributes.of(AttributeKey.stringKey("db.system.name"), "postgresql"), null));
            assertEquals(root, child, "two spans of trace " + trace + " landed in different buckets");
            seen.add(root);
        }
        assertEquals(Set.of(TelemetryPolicy.IO, TelemetryPolicy.FULL), seen,
                "64 traces should have hit both halves of a 50/50 split");
    }

    @Test
    @DisplayName("the static path builds no context and allocates nothing")
    void staticPathAllocatesNoContext() throws IOException {
        start(STATIC);
        assertTrue(waitFor(() -> policy.cachedLevel() == TelemetryLevel.IO));
        SpanStart span = server(traceId(), "/weather");

        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        for (int i = 0; i < 20_000; i++) {
            policy.policy(span); // warm up
        }
        long before = threads.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 100_000; i++) {
            policy.policy(span);
        }
        long allocated = threads.getThreadAllocatedBytes(thread) - before;

        assertEquals(0, policy.contexts().builds(), "a static flag must never build a context");
        // A context is hundreds of bytes; 100k of them would be tens of MB.
        assertTrue(allocated < 100_000, "static path allocated " + allocated + " bytes for 100k spans");
    }

    @Test
    @DisplayName("per-span evaluation cost, static vs targeted (printed, for the README)")
    void measuresPerSpanCost() throws IOException {
        start(HEALTH_RULE);
        SpanStart span = server(traceId(), "/weather");
        long targeted = nanosPerCall(span);
        assertNotEquals(0, policy.contexts().builds(), "a targeted flag builds a context per span");
        policy.shutdown();

        start(STATIC);
        assertTrue(waitFor(() -> policy.cachedLevel() == TelemetryLevel.IO));
        long cached = nanosPerCall(span);

        // How much of the targeted cost is building the context, and how much
        // is flagd evaluating the rule.
        EvaluationContextFactory factory = policy.contexts();
        for (int i = 0; i < 20_000; i++) {
            factory.build(span);
        }
        int n = 50_000;
        long begin = System.nanoTime();
        for (int i = 0; i < n; i++) {
            factory.build(span);
        }
        long build = Math.max(1, (System.nanoTime() - begin) / n);

        System.out.printf(Locale.ROOT,
                "OTELFEATURE-COST static=%dns targeted=%dns (of which context build=%dns) per span%n",
                cached, targeted, build);
        assertTrue(cached < targeted, "the cached path should be cheaper than evaluating");
    }

    private long nanosPerCall(SpanStart span) {
        for (int i = 0; i < 20_000; i++) {
            policy.policy(span);
        }
        int n = 50_000;
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            policy.policy(span);
        }
        return Math.max(1, (System.nanoTime() - start) / n);
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

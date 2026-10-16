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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openfeature.contrib.providers.flagd.Config;
import dev.openfeature.contrib.providers.flagd.FlagdOptions;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.resources.Resource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code traceSampleRatio} against a real flagd provider ({@code FILE} resolver). */
@DisplayName("traceSampleRatio against a real flagd provider")
class FlagdSampleRatioIntegrationTest {

    private static final long TIMEOUT_MILLIS = 15_000;

    private static String flags(String ratioFlag) {
        return """
                {
                  "$schema": "https://flagd.dev/schema/v0/flags.json",
                  "flags": {
                    "otelEnabled": {
                      "state": "ENABLED", "defaultVariant": "on",
                      "variants": { "on": true, "off": false }
                    },
                    "telemetryLevel": {
                      "state": "ENABLED", "defaultVariant": "full",
                      "variants": { "full": "FULL", "io": "IO", "off": "OFF" }
                    }%s
                  }
                }
                """.formatted(ratioFlag == null ? "" : ",\n" + ratioFlag);
    }

    private static String staticRatio(String value) {
        return """
                    "traceSampleRatio": {
                      "state": "ENABLED", "defaultVariant": "set",
                      "variants": { "set": %s, "all": 1.0 }
                    }""".formatted(value);
    }

    @TempDir
    Path tempDir;

    private FlagdTelemetryPolicy policy;

    private void start(String json) throws IOException {
        Path file = tempDir.resolve("flags.json");
        Files.writeString(file, json, StandardCharsets.UTF_8);
        policy = new FlagdTelemetryPolicy("telemetryLevel", "otelEnabled", "recordSpan", "traceSampleRatio",
                TelemetryPolicy.FULL,
                FlagdOptions.builder()
                        .resolverType(Config.Resolver.FILE)
                        .offlineFlagSourcePath(file.toString())
                        .offlinePollIntervalMs(50)
                        .build());
        policy.setResource(Resource.create(Attributes.builder().put("service.name", "weather").build()));
        assertTrue(waitFor(() -> policy.cachedEnabled() != null), "provider never became ready");
    }

    @AfterEach
    void tearDown() {
        if (policy != null) {
            policy.shutdown();
        }
    }

    private SpanStart root() {
        return new SpanStart("0af7651916cd43dd8448eb211c80319c", "GET /weather", SpanKind.SERVER,
                Attributes.empty(), null);
    }

    @Test
    @DisplayName("a static ratio is cached, and the composed policy is one shared instance")
    void staticRatioIsComposedOnce() throws IOException {
        start(flags(staticRatio("0.1")));
        assertTrue(waitFor(() -> policy.cachedPolicy() != null), "the static policy is composed");

        TelemetryPolicy first = policy.cachedPolicy();
        assertEquals(0.1, first.sampleRatio());
        assertSame(first, policy.cachedPolicy(), "no allocation per span on the static path");
        assertSame(first, policy.policy(root()));
        assertEquals(0, policy.contexts().builds());
    }

    @Test
    @DisplayName("an out-of-range ratio is clamped")
    void outOfRangeIsClamped() throws IOException {
        start(flags(staticRatio("5.0")));
        assertTrue(waitFor(() -> policy.cachedPolicy() != null));

        assertEquals(1.0, policy.cachedPolicy().sampleRatio());
    }

    @Test
    @DisplayName("an integer ratio (1, 0) reads as a number")
    void integerRatioReadsAsNumber() throws IOException {
        start(flags(staticRatio("0")));
        assertTrue(waitFor(() -> policy.cachedRatio() != null));

        // flagd 0.14 hands a JSON integer to a double flag as a number.
        assertEquals(0.0, policy.cachedRatio());
        assertEquals(0.0, policy.policy(root()).sampleRatio());
    }

    @Test
    @DisplayName("a non-numeric ratio falls back to the agent's sampler, and is not cached")
    void nonNumericFallsBack() throws IOException {
        start(flags(staticRatio("\"lots\"")));

        assertTrue(waitFor(() -> policy.cachedRecord() != null));
        assertNull(policy.cachedRatio(), "a type mismatch is not a cacheable answer");
        assertEquals(TelemetryPolicy.FULL, policy.policy(root()));
    }

    @Test
    @DisplayName("no traceSampleRatio: the agent's sampler decides, with no per-span evaluation")
    void missingRatioDelegates() throws IOException {
        start(flags(null));
        assertTrue(waitFor(() -> policy.cachedRatio() != null && policy.cachedPolicy() != null),
                "a missing flag is cached as its fallback");

        assertSame(TelemetryPolicy.FULL, policy.cachedPolicy());
        assertEquals(0, policy.contexts().builds());
        assertTrue(!policy.sampleRatioInPlay(), "no ratio: children stay with the agent's sampler");
    }

    @Test
    @DisplayName("targeted: a ratio per service from the semconv context")
    void ratioPerService() throws IOException {
        start(flags("""
                    "traceSampleRatio": {
                      "state": "ENABLED", "defaultVariant": "tenth",
                      "variants": { "tenth": 0.1, "all": 1.0 },
                      "targeting": { "if": [ { "==": [ { "var": "service.name" }, "weather" ] }, "all", null ] }
                    }"""));
        assertTrue(waitFor(() -> policy.cachedRecord() != null));

        // The processor never needs the ratio: its policy stays cached and
        // builds no context, even with the ratio targeted.
        assertTrue(waitFor(() -> policy.cachedPolicy(TelemetryPolicy.Need.SPAN) != null),
                "the SPAN policy is composed after every flag is classified");
        assertEquals(TelemetryPolicy.FULL, policy.cachedPolicy(TelemetryPolicy.Need.SPAN));
        assertNull(policy.cachedPolicy(), "the full policy is per span now");
        assertTrue(policy.sampleRatioInPlay());
        long before = policy.contexts().builds();
        policy.policy(root(), TelemetryPolicy.Need.SPAN);
        assertEquals(before, policy.contexts().builds(), "a SPAN decision must not evaluate the ratio");

        assertEquals(1.0, policy.policy(root()).sampleRatio(), "weather is turned up to 1.0");
        policy.setResource(Resource.create(Attributes.builder().put("service.name", "geocode").build()));
        assertEquals(0.1, policy.policy(root()).sampleRatio(), "everyone else stays at 0.1");
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

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openfeature.contrib.providers.flagd.Config;
import dev.openfeature.contrib.providers.flagd.FlagdOptions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives {@link FlagdTelemetryPolicy} against a <em>real</em> flagd provider in
 * {@code FILE} resolver mode.
 *
 * <p>{@code FILE} shares the flag parser, the targeting engine and the
 * {@code PROVIDER_CONFIGURATION_CHANGED} plumbing with the {@code IN_PROCESS}
 * resolver used in production - it is fed from a file on disk instead of
 * flagd's gRPC sync stream. So "does flagd really report STATIC here and
 * TARGETING_MATCH there, and does the extension really re-classify when the
 * ruleset moves?" is observed rather than assumed, without needing a flagd
 * container.
 */
@DisplayName("FlagdTelemetryPolicy against a real flagd provider")
class FlagdTelemetryPolicyIntegrationTest {

    private static final String FLAG_KEY = "telemetryLevel";
    private static final String ENABLED_KEY = "otelEnabled";
    private static final long TIMEOUT_MILLIS = 15_000;

    /** No targeting on either flag: flagd resolves both with reason STATIC. */
    private static String staticFlags(String defaultVariant) {
        return staticFlags(defaultVariant, "on");
    }

    /**
     * Both flags, untargeted.
     *
     * @param levelVariant {@code full}, {@code io} or {@code off}
     * @param enabledVariant {@code on} or {@code off}
     */
    private static String staticFlags(String levelVariant, String enabledVariant) {
        return """
                {
                  "$schema": "https://flagd.dev/schema/v0/flags.json",
                  "flags": {
                    "telemetryLevel": {
                      "state": "ENABLED",
                      "defaultVariant": "%s",
                      "variants": { "io": "IO", "full": "FULL", "off": "OFF" }
                    },
                    "otelEnabled": {
                      "state": "ENABLED",
                      "defaultVariant": "%s",
                      "variants": { "on": true, "off": false }
                    }
                  }
                }
                """
                .formatted(levelVariant, enabledVariant);
    }

    /**
     * The same flag with a targeting rule. It flips every second on flagd's own
     * injected {@code $flagd.timestamp}, so it needs no evaluation context -
     * what matters here is only that flagd now reports a context-dependent
     * reason.
     */
    private static String targetedFlags() {
        return """
                {
                  "$schema": "https://flagd.dev/schema/v0/flags.json",
                  "flags": {
                    "otelEnabled": {
                      "state": "ENABLED",
                      "defaultVariant": "on",
                      "variants": { "on": true, "off": false }
                    },
                    "telemetryLevel": {
                      "state": "ENABLED",
                      "defaultVariant": "full",
                      "variants": { "io": "IO", "full": "FULL" },
                      "targeting": {
                        "if": [
                          { "==": [{ "%": [{ "var": "$flagd.timestamp" }, 2] }, 0] },
                          "io",
                          "full"
                        ]
                      }
                    }
                  }
                }
                """;
    }

    @TempDir
    Path tempDir;

    private Path flagFile;
    private FlagdTelemetryPolicy telemetryLevel;

    @BeforeEach
    void setUp() throws IOException {
        flagFile = tempDir.resolve("flags.json");
        write(staticFlags("full"));

        telemetryLevel = policyWithDefaults(TelemetryPolicy.FULL);
    }

    private FlagdTelemetryPolicy policyWithDefaults(TelemetryPolicy defaults) {
        return new FlagdTelemetryPolicy(
                FLAG_KEY,
                ENABLED_KEY,
                defaults,
                FlagdOptions.builder()
                        .resolverType(Config.Resolver.FILE)
                        .offlineFlagSourcePath(flagFile.toString())
                        // The file is re-read on a timer; the 5s default would
                        // make every assertion below a five-second wait.
                        .offlinePollIntervalMs(50)
                        .build());
    }

    @AfterEach
    void tearDown() {
        if (telemetryLevel != null) {
            telemetryLevel.shutdown();
        }
    }

    @Test
    @DisplayName("an untargeted flag is classified static and answered without evaluating")
    void untargetedFlagIsClassifiedStatic() {
        assertTrue(waitFor(() -> telemetryLevel.cachedLevel() != null),
                "provider never classified the flag");

        assertEquals(TelemetryLevel.FULL, telemetryLevel.cachedLevel());
        assertEquals(TelemetryPolicy.FULL, telemetryLevel.policy());
    }

    @Test
    @DisplayName("editing the flag takes effect, driven by the configuration-change event")
    void editingTheFlagTakesEffect() throws IOException {
        assertTrue(waitFor(() -> telemetryLevel.cachedLevel() == TelemetryLevel.FULL));

        write(staticFlags("io"));

        assertTrue(waitFor(() -> telemetryLevel.policy() == TelemetryPolicy.IO),
                "flag edit never reached the extension");
        assertEquals(TelemetryLevel.IO, telemetryLevel.cachedLevel(),
                "an untargeted flag should still be answered from the kept decision");
    }

    @Test
    @DisplayName("attaching targeting switches caching off; removing it switches caching back on")
    void targetingSwitchesCachingOffAndBackOn() throws IOException {
        assertTrue(waitFor(() -> telemetryLevel.cachedLevel() != null),
                "provider never classified the flag");

        write(targetedFlags());
        assertTrue(waitFor(() -> telemetryLevel.cachedLevel() == null),
                "a targeted flag must be evaluated per span, not cached");

        // And it really does resolve per evaluation now: the rule flips on the
        // second, so polling it across a second boundary must produce both.
        assertTrue(waitFor(new BooleanSupplier() {
            private boolean sawSuppressed;
            private boolean sawVisible;

            @Override
            public boolean getAsBoolean() {
                if (telemetryLevel.policy() == TelemetryPolicy.IO) {
                    sawSuppressed = true;
                } else {
                    sawVisible = true;
                }
                return sawSuppressed && sawVisible;
            }
        }), "targeted flag never varied between evaluations");

        write(staticFlags("full"));
        assertTrue(waitFor(() -> telemetryLevel.cachedLevel() != null),
                "caching did not resume once the targeting was removed");
        assertEquals(TelemetryLevel.FULL, telemetryLevel.cachedLevel());
    }

    @Test
    @DisplayName("a missing flag is not cached, and suppresses nothing")
    void missingFlagIsNotCached() throws IOException {
        write("""
                { "$schema": "https://flagd.dev/schema/v0/flags.json", "flags": {} }
                """);

        assertTrue(waitFor(() -> {
            // ERROR is not a cacheable reason: the flag may yet appear.
            return telemetryLevel.cachedLevel() == null && telemetryLevel.cachedEnabled() == null
                    && telemetryLevel.policy() == TelemetryPolicy.FULL;
        }), "missing flags should resolve to their defaults and stay uncached");

        assertNull(telemetryLevel.cachedLevel());
        assertNull(telemetryLevel.cachedEnabled());
    }

    @Test
    @DisplayName("telemetryLevel=OFF is served from the kept decision and switches back")
    void offVariantTakesEffect() throws IOException {
        assertTrue(waitFor(() -> telemetryLevel.cachedLevel() == TelemetryLevel.FULL));

        write(staticFlags("off"));
        assertTrue(waitFor(() -> telemetryLevel.policy() == TelemetryPolicy.DISABLED),
                "OFF never reached the extension");
        assertEquals(TelemetryLevel.OFF, telemetryLevel.cachedLevel());

        write(staticFlags("full"));
        assertTrue(waitFor(() -> telemetryLevel.policy() == TelemetryPolicy.FULL),
                "switching back to FULL never reached the extension");
    }

    @Test
    @DisplayName("otelEnabled=false is OFF whatever telemetryLevel says, and flips back")
    void otelEnabledFalseIsOff() throws IOException {
        assertTrue(waitFor(() -> Boolean.TRUE.equals(telemetryLevel.cachedEnabled())),
                "provider never classified otelEnabled");

        write(staticFlags("full", "off"));
        assertTrue(waitFor(() -> telemetryLevel.policy() == TelemetryPolicy.DISABLED),
                "otelEnabled=false never reached the extension");
        assertEquals(Boolean.FALSE, telemetryLevel.cachedEnabled(),
                "an untargeted otelEnabled should be answered from the kept decision");
        assertEquals(TelemetryLevel.FULL, telemetryLevel.cachedLevel(),
                "telemetryLevel itself is untouched");

        write(staticFlags("io", "on"));
        assertTrue(waitFor(() -> telemetryLevel.policy() == TelemetryPolicy.IO),
                "with the kill switch back on, telemetryLevel decides again");
    }

    @Test
    @DisplayName("a flag file without otelEnabled leaves tracing on")
    void missingOtelEnabledFailsOpen() throws IOException {
        write("""
                {
                  "$schema": "https://flagd.dev/schema/v0/flags.json",
                  "flags": {
                    "telemetryLevel": {
                      "state": "ENABLED",
                      "defaultVariant": "io",
                      "variants": { "io": "IO", "full": "FULL" }
                    }
                  }
                }
                """);

        assertTrue(waitFor(() -> telemetryLevel.cachedLevel() == TelemetryLevel.IO
                        && telemetryLevel.cachedEnabled() == null),
                "a missing otelEnabled should stay uncached");
        assertEquals(TelemetryPolicy.IO, telemetryLevel.policy());
    }

    @Test
    @DisplayName("each field falls back to its own startup default; flags override the floor")
    void fieldsFallBackToStartupDefaults() throws IOException {
        telemetryLevel.shutdown();
        // otelEnabled=true, telemetryLevel missing, startup defaults "disabled".
        write("""
                {
                  "$schema": "https://flagd.dev/schema/v0/flags.json",
                  "flags": {
                    "otelEnabled": {
                      "state": "ENABLED",
                      "defaultVariant": "on",
                      "variants": { "on": true, "off": false }
                    }
                  }
                }
                """);
        telemetryLevel = policyWithDefaults(TelemetryPolicy.DISABLED);

        assertTrue(waitFor(() -> Boolean.TRUE.equals(telemetryLevel.cachedEnabled())),
                "provider never classified otelEnabled");
        assertEquals(TelemetryPolicy.DISABLED, telemetryLevel.policy(),
                "the missing telemetryLevel keeps its share of the 'disabled' floor");

        write(staticFlags("full", "on"));
        assertTrue(waitFor(() -> telemetryLevel.policy() == TelemetryPolicy.FULL),
                "once both flags say so, they override the floor");
    }

    private void write(String json) throws IOException {
        Files.writeString(flagFile, json, StandardCharsets.UTF_8);
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

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
 * Drives {@link FlagdTelemetryLevel} against a <em>real</em> flagd provider in
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
@DisplayName("FlagdTelemetryLevel against a real flagd provider")
class FlagdTelemetryLevelIntegrationTest {

    private static final String FLAG_KEY = "telemetryLevel";
    private static final long TIMEOUT_MILLIS = 15_000;

    /** No targeting: flagd resolves this with reason STATIC. */
    private static String staticFlags(String defaultVariant) {
        return """
                {
                  "$schema": "https://flagd.dev/schema/v0/flags.json",
                  "flags": {
                    "telemetryLevel": {
                      "state": "ENABLED",
                      "defaultVariant": "%s",
                      "variants": { "io": "IO", "full": "FULL" }
                    }
                  }
                }
                """
                .formatted(defaultVariant);
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
    private FlagdTelemetryLevel telemetryLevel;

    @BeforeEach
    void setUp() throws IOException {
        flagFile = tempDir.resolve("flags.json");
        write(staticFlags("full"));

        telemetryLevel = new FlagdTelemetryLevel(
                FLAG_KEY,
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
        assertTrue(waitFor(() -> telemetryLevel.cachedDecision() != null),
                "provider never classified the flag");

        assertEquals(Boolean.FALSE, telemetryLevel.cachedDecision());
        assertFalse(telemetryLevel.shouldSuppressInternal());
    }

    @Test
    @DisplayName("editing the flag takes effect, driven by the configuration-change event")
    void editingTheFlagTakesEffect() throws IOException {
        assertTrue(waitFor(() -> Boolean.FALSE.equals(telemetryLevel.cachedDecision())));

        write(staticFlags("io"));

        assertTrue(waitFor(telemetryLevel::shouldSuppressInternal),
                "flag edit never reached the extension");
        assertEquals(Boolean.TRUE, telemetryLevel.cachedDecision(),
                "an untargeted flag should still be answered from the kept decision");
    }

    @Test
    @DisplayName("attaching targeting switches caching off; removing it switches caching back on")
    void targetingSwitchesCachingOffAndBackOn() throws IOException {
        assertTrue(waitFor(() -> telemetryLevel.cachedDecision() != null),
                "provider never classified the flag");

        write(targetedFlags());
        assertTrue(waitFor(() -> telemetryLevel.cachedDecision() == null),
                "a targeted flag must be evaluated per span, not cached");

        // And it really does resolve per evaluation now: the rule flips on the
        // second, so polling it across a second boundary must produce both.
        assertTrue(waitFor(new BooleanSupplier() {
            private boolean sawSuppressed;
            private boolean sawVisible;

            @Override
            public boolean getAsBoolean() {
                if (telemetryLevel.shouldSuppressInternal()) {
                    sawSuppressed = true;
                } else {
                    sawVisible = true;
                }
                return sawSuppressed && sawVisible;
            }
        }), "targeted flag never varied between evaluations");

        write(staticFlags("full"));
        assertTrue(waitFor(() -> telemetryLevel.cachedDecision() != null),
                "caching did not resume once the targeting was removed");
        assertEquals(Boolean.FALSE, telemetryLevel.cachedDecision());
    }

    @Test
    @DisplayName("a missing flag is not cached, and suppresses nothing")
    void missingFlagIsNotCached() throws IOException {
        write("""
                { "$schema": "https://flagd.dev/schema/v0/flags.json", "flags": {} }
                """);

        assertTrue(waitFor(() -> {
            // ERROR is not a cacheable reason: the flag may yet appear.
            return telemetryLevel.cachedDecision() == null && !telemetryLevel.shouldSuppressInternal();
        }), "a missing flag should resolve to its default and stay uncached");

        assertNull(telemetryLevel.cachedDecision());
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

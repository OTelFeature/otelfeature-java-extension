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

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Reason;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests for the "evaluate once when static, every time when targeted" rule.
 *
 * <p>Evaluations are <em>counted</em> rather than inspected, so "one evaluation
 * answers N spans" is measured.
 */
@DisplayName("TelemetryFlag")
class TelemetryFlagTest {

    private static final String FLAG_KEY = "telemetryLevel";

    private static final String ENABLED_KEY = "otelEnabled";

    /** An evaluation function that records every call it receives. */
    static class RecordingEvaluator<T> implements Function<EvaluationContext, FlagEvaluationDetails<T>> {
        final List<EvaluationContext> calls = new ArrayList<>();
        private final List<FlagEvaluationDetails<T>> responses;

        @SafeVarargs
        RecordingEvaluator(FlagEvaluationDetails<T>... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public FlagEvaluationDetails<T> apply(EvaluationContext context) {
            calls.add(context);
            return responses.get(Math.min(calls.size() - 1, responses.size() - 1));
        }

        int count() {
            return calls.size();
        }
    }

    private static FlagEvaluationDetails<String> details(String value, Reason reason) {
        return FlagEvaluationDetails.<String>builder()
                .flagKey(FLAG_KEY)
                .value(value)
                .reason(reason.name())
                .build();
    }

    private static FlagEvaluationDetails<Boolean> enabled(Boolean value, Reason reason) {
        return FlagEvaluationDetails.<Boolean>builder()
                .flagKey(ENABLED_KEY)
                .value(value)
                .reason(reason.name())
                .build();
    }

    private static TelemetryFlag<String, TelemetryLevel> levelResolver(RecordingEvaluator<String> evaluator) {
        return new TelemetryFlag<>(FLAG_KEY, evaluator, TelemetryLevel::parse, TelemetryLevel.FULL);
    }

    private static TelemetryFlag<Boolean, Boolean> enabledResolver(RecordingEvaluator<Boolean> evaluator) {
        return new TelemetryFlag<>(ENABLED_KEY, evaluator, enabled -> enabled, Boolean.TRUE);
    }

    // ----------------------------------------------------------------------
    // The rule
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("a static flag is evaluated once, however many spans ask")
    void staticFlagEvaluatedOnce() {
        RecordingEvaluator<String> evaluator = new RecordingEvaluator<>(details("IO", Reason.STATIC));
        TelemetryFlag<String, TelemetryLevel> resolver = levelResolver(evaluator);

        resolver.classify(null);
        for (int i = 0; i < 100; i++) {
            assertEquals(TelemetryLevel.IO, resolver.value());
        }

        assertEquals(1, evaluator.count(), "only the context-free probe should have evaluated");
        assertEquals(TelemetryLevel.IO, resolver.cachedDecision());
    }

    @ParameterizedTest
    @EnumSource(
            value = Reason.class,
            names = {"TARGETING_MATCH", "DEFAULT", "SPLIT", "DISABLED", "ERROR", "CACHED", "UNKNOWN"})
    @DisplayName("any non-STATIC reason is evaluated for every span")
    void nonStaticReasonsEvaluatedEveryTime(Reason reason) {
        RecordingEvaluator<String> evaluator = new RecordingEvaluator<>(details("IO", reason));
        TelemetryFlag<String, TelemetryLevel> resolver = levelResolver(evaluator);
        // ERROR and DISABLED hand back the fallback, whatever value came with them.
        TelemetryLevel expected =
                reason == Reason.ERROR || reason == Reason.DISABLED ? TelemetryLevel.FULL : TelemetryLevel.IO;

        resolver.classify(null);
        for (int i = 0; i < 5; i++) {
            assertEquals(expected, resolver.value());
        }

        assertEquals(6, evaluator.count(), "probe plus one evaluation per span");
        assertNull(resolver.cachedDecision());
    }

    @Test
    @DisplayName("the classifying evaluation carries no context")
    void probeIsContextFree() {
        RecordingEvaluator<String> evaluator = new RecordingEvaluator<>(details("FULL", Reason.STATIC));
        TelemetryFlag<String, TelemetryLevel> resolver = levelResolver(evaluator);

        resolver.classify(null);

        assertEquals(1, evaluator.count());
        assertNull(evaluator.calls.get(0), "the probe must not depend on an evaluation context");
    }

    @Test
    @DisplayName("FULL means show everything")
    void fullMeansNoSuppression() {
        RecordingEvaluator<String> evaluator = new RecordingEvaluator<>(details("FULL", Reason.STATIC));
        TelemetryFlag<String, TelemetryLevel> resolver = levelResolver(evaluator);

        resolver.classify(null);

        assertEquals(TelemetryLevel.FULL, resolver.value());
        assertEquals(TelemetryLevel.FULL, resolver.cachedDecision());
    }

    @Test
    @DisplayName("an unrecognised value is treated as FULL")
    void unknownValueMeansNoSuppression() {
        RecordingEvaluator<String> evaluator = new RecordingEvaluator<>(details("something-else", Reason.STATIC));
        TelemetryFlag<String, TelemetryLevel> resolver = levelResolver(evaluator);

        resolver.classify(null);

        assertEquals(TelemetryLevel.FULL, resolver.value());
    }

    @Test
    @DisplayName("before any provider event, spans evaluate directly")
    void evaluatesDirectlyBeforeFirstEvent() {
        RecordingEvaluator<String> evaluator = new RecordingEvaluator<>(details("IO", Reason.STATIC));
        TelemetryFlag<String, TelemetryLevel> resolver = levelResolver(evaluator);

        assertNull(resolver.cachedDecision());
        assertEquals(TelemetryLevel.IO, resolver.value());
        assertEquals(1, evaluator.count());
    }

    // ----------------------------------------------------------------------
    // Re-classification
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("a change to our flag re-classifies")
    void changeToOurFlagReclassifies() {
        RecordingEvaluator<String> evaluator =
                new RecordingEvaluator<>(details("FULL", Reason.STATIC), details("IO", Reason.STATIC));
        TelemetryFlag<String, TelemetryLevel> resolver = levelResolver(evaluator);

        resolver.classify(null);
        assertEquals(TelemetryLevel.FULL, resolver.value());

        resolver.classify(List.of(FLAG_KEY));
        assertEquals(TelemetryLevel.IO, resolver.value());

        assertEquals(2, evaluator.count(), "two probes, and no per-span evaluation at all");
    }

    @Test
    @DisplayName("a change to some other flag is ignored")
    void changeToAnotherFlagIgnored() {
        RecordingEvaluator<String> evaluator = new RecordingEvaluator<>(details("FULL", Reason.STATIC));
        TelemetryFlag<String, TelemetryLevel> resolver = levelResolver(evaluator);

        resolver.classify(null);
        resolver.classify(List.of("someUnrelatedFlag"));
        resolver.value();

        assertEquals(1, evaluator.count());
    }

    @Test
    @DisplayName("an event with no key list always re-classifies")
    void eventWithoutKeysAlwaysReclassifies() {
        RecordingEvaluator<String> evaluator = new RecordingEvaluator<>(details("FULL", Reason.STATIC));
        TelemetryFlag<String, TelemetryLevel> resolver = levelResolver(evaluator);

        resolver.classify(null);
        resolver.classify(List.of());
        resolver.classify(null);

        assertEquals(3, evaluator.count());
    }

    @Test
    @DisplayName("attaching targeting switches caching off")
    void attachingTargetingSwitchesCachingOff() {
        RecordingEvaluator<String> evaluator = new RecordingEvaluator<>(
                details("FULL", Reason.STATIC), details("IO", Reason.TARGETING_MATCH));
        TelemetryFlag<String, TelemetryLevel> resolver = levelResolver(evaluator);

        resolver.classify(null);
        assertEquals(TelemetryLevel.FULL, resolver.cachedDecision());

        resolver.classify(List.of(FLAG_KEY));
        assertNull(resolver.cachedDecision(), "a targeted flag must be evaluated per span");
    }

    @Test
    @DisplayName("removing targeting switches caching back on")
    void removingTargetingSwitchesCachingBackOn() {
        RecordingEvaluator<String> evaluator = new RecordingEvaluator<>(
                details("IO", Reason.TARGETING_MATCH), details("IO", Reason.STATIC));
        TelemetryFlag<String, TelemetryLevel> resolver = levelResolver(evaluator);

        resolver.classify(null);
        assertNull(resolver.cachedDecision());

        resolver.classify(List.of(FLAG_KEY));
        assertEquals(TelemetryLevel.IO, resolver.cachedDecision());
    }

    // ----------------------------------------------------------------------
    // OFF
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("OFF is its own level, and is cached like any other static answer")
    void offIsCached() {
        RecordingEvaluator<String> evaluator = new RecordingEvaluator<>(details("OFF", Reason.STATIC));
        TelemetryFlag<String, TelemetryLevel> resolver = levelResolver(evaluator);

        resolver.classify(null);
        for (int i = 0; i < 10; i++) {
            assertEquals(TelemetryLevel.OFF, resolver.value());
        }

        assertEquals(TelemetryLevel.OFF, resolver.cachedDecision());
        assertEquals(1, evaluator.count());
    }

    @ParameterizedTest
    @CsvSource({"FULL,FULL", "full,FULL", "IO,IO", "io,IO", "OFF,OFF", "off,OFF", "' OFF ',OFF",
            "NONE,FULL", "'',FULL", "disabled,FULL"})
    @DisplayName("telemetryLevel values parse case-insensitively; unknown ones take the fallback")
    void parsesVariants(String value, TelemetryLevel expected) {
        TelemetryFlag<String, TelemetryLevel> resolver =
                levelResolver(new RecordingEvaluator<>(details(value, Reason.STATIC)));
        resolver.classify(null);
        assertEquals(expected, resolver.value());
    }

    @Test
    @DisplayName("a null value takes the fallback")
    void nullValueIsFallback() {
        TelemetryFlag<String, TelemetryLevel> resolver =
                new TelemetryFlag<>(FLAG_KEY, new RecordingEvaluator<>(details(null, Reason.STATIC)),
                        TelemetryLevel::parse, TelemetryLevel.IO);
        assertEquals(TelemetryLevel.IO, resolver.value());
    }

    // ----------------------------------------------------------------------
    // otelEnabled
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("otelEnabled=false is cached like any static answer")
    void otelEnabledFalseIsOff() {
        RecordingEvaluator<Boolean> evaluator = new RecordingEvaluator<>(enabled(false, Reason.STATIC));
        TelemetryFlag<Boolean, Boolean> resolver = enabledResolver(evaluator);

        resolver.classify(null);
        for (int i = 0; i < 10; i++) {
            assertEquals(Boolean.FALSE, resolver.value());
        }

        assertEquals(Boolean.FALSE, resolver.cachedDecision());
        assertEquals(1, evaluator.count());
    }

    @Test
    @DisplayName("otelEnabled=true is cached as true")
    void otelEnabledTrueIsFull() {
        RecordingEvaluator<Boolean> evaluator = new RecordingEvaluator<>(enabled(true, Reason.STATIC));
        TelemetryFlag<Boolean, Boolean> resolver = enabledResolver(evaluator);

        resolver.classify(null);

        assertEquals(Boolean.TRUE, resolver.value());
        assertEquals(Boolean.TRUE, resolver.cachedDecision());
    }

    @Test
    @DisplayName("an unresolvable otelEnabled fails open and is not cached")
    void otelEnabledErrorFailsOpen() {
        // What the SDK hands back on a missing flag or a type mismatch: the
        // caller's default (true) with reason ERROR.
        RecordingEvaluator<Boolean> evaluator = new RecordingEvaluator<>(enabled(true, Reason.ERROR));
        TelemetryFlag<Boolean, Boolean> resolver = enabledResolver(evaluator);

        resolver.classify(null);

        assertNull(resolver.cachedDecision());
        assertEquals(Boolean.TRUE, resolver.value());
    }

    @Test
    @DisplayName("a null otelEnabled value fails open")
    void otelEnabledNullFailsOpen() {
        RecordingEvaluator<Boolean> evaluator = new RecordingEvaluator<>(enabled(null, Reason.STATIC));
        TelemetryFlag<Boolean, Boolean> resolver = enabledResolver(evaluator);

        resolver.classify(null);

        assertEquals(Boolean.TRUE, resolver.value());
    }

    @Test
    @DisplayName("otelEnabled re-classifies on its own key and ignores telemetryLevel's")
    void otelEnabledReclassifiesOnItsOwnKey() {
        RecordingEvaluator<Boolean> evaluator =
                new RecordingEvaluator<>(enabled(true, Reason.STATIC), enabled(false, Reason.STATIC));
        TelemetryFlag<Boolean, Boolean> resolver = enabledResolver(evaluator);

        resolver.classify(null);
        resolver.classify(List.of(FLAG_KEY));
        assertEquals(Boolean.TRUE, resolver.value(), "a telemetryLevel change is not ours");

        resolver.classify(List.of(ENABLED_KEY));
        assertEquals(Boolean.FALSE, resolver.value());

        assertEquals(2, evaluator.count());
    }

    @Test
    @DisplayName("a targeted otelEnabled is evaluated for every span")
    void targetedOtelEnabledEvaluatedEveryTime() {
        RecordingEvaluator<Boolean> evaluator =
                new RecordingEvaluator<>(enabled(false, Reason.TARGETING_MATCH));
        TelemetryFlag<Boolean, Boolean> resolver = enabledResolver(evaluator);

        resolver.classify(null);
        for (int i = 0; i < 3; i++) {
            assertEquals(Boolean.FALSE, resolver.value());
        }

        assertNull(resolver.cachedDecision());
        assertEquals(4, evaluator.count());
    }

    // ----------------------------------------------------------------------
    // Fallback and error logging
    // ----------------------------------------------------------------------

    private static FlagEvaluationDetails<String> error(ErrorCode code) {
        return FlagEvaluationDetails.<String>builder()
                .flagKey(FLAG_KEY)
                .value("FULL")
                .reason(Reason.ERROR.name())
                .errorCode(code)
                .errorMessage("flag " + FLAG_KEY + " " + code)
                .build();
    }

    /** Collects what TelemetryFlag logs at WARNING and above. */
    static final class Warnings extends Handler implements AutoCloseable {
        private final Logger logger = Logger.getLogger(TelemetryFlag.class.getName());
        final List<LogRecord> records = new ArrayList<>();

        Warnings() {
            logger.addHandler(this);
        }

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
                records.add(record);
            }
        }

        @Override
        public void flush() {}

        @Override
        public void close() {
            logger.removeHandler(this);
        }
    }

    @Test
    @DisplayName("an error code yields the fallback, not the value that came with it")
    void errorCodeYieldsFallback() {
        TelemetryFlag<String, TelemetryLevel> resolver = new TelemetryFlag<>(
                FLAG_KEY, new RecordingEvaluator<>(error(ErrorCode.FLAG_NOT_FOUND)),
                TelemetryLevel::parse, TelemetryLevel.IO);

        assertEquals(TelemetryLevel.IO, resolver.value());
    }

    @Test
    @DisplayName("a failing flag is logged once, not once per span")
    void errorLoggedOnce() {
        RecordingEvaluator<String> evaluator = new RecordingEvaluator<>(error(ErrorCode.FLAG_NOT_FOUND));
        TelemetryFlag<String, TelemetryLevel> resolver = levelResolver(evaluator);

        try (Warnings warnings = new Warnings()) {
            resolver.classify(null);
            for (int i = 0; i < 100; i++) {
                resolver.value();
            }
            assertEquals(1, warnings.records.size(), "one warning for 101 failing evaluations");
            assertTrue(warnings.records.get(0).getMessage().contains("FLAG_NOT_FOUND"));
        }
    }

    @Test
    @DisplayName("a different error, or the same error after a recovery, is logged again")
    void newErrorLoggedAgain() {
        RecordingEvaluator<String> evaluator = new RecordingEvaluator<>(
                error(ErrorCode.FLAG_NOT_FOUND),
                error(ErrorCode.TYPE_MISMATCH),
                details("IO", Reason.STATIC),
                error(ErrorCode.TYPE_MISMATCH));
        TelemetryFlag<String, TelemetryLevel> resolver = levelResolver(evaluator);

        try (Warnings warnings = new Warnings()) {
            resolver.value(); // FLAG_NOT_FOUND: logged
            resolver.value(); // TYPE_MISMATCH: a different error, logged
            resolver.value(); // resolves
            resolver.value(); // TYPE_MISMATCH again after recovering: logged
            resolver.value(); // and the last response repeats: not logged
            assertEquals(3, warnings.records.size());
        }
    }

    @Test
    @DisplayName("an unrecognised variant takes the fallback and is logged once")
    void unrecognisedValueLoggedOnce() {
        RecordingEvaluator<String> evaluator = new RecordingEvaluator<>(details("INTERNAL_ONLY", Reason.TARGETING_MATCH));
        TelemetryFlag<String, TelemetryLevel> resolver = levelResolver(evaluator);

        try (Warnings warnings = new Warnings()) {
            for (int i = 0; i < 10; i++) {
                assertEquals(TelemetryLevel.FULL, resolver.value());
            }
            assertEquals(1, warnings.records.size());
            assertTrue(warnings.records.get(0).getMessage().contains("INTERNAL_ONLY"));
        }
    }

    @Test
    @DisplayName("a resolution without a value is logged once")
    void nullValueLoggedOnce() {
        TelemetryFlag<String, TelemetryLevel> resolver =
                levelResolver(new RecordingEvaluator<>(details(null, Reason.TARGETING_MATCH)));

        try (Warnings warnings = new Warnings()) {
            resolver.value();
            resolver.value();
            assertEquals(1, warnings.records.size());
        }
    }

    @Test
    @DisplayName("provider-not-ready at startup is not a warning")
    void providerNotReadyIsQuiet() {
        TelemetryFlag<String, TelemetryLevel> resolver =
                levelResolver(new RecordingEvaluator<>(error(ErrorCode.PROVIDER_NOT_READY)));

        try (Warnings warnings = new Warnings()) {
            assertEquals(TelemetryLevel.FULL, resolver.value());
            assertEquals(0, warnings.records.size(), "every startup passes through this");
        }
    }

    @Test
    @DisplayName("a real error after provider-not-ready is still a warning")
    void realErrorAfterNotReadyWarns() {
        TelemetryFlag<String, TelemetryLevel> resolver = levelResolver(new RecordingEvaluator<>(
                error(ErrorCode.PROVIDER_NOT_READY), error(ErrorCode.FLAG_NOT_FOUND)));

        try (Warnings warnings = new Warnings()) {
            resolver.value();
            resolver.value();
            assertEquals(1, warnings.records.size());
            assertTrue(warnings.records.get(0).getMessage().contains("FLAG_NOT_FOUND"));
        }
    }
}

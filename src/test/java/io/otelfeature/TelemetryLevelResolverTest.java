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

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Reason;
import io.opentelemetry.api.trace.SpanKind;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
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
@DisplayName("TelemetryLevelResolver")
class TelemetryLevelResolverTest {

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

    private static TelemetryLevelResolver<String> levelResolver(RecordingEvaluator<String> evaluator) {
        return TelemetryLevelResolver.telemetryLevel(FLAG_KEY, evaluator);
    }

    // ----------------------------------------------------------------------
    // The rule
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("a static flag is evaluated once, however many spans ask")
    void staticFlagEvaluatedOnce() {
        RecordingEvaluator<String> evaluator = new RecordingEvaluator<>(details("IO", Reason.STATIC));
        TelemetryLevelResolver<String> resolver = levelResolver(evaluator);

        resolver.classify(null);
        for (int i = 0; i < 100; i++) {
            assertEquals(TelemetryLevel.IO, resolver.level());
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
        TelemetryLevelResolver<String> resolver = levelResolver(evaluator);

        resolver.classify(null);
        for (int i = 0; i < 5; i++) {
            assertEquals(TelemetryLevel.IO, resolver.level());
        }

        assertEquals(6, evaluator.count(), "probe plus one evaluation per span");
        assertNull(resolver.cachedDecision());
    }

    @Test
    @DisplayName("the classifying evaluation carries no context")
    void probeIsContextFree() {
        RecordingEvaluator<String> evaluator = new RecordingEvaluator<>(details("FULL", Reason.STATIC));
        TelemetryLevelResolver<String> resolver = levelResolver(evaluator);

        resolver.classify(null);

        assertEquals(1, evaluator.count());
        assertNull(evaluator.calls.get(0), "the probe must not depend on an evaluation context");
    }

    @Test
    @DisplayName("FULL means show everything")
    void fullMeansNoSuppression() {
        RecordingEvaluator<String> evaluator = new RecordingEvaluator<>(details("FULL", Reason.STATIC));
        TelemetryLevelResolver<String> resolver = levelResolver(evaluator);

        resolver.classify(null);

        assertEquals(TelemetryLevel.FULL, resolver.level());
        assertEquals(TelemetryLevel.FULL, resolver.cachedDecision());
    }

    @Test
    @DisplayName("an unrecognised value is treated as FULL")
    void unknownValueMeansNoSuppression() {
        RecordingEvaluator<String> evaluator = new RecordingEvaluator<>(details("something-else", Reason.STATIC));
        TelemetryLevelResolver<String> resolver = levelResolver(evaluator);

        resolver.classify(null);

        assertEquals(TelemetryLevel.FULL, resolver.level());
    }

    @Test
    @DisplayName("before any provider event, spans evaluate directly")
    void evaluatesDirectlyBeforeFirstEvent() {
        RecordingEvaluator<String> evaluator = new RecordingEvaluator<>(details("IO", Reason.STATIC));
        TelemetryLevelResolver<String> resolver = levelResolver(evaluator);

        assertNull(resolver.cachedDecision());
        assertEquals(TelemetryLevel.IO, resolver.level());
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
        TelemetryLevelResolver<String> resolver = levelResolver(evaluator);

        resolver.classify(null);
        assertEquals(TelemetryLevel.FULL, resolver.level());

        resolver.classify(List.of(FLAG_KEY));
        assertEquals(TelemetryLevel.IO, resolver.level());

        assertEquals(2, evaluator.count(), "two probes, and no per-span evaluation at all");
    }

    @Test
    @DisplayName("a change to some other flag is ignored")
    void changeToAnotherFlagIgnored() {
        RecordingEvaluator<String> evaluator = new RecordingEvaluator<>(details("FULL", Reason.STATIC));
        TelemetryLevelResolver<String> resolver = levelResolver(evaluator);

        resolver.classify(null);
        resolver.classify(List.of("someUnrelatedFlag"));
        resolver.level();

        assertEquals(1, evaluator.count());
    }

    @Test
    @DisplayName("an event with no key list always re-classifies")
    void eventWithoutKeysAlwaysReclassifies() {
        RecordingEvaluator<String> evaluator = new RecordingEvaluator<>(details("FULL", Reason.STATIC));
        TelemetryLevelResolver<String> resolver = levelResolver(evaluator);

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
        TelemetryLevelResolver<String> resolver = levelResolver(evaluator);

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
        TelemetryLevelResolver<String> resolver = levelResolver(evaluator);

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
        TelemetryLevelResolver<String> resolver = levelResolver(evaluator);

        resolver.classify(null);
        for (int i = 0; i < 10; i++) {
            assertEquals(TelemetryLevel.OFF, resolver.level());
        }

        assertEquals(TelemetryLevel.OFF, resolver.cachedDecision());
        assertEquals(1, evaluator.count());
    }

    @ParameterizedTest
    @CsvSource({"FULL,FULL", "full,FULL", "IO,IO", "io,IO", "OFF,OFF", "off,OFF", "' OFF ',OFF",
            "NONE,FULL", "'',FULL", "disabled,FULL"})
    @DisplayName("telemetryLevel values parse case-insensitively and fail open")
    void parsesVariants(String value, TelemetryLevel expected) {
        assertEquals(expected, TelemetryLevel.parse(value));
    }

    @Test
    @DisplayName("a null value fails open")
    void nullValueIsFull() {
        assertEquals(TelemetryLevel.FULL, TelemetryLevel.parse(null));
    }

    @ParameterizedTest
    @EnumSource(SpanKind.class)
    @DisplayName("each level drops exactly the kinds it should")
    void levelsSuppressTheRightKinds(SpanKind kind) {
        assertFalse(TelemetryLevel.FULL.suppresses(kind));
        assertEquals(kind == SpanKind.INTERNAL, TelemetryLevel.IO.suppresses(kind));
        assertTrue(TelemetryLevel.OFF.suppresses(kind));
    }

    // ----------------------------------------------------------------------
    // otelEnabled
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("otelEnabled=false is OFF, and a static one is evaluated once")
    void otelEnabledFalseIsOff() {
        RecordingEvaluator<Boolean> evaluator = new RecordingEvaluator<>(enabled(false, Reason.STATIC));
        TelemetryLevelResolver<Boolean> resolver = TelemetryLevelResolver.otelEnabled(ENABLED_KEY, evaluator);

        resolver.classify(null);
        for (int i = 0; i < 10; i++) {
            assertEquals(TelemetryLevel.OFF, resolver.level());
        }

        assertEquals(TelemetryLevel.OFF, resolver.cachedDecision());
        assertEquals(1, evaluator.count());
    }

    @Test
    @DisplayName("otelEnabled=true defers to telemetryLevel (reads as FULL)")
    void otelEnabledTrueIsFull() {
        RecordingEvaluator<Boolean> evaluator = new RecordingEvaluator<>(enabled(true, Reason.STATIC));
        TelemetryLevelResolver<Boolean> resolver = TelemetryLevelResolver.otelEnabled(ENABLED_KEY, evaluator);

        resolver.classify(null);

        assertEquals(TelemetryLevel.FULL, resolver.level());
        assertEquals(TelemetryLevel.FULL, resolver.cachedDecision());
    }

    @Test
    @DisplayName("an unresolvable otelEnabled fails open and is not cached")
    void otelEnabledErrorFailsOpen() {
        // What the SDK hands back on a missing flag or a type mismatch: the
        // caller's default (true) with reason ERROR.
        RecordingEvaluator<Boolean> evaluator = new RecordingEvaluator<>(enabled(true, Reason.ERROR));
        TelemetryLevelResolver<Boolean> resolver = TelemetryLevelResolver.otelEnabled(ENABLED_KEY, evaluator);

        resolver.classify(null);

        assertNull(resolver.cachedDecision());
        assertEquals(TelemetryLevel.FULL, resolver.level());
    }

    @Test
    @DisplayName("a null otelEnabled value fails open")
    void otelEnabledNullFailsOpen() {
        RecordingEvaluator<Boolean> evaluator = new RecordingEvaluator<>(enabled(null, Reason.STATIC));
        TelemetryLevelResolver<Boolean> resolver = TelemetryLevelResolver.otelEnabled(ENABLED_KEY, evaluator);

        resolver.classify(null);

        assertEquals(TelemetryLevel.FULL, resolver.level());
    }

    @Test
    @DisplayName("otelEnabled re-classifies on its own key and ignores telemetryLevel's")
    void otelEnabledReclassifiesOnItsOwnKey() {
        RecordingEvaluator<Boolean> evaluator =
                new RecordingEvaluator<>(enabled(true, Reason.STATIC), enabled(false, Reason.STATIC));
        TelemetryLevelResolver<Boolean> resolver = TelemetryLevelResolver.otelEnabled(ENABLED_KEY, evaluator);

        resolver.classify(null);
        resolver.classify(List.of(FLAG_KEY));
        assertEquals(TelemetryLevel.FULL, resolver.level(), "a telemetryLevel change is not ours");

        resolver.classify(List.of(ENABLED_KEY));
        assertEquals(TelemetryLevel.OFF, resolver.level());

        assertEquals(2, evaluator.count());
    }

    @Test
    @DisplayName("a targeted otelEnabled is evaluated for every span")
    void targetedOtelEnabledEvaluatedEveryTime() {
        RecordingEvaluator<Boolean> evaluator =
                new RecordingEvaluator<>(enabled(false, Reason.TARGETING_MATCH));
        TelemetryLevelResolver<Boolean> resolver = TelemetryLevelResolver.otelEnabled(ENABLED_KEY, evaluator);

        resolver.classify(null);
        for (int i = 0; i < 3; i++) {
            assertEquals(TelemetryLevel.OFF, resolver.level());
        }

        assertNull(resolver.cachedDecision());
        assertEquals(4, evaluator.count());
    }
}

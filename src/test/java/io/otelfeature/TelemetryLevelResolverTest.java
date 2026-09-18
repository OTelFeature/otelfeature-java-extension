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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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

    /** An evaluation function that records every call it receives. */
    static class RecordingEvaluator implements Function<EvaluationContext, FlagEvaluationDetails<String>> {
        final List<EvaluationContext> calls = new ArrayList<>();
        private final List<FlagEvaluationDetails<String>> responses;

        @SafeVarargs
        RecordingEvaluator(FlagEvaluationDetails<String>... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public FlagEvaluationDetails<String> apply(EvaluationContext context) {
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

    // ----------------------------------------------------------------------
    // The rule
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("a static flag is evaluated once, however many spans ask")
    void staticFlagEvaluatedOnce() {
        RecordingEvaluator evaluator = new RecordingEvaluator(details("IO", Reason.STATIC));
        TelemetryLevelResolver resolver = new TelemetryLevelResolver(FLAG_KEY, evaluator);

        resolver.classify(null);
        for (int i = 0; i < 100; i++) {
            assertTrue(resolver.shouldSuppressInternal());
        }

        assertEquals(1, evaluator.count(), "only the context-free probe should have evaluated");
        assertEquals(Boolean.TRUE, resolver.cachedDecision());
    }

    @ParameterizedTest
    @EnumSource(
            value = Reason.class,
            names = {"TARGETING_MATCH", "DEFAULT", "SPLIT", "DISABLED", "ERROR", "CACHED", "UNKNOWN"})
    @DisplayName("any non-STATIC reason is evaluated for every span")
    void nonStaticReasonsEvaluatedEveryTime(Reason reason) {
        RecordingEvaluator evaluator = new RecordingEvaluator(details("IO", reason));
        TelemetryLevelResolver resolver = new TelemetryLevelResolver(FLAG_KEY, evaluator);

        resolver.classify(null);
        for (int i = 0; i < 5; i++) {
            assertTrue(resolver.shouldSuppressInternal());
        }

        assertEquals(6, evaluator.count(), "probe plus one evaluation per span");
        assertNull(resolver.cachedDecision());
    }

    @Test
    @DisplayName("the classifying evaluation carries no context")
    void probeIsContextFree() {
        RecordingEvaluator evaluator = new RecordingEvaluator(details("FULL", Reason.STATIC));
        TelemetryLevelResolver resolver = new TelemetryLevelResolver(FLAG_KEY, evaluator);

        resolver.classify(null);

        assertEquals(1, evaluator.count());
        assertNull(evaluator.calls.get(0), "the probe must not depend on an evaluation context");
    }

    @Test
    @DisplayName("FULL means show everything")
    void fullMeansNoSuppression() {
        RecordingEvaluator evaluator = new RecordingEvaluator(details("FULL", Reason.STATIC));
        TelemetryLevelResolver resolver = new TelemetryLevelResolver(FLAG_KEY, evaluator);

        resolver.classify(null);

        assertFalse(resolver.shouldSuppressInternal());
        assertEquals(Boolean.FALSE, resolver.cachedDecision());
    }

    @Test
    @DisplayName("an unrecognised value is treated as no suppression")
    void unknownValueMeansNoSuppression() {
        RecordingEvaluator evaluator = new RecordingEvaluator(details("something-else", Reason.STATIC));
        TelemetryLevelResolver resolver = new TelemetryLevelResolver(FLAG_KEY, evaluator);

        resolver.classify(null);

        assertFalse(resolver.shouldSuppressInternal());
    }

    @Test
    @DisplayName("before any provider event, spans evaluate directly")
    void evaluatesDirectlyBeforeFirstEvent() {
        RecordingEvaluator evaluator = new RecordingEvaluator(details("IO", Reason.STATIC));
        TelemetryLevelResolver resolver = new TelemetryLevelResolver(FLAG_KEY, evaluator);

        assertNull(resolver.cachedDecision());
        assertTrue(resolver.shouldSuppressInternal());
        assertEquals(1, evaluator.count());
    }

    // ----------------------------------------------------------------------
    // Re-classification
    // ----------------------------------------------------------------------

    @Test
    @DisplayName("a change to our flag re-classifies")
    void changeToOurFlagReclassifies() {
        RecordingEvaluator evaluator =
                new RecordingEvaluator(details("FULL", Reason.STATIC), details("IO", Reason.STATIC));
        TelemetryLevelResolver resolver = new TelemetryLevelResolver(FLAG_KEY, evaluator);

        resolver.classify(null);
        assertFalse(resolver.shouldSuppressInternal());

        resolver.classify(List.of(FLAG_KEY));
        assertTrue(resolver.shouldSuppressInternal());

        assertEquals(2, evaluator.count(), "two probes, and no per-span evaluation at all");
    }

    @Test
    @DisplayName("a change to some other flag is ignored")
    void changeToAnotherFlagIgnored() {
        RecordingEvaluator evaluator = new RecordingEvaluator(details("FULL", Reason.STATIC));
        TelemetryLevelResolver resolver = new TelemetryLevelResolver(FLAG_KEY, evaluator);

        resolver.classify(null);
        resolver.classify(List.of("someUnrelatedFlag"));
        resolver.shouldSuppressInternal();

        assertEquals(1, evaluator.count());
    }

    @Test
    @DisplayName("an event with no key list always re-classifies")
    void eventWithoutKeysAlwaysReclassifies() {
        RecordingEvaluator evaluator = new RecordingEvaluator(details("FULL", Reason.STATIC));
        TelemetryLevelResolver resolver = new TelemetryLevelResolver(FLAG_KEY, evaluator);

        resolver.classify(null);
        resolver.classify(List.of());
        resolver.classify(null);

        assertEquals(3, evaluator.count());
    }

    @Test
    @DisplayName("attaching targeting switches caching off")
    void attachingTargetingSwitchesCachingOff() {
        RecordingEvaluator evaluator = new RecordingEvaluator(
                details("FULL", Reason.STATIC), details("IO", Reason.TARGETING_MATCH));
        TelemetryLevelResolver resolver = new TelemetryLevelResolver(FLAG_KEY, evaluator);

        resolver.classify(null);
        assertEquals(Boolean.FALSE, resolver.cachedDecision());

        resolver.classify(List.of(FLAG_KEY));
        assertNull(resolver.cachedDecision(), "a targeted flag must be evaluated per span");
    }

    @Test
    @DisplayName("removing targeting switches caching back on")
    void removingTargetingSwitchesCachingBackOn() {
        RecordingEvaluator evaluator = new RecordingEvaluator(
                details("IO", Reason.TARGETING_MATCH), details("IO", Reason.STATIC));
        TelemetryLevelResolver resolver = new TelemetryLevelResolver(FLAG_KEY, evaluator);

        resolver.classify(null);
        assertNull(resolver.cachedDecision());

        resolver.classify(List.of(FLAG_KEY));
        assertEquals(Boolean.TRUE, resolver.cachedDecision());
    }
}

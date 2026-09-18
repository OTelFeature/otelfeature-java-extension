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

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Reason;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Decides how often {@code telemetryLevel} actually has to be evaluated.
 *
 * <p>The flag is asked about on every span, but most of the time the answer
 * cannot have changed since the last one: a flag with no {@code targeting}
 * block is one {@code defaultVariant} for the whole world. flagd says exactly
 * that, in the resolution reason:
 *
 * <table border="1">
 *   <caption>What each reason implies</caption>
 *   <tr><th>reason</th><th>flagd produced it because</th><th>same for every span?</th></tr>
 *   <tr><td>{@code STATIC}</td><td>no {@code targeting}, served {@code defaultVariant}</td><td>yes</td></tr>
 *   <tr><td>{@code TARGETING_MATCH}</td><td>{@code targeting} ran and picked a variant</td><td>no</td></tr>
 *   <tr><td>{@code DEFAULT}</td><td>{@code targeting} ran and matched nothing</td><td>no</td></tr>
 *   <tr><td>{@code DISABLED}</td><td>flag state is {@code DISABLED}</td><td>not worth assuming</td></tr>
 *   <tr><td>{@code ERROR}</td><td>flag missing, provider not ready, ...</td><td>not yet knowable</td></tr>
 * </table>
 *
 * <p>So {@link #classify(List)} evaluates the flag <em>once, without an
 * evaluation context</em>, purely to read the reason back. {@code STATIC} - and
 * only {@code STATIC} - is kept, and every subsequent span is then answered
 * from an {@link AtomicReference} with no evaluation and no hook chain at all.
 * Any other reason means the value can depend on who is asking, so
 * {@link #shouldSuppressInternal()} evaluates for every span.
 *
 * <p>{@code classify} is driven by the provider's own events (see
 * {@link FlagdTelemetryLevel}), so attaching a {@code targeting} block to
 * {@code telemetryLevel} makes the next classification report
 * {@code TARGETING_MATCH}, caching switches itself off, and spans start being
 * evaluated individually. Remove the targeting and caching resumes by itself.
 */
final class TelemetryLevelResolver implements TelemetryLevelSource {

    private static final Logger log = Logger.getLogger(TelemetryLevelResolver.class.getName());

    /** Variant value that means "suppress INTERNAL spans". */
    static final String SUPPRESS_VALUE = "IO";

    /** Returned when the flag cannot be resolved: show everything. */
    static final String DEFAULT_VALUE = "FULL";

    private final String flagKey;
    private final Function<EvaluationContext, FlagEvaluationDetails<String>> evaluate;

    /**
     * The kept answer, or {@code null} for "not cacheable, evaluate per span".
     * Written by the provider's event thread, read by every span.
     */
    private final AtomicReference<Boolean> cached = new AtomicReference<>();

    TelemetryLevelResolver(
            String flagKey, Function<EvaluationContext, FlagEvaluationDetails<String>> evaluate) {
        this.flagKey = flagKey;
        this.evaluate = evaluate;
    }

    /**
     * Evaluates once, context-free, and decides whether the answer can be kept.
     *
     * <p>Wired to the provider's READY / CONFIGURATION_CHANGED / ERROR / STALE
     * events rather than called from the span path.
     *
     * @param flagsChanged the keys the provider reported as changed, or
     *     {@code null}/empty when it didn't say (READY, ERROR, STALE)
     */
    void classify(List<String> flagsChanged) {
        // When the provider names the keys that moved and ours isn't among
        // them, nothing about our classification can have changed.
        if (flagsChanged != null && !flagsChanged.isEmpty() && !flagsChanged.contains(flagKey)) {
            return;
        }

        FlagEvaluationDetails<String> details = evaluate.apply(null);

        // Deliberately STATIC only. TARGETING_MATCH and DEFAULT both mean a
        // targeting block ran, so the answer belongs to one evaluation context
        // and not to the process; DISABLED and ERROR are not worth betting on.
        Boolean decision = Reason.STATIC.name().equals(details.getReason())
                ? Boolean.valueOf(suppresses(details.getValue()))
                : null;

        Boolean previous = cached.getAndSet(decision);
        if (!Objects.equals(previous, decision)) {
            if (decision == null) {
                log.info("otelfeature-java-extension: " + flagKey + " resolves with reason="
                        + details.getReason() + " - evaluating per span");
            } else {
                log.info("otelfeature-java-extension: " + flagKey + " is static (reason="
                        + details.getReason() + ") - INTERNAL spans "
                        + (decision ? "suppressed" : "visible")
                        + ", no further evaluation");
            }
        }
    }

    @Override
    public boolean shouldSuppressInternal() {
        Boolean decision = cached.get();
        if (decision != null) {
            // The point of the whole exercise: a volatile read, and done.
            return decision;
        }
        return suppresses(evaluate.apply(evaluationContext()).getValue());
    }

    /**
     * The evaluation context handed to a non-static evaluation.
     *
     * <p>{@code null} for now - this is the seam for contextual evaluation.
     * Describing the span, and eventually its OTel attributes, to a targeting
     * rule goes here; the caching rule above already handles such a flag
     * correctly by evaluating it for every span.
     *
     * @return the evaluation context, or {@code null} for none
     */
    private EvaluationContext evaluationContext() {
        return null;
    }

    /** Whether the resolved value means "suppress". */
    private static boolean suppresses(String value) {
        return SUPPRESS_VALUE.equalsIgnoreCase(value);
    }

    /**
     * The kept answer, or {@code null} when every span is evaluated. For tests.
     *
     * @return the cached decision, or {@code null} if there isn't one
     */
    Boolean cachedDecision() {
        return cached.get();
    }
}

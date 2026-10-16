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

import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Reason;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One telemetry flag: how often it actually has to be evaluated, and what it
 * means when it cannot be.
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
 * {@link #value()} evaluates for every span.
 *
 * <p>{@code classify} is driven by the provider's own events (see
 * {@link FlagdTelemetryPolicy}), so attaching a {@code targeting} block to the
 * flag makes the next classification report {@code TARGETING_MATCH}, caching
 * switches itself off, and spans start being evaluated individually. Remove the
 * targeting and caching resumes by itself.
 *
 * <p>A resolution that carries an error code, no value, or a value the flag
 * cannot interpret yields the {@code fallback} - the field's startup default -
 * and is logged once per transition into that problem rather than once per
 * span. A flag that flaps between two states logs each flip; racing threads
 * may occasionally log the same transition twice. "Provider not ready", which
 * every startup passes through, is logged at FINE.
 *
 * @param <T> the flag's value type
 * @param <R> what the flag means to the policy
 */
final class TelemetryFlag<T, R> {

    private static final Logger log = Logger.getLogger(TelemetryFlag.class.getName());

    private final String flagKey;
    private final Function<EvaluationContext, FlagEvaluationDetails<T>> evaluate;
    private final Function<T, R> interpret;
    private final R fallback;

    /**
     * The kept answer, or {@code null} for "not cacheable, evaluate per span".
     * Written by the provider's event thread, read by every span.
     */
    private final AtomicReference<R> cached = new AtomicReference<>();

    /** Marker for a resolution that came back without a value. */
    private static final String NO_VALUE = "NO_VALUE";

    /** Marker for a value the flag does not recognise, e.g. an unknown variant. */
    private static final String UNRECOGNISED_VALUE = "UNRECOGNISED_VALUE";

    /**
     * The problem last logged ({@link ErrorCode} name or one of the markers
     * above), so a failing flag is reported once, not per span.
     */
    private final AtomicReference<String> lastProblem = new AtomicReference<>();

    /**
     * @param flagKey the flag's key
     * @param evaluate evaluates the flag for a context ({@code null} for none);
     *     must not throw
     * @param interpret what a successfully resolved, non-null value means
     * @param fallback what the flag means when it cannot be resolved
     */
    TelemetryFlag(
            String flagKey,
            Function<EvaluationContext, FlagEvaluationDetails<T>> evaluate,
            Function<T, R> interpret,
            R fallback) {
        this.flagKey = flagKey;
        this.evaluate = evaluate;
        this.interpret = interpret;
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    /**
     * Evaluates once, context-free, and decides whether the answer can be kept.
     *
     * <p>Wired to the provider's READY / CONFIGURATION_CHANGED / ERROR / STALE
     * events rather than called from the span path.
     *
     * <p>{@code synchronized} because the SDK dispatches event handlers on a
     * thread pool: two events in quick succession could otherwise evaluate the
     * old and the new ruleset concurrently and write in the wrong order,
     * leaving a stale answer - say "disabled" - cached until the next event.
     * The span path never takes this lock.
     *
     * @param flagsChanged the keys the provider reported as changed, or
     *     {@code null}/empty when it didn't say (READY, ERROR, STALE)
     */
    synchronized void classify(List<String> flagsChanged) {
        // When the provider names the keys that moved and ours isn't among
        // them, nothing about our classification can have changed.
        if (flagsChanged != null && !flagsChanged.isEmpty() && !flagsChanged.contains(flagKey)) {
            return;
        }

        FlagEvaluationDetails<T> details = evaluate.apply(null);
        R meaning = interpret(details);

        // Deliberately STATIC only. TARGETING_MATCH and DEFAULT both mean a
        // targeting block ran, so the answer belongs to one evaluation context
        // and not to the process; DISABLED and ERROR are not worth betting on.
        R decision = Reason.STATIC.name().equals(details.getReason()) ? meaning : null;

        R previous = cached.getAndSet(decision);
        if (!Objects.equals(previous, decision)) {
            if (decision == null) {
                log.info("otelfeature-java-extension: " + flagKey + " resolves with reason="
                        + details.getReason() + " - evaluating per span");
            } else {
                log.info("otelfeature-java-extension: " + flagKey + " is static (reason="
                        + details.getReason() + ") - " + decision + ", no further evaluation");
            }
        }
    }

    /**
     * What the flag means for the next span: the kept answer if there is one,
     * otherwise a fresh evaluation.
     *
     * @return never {@code null}
     */
    R value() {
        return value(() -> null);
    }

    /**
     * What the flag means for the next span.
     *
     * @param context builds the span's evaluation context; called only when
     *     the flag is not static and actually has to be evaluated, so the
     *     cached path builds nothing
     * @return never {@code null}
     */
    R value(Supplier<EvaluationContext> context) {
        R decision = cached.get();
        if (decision != null) {
            // The point of the whole exercise: a volatile read, and done.
            return decision;
        }
        return interpret(evaluate.apply(context.get()));
    }

    private R interpret(FlagEvaluationDetails<T> details) {
        ErrorCode error = details.getErrorCode();
        if (error != null || Reason.ERROR.name().equals(details.getReason())) {
            String code = (error == null ? ErrorCode.GENERAL : error).name();
            report(code, details.getErrorMessage(), error == ErrorCode.PROVIDER_NOT_READY);
            return fallback;
        }
        if (Reason.DISABLED.name().equals(details.getReason())) {
            // A flag switched off in flagd hands back the caller's default
            // value; what that means is the startup default's business.
            resolved();
            return fallback;
        }
        T value = details.getValue();
        if (value == null) {
            report(NO_VALUE, null, false);
            return fallback;
        }
        R meaning = interpret.apply(value);
        if (meaning == null) {
            report(UNRECOGNISED_VALUE, "'" + value + "'", false);
            return fallback;
        }
        resolved();
        return meaning;
    }

    /** Marks the flag healthy again, logging the recovery if it was not. */
    private void resolved() {
        // Read first: on a healthy, targeted flag this runs on every span, and
        // an unconditional getAndSet would be a contended write each time.
        if (lastProblem.get() != null && lastProblem.getAndSet(null) != null) {
            log.info("otelfeature-java-extension: " + flagKey + " resolves again");
        }
    }

    /**
     * Logs a problem the first time it is seen - once per transition into it,
     * not once per span.
     *
     * @param problem an {@link ErrorCode} name, or one of our own markers
     * @param detail extra context for the message, or {@code null}
     * @param quietly {@code true} for the expected startup race
     *     (provider not ready yet), logged at FINE instead of WARNING
     */
    private void report(String problem, String detail, boolean quietly) {
        if (problem.equals(lastProblem.get()) || problem.equals(lastProblem.getAndSet(problem))) {
            return;
        }
        String message = "otelfeature-java-extension: " + flagKey + " cannot be used (" + problem
                + (detail == null || detail.isBlank() ? "" : ": " + detail)
                + ") - using the startup default " + fallback
                + "; logged once until it resolves or the problem changes";
        log.log(quietly ? Level.FINE : Level.WARNING, message);
    }


    /**
     * The kept answer, or {@code null} when every span is evaluated. For tests.
     *
     * @return the cached decision, or {@code null} if there isn't one
     */
    R cachedDecision() {
        return cached.get();
    }
}

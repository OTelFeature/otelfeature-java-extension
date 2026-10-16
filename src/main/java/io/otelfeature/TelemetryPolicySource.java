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

/**
 * Answers the one question every enforcement point asks of the feature flags:
 * what is the {@link TelemetryPolicy} right now?
 *
 * <p>Deliberately one method, and deliberately not tied to OpenFeature: an
 * enforcement point has no business knowing where the answer comes from, and
 * a test can supply one with a lambda.
 */
@FunctionalInterface
public interface TelemetryPolicySource {

    /**
     * Returns the policy for a span that is about to start.
     *
     * <p>A static flag answers without looking at the span; a targeted one is
     * evaluated against the span's context (see {@link EvaluationContextFactory}).
     *
     * <p>Called on the span hot path, from whatever thread created the span, so
     * implementations must be thread-safe, cheap and must never throw: a flag
     * that cannot be resolved falls back to its startup default.
     *
     * @param span the span the decision is for
     * @return the policy that span is subject to
     */
    TelemetryPolicy policy(SpanStart span);

    /**
     * The policy, if it does not depend on the span at all - every flag
     * static, or tracing disabled outright.
     *
     * <p>Enforcement points ask this first, so that the common, static case
     * builds nothing per span: not even the {@link SpanStart}, whose start
     * attributes the SDK copies on every read.
     *
     * @return the span-independent policy, or {@code null} if
     *     {@link #policy(SpanStart)} has to be asked
     */
    default TelemetryPolicy cachedPolicy() {
        return null;
    }

    /**
     * The policy from the global switches alone ({@code OTEL_SDK_DISABLED},
     * {@code otelEnabled}, {@code telemetryLevel}), with {@code record} taken
     * as {@code true}: for decisions a per-span flag cannot change, so it need
     * not be evaluated.
     *
     * @param span the span the decision is for
     * @return the policy without {@code recordSpan}
     */
    default TelemetryPolicy globalPolicy(SpanStart span) {
        TelemetryPolicy p = policy(span);
        return TelemetryPolicy.of(p.enabled(), p.suppressInternal(), true);
    }

    /**
     * {@link #globalPolicy(SpanStart)} without the span, if the global switches
     * don't depend on it.
     *
     * @return the span-independent global policy, or {@code null}
     */
    default TelemetryPolicy cachedGlobalPolicy() {
        TelemetryPolicy p = cachedPolicy();
        return p == null ? null : TelemetryPolicy.of(p.enabled(), p.suppressInternal(), true);
    }

    /** Releases whatever backs this source. The default does nothing. */
    default void shutdown() {}
}

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

import io.opentelemetry.api.trace.SpanKind;
import java.util.Locale;
import java.util.function.Function;

/**
 * Everything the telemetry flags currently say, as one value.
 *
 * <p>Every enforcement point reads one policy per decision instead of asking
 * individual flags, so there is exactly one place where a missing flag or a
 * provider error has to degrade safely: {@link FlagdTelemetryPolicy}, which
 * falls back field by field to {@link #startupDefaults}.
 *
 * <ul>
 *   <li>{@code enabled} - {@code false} when {@code otelEnabled=false} or
 *       {@code telemetryLevel=OFF}: drop every span.</li>
 *   <li>{@code suppressInternal} - {@code telemetryLevel=IO}: drop
 *       {@code INTERNAL} spans.</li>
 *   <li>{@code record} - {@code recordSpan}, per span: {@code false} drops this
 *       one span. It never re-enables what {@code enabled} turned off.</li>
 *   <li>{@code sampleRatio} - {@code traceSampleRatio}: the fraction of new
 *       traces to sample, {@code 0.0}-{@code 1.0}; {@code NaN} for "no
 *       override, the agent's configured sampler decides".</li>
 * </ul>
 *
 * <p>Further flags (sample ratio, disabled instrumentation scopes) add a field
 * each.
 *
 * @param enabled whether any span may be exported
 * @param suppressInternal whether {@code INTERNAL} spans are dropped
 * @param record whether this span is recorded ({@code recordSpan})
 * @param sampleRatio the root sampling ratio, or {@code NaN} for no override
 */
public record TelemetryPolicy(boolean enabled, boolean suppressInternal, boolean record, double sampleRatio) {

    /** "No override": the agent's configured sampler decides. */
    static final double DELEGATE = Double.NaN;

    /**
     * Which fields a decision needs, so a flag is only evaluated where it can
     * change the outcome.
     */
    public enum Need {
        /** {@code enabled} and {@code suppressInternal}: the global switches. */
        GLOBAL,
        /** ... and {@code record}: a decision about this one span. */
        SPAN,
        /** ... and {@code sampleRatio}: a decision about a new trace. */
        ROOT
    }

    /**
     * This policy with only the fields {@code need} covers; the rest at their
     * neutral values ({@code record=true}, no sample ratio).
     *
     * @param need the fields to keep
     * @return the narrowed policy, a shared instance unless it keeps a ratio
     */
    public TelemetryPolicy narrow(Need need) {
        return switch (need) {
            case ROOT -> this;
            case SPAN -> of(enabled, suppressInternal, record);
            case GLOBAL -> of(enabled, suppressInternal, true);
        };
    }

    /** Trace everything: the stock agent's behaviour. */
    public static final TelemetryPolicy FULL = new TelemetryPolicy(true, false, true, DELEGATE);

    /** Drop {@code INTERNAL} spans, keep the edges. */
    public static final TelemetryPolicy IO = new TelemetryPolicy(true, true, true, DELEGATE);

    /** Drop every span. */
    public static final TelemetryPolicy DISABLED = new TelemetryPolicy(false, false, true, DELEGATE);

    /**
     * Drop this span ({@code recordSpan=false}); tracing as such stays on.
     * {@code suppressInternal} is not carried: the span is dropped whatever its
     * kind, and the policy is per span.
     */
    public static final TelemetryPolicy NOT_RECORDED = new TelemetryPolicy(true, false, false, DELEGATE);

    /**
     * The policy for the given fields, without allocating: this is called on
     * the span path.
     *
     * @param enabled whether any span may be exported
     * @param suppressInternal whether {@code INTERNAL} spans are dropped
     * @return a shared instance
     */
    static TelemetryPolicy of(boolean enabled, boolean suppressInternal) {
        return of(enabled, suppressInternal, true);
    }

    /**
     * The policy for the given fields, without allocating. Precedence:
     * {@code enabled=false} wins over everything, then {@code record=false}.
     *
     * @param enabled whether any span may be exported
     * @param suppressInternal whether {@code INTERNAL} spans are dropped
     * @param record whether this span is recorded
     * @return a shared instance
     */
    static TelemetryPolicy of(boolean enabled, boolean suppressInternal, boolean record) {
        if (!enabled) {
            return DISABLED;
        }
        if (!record) {
            return NOT_RECORDED;
        }
        return suppressInternal ? IO : FULL;
    }

    /**
     * The policy for the given fields. Allocates only when a sample ratio is
     * actually set and still matters (tracing enabled, span recorded).
     *
     * @param enabled whether any span may be exported
     * @param suppressInternal whether {@code INTERNAL} spans are dropped
     * @param record whether this span is recorded
     * @param sampleRatio the root sampling ratio, or {@code NaN}
     * @return the policy
     */
    static TelemetryPolicy of(boolean enabled, boolean suppressInternal, boolean record, double sampleRatio) {
        if (!enabled || !record || Double.isNaN(sampleRatio)) {
            return of(enabled, suppressInternal, record);
        }
        return new TelemetryPolicy(true, suppressInternal, true, sampleRatio);
    }

    /**
     * Whether {@code traceSampleRatio} overrides the agent's sampler for new
     * traces.
     *
     * @return {@code true} if {@link #sampleRatio()} is set
     */
    public boolean overridesSampleRatio() {
        return !Double.isNaN(sampleRatio);
    }

    /**
     * {@code traceSampleRatio} as the policy reads it: above {@code 1.0} it is
     * clamped to {@code 1.0}; a negative value, or anything that isn't a finite
     * number, is no answer at all, and the fallback applies. A negative ratio
     * is far more likely a typo than a wish to drop every trace, and failing
     * open is the safer reading.
     *
     * @param value the flag's value
     * @return the ratio, or {@code null}
     */
    static Double clampRatio(Double value) {
        if (value == null || value.isNaN() || value.isInfinite() || value < 0.0) {
            return null;
        }
        return Math.min(1.0, value);
    }

    /**
     * Whether a span of the given kind is dropped under this policy.
     *
     * @param kind the span's kind
     * @return {@code true} if the span should be suppressed
     */
    public boolean suppresses(SpanKind kind) {
        return !enabled || !record || (suppressInternal && kind == SpanKind.INTERNAL);
    }

    /**
     * The per-field fallback, captured once at startup from the environment.
     *
     * <p>This is what every field resolves to while flagd is unreachable, a flag
     * is missing, or it holds a value we cannot read - so that installing the
     * extension changes nothing until someone deliberately sets a flag.
     *
     * <ul>
     *   <li>{@code enabled} - {@code false} only if {@code OTEL_SDK_DISABLED}
     *       (or {@code -Dotel.sdk.disabled}) is {@code true}, and then it is a
     *       <b>hard floor</b>: flags never enable what {@code OTEL_SDK_DISABLED}
     *       turned off. Autoconfigure still calls this extension's customizer
     *       in that case, which then installs nothing.</li>
     *   <li>{@code suppressInternal} - {@code false}: the stock agent has no
     *       setting for it.</li>
     *   <li>{@code record} - {@code true}: the stock agent records every span
     *       it samples.</li>
     *   <li>{@code sampleRatio} - {@code NaN}: the agent's own sampler
     *       ({@code OTEL_TRACES_SAMPLER} / {@code OTEL_TRACES_SAMPLER_ARG}) is
     *       the floor, and decides whenever {@code traceSampleRatio} is
     *       missing or unreadable.</li>
     * </ul>
     *
     * @return the startup defaults
     */
    static TelemetryPolicy startupDefaults() {
        return startupDefaults(name -> {
            String property = System.getProperty(name.toLowerCase(Locale.ROOT).replace('_', '.'));
            return property != null ? property : System.getenv(name);
        });
    }

    /**
     * Visible for testing.
     *
     * @param config looks up a setting by its environment-variable name
     * @return the startup defaults
     */
    static TelemetryPolicy startupDefaults(Function<String, String> config) {
        boolean sdkDisabled = Boolean.parseBoolean(config.apply("OTEL_SDK_DISABLED"));
        return of(!sdkDisabled, false);
    }
}

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
 * </ul>
 *
 * <p>Further flags (sample ratio, disabled instrumentation scopes) add a field
 * each.
 *
 * @param enabled whether any span may be exported
 * @param suppressInternal whether {@code INTERNAL} spans are dropped
 * @param record whether this span is recorded ({@code recordSpan})
 */
public record TelemetryPolicy(boolean enabled, boolean suppressInternal, boolean record) {

    /** Trace everything: the stock agent's behaviour. */
    public static final TelemetryPolicy FULL = new TelemetryPolicy(true, false, true);

    /** Drop {@code INTERNAL} spans, keep the edges. */
    public static final TelemetryPolicy IO = new TelemetryPolicy(true, true, true);

    /** Drop every span. */
    public static final TelemetryPolicy DISABLED = new TelemetryPolicy(false, false, true);

    /** Drop this span ({@code recordSpan=false}); tracing as such stays on. */
    public static final TelemetryPolicy NOT_RECORDED = new TelemetryPolicy(true, false, false);

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

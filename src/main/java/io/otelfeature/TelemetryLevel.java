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

/**
 * How much tracing the flags currently allow.
 *
 * <p>Ordered from most to least: {@code FULL} exports every span, {@code IO}
 * drops {@code INTERNAL} spans and keeps the edges ({@code SERVER},
 * {@code CLIENT}, ...), {@code OFF} drops every span. {@code OFF} is reached
 * either through the {@code telemetryLevel} variant of the same name or through
 * {@code otelEnabled=false}.
 */
public enum TelemetryLevel {
    FULL,
    IO,
    OFF;

    /**
     * Reads a {@code telemetryLevel} variant value, failing open.
     *
     * @param value the resolved flag value, case-insensitive
     * @return the matching level, or {@link #FULL} for anything unrecognised
     */
    static TelemetryLevel parse(String value) {
        if (value != null) {
            for (TelemetryLevel level : values()) {
                if (level.name().equalsIgnoreCase(value.trim())) {
                    return level;
                }
            }
        }
        return FULL;
    }

    /**
     * Whether a span of the given kind is dropped at this level.
     *
     * @param kind the span's kind
     * @return {@code true} if the span should be suppressed
     */
    boolean suppresses(SpanKind kind) {
        return switch (this) {
            case FULL -> false;
            case IO -> kind == SpanKind.INTERNAL;
            case OFF -> true;
        };
    }
}

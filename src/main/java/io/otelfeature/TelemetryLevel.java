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
 * The variants of the {@code telemetryLevel} flag.
 *
 * <p>{@code FULL} exports every span, {@code IO} drops {@code INTERNAL} spans
 * and keeps the edges ({@code SERVER}, {@code CLIENT}, ...), {@code OFF} drops
 * every span. {@link FlagdTelemetryPolicy} folds the level into a
 * {@link TelemetryPolicy}: {@code OFF} clears {@code enabled}, {@code IO} sets
 * {@code suppressInternal}.
 */
enum TelemetryLevel {
    FULL,
    IO,
    OFF;

    /**
     * Reads a {@code telemetryLevel} variant value.
     *
     * @param value the resolved flag value, case-insensitive
     * @return the matching level, or {@code null} if the value is not one -
     *     the caller then falls back to the startup default
     */
    static TelemetryLevel parse(String value) {
        if (value != null) {
            for (TelemetryLevel level : values()) {
                if (level.name().equalsIgnoreCase(value.trim())) {
                    return level;
                }
            }
        }
        return null;
    }

    /**
     * The level a policy amounts to, for the {@code telemetryLevel} fallback.
     *
     * @param policy the startup defaults
     * @return {@code IO} if they suppress {@code INTERNAL} spans, else {@code FULL}
     */
    static TelemetryLevel of(TelemetryPolicy policy) {
        return policy.suppressInternal() ? IO : FULL;
    }
}

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

import java.util.logging.Logger;

/**
 * Where {@code enabled=false} is enforced, chosen once at startup with
 * {@code OTELFEATURE_ENFORCEMENT}.
 *
 * <ul>
 *   <li>{@code processor} (default) - {@link FilteringSpanProcessor} drops spans
 *       at {@code onEnd}: never batched, serialised or sent, but created and
 *       populated first.</li>
 *   <li>{@code sampler} - {@link FlagAwareSampler} drops local roots before they
 *       are recorded, and their subtree with them: never recorded, so no
 *       recording, processor or export cost (the instrumentation still runs
 *       and hands out non-recording spans).</li>
 * </ul>
 *
 * <p>{@code INTERNAL} suppression ({@code telemetryLevel=IO}) stays in the
 * processor in both modes.
 */
enum Enforcement {
    PROCESSOR,
    SAMPLER;

    private static final Logger log = Logger.getLogger(Enforcement.class.getName());

    static final String ENV = "OTELFEATURE_ENFORCEMENT";

    static Enforcement fromEnvironment() {
        return parse(System.getenv(ENV));
    }

    /**
     * @param value {@code processor} or {@code sampler}, case-insensitive
     * @return the mode; {@link #PROCESSOR} when unset or unrecognised
     */
    static Enforcement parse(String value) {
        if (value == null || value.isBlank()) {
            return PROCESSOR;
        }
        for (Enforcement mode : values()) {
            if (mode.name().equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        log.warning("otelfeature-java-extension: " + ENV + "='" + value
                + "' is neither 'processor' nor 'sampler', using 'processor'");
        return PROCESSOR;
    }
}

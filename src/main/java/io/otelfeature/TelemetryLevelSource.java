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
 * Answers the one question {@link FilteringSpanProcessor} asks of the feature
 * flag: should {@code INTERNAL} spans be suppressed right now?
 *
 * <p>Deliberately one method, and deliberately not tied to OpenFeature: the
 * span processor has no business knowing where the answer comes from, and a
 * test can supply one with a lambda.
 */
@FunctionalInterface
public interface TelemetryLevelSource {

    /**
     * Returns {@code true} if {@code INTERNAL} spans should be suppressed.
     *
     * <p>Called on the span hot path, from whatever thread created the span, so
     * implementations must be thread-safe and cheap.
     *
     * @return {@code true} if INTERNAL spans should be filtered out
     */
    boolean shouldSuppressInternal();

    /** Releases whatever backs this source. The default does nothing. */
    default void shutdown() {}
}

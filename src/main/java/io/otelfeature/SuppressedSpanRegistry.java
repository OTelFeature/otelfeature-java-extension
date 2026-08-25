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

import io.opentelemetry.api.trace.SpanContext;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry that maps suppressed span IDs to their parent span
 * contexts, enabling re-parenting of surviving children.
 *
 * <p>Shared between {@link FilteringSpanProcessor} (which records dropped
 * INTERNAL spans) and {@link ReparentingSpanExporter} (which re-parents
 * surviving children whose parents were suppressed).
 *
 * <p>The registry is bounded to prevent unbounded memory growth. When the
 * maximum size is reached, the registry is cleared entirely — a safety valve
 * that should never trigger in normal operation.
 */
class SuppressedSpanRegistry {

    private static final int MAX_SIZE = 10_000;

    private final ConcurrentHashMap<String, SpanContext> map = new ConcurrentHashMap<>();

    /**
     * Records that a span was suppressed, mapping its span ID to its parent
     * span context.
     *
     * @param spanId the suppressed span's ID
     * @param parentContext the suppressed span's parent context
     */
    void record(String spanId, SpanContext parentContext) {
        if (map.size() >= MAX_SIZE) {
            map.clear();
        }
        map.put(spanId, parentContext);
    }

    /**
     * Resolves the nearest non-suppressed ancestor for a given span ID by
     * following the chain of suppressed spans.
     *
     * @param spanId the span ID to resolve
     * @return the resolved ancestor span context, or {@code null} if the span
     *         ID is not in the registry (was not suppressed)
     */
    SpanContext resolve(String spanId) {
        SpanContext resolved = map.get(spanId);
        if (resolved == null) {
            return null;
        }
        // Follow the chain: if the resolved parent was also suppressed,
        // keep following until we find a non-suppressed ancestor.
        int depth = 0;
        while (depth < 100) {
            String resolvedId = resolved.getSpanId();
            SpanContext next = map.get(resolvedId);
            if (next == null || next.getSpanId().equals(resolvedId)) {
                break;
            }
            resolved = next;
            depth++;
        }
        return resolved;
    }

    /**
     * Returns the current number of entries in the registry.
     *
     * @return the current size
     */
    int size() {
        return map.size();
    }

    /**
     * Clears all entries from the registry.
     */
    void clear() {
        map.clear();
    }
}

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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe registry that maps suppressed span IDs to their parent span
 * contexts, enabling re-parenting of surviving children.
 *
 * <p>Shared between {@link FilteringSpanProcessor} (which records every span
 * it drops - {@code INTERNAL} ones at {@code IO}, all of them at {@code OFF})
 * and {@link ReparentingSpanExporter} (which re-parents surviving children
 * whose parents were suppressed).
 *
 * <p>The registry is bounded, and under {@code OFF} - where every span is
 * recorded - the bound is reached routinely. So it is enforced by sweeping out
 * entries for spans that have <em>already ended</em>, never in-flight ones: an
 * in-flight span whose entry vanished would be exported at {@code onEnd}, and
 * show up as a stray span while tracing is supposedly off. In-flight entries
 * are bounded by concurrency, not throughput. Sweeps run once per
 * {@code sweepAt} new entries, so they cost O(1) amortised per span.
 *
 * <p>Only if in-flight entries alone reach {@code hardMax} - spans that are
 * started and never ended, i.e. an instrumentation leak - is the registry
 * cleared entirely. A span whose entry is dropped that way is exported rather
 * than suppressed, which is the safe direction to fail in.
 */
class SuppressedSpanRegistry {

    private static final int DEFAULT_SWEEP_AT = 10_000;
    private static final int DEFAULT_HARD_MAX = 100_000;

    /** A suppressed span's parent, and whether the span has ended yet. */
    private static final class Entry {
        final SpanContext parent;
        volatile boolean ended;

        Entry(SpanContext parent) {
            this.parent = parent;
        }
    }

    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();
    private final int sweepAt;
    private final int hardMax;
    private final AtomicInteger nextSweep;

    SuppressedSpanRegistry() {
        this(DEFAULT_SWEEP_AT, DEFAULT_HARD_MAX);
    }

    /**
     * Visible for testing: small limits make sweeps observable.
     *
     * @param sweepAt how many new entries between sweeps
     * @param hardMax in-flight entries beyond which the registry is cleared
     */
    SuppressedSpanRegistry(int sweepAt, int hardMax) {
        this.sweepAt = sweepAt;
        this.hardMax = hardMax;
        this.nextSweep = new AtomicInteger(sweepAt);
    }

    /**
     * Records that a span was suppressed, mapping its span ID to its parent
     * span context.
     *
     * @param spanId the suppressed span's ID
     * @param parentContext the suppressed span's parent context
     */
    void record(String spanId, SpanContext parentContext) {
        if (map.size() >= nextSweep.get()) {
            sweep();
        }
        map.put(spanId, new Entry(parentContext));
    }

    /**
     * Marks a suppressed span as ended, so a later sweep may drop its entry.
     *
     * @param spanId the span ID that ended
     */
    void markEnded(String spanId) {
        Entry entry = map.get(spanId);
        if (entry != null) {
            entry.ended = true;
        }
    }

    private synchronized void sweep() {
        if (map.size() < nextSweep.get()) {
            return; // another thread swept while we waited
        }
        map.values().removeIf(entry -> entry.ended);
        if (map.size() >= hardMax) {
            // Only spans that were never ended are left: a leak upstream.
            map.clear();
        }
        // Next sweep after another sweepAt entries, however many survived.
        nextSweep.set(map.size() + sweepAt);
    }

    /**
     * Whether a span was suppressed at {@code onStart}.
     *
     * <p>{@link FilteringSpanProcessor#onEnd} uses this instead of re-reading
     * the feature flag, so a span's fate is decided exactly once.
     *
     * @param spanId the span ID to check
     * @return {@code true} if the span was recorded as suppressed
     */
    boolean isSuppressed(String spanId) {
        return map.containsKey(spanId);
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
        Entry entry = map.get(spanId);
        if (entry == null) {
            return null;
        }
        SpanContext resolved = entry.parent;
        // Follow the chain: if the resolved parent was also suppressed,
        // keep following until we find a non-suppressed ancestor.
        int depth = 0;
        while (depth < 100) {
            String resolvedId = resolved.getSpanId();
            Entry next = map.get(resolvedId);
            if (next == null || next.parent.getSpanId().equals(resolvedId)) {
                break;
            }
            resolved = next.parent;
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
        nextSweep.set(sweepAt);
    }
}

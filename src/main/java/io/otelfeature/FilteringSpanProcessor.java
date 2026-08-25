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
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * Wraps a delegate {@link SpanProcessor} and drops {@code INTERNAL} spans when
 * suppression is active (flagd {@code telemetryLevel} flag set to {@code "IO"}).
 *
 * <p>Filtering at the {@link SpanProcessor} level means dropped spans never enter
 * the {@code BatchSpanProcessor} queue, saving memory and CPU compared to
 * exporter-level filtering. The span is still created and recorded by the SDK
 * (so its context is valid for child propagation), but it is never batched,
 * serialized, or sent over the network.
 *
 * <p>When a span is dropped, its ID and parent context are recorded in a
 * {@link SuppressedSpanRegistry} so that {@link ReparentingSpanExporter} can
 * re-parent surviving children to the nearest non-suppressed ancestor.
 *
 * <p>When suppression is inactive, all spans are passed through unchanged
 * with minimal overhead: one {@code AtomicReference.get()} (volatile read)
 * and one enum comparison per span.
 */
public class FilteringSpanProcessor implements SpanProcessor {

    private final SpanProcessor delegate;
    private final FlagdClient flagdClient;
    private final SuppressedSpanRegistry registry;

    public FilteringSpanProcessor(SpanProcessor delegate, FlagdClient flagdClient,
                                  SuppressedSpanRegistry registry) {
        this.delegate = delegate;
        this.flagdClient = flagdClient;
        this.registry = registry;
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        delegate.onStart(parentContext, span);
    }

    @Override
    public boolean isStartRequired() {
        return delegate.isStartRequired();
    }

    @Override
    public void onEnd(ReadableSpan span) {
        if (flagdClient.shouldSuppressInternal() && span.getKind() == SpanKind.INTERNAL) {
            // Record this span's ID → parent context so children can be re-parented
            SpanContext parentContext = span.getParentSpanContext();
            if (parentContext != null && parentContext.isValid()) {
                registry.record(span.getSpanContext().getSpanId(), parentContext);
            }
            return; // drop — don't forward to delegate (BatchSpanProcessor)
        }
        delegate.onEnd(span);
    }

    @Override
    public boolean isEndRequired() {
        return true; // we must intercept onEnd to filter
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }

    @Override
    public CompletableResultCode forceFlush() {
        return delegate.forceFlush();
    }
}

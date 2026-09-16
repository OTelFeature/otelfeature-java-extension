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
 * <p>The suppression decision is made <b>once per span, at {@code onStart}</b>,
 * and {@code onEnd} then consults the registry rather than asking the flag a
 * second time. Two independent evaluations of the same span could legitimately
 * disagree - the flag can be flipped, or targeted so that it resolves per
 * evaluation - and a span recorded as suppressed at start but exported at end
 * (or the reverse) would corrupt the parent chain. Asking once also halves the
 * evaluations.
 *
 * <p>When suppression is inactive, all spans are passed through unchanged with
 * minimal overhead: for a static flag that is one volatile read and one enum
 * comparison per span, with no flag evaluation at all - see
 * {@link TelemetryLevelResolver}.
 */
public class FilteringSpanProcessor implements SpanProcessor {

    private final SpanProcessor delegate;
    private final TelemetryLevelSource telemetryLevel;
    private final SuppressedSpanRegistry registry;

    public FilteringSpanProcessor(SpanProcessor delegate, TelemetryLevelSource telemetryLevel,
                                  SuppressedSpanRegistry registry) {
        this.delegate = delegate;
        this.telemetryLevel = telemetryLevel;
        this.registry = registry;
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        // Record suppressed INTERNAL spans in the registry at onStart time, so
        // that ReparentingSpanExporter can re-parent surviving children before
        // this span ends and is dropped. With SimpleSpanProcessor (and even
        // BatchSpanProcessor), children are typically exported before their
        // parents end, so recording at onEnd would be too late.
        //
        // This is also where the decision is taken for the span's whole
        // lifetime: onEnd reads the registry instead of the flag.
        if (span.getKind() == SpanKind.INTERNAL && telemetryLevel.shouldSuppressInternal()) {
            SpanContext parent = span.getParentSpanContext();
            // A suppressed root span is recorded against an invalid parent, so
            // onEnd can still find it and its children are promoted to roots
            // rather than pointing at a span that was never exported.
            registry.record(
                    span.getSpanContext().getSpanId(),
                    parent != null && parent.isValid() ? parent : SpanContext.getInvalid());
        }
        delegate.onStart(parentContext, span);
    }

    @Override
    public boolean isStartRequired() {
        // Must be true: we need onStart() to pre-record suppressed INTERNAL
        // spans in the registry before any child spans are exported.
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        // Deliberately the registry and not the flag: the decision belongs to
        // onStart, and re-asking could answer differently for the same span.
        if (span.getKind() == SpanKind.INTERNAL
                && registry.isSuppressed(span.getSpanContext().getSpanId())) {
            return; // don't forward to delegate (BatchSpanProcessor)
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

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
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * Wraps a delegate {@link SpanProcessor} and drops the spans the current
 * {@link TelemetryPolicy} does not allow: {@code INTERNAL} spans under
 * {@code suppressInternal} (flagd {@code telemetryLevel} set to {@code "IO"}),
 * every span when not {@code enabled} ({@code telemetryLevel} set to
 * {@code "OFF"}, or {@code otelEnabled} set to {@code false}).
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
 * <p>{@code OFF} is suppression, not an SDK disable: spans are still created,
 * populated and propagated, then dropped here. It saves batching and export,
 * not the instrumentation's own CPU. Dropping at the sampler instead is
 * OTelFeature/otelfeature-java-extension#21.
 *
 * <p>When suppression is inactive, all spans are passed through unchanged with
 * minimal overhead: for static flags that is two volatile reads and a boolean
 * check per span, with no flag evaluation at all - see
 * {@link TelemetryFlag}.
 */
public class FilteringSpanProcessor implements SpanProcessor {

    private final SpanProcessor delegate;
    private final TelemetryPolicySource policy;
    private final SuppressedSpanRegistry registry;

    public FilteringSpanProcessor(SpanProcessor delegate, TelemetryPolicySource policy,
                                  SuppressedSpanRegistry registry) {
        this.delegate = delegate;
        this.policy = policy;
        this.registry = registry;
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        // Record suppressed spans in the registry at onStart time, so
        // that ReparentingSpanExporter can re-parent surviving children before
        // this span ends and is dropped. With SimpleSpanProcessor (and even
        // BatchSpanProcessor), children are typically exported before their
        // parents end, so recording at onEnd would be too late.
        //
        // This is also where the decision is taken for the span's whole
        // lifetime: onEnd reads the registry instead of the flag.
        SpanContext parent = span.getParentSpanContext();
        boolean localParent = parent != null && parent.isValid() && !parent.isRemote();
        // A span inside a request recordSpan dropped as a whole goes with it,
        // without asking any flag: the request was decided at its root.
        if (localParent && registry.suppressesSubtree(parent.getSpanId())) {
            registry.record(span.getSpanContext().getSpanId(), parent, true);
            delegate.onStart(parentContext, span);
            return;
        }

        // Static flags answer without the span: getAttributes() copies the
        // span's attributes under its lock, so it is only read when a flag
        // actually has to be evaluated.
        // SPAN: the processor never uses the sample ratio, so a targeted
        // traceSampleRatio is not evaluated here.
        TelemetryPolicy decision = policy.cachedPolicy(TelemetryPolicy.Need.SPAN);
        if (decision == null) {
            decision = policy.policy(new SpanStart(
                    span.getSpanContext().getTraceId(),
                    span.getName(),
                    span.getKind(),
                    span.getAttributes(),
                    span.getInstrumentationScopeInfo()), TelemetryPolicy.Need.SPAN);
        }
        if (decision.suppresses(span.getKind())) {
            SpanContext recordedParent = parent != null && parent.isValid() ? parent : SpanContext.getInvalid();
            // recordSpan=false on a root (no parent, or an entry span with a
            // remote one) drops the whole request in this service, as the
            // sampler would: its local subtree is suppressed with it. Anything
            // else - IO, OFF, recordSpan mid-trace - suppresses just this span,
            // and its children are re-parented. A suppressed root is recorded
            // against an invalid parent, so its surviving children are
            // promoted to roots rather than pointing at a span nobody exported.
            boolean subtree = !decision.record()
                    && FlagAwareSampler.isRecordSpanRoot(recordedParent, span.getKind());
            registry.record(span.getSpanContext().getSpanId(), recordedParent, subtree);
        }
        delegate.onStart(parentContext, span);
    }

    @Override
    public boolean isStartRequired() {
        // Must be true: we need onStart() to pre-record suppressed spans in
        // the registry before any child spans are exported.
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        // Deliberately the registry and not the flag: the decision belongs to
        // onStart, and re-asking could answer differently for the same span.
        String spanId = span.getSpanContext().getSpanId();
        if (registry.isSuppressed(spanId)) {
            // From here on the entry is only needed to re-parent children, so
            // the registry may sweep it once it fills up.
            registry.markEnded(spanId);
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

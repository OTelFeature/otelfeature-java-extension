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

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingResult;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Decides before a span exists: a span this sampler drops is never created as
 * a recording span, so no span processor sees it, no attributes are recorded
 * on it, and nothing downstream of it is sampled either.
 *
 * <p>The answer is {@code DROP} or "ask the delegate". {@code DROP} is given
 * only where dropping the whole subtree is the intended outcome, because that
 * is what a sampler drop means: the span's context carries {@code sampled=0},
 * the stock parent-based samplers then drop every descendant, and outgoing
 * calls tell downstream services the same.
 *
 * <ul>
 *   <li>{@code enabled=false} ({@code otelEnabled=false} or
 *       {@code telemetryLevel=OFF}): {@code DROP} every <em>local root</em> - a
 *       span with no parent, or whose parent is remote (an incoming request) -
 *       and every span whose local parent was not sampled. A span with a
 *       sampled local parent is left to the delegate, so a trace that
 *       was already sampled when the flag flipped finishes coherently instead
 *       of losing its tail.</li>
 *   <li>Everything else, including {@code telemetryLevel=IO}: delegate.
 *       {@code INTERNAL} spans are usually mid-trace, and dropping one here
 *       would take its children with it; that suppression stays in
 *       {@link FilteringSpanProcessor}, which re-parents the survivors.</li>
 * </ul>
 *
 * <p>Fails open: if the policy cannot be read, the delegate decides.
 */
final class FlagAwareSampler implements Sampler {

    private static final Logger log = Logger.getLogger(FlagAwareSampler.class.getName());

    private final Sampler delegate;
    private final TelemetryPolicySource policy;
    private final AtomicBoolean warned = new AtomicBoolean();

    FlagAwareSampler(Sampler delegate, TelemetryPolicySource policy) {
        this.delegate = delegate;
        this.policy = policy;
    }

    @Override
    public SamplingResult shouldSample(
            Context parentContext,
            String traceId,
            String name,
            SpanKind spanKind,
            Attributes attributes,
            List<LinkData> parentLinks) {
        SpanContext parent = Span.fromContext(parentContext).getSpanContext();
        boolean localRoot = !parent.isValid() || parent.isRemote();
        // While disabled, a local parent that was not sampled is a root we (or
        // the delegate) dropped: keep its children dropped too, even with a
        // delegate that is not parent-based (OTEL_TRACES_SAMPLER=always_on),
        // which would otherwise sample them afresh as orphans. Once tracing is
        // enabled again, children are the delegate's call as usual - a
        // non-parent-based delegate may then sample the rest of a trace whose
        // root was dropped.
        if ((localRoot || !parent.isSampled())
                && !enabled(new SpanStart(traceId, name, spanKind, attributes, null))) {
            return SamplingResult.drop();
        }
        return delegate.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
    }

    private boolean enabled(SpanStart span) {
        try {
            return policy.policy(span).enabled();
        } catch (Throwable t) {
            // The source is not supposed to throw; if it does anyway, tracing
            // stays as the delegate would have it. Warn once, not per span.
            boolean first = warned.compareAndSet(false, true);
            log.log(first ? Level.WARNING : Level.FINE,
                    "otelfeature-java-extension: policy unavailable, delegating"
                            + (first ? " (further occurrences are logged at FINE)" : ""), t);
            return true;
        }
    }

    @Override
    public String getDescription() {
        return "FlagAwareSampler{delegate=" + delegate.getDescription() + "}";
    }

    @Override
    public String toString() {
        return getDescription();
    }
}

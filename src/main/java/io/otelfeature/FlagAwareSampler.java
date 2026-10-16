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

import io.opentelemetry.api.common.AttributeKey;
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
 *   <li>{@code recordSpan=false} for this span: {@code DROP} it if it is a
 *       root (no parent, or a {@code SERVER}/{@code CONSUMER} entry span with
 *       a remote parent - see {@link #isRecordSpanRoot}) or a
 *       database client leaf ({@code CLIENT} with a wire-protocol
 *       {@code db.system.name}); any other span - including a request that
 *       arrives with a sampled {@code traceparent} - is left to
 *       {@link FilteringSpanProcessor}, so a mid-trace span that propagates
 *       never sends {@code sampled=0} downstream. Rules on
 *       {@code otel.scope.*} never match here (a sampler is not told the
 *       scope) and are enforced by the processor instead.</li>
 *   <li>Everything else, including {@code telemetryLevel=IO}: delegate.
 *       {@code INTERNAL} spans are usually mid-trace, and dropping one here
 *       would take its children with it; that suppression stays in
 *       {@link FilteringSpanProcessor}, which re-parents the survivors.</li>
 * </ul>
 *
 * <p>{@code traceSampleRatio}, in both enforcement modes: a trace root with no
 * parent at all is sampled by trace id at the flag's ratio instead of by the
 * delegate ({@code Sampler.traceIdRatioBased}), so every service applying the
 * same ratio makes the same call for the same trace. Every span with a parent,
 * local or remote, stays with the delegate: parent-based decisions are never
 * second-guessed mid-trace, which is why the ratio has to be changed at the
 * entry point. With no ratio set, the delegate decides as before.
 *
 * <p>In {@code processor} mode only the ratio applies here; {@code OFF} and
 * {@code recordSpan} are left to the processor.
 *
 * <p>Fails open: if the policy cannot be read, the delegate decides.
 */
final class FlagAwareSampler implements Sampler {

    private static final Logger log = Logger.getLogger(FlagAwareSampler.class.getName());

    private final Sampler delegate;
    private final TelemetryPolicySource policy;
    private final boolean enforce;
    private final AtomicBoolean warned = new AtomicBoolean();

    /**
     * {@code OTEL_TRACES_SAMPLER=always_off}: a hard floor. Flags only reduce
     * telemetry, so no ratio and no sampled parent may sample anything.
     */
    private final boolean delegateAlwaysOff;

    /** Sampler mode: enforces {@code enabled} and {@code recordSpan}, and applies the ratio. */
    FlagAwareSampler(Sampler delegate, TelemetryPolicySource policy) {
        this(delegate, policy, true);
    }

    /**
     * @param delegate the agent's configured sampler
     * @param policy where the flags come from
     * @param enforce {@code true} in sampler mode (drops for {@code enabled} and
     *     {@code recordSpan}); {@code false} in processor mode, where only the
     *     sample ratio is applied here
     */
    FlagAwareSampler(Sampler delegate, TelemetryPolicySource policy, boolean enforce) {
        this(delegate, policy, enforce, false);
    }

    /**
     * @param delegate the agent's configured sampler
     * @param policy where the flags come from
     * @param enforce {@code true} in sampler mode
     * @param configuredAlwaysOff {@code true} if {@code otel.traces.sampler} is
     *     {@code always_off}; an {@code AlwaysOffSampler} delegate is
     *     recognised either way
     */
    FlagAwareSampler(Sampler delegate, TelemetryPolicySource policy, boolean enforce,
            boolean configuredAlwaysOff) {
        this.delegate = delegate;
        this.policy = policy;
        this.enforce = enforce;
        this.delegateAlwaysOff = configuredAlwaysOff
                || delegate == Sampler.alwaysOff()
                || "AlwaysOffSampler".equals(delegate.getDescription());
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
        boolean askEnabled = localRoot || !parent.isSampled();
        // recordSpan=false may only DROP where losing the subtree is the
        // intended outcome: a trace root (the whole trace goes) or a leaf that propagates
        // nothing (a DB call over its own wire protocol); see isRecordSpanRoot
        // for what counts as a root. Anything else that propagates mid-trace
        // is left to the processor, which suppresses it and re-parents its
        // children, so downstream services don't inherit sampled=0.
        boolean askRecord = isRecordSpanRoot(parent, spanKind) || isDatabaseLeaf(spanKind, attributes);
        // traceSampleRatio decides new traces only: a span with no parent.
        boolean askRatio = !parent.isValid() && !delegateAlwaysOff;
        if (!enforce) {
            askEnabled = false;
            askRecord = false;
        }
        if (askEnabled || askRecord || askRatio) {
            TelemetryPolicy.Need need = askRatio ? TelemetryPolicy.Need.ROOT
                    : askRecord ? TelemetryPolicy.Need.SPAN : TelemetryPolicy.Need.GLOBAL;
            TelemetryPolicy decision = policyFor(traceId, name, spanKind, attributes, need);
            if (decision != null) {
                if (askEnabled && !decision.enabled()) {
                    return SamplingResult.drop();
                }
                if (askRecord && decision.enabled() && !decision.record()) {
                    return SamplingResult.drop();
                }
                if (askRatio && decision.overridesSampleRatio()) {
                    return sampledByRatio(traceId, decision.sampleRatio())
                            ? SamplingResult.recordAndSample() : SamplingResult.drop();
                }
            }
        }
        // While a ratio is in play, a span with a parent follows its parent
        // (ParentBased semantics), whatever the delegate would do: otherwise a
        // non-parent-based delegate (always_on, traceidratio, jaeger_remote)
        // would re-decide the children of a root the ratio decided, and break
        // the trace. With no ratio in play the delegate decides as always -
        // and an always_off delegate is a hard floor no flag can lift.
        if (parent.isValid() && !delegateAlwaysOff && sampleRatioInPlay()) {
            return parent.isSampled() ? SamplingResult.recordAndSample() : SamplingResult.drop();
        }
        return delegate.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
    }

    /**
     * The same decision as the SDK's {@code TraceIdRatioBasedSampler} - the
     * lower 64 bits of the trace id against {@code ratio * Long.MAX_VALUE} - so
     * a Java service applying the ratio and one using
     * {@code OTEL_TRACES_SAMPLER=traceidratio} agree on every trace. Computed
     * inline: a ratio targeted per span would otherwise build a new sampler
     * on most roots.
     */
    static boolean sampledByRatio(String traceId, double ratio) {
        long bound = ratio <= 0.0 ? Long.MIN_VALUE
                : ratio >= 1.0 ? Long.MAX_VALUE
                : (long) (ratio * Long.MAX_VALUE);
        return Math.abs(lower64(traceId)) < bound;
    }

    /** The lower 64 bits of a 32-hex-digit trace id, as the SDK reads them. */
    private static long lower64(String traceId) {
        long value = 0;
        for (int i = 16; i < 32; i++) {
            value = (value << 4) | Character.digit(traceId.charAt(i), 16);
        }
        return value;
    }

    private boolean sampleRatioInPlay() {
        try {
            return policy.sampleRatioInPlay();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Whether a span counts as a root for {@code recordSpan}: one with no
     * parent at all, or an <em>entry</em> span - {@code SERVER} or
     * {@code CONSUMER} - with a remote parent, sampled or not. Dropping an
     * entry span drops the whole request in this service; the trace then
     * ends at the caller, and downstream services get {@code sampled=0}.
     * That is the intended trade-off for, say, a health check proxied with a
     * sampled {@code traceparent}. {@code INTERNAL} and {@code CLIENT} spans
     * with a remote parent are not roots: they stay with the processor.
     *
     * @param parent the parent span context ({@link SpanContext#getInvalid()} for none)
     * @param kind the span's kind
     * @return {@code true} if dropping the span means dropping its request
     */
    static boolean isRecordSpanRoot(SpanContext parent, SpanKind kind) {
        if (parent == null || !parent.isValid()) {
            return true;
        }
        return parent.isRemote() && (kind == SpanKind.SERVER || kind == SpanKind.CONSUMER);
    }

    /**
     * A database client call over the database's own wire protocol: a leaf
     * that propagates no context, so dropping it drops nothing else.
     *
     * <p>Deliberately an allowlist. Databases reached over HTTP
     * (Elasticsearch, OpenSearch, DynamoDB, CouchDB, ClickHouse's HTTP
     * interface, ...) carry {@code db.system.name} too, but their transport
     * propagates {@code traceparent}; they are left to the processor. The
     * same goes for JDBC with the agent's experimental sqlcommenter enabled,
     * which writes the traceparent into the SQL - see the README.
     */
    static boolean isDatabaseLeaf(SpanKind kind, Attributes attributes) {
        if (kind != SpanKind.CLIENT || attributes == null) {
            return false;
        }
        String system = attributes.get(DB_SYSTEM_NAME);
        if (system == null) {
            system = attributes.get(DB_SYSTEM);
        }
        return system != null && WIRE_PROTOCOL_DATABASES.contains(system);
    }

    /** Semconv {@code db.system.name} values whose client speaks a non-HTTP wire protocol. */
    private static final java.util.Set<String> WIRE_PROTOCOL_DATABASES = java.util.Set.of(
            "postgresql", "mysql", "mariadb", "oracle.db", "oracle", "microsoft.sql_server", "mssql",
            "ibm.db2", "db2", "h2", "hsqldb", "derby", "sqlite", "other_sql",
            "redis", "valkey", "memcached", "mongodb", "cassandra", "couchbase", "neo4j");

    private static final AttributeKey<String> DB_SYSTEM_NAME = AttributeKey.stringKey("db.system.name");
    private static final AttributeKey<String> DB_SYSTEM = AttributeKey.stringKey("db.system");

    /**
     * The policy for this span, or {@code null} to delegate (fail open).
     *
     * @param need which flags matter for this decision: only the global
     *     switches for a child of an unsampled parent, {@code recordSpan} too
     *     for a root or a DB leaf, and {@code traceSampleRatio} only for a root,
     *     so a targeted flag is never evaluated for spans it cannot affect
     */
    private TelemetryPolicy policyFor(
            String traceId, String name, SpanKind kind, Attributes attributes, TelemetryPolicy.Need need) {
        try {
            TelemetryPolicy cached = policy.cachedPolicy(need);
            if (cached != null) {
                return cached;
            }
            return policy.policy(new SpanStart(traceId, name, kind, attributes, null), need);
        } catch (Throwable t) {
            // The source is not supposed to throw; if it does anyway, tracing
            // stays as the delegate would have it. Warn once, not per span.
            boolean first = warned.compareAndSet(false, true);
            log.log(first ? Level.WARNING : Level.FINE,
                    "otelfeature-java-extension: policy unavailable, delegating"
                            + (first ? " (further occurrences are logged at FINE)" : ""), t);
            return null;
        }
    }

    @Override
    public String getDescription() {
        return "FlagAwareSampler{delegate=" + delegate.getDescription()
                + ", enforcement=" + (enforce ? "sampler" : "processor") + "}";
    }

    @Override
    public String toString() {
        return getDescription();
    }
}

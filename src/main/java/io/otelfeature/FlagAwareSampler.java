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
 *       trace root (a span with no parent at all) or a
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
        boolean askEnabled = localRoot || !parent.isSampled();
        // recordSpan=false may only DROP where losing the subtree is the
        // intended outcome: a trace root (the whole trace goes) or a leaf that propagates
        // nothing (a DB call over its own wire protocol). Anything else that
        // propagates mid-trace - including a request arriving with a sampled
        // traceparent - is left to the processor, which suppresses it and
        // re-parents its children, so downstream services never inherit
        // sampled=0.
        boolean askRecord = isRecordSpanRoot(parent) || isDatabaseLeaf(spanKind, attributes);
        if (askEnabled || askRecord) {
            TelemetryPolicy decision = policyFor(traceId, name, spanKind, attributes, askRecord);
            if (decision != null) {
                if (askEnabled && !decision.enabled()) {
                    return SamplingResult.drop();
                }
                if (askRecord && decision.enabled() && !decision.record()) {
                    return SamplingResult.drop();
                }
            }
        }
        return delegate.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
    }

    /**
     * Whether a span counts as a trace root for {@code recordSpan}: one with no
     * parent at all. A span with a remote parent - sampled or not - is left
     * to the processor for now; whether an unsampled remote parent should
     * count as a root too is an open decision, and this is the one place to
     * change it.
     */
    static boolean isRecordSpanRoot(SpanContext parent) {
        return !parent.isValid();
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
     * @param needRecord whether {@code recordSpan} matters for this decision;
     *     when it doesn't (a child of an unsampled parent, say), only the global
     *     switches are asked, so a targeted recordSpan is not evaluated for
     *     spans it could not drop anyway
     */
    private TelemetryPolicy policyFor(
            String traceId, String name, SpanKind kind, Attributes attributes, boolean needRecord) {
        try {
            TelemetryPolicy cached = needRecord ? policy.cachedPolicy() : policy.cachedGlobalPolicy();
            if (cached != null) {
                return cached;
            }
            SpanStart span = new SpanStart(traceId, name, kind, attributes, null);
            return needRecord ? policy.policy(span) : policy.globalPolicy(span);
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
        return "FlagAwareSampler{delegate=" + delegate.getDescription() + "}";
    }

    @Override
    public String toString() {
        return getDescription();
    }
}

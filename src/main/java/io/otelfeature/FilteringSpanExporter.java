package io.otelfeature;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/**
 * Wraps a delegate {@link SpanExporter} and, when suppression is active,
 * drops {@code INTERNAL} spans while <strong>re-parenting</strong> their
 * children to the nearest non-suppressed ancestor.
 *
 * <p>This solves the orphaned-children problem that a simple filter creates:
 * if you just drop an {@code INTERNAL} span, its children ({@code CLIENT}
 * spans for JDBC, HTTP, etc.) still reference it as their parent, but the
 * parent no longer exists in the exported trace — they become parentless.
 *
 * <p>Instead, this exporter:
 * <ol>
 *   <li>Identifies all {@code INTERNAL} spans in the batch that should be
 *       suppressed.</li>
 *   <li>Builds a re-parenting map: for each suppressed span, resolves its
 *       nearest non-suppressed ancestor (following the chain transitively
 *       in case multiple {@code INTERNAL} spans are nested).</li>
 *   <li>For each surviving span whose parent is suppressed, wraps it with
 *       a {@link ReparentedSpanData} that points to the resolved ancestor.</li>
 *   <li>Drops the suppressed spans and exports the rest.</li>
 * </ol>
 *
 * <p>When suppression is inactive, all spans are passed through unchanged.
 */
public class FilteringSpanExporter implements SpanExporter {

    private final SpanExporter delegate;
    private final FlagdClient flagdClient;

    public FilteringSpanExporter(SpanExporter delegate, FlagdClient flagdClient) {
        this.delegate = delegate;
        this.flagdClient = flagdClient;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        if (!flagdClient.shouldSuppressInternal()) {
            return delegate.export(spans);
        }

        List<SpanData> spanList = new ArrayList<>(spans);

        // Index all spans by span ID for parent lookup
        Map<String, SpanData> spanById = new HashMap<>(spanList.size());
        for (SpanData span : spanList) {
            spanById.put(span.getSpanId(), span);
        }

        // Identify suppressed INTERNAL spans
        Set<String> suppressedIds = new HashSet<>();
        for (SpanData span : spanList) {
            if (span.getKind() == SpanKind.INTERNAL) {
                suppressedIds.add(span.getSpanId());
            }
        }

        if (suppressedIds.isEmpty()) {
            return delegate.export(spanList);
        }

        // Build re-parenting map: suppressedSpanId -> resolved ancestor span context
        Map<String, SpanContext> reparentMap = new HashMap<>();

        for (String suppressedId : suppressedIds) {
            SpanData suppressedSpan = spanById.get(suppressedId);
            if (suppressedSpan == null) {
                // Parent span might not be in this batch — keep original parent
                continue;
            }

            SpanContext resolvedParent = resolveParent(
                    suppressedSpan.getParentSpanContext(), spanById, suppressedIds, reparentMap);

            reparentMap.put(suppressedId, resolvedParent);
        }

        // Build the output: drop suppressed spans, re-parent children
        List<SpanData> result = new ArrayList<>(spanList.size() - suppressedIds.size());

        for (SpanData span : spanList) {
            if (suppressedIds.contains(span.getSpanId())) {
                continue; // drop suppressed span
            }

            String parentId = span.getParentSpanId();
            if (parentId != null && reparentMap.containsKey(parentId)) {
                // Re-parent to resolved ancestor
                SpanContext newParent = reparentMap.get(parentId);
                result.add(new ReparentedSpanData(span, newParent));
            } else {
                result.add(span);
            }
        }

        return delegate.export(result);
    }

    /**
     * Resolves the nearest non-suppressed ancestor for a span, following
     * the parent chain transitively through suppressed spans.
     */
    private SpanContext resolveParent(
            SpanContext parentContext,
            Map<String, SpanData> spanById,
            Set<String> suppressedIds,
            Map<String, SpanContext> reparentMap) {

        if (parentContext == null || !parentContext.isValid()) {
            return parentContext; // root span — no parent
        }

        String parentId = parentContext.getSpanId();

        // If parent is not suppressed, keep it as-is
        if (!suppressedIds.contains(parentId)) {
            return parentContext;
        }

        // If we've already resolved this parent, use the cached result
        if (reparentMap.containsKey(parentId)) {
            return reparentMap.get(parentId);
        }

        // Parent is suppressed — look it up in the batch and follow the chain
        SpanData parentSpan = spanById.get(parentId);
        if (parentSpan == null) {
            // Parent not in this batch — can't resolve further, keep original
            return parentContext;
        }

        // Recursively resolve the grandparent
        return resolveParent(parentSpan.getParentSpanContext(), spanById, suppressedIds, reparentMap);
    }

    @Override
    public CompletableResultCode flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResultCode shutdown() {
        return delegate.shutdown();
    }
}

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
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Wraps a delegate {@link SpanExporter} and re-parents surviving spans whose
 * parents were suppressed by {@link FilteringSpanProcessor}.
 *
 * <p>This works in concert with {@link FilteringSpanProcessor} via a shared
 * {@link SuppressedSpanRegistry}:
 * <ol>
 *   <li>{@code FilteringSpanProcessor} drops {@code INTERNAL} spans and records
 *       their IDs in the registry.</li>
 *   <li>This exporter checks each surviving span's parent ID against the
 *       registry. If the parent was suppressed, the span is re-parented to
 *       the nearest non-suppressed ancestor (following the chain transitively).</li>
 * </ol>
 *
 * <p>When the registry is empty (suppression inactive or no spans suppressed),
 * this exporter is a transparent passthrough with minimal overhead.
 */
public class ReparentingSpanExporter implements SpanExporter {

    private final SpanExporter delegate;
    private final SuppressedSpanRegistry registry;

    public ReparentingSpanExporter(SpanExporter delegate, SuppressedSpanRegistry registry) {
        this.delegate = delegate;
        this.registry = registry;
    }

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        if (registry.size() == 0) {
            return delegate.export(spans);
        }

        List<SpanData> result = new ArrayList<>(spans.size());
        for (SpanData span : spans) {
            SpanContext parentContext = span.getParentSpanContext();
            if (parentContext != null && parentContext.isValid()) {
                SpanContext resolvedParent = registry.resolve(parentContext.getSpanId());
                if (resolvedParent != null) {
                    result.add(new ReparentedSpanData(span, resolvedParent));
                    continue;
                }
            }
            result.add(span);
        }

        return delegate.export(result);
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

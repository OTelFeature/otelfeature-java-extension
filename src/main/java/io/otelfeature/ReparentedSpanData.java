package io.otelfeature;

import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.api.common.Attributes;
import java.util.List;

/**
 * A delegating {@link SpanData} wrapper that overrides the parent span context
 * to support re-parenting when an INTERNAL span is suppressed.
 *
 * <p>All other attributes are delegated to the original {@link SpanData}.
 */
class ReparentedSpanData implements SpanData {

    private final SpanData delegate;
    private final SpanContext newParentSpanContext;

    ReparentedSpanData(SpanData delegate, SpanContext newParentSpanContext) {
        this.delegate = delegate;
        this.newParentSpanContext = newParentSpanContext;
    }

    @Override
    public SpanContext getSpanContext() {
        return delegate.getSpanContext();
    }

    @Override
    public SpanContext getParentSpanContext() {
        return newParentSpanContext;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public SpanKind getKind() {
        return delegate.getKind();
    }

    @Override
    public StatusData getStatus() {
        return delegate.getStatus();
    }

    @Override
    public long getStartEpochNanos() {
        return delegate.getStartEpochNanos();
    }

    @Override
    public Attributes getAttributes() {
        return delegate.getAttributes();
    }

    @Override
    public List<EventData> getEvents() {
        return delegate.getEvents();
    }

    @Override
    public List<LinkData> getLinks() {
        return delegate.getLinks();
    }

    @Override
    public long getEndEpochNanos() {
        return delegate.getEndEpochNanos();
    }

    @Override
    public boolean hasEnded() {
        return delegate.hasEnded();
    }

    @Override
    public int getTotalRecordedEvents() {
        return delegate.getTotalRecordedEvents();
    }

    @Override
    public int getTotalRecordedLinks() {
        return delegate.getTotalRecordedLinks();
    }

    @Override
    public int getTotalAttributeCount() {
        return delegate.getTotalAttributeCount();
    }

    @Override
    public InstrumentationScopeInfo getInstrumentationScopeInfo() {
        return delegate.getInstrumentationScopeInfo();
    }

    @Override
    public Resource getResource() {
        return delegate.getResource();
    }
}

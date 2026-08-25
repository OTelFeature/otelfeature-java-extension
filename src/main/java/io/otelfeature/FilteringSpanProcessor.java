package io.otelfeature;

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
 * <p>Children of suppressed spans are <strong>not</strong> re-parented. They
 * retain their original parent span ID, which may reference a span that was
 * not exported. This is the same behavior as any sampling scenario (e.g.,
 * probabilistic sampling at &lt;100%) and is handled gracefully by trace
 * backends such as Jaeger, Tempo, and Datadog.
 *
 * <p>When suppression is inactive, all spans are passed through unchanged
 * with minimal overhead: one {@code AtomicReference.get()} (volatile read)
 * and one enum comparison per span.
 */
public class FilteringSpanProcessor implements SpanProcessor {

    private final SpanProcessor delegate;
    private final FlagdClient flagdClient;

    public FilteringSpanProcessor(SpanProcessor delegate, FlagdClient flagdClient) {
        this.delegate = delegate;
        this.flagdClient = flagdClient;
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

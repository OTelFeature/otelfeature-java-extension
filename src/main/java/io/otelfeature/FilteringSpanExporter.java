package io.otelfeature;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import io.opentelemetry.sdk.common.CompletableResult;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;

/**
 * Wraps a delegate {@link SpanExporter} and filters out {@code INTERNAL}
 * spans when the {@link FlagdClient} says they should be suppressed.
 *
 * <p>When suppression is active, {@code INTERNAL} spans are simply not
 * forwarded to the delegate exporter — they're dropped before export.
 * {@code SERVER} and {@code CLIENT} spans are always passed through.
 *
 * <p>This is the Java equivalent of the Python {@code otelfeature-instrument}'s
 * INTERNAL-span suppression: it lets you reduce trace verbosity by hiding
 * internal implementation spans while keeping the I/O boundary spans that
 * show the request flow between services.
 */
public class FilteringSpanExporter implements SpanExporter {

    private final SpanExporter delegate;
    private final FlagdClient flagdClient;

    public FilteringSpanExporter(SpanExporter delegate, FlagdClient flagdClient) {
        this.delegate = delegate;
        this.flagdClient = flagdClient;
    }

    @Override
    public CompletableResult export(Collection<SpanData> spans) {
        if (flagdClient.shouldSuppressInternal()) {
            List<SpanData> filtered = spans.stream()
                    .filter(span -> span.getKind() != io.opentelemetry.trace.SpanKind.INTERNAL)
                    .collect(Collectors.toList());
            return delegate.export(filtered);
        }
        return delegate.export(spans);
    }

    @Override
    public CompletableResult flush() {
        return delegate.flush();
    }

    @Override
    public CompletableResult shutdown() {
        return delegate.shutdown();
    }
}

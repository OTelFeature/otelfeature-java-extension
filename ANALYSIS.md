# Analysis: otelfeature-java-extension Span Suppression

## 1. Current Architecture

The extension suppresses `INTERNAL` spans based on a flagd feature flag (`telemetryLevel`).
It works at the **SpanExporter** level — the very last stage in the OTel pipeline:

```
Tracer → SpanProcessor (BatchSpanProcessor) → SpanExporter (wrapped) → OTLP Collector
                                                         ↑
                                                  FilteringSpanExporter
                                                  (drops INTERNAL spans here)
```

Four classes are involved:

| Class | Role |
|-------|------|
| `OtelfeatureCustomizer` | SPI entry point; wraps the exporter |
| `FilteringSpanExporter` | Per-batch filtering + re-parenting logic |
| `ReparentedSpanData` | Wrapper to override `getParentSpanContext()` |
| `FlagdClient` | Polls flagd for the feature flag |

---

## 2. Issues Identified

### 2.1 Wrong Pipeline Layer (Performance)

**Filtering at the exporter is the latest possible intervention.** By the time spans reach
the exporter, the SDK has already:

1. **Created** the span object and allocated all its data structures
2. **Recorded** all attributes, events, and timing data
3. **Processed** the span through the entire `SpanProcessor` chain
4. **Batched** the span into a `BatchSpanProcessor` queue
5. **Drained** the batch into a `Collection<SpanData>` for export

All of this work is wasted for every `INTERNAL` span that gets dropped at the exporter.

### 2.2 Re-parenting Is Fundamentally Broken (Correctness)

The `FilteringSpanExporter` tries to re-parent children of suppressed `INTERNAL` spans to
their nearest non-suppressed ancestor. This is a noble goal, but **it cannot work reliably
at the exporter level**:

- **Spans are batched by time/size, not by trace.** A parent `INTERNAL` span and its child
  `CLIENT` span may end up in different export batches.
- The code itself acknowledges this: `spanById` only covers the current batch, and
  `resolveParent()` returns the original (suppressed) parent context when the parent isn't
  in the batch — leaving the child orphaned anyway.
- **Multiple nested `INTERNAL` spans** require recursive resolution within the batch, adding
  further complexity for a best-effort result.

In practice, the re-parenting only works when the **entire trace tree happens to be in a
single export batch**, which is not guaranteed by the OTel SDK.

### 2.3 Heavy Per-Batch Allocations (Performance)

When suppression is active, every `export()` call allocates:

| Object | Purpose | Size |
|--------|---------|------|
| `ArrayList<>(spans)` | Copy of input collection | O(n) |
| `HashMap<String, SpanData>` | `spanById` index | O(n) |
| `HashSet<String>` | `suppressedIds` | O(k) |
| `HashMap<String, SpanContext>` | `reparentMap` | O(k) |
| `ArrayList<>` | `result` output list | O(n-k) |
| `ReparentedSpanData` per reparented span | Wrapper object | O(1) each |

Where `n` = batch size, `k` = suppressed span count. This is significant overhead in the
export hot path, called for every batch (default: every 5 seconds or 512 spans).

### 2.4 Blocking Constructor in FlagdClient (Startup Latency)

```java
public FlagdClient() {
    // ...
    safePoll.run();  // ← BLOCKS the calling thread for up to 5 seconds!
    scheduler.scheduleAtFixedRate(safePoll, POLL_INTERVAL, POLL_INTERVAL, TimeUnit.SECONDS);
}
```

`safePoll.run()` executes synchronously in the constructor. If flagd is unreachable, this
blocks agent startup for up to 5 seconds (the connect timeout). Since the customizer runs
during agent initialization, this delays application startup.

### 2.5 Fragile JSON Parsing

```java
private String extractValue(String json) {
    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
            "\"value\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
    return matcher.find() ? matcher.group(1) : null;
}
```

The regex matches the **first** `"value"` field anywhere in the JSON. If flagd adds
metadata fields before the actual value, or if the response structure changes, this breaks
silently. It also can't handle boolean or numeric values (though the current flag is a string).

### 2.6 README Inconsistencies

The README claims the extension uses:
- `java.net.http.HttpClient` — but the code uses `java.net.HttpURLConnection`
- `org.slf4j.Logger` — but the code uses `java.util.logging.Logger`

### 2.7 No Tests

There are zero tests in the project. The filtering logic, re-parenting logic, and flagd
client are all untested.

### 2.8 No Graceful Shutdown

The `FlagdClient`'s `ScheduledExecutorService` is never shut down. While the thread is a
daemon thread (so it won't prevent JVM exit), there's no cleanup hook for orderly shutdown.

---

## 3. Recommended Approach: SpanProcessor-Level Filtering

### Why SpanProcessor?

The OTel SDK pipeline is:

```
Tracer → SpanProcessor (onStart/onEnd) → BatchSpanProcessor → SpanExporter → OTLP
```

`AutoConfigurationCustomizer` provides `addSpanProcessorCustomizer()` (available since
SDK 1.33.0, confirmed in 1.44.0) which lets us **wrap the auto-configured SpanProcessor**
(typically `BatchSpanProcessor`). By filtering in `onEnd()`, we drop spans **before they
enter the batch queue**, saving:

- Batch queue memory and enqueue/dequeue overhead
- Batch draining and `SpanData` conversion
- Exporter serialization and network I/O for dropped spans

### Why not a custom Sampler?

A `Sampler` is the earliest possible intervention (span is never created), and the Java
`Sampler.shouldSample()` method does receive `SpanKind` as a parameter. However, there's a
critical problem: **`ParentBased` samplers**.

The default OTel sampler is `ParentBased(AlwaysOn)`. Its behavior:
- If parent was sampled → sample the child
- If parent was **not** sampled → **do not sample** the child (default `localParentNotSampled = AlwaysOff`)

If we drop an `INTERNAL` span via the sampler (`NOT_RECORD`), its children would also be
dropped by the `ParentBased` sampler because the parent's `isSampled()` flag is `false`.
This would suppress `SERVER` and `CLIENT` spans too — the exact spans we want to keep.

A `SpanProcessor` avoids this entirely: the sampler samples everything (including
`INTERNAL` spans), so children always see their parent as sampled. The `SpanProcessor`
then drops `INTERNAL` spans before they reach the batch, while children pass through
normally.

### What about re-parenting?

**We drop it.** Here's why:

1. **It was already broken.** The exporter-level re-parenting only worked when all spans
   in a trace were in the same batch — which is not guaranteed.

2. **Orphaned children are normal in OTel.** Any sampler running at <100% produces traces
   where some parent spans are missing. Trace backends (Jaeger, Tempo, Datadog, etc.)
   handle this gracefully — they display the child as a root span or show a "missing parent"
   indicator.

3. **The `SpanProcessor` approach can't re-parent anyway.** `onEnd()` receives one span at
   a time, with no batch context. Re-parenting would require buffering and correlation
   across spans, adding back the complexity we're trying to remove.

4. **The behavior is equivalent.** When the current re-parenting fails (parent not in
   batch), children are orphaned — exactly the same as no re-parenting. The new approach
   is simply honest about this limitation instead of pretending to fix it.

---

## 4. Implementation

### 4.1 `OtelfeatureCustomizer.java` (modified)

```java
package io.otelfeature;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

public class OtelfeatureCustomizer implements AutoConfigurationCustomizerProvider {

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        FlagdClient flagdClient = new FlagdClient();

        autoConfiguration.addSpanProcessorCustomizer(
                (processor, config) -> new FilteringSpanProcessor(processor, flagdClient));
    }
}
```

**Change:** `addSpanExporterCustomizer` → `addSpanProcessorCustomizer`. This moves
filtering from the exporter (end of pipeline) to the processor (before batching).

### 4.2 `FilteringSpanProcessor.java` (new — replaces `FilteringSpanExporter.java`)

```java
package io.otelfeature;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * Wraps a delegate {@link SpanProcessor} and drops {@code INTERNAL} spans when
 * suppression is active (flagd {@code telemetryLevel} = {@code "IO"}).
 *
 * <p>Filtering at the {@link SpanProcessor} level means dropped spans never enter
 * the batch queue, saving memory and CPU compared to exporter-level filtering.
 *
 * <p>Children of suppressed spans are <strong>not</strong> re-parented. They retain
 * their original parent span ID, which may reference a span that was not exported.
 * This is the same behavior as any sampling scenario (e.g., probabilistic sampling)
 * and is handled gracefully by trace backends.
 *
 * <p>When suppression is inactive, all spans are passed through unchanged with
 * minimal overhead (one {@code AtomicReference.get()} + one enum comparison per span).
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
```

### 4.3 `FlagdClient.java` (modified — fix blocking constructor)

```java
package io.otelfeature;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FlagdClient {

    private static final Logger log = Logger.getLogger(FlagdClient.class.getName());

    private static final String FLAGD_HOST = System.getenv().getOrDefault("FLAGD_HOST", "flagd");
    private static final int FLAGD_PORT = Integer.parseInt(
            System.getenv().getOrDefault("FLAGD_PORT", "8016"));
    private static final int POLL_INTERVAL = Integer.parseInt(
            System.getenv().getOrDefault("FLAGD_POLL_INTERVAL_SECONDS", "5"));
    private static final String FLAG_NAME = System.getenv().getOrDefault(
            "OTELFEATURE_FLAG_NAME", "telemetryLevel");

    private final ScheduledExecutorService scheduler;
    private final AtomicReference<Boolean> suppressInternal = new AtomicReference<>(false);

    public FlagdClient() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "otelfeature-flagd-poller");
            t.setDaemon(true);
            return t;
        });

        Runnable safePoll = () -> {
            try {
                poll();
            } catch (Throwable t) {
                log.log(Level.WARNING, "otelfeature-java-extension: unexpected error polling flagd", t);
            }
        };

        // Schedule first poll immediately on the background thread (non-blocking).
        // Until the first poll completes, shouldSuppressInternal() returns false (no suppression).
        scheduler.scheduleAtFixedRate(safePoll, 0, POLL_INTERVAL, TimeUnit.SECONDS);

        log.info("otelfeature-java-extension: polling flagd at " + FLAGD_HOST + ":" + FLAGD_PORT
                + " every " + POLL_INTERVAL + "s for flag '" + FLAG_NAME + "'");
    }

    private void poll() {
        HttpURLConnection conn = null;
        try {
            URI uri = URI.create(String.format("http://%s:%d/ofrep/v1/evaluate/flags/%s",
                    FLAGD_HOST, FLAGD_PORT, FLAG_NAME));

            conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write("{\"context\":{}}".getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();

            if (status == 200) {
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        body.append(line);
                    }
                }

                String value = extractValue(body.toString());
                boolean shouldSuppress = "IO".equalsIgnoreCase(value);
                boolean previous = suppressInternal.getAndSet(shouldSuppress);

                if (previous != shouldSuppress) {
                    log.info("otelfeature-java-extension: telemetryLevel changed to '" + value
                            + "' — INTERNAL spans " + (shouldSuppress ? "suppressed" : "visible"));
                }
            } else {
                log.fine("otelfeature-java-extension: flagd returned status " + status
                        + " — defaulting to no suppression");
                suppressInternal.set(false);
            }
        } catch (Exception e) {
            log.fine("otelfeature-java-extension: failed to poll flagd ("
                    + FLAGD_HOST + ":" + FLAGD_PORT + ") — " + e.getMessage());
            // Keep the last known value — don't flip on transient errors
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String extractValue(String json) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "\"value\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    public boolean shouldSuppressInternal() {
        return suppressInternal.get();
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }
}
```

**Key change:** Removed `safePoll.run()` (synchronous/blocking) and replaced with
`scheduler.scheduleAtFixedRate(safePoll, 0, POLL_INTERVAL, TimeUnit.SECONDS)` which
runs the first poll on the background thread. The constructor returns immediately.

### 4.4 Files to Delete

- `FilteringSpanExporter.java` — replaced by `FilteringSpanProcessor.java`
- `ReparentedSpanData.java` — no longer needed (re-parenting removed)

---

## 5. Performance Comparison

### Before (exporter-level filtering)

When suppression is **active**, per batch:
- 1× `ArrayList` copy of input collection — O(n) allocation + copy
- 1× `HashMap<String, SpanData>` for spanById — O(n) allocation + n puts
- 1× `HashSet<String>` for suppressedIds — O(k) allocation + k puts
- 1× `HashMap<String, SpanContext>` for reparentMap — O(k) allocation + k puts
- 1× `ArrayList` for result — O(n-k) allocation
- k× `ReparentedSpanData` wrapper objects — k allocations
- Recursive `resolveParent()` calls with potential stack depth

When suppression is **inactive**: passthrough (minimal overhead). ✅

### After (SpanProcessor-level filtering)

When suppression is **active**, per span:
- 1× `AtomicReference.get()` — O(1), volatile read
- 1× `SpanKind` enum comparison — O(1)
- If dropped: return (no further work)
- If kept: `delegate.onEnd(span)` — normal path

When suppression is **inactive**: same per-span overhead (one volatile read + one comparison).
Negligible — no collection allocations, no wrapper objects.

### Summary

| Metric | Before (Exporter) | After (SpanProcessor) |
|--------|-------------------|----------------------|
| Pipeline stage | Last (export) | Middle (before batch) |
| Per-batch allocations | 5 collections + k wrappers | 0 |
| Per-span cost | O(1) amortized | O(1) (volatile read + enum compare) |
| Dropped span work | Created → recorded → processed → batched → dropped at export | Created → recorded → dropped at processor (never batched) |
| Re-parenting | Best-effort, often broken | Not attempted (honest about limitation) |
| Classes | 4 | 3 (−1 deleted, −1 replaced) |
| Lines of code | ~250 (exporter + reparenting) | ~50 (processor) |
| Startup blocking | Up to 5s if flagd unreachable | Non-blocking |

---

## 6. Additional Recommendations

### 6.1 Add Tests

The project has zero tests. At minimum, add tests for:
- `FilteringSpanProcessor`: verify INTERNAL spans are dropped when suppression is active,
  and all spans pass through when inactive.
- `FlagdClient`: verify flag parsing, default behavior, and transient error handling.

### 6.2 Consider flagd Java SDK

If dependency constraints allow, consider using the
[flagd Java SDK](https://github.com/open-feature/java-sdk-contrib/tree/main/flagd) instead
of raw HTTP polling. This would provide:
- Proper JSON parsing
- gRPC streaming (push-based flag updates instead of polling)
- Built-in retry/backoff logic
- Standardized OFREP client implementation

### 6.3 Fix README

Update the README to reflect the actual implementation:
- `HttpURLConnection` (not `java.net.http.HttpClient`)
- `java.util.logging.Logger` (not `org.slf4j.Logger`)
- Document the SpanProcessor-based architecture
- Remove references to re-parenting

### 6.4 Consider `addSamplerCustomizer` for Future Optimization

If the `ParentBased` sampler issue can be resolved (e.g., by also customizing the sampler
to always sample children of suppressed spans), moving to a `Sampler`-based approach would
eliminate span creation overhead entirely. This is a more complex optimization that could
be explored as a follow-up.

# otelfeature-java-extension

An [OpenTelemetry Java agent](https://github.com/open-telemetry/opentelemetry-java-instrumentation)
extension that adds flagd-controlled `INTERNAL` span suppression to any Java
service — the Java counterpart to the Python
[otelfeature-instrument](https://github.com/OTelFeature/otelfeature-instrument).

## What it does

The OTel Java agent auto-instruments libraries and produces spans of various
kinds: `SERVER` (incoming HTTP), `CLIENT` (outgoing HTTP/DB), and `INTERNAL`
(e.g. Spring WebMVC controller spans). This extension wraps the agent's
`SpanProcessor` and **filters out `INTERNAL` spans** when the `telemetryLevel`
flag served by [flagd](https://flagd.dev/) is set to `"IO"`.

| `telemetryLevel` | `INTERNAL` spans | `SERVER` / `CLIENT` spans |
|-------------------|------------------|---------------------------|
| `"FULL"` (default) | ✅ exported | ✅ exported |
| `"IO"`            | ❌ suppressed    | ✅ exported |

Flip the flag in flagd and the change takes effect on the **very next span**
— no restart, no redeploy, and no waiting for a poll interval.

## How it works

```
┌──────────────────────────────────────────────────────────────┐
│  JVM + OTel Java Agent                                       │
│                                                              │
│  ┌──────────────┐   ┌──────────────────┐   ┌──────────────┐  │
│  │ Instrumented │──▶│ Filtering        │──▶│ BatchSpan    │  │
│  │ libraries    │   │ SpanProcessor    │   │ Processor    │  │
│  └──────────────┘   │ (this extension) │   └──────┬───────┘  │
│                     └────────┬─────────┘          │          │
│                              │                    ▼          │
│                       ┌──────▼──────────────┐   ┌──────────────┐ │
│                       │ FlagdTelemetryLevel │   │ SpanExporter │ │
│                       │ OpenFeature SDK +   │   │ → OTLP to    │ │
│                       │ flagd in-process    │   │   collector  │ │
│                       └──────┬──────────────┘   └──────────────┘ │
│                              │ gRPC sync stream (flagd pushes) │
│                              └────────────▶ flagd :8015           │
└──────────────────────────────────────────────────────────────┘
```

The extension is discovered via the OTel Java agent's
[`AutoConfigurationCustomizerProvider` SPI](https://opentelemetry.io/docs/zero-code/java/agent/extensions/).
At agent startup:

1. The agent scans `META-INF/services/` on the classpath/extension path.
2. It finds `OtelfeatureCustomizer` and calls `customize()`.
3. The customizer wraps the auto-configured `SpanProcessor` with
   `FilteringSpanProcessor` via `addSpanProcessorCustomizer`.
4. `FlagdTelemetryLevel` registers a [flagd](https://flagd.dev/)
   [in-process](https://flagd.dev/reference/specifications/in-process-providers/)
   OpenFeature provider, which opens a gRPC **sync stream** to flagd (port
   8015). flagd pushes the whole flag ruleset down that stream and keeps it
   current, so evaluation is a local, in-memory operation. Registration is
   asynchronous — agent startup is not blocked on flagd being reachable, and
   until the provider is ready the flag resolves to its default (suppress
   nothing).
5. On each span's `onStart()`, `FilteringSpanProcessor` asks whether
   `INTERNAL` spans should be suppressed and, if so, records the span in a
   registry; `onEnd()` consults that registry and drops the span before it
   enters the batch queue. All other span kinds are passed through unchanged.

The decision is taken once per span, at `onStart`, and never revisited. Two
independent evaluations of the same span could legitimately disagree — the
flag can be flipped, or targeted so that it resolves per evaluation — and a
span recorded as suppressed at start but exported at end (or the reverse)
would corrupt the parent chain.

### Evaluating once, or evaluating always

`telemetryLevel` is asked about on every single span, but most of the time the
answer cannot have changed since the last one: a flag with no `targeting` block
is one `defaultVariant` for the whole world. flagd says so in the resolution
`reason`, and that reason is the entire mechanism:

| `reason`          | flagd produced it because                        | same for every span? |
|-------------------|--------------------------------------------------|----------------------|
| `STATIC`          | flag has no `targeting`, served `defaultVariant`  | yes                  |
| `TARGETING_MATCH` | `targeting` rules ran and picked a variant        | no                   |
| `DEFAULT`         | `targeting` rules ran and matched nothing         | no                   |
| `DISABLED`        | flag `state` is `DISABLED`, served our default    | not worth assuming   |
| `ERROR`           | flag missing, provider not ready, ...             | not yet knowable     |

So the flag is evaluated **once, from the provider's event handler, without an
evaluation context**, purely to read the reason back:

- `STATIC` — and only `STATIC` — is kept. Every subsequent span is answered
  from an `AtomicReference`: no evaluation, no hook chain.
- Anything else means the value can depend on who's asking, so every span
  evaluates for itself.

That classification is redone on every `PROVIDER_CONFIGURATION_CHANGED`, so
attaching a `targeting` block to `telemetryLevel` in flagd makes the next probe
report `TARGETING_MATCH`, caching switches itself off, and spans start being
evaluated individually. Remove the targeting and caching resumes by itself. No
configuration, no restart, no code change here.

That event is also what makes keeping an answer safe in the first place: flagd
pushes the changed ruleset down the sync stream and the provider says so. The
OFREP poller this replaced had no such signal — which is why it could only
ever re-read the flag on a timer.

Filtering at the `SpanProcessor` level (before batching) means dropped spans
never enter the `BatchSpanProcessor` queue, saving memory and CPU compared
to exporter-level filtering. Children of suppressed `INTERNAL` spans are
still exported — they retain their original parent span ID, which trace
backends handle gracefully (same as any sampling scenario).

No code changes to the service — just attach the extension JAR alongside the
agent.

## Usage

### With the OTel Java agent

```sh
java -javaagent:opentelemetry-javaagent.jar \
     -Dotel.javaagent.extensions=otelfeature-java-extension.jar \
     -jar your-app.jar
```

Or via environment variable:

```sh
OTEL_JAVAAGENT_EXTENSIONS=/path/to/otelfeature-java-extension.jar
java -javaagent:opentelemetry-javaagent.jar -jar your-app.jar
```

### Configuration

| Environment variable                  | Default          | Description                                    |
|---------------------------------------|------------------|------------------------------------------------|
| `FLAGD_HOST`                          | `flagd`          | flagd host                                      |
| `FLAGD_PORT`                          | `8015`           | flagd gRPC **sync** port (in-process resolver)  |
| `OTELFEATURE_FLAG_NAME`               | `telemetryLevel` | flagd flag key to evaluate                     |

> [!IMPORTANT]
> `FLAGD_PORT` now defaults to flagd's gRPC **sync** port `8015`, not the OFREP
> REST port `8016` the previous version polled. If you set it explicitly,
> update it. `FLAGD_POLL_INTERVAL_SECONDS` is gone — there is no polling any
> more.

If flagd is unreachable, the extension defaults to **no suppression** (all
spans exported); once connected, the provider keeps the last known ruleset
across transient stream errors.

## Building

```sh
gradle build --no-daemon
# Output: build/libs/otelfeature-java-extension.jar
```

Requires Java 21+ and Gradle 8+.

## Dependencies

Earlier versions had no runtime dependencies at all, at the cost of a
hand-rolled OFREP client: `HttpURLConnection`, a regex "JSON parser" and a
polling scheduler. That is exactly what this version replaces, so the extension
now bundles the real thing:

- `dev.openfeature:sdk` — the OpenFeature Java SDK
- `dev.openfeature.contrib.providers:flagd` — the flagd provider, and with it
  gRPC, protobuf, jackson and gson

They are shaded into the single extension JAR (`shadowJar`), so usage is
unchanged — still one JAR on the agent's extension path — but that JAR is now
roughly 27 MB rather than 30 KB. `mergeServiceFiles()` is required because
gRPC discovers `ManagedChannelProvider`/`NameResolverProvider` through
`META-INF/services`.

Nothing is relocated. Should the agent's own gRPC instrumentation end up
tracing the flag sync stream, or the bundled classes collide with the host
application's, relocation is one `relocate` line per package in the same
`shadowJar` block.

The OTel SDK and autoconfigure SPI remain `compileOnly` — they're provided by
the agent when the extension is loaded, and `io.opentelemetry` is explicitly
excluded from the bundle so the extension uses the agent's copy instead of
shipping a second one.

## Relationship to otelfeature-instrument (Python)

| Aspect | Python (`otelfeature-instrument`) | Java (`otelfeature-java-extension`) |
|--------|------------------------------------|------------------------------------|
| Delivery mechanism | pip package + CLI launcher | Agent extension JAR via SPI |
| Instrumentation approach | SDK configurator | `-javaagent` bytecode instrumentation |
| flagd connection | gRPC (in-process resolver, port 8015) | gRPC (in-process resolver, port 8015) |
| Span suppression | Configures tracer to not emit INTERNAL spans | Filters INTERNAL spans at SpanProcessor level (before batching) |
| Flag updates | Push (gRPC sync stream) | Push (gRPC sync stream) |
| Flag evaluation | Once while static, per span while targeted | Once while static, per span while targeted |
| Service code changes | None | None |

Both achieve the same result: flagd-controlled `INTERNAL` span suppression
with zero service code changes. The delivery mechanism is idiomatic for each
language.

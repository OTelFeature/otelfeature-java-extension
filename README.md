# otelfeature-java-extension

An [OpenTelemetry Java agent](https://github.com/open-telemetry/opentelemetry-java-instrumentation)
extension that adds flagd-controlled span suppression — from dropping
`INTERNAL` spans to switching tracing off entirely — to any Java
service — the Java counterpart to the Python
[otelfeature-instrument](https://github.com/OTelFeature/otelfeature-instrument).

## What it does

The OTel Java agent auto-instruments libraries and produces spans of various
kinds: `SERVER` (incoming HTTP), `CLIENT` (outgoing HTTP/DB), and `INTERNAL`
(e.g. Spring WebMVC controller spans). This extension wraps the agent's
`SpanProcessor` and drops spans according to two flags served by
[flagd](https://flagd.dev/):

| `telemetryLevel` | `INTERNAL` spans | `SERVER` / `CLIENT` / `PRODUCER` / `CONSUMER` spans |
|-------------------|------------------|-----------------------------------------------------|
| `"FULL"` (default) | ✅ exported | ✅ exported |
| `"IO"`            | ❌ suppressed    | ✅ exported |
| `"OFF"`           | ❌ suppressed    | ❌ suppressed |

| `otelEnabled`      | effect |
|--------------------|--------|
| `true` (default)   | `telemetryLevel` decides |
| `false`            | same as `telemetryLevel: "OFF"`, whatever `telemetryLevel` says |

`otelEnabled` is the plain kill switch — a boolean anyone can flip. The `OFF`
variant is the same thing for operators who already manage `telemetryLevel`.
Tracing is off when **either** says so. This is a flag-native counterpart to
OpenTelemetry's own `OTEL_SDK_DISABLED`, which is read once at startup and
needs a restart to change.

Flip either flag in flagd and the change takes effect on the **very next span**
— no restart, no redeploy, and no waiting for a poll interval.

Both flags fail open. A value that is not `FULL`/`IO`/`OFF` (case-insensitive)
reads as `FULL`; a missing flag, a value of the wrong type or a provider error
reads as `FULL` / `true`. Only traces are affected: metrics and logs carry on
regardless.

> [!IMPORTANT]
> **`OFF` is suppression, not an SDK disable.** In Java, spans are still
> created, populated and propagated by the agent, then dropped in
> `FilteringSpanProcessor` before they reach the batch queue. So `OFF` (and
> `otelEnabled=false`) saves **export** — batching, serialisation, network,
> backend cost — but **not** the instrumentation's CPU. That changes when
> [OTelFeature/otelfeature-java-extension#21](https://github.com/OTelFeature/otelfeature-java-extension/issues/21)
> lands and root spans are dropped at the sampler instead.
>
> Context propagation keeps working while tracing is off, so a trace resumes
> coherently the moment the flag is flipped back: a span that survives is
> re-parented past any ancestor dropped while `OFF`, the same way children of
> suppressed `INTERNAL` spans are.

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
5. On each span's `onStart()`, `FilteringSpanProcessor` asks for the current
   level — `OFF` if `otelEnabled` is `false`, otherwise `telemetryLevel` — and,
   if that level drops this span's kind, records the span in a registry;
   `onEnd()` consults that registry and drops the span before it enters the
   batch queue. Everything else is passed through unchanged.

The decision is taken once per span, at `onStart`, and never revisited. Two
independent evaluations of the same span could legitimately disagree — the
flag can be flipped, or targeted so that it resolves per evaluation — and a
span recorded as suppressed at start but exported at end (or the reverse)
would corrupt the parent chain.

### Evaluating once, or evaluating always

`telemetryLevel` and `otelEnabled` are asked about on every single span, but
most of the time the answer cannot have changed since the last one: a flag with no `targeting` block
is one `defaultVariant` for the whole world. flagd says so in the resolution
`reason`, and that reason is the entire mechanism:

| `reason`          | flagd produced it because                        | same for every span? |
|-------------------|--------------------------------------------------|----------------------|
| `STATIC`          | flag has no `targeting`, served `defaultVariant`  | yes                  |
| `TARGETING_MATCH` | `targeting` rules ran and picked a variant        | no                   |
| `DEFAULT`         | `targeting` rules ran and matched nothing         | no                   |
| `DISABLED`        | flag `state` is `DISABLED`, served our default    | not worth assuming   |
| `ERROR`           | flag missing, provider not ready, ...             | not yet knowable     |

So each flag is evaluated **once, from the provider's event handler, without an
evaluation context**, purely to read the reason back:

- `STATIC` — and only `STATIC` — is kept. Every subsequent span is answered
  from an `AtomicReference`: no evaluation, no hook chain.
- Anything else means the value can depend on who's asking, so every span
  evaluates for itself.

Each flag is classified on its own, so a targeted `telemetryLevel` next to a
static `otelEnabled` costs one cached read plus one evaluation per span. One
consequence: a flag that is missing resolves with `ERROR` and is therefore
evaluated per span — define `otelEnabled` in the ruleset (as `true`) rather
than leaving it out, or every span pays for the lookup.

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
| `OTELFEATURE_FLAG_NAME`               | `telemetryLevel` | flagd key of the level flag (`FULL`/`IO`/`OFF`) |
| `OTELFEATURE_ENABLED_FLAG_NAME`       | `otelEnabled`    | flagd key of the boolean kill switch            |

> [!IMPORTANT]
> `FLAGD_PORT` now defaults to flagd's gRPC **sync** port `8015`, not the OFREP
> REST port `8016` the previous version polled. If you set it explicitly,
> update it. `FLAGD_POLL_INTERVAL_SECONDS` is gone — there is no polling any
> more.

A minimal flagd ruleset for both flags:

```json
{
  "$schema": "https://flagd.dev/schema/v0/flags.json",
  "flags": {
    "otelEnabled": {
      "state": "ENABLED",
      "defaultVariant": "on",
      "variants": { "on": true, "off": false }
    },
    "telemetryLevel": {
      "state": "ENABLED",
      "defaultVariant": "full",
      "variants": { "full": "FULL", "io": "IO", "off": "OFF" }
    }
  }
}
```

If flagd is unreachable, the extension defaults to **no suppression** (all
spans exported); once connected, the provider keeps the last known ruleset
across transient stream errors.

## Building

```sh
gradle build --no-daemon
# Output: build/libs/otelfeature-java-extension.jar
```

Requires Java 21+ and Gradle 9+ (the shadow plugin's floor; CI pins
9.7.1 rather than relying on whatever the runner image ships).

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
| `OFF` / `otelEnabled=false` | Suppressed at span creation: saves export **and** CPU | Dropped at `onEnd`: saves export, **not** CPU (until #21) |
| Flag updates | Push (gRPC sync stream) | Push (gRPC sync stream) |
| Flag evaluation | Once while static, per span while targeted | Once while static, per span while targeted |
| Service code changes | None | None |

Both achieve the same result: flagd-controlled span suppression with zero
service code changes. The delivery mechanism is idiomatic for each
language.

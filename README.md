# otelfeature-java-extension

An [OpenTelemetry Java agent](https://github.com/open-telemetry/opentelemetry-java-instrumentation)
extension that lets [flagd](https://flagd.dev/) flags control a Java
service's spans at runtime: switch tracing off, drop `INTERNAL` spans, drop
single spans by rule, and set the sampling ratio. No restart, no code change
in the service. The Python counterpart is
[otelfeature-instrument](https://github.com/OTelFeature/otelfeature-instrument).

> From the KubeCon + CloudNativeCon North America 2026 talk
> **OpenFeature as a Control Plane for OpenTelemetry** by Simon Schrottner
> ([Flagsmith](https://www.flagsmith.com/?utm_source=github&utm_medium=readme&utm_campaign=otelfeature&utm_content=otelfeature-java-extension)) and Juliano Costa
> ([Dash0](https://www.dash0.com/?utm_source=github&utm_medium=readme&utm_campaign=otelfeature&utm_content=otelfeature-java-extension)).
> Slides, recording and contacts: **[otelfeature.github.io/presentation](https://otelfeature.github.io/presentation/)**

The design rules (flag precedence, hard floors, the evaluation-context
contract) are specified in the
[OTelFeature/poc spec](https://github.com/OTelFeature/poc/blob/main/docs/spec/README.md).
This README covers what you need to run the extension.

## Usage

### Get the JAR

Each [GitHub Release](https://github.com/OTelFeature/otelfeature-java-extension/releases)
has `otelfeature-java-extension.jar` attached; the latest is always at
`https://github.com/OTelFeature/otelfeature-java-extension/releases/latest/download/otelfeature-java-extension.jar`.
To build it yourself, see [Development](#development).

### Attach it to the agent

```sh
java -javaagent:opentelemetry-javaagent.jar \
     -Dotel.javaagent.extensions=otelfeature-java-extension.jar \
     -jar your-app.jar
```

or with `OTEL_JAVAAGENT_EXTENSIONS=/path/to/otelfeature-java-extension.jar`.
It needs Java 21+ and the OpenTelemetry Java agent 2.x (tested with v2.31.1).

### Configuration

The extension's own settings are environment variables only (no `-D`
equivalents). All are read once at startup.

| Environment variable                  | Default          | Description                                    |
|---------------------------------------|------------------|------------------------------------------------|
| `FLAGD_HOST`                          | `flagd`          | flagd host                                      |
| `FLAGD_PORT`                          | `8015`           | flagd gRPC **sync** port (in-process resolver)  |
| `OTELFEATURE_FLAG_NAME`               | `telemetryLevel` | flagd key of the level flag (`FULL`/`IO`/`OFF`) |
| `OTELFEATURE_ENABLED_FLAG_NAME`       | `otelEnabled`    | flagd key of the boolean kill switch            |
| `OTELFEATURE_RECORD_SPAN_FLAG_NAME`   | `recordSpan`     | flagd key of the per-span boolean               |
| `OTELFEATURE_SAMPLE_RATIO_FLAG_NAME`  | `traceSampleRatio` | flagd key of the sampling ratio (`0.0`–`1.0`) |
| `OTELFEATURE_ERROR_RATE_INTERVAL`     | `10s`            | how often the [error rate](#the-services-error-rate-otelfeaturehttpservererror_rate) is computed (`500ms`, `10s`, `1m`, or plain seconds) |
| `OTELFEATURE_ERROR_RATE_MIN_REQUESTS` | `10`             | fewest requests per interval for an error rate  |
| `OTELFEATURE_ENFORCEMENT`             | `processor`      | `processor` or `sampler`, see [Enforcement](#enforcement-processor-or-sampler) |
| `OTELFEATURE_OPENFEATURE_TRACING_HOOK` | `false`         | `true` records the extension's own flag evaluations as span events, see [opt-in hook](#flag-evaluations-as-span-events-opt-in) |

A minimal flagd ruleset:

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

The agent's `OTEL_*` variables still apply: the env var is the default, a
flag is the live override. Which `OTEL_*` variables have a live flag, and the
two hard floors, are listed in the spec:
[`OTEL_*` env vars and live flags](https://github.com/OTelFeature/poc/blob/main/docs/spec/entity_model.md#otel_-env-vars-and-live-flags).

## What it does

| `telemetryLevel` | `INTERNAL` spans | `SERVER` / `CLIENT` / `PRODUCER` / `CONSUMER` spans |
|-------------------|------------------|-----------------------------------------------------|
| `"FULL"` (default) | ✅ exported | ✅ exported |
| `"IO"`            | ❌ suppressed    | ✅ exported |
| `"OFF"`           | ❌ suppressed    | ❌ suppressed |

| `otelEnabled`      | effect |
|--------------------|--------|
| `true` (default)   | `telemetryLevel` decides |
| `false`            | same as `telemetryLevel: "OFF"`, whatever `telemetryLevel` says |

Tracing is off when **either** flag says so. A change takes effect on the next
span after flagd pushes it. Only traces are affected; metrics and logs carry
on.

**Fail open.** A missing flag, a value of the wrong type, a value that is not
`FULL`/`IO`/`OFF` (case-insensitive) or a provider error reads as `FULL` /
`true`, and is logged once. Until flagd is reachable nothing is suppressed;
once connected, the provider keeps the last known ruleset across stream
errors.

**Hard floor.** With `OTEL_SDK_DISABLED=true` the extension installs nothing
(not even a flagd connection), and no flag turns tracing on.

### Dropping single spans: `recordSpan`

`recordSpan` is a boolean (default `true`) evaluated **per span** against the
[evaluation context](#the-evaluation-context-just-your-opentelemetry-attributes).
A span it answers `false` for is not exported.

```json
"recordSpan": {
  "state": "ENABLED",
  "defaultVariant": "on",
  "variants": { "on": true, "off": false },
  "targeting": { "if": [ { "and": [
      { "==": [ { "var": "service.name" }, "geocode" ] },
      { "==": [ { "var": "url.path" }, "/health" ] } ] }, "off", null ] }
}
```

`recordSpan=true` never re-enables what `otelEnabled=false`,
`telemetryLevel=OFF` or a hard floor turned off, and is not evaluated then.
The decision is made once, when the span starts.

| Span | `processor` mode | `sampler` mode |
|---|---|---|
| a root: no parent, or a `SERVER`/`CONSUMER` span with a remote parent (sampled or not) | suppressed with its whole local subtree | `DROP`, and its subtree with it |
| a database call over its own protocol (`CLIENT` with `db.system.name` `postgresql`, `mysql`, `redis`, `mongodb`, …, or the deprecated `db.system`) | suppressed | `DROP` |
| any other span, including `INTERNAL`/`CLIENT` with a remote parent and databases over HTTP (`elasticsearch`, `aws.dynamodb`, …) | suppressed; children re-parented | suppressed by the processor, so downstream services don't inherit `sampled=0` |
| a rule on `otel.scope.*` | applies | applies at the processor: a sampler is not told the scope |

Dropping an entry span with a remote parent ends the trace at the caller. In
`sampler` mode its outgoing calls carry `sampled=0`, so downstream services
drop their part too. In `processor` mode they carry `sampled=1`, so a
downstream service that still traces exports spans with a missing parent.

Caveats for `sampler` mode:

- **Write scope rules positively.** The sampler sees no scope, so "off unless
  `otel.scope.name` is X" would `DROP` every root. Write "off if
  `otel.scope.name` is X".
- **sqlcommenter.** With the agent's experimental JDBC sqlcommenter on (off by
  default), a dropped DB span sends `sampled=0` to a database that traces.
- **Non-parent-based samplers.** With `OTEL_TRACES_SAMPLER=always_on` instead
  of the default `parentbased_always_on`, the children of a root dropped by
  `recordSpan` are sampled afresh and exported as orphans.

A static `recordSpan`, or none at all, costs nothing per span. A missing
flag, a non-boolean value or a provider error records the span (fail open).

### Sampling ratio: `traceSampleRatio`

`traceSampleRatio` is a live `OTEL_TRACES_SAMPLER_ARG`: a double, `0.0`–`1.0`,
the fraction of **new traces** to sample. `0.0` samples nothing.

```json
"traceSampleRatio": {
  "state": "ENABLED",
  "defaultVariant": "tenth",
  "variants": { "tenth": 0.1, "all": 1.0 },
  "targeting": { "if": [ { "==": [ { "var": "deployment.environment.name" }, "staging" ] }, "all", null ] }
}
```

- **Roots only.** The ratio decides spans with no parent, by trace id
  (`Sampler.traceIdRatioBased`), in both
  [enforcement modes](#enforcement-processor-or-sampler). It is evaluated once
  per trace, not per span.
- **Whole traces.** While a ratio is set or targeted, every span with a parent
  follows its parent, even if the agent's sampler is not parent-based. With no
  ratio, the agent's sampler decides everything.
- **Fail open.** Above `1.0` is clamped to `1.0`. A negative or non-numeric
  value, a missing flag or a provider error leaves the decision to the agent's
  sampler (`OTEL_TRACES_SAMPLER` / `OTEL_TRACES_SAMPLER_ARG`).
- **Hard floor.** With `OTEL_TRACES_SAMPLER=always_off` nothing is sampled,
  whatever the ratio or a sampled parent says.

> [!IMPORTANT]
> **A ratio change only matters at the service that starts the trace.** A
> request that arrives with a `traceparent` follows its caller's decision. So
> raising `traceSampleRatio` on a downstream service does almost nothing.
> Target the rule at the entry service (`service.name` in the context), or
> roll the same ratio out everywhere.

**Java and Python can disagree.** Each extension uses its own SDK's trace-id
ratio formula: Java reads the low 64 bits of the trace id signed, Python
unsigned. At the same ratio they can decide differently for the same trace
id. With parent-based propagation that is harmless, because only the entry
service decides. Where a decision must agree across languages, use a flagd
`fractional` rule on `targetingKey` (the trace id): it buckets the same in
every service. See
[ratio-parity.md](https://github.com/OTelFeature/poc/blob/main/docs/spec/ratio-parity.md).

**Compared with `jaeger_remote`.** `OTEL_TRACES_SAMPLER=jaeger_remote` also
changes sampling remotely, by polling a Jaeger-compatible strategy endpoint.
`traceSampleRatio` reuses the flagd and OpenFeature setup you already run and
gets OpenFeature targeting (by environment, service, region, percentage
rollout, or the service's error rate). `jaeger_remote` fits better for
per-operation adaptive strategies. The two can run together: the remote
sampler decides while `traceSampleRatio` is unset, and the ratio replaces it
while it is set.

### Enforcement: processor or sampler

`OTELFEATURE_ENFORCEMENT` picks where `OFF` / `otelEnabled=false` is enforced.

| | `processor` (default) | `sampler` |
|---|---|---|
| What drops a span | `FilteringSpanProcessor`, after the span was created | `FlagAwareSampler`, before the span exists |
| Which spans `OFF` drops | every span | every local root, and so its subtree |
| CPU of dropped spans | fully paid; saves batching, serialisation and export | only the non-recording span the instrumentation still creates |
| `traceparent` sent downstream while `OFF` | `sampled=1` | `sampled=0`, downstream stops too |
| Flag flipped mid-request | the rest of the trace is dropped | the trace finishes (without its `INTERNAL` spans), the next one is dropped |

`INTERNAL` suppression stays in the processor in both modes, because a sampler
`DROP` takes the span's whole subtree with it. The sampler wraps the agent's
configured sampler; its `getDescription()` reports
`FlagAwareSampler{delegate=..., enforcement=processor|sampler}`. If the agent never
applies it, the processor enforces `OFF` and logs a warning.

### Known limitations

- **Trace context keeps propagating while tracing is off.** In `processor`
  mode outgoing calls still carry `traceparent` with `sampled=1`, because spans
  are dropped after they are created and propagated. A downstream service that
  still traces exports spans whose parent never arrives, so traces show gaps or
  a missing root. The same happens to the surviving half of a trace when the
  flag flips mid-request. In `sampler` mode the trace is cut off at this
  service instead.
- **No CPU saving in `processor` mode.** It saves export, not instrumentation
  work.
- **Spans that never end.** The registry of suppressed spans is swept of ended
  spans. Only if 100,000 suppressed spans are open at once (an instrumentation
  leak) is it cleared, which briefly lets in-flight spans through.

## The evaluation context: just your OpenTelemetry attributes

Targeted flags are evaluated against OpenTelemetry's own names:

| Source | Keys |
|---|---|
| Resource | every resource attribute: `service.name`, `service.namespace`, `service.version`, `deployment.environment.name`, `telemetry.sdk.language`, … |
| Instrumentation scope | `otel.scope.name`, `otel.scope.version` (processor decisions only) |
| Span at start | `span.kind` (`server`, `client`, `internal`, `producer`, `consumer`), `span.name`, and every attribute set at start: `url.path`, `http.request.method`, `server.address`, `db.system.name`, `db.operation.name`, `rpc.service`, … |
| Metrics | `otelfeature.http.server.error_rate`, see below |
| `targetingKey` | the trace id (hex) |

- Write the semconv name as is: `{"var": "service.name"}`.
- **Only what exists at span start.** `http.route` and
  `http.response.status_code` are **not** in the context: the agent sets them
  later. Target `url.path` instead.
- Resource keys, `span.kind` and `otel.scope.*` win over a span attribute of
  the same name.
- The context is sent nested by dot segment. An API, client or hook context
  that adds a top-level key such as `service` would replace that whole
  namespace; this extension uses an isolated OpenFeature API and adds no such
  contexts itself.

The full contract is in the spec:
[evaluation-context contract](https://github.com/OTelFeature/poc/blob/main/docs/spec/entity_model.md#evaluation-context-contract).

### The service's error rate: `otelfeature.http.server.error_rate`

The share of HTTP server requests that failed in the last interval, taken from
the agent's `http.server.request.duration` histogram. A request is an error if
it has `error.type`, or else `http.response.status_code >= 500`. Because it
comes from metrics, it keeps working when sampling is turned down. A rule can
raise sampling when errors rise:

```json
"traceSampleRatio": {
  "state": "ENABLED", "defaultVariant": "tenth",
  "variants": { "tenth": 0.1, "all": 1.0 },
  "targeting": { "if": [
    { ">": [ { "var": "otelfeature.http.server.error_rate" }, 0.05 ] }, "all", null ] }
}
```

- **Lag.** The rate is computed every `OTELFEATURE_ERROR_RATE_INTERVAL`
  (default `10s`) and the context holds the last finished interval, so it lags
  the traffic by one to two intervals.
- **Minimum requests.** An interval with fewer than
  `OTELFEATURE_ERROR_RATE_MIN_REQUESTS` (default `10`) requests publishes no
  rate, and the key is absent. A quiet service below the minimum never gets a
  rate, even in a total outage.
- **Prefer `>` rules.** `>` against a missing key is false. With `<` or `<=`,
  some JsonLogic evaluators treat a missing key as `0`, so "below 1%" would
  match when there is no rate.
- **No rate** with `OTEL_METRICS_EXPORTER=none`, or with the HTTP server
  instrumentation off. Error-rate rules then never match.
- **If a collection fails**, the last rate is withdrawn, not kept.
- **OTLP export is unchanged.** The extension adds a separate in-process
  metric reader with delta temporality. One side effect: asynchronous
  instruments' callbacks also run on its interval, so a callback with side
  effects (such as `observe(counter.getAndSet(0))`) loses data to it. The
  agent's own callbacks are not affected.

## Per-span cost

A flag with no `targeting` (flagd reason `STATIC`), or one missing from the
ruleset, is evaluated once and cached until flagd pushes a changed ruleset.
Such a span decision is one volatile read, measured at 5–19 ns per span, with
no allocation. A targeted flag builds the context and evaluates the rule on
every span: with a three-condition rule (`service.name`, `span.kind`,
`url.path`), 7.5–9.6 µs per span, of which
2.5–3 µs is building the context.

These are rough single-machine numbers (developer laptop, JDK 21, flagd `FILE`
resolver) from `FlagdEvaluationContextIntegrationTest.measuresPerSpanCost`,
not a benchmark.

## Flag evaluations as span events (opt-in)

The extension writes nothing about its decisions onto spans. To debug a rule,
set `OTELFEATURE_OPENFEATURE_TRACING_HOOK=true` (only `true`, case and
whitespace ignored). This attaches the bundled
[OpenFeature OpenTelemetry hook](https://github.com/open-feature/java-sdk-contrib/tree/main/hooks/open-telemetry)
to the extension's own, isolated OpenFeature API. The application's
OpenFeature API never sees it.

- Keep it off in normal operation: each targeted flag evaluated adds an
  event, so several per span start.
- Events land on the span current while the flag is evaluated, usually the
  **parent** of the span being decided. Evaluations for a new root with no
  current span, such as every `traceSampleRatio` evaluation of an incoming
  request, record nothing.
- Evaluation errors are not recorded.
- The Java hook (3.3.1) writes semconv **1.28** names: event `feature_flag`
  with `feature_flag.key`, `feature_flag.variant`, `feature_flag.provider_name`.
  The Python extension writes the current names (`feature_flag.evaluation`,
  `feature_flag.result.variant`, `feature_flag.provider.name`). Queries across
  both have to match both.

## How it works

The agent finds `OtelfeatureCustomizer` through its
[`AutoConfigurationCustomizerProvider` SPI](https://opentelemetry.io/docs/zero-code/java/agent/extensions/).
At startup it:

1. wraps the agent's `SpanProcessor` in `FilteringSpanProcessor`, and its
   exporter in `ReparentingSpanExporter`, which re-parents the children of
   suppressed spans;
2. wraps the agent's sampler in `FlagAwareSampler`;
3. registers a flagd
   [in-process](https://flagd.dev/reference/specifications/in-process-providers/)
   provider. It opens a gRPC sync stream to flagd, which pushes the ruleset, so
   evaluation is local. Registration is asynchronous and does not block agent
   startup.

## Relationship to otelfeature-instrument (Python)

| Aspect | Python (`otelfeature-instrument`) | Java (`otelfeature-java-extension`) |
|--------|------------------------------------|------------------------------------|
| Delivery | Python package (release wheel) + CLI launcher | agent extension JAR via SPI |
| flagd connection | in-process, gRPC sync on 8015 | in-process, gRPC sync on 8015 |
| `OFF` / `otelEnabled=false` | suppressed at span creation: saves export and CPU | `processor`: saves export, not CPU; `sampler`: roots never recorded |
| Service code changes | none | none |

## Development

The only host requirement is **Docker** (on Windows, run it in WSL). The
toolchain (JDK 21, Gradle 9, all dependencies) is the image in
[`ci/Dockerfile`](ci/Dockerfile), which CI uses too.

```sh
make test    # compile, test and package in the toolchain image, as CI does
make jar     # just build/libs/otelfeature-java-extension.jar, no tests
make shell   # a shell in the toolchain image, repo mounted at /src
```

- Builds run `--offline`, so a dependency missing from the image fails.
- With rootless Docker or Podman, run `make test DOCKER_USER=0:0`.
- To build the JAR from a git ref without a checkout:

  ```sh
  docker build --target artifact --output type=local,dest=out -f ci/Dockerfile \
    https://github.com/OTelFeature/otelfeature-java-extension.git#main
  ```

  The JAR lands at `out/otelfeature-java-extension.jar`.

Without Docker, `gradle build` works with Java 21+ and Gradle 9+.

**Dependencies.** The OpenFeature SDK, the flagd provider (with gRPC,
protobuf, jackson and gson) the OpenFeature OpenTelemetry hook and `slf4j-jdk14` are shaded
into the one JAR (about 27 MB). Only the semconv constants are relocated (to
`io.otelfeature.shaded.io.opentelemetry.semconv`). `io.opentelemetry` is not
bundled: the agent provides it.

**Releases.** Pushing a `v*` tag matching `version` in `build.gradle` (`v0.4.0`
for `0.4.0`) builds, tests and creates a GitHub Release with the JAR. A
`v0.4.0-rc.N` tag becomes a pre-release; any other tag is rejected. Check the
generated notes of a final release: GitHub may compare against the rc.

## Talk to us

This is a prototype with open questions, and we'd like to hear how it
fits your setup:

- [Open an issue](https://github.com/OTelFeature/otelfeature-java-extension/issues/new)
- CNCF Slack ([invite](https://slack.cncf.io)): `#openfeature` and `#opentelemetry`
- The speakers' websites: [Simon Schrottner](https://schrottner.at) and
  [Juliano Costa](https://jcosta.dev)

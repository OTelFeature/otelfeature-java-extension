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

Flip the flag in flagd and the change takes effect within seconds — no
restart, no redeploy.

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
│                       ┌──────▼───────┐      ┌──────────────┐ │
│                       │ FlagdClient  │      │ SpanExporter │ │
│                       │ (polls every │      │ → OTLP to    │ │
│                       │  5 seconds)  │      │   collector  │ │
│                       └──────┬───────┘      └──────────────┘ │
│                              │                               │
│                              └──HTTP──▶ flagd :8016 (OFREP)  │
└──────────────────────────────────────────────────────────────┘
```

The extension is discovered via the OTel Java agent's
[`AutoConfigurationCustomizerProvider` SPI](https://opentelemetry.io/docs/zero-code/java/agent/extensions/).
At agent startup:

1. The agent scans `META-INF/services/` on the classpath/extension path.
2. It finds `OtelfeatureCustomizer` and calls `customize()`.
3. The customizer wraps the auto-configured `SpanProcessor` with
   `FilteringSpanProcessor` via `addSpanProcessorCustomizer`.
4. `FlagdClient` starts polling flagd's OFREP REST endpoint (port 8016)
   every 5 seconds for the `telemetryLevel` flag. The first poll runs
   asynchronously on a background thread — agent startup is not blocked.
5. On each span's `onEnd()`, `FilteringSpanProcessor` checks the cached flag
   value and drops `INTERNAL` spans before they enter the batch queue. All
   other span kinds are passed through unchanged.

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
| `FLAGD_PORT`                          | `8016`           | flagd OFREP REST port                           |
| `FLAGD_POLL_INTERVAL_SECONDS`         | `5`              | How often to poll flagd for flag changes       |
| `OTELFEATURE_FLAG_NAME`               | `telemetryLevel` | flagd flag key to evaluate                     |

If flagd is unreachable, the extension defaults to **no suppression** (all
spans exported) and keeps the last known value on transient errors.

## Building

```sh
gradle build --no-daemon
# Output: build/libs/otelfeature-java-extension-0.2.1.jar
```

Requires Java 21+ and Gradle 8+.

## Dependencies

The extension has **zero runtime dependencies** beyond the JDK and the OTel
Java agent (which provides the SDK at runtime). It uses only:

- `java.net.HttpURLConnection` (JDK 11+) for flagd communication
- `java.util.regex` for lightweight JSON parsing
- `java.util.concurrent` for the polling scheduler
- `java.util.logging.Logger` (part of the JDK)

The OTel SDK and autoconfigure SPI are `compileOnly` dependencies — they're
provided by the agent when the extension is loaded.

## Relationship to otelfeature-instrument (Python)

| Aspect | Python (`otelfeature-instrument`) | Java (`otelfeature-java-extension`) |
|--------|------------------------------------|------------------------------------|
| Delivery mechanism | pip package + CLI launcher | Agent extension JAR via SPI |
| Instrumentation approach | SDK configurator | `-javaagent` bytecode instrumentation |
| flagd connection | gRPC (in-process resolver, port 8015) | HTTP (OFREP REST, port 8016) |
| Span suppression | Configures tracer to not emit INTERNAL spans | Filters INTERNAL spans at SpanProcessor level (before batching) |
| Flag updates | Push (gRPC sync stream) | Poll (every 5 seconds) |
| Service code changes | None | None |

Both achieve the same result: flagd-controlled `INTERNAL` span suppression
with zero service code changes. The delivery mechanism is idiomatic for each
language.

## License

This project is licensed under the [Apache License 2.0](LICENSE).

```
Copyright 2024 OTelFeature

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

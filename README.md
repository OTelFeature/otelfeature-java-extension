# otelfeature-java-extension

An [OpenTelemetry Java agent](https://github.com/open-telemetry/opentelemetry-java-instrumentation)
extension that adds flagd-controlled `INTERNAL` span suppression to any Java
service — the Java counterpart to the Python
[otelfeature-instrument](https://github.com/OTelFeature/otelfeature-instrument).

## What it does

The OTel Java agent auto-instruments libraries and produces spans of various
kinds: `SERVER` (incoming HTTP), `CLIENT` (outgoing HTTP/DB), and `INTERNAL`
(e.g. Spring WebMVC controller spans). This extension wraps the agent's span
exporter and **filters out `INTERNAL` spans** when the `telemetryLevel` flag
served by [flagd](https://flagd.dev/) is set to `"IO"`.

| `telemetryLevel` | `INTERNAL` spans | `SERVER` / `CLIENT` spans |
|-------------------|------------------|---------------------------|
| `"FULL"` (default) | ✅ exported | ✅ exported |
| `"IO"`            | ❌ suppressed    | ✅ exported |

Flip the flag in flagd and the change takes effect within seconds — no
restart, no redeploy.

## How it works

```
┌──────────────────────────────────────────────────────┐
│  JVM + OTel Java Agent                               │
│                                                      │
│  ┌──────────────┐   ┌──────────────┐   ┌───────────┐ │
│  │ Instrumented │──▶│ SpanExporter │──▶│  OTLP to  │ │
│  │ libraries    │   │ (wrapped)    │   │ collector │ │
│  └──────────────┘   └──────┬───────┘   └───────────┘ │
│                            │                         │
│                     ┌──────▼───────┐                 │
│                     │ Filtering    │                 │
│                     │ SpanExporter │                 │
│                     │  (this ext)  │                 │
│                     └──────┬───────┘                 │
│                            │                         │
│                     ┌──────▼───────┐                 │
│                     │ FlagdClient  │──HTTP──▶ flagd  │
│                     │ (polls every │   :8016  OFREP  │
│                     │  5 seconds)  │                 │
│                     └──────────────┘                 │
└──────────────────────────────────────────────────────┘
```

The extension is discovered via the OTel Java agent's
[`AutoConfigurationCustomizerProvider` SPI](https://opentelemetry.io/docs/zero-code/java/agent/extensions/).
At agent startup:

1. The agent scans `META-INF/services/` on the classpath/extension path.
2. It finds `OtelfeatureCustomizer` and calls `customize()`.
3. The customizer wraps the real span exporter with `FilteringSpanExporter`.
4. `FlagdClient` starts polling flagd's OFREP REST endpoint (port 8016)
   every 5 seconds for the `telemetryLevel` flag.
5. On each span export batch, `FilteringSpanExporter` checks the cached flag
   value and drops `INTERNAL` spans when suppression is active.

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

| Environment variable                  | Default       | Description                                    |
|---------------------------------------|---------------|------------------------------------------------|
| `FLAGD_HOST`                          | `flagd`       | flagd host                                      |
| `FLAGD_PORT`                          | `8016`        | flagd OFREP REST port                           |
| `FLAGD_POLL_INTERVAL_SECONDS`         | `5`           | How often to poll flagd for flag changes       |
| `OTELFEATURE_FLAG_NAME`               | `telemetryLevel` | flagd flag key to evaluate                   |

If flagd is unreachable, the extension defaults to **no suppression** (all
spans exported) and keeps the last known value on transient errors.

## Building

```sh
gradle build -x test --no-daemon
# Output: build/libs/otelfeature-java-extension-0.1.0.jar
```

Requires Java 21+ and Gradle 8+.

## Dependencies

The extension has **zero runtime dependencies** beyond the JDK and the OTel
Java agent (which provides the SDK at runtime). It uses only:

- `java.net.http.HttpClient` (JDK 11+) for flagd communication
- `java.util.regex` for lightweight JSON parsing
- `java.util.concurrent` for the polling scheduler
- `org.slf4j.Logger` (provided by the agent at runtime)

The OTel SDK and autoconfigure SPI are `compileOnly` dependencies — they're
provided by the agent when the extension is loaded.

## Relationship to otelfeature-instrument (Python)

| Aspect | Python (`otelfeature-instrument`) | Java (`otelfeature-java-extension`) |
|--------|------------------------------------|------------------------------------|
| Delivery mechanism | pip package + CLI launcher | Agent extension JAR via SPI |
| Instrumentation approach | SDK configurator | `-javaagent` bytecode instrumentation |
| flagd connection | gRPC (in-process resolver, port 8015) | HTTP (OFREP REST, port 8016) |
| Span suppression | Configures tracer to not emit INTERNAL spans | Filters INTERNAL spans at export time |
| Flag updates | Push (gRPC sync stream) | Poll (every 5 seconds) |
| Service code changes | None | None |

Both achieve the same result: flagd-controlled `INTERNAL` span suppression
with zero service code changes. The delivery mechanism is idiomatic for each
language.

package io.otelfeature;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

/**
 * SPI entry point for the otelfeature-java-extension.
 *
 * <p>Registered via {@code META-INF/services/} and discovered by the OTel
 * Java agent at startup. Wraps the configured {@link
 * io.opentelemetry.sdk.trace.export.SpanExporter SpanExporter} with a
 * {@link FilteringSpanExporter} that suppresses {@code INTERNAL} spans
 * based on the {@code telemetryLevel} flag served by flagd.
 *
 * <p>This is the Java equivalent of the Python {@code otelfeature-instrument}
 * launcher — it adds flagd-controlled span suppression to any Java service
 * running under the OTel Java agent, with zero code changes to the service
 * itself.
 *
 * <p>Usage:
 * <pre>
 * java -javaagent:opentelemetry-javaagent.jar \
 *      -Dotel.javaagent.extensions=otelfeature-java-extension.jar \
 *      -jar your-app.jar
 * </pre>
 *
 * <p>Or via environment variable:
 * <pre>
 * OTEL_JAVAAGENT_EXTENSIONS=/path/to/otelfeature-java-extension.jar
 * </pre>
 */
public class OtelfeatureCustomizer implements AutoConfigurationCustomizerProvider {

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        FlagdClient flagdClient = new FlagdClient();

        autoConfiguration.addSpanExporterCustomizer(
                (exporter, config) -> new FilteringSpanExporter(exporter, flagdClient));
    }
}

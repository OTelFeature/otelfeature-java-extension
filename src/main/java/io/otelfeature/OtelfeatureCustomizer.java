/*
 * Copyright 2024 OTelFeature
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */

package io.otelfeature;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;

/**
 * SPI entry point for the otelfeature-java-extension.
 *
 * <p>Registered via {@code META-INF/services/} and discovered by the OTel
 * Java agent at startup. Wraps the auto-configured {@link
 * io.opentelemetry.sdk.trace.SpanProcessor SpanProcessor} with a
 * {@link FilteringSpanProcessor} that suppresses {@code INTERNAL} spans
 * based on the {@code telemetryLevel} flag served by flagd.
 *
 * <p>Filtering at the {@code SpanProcessor} level (before batching) is more
 * performant than exporter-level filtering: dropped spans never enter the
 * batch queue, saving memory and CPU.
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

        autoConfiguration.addSpanProcessorCustomizer(
                (processor, config) -> new FilteringSpanProcessor(processor, flagdClient));
    }
}

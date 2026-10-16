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
import java.util.logging.Logger;

/**
 * SPI entry point for the otelfeature-java-extension.
 *
 * <p>Registered via {@code META-INF/services/} and discovered by the OTel
 * Java agent at startup. It installs two cooperating components:
 *
 * <ol>
 *   <li><b>FilteringSpanProcessor</b> (via {@code addSpanProcessorCustomizer}):
 *       drops spans before they enter the batch queue when suppression is
 *       active ({@code INTERNAL} at {@code IO}, all of them at {@code OFF}).
 *       Records dropped span IDs in a shared registry.
 *       It asks {@link FlagdTelemetryPolicy}, which syncs the flag ruleset
 *       in-process from flagd and evaluates it only as often as the flag
 *       actually requires - see {@link TelemetryFlag}.</li>
 *   <li><b>ReparentingSpanExporter</b> (via {@code addSpanExporterCustomizer}):
 *       re-parents surviving spans whose parents were suppressed, using the
 *       shared registry to resolve the nearest non-suppressed ancestor.</li>
 * </ol>
 *
 * <p>This two-layer approach gives both performance (dropped spans never
 * batched) and correctness (children re-parented, trace hierarchy preserved).
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

    private static final Logger log = Logger.getLogger(OtelfeatureCustomizer.class.getName());

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        // OTEL_SDK_DISABLED is a hard floor: flags only ever reduce telemetry,
        // never enable what the environment turned off. Autoconfigure still
        // calls customizers when the SDK is disabled (it then builds a no-op
        // SDK and ignores them), so don't even open a connection to flagd.
        if (!TelemetryPolicy.startupDefaults().enabled()) {
            log.info("otelfeature-java-extension: OTEL_SDK_DISABLED is set - not installing;"
                    + " flags never enable what OTEL_SDK_DISABLED turned off");
            return;
        }
        FlagdTelemetryPolicy policy = new FlagdTelemetryPolicy();
        SuppressedSpanRegistry registry = new SuppressedSpanRegistry();

        autoConfiguration.addSpanProcessorCustomizer(
                (processor, config) -> new FilteringSpanProcessor(processor, policy, registry));

        autoConfiguration.addSpanExporterCustomizer(
                (exporter, config) -> new ReparentingSpanExporter(exporter, registry));

        // The provider holds a gRPC channel to flagd; close it on the way out
        // rather than leaving the sync stream to be torn down by the JVM.
        Runtime.getRuntime().addShutdownHook(new Thread(policy::shutdown,
                "otelfeature-shutdown"));
    }
}

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The SPI entry point, driven through the real autoconfigure path. */
@DisplayName("OtelfeatureCustomizer under autoconfigure")
class OtelfeatureCustomizerTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty("otel.sdk.disabled");
    }

    @Test
    @DisplayName("OTEL_SDK_DISABLED: the customizer still runs, installs nothing, and nothing is exported")
    void sdkDisabledIsAHardFloor() {
        // The agent reads -Dotel.sdk.disabled / OTEL_SDK_DISABLED; so do we.
        System.setProperty("otel.sdk.disabled", "true");
        List<String> messages = new ArrayList<>();
        Logger logger = Logger.getLogger(OtelfeatureCustomizer.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                messages.add(record.getMessage());
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            AutoConfiguredOpenTelemetrySdk sdk = AutoConfiguredOpenTelemetrySdk.builder()
                    .addPropertiesSupplier(() -> Map.of(
                            "otel.sdk.disabled", "true",
                            "otel.traces.exporter", "none",
                            "otel.metrics.exporter", "none",
                            "otel.logs.exporter", "none"))
                    .disableShutdownHook()
                    .build();

            assertTrue(messages.stream().anyMatch(m -> m.contains("not installing")),
                    "autoconfigure calls SPI customizers even with the SDK disabled; ours must bow out: "
                            + messages);
            // A disabled SDK (1.66) still hands out recording spans, but its only
            // span processor is a no-op: nothing is ever exported, and with our
            // customizer gone there is no flag that could change that.
            String tracerProvider = sdk.getOpenTelemetrySdk().getSdkTracerProvider().toString();
            assertTrue(tracerProvider.contains("spanProcessor=NoopSpanProcessor"),
                    "a disabled SDK must have no span processors: " + tracerProvider);
            assertFalse(tracerProvider.contains("FilteringSpanProcessor"));
        } finally {
            logger.removeHandler(handler);
        }
    }
}

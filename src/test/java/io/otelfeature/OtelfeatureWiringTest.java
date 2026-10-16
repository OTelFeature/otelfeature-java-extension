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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openfeature.contrib.providers.flagd.Config;
import dev.openfeature.contrib.providers.flagd.FlagdOptions;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.opentelemetry.sdk.autoconfigure.spi.internal.DefaultConfigProperties;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.SdkMeterProviderBuilder;
import io.opentelemetry.sdk.resources.Resource;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link OtelfeatureCustomizer#install} end to end, through a recording stand-in
 * for the agent's {@link AutoConfigurationCustomizer}: the hooks it registers
 * are applied the way autoconfigure applies them.
 */
@DisplayName("OtelfeatureCustomizer wiring")
class OtelfeatureWiringTest {

    private static final long TIMEOUT_MILLIS = 15_000;

    @TempDir
    Path tempDir;

    private FlagdTelemetryPolicy policy;
    private SdkMeterProvider meterProvider;

    @AfterEach
    void tearDown() {
        if (meterProvider != null) {
            meterProvider.shutdown();
        }
        if (policy != null) {
            policy.shutdown();
        }
    }

    /** Records every hook registered on it; returns itself, like the real one. */
    private static AutoConfigurationCustomizer recorder(Map<String, List<Object>> hooks) {
        return (AutoConfigurationCustomizer) Proxy.newProxyInstance(
                AutoConfigurationCustomizer.class.getClassLoader(),
                new Class<?>[] {AutoConfigurationCustomizer.class},
                (proxy, method, args) -> {
                    if (method.getReturnType() == AutoConfigurationCustomizer.class) {
                        hooks.computeIfAbsent(method.getName(), k -> new ArrayList<>()).add(args[0]);
                        return proxy;
                    }
                    return null;
                });
    }

    @SuppressWarnings("unchecked")
    private static <T> T apply(Map<String, List<Object>> hooks, String hook, T target, ConfigProperties config) {
        for (Object customizer : hooks.getOrDefault(hook, List.of())) {
            target = ((BiFunction<T, ConfigProperties, T>) customizer).apply(target, config);
        }
        return target;
    }

    private void startPolicy() throws IOException {
        Path file = tempDir.resolve("flags.json");
        Files.writeString(file, """
                {
                  "$schema": "https://flagd.dev/schema/v0/flags.json",
                  "flags": {
                    "traceSampleRatio": {
                      "state": "ENABLED", "defaultVariant": "tenth",
                      "variants": { "tenth": 0.1, "all": 1.0 },
                      "targeting": { "if": [
                        { ">": [ { "var": "otelfeature.http.server.error_rate" }, 0.05 ] }, "all", null ] }
                    }
                  }
                }
                """, StandardCharsets.UTF_8);
        policy = new FlagdTelemetryPolicy("telemetryLevel", "otelEnabled", "recordSpan", "traceSampleRatio",
                TelemetryPolicy.FULL,
                FlagdOptions.builder().resolverType(Config.Resolver.FILE)
                        .offlineFlagSourcePath(file.toString()).offlinePollIntervalMs(50).build());
        assertTrue(waitFor(() -> policy.sampleRatioInPlay()), "provider never became ready");
    }

    private static SpanStart root() {
        return new SpanStart("0af7651916cd43dd8448eb211c80319c", "GET /weather", SpanKind.SERVER,
                Attributes.empty(), null);
    }

    @Test
    @DisplayName("the error-rate reader is registered, and its rate reaches the policy's rules")
    void errorRateReachesTheRules() throws IOException {
        startPolicy();
        Map<String, List<Object>> hooks = new java.util.HashMap<>();
        ErrorRateReader reader = new ErrorRateReader(policy::setErrorRate, Duration.ofHours(1), 10);
        OtelfeatureCustomizer.install(recorder(hooks), policy, reader, Enforcement.PROCESSOR);
        ConfigProperties config = DefaultConfigProperties.createFromMap(Map.of());

        Resource resource = apply(hooks, "addResourceCustomizer", Resource.getDefault(), config);
        assertEquals(Resource.getDefault(), resource, "the resource is captured, not changed");
        SdkMeterProviderBuilder builder = apply(hooks, "addMeterProviderCustomizer", SdkMeterProvider.builder(), config);
        meterProvider = builder.build();

        var histogram = meterProvider.get("io.opentelemetry.tomcat-10.0")
                .histogramBuilder(ErrorRateReader.METRIC).build();
        for (int i = 0; i < 20; i++) {
            histogram.record(0.01, i % 5 == 0
                    ? Attributes.of(AttributeKey.longKey("http.response.status_code"), 503L,
                            AttributeKey.stringKey("error.type"), "503")
                    : Attributes.of(AttributeKey.longKey("http.response.status_code"), 200L));
        }
        assertEquals(0.1, policy.policy(root()).sampleRatio(), "no rate yet");

        reader.collect(); // the interval elapses: 4 of 20 failed

        assertEquals(1.0, policy.policy(root()).sampleRatio(), "the rule saw error_rate 0.2");
    }

    @Test
    @DisplayName("OTEL_METRICS_EXPORTER=none: no reader, the metrics SDK stays off")
    void metricsExporterNoneRegistersNothing() throws IOException {
        startPolicy();
        Map<String, List<Object>> hooks = new java.util.HashMap<>();
        List<Double> published = new ArrayList<>();
        ErrorRateReader reader = new ErrorRateReader(published::add, Duration.ofHours(1), 1);
        OtelfeatureCustomizer.install(recorder(hooks), policy, reader, Enforcement.PROCESSOR);

        SdkMeterProviderBuilder builder = apply(hooks, "addMeterProviderCustomizer", SdkMeterProvider.builder(),
                DefaultConfigProperties.createFromMap(Map.of("otel.metrics.exporter", "none")));
        meterProvider = builder.build();

        assertTrue(meterProvider.toString().contains("metricReaders=[]"),
                "no reader may be registered: " + meterProvider);
    }

    private static boolean waitFor(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}

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

import dev.openfeature.contrib.providers.flagd.Config;
import dev.openfeature.contrib.providers.flagd.FlagdOptions;
import dev.openfeature.contrib.providers.flagd.FlagdProvider;
import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.EventDetails;
import dev.openfeature.sdk.OpenFeatureAPI;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Binds {@link TelemetryLevelResolver} to a real flagd
 * <a href="https://flagd.dev/reference/specifications/in-process-providers/">in-process</a>
 * OpenFeature provider.
 *
 * <p>In-process is not a deployment preference here, it is what the design
 * needs:
 *
 * <ul>
 *   <li><b>No network call on the span path.</b> flagd streams the whole flag
 *       ruleset to this process over a gRPC sync stream and keeps it current;
 *       evaluation is then a local operation. The OFREP REST poller this
 *       replaced could only ever be as fresh as its poll interval, and every
 *       poll was an HTTP round trip.</li>
 *   <li><b>It emits configuration-change events.</b> That is what makes it safe
 *       to stop evaluating a static flag at all: when the ruleset moves, the
 *       provider says so and {@link TelemetryLevelResolver#classify} re-runs. A
 *       polling client has no such signal.</li>
 * </ul>
 *
 * <p>Configuration, all optional:
 *
 * <ul>
 *   <li>{@code FLAGD_HOST} - flagd host (default: {@code flagd})</li>
 *   <li>{@code FLAGD_PORT} - flagd gRPC <em>sync</em> port (default: {@code 8015};
 *       note this is not the {@code 8013} evaluation port, nor the {@code 8016}
 *       OFREP port the previous implementation polled)</li>
 *   <li>{@code OTELFEATURE_FLAG_NAME} - flag key (default: {@code telemetryLevel})</li>
 * </ul>
 */
public final class FlagdTelemetryLevel implements TelemetryLevelSource {

    private static final Logger log = Logger.getLogger(FlagdTelemetryLevel.class.getName());

    private static final String DEFAULT_HOST = "flagd";
    private static final int DEFAULT_SYNC_PORT = 8015;

    private final OpenFeatureAPI api;
    private final TelemetryLevelResolver resolver;

    public FlagdTelemetryLevel() {
        this(env("OTELFEATURE_FLAG_NAME", "telemetryLevel"), inProcessOptions());
    }

    /**
     * Visible for testing: lets a test drive the same wiring through flagd's
     * {@code FILE} resolver, which shares the parser, targeting engine and
     * configuration-change events with {@code IN_PROCESS}.
     *
     * @param flagKey the flag to resolve
     * @param options how to reach flagd
     */
    FlagdTelemetryLevel(String flagKey, FlagdOptions options) {
        // createIsolated(), deliberately not getInstance(): this extension runs
        // inside somebody else's application, and that application may well use
        // OpenFeature itself. Registering our flagd provider on the global
        // singleton would replace theirs.
        this.api = OpenFeatureAPI.createIsolated();
        Client client = api.getClient();

        this.resolver = new TelemetryLevelResolver(
                flagKey,
                context -> client.getStringDetails(flagKey, TelemetryLevelResolver.DEFAULT_VALUE, context));

        // Subscribe before registering the provider, so no event can land in
        // the gap. The SDK also replays the current state to a handler as it is
        // added, so subscribing early costs nothing.
        client.onProviderReady(this::reclassify);
        client.onProviderConfigurationChanged(this::reclassify);
        client.onProviderError(this::reclassify);
        client.onProviderStale(this::reclassify);

        // setProvider, not setProviderAndWait: agent startup must not block on
        // flagd being reachable. Until the provider is ready the flag resolves
        // to its default, which is "suppress nothing".
        api.setProvider(new FlagdProvider(options));

        log.info("otelfeature-java-extension: syncing flag '" + flagKey + "' from flagd");
    }

    private static FlagdOptions inProcessOptions() {
        String host = env("FLAGD_HOST", DEFAULT_HOST);
        int port = envPort(DEFAULT_SYNC_PORT);
        log.info("otelfeature-java-extension: flagd in-process sync stream at " + host + ":" + port);
        return FlagdOptions.builder()
                .resolverType(Config.Resolver.IN_PROCESS)
                .host(host)
                .port(port)
                .build();
    }

    private void reclassify(EventDetails details) {
        try {
            resolver.classify(details == null ? null : details.getFlagsChanged());
        } catch (Throwable t) {
            // An exception escaping here would propagate into the SDK's event
            // dispatch; telemetry configuration must never take the host
            // application with it.
            log.log(Level.WARNING, "otelfeature-java-extension: failed to evaluate telemetry level", t);
        }
    }

    @Override
    public boolean shouldSuppressInternal() {
        return resolver.shouldSuppressInternal();
    }

    @Override
    public void shutdown() {
        api.shutdown();
    }

    /**
     * Visible for testing: the kept answer, or {@code null} when every span is
     * evaluated.
     *
     * @return the cached decision, or {@code null} if there isn't one
     */
    Boolean cachedDecision() {
        return resolver.cachedDecision();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int envPort(int fallback) {
        String value = System.getenv("FLAGD_PORT");
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warning("otelfeature-java-extension: FLAGD_PORT='" + value + "' is not a number, using "
                    + fallback);
            return fallback;
        }
    }
}

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
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves the {@link TelemetryPolicy} from a real flagd
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
 *       provider says so and {@link TelemetryFlag#classify} re-runs. A
 *       polling client has no such signal.</li>
 * </ul>
 *
 * <p>Two flags feed the policy, each a {@link TelemetryFlag} with its own
 * caching:
 *
 * <ul>
 *   <li>{@code enabled} is {@code otelEnabled && telemetryLevel != OFF}: off if
 *       either says off. The boolean is the one to flip by hand; the {@code OFF}
 *       variant is there for whoever already manages {@code telemetryLevel}.</li>
 *   <li>{@code suppressInternal} is {@code telemetryLevel == IO}.</li>
 * </ul>
 *
 * <p>Whatever cannot be resolved - flagd unreachable, a missing flag, a wrong
 * type, an unknown variant - takes its field from
 * {@link TelemetryPolicy#startupDefaults()}, and the failure is logged once
 * rather than per span.
 *
 * <p>Configuration, all optional:
 *
 * <ul>
 *   <li>{@code FLAGD_HOST} - flagd host (default: {@code flagd})</li>
 *   <li>{@code FLAGD_PORT} - flagd gRPC <em>sync</em> port (default: {@code 8015};
 *       note this is not the {@code 8013} evaluation port, nor the {@code 8016}
 *       OFREP port the previous implementation polled)</li>
 *   <li>{@code OTELFEATURE_FLAG_NAME} - level flag key (default: {@code telemetryLevel})</li>
 *   <li>{@code OTELFEATURE_ENABLED_FLAG_NAME} - kill-switch flag key (default:
 *       {@code otelEnabled})</li>
 * </ul>
 */
public final class FlagdTelemetryPolicy implements TelemetryPolicySource {

    private static final Logger log = Logger.getLogger(FlagdTelemetryPolicy.class.getName());

    private static final String DEFAULT_HOST = "flagd";
    private static final int DEFAULT_SYNC_PORT = 8015;

    private final OpenFeatureAPI api;
    private final TelemetryFlag<String, TelemetryLevel> levelFlag;
    private final TelemetryFlag<Boolean, Boolean> enabledFlag;

    public FlagdTelemetryPolicy() {
        this(env("OTELFEATURE_FLAG_NAME", "telemetryLevel"),
                env("OTELFEATURE_ENABLED_FLAG_NAME", "otelEnabled"),
                TelemetryPolicy.startupDefaults(),
                inProcessOptions());
    }

    /**
     * Visible for testing: lets a test drive the same wiring through flagd's
     * {@code FILE} resolver, which shares the parser, targeting engine and
     * configuration-change events with {@code IN_PROCESS}.
     *
     * @param levelFlagKey the {@code telemetryLevel} flag to resolve
     * @param enabledFlagKey the {@code otelEnabled} flag to resolve
     * @param defaults the per-field fallback
     * @param options how to reach flagd
     */
    FlagdTelemetryPolicy(
            String levelFlagKey, String enabledFlagKey, TelemetryPolicy defaults, FlagdOptions options) {
        // createIsolated(), deliberately not getInstance(): this extension runs
        // inside somebody else's application, and that application may well use
        // OpenFeature itself. Registering our flagd provider on the global
        // singleton would replace theirs.
        this.api = OpenFeatureAPI.createIsolated();
        Client client = api.getClient();

        TelemetryLevel defaultLevel = TelemetryLevel.of(defaults);
        this.levelFlag = new TelemetryFlag<>(
                levelFlagKey,
                context -> client.getStringDetails(levelFlagKey, defaultLevel.name(), context),
                TelemetryLevel::parse,
                defaultLevel);
        this.enabledFlag = new TelemetryFlag<>(
                enabledFlagKey,
                context -> client.getBooleanDetails(enabledFlagKey, defaults.enabled(), context),
                enabled -> enabled,
                defaults.enabled());

        // Subscribe before registering the provider, so no event can land in
        // the gap. The SDK also replays the current state to a handler as it is
        // added, so subscribing early costs nothing.
        client.onProviderReady(this::reclassify);
        client.onProviderConfigurationChanged(this::reclassify);
        client.onProviderError(this::reclassify);
        client.onProviderStale(this::reclassify);

        // setProvider, not setProviderAndWait: agent startup must not block on
        // flagd being reachable. Until the provider is ready every field is its
        // startup default.
        api.setProvider(new FlagdProvider(options));
    }

    private static FlagdOptions inProcessOptions() {
        String host = env("FLAGD_HOST", DEFAULT_HOST);
        int port = envPort(DEFAULT_SYNC_PORT);
        log.info("otelfeature-java-extension: resolving '" + env("OTELFEATURE_FLAG_NAME", "telemetryLevel")
                + "' and '" + env("OTELFEATURE_ENABLED_FLAG_NAME", "otelEnabled")
                + "' from flagd's in-process sync stream at " + host + ":" + port);
        return FlagdOptions.builder()
                .resolverType(Config.Resolver.IN_PROCESS)
                .host(host)
                .port(port)
                .build();
    }

    private void reclassify(EventDetails details) {
        List<String> flagsChanged = details == null ? null : details.getFlagsChanged();
        reclassify(enabledFlag, flagsChanged);
        reclassify(levelFlag, flagsChanged);
    }

    private static void reclassify(TelemetryFlag<?, ?> flag, List<String> flagsChanged) {
        try {
            flag.classify(flagsChanged);
        } catch (Throwable t) {
            // An exception escaping here would propagate into the SDK's event
            // dispatch; telemetry configuration must never take the host
            // application with it.
            log.log(Level.WARNING, "otelfeature-java-extension: failed to evaluate a telemetry flag", t);
        }
    }

    @Override
    public TelemetryPolicy policy() {
        // The kill switch first: while it is off, telemetryLevel is not asked.
        if (!enabledFlag.value()) {
            return TelemetryPolicy.DISABLED;
        }
        TelemetryLevel level = levelFlag.value();
        return TelemetryPolicy.of(level != TelemetryLevel.OFF, level == TelemetryLevel.IO);
    }

    @Override
    public void shutdown() {
        api.shutdown();
    }

    /**
     * Visible for testing: the kept {@code telemetryLevel} answer, or
     * {@code null} when every span evaluates it.
     *
     * @return the cached level, or {@code null} if there isn't one
     */
    TelemetryLevel cachedLevel() {
        return levelFlag.cachedDecision();
    }

    /**
     * Visible for testing: the kept {@code otelEnabled} answer, or {@code null}
     * when every span evaluates it.
     *
     * @return the cached decision, or {@code null} if there isn't one
     */
    Boolean cachedEnabled() {
        return enabledFlag.cachedDecision();
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

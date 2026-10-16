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
import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.EventDetails;
import dev.openfeature.sdk.OpenFeatureAPI;
import io.opentelemetry.sdk.resources.Resource;
import java.util.List;
import java.util.function.Supplier;
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
 *   <li>{@code record} is {@code recordSpan}, a per-span boolean, asked only
 *       when the two above leave tracing on.</li>
 *   <li>{@code sampleRatio} is {@code traceSampleRatio}, a double clamped into
 *       {@code 0.0}-{@code 1.0}, asked only for a span that is recorded.</li>
 * </ul>
 *
 * <p>{@code OTEL_SDK_DISABLED=true} is a hard floor: the policy is then
 * {@link TelemetryPolicy#DISABLED} whatever the flags say. Flags never enable
 * what {@code OTEL_SDK_DISABLED} turned off.
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
 *   <li>{@code OTELFEATURE_RECORD_SPAN_FLAG_NAME} - per-span flag key (default:
 *       {@code recordSpan})</li>
 *   <li>{@code OTELFEATURE_SAMPLE_RATIO_FLAG_NAME} - sample-ratio flag key
 *       (default: {@code traceSampleRatio})</li>
 * </ul>
 */
public final class FlagdTelemetryPolicy implements TelemetryPolicySource {

    private static final Logger log = Logger.getLogger(FlagdTelemetryPolicy.class.getName());

    private static final String DEFAULT_HOST = "flagd";
    private static final int DEFAULT_SYNC_PORT = 8015;

    private final OpenFeatureAPI api;
    private final TelemetryFlag<String, TelemetryLevel> levelFlag;
    private final TelemetryFlag<Boolean, Boolean> enabledFlag;
    private final TelemetryFlag<Boolean, Boolean> recordFlag;
    private final TelemetryFlag<Double, Double> ratioFlag;

    /**
     * The span-independent policy for each {@link TelemetryPolicy.Need},
     * composed once per reclassification rather than per span (a policy with
     * a sample ratio is not a shared constant); an entry is {@code null} if a
     * flag it needs has to be evaluated per span.
     */
    private volatile Composed composed = Composed.NONE;

    private record Composed(TelemetryPolicy global, TelemetryPolicy span, TelemetryPolicy root) {
        static final Composed NONE = new Composed(null, null, null);

        TelemetryPolicy get(TelemetryPolicy.Need need) {
            return switch (need) {
                case GLOBAL -> global;
                case SPAN -> span;
                case ROOT -> root;
            };
        }
    }
    private final EvaluationContextFactory contexts = new EvaluationContextFactory();

    /**
     * {@code false} when the startup defaults disable the SDK: a hard floor no
     * flag can lift.
     */
    private final boolean floorEnabled;

    public FlagdTelemetryPolicy() {
        this(env("OTELFEATURE_FLAG_NAME", "telemetryLevel"),
                env("OTELFEATURE_ENABLED_FLAG_NAME", "otelEnabled"),
                env("OTELFEATURE_RECORD_SPAN_FLAG_NAME", "recordSpan"),
                env("OTELFEATURE_SAMPLE_RATIO_FLAG_NAME", "traceSampleRatio"),
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
        this(levelFlagKey, enabledFlagKey, "recordSpan", defaults, options);
    }

    /**
     * Visible for testing, with the {@code recordSpan} key as well.
     *
     * @param levelFlagKey the {@code telemetryLevel} flag to resolve
     * @param enabledFlagKey the {@code otelEnabled} flag to resolve
     * @param recordFlagKey the {@code recordSpan} flag to resolve
     * @param defaults the per-field fallback
     * @param options how to reach flagd
     */
    FlagdTelemetryPolicy(String levelFlagKey, String enabledFlagKey, String recordFlagKey,
            TelemetryPolicy defaults, FlagdOptions options) {
        this(levelFlagKey, enabledFlagKey, recordFlagKey, "traceSampleRatio", defaults, options);
    }

    /**
     * Visible for testing, with every flag key.
     *
     * @param levelFlagKey the {@code telemetryLevel} flag to resolve
     * @param enabledFlagKey the {@code otelEnabled} flag to resolve
     * @param recordFlagKey the {@code recordSpan} flag to resolve
     * @param ratioFlagKey the {@code traceSampleRatio} flag to resolve
     * @param defaults the per-field fallback
     * @param options how to reach flagd
     */
    FlagdTelemetryPolicy(String levelFlagKey, String enabledFlagKey, String recordFlagKey,
            String ratioFlagKey, TelemetryPolicy defaults, FlagdOptions options) {
        // createIsolated(), deliberately not getInstance(): this extension runs
        // inside somebody else's application, and that application may well use
        // OpenFeature itself. Registering our flagd provider on the global
        // singleton would replace theirs.
        this.api = OpenFeatureAPI.createIsolated();
        Client client = api.getClient();

        this.floorEnabled = defaults.enabled();
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
        this.recordFlag = new TelemetryFlag<>(
                recordFlagKey,
                context -> client.getBooleanDetails(recordFlagKey, defaults.record(), context),
                record -> record,
                defaults.record());
        this.ratioFlag = new TelemetryFlag<>(
                ratioFlagKey,
                context -> client.getDoubleDetails(ratioFlagKey, defaults.sampleRatio(), context),
                TelemetryPolicy::clampRatio,
                defaults.sampleRatio());

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
                + "', '" + env("OTELFEATURE_ENABLED_FLAG_NAME", "otelEnabled")
                + "', '" + env("OTELFEATURE_RECORD_SPAN_FLAG_NAME", "recordSpan")
                + "' and '" + env("OTELFEATURE_SAMPLE_RATIO_FLAG_NAME", "traceSampleRatio")
                + "' from flagd's in-process sync stream at " + host + ":" + port);
        return FlagdOptions.builder()
                .resolverType(Config.Resolver.IN_PROCESS)
                .host(host)
                .port(port)
                .build();
    }

    // synchronized: two events composing at once could otherwise publish a
    // policy built from the older set of decisions last.
    private synchronized void reclassify(EventDetails details) {
        List<String> flagsChanged = details == null ? null : details.getFlagsChanged();
        reclassify(enabledFlag, flagsChanged);
        reclassify(levelFlag, flagsChanged);
        reclassify(recordFlag, flagsChanged);
        reclassify(ratioFlag, flagsChanged);
        composed = new Composed(compose(TelemetryPolicy.Need.GLOBAL), compose(TelemetryPolicy.Need.SPAN),
                compose(TelemetryPolicy.Need.ROOT));
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
    public TelemetryPolicy cachedPolicy() {
        return cachedPolicy(TelemetryPolicy.Need.ROOT);
    }

    @Override
    public TelemetryPolicy cachedPolicy(TelemetryPolicy.Need need) {
        // OTEL_SDK_DISABLED is a hard floor: flags only ever reduce telemetry,
        // they never enable what the environment turned off.
        if (!floorEnabled) {
            return TelemetryPolicy.DISABLED;
        }
        // One volatile read: composed when the flags were last classified.
        return composed.get(need);
    }

    @Override
    public boolean sampleRatioInPlay() {
        Double ratio = ratioFlag.cachedDecision();
        if (ratio != null) {
            return !ratio.isNaN();
        }
        // Targeted (or failing uncached): it may be set for some traces. Not
        // classified yet (provider not ready): it cannot be - the fallback is
        // "no override".
        return ratioFlag.evaluatedPerSpan();
    }

    /**
     * The span-independent policy for {@code need} from the cached decisions,
     * or {@code null}. Runs on reclassification, not per span.
     */
    private TelemetryPolicy compose(TelemetryPolicy.Need need) {
        Boolean enabled = enabledFlag.cachedDecision();
        if (enabled == null) {
            return null;
        }
        if (!enabled) {
            return TelemetryPolicy.DISABLED;
        }
        TelemetryLevel level = levelFlag.cachedDecision();
        if (level == null) {
            return null;
        }
        if (level == TelemetryLevel.OFF) {
            return TelemetryPolicy.DISABLED;
        }
        if (need == TelemetryPolicy.Need.GLOBAL) {
            return of(level, true, TelemetryPolicy.DELEGATE);
        }
        Boolean record = recordFlag.cachedDecision();
        if (record == null) {
            return null;
        }
        if (!record) {
            return TelemetryPolicy.NOT_RECORDED;
        }
        if (need == TelemetryPolicy.Need.SPAN) {
            return of(level, true, TelemetryPolicy.DELEGATE);
        }
        Double ratio = ratioFlag.cachedDecision();
        return ratio == null ? null : of(level, true, ratio);
    }

    @Override
    public TelemetryPolicy policy(SpanStart span) {
        return policy(span, TelemetryPolicy.Need.ROOT);
    }

    @Override
    public TelemetryPolicy policy(SpanStart span, TelemetryPolicy.Need need) {
        TelemetryPolicy cached = cachedPolicy(need);
        if (cached != null) {
            return cached;
        }
        // At least one flag must be evaluated: build the span's context once,
        // on first use, and share it between the flags.
        LazyContext context = new LazyContext(contexts, span);
        // Precedence: the kill switch, then telemetryLevel, then recordSpan,
        // then traceSampleRatio. Whatever a higher one decides, the lower ones
        // are not asked: they never re-enable what a higher one turned off.
        if (!enabledFlag.value(context)) {
            return TelemetryPolicy.DISABLED;
        }
        TelemetryLevel level = levelFlag.value(context);
        if (level == TelemetryLevel.OFF) {
            return TelemetryPolicy.DISABLED;
        }
        if (need == TelemetryPolicy.Need.GLOBAL) {
            return of(level, true, TelemetryPolicy.DELEGATE);
        }
        if (!recordFlag.value(context)) {
            return TelemetryPolicy.NOT_RECORDED;
        }
        if (need == TelemetryPolicy.Need.SPAN) {
            return of(level, true, TelemetryPolicy.DELEGATE);
        }
        return of(level, true, ratioFlag.value(context));
    }

    private static TelemetryPolicy of(TelemetryLevel level, boolean record, double ratio) {
        return TelemetryPolicy.of(level != TelemetryLevel.OFF, level == TelemetryLevel.IO, record, ratio);
    }

    /**
     * Visible for testing: the kept {@code traceSampleRatio} answer ({@code NaN}
     * for "no override"), or {@code null} when every span evaluates it.
     *
     * @return the cached ratio, or {@code null}
     */
    Double cachedRatio() {
        return ratioFlag.cachedDecision();
    }

    /**
     * Visible for testing: the kept {@code recordSpan} answer, or {@code null}
     * when every span evaluates it.
     *
     * @return the cached decision, or {@code null} if there isn't one
     */
    Boolean cachedRecord() {
        return recordFlag.cachedDecision();
    }

    /**
     * Captures the resource, the process-wide part of every evaluation context.
     *
     * @param resource the SDK's final resource
     */
    void setResource(Resource resource) {
        contexts.setResource(resource);
    }

    /**
     * Publishes the service's HTTP server error rate into the evaluation
     * context ({@code otelfeature.http.server.error_rate}), or removes it.
     *
     * @param rate the last interval's error rate, or {@code null}
     */
    void setErrorRate(Double rate) {
        contexts.setErrorRate(rate);
    }

    /** Builds a span's evaluation context the first time a flag asks for it. */
    private static final class LazyContext implements Supplier<EvaluationContext> {
        private final EvaluationContextFactory factory;
        private final SpanStart span;
        private EvaluationContext built;

        LazyContext(EvaluationContextFactory factory, SpanStart span) {
            this.factory = factory;
            this.span = span;
        }

        @Override
        public EvaluationContext get() {
            if (built == null && span != null) {
                built = factory.build(span);
            }
            return built;
        }
    }

    /** For tests: the factory, to count how many contexts were built. */
    EvaluationContextFactory contexts() {
        return contexts;
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

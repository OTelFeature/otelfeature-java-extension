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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
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
 * <p>With {@code OTELFEATURE_ENFORCEMENT=sampler} a third one joins them:
 * <b>FlagAwareSampler</b> (via {@code addSamplerCustomizer}) drops local roots
 * while tracing is disabled, before the span exists. See {@link Enforcement}.
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

    /**
     * Run after every other customizer provider, so the resource captured for
     * the evaluation context is the final one - a distro or extension that
     * adds {@code service.namespace} after us would otherwise be invisible to
     * targeting rules. (A provider that also claims {@code Integer.MAX_VALUE}
     * may still run after us.)
     */
    @Override
    public int order() {
        return Integer.MAX_VALUE;
    }

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
        Enforcement mode = Enforcement.fromEnvironment();
        Logger.getLogger(OtelfeatureCustomizer.class.getName()).info(
                "otelfeature-java-extension: enforcing at the " + mode.name().toLowerCase()
                        + " (" + Enforcement.ENV + ")");

        // The resource is the process-wide part of every evaluation context.
        autoConfiguration.addResourceCustomizer((resource, config) -> {
            policy.setResource(resource);
            return resource;
        });

        AtomicBoolean samplerInstalled = new AtomicBoolean();
        if (mode == Enforcement.SAMPLER) {
            autoConfiguration.addSamplerCustomizer((sampler, config) -> {
                samplerInstalled.set(true);
                return new FlagAwareSampler(sampler, policy);
            });
        }

        TelemetryPolicySource processorPolicy = processorPolicy(policy, mode, samplerInstalled::get);
        autoConfiguration.addSpanProcessorCustomizer(
                (processor, config) -> new FilteringSpanProcessor(processor, processorPolicy, registry));

        autoConfiguration.addSpanExporterCustomizer(
                (exporter, config) -> new ReparentingSpanExporter(exporter, registry));

        // The provider holds a gRPC channel to flagd; close it on the way out
        // rather than leaving the sync stream to be torn down by the JVM.
        Runtime.getRuntime().addShutdownHook(new Thread(policy::shutdown,
                "otelfeature-shutdown"));
    }

    /**
     * What {@link FilteringSpanProcessor} enforces in each mode.
     *
     * <p>In sampler mode {@code enabled} belongs to {@link FlagAwareSampler}
     * alone: it drops local roots, and a trace that was already sampled when
     * the flag flipped is left to finish whole rather than lose its tail at
     * the processor. The processor keeps {@code suppressInternal} in both modes
     * - and while disabled it keeps suppressing {@code INTERNAL} spans of those
     * in-flight traces, so turning telemetry off never exports more than
     * {@code IO} would.
     *
     * <p>If sampler mode was asked for but the sampler customizer never ran
     * (a host that sets its own sampler after autoconfiguration, say), the
     * processor keeps enforcing {@code enabled} itself, so {@code OFF} still
     * works, and says so once.
     *
     * <p>A policy read that throws fails open to {@link TelemetryPolicy#FULL}
     * rather than throwing into the instrumented application's thread.
     *
     * @param policy the full policy
     * @param mode where {@code enabled} is enforced
     * @param samplerInstalled whether {@link FlagAwareSampler} was actually built
     * @return the policy the processor should apply
     */
    static TelemetryPolicySource processorPolicy(
            TelemetryPolicySource policy, Enforcement mode, BooleanSupplier samplerInstalled) {
        AtomicBoolean warned = new AtomicBoolean();
        java.util.function.UnaryOperator<TelemetryPolicy> view = p -> {
            if (p == null || mode == Enforcement.PROCESSOR) {
                return p;
            }
            if (!samplerInstalled.getAsBoolean()) {
                if (warned.compareAndSet(false, true)) {
                    Logger.getLogger(OtelfeatureCustomizer.class.getName()).warning(
                            "otelfeature-java-extension: " + Enforcement.ENV + "=sampler, but the agent"
                                    + " never applied the sampler customizer - enforcing at the processor");
                }
                return p;
            }
            // recordSpan stays: the sampler drops what it safely can, and the
            // processor suppresses the rest (mid-trace spans that propagate).
            return TelemetryPolicy.of(true, !p.enabled() || p.suppressInternal(), p.record());
        };
        return new TelemetryPolicySource() {
            @Override
            public TelemetryPolicy policy(SpanStart span) {
                try {
                    return view.apply(policy.policy(span));
                } catch (Throwable t) {
                    return TelemetryPolicy.FULL;
                }
            }

            @Override
            public TelemetryPolicy cachedPolicy() {
                try {
                    return view.apply(policy.cachedPolicy());
                } catch (Throwable t) {
                    return TelemetryPolicy.FULL;
                }
            }
        };
    }
}

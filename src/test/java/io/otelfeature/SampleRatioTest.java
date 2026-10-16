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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.sdk.trace.samplers.SamplingDecision;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** {@code traceSampleRatio} in {@link TelemetryPolicy} and {@link FlagAwareSampler}. */
@DisplayName("traceSampleRatio")
class SampleRatioTest {

    private static TelemetryPolicy ratio(double r) {
        return TelemetryPolicy.of(true, false, true, r);
    }

    private static String traceId() {
        return String.format(Locale.ROOT, "%016x%016x",
                ThreadLocalRandom.current().nextLong(), ThreadLocalRandom.current().nextLong());
    }

    private static SamplingDecision root(FlagAwareSampler sampler, String traceId) {
        return sampler.shouldSample(Context.root(), traceId, "GET /weather", SpanKind.SERVER,
                Attributes.empty(), List.of()).getDecision();
    }

    // ----------------------------------------------------------------------
    // The policy field
    // ----------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"0.25,0.25", "0.0,0.0", "1.0,1.0", "5.0,1.0"})
    @DisplayName("ratios above 1.0 are clamped")
    void clamps(double value, double expected) {
        assertEquals(expected, TelemetryPolicy.clampRatio(value));
    }

    @Test
    @DisplayName("non-numbers are no answer: the fallback applies")
    void nonNumbersFallBack() {
        assertNull(TelemetryPolicy.clampRatio(Double.NaN));
        assertNull(TelemetryPolicy.clampRatio(Double.POSITIVE_INFINITY));
        assertNull(TelemetryPolicy.clampRatio(null));
        assertNull(TelemetryPolicy.clampRatio(-1.0), "a negative ratio is a typo, not 'drop everything'");
    }

    @Test
    @DisplayName("a ratio only matters while tracing is on and the span recorded")
    void precedence() {
        assertSame(TelemetryPolicy.DISABLED, TelemetryPolicy.of(false, false, true, 1.0));
        assertSame(TelemetryPolicy.NOT_RECORDED, TelemetryPolicy.of(true, false, false, 1.0));
        assertSame(TelemetryPolicy.FULL, TelemetryPolicy.of(true, false, true, Double.NaN));
        assertTrue(ratio(0.5).overridesSampleRatio());
        assertFalse(TelemetryPolicy.FULL.overridesSampleRatio(), "no ratio by default");
        assertFalse(TelemetryPolicy.startupDefaults(name -> null).overridesSampleRatio(),
                "the agent's own sampler is the floor");
    }

    // ----------------------------------------------------------------------
    // The sampler
    // ----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("ratio 0.0 drops new traces, in both enforcement modes")
    void zeroDropsRoots(boolean enforce) {
        FlagAwareSampler sampler = new FlagAwareSampler(Sampler.alwaysOn(), span -> ratio(0.0), enforce);

        assertEquals(SamplingDecision.DROP, root(sampler, traceId()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("ratio 1.0 samples every new trace, overriding the agent's sampler")
    void oneOverridesTheDelegate(boolean enforce) {
        FlagAwareSampler sampler = new FlagAwareSampler(
                Sampler.traceIdRatioBased(0.0001), span -> ratio(1.0), enforce);

        assertEquals(SamplingDecision.RECORD_AND_SAMPLE, root(sampler, traceId()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("OTEL_TRACES_SAMPLER=always_off is a hard floor: ratio 1.0 samples nothing")
    void alwaysOffIsAHardFloor(boolean enforce) {
        FlagAwareSampler sampler = new FlagAwareSampler(Sampler.alwaysOff(), span -> ratio(1.0), enforce);

        assertEquals(SamplingDecision.DROP, root(sampler, traceId()));
    }

    @Test
    @DisplayName("always_off configured but wrapped (e.g. by another extension): still a hard floor")
    void configuredAlwaysOffIsAHardFloor() {
        Sampler wrapped = new Sampler() {
            @Override
            public io.opentelemetry.sdk.trace.samplers.SamplingResult shouldSample(Context parentContext,
                    String traceId, String name, SpanKind spanKind, Attributes attributes,
                    List<io.opentelemetry.sdk.trace.data.LinkData> parentLinks) {
                return io.opentelemetry.sdk.trace.samplers.SamplingResult.drop();
            }

            @Override
            public String getDescription() {
                return "SomeWrapper{AlwaysOffSampler}";
            }
        };
        FlagAwareSampler sampler = new FlagAwareSampler(wrapped, span -> ratio(1.0), true, true);

        assertEquals(SamplingDecision.DROP, root(sampler, traceId()));
    }

    @Test
    @DisplayName("always_off: a sampled upstream parent doesn't cause sampling either, ratio in play or not")
    void alwaysOffIgnoresSampledParents() {
        TelemetryPolicySource ratioInPlay = new TelemetryPolicySource() {
            @Override
            public TelemetryPolicy policy(SpanStart span) {
                return ratio(1.0);
            }

            @Override
            public boolean sampleRatioInPlay() {
                return true;
            }
        };
        FlagAwareSampler sampler = new FlagAwareSampler(Sampler.alwaysOff(), ratioInPlay);
        SpanContext upstream = SpanContext.createFromRemoteParent(
                "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331",
                TraceFlags.getSampled(), TraceState.getDefault());

        assertEquals(SamplingDecision.DROP, sampler.shouldSample(
                Context.root().with(Span.wrap(upstream)), upstream.getTraceId(), "GET /weather",
                SpanKind.SERVER, Attributes.empty(), List.of()).getDecision());
    }

    @ParameterizedTest
    @CsvSource({"always_on", "traceidratio"})
    @DisplayName("while a ratio is in play, children follow their root even with a non-parent-based delegate")
    void childrenFollowTheRootWithAnyDelegate(String delegateName) {
        Sampler delegate = "always_on".equals(delegateName)
                ? Sampler.alwaysOn() : Sampler.traceIdRatioBased(0.1);
        double[] current = {1.0};
        TelemetryPolicySource source = new TelemetryPolicySource() {
            @Override
            public TelemetryPolicy policy(SpanStart span) {
                return ratio(current[0]);
            }

            @Override
            public boolean sampleRatioInPlay() {
                return true;
            }
        };
        FilteringSpanProcessorTest.CountingProcessor counting = new FilteringSpanProcessorTest.CountingProcessor();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setSampler(new FlagAwareSampler(delegate, source))
                .addSpanProcessor(counting)
                .build();

        // Ratio 1.0: the root is sampled, so all 20 children must be too.
        Span root = provider.get("test").spanBuilder("root").startSpan();
        try (var s = root.makeCurrent()) {
            for (int i = 0; i < 20; i++) {
                provider.get("test").spanBuilder("child-" + i).startSpan().end();
            }
        }
        root.end();
        assertEquals(21, counting.onStartCount.get(), "no child may be re-decided by " + delegateName);

        // Ratio 0.0: the root is dropped, so no child may be sampled as an orphan.
        current[0] = 0.0;
        Span dropped = provider.get("test").spanBuilder("dropped").startSpan();
        try (var s = dropped.makeCurrent()) {
            for (int i = 0; i < 20; i++) {
                provider.get("test").spanBuilder("orphan-" + i).startSpan().end();
            }
        }
        dropped.end();
        provider.shutdown();
        assertEquals(21, counting.onStartCount.get(), "no orphans under " + delegateName);
    }

    @Test
    @DisplayName("the inline ratio decision matches the SDK's traceidratio sampler exactly")
    void inlineDecisionMatchesTheSdk() {
        for (double r : new double[] {0.0, 0.01, 0.1, 0.33, 0.5, 0.9, 1.0}) {
            Sampler sdk = Sampler.traceIdRatioBased(r);
            for (int i = 0; i < 500; i++) {
                String trace = traceId();
                boolean expected = sdk.shouldSample(Context.root(), trace, "x", SpanKind.SERVER,
                        Attributes.empty(), List.of()).getDecision() == SamplingDecision.RECORD_AND_SAMPLE;
                assertEquals(expected, FlagAwareSampler.sampledByRatio(trace, r), "ratio " + r + ", " + trace);
            }
        }
    }

    @Test
    @DisplayName("no ratio set: the agent's sampler decides")
    void noRatioDelegates() {
        FlagAwareSampler sampler = new FlagAwareSampler(Sampler.alwaysOff(), span -> TelemetryPolicy.FULL);

        assertEquals(SamplingDecision.DROP, root(sampler, traceId()));
    }

    @Test
    @DisplayName("two services with the same ratio make the same call for the same trace")
    void consistentAcrossServices() {
        FlagAwareSampler weather = new FlagAwareSampler(Sampler.alwaysOn(), span -> ratio(0.5));
        FlagAwareSampler geocode =
                new FlagAwareSampler(Sampler.traceIdRatioBased(0.01), span -> ratio(0.5), false);

        int sampled = 0;
        for (int i = 0; i < 400; i++) {
            String trace = traceId();
            SamplingDecision decision = root(weather, trace);
            assertEquals(decision, root(geocode, trace), "trace " + trace + " decided differently");
            if (decision == SamplingDecision.RECORD_AND_SAMPLE) {
                sampled++;
            }
        }
        assertTrue(sampled > 140 && sampled < 260, "0.5 sampled " + sampled + " of 400");
    }

    @Test
    @DisplayName("parent-based: a trace sampled at the root keeps its children, whatever the ratio is now")
    void childrenFollowTheirRoot() {
        FilteringSpanProcessorTest.TestPolicy policy = new FilteringSpanProcessorTest.TestPolicy();
        policy.setPolicy(ratio(1.0));
        FilteringSpanProcessorTest.CountingProcessor counting = new FilteringSpanProcessorTest.CountingProcessor();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .setSampler(new FlagAwareSampler(Sampler.parentBased(Sampler.alwaysOn()), policy))
                .addSpanProcessor(counting)
                .build();

        Span root = provider.get("test").spanBuilder("root").startSpan();
        policy.setPolicy(ratio(0.0));
        try (var s = root.makeCurrent()) {
            provider.get("test").spanBuilder("child").startSpan().end();
        }
        root.end();
        provider.get("test").spanBuilder("next root").startSpan().end();
        provider.shutdown();

        assertEquals(2, counting.onStartCount.get(), "root and child; the next trace is at 0.0");
    }

    @Test
    @DisplayName("a request arriving with a sampled traceparent is not re-sampled")
    void remoteParentIsNotResampled() {
        FlagAwareSampler sampler =
                new FlagAwareSampler(Sampler.parentBased(Sampler.alwaysOn()), span -> ratio(0.0));
        SpanContext upstream = SpanContext.createFromRemoteParent(
                "0af7651916cd43dd8448eb211c80319c", "b7ad6b7169203331",
                TraceFlags.getSampled(), TraceState.getDefault());

        assertEquals(SamplingDecision.RECORD_AND_SAMPLE, sampler.shouldSample(
                Context.root().with(Span.wrap(upstream)), upstream.getTraceId(), "GET /weather",
                SpanKind.SERVER, Attributes.empty(), List.of()).getDecision(),
                "the entry point decided; flip the ratio there");
    }

    @Test
    @DisplayName("processor mode: the sampler applies the ratio but never drops for OFF")
    void processorModeOnlyAppliesTheRatio() {
        FlagAwareSampler sampler =
                new FlagAwareSampler(Sampler.alwaysOn(), span -> TelemetryPolicy.DISABLED, false);

        assertEquals(SamplingDecision.RECORD_AND_SAMPLE, root(sampler, traceId()),
                "OFF is the processor's job in processor mode");
    }
}

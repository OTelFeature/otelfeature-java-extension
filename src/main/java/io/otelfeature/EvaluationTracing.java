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

import dev.openfeature.contrib.hooks.otel.TracesHook;
import dev.openfeature.sdk.FlagEvaluationDetails;
import dev.openfeature.sdk.Hook;
import dev.openfeature.sdk.HookContext;
import java.util.Locale;
import java.util.Map;

/**
 * The opt-in OpenFeature OpenTelemetry traces hook for the extension's own
 * flag evaluations ({@code OTELFEATURE_OPENFEATURE_TRACING_HOOK=true}, off by
 * default; UC-007 BR-002 to BR-004).
 *
 * <p>{@link FlagdTelemetryPolicy} adds it to the client of its isolated
 * {@code OpenFeatureAPI}, so the application's own OpenFeature never sees it.
 * What it records, and where, is the hook's business: an event per
 * evaluation that is not served from the cache, on the span current at the
 * time - the parent of the span being decided, so a new root records nothing.
 */
final class EvaluationTracing {

    static final String ENV = "OTELFEATURE_OPENFEATURE_TRACING_HOOK";

    private EvaluationTracing() {}

    /**
     * Whether a value of {@link #ENV} switches the hook on: only {@code true},
     * ignoring case and surrounding whitespace, as in the Python extension.
     *
     * @param value the variable's value, or {@code null} when unset
     * @return {@code true} only for {@code true}
     */
    static boolean enabled(String value) {
        return value != null && "true".equals(value.trim().toLowerCase(Locale.ROOT));
    }

    /** @return whether the environment asks for the hook */
    static boolean fromEnvironment() {
        return enabled(System.getenv(ENV));
    }

    /** @return a new hook for the extension's client */
    static Hook<?> hook() {
        return new ExtensionTracesHook();
    }

    /**
     * {@code dev.openfeature.contrib.hooks:otel}'s {@link TracesHook}, minus
     * exceptions: it has no option to leave them out, and a flagd outage would
     * otherwise put one on an application span per evaluation.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static final class ExtensionTracesHook extends TracesHook {

        @Override
        public void after(HookContext ctx, FlagEvaluationDetails details, Map hints) {
            try {
                super.after(ctx, details, hints);
            } catch (RuntimeException e) {
                // The SDK turns an exception from an after hook into an error
                // and serves the default: never let a debugging aid change a
                // telemetry decision.
            }
        }

        @Override
        public void error(HookContext ctx, Exception error, Map hints) {
            // Not recorded (UC-007 BR-002).
        }
    }
}

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

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;

/**
 * What is known about a span at the moment a policy decision is made for it.
 *
 * <p>Only the fields are captured here; turning them into an evaluation
 * context is {@link EvaluationContextFactory}'s job, and happens only when a
 * flag actually has to be evaluated.
 *
 * @param traceId the span's trace id (hex), the {@code targetingKey}
 * @param name the span name
 * @param kind the span kind
 * @param attributes the attributes set at span start
 * @param scope the instrumentation scope, or {@code null} where it is not
 *     known - a {@code Sampler} is not told the scope
 */
public record SpanStart(
        String traceId, String name, SpanKind kind, Attributes attributes, InstrumentationScopeInfo scope) {}

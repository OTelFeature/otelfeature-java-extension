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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.Value;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("EvaluationContextFactory: the context contract")
class EvaluationContextFactoryTest {

    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";

    /** Follows a dotted path through the nested context, the way JsonLogic's var does. */
    static Value at(EvaluationContext context, String path) {
        String[] segments = path.split("\\.");
        Value value = context.getValue(segments[0]);
        for (int i = 1; i < segments.length && value != null; i++) {
            value = value.isStructure() ? value.asStructure().getValue(segments[i]) : null;
        }
        return value;
    }

    private static EvaluationContextFactory factory() {
        EvaluationContextFactory factory = new EvaluationContextFactory();
        factory.setResource(Resource.create(Attributes.builder()
                .put("service.name", "weather")
                .put("service.namespace", "demo")
                .put("service.version", "1.2.3")
                .put("deployment.environment.name", "prod")
                .put("telemetry.sdk.language", "java")
                .build()));
        return factory;
    }

    private static SpanStart serverSpan(Attributes attributes) {
        return new SpanStart(TRACE_ID, "GET /health", SpanKind.SERVER, attributes,
                InstrumentationScopeInfo.builder("io.opentelemetry.tomcat-10.0").setVersion("2.10.0").build());
    }

    @Test
    @DisplayName("resource, scope and span keys are nested by dot segment")
    void everyKeyIsNested() {
        EvaluationContext context = factory().build(serverSpan(Attributes.builder()
                .put("url.path", "/health")
                .put("http.request.method", "GET")
                .put("server.address", "weather.local")
                .build()));

        assertEquals("weather", at(context, "service.name").asString());
        assertEquals("demo", at(context, "service.namespace").asString());
        assertEquals("1.2.3", at(context, "service.version").asString());
        assertEquals("prod", at(context, "deployment.environment.name").asString());
        assertEquals("java", at(context, "telemetry.sdk.language").asString());
        assertEquals("io.opentelemetry.tomcat-10.0", at(context, "otel.scope.name").asString());
        assertEquals("2.10.0", at(context, "otel.scope.version").asString());
        assertEquals("server", at(context, "span.kind").asString());
        assertEquals("GET /health", at(context, "span.name").asString());
        assertEquals("/health", at(context, "url.path").asString());
        assertEquals("GET", at(context, "http.request.method").asString());
        assertEquals("weather.local", at(context, "server.address").asString());
        assertNull(context.getValue("service.name"), "the flat dotted key is never sent");
    }

    @Test
    @DisplayName("targetingKey is the trace id")
    void targetingKeyIsTheTraceId() {
        assertEquals(TRACE_ID, factory().build(serverSpan(Attributes.empty())).getTargetingKey());
    }

    @Test
    @DisplayName("span.kind is the lower-case semconv value for every kind")
    void spanKindIsLowerCase() {
        for (SpanKind kind : SpanKind.values()) {
            EvaluationContext context = factory().build(
                    new SpanStart(TRACE_ID, "x", kind, Attributes.empty(), null));
            assertEquals(kind.name().toLowerCase(java.util.Locale.ROOT), at(context, "span.kind").asString());
        }
    }

    @Test
    @DisplayName("a sampler decision has no scope keys")
    void noScopeNoScopeKeys() {
        EvaluationContext context = factory().build(
                new SpanStart(TRACE_ID, "x", SpanKind.SERVER, Attributes.empty(), null));

        assertNull(context.getValue("otel"));
    }

    @Test
    @DisplayName("values keep their type; arrays pass through as lists")
    void valuesKeepTheirType() {
        EvaluationContext context = factory().build(serverSpan(Attributes.builder()
                .put("server.port", 8080L)
                .put("sample.ratio", 0.25)
                .put("tls.enabled", true)
                .put(AttributeKey.stringArrayKey("http.request.header.accept"), List.of("a", "b"))
                .build()));

        assertEquals(8080L, at(context, "server.port").asObject());
        assertEquals(0.25, at(context, "sample.ratio").asDouble());
        assertTrue(at(context, "tls.enabled").asBoolean());
        assertEquals(List.of("a", "b"),
                at(context, "http.request.header.accept").asList().stream().map(Value::asString).toList());
    }

    @Test
    @DisplayName("collision: the namespace wins over the leaf, in either order")
    void namespaceWinsInEitherOrder() {
        Map<String, Object> leafFirst = new LinkedHashMap<>();
        EvaluationContextFactory.insert(leafFirst, "db.system", "postgresql");
        EvaluationContextFactory.insert(leafFirst, "db.system.name", "postgresql");

        Map<String, Object> namespaceFirst = new LinkedHashMap<>();
        EvaluationContextFactory.insert(namespaceFirst, "db.system.name", "postgresql");
        EvaluationContextFactory.insert(namespaceFirst, "db.system", "postgresql");

        Map<String, Object> expected = Map.of("db", Map.of("system", Map.of("name", "postgresql")));
        assertEquals(expected, leafFirst);
        assertEquals(expected, namespaceFirst);
    }

    @Test
    @DisplayName("our own span keys win over a start attribute of the same name")
    void ownKeysWinOverAttributes() {
        EvaluationContext context = factory().build(serverSpan(Attributes.of(
                AttributeKey.stringKey("span.kind"), "bogus")));

        assertEquals("server", at(context, "span.kind").asString());
    }

    @Test
    @DisplayName("before the resource is known, the span part still works")
    void noResourceYet() {
        EvaluationContext context = new EvaluationContextFactory().build(serverSpan(Attributes.empty()));

        assertNull(context.getValue("service"));
        assertEquals("server", at(context, "span.kind").asString());
    }

    @Test
    @DisplayName("a start attribute loses to a resource attribute of the same name (parity with Python)")
    void resourceWinsOverStartAttribute() {
        EvaluationContext context = factory().build(serverSpan(Attributes.of(
                AttributeKey.stringKey("service.name"), "spoofed")));

        assertEquals("weather", at(context, "service.name").asString());
    }

    @Test
    @DisplayName("a namespace shared with the resource keeps both sides")
    void sharedNamespaceKeepsBoth() {
        EvaluationContext context = factory().build(serverSpan(Attributes.of(
                AttributeKey.stringKey("service.instance.id"), "pod-7")));

        assertEquals("pod-7", at(context, "service.instance.id").asString());
        assertEquals("weather", at(context, "service.name").asString());
        assertEquals("demo", at(context, "service.namespace").asString());
    }

    @Test
    @DisplayName("collision across sources: the namespace wins, whichever side holds the leaf")
    void namespaceWinsAcrossSources() {
        EvaluationContextFactory leafInResource = new EvaluationContextFactory();
        leafInResource.setResource(Resource.create(Attributes.of(
                AttributeKey.stringKey("deployment.environment"), "prod")));
        EvaluationContext first = leafInResource.build(serverSpan(Attributes.of(
                AttributeKey.stringKey("deployment.environment.name"), "staging")));
        assertEquals("staging", at(first, "deployment.environment.name").asString());

        EvaluationContextFactory namespaceInResource = new EvaluationContextFactory();
        namespaceInResource.setResource(Resource.create(Attributes.of(
                AttributeKey.stringKey("deployment.environment.name"), "prod")));
        EvaluationContext second = namespaceInResource.build(serverSpan(Attributes.of(
                AttributeKey.stringKey("deployment.environment"), "legacy")));
        assertEquals("prod", at(second, "deployment.environment.name").asString());
    }

    @Test
    @DisplayName("edge keys: empty segments collapse, nameless keys are left out")
    void edgeKeys() {
        Map<String, Object> tree = new LinkedHashMap<>();
        EvaluationContextFactory.insert(tree, ".a", "1");
        EvaluationContextFactory.insert(tree, "b.", "2");
        EvaluationContextFactory.insert(tree, "c..d", "3");
        EvaluationContextFactory.insert(tree, "", "4");
        EvaluationContextFactory.insert(tree, ".", "5");

        assertEquals(Map.of("a", "1", "b", "2", "c", Map.of("d", "3")), tree);
    }

    @Test
    @DisplayName("the shared resource structures cannot be modified through a context")
    void sharedStructuresAreSealed() {
        EvaluationContextFactory factory = factory();
        EvaluationContext context = factory.build(serverSpan(Attributes.empty()));

        Value service = context.getValue("service");
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> ((dev.openfeature.sdk.MutableStructure) service.asStructure()).add("name", "hijacked"));
        assertEquals("weather", at(factory.build(serverSpan(Attributes.empty())), "service.name").asString());
    }

    @Test
    @DisplayName("the error rate is in the context while published, and gone once withdrawn")
    void errorRateKey() {
        EvaluationContextFactory factory = factory();
        assertNull(factory.build(serverSpan(Attributes.empty())).getValue("otelfeature"));

        factory.setErrorRate(0.07);
        assertEquals(0.07, at(factory.build(serverSpan(Attributes.empty())),
                "otelfeature.http.server.error_rate").asDouble());

        factory.setErrorRate(null);
        assertNull(factory.build(serverSpan(Attributes.empty())).getValue("otelfeature"));
    }
}

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

import dev.openfeature.sdk.EvaluationContext;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.MutableStructure;
import dev.openfeature.sdk.Value;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds the flag evaluation context for a span out of OpenTelemetry's own
 * names: the resource, the instrumentation scope, and the span as it starts.
 *
 * <table border="1">
 *   <caption>The context contract</caption>
 *   <tr><th>source</th><th>keys</th></tr>
 *   <tr><td>resource (once per process)</td><td>every resource attribute -
 *       {@code service.name}, {@code service.namespace}, {@code service.version},
 *       {@code deployment.environment.name}, {@code telemetry.sdk.language}, ...</td></tr>
 *   <tr><td>instrumentation scope</td><td>{@code otel.scope.name},
 *       {@code otel.scope.version} (not in sampler decisions: a sampler is
 *       not told the scope)</td></tr>
 *   <tr><td>span at start</td><td>{@code span.kind} (lower-case semconv value),
 *       {@code span.name}, and every attribute set at start - {@code url.path},
 *       {@code http.request.method}, {@code server.address},
 *       {@code db.system.name}, {@code db.operation.name}, {@code rpc.service}, ...</td></tr>
 *   <tr><td>the service's HTTP metrics</td><td>{@code otelfeature.http.server.error_rate}
 *       (see {@link ErrorRateReader}), when the last interval had enough requests</td></tr>
 *   <tr><td>{@code targetingKey}</td><td>the trace id (hex), so flagd's
 *       {@code fractional} buckets a whole trace the same way in every service</td></tr>
 * </table>
 *
 * <p><b>Shape</b> (OTelFeature/poc#22): every key is nested by dot segment -
 * {@code service.name} is sent as {@code {"service": {"name": ...}}} - because
 * JsonLogic's {@code var} splits on dots in every flagd evaluator, so a rule
 * author writes {@code {"var": "service.name"}} verbatim. Only the nested form is
 * sent. When a name is both a leaf and a namespace (a deprecated name next to
 * its replacement, such as {@code db.system} and {@code db.system.name}), the
 * namespace wins and the leaf is dropped, whatever order they arrive in. For
 * the same leaf from two sources, the extension's own keys win over a span
 * start attribute of the same name (the same rule as the Python extension):
 * start attributes, then resource, then scope, then
 * {@code span.kind}/{@code span.name}.
 *
 * <p>Empty dot segments collapse ({@code .a} and {@code a.} are {@code a},
 * {@code a..b} is {@code a.b}); a key with no segment at all ({@code ""},
 * {@code "."}) is left out. Both are logged at FINE.
 *
 * <p>The resource part is built once, when the resource is known; the per-span
 * part only when a flag is not static and actually has to be evaluated.
 */
final class EvaluationContextFactory {

    private static final Logger log = Logger.getLogger(EvaluationContextFactory.class.getName());

    /**
     * The resource part, prepared once: its finished, immutable top-level
     * values, and its raw pairs grouped by top-level segment for the rare span
     * key that shares a namespace with it.
     */
    private record Base(Map<String, Value> values, Map<String, List<Map.Entry<String, Object>>> byTop) {
        static final Base EMPTY = new Base(Map.of(), Map.of());
    }

    private volatile Base base = Base.EMPTY;

    /** The context key the service's HTTP error rate is published under. */
    static final String ERROR_RATE_KEY = "otelfeature.http.server.error_rate";

    /** The last interval's HTTP server error rate, or {@code null} for none. */
    private volatile Double errorRate;

    /**
     * Publishes the service's HTTP server error rate into every context built
     * from now on, or removes it ({@code null}).
     *
     * @param rate errors / requests over the last interval, or {@code null}
     */
    void setErrorRate(Double rate) {
        this.errorRate = rate;
    }

    /** How many contexts have been built. For tests; uncontended on the span path. */
    private final LongAdder builds = new LongAdder();

    /**
     * Captures the process-wide part of the context.
     *
     * @param resource the SDK's final resource
     */
    void setResource(Resource resource) {
        Map<String, Object> tree = new LinkedHashMap<>();
        Map<String, List<Map.Entry<String, Object>>> byTop = new LinkedHashMap<>();
        resource.getAttributes().forEach((key, value) -> {
            insert(tree, key.getKey(), value);
            String top = topSegment(key.getKey());
            if (top != null) {
                byTop.computeIfAbsent(top, k -> new ArrayList<>()).add(Map.entry(key.getKey(), value));
            }
        });
        this.base = new Base(Collections.unmodifiableMap(toValues(tree)), Map.copyOf(byTop));
    }

    /**
     * Builds the context for one span.
     *
     * <p>Only the span's own part is built here; the resource part is shared,
     * already finished, between all contexts. A namespace the span's keys share
     * with the resource (say a {@code service.*} start attribute) is rebuilt
     * with both, and the resource's values win.
     *
     * @param span the span as it starts
     * @return a nested evaluation context keyed by the trace id
     */
    EvaluationContext build(SpanStart span) {
        builds.increment();
        Base b = base;
        Map<String, Object> tree = new LinkedHashMap<>();

        // 1. Start attributes: the lowest precedence.
        Attributes attributes = span.attributes();
        if (attributes != null) {
            attributes.forEach((key, value) -> insert(tree, key.getKey(), value));
        }

        // 2. The resource, wherever the span shares a namespace with it.
        //    Elsewhere the shared, pre-built resource values are used as-is.
        if (!b.byTop().isEmpty()) {
            for (String top : sharedTops(tree, b)) {
                for (Map.Entry<String, Object> pair : b.byTop().get(top)) {
                    insert(tree, pair.getKey(), pair.getValue());
                }
            }
        }

        // 3. Scope and span keys: the extension's own, highest precedence.
        InstrumentationScopeInfo scope = span.scope();
        if (scope != null) {
            insert(tree, "otel.scope.name", scope.getName());
            if (scope.getVersion() != null) {
                insert(tree, "otel.scope.version", scope.getVersion());
            }
        }
        if (span.kind() != null) {
            insert(tree, "span.kind", span.kind().name().toLowerCase(Locale.ROOT));
        }
        if (span.name() != null) {
            insert(tree, "span.name", span.name());
        }
        Double rate = errorRate;
        if (rate != null) {
            insert(tree, ERROR_RATE_KEY, rate);
        }

        Map<String, Value> values = new HashMap<>(b.values());
        values.putAll(toValues(tree));
        return new MutableContext(span.traceId(), values);
    }

    /** The resource namespaces this span's keys will touch: its start attributes', and ours. */
    private static List<String> sharedTops(Map<String, Object> tree, Base b) {
        List<String> tops = new ArrayList<>(2);
        for (String top : tree.keySet()) {
            if (b.byTop().containsKey(top)) {
                tops.add(top);
            }
        }
        for (String own : OWN_TOPS) {
            if (b.byTop().containsKey(own) && !tree.containsKey(own)) {
                tops.add(own);
            }
        }
        return tops;
    }

    /** Top-level namespaces of the keys this extension adds itself. */
    private static final List<String> OWN_TOPS = List.of("otel", "span");

    /** The first non-empty dot segment of a key, or {@code null} if there is none. */
    private static String topSegment(String key) {
        int start = 0;
        while (start < key.length()) {
            int dot = key.indexOf('.', start);
            int end = dot < 0 ? key.length() : dot;
            if (end > start) {
                return key.substring(start, end);
            }
            start = end + 1;
        }
        return null;
    }

    /**
     * Inserts one dotted key into the nested tree. The namespace wins over a
     * leaf of the same name, in either order.
     */
    @SuppressWarnings("unchecked")
    static void insert(Map<String, Object> tree, String key, Object value) {
        Object converted = convert(value);
        if (converted == null) {
            return;
        }
        // Walk the key segment by segment with indexOf: no split, no regex,
        // no arrays - this runs for every key of every evaluated span.
        Map<String, Object> node = tree;
        String pending = null;
        int start = 0;
        int length = key.length();
        while (start <= length) {
            int dot = key.indexOf('.', start);
            int end = dot < 0 ? length : dot;
            if (end > start) {
                if (pending != null) {
                    Object child = node.get(pending);
                    if (!(child instanceof Map)) {
                        if (child != null) {
                            dropped(key.substring(0, start - 1), key);
                        }
                        child = new LinkedHashMap<String, Object>();
                        node.put(pending, child);
                    }
                    node = (Map<String, Object>) child;
                }
                pending = key.substring(start, end);
            }
            if (dot < 0) {
                break;
            }
            start = dot + 1;
        }
        if (pending == null) {
            if (log.isLoggable(Level.FINE)) {
                log.fine("otelfeature-java-extension: attribute key '" + key
                        + "' has no name segment - left out of the context");
            }
            return;
        }
        if (node.get(pending) instanceof Map) {
            dropped(key, key + ".*");
            return;
        }
        node.put(pending, converted);
    }

    private static void dropped(String leaf, String namespace) {
        if (log.isLoggable(Level.FINE)) {
            log.fine("otelfeature-java-extension: '" + leaf + "' is both a value and a namespace ("
                    + namespace + ") - the namespace wins, the value is left out of the context");
        }
    }

    /** OTel attribute values to plain values a {@link Value} can hold. */
    private static Object convert(Object value) {
        if (value instanceof String || value instanceof Boolean || value instanceof Long
                || value instanceof Double) {
            return value;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                Object c = convert(item);
                if (c != null) {
                    out.add(c);
                }
            }
            return Collections.unmodifiableList(out);
        }
        return value == null ? null : value.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Value> toValues(Map<String, Object> tree) {
        Map<String, Value> out = new LinkedHashMap<>(tree.size() * 2);
        for (Map.Entry<String, Object> entry : tree.entrySet()) {
            out.put(entry.getKey(), toValue(entry.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Value toValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            // MutableStructure keeps the map it is given; ImmutableStructure
            // would deep-copy it again at every level of nesting. The map is
            // unmodifiable, so a hook that casts and calls add() fails instead
            // of changing the resource part every later context shares.
            return new Value(new MutableStructure(
                    Collections.unmodifiableMap(toValues((Map<String, Object>) map))));
        }
        if (value instanceof String s) {
            return new Value(s);
        }
        if (value instanceof Boolean b) {
            return new Value(b);
        }
        if (value instanceof Long l) {
            return new Value(l);
        }
        if (value instanceof Double d) {
            return new Value(d);
        }
        if (value instanceof List<?> list) {
            List<Value> values = new ArrayList<>(list.size());
            for (Object item : list) {
                values.add(toValue(item));
            }
            return new Value(values);
        }
        return new Value(String.valueOf(value));
    }

    /** For tests: how many contexts have been built so far. */
    long builds() {
        return builds.sum();
    }
}

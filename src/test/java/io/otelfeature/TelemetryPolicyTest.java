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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.trace.SpanKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@DisplayName("TelemetryPolicy")
class TelemetryPolicyTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty("otel.sdk.disabled");
    }

    @ParameterizedTest
    @EnumSource(SpanKind.class)
    @DisplayName("each policy drops exactly the kinds it should")
    void policiesSuppressTheRightKinds(SpanKind kind) {
        assertFalse(TelemetryPolicy.FULL.suppresses(kind));
        assertEquals(kind == SpanKind.INTERNAL, TelemetryPolicy.IO.suppresses(kind));
        assertTrue(TelemetryPolicy.DISABLED.suppresses(kind));
    }

    @Test
    @DisplayName("policies come from a fixed set of shared instances")
    void policiesAreShared() {
        assertSame(TelemetryPolicy.FULL, TelemetryPolicy.of(true, false));
        assertSame(TelemetryPolicy.IO, TelemetryPolicy.of(true, true));
        assertSame(TelemetryPolicy.DISABLED, TelemetryPolicy.of(false, true));
        assertSame(TelemetryPolicy.DISABLED, TelemetryPolicy.of(false, false));
    }

    @Test
    @DisplayName("startup defaults are the stock agent's behaviour")
    void startupDefaultsAreStock() {
        assertEquals(TelemetryPolicy.FULL, TelemetryPolicy.startupDefaults(name -> null));
        assertEquals(TelemetryPolicy.FULL, TelemetryPolicy.startupDefaults(name -> "false"));
    }

    @Test
    @DisplayName("OTEL_SDK_DISABLED=true makes 'disabled' the floor")
    void sdkDisabledIsTheFloor() {
        assertEquals(TelemetryPolicy.DISABLED, TelemetryPolicy.startupDefaults(
                name -> "OTEL_SDK_DISABLED".equals(name) ? "true" : null));
    }

    @Test
    @DisplayName("-Dotel.sdk.disabled=true is read as well")
    void systemPropertyIsRead() {
        System.setProperty("otel.sdk.disabled", "true");
        assertEquals(TelemetryPolicy.DISABLED, TelemetryPolicy.startupDefaults());
    }

    @Test
    @DisplayName("the telemetryLevel fallback follows every field of the defaults")
    void levelFallbackFollowsDefaults() {
        assertEquals(TelemetryLevel.FULL, TelemetryLevel.of(TelemetryPolicy.FULL));
        assertEquals(TelemetryLevel.IO, TelemetryLevel.of(TelemetryPolicy.IO));
        assertEquals(TelemetryLevel.OFF, TelemetryLevel.of(TelemetryPolicy.DISABLED));
    }

    @ParameterizedTest
    @EnumSource(SpanKind.class)
    @DisplayName("record=false drops this span, whatever its kind")
    void notRecordedDropsEveryKind(SpanKind kind) {
        assertTrue(TelemetryPolicy.NOT_RECORDED.suppresses(kind));
    }

    @Test
    @DisplayName("precedence: disabled beats record=false, record=false beats IO")
    void recordPrecedence() {
        assertSame(TelemetryPolicy.DISABLED, TelemetryPolicy.of(false, false, true));
        assertSame(TelemetryPolicy.DISABLED, TelemetryPolicy.of(false, true, false));
        assertSame(TelemetryPolicy.NOT_RECORDED, TelemetryPolicy.of(true, true, false));
        assertSame(TelemetryPolicy.NOT_RECORDED, TelemetryPolicy.of(true, false, false));
        assertSame(TelemetryPolicy.IO, TelemetryPolicy.of(true, true, true));
        assertTrue(TelemetryPolicy.startupDefaults(name -> null).record(), "the stock agent records");
    }
}

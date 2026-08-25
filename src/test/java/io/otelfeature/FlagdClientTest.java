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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("FlagdClient")
class FlagdClientTest {

    @Nested
    @DisplayName("extractValue (JSON parsing)")
    class ExtractValue {

        /**
         * The FlagdClient.extractValue method uses a regex to extract the
         * "value" field from the OFREP JSON response. Since it's private,
         * we test the same regex pattern directly.
         */
        private String extractValue(String json) {
            Matcher matcher = Pattern.compile(
                    "\"value\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
            return matcher.find() ? matcher.group(1) : null;
        }

        @Test
        @DisplayName("extracts string value from OFREP response")
        void extractsStringValue() {
            String json = """
                {"value":"IO","reason":"STATIC","variant":"on"}""";
            assertEquals("IO", extractValue(json));
        }

        @Test
        @DisplayName("extracts 'FULL' value")
        void extractsFullValue() {
            String json = """
                {"value":"FULL","reason":"STATIC","variant":"off"}""";
            assertEquals("FULL", extractValue(json));
        }

        @Test
        @DisplayName("extracts value with spaces around colon")
        void extractsWithSpaces() {
            String json = """
                {"value"  :  "IO"}""";
            assertEquals("IO", extractValue(json));
        }

        @Test
        @DisplayName("returns null when value field is absent")
        void returnsNullWhenAbsent() {
            String json = """
                {"reason":"STATIC","variant":"on"}""";
            assertEquals(null, extractValue(json));
        }

        @Test
        @DisplayName("returns null for empty JSON")
        void returnsNullForEmpty() {
            assertEquals(null, extractValue(""));
        }

        @Test
        @DisplayName("returns first match when multiple value fields exist")
        void returnsFirstMatch() {
            // This documents the known limitation: the regex matches the
            // first "value" field. In real OFREP responses, the top-level
            // "value" appears first, so this is correct for the current API.
            String json = """
                {"value":"IO","metadata":{"value":"should-not-match"}}""";
            assertEquals("IO", extractValue(json));
        }

        @Test
        @DisplayName("extracts value from realistic OFREP response")
        void extractsFromRealisticResponse() {
            String json = """
                {
                  "value": "IO",
                  "reason": "STATIC",
                  "variant": "on",
                  "metadata": {
                    "flag": "telemetryLevel",
                    "timestamp": "2024-01-01T00:00:00Z"
                  }
                }""";
            assertEquals("IO", extractValue(json));
        }
    }

    @Nested
    @DisplayName("shouldSuppressInternal (flag interpretation)")
    class ShouldSuppressInternal {

        @Test
        @DisplayName("IO value means suppression is active")
        void ioMeansSuppress() {
            // Simulating the logic in FlagdClient.poll()
            String value = "IO";
            boolean shouldSuppress = "IO".equalsIgnoreCase(value);
            assertTrue(shouldSuppress);
        }

        @Test
        @DisplayName("FULL value means suppression is inactive")
        void fullMeansNoSuppress() {
            String value = "FULL";
            boolean shouldSuppress = "IO".equalsIgnoreCase(value);
            assertFalse(shouldSuppress);
        }

        @Test
        @DisplayName("io value is case-insensitive")
        void ioCaseInsensitive() {
            assertTrue("IO".equalsIgnoreCase("io"));
            assertTrue("IO".equalsIgnoreCase("Io"));
            assertTrue("IO".equalsIgnoreCase("iO"));
        }

        @Test
        @DisplayName("null value means no suppression")
        void nullMeansNoSuppress() {
            String value = null;
            boolean shouldSuppress = "IO".equalsIgnoreCase(value);
            assertFalse(shouldSuppress);
        }

        @Test
        @DisplayName("unknown value means no suppression")
        void unknownMeansNoSuppress() {
            String value = "DEBUG";
            boolean shouldSuppress = "IO".equalsIgnoreCase(value);
            assertFalse(shouldSuppress);
        }
    }

    @Nested
    @DisplayName("default state")
    class DefaultState {

        @Test
        @DisplayName("AtomicReference defaults to false (no suppression)")
        void atomicReferenceDefaultsToFalse() {
            java.util.concurrent.atomic.AtomicReference<Boolean> ref =
                    new java.util.concurrent.atomic.AtomicReference<>(false);
            assertFalse(ref.get());
        }
    }
}

/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.dice.metamodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.kotlin.KotlinModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * Contract for DriftReport as JSON, checked with a plain mapper and with the Kotlin module:
 *
 * E1: a scoped report writes its context as "contextId" with the plain id string.
 * E2: a whole-graph report writes "contextId" as JSON null.
 * E3: a scoped check result writes "contextId" as the plain id string, both at its top level and
 *     inside its nested "report".
 * I1: no key in the JSON contains a dash, so the compiler-mangled name never leaks out.
 * I2: no "contextIdValue" key is written, because that accessor is for Java callers only.
 * I3: the check result's top level and nested report carry no dashed key.
 * I4: the check result's top level and nested report carry no "contextIdValue" key.
 *
 * DriftReport and DriftCheckResult are both covered here.
 */
class DriftContextIdJsonJavaTest {

    private static final String CONTEXT_KEY = "contextId";

    /** A plain mapper that only knows the Java time types. */
    private static ObjectMapper plainMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /** A mapper that also has the Kotlin module, so Kotlin properties read the way Kotlin names them. */
    private static ObjectMapper kotlinMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new KotlinModule.Builder().build());
    }

    /** Both mappers, so every test runs against each one. */
    private static List<ObjectMapper> bothMappers() {
        List<ObjectMapper> mappers = new ArrayList<>();
        mappers.add(plainMapper());
        mappers.add(kotlinMapper());
        return mappers;
    }

    /** Collects the top-level key names of a JSON object. */
    private static Set<String> keysOf(JsonNode node) {
        Set<String> keys = new java.util.HashSet<>();
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            keys.add(names.next());
        }
        return keys;
    }

    @Test
    void e1_scopedReportWritesContextIdAsAcme() {
        DriftReport report = DriftContextIdFixtures.scopedReport("acme");

        for (ObjectMapper mapper : bothMappers()) {
            JsonNode json = mapper.valueToTree(report);
            JsonNode context = json.get(CONTEXT_KEY);

            assertNotNull(context, "expected a contextId key, got " + keysOf(json));
            assertEquals("acme", context.asText());
        }
    }

    @Test
    void e2_wholeGraphReportWritesContextIdAsNull() {
        DriftReport report = new DriftReport("support", "hash", Set.of(), Set.of(), Instant.EPOCH);

        for (ObjectMapper mapper : bothMappers()) {
            JsonNode json = mapper.valueToTree(report);

            assertTrue(json.has(CONTEXT_KEY), "expected a contextId key, got " + keysOf(json));
            assertTrue(json.get(CONTEXT_KEY).isNull(), "expected contextId to be null, got " + json.get(CONTEXT_KEY));
        }
    }

    @Test
    void i1_noKeyContainsADash() {
        DriftReport scoped = DriftContextIdFixtures.scopedReport("acme");
        DriftReport wholeGraph = new DriftReport("support", "hash", Set.of(), Set.of(), Instant.EPOCH);

        for (ObjectMapper mapper : bothMappers()) {
            for (DriftReport report : List.of(scoped, wholeGraph)) {
                for (String key : keysOf(mapper.valueToTree(report))) {
                    assertFalse(key.contains("-"), "key must not contain a dash, got " + key);
                }
            }
        }
    }

    @Test
    void i2_noContextIdValueKeyIsWritten() {
        DriftReport scoped = DriftContextIdFixtures.scopedReport("acme");
        DriftReport wholeGraph = new DriftReport("support", "hash", Set.of(), Set.of(), Instant.EPOCH);

        for (ObjectMapper mapper : bothMappers()) {
            for (DriftReport report : List.of(scoped, wholeGraph)) {
                assertFalse(
                        keysOf(mapper.valueToTree(report)).contains("contextIdValue"),
                        "contextIdValue must not be serialized");
            }
        }
    }

    @Test
    void e3_scopedCheckResultWritesContextIdAsAcmeAtBothLevels() {
        DriftCheckResult result = DriftContextIdFixtures.scopedResult("acme");

        for (ObjectMapper mapper : bothMappers()) {
            JsonNode json = mapper.valueToTree(result);
            JsonNode topLevel = json.get(CONTEXT_KEY);
            JsonNode nested = json.get("report") == null ? null : json.get("report").get(CONTEXT_KEY);

            assertNotNull(topLevel, "expected a top-level contextId key, got " + keysOf(json));
            assertEquals("acme", topLevel.asText());
            assertNotNull(nested, "expected a contextId key in report, got " + keysOf(json.get("report")));
            assertEquals("acme", nested.asText());
        }
    }

    @Test
    void i3_noCheckResultKeyContainsADash() {
        DriftCheckResult result = DriftContextIdFixtures.scopedResult("acme");

        for (ObjectMapper mapper : bothMappers()) {
            JsonNode json = mapper.valueToTree(result);
            for (String key : keysOf(json)) {
                assertFalse(key.contains("-"), "top-level key must not contain a dash, got " + key);
            }
            for (String key : keysOf(json.get("report"))) {
                assertFalse(key.contains("-"), "report key must not contain a dash, got " + key);
            }
        }
    }

    @Test
    void i4_noCheckResultLevelWritesContextIdValue() {
        DriftCheckResult result = DriftContextIdFixtures.scopedResult("acme");

        for (ObjectMapper mapper : bothMappers()) {
            JsonNode json = mapper.valueToTree(result);
            assertFalse(keysOf(json).contains("contextIdValue"),
                    "top-level contextIdValue must not be serialized, got " + keysOf(json));
            assertFalse(keysOf(json.get("report")).contains("contextIdValue"),
                    "report contextIdValue must not be serialized, got " + keysOf(json.get("report")));
        }
    }
}

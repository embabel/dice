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
package com.embabel.dice;

import com.embabel.dice.common.SourceAnalysisContext;
import com.embabel.dice.proposition.PropositionQuery;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.kotlin.KotlinModule;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks how a Java caller's context id shows up in the JSON for a {@link PropositionQuery}
 * and for a {@link SourceAnalysisContext}.
 *
 * Contract, read from the code:
 * E1: a query built with againstContext("acme") writes the context as a plain string under "contextId".
 * E2: the String helper "contextIdValue" is never written.
 * E3: no key in the query's JSON contains a hyphen, so no compiler-mangled name leaks out.
 * E4: the getter for the query's context id carries @JsonProperty("contextId").
 * E5: the mangled getter "getContextId-*" on SourceAnalysisContext carries @JsonProperty("contextId").
 */
class QueryContextIdJsonJavaTest {

    private static ObjectMapper plainMapper() {
        return new ObjectMapper();
    }

    private static ObjectMapper kotlinMapper() {
        return new ObjectMapper().registerModule(new KotlinModule.Builder().build());
    }

    private static List<String> allFieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        collectFieldNames(node, names);
        return names;
    }

    private static void collectFieldNames(JsonNode node, List<String> names) {
        if (node.isObject()) {
            Iterator<String> fields = node.fieldNames();
            while (fields.hasNext()) {
                String name = fields.next();
                names.add(name);
                collectFieldNames(node.get(name), names);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectFieldNames(child, names);
            }
        }
    }

    /**
     * Finds the getter for the context id. Kotlin mangles it to "getContextId-*" because
     * ContextId is an inline class, so there must be exactly one such method.
     */
    private static Method mangledContextIdGetter(Class<?> type) {
        List<Method> found = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (method.getName().startsWith("getContextId-")) {
                found.add(method);
            }
        }
        assertEquals(1, found.size(), "expected exactly one getContextId-* getter on " + type.getSimpleName());
        return found.get(0);
    }

    @Test
    void e1_plainMapperWritesContextIdAsAPlainString() {
        JsonNode node = plainMapper().valueToTree(PropositionQuery.againstContext("acme"));

        assertTrue(node.has("contextId"), "expected a contextId key, got " + allFieldNames(node));
        assertTrue(node.get("contextId").isTextual(), "contextId should be a plain string");
        assertEquals("acme", node.get("contextId").asText());
    }

    @Test
    void e1_kotlinMapperWritesContextIdAsAPlainString() {
        JsonNode node = kotlinMapper().valueToTree(PropositionQuery.againstContext("acme"));

        assertTrue(node.has("contextId"), "expected a contextId key, got " + allFieldNames(node));
        assertTrue(node.get("contextId").isTextual(), "contextId should be a plain string");
        assertEquals("acme", node.get("contextId").asText());
    }

    @Test
    void e2_contextIdValueIsNeverWritten() {
        JsonNode plain = plainMapper().valueToTree(PropositionQuery.againstContext("acme"));
        JsonNode kotlin = kotlinMapper().valueToTree(PropositionQuery.againstContext("acme"));

        assertFalse(plain.has("contextIdValue"), "plain mapper wrote contextIdValue: " + allFieldNames(plain));
        assertFalse(kotlin.has("contextIdValue"), "kotlin mapper wrote contextIdValue: " + allFieldNames(kotlin));
    }

    @Test
    void e3_noKeyContainsAHyphen() {
        for (ObjectMapper mapper : List.of(plainMapper(), kotlinMapper())) {
            JsonNode node = mapper.valueToTree(PropositionQuery.againstContext("acme"));
            for (String name : allFieldNames(node)) {
                assertFalse(name.contains("-"), "key with a hyphen leaked into the JSON: " + name);
            }
        }
    }

    @Test
    void e4_queryContextIdGetterCarriesTheJsonPropertyName() {
        Method getter = mangledContextIdGetter(PropositionQuery.class);
        JsonProperty annotation = getter.getAnnotation(JsonProperty.class);

        assertNotNull(annotation, getter.getName() + " should carry @JsonProperty");
        assertEquals("contextId", annotation.value());
    }

    @Test
    void e5_sourceAnalysisContextMangledGetterCarriesTheJsonPropertyName() {
        Method getter = mangledContextIdGetter(SourceAnalysisContext.class);
        JsonProperty annotation = getter.getAnnotation(JsonProperty.class);

        assertNotNull(annotation, getter.getName() + " should carry @JsonProperty");
        assertEquals("contextId", annotation.value());
    }
}

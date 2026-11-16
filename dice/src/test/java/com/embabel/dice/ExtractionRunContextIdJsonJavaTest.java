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

import com.embabel.dice.proposition.extraction.ExtractionRun;
import com.embabel.dice.proposition.extraction.ExtractionRunKey;
import com.embabel.dice.proposition.extraction.ExtractionRunLineage;
import com.embabel.dice.proposition.extraction.ExtractionRunRef;
import com.embabel.dice.proposition.extraction.ExtractionRunStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.kotlin.KotlinModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the extraction run types write their tenant as a plain "contextId" string when
 * Jackson serializes them, with a plain mapper and with the Kotlin module added.
 */
class ExtractionRunContextIdJsonJavaTest {

    private static final Instant STARTED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private static ObjectMapper plainMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    private static ObjectMapper kotlinMapper() {
        return plainMapper().registerModule(new KotlinModule.Builder().build());
    }

    private static void assertNoKeyContainsDash(JsonNode node) {
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                assertFalse(field.getKey().contains("-"),
                        "no key may contain a dash, found " + field.getKey());
                assertNoKeyContainsDash(field.getValue());
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                assertNoKeyContainsDash(child);
            }
        }
    }

    private static void assertContextIdContract(ObjectMapper mapper, Object value) {
        JsonNode tree = mapper.valueToTree(value);

        JsonNode contextId = tree.get("contextId");
        assertNotNull(contextId, "contextId must be a top-level key, tree was " + tree);
        assertTrue(contextId.isTextual(), "contextId must be a JSON string");
        assertEquals("acme", contextId.asText());
        assertNull(tree.get("contextIdValue"), "contextIdValue must not be written");
        assertNoKeyContainsDash(tree);
    }

    @Test
    void runKeyWritesTenantAsContextIdWithPlainMapper() {
        assertContextIdContract(plainMapper(), ExtractionRunKey.of("acme", "run-1"));
    }

    @Test
    void runKeyWritesTenantAsContextIdWithKotlinModule() {
        assertContextIdContract(kotlinMapper(), ExtractionRunKey.of("acme", "run-1"));
    }

    @Test
    void runWritesTenantAsContextIdWithPlainMapper() {
        assertContextIdContract(plainMapper(), newRun());
    }

    @Test
    void runWritesTenantAsContextIdWithKotlinModule() {
        assertContextIdContract(kotlinMapper(), newRun());
    }

    private static ExtractionRun newRun() {
        return ExtractionRun.of(
                "acme",
                ExtractionRunLineage.root(new ExtractionRunRef("run-1")),
                ExtractionRunStatus.RUNNING,
                STARTED_AT);
    }
}

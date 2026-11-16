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

import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionDraft;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.kotlin.KotlinModule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the JSON form of a {@link PropositionDraft} from Java, with a plain mapper and with the Kotlin module.
 *
 * Contract, read from the code:
 * E1: the context id is written as a plain string under the key "contextId".
 * E2: the plain string twin "contextIdValue" is never written.
 * E3: no key contains a hyphen, so no compiler-generated name such as "contextId-SSYM5D4" leaks out.
 */
class DraftContextIdJsonJavaTest {

    private static PropositionDraft draft() {
        return Proposition.draft("acme", "t", 0.9);
    }

    private static ObjectMapper plainMapper() {
        return new ObjectMapper();
    }

    private static ObjectMapper kotlinMapper() {
        return new ObjectMapper().registerModule(new KotlinModule.Builder().build());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            names.add(it.next());
        }
        return names;
    }

    @Test
    void e1_plainMapperWritesContextIdAsAString() {
        JsonNode node = plainMapper().valueToTree(draft());

        assertTrue(node.has("contextId"), "expected a contextId key, got " + fieldNames(node));
        assertEquals("acme", node.get("contextId").asText());
        assertTrue(node.get("contextId").isTextual(), "contextId should be a plain string");
    }

    @Test
    void e1_kotlinMapperWritesContextIdAsAString() {
        JsonNode node = kotlinMapper().valueToTree(draft());

        assertTrue(node.has("contextId"), "expected a contextId key, got " + fieldNames(node));
        assertEquals("acme", node.get("contextId").asText());
        assertTrue(node.get("contextId").isTextual(), "contextId should be a plain string");
    }

    @Test
    void e2_plainMapperSkipsContextIdValue() {
        JsonNode node = plainMapper().valueToTree(draft());

        assertFalse(node.has("contextIdValue"), "contextIdValue must not be written, got " + fieldNames(node));
    }

    @Test
    void e2_kotlinMapperSkipsContextIdValue() {
        JsonNode node = kotlinMapper().valueToTree(draft());

        assertFalse(node.has("contextIdValue"), "contextIdValue must not be written, got " + fieldNames(node));
    }

    @Test
    void e3_plainMapperWritesNoKeyWithAHyphen() {
        List<String> names = fieldNames(plainMapper().valueToTree(draft()));

        for (String name : names) {
            assertFalse(name.contains("-"), "key '" + name + "' has a hyphen, got " + names);
        }
    }

    @Test
    void e3_kotlinMapperWritesNoKeyWithAHyphen() {
        List<String> names = fieldNames(kotlinMapper().valueToTree(draft()));

        for (String name : names) {
            assertFalse(name.contains("-"), "key '" + name + "' has a hyphen, got " + names);
        }
    }
}

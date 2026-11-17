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

import com.embabel.dice.incremental.BookmarkKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.kotlin.KotlinModule;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Checks that the events and chunk history keys write their context id as a plain
 * {@code contextId} field, with a plain Jackson mapper and with the Kotlin module.
 *
 * Contract:
 * E1: each value writes {@code contextId} as the string "acme".
 * E2: no top-level field name contains a dash, so the compiler-mangled key never shows up.
 * E3: no {@code contextIdValue} field is written.
 * E4: the Kotlin mapper reads {@code {"contextId":"acme","sourceId":"s"}} into a {@link BookmarkKey}.
 */
class EventContextIdJsonJavaTest {

    private static final String CONTEXT_ID = "acme";

    private static ObjectMapper plainMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private static ObjectMapper kotlinMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new KotlinModule.Builder().build());
    }

    private static List<Object> valuesWithAContextId() {
        List<Object> values = new ArrayList<>();
        values.add(ContextIdFixtures.ungroundedMentionsFound(CONTEXT_ID));
        values.add(ContextIdFixtures.propositionContradicted(CONTEXT_ID));
        values.add(ContextIdFixtures.bookmarkKey(CONTEXT_ID));
        values.add(ContextIdFixtures.hashKey(CONTEXT_ID));
        values.add(ContextIdFixtures.processedChunkRecord(CONTEXT_ID));
        return values;
    }

    private static void assertContextIdIsPlainString(ObjectMapper mapper, Object value) {
        JsonNode tree = mapper.valueToTree(value);

        JsonNode contextId = tree.get("contextId");
        assertNotNull(contextId, "expected a contextId field in " + value.getClass().getSimpleName());
        assertEquals(CONTEXT_ID, contextId.asText(), value.getClass().getSimpleName());

        Iterator<Map.Entry<String, JsonNode>> fields = tree.fields();
        while (fields.hasNext()) {
            String name = fields.next().getKey();
            assertFalse(name.contains("-"),
                    value.getClass().getSimpleName() + " wrote a mangled field name: " + name);
            assertFalse("contextIdValue".equals(name),
                    value.getClass().getSimpleName() + " must not write contextIdValue");
        }
    }

    @Test
    void plainMapperWritesContextIdAsAPlainStringForEveryValue() {
        for (Object value : valuesWithAContextId()) {
            assertContextIdIsPlainString(plainMapper(), value);
        }
    }

    @Test
    void kotlinMapperWritesContextIdAsAPlainStringForEveryValue() {
        for (Object value : valuesWithAContextId()) {
            assertContextIdIsPlainString(kotlinMapper(), value);
        }
    }

    @Test
    void kotlinMapperReadsABookmarkKeyFromTheContextIdField() throws Exception {
        BookmarkKey key = kotlinMapper().readValue(
                "{\"contextId\":\"acme\",\"sourceId\":\"s\"}", BookmarkKey.class);

        assertEquals(CONTEXT_ID, key.getContextIdValue());
        assertEquals("s", key.getSourceId());
    }
}

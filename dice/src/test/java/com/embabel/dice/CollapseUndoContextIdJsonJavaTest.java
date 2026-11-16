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

import com.embabel.dice.spi.CollapseUndoCommand;
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
 * Checks the JSON shape of a collapse undo command and of the context mismatch exception, as a
 * Java caller sees it through both a plain Jackson mapper and one with the Kotlin module.
 *
 * Contract:
 * R1: The command is built with {@code CollapseUndoCommand.of}, so every field is non-blank.
 * E1: The command writes its context under the key {@code contextId}, with both mappers.
 * E2: The command reads back from that JSON with the Kotlin mapper and equals the original.
 * E3: The mismatch exception writes {@code commandedContextId} and {@code actualContextId}, with both mappers.
 * I1: No top-level key of either object contains a hyphen, so no compiler-generated name leaks out.
 */
class CollapseUndoContextIdJsonJavaTest {

    private final ObjectMapper plain = new ObjectMapper();

    private final ObjectMapper withKotlin = new ObjectMapper().registerModule(new KotlinModule.Builder().build());

    @Test
    void plainMapperWritesTheCommandContextUnderContextId() throws Exception {
        CollapseUndoCommand command = CollapseUndoCommand.of("acme", "s", "r");

        JsonNode json = plain.readTree(plain.writeValueAsString(command));

        assertEquals("acme", json.get("contextId").asText());
    }

    @Test
    void kotlinMapperWritesTheCommandContextUnderContextId() throws Exception {
        CollapseUndoCommand command = CollapseUndoCommand.of("acme", "s", "r");

        JsonNode json = withKotlin.readTree(withKotlin.writeValueAsString(command));

        assertEquals("acme", json.get("contextId").asText());
    }

    @Test
    void commandRoundTripsThroughTheKotlinMapper() throws Exception {
        CollapseUndoCommand command = CollapseUndoCommand.of("acme", "s", "r");

        CollapseUndoCommand copy = withKotlin.readValue(
                withKotlin.writeValueAsString(command), CollapseUndoCommand.class);

        assertEquals(command, copy);
        assertEquals("acme", copy.getContextIdValue());
    }

    @Test
    void plainMapperWritesTheMismatchContextsUnderTheirOwnKeys() throws Exception {
        Exception mismatch = ContextIdFixtures.collapseUndoContextMismatch("a", "b");

        JsonNode json = plain.readTree(plain.writeValueAsString(mismatch));

        assertEquals("a", json.get("commandedContextId").asText());
        assertEquals("b", json.get("actualContextId").asText());
    }

    @Test
    void kotlinMapperWritesTheMismatchContextsUnderTheirOwnKeys() throws Exception {
        Exception mismatch = ContextIdFixtures.collapseUndoContextMismatch("a", "b");

        JsonNode json = withKotlin.readTree(withKotlin.writeValueAsString(mismatch));

        assertEquals("a", json.get("commandedContextId").asText());
        assertEquals("b", json.get("actualContextId").asText());
    }

    @Test
    void noTopLevelKeyOfTheCommandOrTheMismatchContainsAHyphen() throws Exception {
        CollapseUndoCommand command = CollapseUndoCommand.of("acme", "s", "r");
        Exception mismatch = ContextIdFixtures.collapseUndoContextMismatch("a", "b");

        List<String> keys = new ArrayList<>();
        for (ObjectMapper mapper : List.of(plain, withKotlin)) {
            keys.addAll(fieldNames(mapper.readTree(mapper.writeValueAsString(command))));
            keys.addAll(fieldNames(mapper.readTree(mapper.writeValueAsString(mismatch))));
        }

        assertFalse(keys.isEmpty());
        for (String key : keys) {
            assertFalse(key.contains("-"), "key must not contain a hyphen: " + key);
        }
        assertTrue(keys.contains("contextId"));
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            names.add(it.next());
        }
        return names;
    }
}

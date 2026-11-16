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
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.store.JsonFilePropositionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.module.kotlin.KotlinModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Java's view of how a {@link Proposition} is written to JSON and read back. The context is
 * stored under the plain key {@code contextId}, the old helper key {@code contextIdValue} stays
 * out of the output, and files written by earlier versions still load.
 */
class PropositionContextIdJsonJavaTest {

    /** A mapper the way a plain Java service would set one up, with the Java time types registered. */
    private static ObjectMapper javaMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    /** A mapper with the Kotlin module added, the way the dice Kotlin code builds its own. */
    private static ObjectMapper kotlinMapper() {
        return new ObjectMapper()
                .registerModule(new KotlinModule.Builder().build())
                .findAndRegisterModules();
    }

    /** Builds an active proposition in the context "acme". */
    private static Proposition acmeProposition(String id) {
        Instant now = Instant.parse("2026-01-02T00:00:00Z");
        return Proposition.create(
                id,
                "acme",
                "Alice likes tea",
                List.of(),
                0.8,
                0.1,
                0.5,
                null,
                List.of(),
                now,
                now,
                now,
                PropositionStatus.ACTIVE
        );
    }

    /** One proposition in the shape earlier versions wrote, with the old helper keys still present. */
    private static final String OLD_SHAPE_PROPOSITION = """
            {
              "id": "old-1",
              "contextId": "acme",
              "contextIdValue": "acme",
              "text": "Alice likes tea",
              "mentions": [],
              "confidence": 0.8,
              "decay": 0.1,
              "importance": 0.5,
              "reasoning": null,
              "grounding": [],
              "created": "2026-01-02T00:00:00Z",
              "revised": "2026-01-02T00:00:00Z",
              "lastTouched": "2026-01-02T00:00:00Z",
              "contentRevised": "2026-01-02T00:00:00Z",
              "metadataRevised": "2026-01-02T00:00:00Z",
              "lastAccessed": "2026-01-02T00:00:00Z",
              "status": "ACTIVE",
              "level": 0,
              "sourceIds": [],
              "reinforceCount": 0,
              "metadata": {},
              "pinned": false
            }
            """;

    /** Returns every field name in the tree, walking nested objects and arrays too. */
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

    @Test
    void bothMappersWriteTheContextUnderContextIdWithoutTheHelperKey() throws Exception {
        Proposition proposition = acmeProposition("write-1");

        for (ObjectMapper mapper : List.of(javaMapper(), kotlinMapper())) {
            JsonNode tree = mapper.readTree(mapper.writeValueAsString(proposition));

            assertEquals("acme", tree.get("contextId").asText());
            assertFalse(tree.has("contextIdValue"), "contextIdValue should not be written");
            for (String name : allFieldNames(tree)) {
                assertFalse(name.contains("-"), "no written key should contain a dash, found " + name);
            }
        }
    }

    @Test
    void theKotlinMapperReadsAnOldShapePropositionWithTheHelperKeysStillInIt() throws Exception {
        Proposition restored = kotlinMapper().readValue(OLD_SHAPE_PROPOSITION, Proposition.class);

        assertEquals("old-1", restored.getId());
        assertEquals("acme", restored.getContextIdValue());
        assertEquals("Alice likes tea", restored.getText());
        assertEquals(PropositionStatus.ACTIVE, restored.getStatus());
        assertEquals(Instant.parse("2026-01-02T00:00:00Z"), restored.getContentRevised());
    }

    @Test
    void anOldShapeFileLoadsThroughTheRepositoryAndAfterASaveNoLongerCarriesTheHelperKey(@TempDir Path dir)
            throws Exception {
        Path file = dir.resolve("propositions.json");
        Files.writeString(file, "[" + OLD_SHAPE_PROPOSITION + "]");

        JsonFilePropositionRepository repository = new JsonFilePropositionRepository(file);
        Proposition loaded = repository.findById("old-1");
        assertNotNull(loaded);
        assertEquals("acme", loaded.getContextIdValue());

        repository.save(loaded);

        String text = Files.readString(file);
        assertTrue(text.contains("\"contextId\""), "saved file should carry contextId");
        assertFalse(text.contains("\"contextIdValue\""), "saved file should not carry contextIdValue");
    }
}

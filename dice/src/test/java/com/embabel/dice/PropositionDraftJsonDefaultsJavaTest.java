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

import com.embabel.dice.proposition.EntityMention;
import com.embabel.dice.proposition.MentionRole;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionDraft;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.temporal.TemporalMetadata;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.kotlin.KotlinModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks reading a {@link PropositionDraft} from JSON that leaves keys out, and a full round trip.
 *
 * E1: with a plain mapper, a JSON object holding only contextId, text and confidence reads into a
 *     draft with the same defaults as Proposition.draft, importance 0.5 among them.
 * E2: the same holds with Jackson's Kotlin module.
 * E3: JSON without text, contextId or confidence fails the read with a message naming the key.
 * E4: a draft taken from a proposition, written to JSON and read back, builds a proposition equal
 *     to the original, with either mapper.
 */
class PropositionDraftJsonDefaultsJavaTest {

    private static final String MINIMAL = "{\"contextId\":\"acme\",\"text\":\"Alice lives in Paris\",\"confidence\":0.9}";

    private static ObjectMapper plain() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private static ObjectMapper withKotlin() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new KotlinModule.Builder().build());
    }

    @Test
    void e1_aPlainMapperFillsMissingKeysWithTheDraftDefaults() throws Exception {
        assertMatchesFreshDraft(plain().readValue(MINIMAL, PropositionDraft.class));
    }

    @Test
    void e2_theKotlinModuleFillsMissingKeysTheSameWay() throws Exception {
        assertMatchesFreshDraft(withKotlin().readValue(MINIMAL, PropositionDraft.class));
    }

    @Test
    void e3_aMissingRequiredKeyFailsWithItsName() {
        for (ObjectMapper mapper : List.of(plain(), withKotlin())) {
            JsonMappingException noText = assertThrows(JsonMappingException.class,
                    () -> mapper.readValue("{\"contextId\":\"acme\",\"confidence\":0.9}", PropositionDraft.class));
            assertTrue(noText.getMessage().contains("text"), noText.getMessage());

            JsonMappingException noContext = assertThrows(JsonMappingException.class,
                    () -> mapper.readValue("{\"text\":\"t\",\"confidence\":0.9}", PropositionDraft.class));
            assertTrue(noContext.getMessage().contains("contextId"), noContext.getMessage());

            JsonMappingException noConfidence = assertThrows(JsonMappingException.class,
                    () -> mapper.readValue("{\"contextId\":\"acme\",\"text\":\"t\"}", PropositionDraft.class));
            assertTrue(noConfidence.getMessage().contains("confidence"), noConfidence.getMessage());
        }
    }

    @Test
    void e4_aDraftRoundTripsThroughJsonToAnEqualProposition() throws Exception {
        Instant created = Instant.parse("2024-01-01T00:00:00Z");
        Proposition original = Proposition.draft("acme", "Alice lives in Paris", 0.9)
                .withId("p-1")
                .withMentions(List.of(new EntityMention("Alice", "Person", "alice-1", MentionRole.SUBJECT, Map.of())))
                .withDecay(0.2)
                .withImportance(0.7)
                .withReasoning("Said in the intro")
                .withGrounding(List.of("chunk-1"))
                .withCreated(created)
                .withContentRevised(created.plusSeconds(60))
                .withMetadataRevised(created.plusSeconds(120))
                .withLastAccessed(created.plusSeconds(180))
                .withPinned(true)
                .withStatus(PropositionStatus.ACTIVE)
                .withLevel(1)
                .withSourceIds(List.of("p-0"))
                .withReinforceCount(2)
                .withMetadata(Map.of("source", "import"))
                .withUri("urn:test:1")
                .withTemporal(new TemporalMetadata(created, created, null, null, List.of(), List.of()))
                .toProposition();

        assertRoundTrips(withKotlin(), original);
        // EntityMention needs the Kotlin module to be read, so the plain mapper round trips a
        // proposition without mentions.
        assertRoundTrips(plain(), original.toDraft().withMentions(List.of()).toProposition());
    }

    private static void assertRoundTrips(ObjectMapper mapper, Proposition original) throws Exception {
        String json = mapper.writeValueAsString(original.toDraft());
        // TemporalMetadata writes a computed isCurrent key that it does not read back, so this
        // read skips unknown keys. Every key the draft itself writes is still read.
        PropositionDraft read = mapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .readValue(json, PropositionDraft.class);

        assertEquals(original, read.toProposition(), json);
    }

    private static void assertMatchesFreshDraft(PropositionDraft read) {
        PropositionDraft fresh = Proposition.draft("acme", "Alice lives in Paris", 0.9);
        assertEquals(fresh.getContextIdValue(), read.getContextIdValue());
        assertEquals(fresh.getText(), read.getText());
        assertEquals(fresh.getConfidence(), read.getConfidence());
        assertEquals(0.5, read.getImportance());
        assertEquals(fresh.getImportance(), read.getImportance());
        assertEquals(fresh.getDecay(), read.getDecay());
        assertEquals(fresh.getPinned(), read.getPinned());
        assertEquals(fresh.getStatus(), read.getStatus());
        assertEquals(fresh.getLevel(), read.getLevel());
        assertEquals(fresh.getReinforceCount(), read.getReinforceCount());
        assertEquals(fresh.getMentions(), read.getMentions());
        assertEquals(fresh.getGrounding(), read.getGrounding());
        assertEquals(fresh.getSourceIds(), read.getSourceIds());
        assertEquals(fresh.getMetadata(), read.getMetadata());
        assertEquals(fresh.getProvenanceEntries(), read.getProvenanceEntries());
        assertEquals(fresh.getId(), read.getId());
        assertFalse(read.toProposition().getText().isEmpty());
    }
}

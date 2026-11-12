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
import com.embabel.dice.provenance.ProvenanceEntry;
import com.embabel.dice.provenance.UriLocator;
import com.embabel.dice.temporal.TemporalMetadata;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shows how Java code builds a proposition with named steps on a draft.
 */
class PropositionDraftJavaInteropTest {

    // The clauses these tests check:
    // 1. E1: a draft with no step builds a proposition with the three arguments and every default,
    //    a non-blank id, and four equal times.
    // 2. E2: with all twenty-one steps set, the built proposition equals the one from the
    //    longest Proposition.create overload.
    // 3. E3: an unset time follows the one before it.
    // 4. E4: withGrounding appends and removes duplicates; the other collection steps replace.
    // 5. I1: a step never changes the draft it is called on, nor follows later changes to the
    //    caller's list.
    // 6. E5: an out-of-range value fails in toProposition() with the constructor's message.
    // 7. L1: p.toDraft().toProposition() equals p, and one step changes only its own field.
    // 8. E6: each build without an id generates a new id; with an id every build uses it.
    // 9. I2: the lists and the map a draft returns reject changes.
    // 10. I3: a later change to the caller's list or map reaches neither the draft nor a
    //     proposition built from it.
    // 11. I4: a proposition keeps its source ids when code tries to clear them through the draft.
    // 12. I5: a draft made with toDraft() does not follow later changes to the proposition's list.

    private static final Instant CREATED = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant CONTENT_REVISED = Instant.parse("2025-02-01T00:00:00Z");
    private static final Instant METADATA_REVISED = Instant.parse("2025-03-01T00:00:00Z");
    private static final Instant LAST_ACCESSED = Instant.parse("2025-04-01T00:00:00Z");

    private static final EntityMention ALICE =
            new EntityMention("Alice", "Person", null, MentionRole.SUBJECT, Map.of());
    private static final EntityMention PARIS =
            new EntityMention("Paris", "City", "paris-1", MentionRole.OBJECT, Map.of());
    private static final ProvenanceEntry FIRST_SOURCE =
            new ProvenanceEntry(new UriLocator("https://example.com/first"));
    private static final ProvenanceEntry SECOND_SOURCE =
            new ProvenanceEntry(new UriLocator("https://example.com/second"));
    private static final TemporalMetadata TEMPORAL =
            new TemporalMetadata(CREATED, CREATED, null, null, List.of(), List.of());

    private static Proposition everyFieldSet() {
        return Proposition.draft("acme", "Alice lives in Paris", 0.9)
                .withId("p-full")
                .withMentions(List.of(ALICE, PARIS))
                .withDecay(0.2)
                .withImportance(0.7)
                .withReasoning("stated directly")
                .withGrounding(List.of("chunk-1"))
                .withCreated(CREATED)
                .withContentRevised(CONTENT_REVISED)
                .withMetadataRevised(METADATA_REVISED)
                .withLastAccessed(LAST_ACCESSED)
                .withPinned(true)
                .withStatus(PropositionStatus.SUPERSEDED)
                .withLevel(1)
                .withSourceIds(List.of("source-1"))
                .withReinforceCount(3)
                .withMetadata(Map.of("trust", 0.8))
                .withUri("urn:prop:1")
                .withTemporal(TEMPORAL)
                .withProvenance(List.of(FIRST_SOURCE))
                .withText("Alice lives in Paris, France")
                .withConfidence(0.95)
                .toProposition();
    }

    @Test
    void e1_draftWithNoStepUsesTheArgumentsAndDefaults() {
        Proposition p = Proposition.draft("acme", "Alice lives in Paris", 0.9).toProposition();

        assertEquals("acme", p.getContextIdValue());
        assertEquals("Alice lives in Paris", p.getText());
        assertEquals(0.9, p.getConfidence());
        assertTrue(p.getMentions().isEmpty());
        assertTrue(p.getGrounding().isEmpty());
        assertTrue(p.getSourceIds().isEmpty());
        assertTrue(p.getMetadata().isEmpty());
        assertTrue(p.getProvenanceEntries().isEmpty());
        assertEquals(0.0, p.getDecay());
        assertEquals(0.5, p.getImportance());
        assertNull(p.getReasoning());
        assertNull(p.getUri());
        assertNull(p.getTemporal());
        assertEquals(PropositionStatus.ACTIVE, p.getStatus());
        assertEquals(0, p.getLevel());
        assertEquals(0, p.getReinforceCount());
        assertFalse(p.getPinned());
        assertFalse(p.getId().isBlank());
        assertEquals(p.getCreated(), p.getContentRevised());
        assertEquals(p.getCreated(), p.getMetadataRevised());
        assertEquals(p.getCreated(), p.getLastAccessed());
    }

    @Test
    void e2_everyStepMatchesTheLongestCreateOverload() {
        Proposition expected = Proposition.create(
                "p-full",
                "acme",
                "Alice lives in Paris, France",
                List.of(ALICE, PARIS),
                0.95,
                0.2,
                0.7,
                "stated directly",
                List.of("chunk-1"),
                CREATED,
                CONTENT_REVISED,
                LAST_ACCESSED,
                PropositionStatus.SUPERSEDED,
                1,
                List.of("source-1"),
                3,
                Map.of("trust", 0.8),
                "urn:prop:1",
                TEMPORAL,
                List.of(FIRST_SOURCE),
                CONTENT_REVISED,
                METADATA_REVISED,
                true);

        assertEquals(expected, everyFieldSet());
    }

    @Test
    void e3_anUnsetTimeFollowsTheOneBeforeIt() {
        PropositionDraft draft = Proposition.draft("acme", "Alice lives in Paris", 0.9);

        Proposition createdOnly = draft.withCreated(CREATED).toProposition();
        assertEquals(CREATED, createdOnly.getCreated());
        assertEquals(CREATED, createdOnly.getContentRevised());
        assertEquals(CREATED, createdOnly.getMetadataRevised());
        assertEquals(CREATED, createdOnly.getLastAccessed());

        Proposition revisedOnly = draft.withContentRevised(CONTENT_REVISED).toProposition();
        assertEquals(CONTENT_REVISED, revisedOnly.getCreated());
        assertEquals(CONTENT_REVISED, revisedOnly.getContentRevised());
        assertEquals(CONTENT_REVISED, revisedOnly.getMetadataRevised());
        assertEquals(CONTENT_REVISED, revisedOnly.getLastAccessed());

        Proposition both = draft.withCreated(CREATED).withContentRevised(CONTENT_REVISED).toProposition();
        assertEquals(CREATED, both.getCreated());
        assertEquals(CONTENT_REVISED, both.getContentRevised());
        assertEquals(CONTENT_REVISED, both.getMetadataRevised());
        assertEquals(CONTENT_REVISED, both.getLastAccessed());
    }

    @Test
    void e4_groundingAppendsAndOtherCollectionsReplace() {
        Proposition p = Proposition.draft("acme", "Alice lives in Paris", 0.9)
                .withGrounding(List.of("a", "b"))
                .withGrounding(List.of("b", "c"))
                .withProvenance(List.of(FIRST_SOURCE))
                .withProvenance(List.of(SECOND_SOURCE))
                .withMentions(List.of(ALICE))
                .withMentions(List.of(PARIS))
                .withSourceIds(List.of("s1"))
                .withSourceIds(List.of("s2"))
                .withMetadata(Map.of("first", 1))
                .withMetadata(Map.of("second", 2))
                .toProposition();

        assertEquals(List.of("a", "b", "c"), p.getGrounding());
        assertEquals(List.of(SECOND_SOURCE), p.getProvenanceEntries());
        assertEquals(List.of(PARIS), p.getMentions());
        assertEquals(List.of("s2"), p.getSourceIds());
        assertEquals(Map.of("second", 2), p.getMetadata());
    }

    @Test
    void i1_aStepNeverChangesTheDraftItIsCalledOn() {
        PropositionDraft d = Proposition.draft("acme", "Alice lives in Paris", 0.9);
        d.withDecay(0.3);
        assertEquals(0.0, d.toProposition().getDecay());

        List<String> sourceIds = new ArrayList<>(List.of("s1"));
        PropositionDraft withSources = d.withSourceIds(sourceIds);
        sourceIds.add("s2");
        assertEquals(List.of("s1"), withSources.toProposition().getSourceIds());
    }

    @Test
    void e5_anOutOfRangeValueFailsWhenTheDraftIsBuilt() {
        PropositionDraft badConfidence = Proposition.draft("acme", "Alice lives in Paris", 1.5);
        IllegalArgumentException confidence =
                assertThrows(IllegalArgumentException.class, badConfidence::toProposition);
        assertEquals("Confidence must be between 0.0 and 1.0", confidence.getMessage());

        PropositionDraft badDecay = Proposition.draft("acme", "Alice lives in Paris", 0.9).withDecay(-0.1);
        IllegalArgumentException decay =
                assertThrows(IllegalArgumentException.class, badDecay::toProposition);
        assertEquals("Decay must be between 0.0 and 1.0", decay.getMessage());

        PropositionDraft badLevel = Proposition.draft("acme", "Alice lives in Paris", 0.9).withLevel(1);
        IllegalArgumentException level =
                assertThrows(IllegalArgumentException.class, badLevel::toProposition);
        assertEquals("Abstracted propositions (level > 0) must have sourceIds", level.getMessage());
    }

    @Test
    void l1_aDraftOfAPropositionBuildsItBack() {
        Proposition p = everyFieldSet();
        assertEquals(p, p.toDraft().toProposition());

        Instant accessed = Instant.parse("2025-06-01T00:00:00Z");
        Proposition touched = p.toDraft().withLastAccessed(accessed).toProposition();
        assertEquals(accessed, touched.getLastAccessed());
        assertNotEquals(p, touched);
        assertEquals(p, touched.toDraft().withLastAccessed(p.getLastAccessed()).toProposition());
    }

    @Test
    void e6_eachBuildWithoutAnIdGeneratesANewOne() {
        PropositionDraft noId = Proposition.draft("acme", "Alice lives in Paris", 0.9);
        assertNotEquals(noId.toProposition().getId(), noId.toProposition().getId());

        PropositionDraft withId = noId.withId("p1");
        assertEquals("p1", withId.toProposition().getId());
        assertEquals("p1", withId.toProposition().getId());
    }

    @Test
    void i2_aDraftsCollectionsRejectChanges() {
        PropositionDraft d = Proposition.draft("acme", "Alice lives in Paris", 0.9)
                .withMentions(List.of(ALICE, PARIS))
                .withGrounding(List.of("chunk-1", "chunk-2"))
                .withSourceIds(List.of("s1", "s2"))
                .withMetadata(Map.of("trust", 0.8, "origin", "wiki"))
                .withProvenance(List.of(FIRST_SOURCE, SECOND_SOURCE));

        assertThrows(UnsupportedOperationException.class, () -> d.getMentions().clear());
        assertThrows(UnsupportedOperationException.class, () -> d.getGrounding().add("chunk-3"));
        assertThrows(UnsupportedOperationException.class, () -> d.getSourceIds().clear());
        assertThrows(UnsupportedOperationException.class, () -> d.getMetadata().put("extra", 1));
        assertThrows(UnsupportedOperationException.class, () -> d.getProvenanceEntries().clear());

        PropositionDraft noSteps = Proposition.draft("acme", "Alice lives in Paris", 0.9);
        assertThrows(UnsupportedOperationException.class, () -> noSteps.getGrounding().add("chunk-1"));
        assertThrows(UnsupportedOperationException.class, () -> noSteps.getMetadata().put("extra", 1));
    }

    @Test
    void i3_aLaterChangeToTheCallersCollectionReachesNothing() {
        List<EntityMention> mentions = new ArrayList<>(List.of(ALICE));
        List<String> grounding = new ArrayList<>(List.of("chunk-1"));
        List<String> sourceIds = new ArrayList<>(List.of("s1"));
        Map<String, Object> metadata = new HashMap<>(Map.of("trust", 0.8));
        List<ProvenanceEntry> provenance = new ArrayList<>(List.of(FIRST_SOURCE));

        PropositionDraft d = Proposition.draft("acme", "Alice lives in Paris", 0.9)
                .withMentions(mentions)
                .withGrounding(grounding)
                .withLevel(1)
                .withSourceIds(sourceIds)
                .withMetadata(metadata)
                .withProvenance(provenance);
        Proposition p = d.toProposition();

        mentions.add(PARIS);
        grounding.add("chunk-2");
        sourceIds.clear();
        metadata.put("extra", 1);
        provenance.add(SECOND_SOURCE);

        assertEquals(List.of(ALICE), d.getMentions());
        assertEquals(List.of("chunk-1"), d.getGrounding());
        assertEquals(List.of("s1"), d.getSourceIds());
        assertEquals(Map.of("trust", 0.8), d.getMetadata());
        assertEquals(List.of(FIRST_SOURCE), d.getProvenanceEntries());

        assertEquals(List.of(ALICE), p.getMentions());
        assertEquals(List.of("chunk-1"), p.getGrounding());
        assertEquals(List.of("s1"), p.getSourceIds());
        assertEquals(Map.of("trust", 0.8), p.getMetadata());
        assertEquals(List.of(FIRST_SOURCE), p.getProvenanceEntries());
    }

    @Test
    void i4_aBuiltPropositionKeepsItsSourceIds() {
        PropositionDraft d = Proposition.draft("acme", "Alice lives in Paris", 0.9)
                .withLevel(1)
                .withSourceIds(List.of("a", "b"));
        Proposition p = d.toProposition();

        assertThrows(UnsupportedOperationException.class, () -> d.getSourceIds().clear());

        assertEquals(List.of("a", "b"), p.getSourceIds());
        assertEquals(1, p.getLevel());
    }

    @Test
    void i5_aDraftOfAPropositionDoesNotFollowItsList() {
        List<String> grounding = new ArrayList<>(List.of("chunk-1", "chunk-2"));
        Proposition p = Proposition.create(
                "p-1",
                "acme",
                "Alice lives in Paris",
                List.of(ALICE),
                0.9,
                0.0,
                null,
                grounding,
                CREATED,
                CREATED,
                PropositionStatus.ACTIVE);

        PropositionDraft d = p.toDraft();
        grounding.add("chunk-3");

        assertEquals(List.of("chunk-1", "chunk-2"), d.getGrounding());
        assertEquals(List.of("chunk-1", "chunk-2"), d.toProposition().getGrounding());
    }
}

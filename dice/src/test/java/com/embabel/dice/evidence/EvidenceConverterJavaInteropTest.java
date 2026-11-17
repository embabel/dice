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
package com.embabel.dice.evidence;

import com.embabel.common.core.types.SimpleSimilaritySearchResult;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.revision.RevisionResult;
import com.embabel.dice.query.graph.PropositionLineage;
import com.embabel.dice.spi.ConflictType;
import com.embabel.dice.temporal.TemporalMetadata;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shows how a Java caller converts claims, scored hits and contradictions into evidence.
 */
class EvidenceConverterJavaInteropTest {

    private static final Instant RECORDED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private final EvidenceConverter converter =
            EvidenceConverter.create().withClock(Clock.fixed(RECORDED_AT, ZoneOffset.UTC));

    private final Proposition original = claim("p-1", "Ada lives in Marylebone", PropositionStatus.CONTRADICTED);

    private final Proposition replacement = claim("p-2", "Ada lives in London", PropositionStatus.ACTIVE);

    @Test
    void contradictionConvertsToConflictingWithTheOriginalFirst() {
        Evidence.Disagreement disagreement = converter.fromContradiction(
                new RevisionResult.Contradicted(original, replacement, ConflictType.Contradiction.INSTANCE));

        Evidence.Conflicting conflicting = assertInstanceOf(Evidence.Conflicting.class, disagreement);
        assertEquals("p-1", conflicting.getItems().get(0).getCitation().getPropositionId());
        assertEquals("p-2", conflicting.getItems().get(1).getCitation().getPropositionId());
        assertEquals(ConflictType.Contradiction.INSTANCE, conflicting.getConflictType());
    }

    @Test
    void customConflictConvertsToConflicting() {
        ConflictType custom = new ConflictType.Custom("policy-change");

        Evidence.Disagreement disagreement =
                converter.fromContradiction(new RevisionResult.Contradicted(original, replacement, custom));

        Evidence.Conflicting conflicting = assertInstanceOf(Evidence.Conflicting.class, disagreement);
        assertEquals("p-1", conflicting.getItems().get(0).getCitation().getPropositionId());
        assertEquals("p-2", conflicting.getItems().get(1).getCitation().getPropositionId());
        assertEquals(custom, disagreement.getConflictType());
    }

    @Test
    void revisionConvertsToSupersededWithTheOriginalAsPrevious() {
        Evidence.Disagreement disagreement = converter.fromContradiction(
                new RevisionResult.Contradicted(original, replacement, ConflictType.Revision.INSTANCE));

        Evidence.Superseded superseded = assertInstanceOf(Evidence.Superseded.class, disagreement);
        assertEquals("p-2", superseded.getCurrent().getCitation().getPropositionId());
        assertEquals(1, superseded.getPrevious().size());
        assertEquals("p-1", superseded.getPrevious().get(0).getCitation().getPropositionId());
        assertEquals(PropositionStatus.SUPERSEDED, superseded.getPrevious().get(0).getStatus());
        assertEquals(original.getConfidence(), superseded.getPrevious().get(0).getConfidence());
        assertEquals(ConflictType.Revision.INSTANCE, superseded.getConflictType());
    }

    @Test
    void worldChangeConvertsToSupersededWithTheOriginalAsPrevious() {
        Evidence.Disagreement disagreement = converter.fromContradiction(
                new RevisionResult.Contradicted(original, replacement, ConflictType.WorldProgression.INSTANCE));

        Evidence.Superseded superseded = assertInstanceOf(Evidence.Superseded.class, disagreement);
        assertEquals("p-2", superseded.getCurrent().getCitation().getPropositionId());
        assertEquals("p-1", superseded.getPrevious().get(0).getCitation().getPropositionId());
        assertEquals(PropositionStatus.SUPERSEDED, superseded.getPrevious().get(0).getStatus());
        assertEquals(ConflictType.WorldProgression.INSTANCE, superseded.getConflictType());
    }

    @Test
    void conflictingEvidenceKeepsTheStoredContradictedStatus() {
        Evidence.Disagreement disagreement = converter.fromContradiction(
                new RevisionResult.Contradicted(original, replacement, ConflictType.Contradiction.INSTANCE));

        Evidence.Conflicting conflicting = assertInstanceOf(Evidence.Conflicting.class, disagreement);
        assertEquals(PropositionStatus.CONTRADICTED, conflicting.getItems().get(0).getStatus());
    }

    @Test
    void pinnedOriginalUnderAWorldChangeKeepsItsActiveStatus() {
        Proposition pinned = claim("p-1", "Ada lives in Marylebone", PropositionStatus.ACTIVE).withPinned(true);

        Evidence.Disagreement disagreement = converter.fromContradiction(
                new RevisionResult.Contradicted(pinned, replacement, ConflictType.WorldProgression.INSTANCE));

        Evidence.Superseded superseded = assertInstanceOf(Evidence.Superseded.class, disagreement);
        assertEquals(PropositionStatus.ACTIVE, superseded.getPrevious().get(0).getStatus());
        assertEquals(PropositionStatus.ACTIVE, superseded.getCurrent().getStatus());
    }

    @Test
    void scoredHitsKeepTheirListPositionAsRankWhenOneIsSkipped() {
        List<SimpleSimilaritySearchResult<Proposition>> hits = List.of(
                new SimpleSimilaritySearchResult<>(claim("p-1", "Ada lives in London", PropositionStatus.ACTIVE), 0.9),
                new SimpleSimilaritySearchResult<>(claim("p-2", " ", PropositionStatus.ACTIVE), 0.8),
                new SimpleSimilaritySearchResult<>(claim("p-3", "Ada works at the library", PropositionStatus.ACTIVE), 0.7));

        Evidence.Recorded recorded = assertInstanceOf(
                Evidence.Recorded.class, converter.fromSimilarityResults(hits, Searched.of("support-team")));

        assertEquals(List.of("p-1", "p-3"), propositionIds(recorded));
        assertEquals(List.of(1, 3), ranks(recorded));
    }

    @Test
    void claimsFromARetrieverKeepTheirListPositionAsRankWhenOneIsSkipped() {
        List<Proposition> claims = List.of(
                claim("p-1", "Ada lives in London", PropositionStatus.ACTIVE),
                claim("p-2", "", PropositionStatus.ACTIVE),
                claim("p-3", "Ada works at the library", PropositionStatus.ACTIVE));

        Evidence.Recorded recorded = assertInstanceOf(
                Evidence.Recorded.class,
                converter.fromPropositions(claims, Searched.of("support-team"), RetrieverId.KEYWORD));

        assertEquals(List.of("p-1", "p-3"), propositionIds(recorded));
        assertEquals(List.of(1, 3), ranks(recorded));
    }

    @Test
    void aNullHitIsRejectedWithItsIndex() {
        List<SimpleSimilaritySearchResult<Proposition>> hits = Arrays.asList(
                new SimpleSimilaritySearchResult<>(claim("p-1", "Ada lives in London", PropositionStatus.ACTIVE), 0.9),
                null);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> converter.fromSimilarityResults(hits, Searched.of("support-team")));

        assertTrue(thrown.getMessage().contains("hits[1]"), thrown.getMessage());
    }

    @Test
    void aHitWithNoClaimIsRejectedWithItsIndex() {
        List<SimpleSimilaritySearchResult<Proposition>> hits = List.of(
                new SimpleSimilaritySearchResult<>(claim("p-1", "Ada lives in London", PropositionStatus.ACTIVE), 0.9),
                new SimpleSimilaritySearchResult<>(null, 0.8));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> converter.fromSimilarityResults(hits, Searched.of("support-team")));

        assertEquals("hits[1].match must not be null", thrown.getMessage());
    }

    @Test
    void aListOfClaimsReadsTheClockOnce() {
        CountingClock clock = new CountingClock(RECORDED_AT.plus(Duration.ofDays(1)));
        List<Proposition> claims = List.of(decayingClaim("p-1"), decayingClaim("p-2"));

        Evidence.Recorded recorded = assertInstanceOf(Evidence.Recorded.class,
                converter.withClock(clock).fromPropositions(claims, Searched.of("support-team")));

        assertEquals(1, clock.reads);
        assertEquals(recorded.getItems().get(0).getEffectiveConfidence(),
                recorded.getItems().get(1).getEffectiveConfidence());
    }

    @Test
    void aContradictionReadsTheClockOnce() {
        CountingClock clock = new CountingClock(RECORDED_AT.plus(Duration.ofDays(1)));

        converter.withClock(clock).fromContradiction(new RevisionResult.Contradicted(
                decayingClaim("p-1"), decayingClaim("p-2"), ConflictType.Contradiction.INSTANCE));

        assertEquals(1, clock.reads);
    }

    @Test
    void revisedAtIsTheRecordedContentRevision() {
        Proposition stored = Proposition.create(
                "p-1", "support-team", "Ada lives in London", List.of(), 0.8, 0.0, null, List.of(),
                RECORDED_AT, RECORDED_AT.plus(Duration.ofDays(10)), PropositionStatus.ACTIVE);

        assertNull(converter.item(stored).getRevisedAt());

        Proposition revised = stored.withText("Ada lives in Camden");
        assertEquals(revised.getContentRevised(), converter.item(revised).getRevisedAt());
    }

    @Test
    void anItemReadsBackTheReinforceCountAndTemporalMetadataOfItsClaim() {
        TemporalMetadata temporal = new TemporalMetadata(RECORDED_AT, RECORDED_AT, null, null, List.of("p-0"), List.of());
        Proposition reinforced = Proposition.create(
                "p-1", "support-team", "Ada lives in London", List.of(), 0.8, 0.0, 0.5, null, List.of(),
                RECORDED_AT, RECORDED_AT, RECORDED_AT, PropositionStatus.ACTIVE, 0, List.of(), 3)
                .withTemporal(temporal);

        EvidenceItem item = converter.item(reinforced);

        assertEquals(3, item.getReinforceCount());
        assertEquals(temporal, item.getTemporal());
    }

    @Test
    void nullAndBlankGroundingIdsAreLeftOut() {
        Proposition grounded = Proposition.create(
                "p-1", "support-team", "Ada lives in London", List.of(), 0.8, 0.0, null,
                Arrays.asList("chunk-1", null, " "), RECORDED_AT, RECORDED_AT, PropositionStatus.ACTIVE);

        assertEquals(List.of("chunk-1"), converter.item(grounded).getCitation().getChunkIds());
    }

    @Test
    void aLaterPageIsRankedFromItsOffsetOnAFixedClock() {
        Instant now = Instant.parse("2026-01-11T00:00:00Z");
        Proposition london = Proposition.draft("support-team", "Ada lives in London", 0.8)
                .withId("claim-11")
                .withCreated(now)
                .toProposition();
        Proposition library = Proposition.draft("support-team", "Ada works at the library", 0.8)
                .withId("claim-12")
                .withCreated(now)
                .toProposition();
        List<SimpleSimilaritySearchResult<Proposition>> page = List.of(
                new SimpleSimilaritySearchResult<>(london, 0.6),
                new SimpleSimilaritySearchResult<>(library, 0.5));
        int offset = 10;

        EvidenceConverter pinned = EvidenceConverter.create().withClock(Clock.fixed(now, ZoneOffset.UTC));

        List<EvidenceItem> items = new ArrayList<>();
        for (int i = 0; i < page.size(); i++) {
            items.add(pinned.item(page.get(i), RetrieverId.VECTOR, offset + i + 1));
        }
        Evidence evidence = Evidence.of(items, Searched.of("support-team"));

        assertEquals(List.of(11, 12), ranks(assertInstanceOf(Evidence.Recorded.class, evidence)));
    }

    @Test
    void anAbstractionConvertsToTheSameItemThroughEveryMethod() {
        Proposition abstraction = abstraction("abs-1", List.of("p-1", "p-2"));
        PropositionLineage lineage = lineage(abstraction, List.of(
                claim("p-1", "Ada lives in London", PropositionStatus.ACTIVE),
                claim("p-2", "Ada works at the library", PropositionStatus.ACTIVE)));

        List<EvidenceItem> items = List.of(
                converter.item(abstraction),
                single(converter.fromPropositions(List.of(abstraction), Searched.of("support-team"))),
                single(converter.fromSimilarityResults(
                        List.of(new SimpleSimilaritySearchResult<>(abstraction, 0.9)), Searched.of("support-team"))),
                converter.item(lineage));

        for (EvidenceItem item : items) {
            assertEquals("Ada is a mathematician", item.getText());
            assertTrue(item.getCitation().sameClaimAs(Citation.of("abs-1", "support-team")));
            assertEquals(0.8, item.getConfidence());
            assertEquals(List.of("p-1", "p-2"), item.getDerivedFromIds());
            assertTrue(item.isDerived());
        }
        assertEquals(List.of("p-1", "p-2"), propositionIds(items.get(3).getDerivedFrom()));
        assertTrue(items.get(0).getDerivedFrom().isEmpty());
    }

    @Test
    void aListMixingAnObservationAndAnAbstractionIsOneRecordedInInputOrder() {
        List<Proposition> claims = List.of(
                abstraction("abs-1", List.of("p-1")),
                claim("p-1", "Ada lives in London", PropositionStatus.ACTIVE));

        Evidence.Recorded recorded = assertInstanceOf(
                Evidence.Recorded.class, converter.fromPropositions(claims, Searched.of("support-team")));

        assertEquals(List.of("abs-1", "p-1"), propositionIds(recorded));
        assertTrue(recorded.getItems().get(0).isDerived());
        assertFalse(recorded.getItems().get(1).isDerived());
    }

    @Test
    void aLineageConvertsToRecordedEvidenceWithOneItem() {
        PropositionLineage lineage = lineage(abstraction("abs-1", List.of("p-1")),
                List.of(claim("p-1", "Ada lives in London", PropositionStatus.ACTIVE)));

        Evidence.Recorded recorded = converter.fromLineage(lineage);

        assertEquals(List.of(converter.item(lineage)), recorded.getItems());
    }

    @Test
    void theLineageStandingWinsOverItsClaim() {
        TemporalMetadata invalidated = new TemporalMetadata(null, null, null, RECORDED_AT, List.of(), List.of());
        PropositionLineage lineage = new PropositionLineage(
                claim("p-1", "Ada lives in London", PropositionStatus.ACTIVE),
                List.of(), List.of("chunk-9"), List.of(), 4, PropositionStatus.SUPERSEDED, invalidated);

        EvidenceItem item = converter.item(lineage);

        assertEquals(PropositionStatus.SUPERSEDED, item.getStatus());
        assertEquals(4, item.getReinforceCount());
        assertEquals(invalidated, item.getTemporal());
        assertEquals(List.of("chunk-9"), item.getCitation().getChunkIds());
        assertEquals(0.0, item.getEffectiveConfidence());
        assertEquals(0.8, item.getConfidence());
    }

    @Test
    void aLineageWithNoTemporalMetadataUsesItsClaims() {
        TemporalMetadata invalidated = new TemporalMetadata(null, null, null, RECORDED_AT, List.of(), List.of());
        Proposition retracted = claim("p-1", "Ada lives in London", PropositionStatus.ACTIVE).withTemporal(invalidated);
        PropositionLineage lineage = new PropositionLineage(
                retracted, List.of(), List.of(), List.of(), 0, PropositionStatus.ACTIVE, null);

        EvidenceItem item = converter.item(lineage);

        assertEquals(invalidated, item.getTemporal());
        assertEquals(0.0, item.getEffectiveConfidence());
    }

    @Test
    void aNegativeLineageReinforceCountIsRejected() {
        PropositionLineage lineage = new PropositionLineage(
                claim("p-1", "Ada lives in London", PropositionStatus.ACTIVE),
                List.of(), List.of(), List.of(), -1, PropositionStatus.ACTIVE, null);

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> converter.item(lineage));

        assertEquals("lineage.reinforceCount must not be negative, was -1", thrown.getMessage());
    }

    @Test
    void aNullLoadedSourceIsRejectedWithItsIndex() {
        PropositionLineage lineage = lineage(abstraction("abs-1", List.of("p-1")),
                Arrays.asList(claim("p-1", "Ada lives in London", PropositionStatus.ACTIVE), null));

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> converter.fromLineage(lineage));

        assertEquals("lineage.sources[1] must not be null", thrown.getMessage());
    }

    @Test
    void aLoadedSourceTheClaimDoesNotNameIsRejected() {
        PropositionLineage lineage = lineage(abstraction("abs-1", List.of("p-1")),
                List.of(claim("p-9", "Ada lives in London", PropositionStatus.ACTIVE)));

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> converter.fromLineage(lineage));

        assertEquals("lineage.sources[0] is claim 'p-9', which the source ids of claim 'abs-1' do not name",
                thrown.getMessage());
    }

    @Test
    void aLoadedSourceOnAnObservationIsRejected() {
        PropositionLineage lineage = lineage(claim("p-3", "Ada lives in London", PropositionStatus.ACTIVE),
                List.of(claim("p-1", "Ada lives in Marylebone", PropositionStatus.ACTIVE)));

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> converter.item(lineage));

        assertEquals("lineage.sources[0] is claim 'p-1', which the source ids of claim 'p-3' do not name",
                thrown.getMessage());
    }

    @Test
    void aRepeatedSourceAndABlankSourceAreDropped() {
        PropositionLineage lineage = lineage(abstraction("abs-1", List.of("p-1", "p-2")), List.of(
                claim("p-1", "Ada lives in London", PropositionStatus.ACTIVE),
                claim("p-2", " ", PropositionStatus.ACTIVE),
                claim("p-1", "Ada lives in Camden", PropositionStatus.ACTIVE)));

        EvidenceItem item = converter.item(lineage);

        assertEquals(List.of("p-1"), propositionIds(item.getDerivedFrom()));
        assertEquals("Ada lives in London", item.getDerivedFrom().get(0).getText());
        assertEquals(List.of("p-1", "p-2"), item.getDerivedFromIds());
    }

    @Test
    void anAbstractionWithOnlyBlankSourceIdsConvertsWithNoSourceIdsEverywhere() {
        Proposition abstraction = abstraction("abs-1", List.of(" ", ""));

        List<EvidenceItem> items = List.of(
                converter.item(abstraction),
                single(converter.fromPropositions(List.of(abstraction), Searched.of("support-team"))),
                single(converter.fromSimilarityResults(
                        List.of(new SimpleSimilaritySearchResult<>(abstraction, 0.9)), Searched.of("support-team"))),
                converter.item(lineage(abstraction, List.of())));

        for (EvidenceItem item : items) {
            assertTrue(item.getDerivedFromIds().isEmpty());
            assertFalse(item.isDerived());
        }
    }

    @Test
    void aLineageWithTwoSourcesReadsTheClockOnce() {
        CountingClock clock = new CountingClock(RECORDED_AT.plus(Duration.ofDays(1)));
        PropositionLineage lineage = lineage(abstraction("abs-1", List.of("p-1", "p-2")),
                List.of(decayingClaim("p-1"), decayingClaim("p-2")));

        EvidenceItem item = converter.withClock(clock).item(lineage);

        assertEquals(1, clock.reads);
        assertEquals(item.getDerivedFrom().get(0).getEffectiveConfidence(),
                item.getDerivedFrom().get(1).getEffectiveConfidence());
    }

    /**
     * Reads the one item of recorded evidence.
     *
     * @param evidence the evidence to read, which must be recorded with one item
     * @return the item
     */
    private static EvidenceItem single(Evidence evidence) {
        List<EvidenceItem> items = assertInstanceOf(Evidence.Recorded.class, evidence).getItems();
        assertEquals(1, items.size());
        return items.get(0);
    }

    /**
     * Lists the claim id of each item.
     *
     * @param items the items to read
     * @return the claim ids, in item order
     */
    private static List<String> propositionIds(List<EvidenceItem> items) {
        return items.stream().map(item -> item.getCitation().getPropositionId()).toList();
    }

    /**
     * Creates an active abstraction at level 1 in the `support-team` context with no decay.
     *
     * @param id the claim id
     * @param sourceIds ids of the claims it was abstracted from
     * @return the abstraction
     */
    private static Proposition abstraction(String id, List<String> sourceIds) {
        return Proposition.create(
                id, "support-team", "Ada is a mathematician", List.of(), 0.8, 0.0, 0.5, null, List.of(),
                RECORDED_AT, RECORDED_AT, RECORDED_AT, PropositionStatus.ACTIVE, 1, sourceIds);
    }

    /**
     * Builds a lineage that takes its standing from the claim, with no provenance or chunk ids.
     *
     * @param proposition the claim the lineage is for
     * @param sources the loaded source claims
     * @return the lineage
     */
    private static PropositionLineage lineage(Proposition proposition, List<Proposition> sources) {
        return new PropositionLineage(
                proposition, List.of(), List.of(), sources, proposition.getReinforceCount(),
                proposition.getStatus(), proposition.getTemporal());
    }

    /**
     * Lists the claim id of each item in recorded evidence.
     *
     * @param recorded the evidence to read
     * @return the claim ids, in item order
     */
    private static List<String> propositionIds(Evidence.Recorded recorded) {
        return recorded.getItems().stream().map(item -> item.getCitation().getPropositionId()).toList();
    }

    /**
     * Lists the rank of the single retrieval on each item in recorded evidence.
     *
     * @param recorded the evidence to read
     * @return the ranks, in item order
     */
    private static List<Integer> ranks(Evidence.Recorded recorded) {
        return recorded.getItems().stream().map(item -> item.getRetrievals().get(0).getRank()).toList();
    }

    /**
     * Creates an active claim that decays, last revised and accessed at {@code RECORDED_AT}.
     *
     * @param id the claim id
     * @return the claim
     */
    private static Proposition decayingClaim(String id) {
        return Proposition.create(
                id, "support-team", "Ada lives in London", List.of(), 0.8, 0.1, null, List.of(),
                RECORDED_AT, RECORDED_AT, PropositionStatus.ACTIVE);
    }

    /**
     * A clock that counts how often it is read and moves 30 days forward after each read.
     */
    private static final class CountingClock extends Clock {

        private Instant next;

        private int reads;

        /**
         * Creates a clock whose first read returns the given instant.
         *
         * @param first the instant the first read returns
         */
        CountingClock(Instant first) {
            this.next = first;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            reads++;
            Instant now = next;
            next = next.plus(Duration.ofDays(30));
            return now;
        }
    }

    /**
     * Creates a claim in the `support-team` context with fixed timestamps and no decay.
     *
     * @param id the claim id
     * @param text the claim text
     * @param status the lifecycle status
     * @return the claim
     */
    private static Proposition claim(String id, String text, PropositionStatus status) {
        return Proposition.create(
                id, "support-team", text, List.of(), 0.8, 0.0, null, List.of(), RECORDED_AT, RECORDED_AT, status);
    }
}

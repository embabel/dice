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

import com.embabel.common.core.types.SimilarityResult;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.spi.ConflictType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shows how a Java caller builds, reads and switches over the evidence types.
 */
class EvidenceJavaInteropTest {

    @Test
    void citationReadsBackItsBuilderValues() {
        Citation citation = Citation.of("p-17", "support-team")
                .withChunkIds(List.of("chunk-4"))
                .withUri("https://example.org/tickets/4");

        assertEquals("p-17", citation.getPropositionId());
        assertEquals("support-team", citation.getContextIdValue());
        assertEquals(List.of("chunk-4"), citation.getChunkIds());
        assertEquals("https://example.org/tickets/4", citation.getUri());
    }

    @Test
    void itemReadsBackConfidenceStatusScoreAndRetrieval() {
        EvidenceItem item = EvidenceItem.of("Ada wrote the first program", Citation.of("p-17", "support-team"), 0.9)
                .withEffectiveConfidence(0.7)
                .withScore(0.016)
                .withRetrieval(Retrieval.of(RetrieverId.VECTOR).withRank(1).withScore(0.83));

        assertEquals(0.9, item.getConfidence());
        assertEquals(0.7, item.getEffectiveConfidence());
        assertEquals(PropositionStatus.ACTIVE, item.getStatus());
        assertEquals(Double.valueOf(0.016), item.getScore());
        assertEquals(1, item.getRetrievals().size());
        assertEquals(Integer.valueOf(1), item.getRetrievals().get(0).getRank());
        assertFalse(item.isDerived());
    }

    @Test
    void graphWalkRetrievalCountsHopsFromEntityPath() {
        Retrieval walked = Retrieval.of(RetrieverId.GRAPH_WALK)
                .withEntityPath(List.of("ada", "analytical-engine", "babbage"));

        assertEquals(Integer.valueOf(2), walked.getHops());
    }

    @Test
    void recordedRejectsEmptyList() {
        assertThrows(IllegalArgumentException.class, () -> Evidence.recorded(List.of()));
    }

    @Test
    void conflictingRejectsFewerThanTwoItems() {
        EvidenceItem item = EvidenceItem.of("Ada wrote the first program", Citation.of("p-17", "support-team"), 0.9);

        assertThrows(IllegalArgumentException.class,
                () -> Evidence.conflicting(List.of(item), ConflictType.Contradiction.INSTANCE));
    }

    @Test
    void factoriesBuildTheMatchingNestedType() {
        EvidenceItem item = EvidenceItem.of("Ada wrote the first program", Citation.of("p-17", "support-team"), 0.9);
        EvidenceItem other = EvidenceItem.of("Ada wrote a note", Citation.of("p-18", "support-team"), 0.6);

        Evidence.Recorded recorded = Evidence.recorded(List.of(item));
        assertEquals(List.of(item), recorded.getItems());

        Evidence.Derived derived = Evidence.derived(
                        "Ada is a mathematician",
                        0.8,
                        DerivationRule.of("prolog").withRuleId("grandparent").withExpression("grandparent(X, Y)"),
                        List.of("p-17"))
                .withInputs(List.of(item))
                .withCitation(Citation.of("p-19", "support-team"));
        assertEquals("prolog", derived.getRule().getMethod());
        assertEquals(List.of(item), derived.getInputs());
        assertEquals("p-19", derived.getCitation().getPropositionId());

        Evidence.Derived settled = derived.withEffectiveConfidence(0.4).withStatus(PropositionStatus.SUPERSEDED);
        assertEquals(0.4, settled.getEffectiveConfidence());
        assertEquals(PropositionStatus.SUPERSEDED, settled.getStatus());

        Evidence.Missing missing = Evidence.missing(Searched.of("support-team"));
        assertEquals("support-team", missing.getSearched().getContextIdValues().iterator().next());

        Evidence.Conflicting conflicting =
                Evidence.conflicting(List.of(item, other), ConflictType.Contradiction.INSTANCE);
        assertEquals(2, conflicting.getItems().size());
    }

    @Test
    void switchReadsOneValueOfEachKind() {
        EvidenceItem item = EvidenceItem.of("Ada wrote the first program", Citation.of("p-17", "support-team"), 0.9);
        EvidenceItem other = EvidenceItem.of("Ada wrote a note", Citation.of("p-18", "support-team"), 0.6);

        Evidence recorded = Evidence.recorded(List.of(item));
        Evidence derived = Evidence.derived("Ada is a mathematician", 0.8, DerivationRule.ABSTRACTION, List.of("p-17"));
        Evidence missing = Evidence.missing(Searched.of("support-team").withQuery("who is Ada"));
        Evidence conflicting = Evidence.conflicting(List.of(item, other), ConflictType.Contradiction.INSTANCE);

        assertEquals("recorded 1", describe(recorded));
        assertEquals("derived Ada is a mathematician", describe(derived));
        assertEquals("missing who is Ada", describe(missing));
        assertEquals("conflicting 2", describe(conflicting));
    }

    @Test
    void evidenceOfNoItemsIsMissingForTheSearch() {
        Searched searched = Searched.of("support-team").withQuery("who is Ada").withFilter("minConfidence", "0.5");

        Evidence evidence = Evidence.of(List.of(), searched);

        Evidence.Missing missing = assertInstanceOf(Evidence.Missing.class, evidence);
        assertEquals("who is Ada", missing.getSearched().getQuery());
        assertEquals(Set.of("support-team"), missing.getSearched().getContextIdValues());
        assertEquals(Map.of("minConfidence", "0.5"), missing.getSearched().getFilters());
    }

    @Test
    void converterReturnsMissingForEmptyPropositionsAndHits() {
        EvidenceConverter converter = EvidenceConverter.create();
        Searched searched = Searched.of("support-team").withQuery("who is Ada");

        assertInstanceOf(Evidence.Missing.class, converter.fromPropositions(List.of(), searched));
        assertInstanceOf(Evidence.Missing.class, converter.fromSimilarityResults(List.of(), searched));
    }

    @Test
    void providerInterfaceAcceptsALambda() {
        EvidenceProvider<String> provider =
                query -> Evidence.missing(Searched.of("support-team").withQuery(query));

        Evidence evidence = provider.evidence("who is Ada");

        Evidence.Missing missing = assertInstanceOf(Evidence.Missing.class, evidence);
        assertEquals("who is Ada", missing.getSearched().getQuery());
    }

    @Test
    void propositionConvertsToRecordedItemWithKeywordRank() {
        Proposition claim = claim();

        Evidence evidence = converter().fromPropositions(
                List.of(claim),
                Searched.of("support-team").withQuery("who is Ada"),
                RetrieverId.KEYWORD);

        Evidence.Recorded recorded = assertInstanceOf(Evidence.Recorded.class, evidence);
        assertEquals(1, recorded.getItems().size());
        EvidenceItem item = recorded.getItems().get(0);
        assertEquals("Ada wrote the first program", item.getText());
        assertEquals("claim-1", item.getCitation().getPropositionId());
        assertEquals(Integer.valueOf(1), item.getRetrievals().get(0).getRank());
    }

    @Test
    void similarityResultConvertsToRecordedItemWithScore() {
        Proposition claim = claim();

        Evidence evidence = converter().fromSimilarityResults(
                List.of(SimilarityResult.create(claim, 0.83)),
                Searched.of("support-team").withQuery("who is Ada"));

        Evidence.Recorded recorded = assertInstanceOf(Evidence.Recorded.class, evidence);
        assertEquals(Double.valueOf(0.83), recorded.getItems().get(0).getScore());
    }

    /**
     * Describes an evidence value by its kind and one property of that kind.
     *
     * @param evidence the value to describe
     * @return a short text that starts with the kind name
     */
    private static String describe(Evidence evidence) {
        return switch (evidence) {
            case Evidence.Recorded recorded -> "recorded " + recorded.getItems().size();
            case Evidence.Derived derived -> "derived " + derived.getConclusion();
            case Evidence.Missing missing -> "missing " + missing.getSearched().getQuery();
            case Evidence.Conflicting conflicting -> "conflicting " + conflicting.getItems().size();
        };
    }

    /**
     * Creates a converter that reads the clock at a fixed instant, so decayed confidence does not
     * depend on when the test runs.
     *
     * @return the converter
     */
    private static EvidenceConverter converter() {
        return EvidenceConverter.create()
                .withClock(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    /**
     * Creates a claim with fixed timestamps and one grounding chunk.
     *
     * @return the claim
     */
    private static Proposition claim() {
        Instant recordedAt = Instant.parse("2026-01-01T00:00:00Z");
        return Proposition.create(
                "claim-1",
                "support-team",
                "Ada wrote the first program",
                List.of(),
                0.9,
                0.0,
                null,
                List.of("chunk-1"),
                recordedAt,
                recordedAt,
                PropositionStatus.ACTIVE);
    }
}

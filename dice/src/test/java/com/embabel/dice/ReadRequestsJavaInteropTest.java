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

import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.store.ReadCriteria;
import com.embabel.dice.store.ReadCriterion;
import com.embabel.dice.store.ReadKeywordQuery;
import com.embabel.dice.store.ReadOrder;
import com.embabel.dice.store.ReadQuery;
import com.embabel.dice.store.ReadVectorSearchRequest;
import com.embabel.dice.store.SimilarityScore;
import com.embabel.dice.store.StoreCapability;
import com.embabel.dice.store.UnsupportedCapabilityException;
import com.embabel.dice.store.VectorSearchRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The criteria and the three requests of a scoped read, built from Java with a chain, and the
 * check a store makes on the criteria and the orders it applies.
 */
class ReadRequestsJavaInteropTest {

    private static final Set<ReadCriterion> APPLIED_CRITERIA =
            EnumSet.of(ReadCriterion.ENTITY_ID, ReadCriterion.STATUSES, ReadCriterion.MIN_CONFIDENCE,
                    ReadCriterion.EFFECTIVE_CONFIDENCE_AS_OF, ReadCriterion.DECAY_K);

    private static final Set<ReadOrder> APPLIED_ORDERS = EnumSet.of(ReadOrder.CREATED_DESC);

    @Test
    void criteriaAreBuiltWithAChain() {
        ReadCriteria active = ReadCriteria.empty()
                .withStatus(PropositionStatus.ACTIVE)
                .withMinConfidence(0.6);
        ReadCriteria aboutAlice = active
                .withEntityId("alice")
                .withAnyEntity("alice", "bob")
                .withPinned(true);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");

        assertEquals(Set.of(PropositionStatus.ACTIVE), active.getStatuses());
        assertEquals(Double.valueOf(0.6), active.getMinConfidence());
        assertNull(active.getEntityId());
        assertEquals("alice", aboutAlice.getEntityId());
        assertEquals(List.of("alice", "bob"), aboutAlice.getAnyEntityIds());
        assertEquals(Boolean.TRUE, aboutAlice.getPinned());
        assertEquals(ReadCriteria.DEFAULT_DECAY_K, ReadCriteria.empty().getDecayK());
        assertEquals(active, ReadCriteria.empty()
                .withMinConfidence(0.6)
                .withStatuses(Set.of(PropositionStatus.ACTIVE)));
        assertEquals(ReadCriteria.empty().withStatuses(Set.of(PropositionStatus.ACTIVE, PropositionStatus.PROMOTED)),
                ReadCriteria.empty().withStatuses(PropositionStatus.ACTIVE, PropositionStatus.PROMOTED));
        assertEquals(ReadCriteria.empty().withCreatedAfter(start).withCreatedBefore(start.plusSeconds(60)),
                ReadCriteria.empty().withCreatedBetween(start, start.plusSeconds(60)));
        assertEquals(Set.of(ReadCriterion.STATUSES, ReadCriterion.MIN_CONFIDENCE), active.getSpecified());
    }

    @Test
    void criteriaRejectAValueThatSelectsNothingDefinite() {
        ReadCriteria empty = ReadCriteria.empty();

        assertThrows(IllegalArgumentException.class, () -> empty.withAnyEntityIds(Arrays.asList("alice", null)));
        assertThrows(IllegalArgumentException.class, () -> empty.withAnyEntity("alice", (String) null));
        assertThrows(IllegalArgumentException.class, () -> empty.withAllEntityIds(List.of()));
        assertThrows(IllegalArgumentException.class, () -> empty.withStatuses(Set.of()));
        assertThrows(IllegalArgumentException.class, () -> empty.withMinEffectiveConfidence(Double.NaN));
        assertThrows(NullPointerException.class, () -> empty.withEntityId(null));
    }

    @Test
    void aStoreThrowsForACriterionItDoesNotApply() {
        ReadCriteria aboutAlice = ReadCriteria.empty().withEntityId("alice");
        ReadCriteria pinned = aboutAlice.withPinned(true);

        aboutAlice.requireOnly(APPLIED_CRITERIA, StoreCapability.SCOPED_READS);
        ReadCriteria.empty().requireOnly(EnumSet.noneOf(ReadCriterion.class), StoreCapability.SCOPED_READS);
        UnsupportedCapabilityException refusal = assertThrows(UnsupportedCapabilityException.class,
                () -> pinned.requireOnly(APPLIED_CRITERIA, StoreCapability.SCOPED_READS));

        assertEquals(StoreCapability.SCOPED_READS, refusal.getCapability());
        assertTrue(refusal.getMessage().contains("PINNED"));
    }

    @Test
    void aQueryAddsAnOrderAndALimitToCriteria() {
        ReadCriteria aboutAlice = ReadCriteria.empty().withEntityId("alice");
        ReadQuery recent = ReadQuery.of(aboutAlice).orderedByCreated().withLimit(20);

        assertSame(aboutAlice, recent.getCriteria());
        assertEquals(ReadOrder.CREATED_DESC, recent.getOrder());
        assertEquals(Integer.valueOf(20), recent.getLimit());
        assertNull(ReadQuery.of(aboutAlice).getLimit());
        assertEquals(recent, ReadQuery.of(aboutAlice).withOrder(ReadOrder.CREATED_DESC).withLimit(20));
        assertThrows(IllegalArgumentException.class, () -> ReadQuery.of(aboutAlice).withLimit(0));

        recent.requireOnly(APPLIED_CRITERIA, APPLIED_ORDERS, StoreCapability.SCOPED_READS);
        ReadQuery.of(aboutAlice).requireOnly(APPLIED_CRITERIA, EnumSet.noneOf(ReadOrder.class), StoreCapability.SCOPED_READS);
        UnsupportedCapabilityException refusal = assertThrows(UnsupportedCapabilityException.class,
                () -> recent.orderedByImportance()
                        .requireOnly(APPLIED_CRITERIA, APPLIED_ORDERS, StoreCapability.SCOPED_READS));
        assertTrue(refusal.getMessage().contains("IMPORTANCE_DESC"));
    }

    @Test
    void aKeywordRequestHoldsLowerCasedTokensWithNoDuplicates() {
        ReadCriteria aboutAlice = ReadCriteria.empty().withEntityId("alice");
        ReadKeywordQuery mentions = ReadKeywordQuery.forTokens("Peanut", "allergy", "PEANUT")
                .withCriteria(aboutAlice)
                .withOrder(ReadOrder.CREATED_DESC)
                .withLimit(10);

        assertEquals(List.of("peanut", "allergy"), mentions.getTokens());
        assertSame(aboutAlice, mentions.getCriteria());
        assertEquals(ReadOrder.CREATED_DESC, mentions.getOrder());
        assertEquals(Integer.valueOf(10), mentions.getLimit());
        assertEquals(ReadKeywordQuery.forTokens("peanut"), ReadKeywordQuery.forTokens(List.of("PEANUT")));
        assertThrows(IllegalArgumentException.class, () -> ReadKeywordQuery.forTokens(List.of()));
        assertThrows(IllegalArgumentException.class, () -> ReadKeywordQuery.forTokens(Arrays.asList("peanut", null)));
        assertThrows(IllegalArgumentException.class, () -> ReadKeywordQuery.forTokens("peanut", " "));
        assertThrows(IllegalArgumentException.class, () -> mentions.withLimit(0));
        mentions.requireOnly(APPLIED_CRITERIA, APPLIED_ORDERS, StoreCapability.SCOPED_READS);
    }

    @Test
    void aSimilaritySearchRequestIsBuiltWithAChain() {
        ReadCriteria aboutAlice = ReadCriteria.empty().withEntityId("alice");
        ReadVectorSearchRequest request = ReadVectorSearchRequest.forText("What does Alice eat?")
                .withCriteria(aboutAlice)
                .withTopK(5)
                .withMinScore(SimilarityScore.ofCosine(0.6));

        assertEquals("What does Alice eat?", request.getText());
        assertSame(aboutAlice, request.getCriteria());
        assertEquals(5, request.getTopK());
        assertEquals(SimilarityScore.ofCosine(0.6), request.getMinScore());
        assertEquals(VectorSearchRequest.DEFAULT_TOP_K, ReadVectorSearchRequest.forText("x").getTopK());
        assertNull(ReadVectorSearchRequest.forText("x").getMinScore());
        assertThrows(IllegalArgumentException.class, () -> ReadVectorSearchRequest.forText(" "));
        assertThrows(IllegalArgumentException.class, () -> request.withTopK(0));
        request.requireOnly(APPLIED_CRITERIA, StoreCapability.SCOPED_VECTOR_SEARCH);
    }
}

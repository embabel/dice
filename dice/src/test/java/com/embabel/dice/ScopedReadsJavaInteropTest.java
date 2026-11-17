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

import com.embabel.agent.core.DataDictionary;
import com.embabel.dice.common.SuggestedEntity;
import com.embabel.dice.common.resolver.ScopedCandidateSearcher;
import com.embabel.dice.common.resolver.SearchResult;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.PropositionStore;
import com.embabel.dice.store.CapabilityReporting;
import com.embabel.dice.store.ContextReadScope;
import com.embabel.dice.store.ReadBudget;
import com.embabel.dice.store.ReadBudgetExceededException;
import com.embabel.dice.store.ReadBudgetResource;
import com.embabel.dice.store.ReadCriteria;
import com.embabel.dice.store.ReadCriterion;
import com.embabel.dice.store.ReadKeywordQuery;
import com.embabel.dice.store.ReadOrder;
import com.embabel.dice.store.ReadQuery;
import com.embabel.dice.store.ReadRefusedException;
import com.embabel.dice.store.ReadScopeViolationException;
import com.embabel.dice.store.ReadVectorSearchRequest;
import com.embabel.dice.store.ScopedReadSpec;
import com.embabel.dice.store.ScopedReads;
import com.embabel.dice.store.ScopedVectorSearch;
import com.embabel.dice.store.StoreCapabilities;
import com.embabel.dice.store.StoreCapability;
import com.embabel.dice.store.UnsupportedCapabilityException;
import com.embabel.dice.store.VectorMatch;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scoped reads used from Java: a host narrows a spec, a caller finds the reads on a store and
 * reads under the spec and a budget, and a store author makes the checks in their order.
 */
class ScopedReadsJavaInteropTest {

    private static final ContextReadScope SCOPE = ContextReadScope.ofValues("alice-session", "team-handbook");

    /**
     * Holds no proposition and selects nothing, so it shows only the checks a store makes and
     * their order. It stands for a store whose database counted a fixed number of candidates. A
     * store that holds data selects by the contexts and by every filter of the spec.
     */
    private static final class CheckingReads implements ScopedReads, ScopedVectorSearch {

        private static final Set<ReadCriterion> APPLIED_CRITERIA =
                EnumSet.of(ReadCriterion.ENTITY_ID, ReadCriterion.STATUSES, ReadCriterion.MIN_CONFIDENCE,
                    ReadCriterion.EFFECTIVE_CONFIDENCE_AS_OF, ReadCriterion.DECAY_K);

        private static final Set<ReadOrder> APPLIED_ORDERS = EnumSet.of(ReadOrder.CREATED_DESC);

        private final long candidates;
        private final long vectorCandidates;
        private int ran;

        private CheckingReads(long candidates, long vectorCandidates) {
            this.candidates = candidates;
            this.vectorCandidates = vectorCandidates;
        }

        @Override
        public List<Proposition> query(ScopedReadSpec spec, ReadQuery query, ReadBudget budget) {
            spec.requireOnly(APPLIED_CRITERIA, StoreCapability.SCOPED_READS);
            query.requireOnly(APPLIED_CRITERIA, APPLIED_ORDERS, StoreCapability.SCOPED_READS);
            Integer limit = query.getLimit();
            if (limit != null) {
                budget.requireRows(limit);
            }
            ran++;
            if (limit == null) {
                budget.requireRows(candidates);
            }
            return List.of();
        }

        @Override
        public int count(ScopedReadSpec spec, ReadCriteria criteria, ReadBudget budget) {
            spec.requireOnly(APPLIED_CRITERIA, StoreCapability.SCOPED_READS);
            criteria.requireOnly(APPLIED_CRITERIA, StoreCapability.SCOPED_READS);
            ran++;
            // The check takes the long the database returned. The cast comes after it.
            budget.requireRows(candidates);
            return (int) candidates;
        }

        @Override
        public List<Proposition> matchKeywords(ScopedReadSpec spec, ReadKeywordQuery request, ReadBudget budget) {
            spec.requireOnly(APPLIED_CRITERIA, StoreCapability.SCOPED_READS);
            request.requireOnly(APPLIED_CRITERIA, APPLIED_ORDERS, StoreCapability.SCOPED_READS);
            Integer limit = request.getLimit();
            if (limit != null) {
                budget.requireRows(limit);
            }
            ran++;
            if (limit == null) {
                budget.requireRows(candidates);
            }
            return List.of();
        }

        @Override
        public List<VectorMatch> findNearest(
                ScopedReadSpec spec, ReadVectorSearchRequest request, ReadBudget budget) {
            spec.requireOnly(APPLIED_CRITERIA, StoreCapability.SCOPED_VECTOR_SEARCH);
            request.requireOnly(APPLIED_CRITERIA, StoreCapability.SCOPED_VECTOR_SEARCH);
            budget.requireVectorSearch();
            budget.requireRows(request.getTopK());
            ran++;
            budget.requireVectorCandidates(vectorCandidates);
            return List.of();
        }
    }

    @Test
    void aSpecNarrowsByCriteriaAndByContext() {
        ScopedReadSpec spec = ScopedReadSpec.of(SCOPE);
        ScopedReadSpec active = spec.narrow(
                criteria -> criteria.withStatus(PropositionStatus.ACTIVE).withMinConfidence(0.6));
        ScopedReadSpec aliceOnly = active.narrowToContexts("alice-session");

        assertTrue(spec.getFilters().isEmpty());
        assertSame(SCOPE, active.getScope());
        assertEquals(Set.of("alice-session"), aliceOnly.getScope().getContextIdValues());
        assertEquals(active.getFilters(), aliceOnly.getFilters());
        assertEquals(active, spec.narrow(
                ReadCriteria.empty().withStatus(PropositionStatus.ACTIVE).withMinConfidence(0.6)));
        assertEquals(aliceOnly, active.narrowToContexts(List.of("alice-session")));
        assertSame(active, active.narrow(criteria -> criteria));
    }

    @Test
    void aSpecRebuiltFromItsScopeHasItsContextsAndNoFilter() {
        ScopedReadSpec aliceOnly = ScopedReadSpec.of(SCOPE)
                .narrow(criteria -> criteria.withMinConfidence(0.6))
                .narrowToContexts("alice-session");

        ScopedReadSpec rebuilt = ScopedReadSpec.of(aliceOnly.getScope());

        assertEquals(Set.of("alice-session"), rebuilt.getScope().getContextIdValues());
        assertTrue(rebuilt.getFilters().isEmpty());
        assertFalse(aliceOnly.toString().contains("team-handbook"));
    }

    @Test
    void aRefusalNamesOnlyTheContextsTheSpecAllowsAndIsNotAnArgumentError() {
        ScopedReadSpec active = ScopedReadSpec.of(SCOPE);
        ScopedReadSpec aliceOnly = active.narrowToContexts("alice-session");

        ReadScopeViolationException outside = assertThrows(ReadScopeViolationException.class,
                () -> active.narrowToContexts("bob-session"));
        assertEquals("bob-session", outside.getContextIdValue());
        assertEquals(SCOPE.getContextIdValues(), outside.getAllowedContextIdValues());

        ReadRefusedException narrowed = assertThrows(ReadRefusedException.class,
                () -> aliceOnly.narrowToContexts("team-handbook"));
        assertTrue(narrowed instanceof ReadScopeViolationException);
        assertFalse(IllegalArgumentException.class.isInstance(narrowed));
        ReadScopeViolationException refusal = (ReadScopeViolationException) narrowed;
        assertEquals("team-handbook", refusal.getContextIdValue());
        assertEquals(Set.of("alice-session"), refusal.getAllowedContextIdValues());
    }

    @Test
    void aScopeViolationSurvivesJavaSerialization() throws Exception {
        ReadScopeViolationException refusal = assertThrows(ReadScopeViolationException.class,
                () -> ScopedReadSpec.of(SCOPE).narrowToContexts("bob-session"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(refusal);
        }

        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            ReadScopeViolationException copy = (ReadScopeViolationException) in.readObject();
            assertEquals("bob-session", copy.getContextIdValue());
            assertEquals(SCOPE.getContextIdValues(), copy.getAllowedContextIdValues());
        }
    }

    @Test
    void aNarrowingFunctionThatReturnsNullFailsWithAMessage() {
        NullPointerException failure = assertThrows(NullPointerException.class,
                () -> ScopedReadSpec.of(SCOPE).narrow(criteria -> null));

        assertTrue(failure.getMessage().contains("The narrowing function returned null"));
    }

    @Test
    void aCallerFindsEachScopedCapabilityOnlyWhenTheStoreListsIt() {
        PropositionStore store = Mockito.mock(PropositionStore.class, Mockito.withSettings()
                .extraInterfaces(CapabilityReporting.class, ScopedReads.class, ScopedVectorSearch.class));
        Mockito.when(((CapabilityReporting) store).getCapabilities())
                .thenReturn(StoreCapabilities.of(StoreCapability.SCOPED_READS));

        ScopedReads reads = StoreCapabilities.find(store, ScopedReads.class);
        ScopedVectorSearch search = StoreCapabilities.find(store, ScopedVectorSearch.class);

        assertSame(store, reads);
        assertNull(search);
        assertNull(StoreCapabilities.find(Mockito.mock(PropositionStore.class), ScopedReads.class));
        assertSame(ScopedReads.class, StoreCapability.SCOPED_READS.getContract());
        assertSame(ScopedVectorSearch.class, StoreCapability.SCOPED_VECTOR_SEARCH.getContract());

        Mockito.when(((CapabilityReporting) store).getCapabilities()).thenReturn(
                StoreCapabilities.of(StoreCapability.SCOPED_READS, StoreCapability.SCOPED_VECTOR_SEARCH));
        assertSame(store, StoreCapabilities.find(store, ScopedVectorSearch.class));
    }

    @Test
    void aCallerReadsUnderASpecAndABudgetAndTheStoreMakesTheChecks() {
        CheckingReads store = new CheckingReads(3, 3);
        ScopedReads reads = store;
        ScopedVectorSearch search = store;
        ScopedReadSpec active = ScopedReadSpec.of(SCOPE).narrow(
                criteria -> criteria.withStatus(PropositionStatus.ACTIVE).withMinConfidence(0.6));
        ReadBudget budget = ReadBudget.ofRows(200).withMaxVectorCandidates(5_000);
        ReadCriteria aboutAlice = ReadCriteria.empty().withEntityId("alice");

        List<Proposition> recent = reads.query(
                active, ReadQuery.of(aboutAlice).orderedByCreated().withLimit(20), budget);
        int total = reads.count(active, aboutAlice, budget);
        List<Proposition> mentions = reads.matchKeywords(
                active, ReadKeywordQuery.forTokens("peanut", "allergy").withLimit(10), budget);
        List<VectorMatch> nearest = List.of();
        if (budget.isVectorSearchAllowed()) {
            nearest = search.findNearest(
                    active, ReadVectorSearchRequest.forText("What does Alice eat?").withTopK(5), budget);
        }

        assertTrue(recent.isEmpty());
        assertEquals(3, total);
        assertTrue(mentions.isEmpty());
        assertTrue(nearest.isEmpty());
        assertEquals(4, store.ran);

        ReadBudget two = ReadBudget.ofRows(2);
        assertThrows(ReadBudgetExceededException.class,
                () -> reads.query(active, ReadQuery.of(aboutAlice).withLimit(201), budget));
        assertThrows(ReadBudgetExceededException.class,
                () -> reads.query(active, ReadQuery.of(aboutAlice), two));
        assertThrows(ReadBudgetExceededException.class,
                () -> reads.matchKeywords(active, ReadKeywordQuery.forTokens("peanut"), two));
        ReadBudgetExceededException counted = assertThrows(ReadBudgetExceededException.class,
                () -> reads.count(active, aboutAlice, two));
        assertEquals(ReadBudgetResource.ROWS, counted.getResource());
        assertEquals(2, counted.getAllowed());
        assertEquals(3L, counted.getRequested());

        ReadVectorSearchRequest request = ReadVectorSearchRequest.forText("peanut allergy");
        ReadBudgetExceededException notEnabled = assertThrows(ReadBudgetExceededException.class,
                () -> search.findNearest(active, request, ReadBudget.ofRows(200)));
        assertEquals(ReadBudgetResource.VECTOR_SEARCH, notEnabled.getResource());
        assertTrue(notEnabled.getMessage().contains("not enabled"));
        ReadBudgetExceededException tooMany = assertThrows(ReadBudgetExceededException.class,
                () -> search.findNearest(active, request, ReadBudget.ofRows(200).withMaxVectorCandidates(2)));
        assertEquals(ReadBudgetResource.VECTOR_CANDIDATES, tooMany.getResource());
        assertEquals(3L, tooMany.getRequested());
    }

    @Test
    void aStoreChecksTheSpecThenTheRequestThenTheBudgetThenReads() {
        CheckingReads store = new CheckingReads(3, 3);
        ScopedReadSpec spec = ScopedReadSpec.of(SCOPE);
        ScopedReadSpec pinnedOnly = spec.narrow(criteria -> criteria.withPinned(true));
        ReadCriteria aboutAlice = ReadCriteria.empty().withEntityId("alice");
        ReadBudget two = ReadBudget.ofRows(2);

        // Each call also breaks a later check. The earlier one decides the exception.
        UnsupportedCapabilityException filter = assertThrows(UnsupportedCapabilityException.class,
                () -> store.query(pinnedOnly, ReadQuery.of(aboutAlice).orderedByImportance().withLimit(201), two));
        assertEquals(StoreCapability.SCOPED_READS, filter.getCapability());
        assertTrue(filter.getMessage().contains("PINNED"));

        UnsupportedCapabilityException order = assertThrows(UnsupportedCapabilityException.class,
                () -> store.query(spec, ReadQuery.of(aboutAlice).orderedByImportance().withLimit(201), two));
        assertTrue(order.getMessage().contains("IMPORTANCE_DESC"));

        assertThrows(UnsupportedCapabilityException.class,
                () -> store.count(spec, aboutAlice.withMinImportance(0.5), two));
        assertThrows(UnsupportedCapabilityException.class,
                () -> store.matchKeywords(pinnedOnly, ReadKeywordQuery.forTokens("peanut").withLimit(201), two));

        UnsupportedCapabilityException search = assertThrows(UnsupportedCapabilityException.class,
                () -> store.findNearest(pinnedOnly, ReadVectorSearchRequest.forText("peanut allergy"), two));
        assertEquals(StoreCapability.SCOPED_VECTOR_SEARCH, search.getCapability());

        ReadBudgetExceededException beforeReading = assertThrows(ReadBudgetExceededException.class,
                () -> store.query(spec, ReadQuery.of(aboutAlice).withLimit(201), two));
        assertEquals(201L, beforeReading.getRequested());
        assertEquals(0, store.ran);
    }

    @Test
    void aCountTooLargeForAnIntIsRefusedWithTheCountTheDatabaseReturned() {
        long counted = 4_294_967_301L;
        CheckingReads store = new CheckingReads(counted, counted);
        ScopedReadSpec spec = ScopedReadSpec.of(SCOPE);
        ReadBudget budget = ReadBudget.ofRows(200).withMaxVectorCandidates(5_000);

        ReadBudgetExceededException rows = assertThrows(ReadBudgetExceededException.class,
                () -> store.count(spec, ReadCriteria.empty(), budget));
        ReadBudgetExceededException candidates = assertThrows(ReadBudgetExceededException.class,
                () -> store.findNearest(spec, ReadVectorSearchRequest.forText("peanut allergy"), budget));

        assertEquals(counted, rows.getRequested());
        assertEquals(counted, candidates.getRequested());
    }

    @Test
    void aSpecHolderSearchesEntityCandidatesUnderTheScopeOfItsSpec() {
        List<ContextReadScope> searched = new ArrayList<>();
        ScopedCandidateSearcher searcher = (suggested, schema, scope) -> {
            searched.add(scope);
            return SearchResult.empty();
        };
        SuggestedEntity alice = new SuggestedEntity(
                List.of("Person"), "Alice", "A customer", "chunk-1", null, Map.of(), 1.0, 0.0, 0.5, "test");
        ScopedReadSpec aliceOnly = ScopedReadSpec.of(SCOPE).narrowToContexts("alice-session");

        SearchResult found = searcher.search(alice, Mockito.mock(DataDictionary.class), aliceOnly.getScope());

        assertTrue(found.getCandidates().isEmpty());
        assertEquals(List.of(ContextReadScope.ofValues("alice-session")), searched);
    }
}

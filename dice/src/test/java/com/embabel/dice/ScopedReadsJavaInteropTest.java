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
import com.embabel.dice.common.resolver.ResolutionLevel;
import com.embabel.dice.common.resolver.ScopedCandidateSearcher;
import com.embabel.dice.common.resolver.SearchResult;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionQuery;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.PropositionStore;
import com.embabel.dice.store.CapabilityReporting;
import com.embabel.dice.store.ContextReadScope;
import com.embabel.dice.store.ReadBudget;
import com.embabel.dice.store.ReadBudgetExceededException;
import com.embabel.dice.store.ReadBudgetResource;
import com.embabel.dice.store.ReadScopeViolationException;
import com.embabel.dice.store.ScopedReadSpec;
import com.embabel.dice.store.ScopedReads;
import com.embabel.dice.store.ScopedVectorSearch;
import com.embabel.dice.store.StoreCapabilities;
import com.embabel.dice.store.StoreCapability;
import com.embabel.dice.store.VectorMatch;
import com.embabel.dice.store.VectorSearchRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scoped reads used from Java: a host narrows a spec, a caller finds the reads on a store and
 * reads under the spec and a budget, and a store author makes the scope and budget checks.
 */
class ScopedReadsJavaInteropTest {

    private static final ContextReadScope SCOPE = ContextReadScope.ofValues("alice-session", "team-handbook");

    /**
     * Holds no proposition and selects nothing, so it shows only the checks a store makes before
     * it reads. A store that holds data first selects by the scope and by every filter of the spec.
     */
    private static final class CheckingReads implements ScopedReads, ScopedVectorSearch {

        private final int vectorCandidates;
        private int ran;

        private CheckingReads(int vectorCandidates) {
            this.vectorCandidates = vectorCandidates;
        }

        @Override
        public List<Proposition> query(ScopedReadSpec spec, PropositionQuery query, ReadBudget budget) {
            spec.requireAllowed(query);
            requireLimit(query, budget);
            ran++;
            return List.of();
        }

        @Override
        public int count(ScopedReadSpec spec, PropositionQuery query) {
            spec.requireAllowed(query);
            if (query.getOrderBy() != PropositionQuery.OrderBy.NONE || query.getLimit() != null) {
                throw new IllegalArgumentException("A count takes no ordering and no limit");
            }
            return 0;
        }

        @Override
        public List<Proposition> matchKeywords(
                ScopedReadSpec spec, PropositionQuery query, List<String> tokens, ReadBudget budget) {
            spec.requireAllowed(query);
            requireLimit(query, budget);
            if (tokens.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("A token must not be blank");
            }
            return List.of();
        }

        @Override
        public List<VectorMatch> findNearest(ScopedReadSpec spec, VectorSearchRequest request, ReadBudget budget) {
            spec.requireAllowed(request.getFilter());
            budget.requireRows(request.getTopK());
            budget.requireVectorCandidates(vectorCandidates);
            return List.of();
        }

        private static void requireLimit(PropositionQuery query, ReadBudget budget) {
            Integer limit = query.getLimit();
            if (limit != null && limit < 1) {
                throw new IllegalArgumentException("A limit must be at least 1");
            }
            // It holds no row, so a read with no limit would return none.
            budget.requireRows(limit != null ? limit : 0);
        }
    }

    @Test
    void aSpecNarrowsWithAFunctionAndRefusesAContextItDoesNotAllow() {
        ScopedReadSpec spec = ScopedReadSpec.of(SCOPE);
        ScopedReadSpec active = spec.narrow(
                filter -> filter.withStatus(PropositionStatus.ACTIVE).withMinConfidence(0.6));
        ScopedReadSpec aliceOnly = active.narrow(PropositionQuery.againstContext("alice-session"));

        assertTrue(spec.getFilters().isEmpty());
        assertEquals(2, aliceOnly.getFilters().size());
        assertSame(SCOPE, aliceOnly.getScope());
        assertEquals(active, spec.narrow(
                ScopedReadSpec.criteria().withStatus(PropositionStatus.ACTIVE).withMinConfidence(0.6)));

        ReadScopeViolationException refusal = assertThrows(ReadScopeViolationException.class,
                () -> active.narrow(PropositionQuery.againstContext("bob-session")));
        assertEquals("bob-session", refusal.getContextIdValue());
        assertEquals(SCOPE.getContextIdValues(), refusal.getAllowedContextIdValues());
        assertThrows(ReadScopeViolationException.class,
                () -> aliceOnly.requireAllowed(PropositionQuery.againstContext("team-handbook")));
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
        CheckingReads store = new CheckingReads(3);
        ScopedReads reads = store;
        ScopedVectorSearch search = store;
        ScopedReadSpec active = ScopedReadSpec.of(SCOPE).narrow(
                filter -> filter.withStatus(PropositionStatus.ACTIVE).withMinConfidence(0.6));
        ReadBudget budget = ReadBudget.ofRows(200).withMaxVectorCandidates(5_000);

        List<Proposition> recent = reads.query(
                active, ScopedReadSpec.criteria().orderedByCreated().withLimit(20), budget);
        int total = reads.count(active, ScopedReadSpec.criteria());
        List<Proposition> mentions = reads.matchKeywords(
                active, ScopedReadSpec.criteria().withLimit(10), List.of("peanut", "allergy"), budget);
        List<VectorMatch> nearest = search.findNearest(
                active, VectorSearchRequest.forText("What does Alice eat?", ScopedReadSpec.criteria()).withTopK(5), budget);

        assertTrue(recent.isEmpty());
        assertEquals(0, total);
        assertTrue(mentions.isEmpty());
        assertTrue(nearest.isEmpty());
        reads.query(active, PropositionQuery.againstContext("alice-session"), budget);
        assertEquals(2, store.ran);

        assertThrows(ReadScopeViolationException.class,
                () -> reads.query(active, PropositionQuery.againstContext("bob-session"), budget));
        assertThrows(ReadBudgetExceededException.class,
                () -> reads.query(active, ScopedReadSpec.criteria().withLimit(201), budget));
        assertThrows(ReadBudgetExceededException.class,
                () -> reads.matchKeywords(active, ScopedReadSpec.criteria().withLimit(201), List.of("peanut"), budget));
        assertThrows(IllegalArgumentException.class,
                () -> reads.query(active, ScopedReadSpec.criteria().withLimit(0), budget));
        assertThrows(IllegalArgumentException.class,
                () -> reads.count(active, ScopedReadSpec.criteria().withLimit(5)));
        assertThrows(IllegalArgumentException.class,
                () -> reads.matchKeywords(active, ScopedReadSpec.criteria(), List.of("peanut", " "), budget));

        VectorSearchRequest request = VectorSearchRequest.forText("peanut allergy", ScopedReadSpec.criteria());
        ReadBudgetExceededException refusal = assertThrows(ReadBudgetExceededException.class,
                () -> search.findNearest(active, request, ReadBudget.ofRows(200)));
        assertEquals(ReadBudgetResource.VECTOR_CANDIDATES, refusal.getResource());
        assertEquals(0, refusal.getAllowed());
        ReadBudgetExceededException tooMany = assertThrows(ReadBudgetExceededException.class,
                () -> search.findNearest(active, request, ReadBudget.ofRows(200).withMaxVectorCandidates(2)));
        assertEquals(3, tooMany.getRequested());
        assertEquals(2, store.ran);
    }

    @Test
    void aScopedCandidateSearcherIsWrittenAsALambda() {
        List<ContextReadScope> searched = new ArrayList<>();
        ScopedCandidateSearcher searcher = (suggested, schema, scope) -> {
            searched.add(scope);
            return SearchResult.empty();
        };
        SuggestedEntity alice = new SuggestedEntity(
                List.of("Person"), "Alice", "A customer", "chunk-1", null, Map.of(), 1.0, 0.0, 0.5, "test");
        ContextReadScope aliceSession = ContextReadScope.ofValues("alice-session");

        SearchResult found = searcher.search(alice, Mockito.mock(DataDictionary.class), aliceSession);

        assertTrue(found.getCandidates().isEmpty());
        assertEquals(List.of(aliceSession), searched);
        assertEquals(ResolutionLevel.HEURISTIC_MATCH, searcher.getResolutionLevel());
    }
}

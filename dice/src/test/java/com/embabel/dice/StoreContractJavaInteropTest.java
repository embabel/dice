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

import com.embabel.agent.rag.service.RetrievableIdentifier;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionQuery;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.PropositionStore;
import com.embabel.dice.store.CapabilityReporting;
import com.embabel.dice.store.FilteredVectorSearch;
import com.embabel.dice.store.SimilarityScore;
import com.embabel.dice.store.StoreCapabilities;
import com.embabel.dice.store.StoreCapability;
import com.embabel.dice.store.UnsupportedCapabilityException;
import com.embabel.dice.store.VectorFiltering;
import com.embabel.dice.store.VectorMatch;
import com.embabel.dice.store.VectorSearchRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store contract used from Java: every factory, chain and interface, the way a Java caller or
 * a Java store author uses it.
 */
class StoreContractJavaInteropTest {

    private static final PropositionQuery ALICE = PropositionQuery.againstContext("alice-session");

    /** The nine members a Java proposition store has to write. The rest have defaults. */
    private static class PlainStore implements PropositionStore {

        private final List<Proposition> saved = new ArrayList<>();

        @Override
        public Proposition save(Proposition proposition) {
            saved.add(proposition);
            return proposition;
        }

        @Override
        public Proposition findById(String id) {
            return null;
        }

        @Override
        public List<Proposition> findByEntity(RetrievableIdentifier entityIdentifier) {
            return List.of();
        }

        @Override
        public List<Proposition> findByStatus(PropositionStatus status) {
            return List.of();
        }

        @Override
        public List<Proposition> findByGrounding(String chunkId) {
            return List.of();
        }

        @Override
        public List<Proposition> findByMinLevel(int minLevel) {
            return List.of();
        }

        @Override
        public List<Proposition> findAll() {
            return saved;
        }

        @Override
        public boolean delete(String id) {
            return false;
        }

        @Override
        public int count() {
            return saved.size();
        }
    }

    /** A store that publishes its capabilities and implements the interface of the one it lists. */
    private static final class CapableStore extends PlainStore implements CapabilityReporting, FilteredVectorSearch {

        @Override
        public StoreCapabilities getCapabilities() {
            return StoreCapabilities.of(StoreCapability.FILTERED_VECTOR_SEARCH);
        }

        @Override
        public List<VectorMatch> findNearest(VectorSearchRequest request) {
            return List.of();
        }
    }

    @Test
    void scoresAreBuiltComparedAndReadFromJava() {
        SimilarityScore close = SimilarityScore.ofCosine(0.9);
        SimilarityScore far = SimilarityScore.ofCosineDistance(0.75);

        assertEquals(0.9, close.getCosine());
        assertEquals(0.25, far.getCosine());
        assertEquals(0.95, close.getUnitInterval(), 1e-9);
        assertTrue(close.compareTo(far) > 0);
        assertEquals(SimilarityScore.ofCosine(0.0), SimilarityScore.ofUnitInterval(0.5));
        assertThrows(IllegalArgumentException.class, () -> SimilarityScore.ofCosine(1.5));
    }

    @Test
    void aMatchIsBuiltAndReadFromJava() {
        Instant now = Instant.now();
        Proposition proposition = Proposition.create("p1", "alice-session", "Alice likes tea", List.of(),
                0.9, 0.0, null, List.of(), now, now, PropositionStatus.ACTIVE);
        SimilarityScore score = SimilarityScore.ofCosine(0.8);

        VectorMatch match = VectorMatch.of(proposition, score);

        assertSame(proposition, match.getProposition());
        assertEquals(score, match.getScore());
        assertEquals(match, VectorMatch.of(proposition, SimilarityScore.ofCosine(0.8)));
    }

    @Test
    void aCallerFindsACapabilityOnAStoreThatPublishesIt() {
        PropositionStore store = new CapableStore();

        FilteredVectorSearch search = StoreCapabilities.find(store, FilteredVectorSearch.class);
        assertNotNull(search);
        assertTrue(search.findNearest(VectorSearchRequest.forText("peanut allergy", ALICE)).isEmpty());

        StoreCapabilities capabilities = StoreCapabilities.describe(store);
        assertTrue(capabilities.supports(StoreCapability.FILTERED_VECTOR_SEARCH));
        assertEquals(VectorFiltering.BEFORE_TOP_K, capabilities.getVectorFiltering());
        assertEquals(1, capabilities.getSupported().size());
    }

    @Test
    void aStoreThatPublishesNothingIsUndeclaredAndHasNoCapability() {
        PropositionStore store = new PlainStore();

        assertSame(StoreCapabilities.UNDECLARED, StoreCapabilities.describe(store));
        assertEquals(VectorFiltering.UNDECLARED, StoreCapabilities.describe(store).getVectorFiltering());
        assertNull(StoreCapabilities.find(store, FilteredVectorSearch.class));
        assertThrows(IllegalArgumentException.class, () -> StoreCapabilities.find(store, Runnable.class));
    }

    @Test
    void aDescriptorIsBuiltWithAChain() {
        StoreCapabilities filtersAfter = StoreCapabilities.NONE.withVectorFiltering(VectorFiltering.AFTER_TOP_K);

        assertFalse(filtersAfter.supports(StoreCapability.FILTERED_VECTOR_SEARCH));
        assertEquals(VectorFiltering.AFTER_TOP_K, filtersAfter.getVectorFiltering());
        assertEquals(VectorFiltering.NONE, StoreCapabilities.NONE.getVectorFiltering());
        assertEquals(VectorFiltering.BEFORE_TOP_K,
                StoreCapabilities.NONE.with(StoreCapability.FILTERED_VECTOR_SEARCH).getVectorFiltering());
        assertThrows(IllegalArgumentException.class,
                () -> StoreCapabilities.NONE.withVectorFiltering(VectorFiltering.BEFORE_TOP_K));
    }

    @Test
    void aRequestIsBuiltWithAChain() {
        VectorSearchRequest request = VectorSearchRequest.forText("What does Alice like to eat?", ALICE)
                .withTopK(5)
                .withMinScore(SimilarityScore.ofCosine(0.6));

        assertEquals(5, request.getTopK());
        assertEquals(ALICE, request.getFilter());
        assertEquals(10, VectorSearchRequest.DEFAULT_TOP_K);
        assertNull(VectorSearchRequest.forText("cats", ALICE).getMinScore());
        assertThrows(IllegalArgumentException.class, () -> request.withTopK(0));
        assertThrows(IllegalArgumentException.class, () -> VectorSearchRequest.forText("cats", ALICE.withLimit(3)));
    }

    @Test
    void aRefusalIsCaughtByTypeAndNamesItsCapability() {
        FilteredVectorSearch refusing = request -> {
            throw new UnsupportedCapabilityException(StoreCapability.FILTERED_VECTOR_SEARCH, "no vector index");
        };

        UnsupportedCapabilityException refusal = assertThrows(UnsupportedCapabilityException.class,
                () -> refusing.findNearest(VectorSearchRequest.forText("cats", ALICE)));

        assertEquals(StoreCapability.FILTERED_VECTOR_SEARCH, refusal.getCapability());
        assertTrue(refusal instanceof UnsupportedOperationException);
        assertSame(FilteredVectorSearch.class, StoreCapability.FILTERED_VECTOR_SEARCH.getContract());
    }

    @Test
    void theKotlinBlockEntriesAreHiddenFromJava() throws Exception {
        // A block entry takes a Kotlin receiver lambda, which Java would have to end with
        // "return Unit.INSTANCE". Java has the chains, so the block entries are synthetic.
        int hidden = 0;
        for (Class<?> owner : List.of(StoreCapabilities.class, VectorSearchRequest.class)) {
            Class<?> companion = Class.forName(owner.getName() + "$Companion");
            for (Method method : companion.getDeclaredMethods()) {
                boolean takesABlock = List.of(method.getParameterTypes()).contains(kotlin.jvm.functions.Function1.class);
                if (takesABlock && Modifier.isPublic(method.getModifiers())) {
                    assertTrue(method.isSynthetic(), owner.getSimpleName() + "." + method.getName());
                    // Kotlin adds a "$default" twin for a block with a default. Count each entry once.
                    if (!method.getName().endsWith("$default")) {
                        hidden++;
                    }
                }
            }
        }
        assertEquals(2, hidden);
    }
}

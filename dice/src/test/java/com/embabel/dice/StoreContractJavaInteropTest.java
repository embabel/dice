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
import com.embabel.dice.store.AbstractionTraversal;
import com.embabel.dice.store.AtomicWrites;
import com.embabel.dice.store.CapabilityReporting;
import com.embabel.dice.store.FilteredVectorSearch;
import com.embabel.dice.store.FullTextSearch;
import com.embabel.dice.store.FullTextSearchRequest;
import com.embabel.dice.store.SimilarityScore;
import com.embabel.dice.store.StoreCapabilities;
import com.embabel.dice.store.StoreCapability;
import com.embabel.dice.store.TraversalDirection;
import com.embabel.dice.store.TraversalRequest;
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
import java.util.function.Supplier;

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

    /** A store that publishes its capabilities and implements the interface of each one. */
    private static final class CapableStore extends PlainStore
            implements CapabilityReporting, FilteredVectorSearch, FullTextSearch, AbstractionTraversal, AtomicWrites {

        @Override
        public StoreCapabilities getCapabilities() {
            return StoreCapabilities.of(
                    StoreCapability.FILTERED_VECTOR_SEARCH,
                    StoreCapability.FULL_TEXT_SEARCH,
                    StoreCapability.ABSTRACTION_TRAVERSAL,
                    StoreCapability.ATOMIC_WRITES);
        }

        @Override
        public List<VectorMatch> findNearest(VectorSearchRequest request) {
            return List.of();
        }

        @Override
        public List<Proposition> findMatching(FullTextSearchRequest request) {
            return List.of();
        }

        @Override
        public List<Proposition> traverse(TraversalRequest request) {
            return List.of();
        }

        @Override
        public <T> T atomically(Supplier<T> work) {
            return work.get();
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

        FullTextSearch search = StoreCapabilities.find(store, FullTextSearch.class);
        assertNotNull(search);
        assertTrue(search.findMatching(FullTextSearchRequest.forText("peanut allergy", ALICE)).isEmpty());

        StoreCapabilities capabilities = StoreCapabilities.describe(store);
        assertTrue(capabilities.supports(StoreCapability.ATOMIC_WRITES));
        assertEquals(VectorFiltering.BEFORE_TOP_K, capabilities.getVectorFiltering());
        assertEquals(4, capabilities.getSupported().size());
    }

    @Test
    void aStoreThatPublishesNothingIsUndeclaredAndHasNoCapability() {
        PropositionStore store = new PlainStore();

        assertSame(StoreCapabilities.UNDECLARED, StoreCapabilities.describe(store));
        assertEquals(VectorFiltering.UNDECLARED, StoreCapabilities.describe(store).getVectorFiltering());
        assertNull(StoreCapabilities.find(store, FullTextSearch.class));
        assertThrows(IllegalArgumentException.class, () -> StoreCapabilities.find(store, Runnable.class));
    }

    @Test
    void aDescriptorIsBuiltWithAChain() {
        StoreCapabilities capabilities = StoreCapabilities.of(StoreCapability.ATOMIC_WRITES)
                .with(StoreCapability.FULL_TEXT_SEARCH)
                .withVectorFiltering(VectorFiltering.AFTER_TOP_K);

        assertTrue(capabilities.supports(StoreCapability.FULL_TEXT_SEARCH));
        assertFalse(capabilities.supports(StoreCapability.FILTERED_VECTOR_SEARCH));
        assertEquals(VectorFiltering.AFTER_TOP_K, capabilities.getVectorFiltering());
        assertEquals(VectorFiltering.NONE, StoreCapabilities.NONE.getVectorFiltering());
        assertThrows(IllegalArgumentException.class,
                () -> StoreCapabilities.NONE.withVectorFiltering(VectorFiltering.BEFORE_TOP_K));
    }

    @Test
    void requestsAreBuiltWithChains() {
        VectorSearchRequest vector = VectorSearchRequest.forText("What does Alice like to eat?", ALICE)
                .withTopK(5)
                .withMinScore(SimilarityScore.ofCosine(0.6));
        assertEquals(5, vector.getTopK());
        assertEquals(ALICE, vector.getFilter());
        assertEquals(10, VectorSearchRequest.DEFAULT_TOP_K);
        assertNull(VectorSearchRequest.forText("cats", ALICE).getMinScore());

        FullTextSearchRequest text = FullTextSearchRequest.forText("peanut allergy", ALICE).withLimit(20);
        assertEquals(20, text.getLimit());
        assertEquals(10, FullTextSearchRequest.DEFAULT_LIMIT);

        TraversalRequest traversal = TraversalRequest.abstractionsOf("p1", ALICE).withMaxDepth(3).withLimit(7);
        assertEquals(TraversalDirection.TO_ABSTRACTIONS, traversal.getDirection());
        assertEquals(3, traversal.getMaxDepth());
        assertEquals(TraversalDirection.TO_SOURCES, TraversalRequest.sourcesOf("p1", ALICE).getDirection());
        assertEquals(16, TraversalRequest.MAX_DEPTH);
        assertEquals(100, TraversalRequest.DEFAULT_LIMIT);

        assertThrows(IllegalArgumentException.class, () -> vector.withTopK(0));
        assertThrows(IllegalArgumentException.class, () -> FullTextSearchRequest.forText("cats", ALICE.withLimit(3)));
        assertThrows(IllegalArgumentException.class, () -> traversal.withMaxDepth(17));
    }

    @Test
    void atomicWritesTakeALambdaWithOrWithoutAResult() {
        AtomicWrites writes = new CapableStore();
        List<String> ran = new ArrayList<>();

        String kept = writes.atomically(() -> "kept");
        writes.runAtomically(() -> ran.add("ran"));

        assertEquals("kept", kept);
        assertEquals(List.of("ran"), ran);
    }

    @Test
    void aRefusalIsCaughtByTypeAndNamesItsCapability() {
        FullTextSearch refusing = request -> {
            throw new UnsupportedCapabilityException(StoreCapability.FULL_TEXT_SEARCH, "no text index");
        };

        UnsupportedCapabilityException refusal = assertThrows(UnsupportedCapabilityException.class,
                () -> refusing.findMatching(FullTextSearchRequest.forText("cats", ALICE)));

        assertEquals(StoreCapability.FULL_TEXT_SEARCH, refusal.getCapability());
        assertTrue(refusal instanceof UnsupportedOperationException);
        assertSame(FullTextSearch.class, StoreCapability.FULL_TEXT_SEARCH.getContract());
    }

    @Test
    void theKotlinBlockEntriesAreHiddenFromJava() throws Exception {
        // A block entry takes a Kotlin receiver lambda, which Java would have to end with
        // "return Unit.INSTANCE". Java has the chains, so the block entries are synthetic.
        int hidden = 0;
        for (Class<?> owner : List.of(StoreCapabilities.class, VectorSearchRequest.class,
                FullTextSearchRequest.class, TraversalRequest.class)) {
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
        assertEquals(5, hidden);
    }
}

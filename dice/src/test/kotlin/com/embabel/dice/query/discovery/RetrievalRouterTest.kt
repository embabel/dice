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
package com.embabel.dice.query.discovery

import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.FilteredVectorUnsupportedException
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.proposition.GraphQueryCapable
import com.embabel.dice.proposition.TemporalQueryCapable
import com.embabel.dice.proposition.VectorSearchCapable
import com.embabel.dice.proposition.matchesFilters
import com.embabel.dice.query.graph.GraphNeighborhood
import com.embabel.dice.query.graph.GraphPath
import com.embabel.dice.query.graph.GraphQuery
import com.embabel.dice.query.graph.PropositionLineage
import com.embabel.dice.query.graph.RelatedEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class RetrievalRouterTest {

    private val contextId = ContextId("router-test")

    private fun proposition(id: String, entityId: String = "A"): Proposition =
        Proposition(
            id = id,
            contextId = contextId,
            text = "fact $id",
            mentions = listOf(
                EntityMention(span = entityId, type = "Entity", resolvedId = entityId, role = MentionRole.SUBJECT),
            ),
            confidence = 0.9,
        )

    /**
     * A spy base-only store that FAILS the test if findAll() or query() is ever invoked. Used to
     * prove VECTOR/TEMPORAL never silently degrade to a full scan when the fragment is absent.
     */
    private open class ScanForbiddenStore : PropositionStore {
        private val store = mutableMapOf<String, Proposition>()
        override fun save(proposition: Proposition): Proposition {
            store[proposition.id] = proposition; return proposition
        }
        override fun findById(id: String): Proposition? = store[id]
        override fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition> = emptyList()
        override fun findByStatus(status: PropositionStatus): List<Proposition> = emptyList()
        override fun findByGrounding(chunkId: String): List<Proposition> = emptyList()
        override fun findByMinLevel(minLevel: Int): List<Proposition> = emptyList()
        override fun findAll(): List<Proposition> =
            throw AssertionError("findAll() must not be invoked for a fragment-absent mode")
        override fun query(query: PropositionQuery): List<Proposition> =
            throw AssertionError("query() must not be invoked for a fragment-absent mode")
        override fun delete(id: String): Boolean = store.remove(id) != null
        override fun count(): Int = store.size
    }

    /**
     * A base store that allows query() (for the ENTITY path), recording the queries it received,
     * and that does NOT implement the vector/temporal fragments.
     */
    private open class RecordingEntityStore : PropositionStore {
        val store = mutableMapOf<String, Proposition>()
        val queries = mutableListOf<PropositionQuery>()
        override fun save(proposition: Proposition): Proposition {
            store[proposition.id] = proposition; return proposition
        }
        override fun findById(id: String): Proposition? = store[id]
        override fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition> = emptyList()
        override fun findByStatus(status: PropositionStatus): List<Proposition> = emptyList()
        override fun findByGrounding(chunkId: String): List<Proposition> = emptyList()
        override fun findByMinLevel(minLevel: Int): List<Proposition> = emptyList()
        override fun findAll(): List<Proposition> = store.values.toList()
        override fun query(query: PropositionQuery): List<Proposition> {
            queries.add(query)
            return super.query(query)
        }
        override fun delete(id: String): Boolean = store.remove(id) != null
        override fun count(): Int = store.size
    }

    /**
     * A fragment-rich store: vector-capable with a fixed scored hit list, AND graph-capable with a
     * native neighbourhood. Lets HYBRID exercise both arms deterministically.
     */
    private class FragmentRichStore(
        private val vectorHits: List<SimilarityResult<Proposition>>,
        private val neighbourhood: GraphNeighborhood,
    ) : RecordingEntityStore(), VectorSearchCapable, GraphQueryCapable {
        init {
            // Keep the complete fixture resident so filtered vector search can select before top-K.
            vectorHits.forEach { save(it.match) }
        }
        override fun findSimilarWithScores(
            textSimilaritySearchRequest: TextSimilaritySearchRequest,
        ): List<SimilarityResult<Proposition>> = vectorHits
        override fun findSimilarWithScores(
            textSimilaritySearchRequest: TextSimilaritySearchRequest,
            query: PropositionQuery,
        ): List<SimilarityResult<Proposition>> {
            val asOf = query.effectiveConfidenceAsOf ?: Instant.now()
            return vectorHits.filter { query.matchesFilters(it.match, asOf) }
                .filter { it.score >= textSimilaritySearchRequest.similarityThreshold }
                .sortedByDescending { it.score }
                .take(textSimilaritySearchRequest.topK)
        }
        override fun neighborhood(entityId: String, depth: Int): GraphNeighborhood = neighbourhood
        override fun pathBetween(entityIdA: String, entityIdB: String): List<GraphPath> = emptyList()
        override fun whyExplain(propositionId: String): PropositionLineage? = null
    }

    private fun scored(prop: Proposition, score: Double): SimilarityResult<Proposition> =
        SimilarityResult.create(prop, score)

    private fun propInContext(id: String, ctx: ContextId, entityId: String = "A"): Proposition =
        Proposition(
            id = id,
            contextId = ctx,
            text = "fact $id",
            mentions = listOf(
                EntityMention(span = entityId, type = "Entity", resolvedId = entityId, role = MentionRole.SUBJECT),
            ),
            confidence = 0.9,
        )

    /**
     * A store backed by a real map (so findAll()/query() honour context filtering), that is BOTH
     * vector- and temporal-capable. Its single-arg vector method returns hits across EVERY context;
     * its filtered vector method applies predicates to the complete fixture before top-K, while its
     * temporal default scans all contexts. This lets tests prove the router supplies its own scope.
     */
    private class CrossContextStore : RecordingEntityStore(), VectorSearchCapable, TemporalQueryCapable {
        override fun findSimilarWithScores(
            textSimilaritySearchRequest: TextSimilaritySearchRequest,
        ): List<SimilarityResult<Proposition>> =
            store.values.map { SimilarityResult.create(it, 0.9) }
        override fun findSimilarWithScores(
            textSimilaritySearchRequest: TextSimilaritySearchRequest,
            query: PropositionQuery,
        ): List<SimilarityResult<Proposition>> {
            val asOf = query.effectiveConfidenceAsOf ?: Instant.now()
            return store.values.filter { query.matchesFilters(it, asOf) }
                .map { SimilarityResult.create(it, 0.9) }
                .filter { it.score >= textSimilaritySearchRequest.similarityThreshold }
                .sortedByDescending { it.score }
                .take(textSimilaritySearchRequest.topK)
        }
    }

    // ------------------------------------------------------------------------
    // Cross-context isolation: a router bound to ctxA never returns ctxB props
    // ------------------------------------------------------------------------

    @Test
    fun `VECTOR scopes results to the bound context`() {
        val ctxA = ContextId("ctxA")
        val ctxB = ContextId("ctxB")
        val store = CrossContextStore().apply {
            save(propInContext("a1", ctxA))
            save(propInContext("b1", ctxB))
        }
        val router = RetrievalRouter(store, GraphQuery(store, ctxA), ctxA)

        val result = router.retrieve(DiscoveryQuery(mode = RetrievalMode.VECTOR, text = "q"))

        assertEquals(listOf("a1"), result.propositions.map { it.id }, "VECTOR must not leak ctxB props")
    }

    @Test
    fun `VECTOR selects eligible hits before top-K despite foreign decoys`() {
        val ctxA = ContextId("ctxA")
        val store = CrossContextStore().apply {
            repeat(5) { save(propInContext("decoy-$it", ContextId("ctxC"))) }
            save(propInContext("a1", ctxA))
            save(propInContext("a2", ctxA))
        }
        val router = RetrievalRouter(store, GraphQuery(store, ctxA), ctxA)

        val result = router.retrieve(DiscoveryQuery(mode = RetrievalMode.VECTOR, text = "q", topK = 2))

        assertEquals(listOf("a1", "a2"), result.propositions.map { it.id })
    }

    @Test
    fun `VECTOR propagates unsupported filtered search without a global fallback`() {
        val store = object : RecordingEntityStore(), VectorSearchCapable {
            override fun findSimilarWithScores(
                textSimilaritySearchRequest: TextSimilaritySearchRequest,
            ): List<SimilarityResult<Proposition>> = throw AssertionError("global vector call")
        }
        val router = RetrievalRouter(store, GraphQuery(store, contextId), contextId)

        assertThrows(FilteredVectorUnsupportedException::class.java) {
            router.retrieve(DiscoveryQuery(mode = RetrievalMode.VECTOR, text = "q"))
        }
        assertTrue(store.queries.isEmpty())
    }

    @Test
    fun `TEMPORAL scopes results to the bound context`() {
        val ctxA = ContextId("ctxA")
        val ctxB = ContextId("ctxB")
        val store = CrossContextStore().apply {
            save(propInContext("a1", ctxA))
            save(propInContext("b1", ctxB))
        }
        val router = RetrievalRouter(store, GraphQuery(store, ctxA), ctxA)

        val result = router.retrieve(
            DiscoveryQuery(mode = RetrievalMode.TEMPORAL, from = Instant.EPOCH, to = Instant.now().plusSeconds(60)),
        )

        assertEquals(listOf("a1"), result.propositions.map { it.id }, "TEMPORAL must not leak ctxB props")
    }

    @Test
    fun `HYBRID vector arm scopes results to the bound context`() {
        val ctxA = ContextId("ctxA")
        val ctxB = ContextId("ctxB")
        val store = CrossContextStore().apply {
            save(propInContext("a1", ctxA))
            save(propInContext("b1", ctxB))
        }
        val router = RetrievalRouter(store, GraphQuery(store, ctxA), ctxA)

        val result = router.retrieve(DiscoveryQuery(mode = RetrievalMode.HYBRID, text = "q"))

        assertEquals(listOf("a1"), result.propositions.map { it.id }, "HYBRID must not leak ctxB props")
    }

    @Test
    fun `whyExplain returns null for a proposition belonging to another context`() {
        val ctxA = ContextId("ctxA")
        val ctxB = ContextId("ctxB")
        val store = RecordingEntityStore().apply {
            save(propInContext("b1", ctxB))
        }
        // Router bound to ctxA; GraphQuery deliberately given no context so whyExplain resolves by id.
        val router = RetrievalRouter(store, GraphQuery(store), ctxA)

        assertEquals(null, router.whyExplain("b1"), "whyExplain must not expose a foreign-context proposition")
    }

    // ------------------------------------------------------------------------
    // Degradation: never findAll() for an absent fragment
    // ------------------------------------------------------------------------

    @Test
    fun `VECTOR against a fragment-absent store returns empty and unsupported without scanning`() {
        val store = ScanForbiddenStore().apply { save(proposition("p1")) }
        val router = RetrievalRouter(store, GraphQuery(store), contextId)

        val result = router.retrieve(DiscoveryQuery(mode = RetrievalMode.VECTOR, text = "anything"))

        assertFalse(result.supported, "VECTOR must report unsupported when no vector fragment")
        assertTrue(result.propositions.isEmpty(), "VECTOR must degrade to typed-empty")
        assertFalse(router.supports(RetrievalMode.VECTOR))
    }

    @Test
    fun `TEMPORAL against a fragment-absent store returns empty and unsupported without scanning`() {
        val store = ScanForbiddenStore().apply { save(proposition("p1")) }
        val router = RetrievalRouter(store, GraphQuery(store), contextId)

        val result = router.retrieve(
            DiscoveryQuery(mode = RetrievalMode.TEMPORAL, from = Instant.EPOCH, to = Instant.now()),
        )

        assertFalse(result.supported, "TEMPORAL must report unsupported when no temporal fragment")
        assertTrue(result.propositions.isEmpty(), "TEMPORAL must degrade to typed-empty")
        assertFalse(router.supports(RetrievalMode.TEMPORAL))
    }

    @Test
    fun `ENTITY routes through the filtered query and is always supported`() {
        val store = RecordingEntityStore().apply {
            save(proposition("p1", entityId = "A"))
            save(proposition("p2", entityId = "B"))
        }
        val router = RetrievalRouter(store, GraphQuery(store), contextId)

        val result = router.retrieve(DiscoveryQuery(mode = RetrievalMode.ENTITY, entityId = "A"))

        assertTrue(router.supports(RetrievalMode.ENTITY))
        assertTrue(result.supported)
        assertEquals(listOf("p1"), result.propositions.map { it.id })
        assertEquals(contextId, store.queries.single().contextId, "ENTITY query must be context-scoped")
        assertEquals("A", store.queries.single().entityId)
    }

    // ------------------------------------------------------------------------
    // HYBRID merge
    // ------------------------------------------------------------------------

    @Test
    fun `HYBRID unions vector hits with neighbourhood, dedupes, orders by score desc then id asc`() {
        val v1 = proposition("v1")
        val v2 = proposition("v2")
        val shared = proposition("s1") // appears in both vector and graph arms
        val g1 = proposition("g1")
        val store = FragmentRichStore(
            vectorHits = listOf(
                scored(v2, 0.7),
                scored(shared, 0.9),
                scored(v1, 0.7),
            ),
            neighbourhood = GraphNeighborhood(
                entityId = "A",
                neighbours = listOf(RelatedEntity("B", listOf(shared, g1))),
            ),
        )
        val router = RetrievalRouter(store, GraphQuery(store), contextId)

        val result = router.retrieve(
            DiscoveryQuery(mode = RetrievalMode.HYBRID, text = "q", entityId = "A", topK = 10),
        )

        assertTrue(result.supported, "HYBRID supported when vector fragment present")
        // shared(0.9) first; then vector tier ties v1/v2 at 0.7 ordered by id; then graph-only g1 last.
        assertEquals(listOf("s1", "v1", "v2", "g1"), result.propositions.map { it.id })
        // dedupe: s1 appears once despite being in both arms.
        assertEquals(1, result.propositions.count { it.id == "s1" })
    }

    @Test
    fun `HYBRID truncates to topK after merge`() {
        val store = FragmentRichStore(
            vectorHits = listOf(scored(proposition("a"), 0.9), scored(proposition("b"), 0.8)),
            neighbourhood = GraphNeighborhood(
                "A",
                listOf(RelatedEntity("B", listOf(proposition("c"), proposition("d")))),
            ),
        )
        val router = RetrievalRouter(store, GraphQuery(store), contextId)

        val result = router.retrieve(
            DiscoveryQuery(mode = RetrievalMode.HYBRID, text = "q", entityId = "A", topK = 2),
        )

        assertEquals(2, result.propositions.size)
        assertEquals(listOf("a", "b"), result.propositions.map { it.id })
    }

    @Test
    fun `HYBRID degrades to graph-only and reports unsupported when vector fragment absent`() {
        // A graph-capable but vector-absent store: use GraphQuery's portable path over a recording store.
        val store = RecordingEntityStore().apply {
            // p1 mentions both A and B so A's neighbourhood yields p1 as a via edge.
            save(
                Proposition(
                    id = "p1",
                    contextId = contextId,
                    text = "A relates to B",
                    mentions = listOf(
                        EntityMention(span = "A", type = "Entity", resolvedId = "A", role = MentionRole.SUBJECT),
                        EntityMention(span = "B", type = "Entity", resolvedId = "B", role = MentionRole.OBJECT),
                    ),
                    confidence = 0.9,
                ),
            )
        }
        val router = RetrievalRouter(store, GraphQuery(store), contextId)

        val result = router.retrieve(
            DiscoveryQuery(mode = RetrievalMode.HYBRID, text = "q", entityId = "A", topK = 10),
        )

        assertFalse(result.supported, "HYBRID reports unsupported when the vector arm is absent")
        assertEquals(listOf("p1"), result.propositions.map { it.id }, "still returns graph-only expansion")
    }

    @Test
    fun `GRAPH_WALK collects neighbourhood via edges and is supported`() {
        val viaProp = proposition("e1")
        val store = FragmentRichStore(
            vectorHits = emptyList(),
            neighbourhood = GraphNeighborhood("A", listOf(RelatedEntity("B", listOf(viaProp)))),
        )
        val router = RetrievalRouter(store, GraphQuery(store), contextId)

        val result = router.retrieve(DiscoveryQuery(mode = RetrievalMode.GRAPH_WALK, entityId = "A"))

        assertTrue(router.supports(RetrievalMode.GRAPH_WALK))
        assertEquals(listOf("e1"), result.propositions.map { it.id })
    }

    @Test
    fun `depth and topK are clamped before routing`() {
        val store = RecordingEntityStore()
        val router = RetrievalRouter(store, GraphQuery(store, contextId), contextId)

        // Excessive depth/topK must not throw; clamping keeps the request bounded.
        val result = router.retrieve(
            DiscoveryQuery(mode = RetrievalMode.ENTITY, entityId = "A", topK = 100_000, depth = 999),
        )
        assertTrue(result.supported)
    }
}

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
package com.embabel.dice.proposition.store

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.service.Cluster
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.GraphTraversalCapable
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.proposition.PropositionStoreTemplate
import com.embabel.dice.proposition.TemporalQueryCapable
import com.embabel.dice.proposition.VectorSearchCapable
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.ConcurrentHashMap

/**
 * Verifies the key contracts of the RAG-backed proposition store: it declares exactly the right
 * capability fragments, delegates CRUD faithfully to the supplementary store, and degrades
 * gracefully for capabilities it doesn't back.
 *
 * All cases run against in-memory repositories — no Docker or live graph required.
 */
class Neo4jRagPropositionRepositoryTest {

    private val contextId = ContextId("fragment-test")

    private fun proposition(text: String): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = listOf(EntityMention(span = "Jim", type = "Person", role = MentionRole.SUBJECT)),
            confidence = 0.9,
        )

    private fun entityRepository(embeddingService: EmbeddingService? = null): NamedEntityDataRepository =
        InMemoryNamedEntityDataRepository(DataDictionary.fromClasses("fragment-test"), embeddingService)

    /**
     * Builds a supplementary store with a deterministic text-keyed stub embedder so cosine
     * similarity results are stable and non-empty.
     */
    private fun vectorBackedCrud(): InMemoryPropositionRepository {
        val embeddingMap = ConcurrentHashMap<String, FloatArray>()
        embeddingMap["A likes B"] = floatArrayOf(1f, 0f, 0f)
        embeddingMap["A loves B"] = floatArrayOf(0.99f, 0.1f, 0f)
        val embeddingService = mock<EmbeddingService>()
        whenever(embeddingService.embed(any<String>())).thenAnswer { invocation ->
            val text = invocation.getArgument<String>(0)
            embeddingMap[text] ?: floatArrayOf(0f, 0f, 0f)
        }
        val store = InMemoryPropositionRepository(embeddingService)
        store.save(proposition("A likes B"))
        store.save(proposition("A loves B"))
        return store
    }

    @Test
    fun `declares vector search and omits graph and temporal capabilities`() {
        val adapter = Neo4jRagPropositionRepository(
            crud = InMemoryPropositionRepository(),
            entityRepository = entityRepository(),
        )

        assertTrue(adapter is VectorSearchCapable, "adapter declares vector search")
        assertFalse(adapter is GraphTraversalCapable, "adapter must not declare graph traversal")
        assertFalse(adapter is TemporalQueryCapable, "adapter must not declare temporal queries")

        val template = PropositionStoreTemplate(adapter)
        assertFalse(template.supportsVector, "an embedder-less supplementary store gives no vector search")
        assertTrue(
            PropositionStoreTemplate(Neo4jRagPropositionRepository(vectorBackedCrud(), entityRepository())).supportsVector,
            "a vector-backed supplementary store gives vector search",
        )
        assertFalse(template.supportsGraph, "template reports no graph support")

        val sources = assertDoesNotThrow<List<Proposition>> { template.findSources(proposition("orphan")) }
        val abstractions = assertDoesNotThrow<List<Proposition>> { template.findAbstractionsOf("missing-id") }
        assertTrue(sources.isEmpty(), "findSources degrades to empty, never throws")
        assertTrue(abstractions.isEmpty(), "findAbstractionsOf degrades to empty, never throws")
    }

    @Test
    fun `delegates CRUD to the supplementary store`() {
        val crud = InMemoryPropositionRepository()
        val adapter = Neo4jRagPropositionRepository(crud = crud, entityRepository = entityRepository())

        val saved = adapter.save(proposition("A knows B"))

        assertEquals(saved, adapter.findById(saved.id), "findById reaches the supplementary store")
        assertEquals(1, adapter.count(), "count reflects the delegated save")
        assertEquals(listOf(saved.id), adapter.findAll().map { it.id }, "findAll reflects the delegated save")
        assertEquals(listOf(saved.id), adapter.query(com.embabel.dice.proposition.PropositionQuery()).map { it.id })
        assertEquals(saved, crud.findById(saved.id), "the same object is visible directly in the supplementary store")
    }

    @Test
    fun `forwards every vector member to the supplementary store when it backs vector search`() {
        val vectorCrud = vectorBackedCrud()
        val vectorAdapter = Neo4jRagPropositionRepository(crud = vectorCrud, entityRepository = entityRepository())
        val request = TextSimilaritySearchRequest(query = "A likes B", similarityThreshold = 0.5, topK = 10)
        val query = PropositionQuery()

        val bare = vectorCrud.findSimilar(request)
        assertFalse(bare.isEmpty(), "sanity: the supplementary store returns non-empty results")
        assertEquals(
            bare.map { it.id },
            vectorAdapter.findSimilar(request).map { it.id },
            "the adapter returns the supplementary store's real similarity results",
        )

        assertEquals(
            vectorCrud.findSimilarWithScores(request, query).map { it.match.id },
            vectorAdapter.findSimilarWithScores(request, query).map { it.match.id },
            "the filtering overload forwards to the supplementary store's override",
        )

        val bareClusters = vectorCrud.findClusters(0.5, 10, query)
        assertFalse(bareClusters.isEmpty(), "sanity: the supplementary store discovers clusters")
        assertEquals(
            bareClusters.map { it.anchor.id },
            vectorAdapter.findClusters(0.5, 10, query).map { it.anchor.id },
            "findClusters forwards to the supplementary store's override",
        )
    }

    @Test
    fun `every vector member degrades to empty when the supplementary store is not vector-capable`() {
        val request = TextSimilaritySearchRequest(query = "A likes B", similarityThreshold = 0.5, topK = 10)
        val query = PropositionQuery()
        val adapter = Neo4jRagPropositionRepository(
            crud = NonVectorPropositionStore(),
            entityRepository = entityRepository(),
        )

        assertTrue(
            assertDoesNotThrow<List<Proposition>> { adapter.findSimilar(request) }.isEmpty(),
            "findSimilar degrades to empty when the store cannot back vectors",
        )
        assertTrue(
            assertDoesNotThrow<List<SimilarityResult<Proposition>>> { adapter.findSimilarWithScores(request) }.isEmpty(),
            "findSimilarWithScores degrades to empty when the store cannot back vectors",
        )
        assertTrue(
            assertDoesNotThrow<List<SimilarityResult<Proposition>>> {
                adapter.findSimilarWithScores(request, query)
            }.isEmpty(),
            "the filtering overload degrades to empty when the store cannot back vectors",
        )
        assertTrue(
            assertDoesNotThrow<List<Cluster<Proposition>>> { adapter.findClusters(0.5, 10, query) }.isEmpty(),
            "findClusters degrades to empty when the store cannot back vectors",
        )
        assertTrue(
            assertDoesNotThrow<List<SimilarityResult<Proposition>>> {
                adapter.findApproximateSimilarWithScores(request, query)
            }.isEmpty(),
            "the index search degrades to empty when the store cannot back vectors",
        )
    }

    @Test
    fun `forwards the index search to the supplementary store's own index search`() {
        val vectors = vectorBackedCrud()
        val indexHit = SimilarityResult(match = proposition("only the index search finds this"), score = 0.42)
        val crud = object : PropositionRepository by vectors {
            override fun findApproximateSimilarWithScores(
                textSimilaritySearchRequest: TextSimilaritySearchRequest,
                query: PropositionQuery,
            ): List<SimilarityResult<Proposition>> = listOf(indexHit)
        }
        val adapter = Neo4jRagPropositionRepository(crud = crud, entityRepository = entityRepository())
        val request = TextSimilaritySearchRequest(query = "A likes B", similarityThreshold = 0.5, topK = 10)

        assertEquals(
            listOf(indexHit),
            adapter.findApproximateSimilarWithScores(request, PropositionQuery()),
            "the adapter must hand the index search to the supplementary store",
        )
    }

    /**
     * A minimal [PropositionStore] that intentionally does not implement [VectorSearchCapable],
     * so the adapter's cast to [VectorSearchCapable] fails and its own empty-result fallback is
     * the code path under test — not a vector-capable delegate's internal degradation.
     */
    private class NonVectorPropositionStore : PropositionStore {
        override fun save(proposition: Proposition): Proposition = proposition
        override fun findById(id: String): Proposition? = null
        override fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition> = emptyList()
        override fun findByStatus(status: PropositionStatus): List<Proposition> = emptyList()
        override fun findByGrounding(chunkId: String): List<Proposition> = emptyList()
        override fun findByMinLevel(minLevel: Int): List<Proposition> = emptyList()
        override fun findAll(): List<Proposition> = emptyList()
        override fun delete(id: String): Boolean = false
        override fun count(): Int = 0
    }
}

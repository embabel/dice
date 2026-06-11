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
package com.embabel.dice.proposition

import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.service.Cluster
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
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
 * Contract for the graceful-degradation boundary over a proposition store.
 *
 * When the wrapped store does not honour a capability the template returns empty, typed results and
 * never throws; when it does, the template delegates to the store's real behaviour. Capability
 * presence is reported up front via supportsVector / supportsGraph.
 */
class PropositionStoreTemplateTest {

    private val contextId = ContextId("test-context")

    private fun proposition(text: String): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = listOf(EntityMention(span = "Jim", type = "Person", role = MentionRole.SUBJECT)),
            confidence = 0.9,
        )

    /**
     * A store that implements ONLY the base persistence port — no vector, graph, or temporal
     * capability. Compile-time proof that a consumer can depend on the base contract alone.
     */
    private class BaseOnlyStore : PropositionStore {
        private val store = mutableMapOf<String, Proposition>()
        override fun save(proposition: Proposition): Proposition {
            store[proposition.id] = proposition
            return proposition
        }
        override fun findById(id: String): Proposition? = store[id]
        override fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition> = emptyList()
        override fun findByStatus(status: PropositionStatus): List<Proposition> = emptyList()
        override fun findByGrounding(chunkId: String): List<Proposition> = emptyList()
        override fun findByMinLevel(minLevel: Int): List<Proposition> = emptyList()
        override fun findAll(): List<Proposition> = store.values.toList()
        override fun delete(id: String): Boolean = store.remove(id) != null
        override fun count(): Int = store.size
    }

    /**
     * Builds a vector-backed in-memory repository whose similarity search returns real, non-empty
     * results, using a stub embedder keyed by text so cosine similarity is deterministic.
     */
    private fun vectorBackedStore(): InMemoryPropositionRepository {
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
    fun `returns empty and does not throw when the store lacks vector search`() {
        val template = PropositionStoreTemplate(BaseOnlyStore())
        val request = TextSimilaritySearchRequest(query = "anything", similarityThreshold = 0.5, topK = 10)

        assertFalse(template.supportsVector, "a base-only store does not support vector search")
        val similar = assertDoesNotThrow<List<Proposition>> { template.findSimilar(request) }
        val clusters = assertDoesNotThrow<List<Cluster<Proposition>>> { template.findClusters() }
        assertTrue(similar.isEmpty(), "findSimilar degrades to empty when vector search is absent")
        assertTrue(clusters.isEmpty(), "findClusters degrades to empty when vector search is absent")
    }

    @Test
    fun `returns empty and does not throw when the store lacks graph traversal`() {
        val template = PropositionStoreTemplate(BaseOnlyStore())

        assertFalse(template.supportsGraph, "a base-only store does not support graph traversal")
        val sources = assertDoesNotThrow<List<Proposition>> { template.findSources(proposition("orphan")) }
        val abstractions = assertDoesNotThrow<List<Proposition>> { template.findAbstractionsOf("missing-id") }
        assertTrue(sources.isEmpty(), "findSources degrades to empty when graph traversal is absent")
        assertTrue(abstractions.isEmpty(), "findAbstractionsOf degrades to empty when graph traversal is absent")
    }

    @Test
    fun `delegates to a vector-capable store`() {
        val store = vectorBackedStore()
        val template = PropositionStoreTemplate(store)
        val request = TextSimilaritySearchRequest(query = "A likes B", similarityThreshold = 0.5, topK = 10)

        assertTrue(template.supportsVector, "a vector-backed store supports vector search")
        val bare = store.findSimilar(request)
        assertFalse(bare.isEmpty(), "sanity: bare store returns non-empty similarity results")
        assertEquals(
            bare.map { it.id },
            template.findSimilar(request).map { it.id },
            "findSimilar through the template must equal the store's real results",
        )
    }
}

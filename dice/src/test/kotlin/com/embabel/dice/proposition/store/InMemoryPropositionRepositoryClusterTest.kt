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
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.ConcurrentHashMap

class InMemoryPropositionRepositoryClusterTest {

    private val embeddingMap = ConcurrentHashMap<String, FloatArray>()
    private lateinit var embeddingService: EmbeddingService
    private lateinit var repo: InMemoryPropositionRepository

    private val testContext = ContextId("test")

    @BeforeEach
    fun setUp() {
        embeddingMap.clear()
        embeddingService = mock<EmbeddingService>()
        whenever(embeddingService.embed(any<String>())).thenAnswer { invocation ->
            val text = invocation.getArgument<String>(0)
            embeddingMap[text] ?: floatArrayOf(0f, 0f, 0f)
        }
        repo = InMemoryPropositionRepository(embeddingService)
    }

    private fun proposition(text: String, entityIds: List<String> = emptyList()): Proposition =
        Proposition(
            contextId = testContext,
            text = text,
            mentions = entityIds.map { EntityMention(span = it, type = "Person", resolvedId = it) },
            confidence = 0.9,
        )

    private fun setEmbedding(text: String, embedding: FloatArray) {
        embeddingMap[text] = embedding
    }

    @Test
    fun `empty repository returns empty list`() {
        val clusters = repo.findClusters()
        assertTrue(clusters.isEmpty())
    }

    @Test
    fun `single proposition returns empty list`() {
        setEmbedding("A likes B", floatArrayOf(1f, 0f, 0f))
        repo.save(proposition("A likes B"))

        val clusters = repo.findClusters()
        assertTrue(clusters.isEmpty())
    }

    @Test
    fun `two similar propositions form one cluster`() {
        setEmbedding("A likes B", floatArrayOf(1f, 0f, 0f))
        setEmbedding("A loves B", floatArrayOf(0.99f, 0.1f, 0f))
        val p1 = repo.save(proposition("A likes B"))
        val p2 = repo.save(proposition("A loves B"))

        val clusters = repo.findClusters(similarityThreshold = 0.9)
        assertEquals(1, clusters.size)

        val cluster = clusters[0]
        // Anchor should be the one with the lesser ID
        val expectedAnchor = if (p1.id < p2.id) p1 else p2
        val expectedSimilar = if (p1.id < p2.id) p2 else p1
        assertEquals(expectedAnchor.id, cluster.anchor.id)
        assertEquals(1, cluster.similar.size)
        assertEquals(expectedSimilar.id, cluster.similar[0].match.id)
    }

    @Test
    fun `two dissimilar propositions return empty list`() {
        setEmbedding("A likes B", floatArrayOf(1f, 0f, 0f))
        setEmbedding("X hates Y", floatArrayOf(0f, 0f, 1f))
        repo.save(proposition("A likes B"))
        repo.save(proposition("X hates Y"))

        val clusters = repo.findClusters(similarityThreshold = 0.9)
        assertTrue(clusters.isEmpty())
    }

    @Test
    fun `three propositions with partial similarity`() {
        // A and B are similar, C is different
        setEmbedding("A likes B", floatArrayOf(1f, 0f, 0f))
        setEmbedding("A loves B", floatArrayOf(0.99f, 0.1f, 0f))
        setEmbedding("X hates Y", floatArrayOf(0f, 0f, 1f))
        val p1 = repo.save(proposition("A likes B"))
        val p2 = repo.save(proposition("A loves B"))
        repo.save(proposition("X hates Y"))

        val clusters = repo.findClusters(similarityThreshold = 0.9)
        assertEquals(1, clusters.size)

        val cluster = clusters[0]
        val clusterIds = setOf(cluster.anchor.id) + cluster.similar.map { it.match.id }.toSet()
        assertTrue(p1.id in clusterIds)
        assertTrue(p2.id in clusterIds)
    }

    @Test
    fun `query filtering limits participation`() {
        setEmbedding("A likes B", floatArrayOf(1f, 0f, 0f))
        setEmbedding("A loves B", floatArrayOf(0.99f, 0.1f, 0f))
        val ctx1 = ContextId("ctx1")
        val ctx2 = ContextId("ctx2")

        val p1 = Proposition(contextId = ctx1, text = "A likes B", mentions = emptyList(), confidence = 0.9)
        val p2 = Proposition(contextId = ctx2, text = "A loves B", mentions = emptyList(), confidence = 0.9)
        repo.save(p1)
        repo.save(p2)

        // Only include ctx1 propositions — only one prop, so no clusters
        val clusters = repo.findClusters(
            similarityThreshold = 0.5,
            query = PropositionQuery(contextId = ctx1),
        )
        assertTrue(clusters.isEmpty())
    }

    @Test
    fun `topK limits similar items per cluster`() {
        // Create 4 very similar propositions
        setEmbedding("text-a", floatArrayOf(1f, 0f, 0f))
        setEmbedding("text-b", floatArrayOf(0.99f, 0.1f, 0f))
        setEmbedding("text-c", floatArrayOf(0.98f, 0.15f, 0f))
        setEmbedding("text-d", floatArrayOf(0.97f, 0.2f, 0f))

        repo.save(proposition("text-a"))
        repo.save(proposition("text-b"))
        repo.save(proposition("text-c"))
        repo.save(proposition("text-d"))

        val clusters = repo.findClusters(similarityThreshold = 0.5, topK = 1)
        // Each cluster can have at most 1 similar item
        for (cluster in clusters) {
            assertTrue(cluster.similar.size <= 1)
        }
    }

    @Test
    fun `no symmetric duplicates`() {
        setEmbedding("A likes B", floatArrayOf(1f, 0f, 0f))
        setEmbedding("A loves B", floatArrayOf(0.99f, 0.1f, 0f))
        val p1 = repo.save(proposition("A likes B"))
        val p2 = repo.save(proposition("A loves B"))

        val clusters = repo.findClusters(similarityThreshold = 0.5)

        // Exactly one cluster — no A->B and B->A
        assertEquals(1, clusters.size)

        // Anchor has the lesser ID
        val anchor = clusters[0].anchor
        val similar = clusters[0].similar[0].match
        assertTrue(anchor.id < similar.id)
    }

    @Test
    fun `clusters ordered by size descending`() {
        // Group 1: 3 similar vectors (will produce larger cluster)
        setEmbedding("g1-a", floatArrayOf(1f, 0f, 0f))
        setEmbedding("g1-b", floatArrayOf(0.99f, 0.1f, 0f))
        setEmbedding("g1-c", floatArrayOf(0.98f, 0.15f, 0f))
        // Group 2: 2 similar vectors (smaller cluster)
        setEmbedding("g2-a", floatArrayOf(0f, 1f, 0f))
        setEmbedding("g2-b", floatArrayOf(0.1f, 0.99f, 0f))

        repo.save(proposition("g1-a"))
        repo.save(proposition("g1-b"))
        repo.save(proposition("g1-c"))
        repo.save(proposition("g2-a"))
        repo.save(proposition("g2-b"))

        val clusters = repo.findClusters(similarityThreshold = 0.8)
        assertTrue(clusters.size >= 2)
        // First cluster should have more or equal similar items than second
        assertTrue(clusters[0].similar.size >= clusters[1].similar.size)
    }
}

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
package com.embabel.dice.projection.memory.collector

import com.embabel.agent.core.ContextId
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.ConcurrentHashMap

class VectorCandidatePairSourceTest {

    private val contextId = ContextId("test-context")
    private val embeddingMap = ConcurrentHashMap<String, FloatArray>()
    private lateinit var embeddingService: EmbeddingService
    private lateinit var repo: InMemoryPropositionRepository

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

    private fun proposition(text: String): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = emptyList<EntityMention>(),
            confidence = 0.8,
        )

    private fun setEmbedding(text: String, embedding: FloatArray) {
        embeddingMap[text] = embedding
    }

    @Test
    fun `proposes a pair for two similar candidates carrying the cluster cosine`() {
        setEmbedding("A likes B", floatArrayOf(1f, 0f, 0f))
        setEmbedding("A loves B", floatArrayOf(0.99f, 0.1f, 0f))
        val p1 = repo.save(proposition("A likes B"))
        val p2 = repo.save(proposition("A loves B"))

        val source = VectorCandidatePairSource(repo, similarityThreshold = 0.9, topK = 10)
        val pairs = source.propose(listOf(p1, p2), contextId)

        assertEquals(1, pairs.size)
        val pair = pairs[0]
        assertEquals(setOf(p1.id, p2.id), setOf(pair.anchor.id, pair.member.id))
        assertTrue(pair.proposalScore != null && pair.proposalScore!! > 0.9)
        // Canonicalized: the smaller id is the anchor.
        assertEquals(minOf(p1.id, p2.id), pair.anchor.id)
    }

    @Test
    fun `only proposes pairs where both members are in the candidate set`() {
        setEmbedding("A likes B", floatArrayOf(1f, 0f, 0f))
        setEmbedding("A loves B", floatArrayOf(0.99f, 0.1f, 0f))
        val p1 = repo.save(proposition("A likes B"))
        repo.save(proposition("A loves B"))

        val source = VectorCandidatePairSource(repo, similarityThreshold = 0.9, topK = 10)
        // Only p1 is in the candidate snapshot.
        val pairs = source.propose(listOf(p1), contextId)

        assertTrue(pairs.isEmpty())
    }

    @Test
    fun `dissimilar candidates produce no pairs`() {
        setEmbedding("A likes B", floatArrayOf(1f, 0f, 0f))
        setEmbedding("X hates Y", floatArrayOf(0f, 0f, 1f))
        val p1 = repo.save(proposition("A likes B"))
        val p2 = repo.save(proposition("X hates Y"))

        val source = VectorCandidatePairSource(repo, similarityThreshold = 0.9, topK = 10)
        val pairs = source.propose(listOf(p1, p2), contextId)

        assertTrue(pairs.isEmpty())
    }
}

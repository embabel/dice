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
import com.embabel.dice.projection.memory.DuplicateCollectorStrategy
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.spi.InMemoryCollectorTraceStore
import com.embabel.dice.spi.InMemoryConnectedComponentsFinder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.ConcurrentHashMap

class MultiSignalCollectorStrategyTest {

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

    private fun proposition(
        text: String,
        confidence: Double = 0.8,
        reinforceCount: Int = 0,
    ): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = emptyList<EntityMention>(),
            confidence = confidence,
            decay = 0.0,
            reinforceCount = reinforceCount,
        )

    private fun setEmbedding(text: String, embedding: FloatArray) {
        embeddingMap[text] = embedding
    }

    /** Builds a vector-only strategy: matchThreshold <= similarityThreshold so nothing dice kept is dropped. */
    private fun vectorOnlyStrategy(
        traceStore: InMemoryCollectorTraceStore = InMemoryCollectorTraceStore(),
        similarityThreshold: Double = 0.7,
        matchThreshold: Double = 0.6,
    ): MultiSignalCollectorStrategy =
        MultiSignalCollectorStrategy(
            pairSources = listOf(VectorCandidatePairSource(repo, similarityThreshold = similarityThreshold)),
            scorers = listOf(VectorSignalScorer()),
            componentsFinder = InMemoryConnectedComponentsFinder(),
            traceStore = traceStore,
            matchThreshold = matchThreshold,
        )

    @Test
    fun `reproduces DuplicateCollectorStrategy on a simple duplicate pair`() {
        setEmbedding("strong statement", floatArrayOf(1f, 0f, 0f))
        setEmbedding("weak statement", floatArrayOf(0.99f, 0.1f, 0f))
        val strong = repo.save(proposition("strong statement", confidence = 0.9))
        val weak = repo.save(proposition("weak statement", confidence = 0.3))
        val candidates = listOf(strong, weak)

        val expected = DuplicateCollectorStrategy(similarityThreshold = 0.7).mark(candidates, repo, contextId)
        val actual = vectorOnlyStrategy().mark(candidates, repo, CollectorRunContext("run-1", contextId))

        assertEquals(expected.map { it.propositionId to it.reason }, actual.map { it.propositionId to it.reason })
    }

    @Test
    fun `reproduces DuplicateCollectorStrategy on an overlapping three-way component`() {
        setEmbedding("a", floatArrayOf(1f, 0f, 0f))
        setEmbedding("b", floatArrayOf(0.99f, 0.1f, 0f))
        setEmbedding("c", floatArrayOf(0.985f, 0.12f, 0f))
        val a = repo.save(proposition("a", confidence = 0.5))
        val b = repo.save(proposition("b", confidence = 0.9))
        val c = repo.save(proposition("c", confidence = 0.7))
        val candidates = listOf(a, b, c)

        val expected = DuplicateCollectorStrategy(similarityThreshold = 0.7).mark(candidates, repo, contextId)
        val actual = vectorOnlyStrategy().mark(candidates, repo, CollectorRunContext("run-2", contextId))

        assertEquals(
            expected.map { it.propositionId to it.reason }.toSet(),
            actual.map { it.propositionId to it.reason }.toSet(),
        )
    }

    @Test
    fun `reproduces DuplicateCollectorStrategy when there are no duplicates`() {
        setEmbedding("alpha", floatArrayOf(1f, 0f, 0f))
        setEmbedding("omega", floatArrayOf(0f, 0f, 1f))
        val alpha = repo.save(proposition("alpha"))
        val omega = repo.save(proposition("omega"))
        val candidates = listOf(alpha, omega)

        val expected = DuplicateCollectorStrategy(similarityThreshold = 0.7).mark(candidates, repo, contextId)
        val actual = vectorOnlyStrategy().mark(candidates, repo, CollectorRunContext("run-3", contextId))

        assertTrue(expected.isEmpty())
        assertTrue(actual.isEmpty())
    }

    @Test
    fun `records candidate edges, components and a decision under the run id`() {
        setEmbedding("strong statement", floatArrayOf(1f, 0f, 0f))
        setEmbedding("weak statement", floatArrayOf(0.99f, 0.1f, 0f))
        val strong = repo.save(proposition("strong statement", confidence = 0.9))
        val weak = repo.save(proposition("weak statement", confidence = 0.3))
        val candidates = listOf(strong, weak)
        val traceStore = InMemoryCollectorTraceStore()

        vectorOnlyStrategy(traceStore).mark(candidates, repo, CollectorRunContext("trace-run", contextId))

        val edges = traceStore.edgesFor("trace-run")
        assertTrue(edges.isNotEmpty())
        assertTrue(edges.any { setOf(it.anchorId, it.memberId) == setOf(strong.id, weak.id) })

        val components = traceStore.componentsFor("trace-run")
        assertTrue(components.isNotEmpty())

        val decisions = traceStore.decisionsFor("trace-run")
        assertEquals(1, decisions.size)
        val decision = decisions[0]
        assertEquals(strong.id, decision.survivorId)
        assertEquals(1, decision.retired.size)
        val retired = decision.retired[0]
        assertEquals(weak.id, retired.propositionId)
        assertEquals(weak.status, retired.priorStatus)
        assertEquals(weak.grounding, retired.foldedGrounding)
        assertEquals(weak.sourceIds, retired.foldedSourceIds)
    }
}

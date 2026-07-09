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
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.spi.InMemoryCollectorTraceStore
import com.embabel.dice.spi.InMemoryConnectedComponentsFinder
import com.embabel.dice.spi.MarkReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.ConcurrentHashMap

/**
 * Wires [MultiSignalCollectorStrategy] with all six built-in signals (vector, lexical,
 * entity-overlap, grounding-overlap, provenance-overlap, polarity-veto) and the vector pair
 * source, then exercises behaviours that only show up once every signal is in the blend
 * together — agreement across signals driving a merge, and the zero-weight veto signal
 * excluding a merge without dragging down the persisted score.
 */
class MultiSignalIntegrationTest {

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

    private fun setEmbedding(text: String, embedding: FloatArray) {
        embeddingMap[text] = embedding
    }

    private fun mention(resolvedId: String, role: MentionRole = MentionRole.SUBJECT): EntityMention =
        EntityMention(span = resolvedId, type = "Organization", resolvedId = resolvedId, role = role)

    private fun proposition(
        text: String,
        mentions: List<EntityMention> = emptyList(),
        confidence: Double = 0.8,
    ): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = mentions,
            confidence = confidence,
        )

    private fun allSignalsStrategy(
        traceStore: InMemoryCollectorTraceStore,
        similarityThreshold: Double = 0.7,
        matchThreshold: Double = 0.6,
    ): MultiSignalCollectorStrategy =
        MultiSignalCollectorStrategy(
            pairSources = listOf(VectorCandidatePairSource(repo, similarityThreshold = similarityThreshold)),
            scorers = listOf(
                VectorSignalScorer(),
                LexicalSignalScorer(),
                EntityOverlapSignalScorer(),
                GroundingOverlapSignalScorer(),
                ProvenanceOverlapSignalScorer(),
                PolarityVetoSignalScorer(),
            ),
            componentsFinder = InMemoryConnectedComponentsFinder(),
            traceStore = traceStore,
            matchThreshold = matchThreshold,
        )

    @Test
    fun `vector, lexical and entity signals agreeing merge the pair`() {
        setEmbedding("Jim works at Acme", floatArrayOf(1f, 0f, 0f))
        setEmbedding("Jim is employed at Acme", floatArrayOf(0.99f, 0.1f, 0f))
        val a = repo.save(proposition("Jim works at Acme", mentions = listOf(mention("org:acme")), confidence = 0.9))
        val b = repo.save(
            proposition("Jim is employed at Acme", mentions = listOf(mention("org:acme")), confidence = 0.6),
        )
        val traceStore = InMemoryCollectorTraceStore()

        val marks = allSignalsStrategy(traceStore).mark(
            listOf(a, b),
            repo,
            CollectorRunContext("agree-run", contextId),
        )

        assertEquals(1, marks.size)
        val duplicate = marks.single()
        assertEquals(b.id, duplicate.propositionId)
        assertEquals(a.id, (duplicate.reason as MarkReason.Duplicate).survivorId)
    }

    @Test
    fun `a shared-entity polarity contradiction is persisted as a vetoed edge and is not merged`() {
        setEmbedding("Jim works at Acme", floatArrayOf(1f, 0f, 0f))
        setEmbedding("Jim no longer works at Acme", floatArrayOf(0.99f, 0.1f, 0f))
        val a = repo.save(proposition("Jim works at Acme", mentions = listOf(mention("org:acme")), confidence = 0.9))
        val b = repo.save(
            proposition("Jim no longer works at Acme", mentions = listOf(mention("org:acme")), confidence = 0.6),
        )
        val traceStore = InMemoryCollectorTraceStore()

        val marks = allSignalsStrategy(traceStore).mark(
            listOf(a, b),
            repo,
            CollectorRunContext("veto-run", contextId),
        )

        assertTrue(marks.isEmpty(), "vetoed pair must not be marked Duplicate")

        val edges = traceStore.edgesFor("veto-run")
        val edge = edges.single { setOf(it.anchorId, it.memberId) == setOf(a.id, b.id) }
        assertTrue(edge.vetoed)
        assertTrue(edge.signals.any { it.signal == "polarity-veto" && it.veto })

        // The zero-weight veto signal must not drag the aggregate down: it should still reflect
        // the real vector/lexical/entity agreement, not 0.
        assertTrue(
            edge.aggregateScore > 0.6,
            "expected vetoed edge's aggregateScore to still reflect corroborating signals, was ${edge.aggregateScore}",
        )
    }

    @Test
    fun `unrelated propositions produce no marks and no vetoed edges`() {
        setEmbedding("Jim works at Acme", floatArrayOf(1f, 0f, 0f))
        setEmbedding("The weather is nice today", floatArrayOf(0f, 0f, 1f))
        val a = repo.save(proposition("Jim works at Acme", mentions = listOf(mention("org:acme"))))
        val b = repo.save(proposition("The weather is nice today"))
        val traceStore = InMemoryCollectorTraceStore()

        val marks = allSignalsStrategy(traceStore).mark(
            listOf(a, b),
            repo,
            CollectorRunContext("unrelated-run", contextId),
        )

        assertTrue(marks.isEmpty())
        assertFalse(traceStore.edgesFor("unrelated-run").any { it.vetoed })
    }
}

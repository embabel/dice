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
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.ProvenanceEvidenceKey
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.spi.CollectorCandidateEdge
import com.embabel.dice.spi.CollectorComponent
import com.embabel.dice.spi.CollectorDecision
import com.embabel.dice.spi.CollectorTraceStore
import com.embabel.dice.spi.InMemoryCollectorTraceStore
import com.embabel.dice.spi.InMemoryConnectedComponentsFinder
import com.embabel.dice.spi.MarkReason
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
        mentions: List<EntityMention> = emptyList(),
        provenanceEntries: List<ProvenanceEntry> = emptyList(),
    ): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = mentions,
            confidence = confidence,
            decay = 0.0,
            reinforceCount = reinforceCount,
            provenanceEntries = provenanceEntries,
        )

    private fun mention(resolvedId: String): EntityMention =
        EntityMention(span = resolvedId, type = "Organization", resolvedId = resolvedId, role = MentionRole.OBJECT)

    private fun setEmbedding(text: String, embedding: FloatArray) {
        embeddingMap[text] = embedding
    }

    /**
     * A vector-plus-polarity strategy: the vector source proposes pairs by cosine similarity
     * (a transitive chain can link two ids that were never proposed as a pair directly), and the
     * polarity scorer both contributes to directly-scored edges and backs the component-level
     * contradiction guard.
     */
    private fun vectorAndPolarityStrategy(
        traceStore: CollectorTraceStore = InMemoryCollectorTraceStore(),
        similarityThreshold: Double = 0.65,
        matchThreshold: Double = 0.6,
    ): MultiSignalCollectorStrategy =
        MultiSignalCollectorStrategy(
            pairSources = listOf(VectorCandidatePairSource(repo, similarityThreshold = similarityThreshold)),
            scorers = listOf(VectorSignalScorer(), PolarityVetoSignalScorer()),
            componentsFinder = InMemoryConnectedComponentsFinder(),
            traceStore = traceStore,
            matchThreshold = matchThreshold,
        )

    /** Builds a vector-only strategy: matchThreshold <= similarityThreshold so nothing dice kept is dropped. */
    private fun vectorOnlyStrategy(
        traceStore: CollectorTraceStore = InMemoryCollectorTraceStore(),
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

    @Test
    fun `records only distinct full evidence added by a fold`() {
        setEmbedding("strong statement", floatArrayOf(1f, 0f, 0f))
        setEmbedding("weak statement", floatArrayOf(0.99f, 0.1f, 0f))
        val locator = UriLocator("https://example.com/source")
        val revisionOne = ProvenanceEntry(locator = locator, sourceRevision = "r1")
        val revisionTwo = ProvenanceEntry(locator = locator, sourceRevision = "r2")
        val strong = repo.save(
            proposition(
                "strong statement",
                confidence = 0.9,
                provenanceEntries = listOf(revisionOne),
            )
        )
        val weak = repo.save(
            proposition(
                "weak statement",
                confidence = 0.3,
                provenanceEntries = listOf(revisionOne, revisionOne, revisionTwo, revisionTwo),
            )
        )
        val traceStore = InMemoryCollectorTraceStore()

        vectorOnlyStrategy(traceStore).mark(
            listOf(strong, weak),
            repo,
            CollectorRunContext("revision-trace-run", contextId),
        )

        val retired = traceStore.decisionsFor("revision-trace-run").single().retired.single()
        assertTrue(
            ProvenanceEvidenceKey.encode(revisionOne) != ProvenanceEvidenceKey.encode(revisionTwo)
        )
        assertEquals(
            emptyList<String>(),
            retired.foldedProvenanceRefs,
        )
        assertEquals(
            listOf(ProvenanceEvidenceKey.encode(revisionTwo)),
            retired.foldedProvenanceEvidenceKeys,
        )
    }

    @Test
    fun `does not merge a and c through a shared neighbor b when a and c contradict`() {
        // Cosine similarity isn't transitive: a-b and b-c are both proposed and score above
        // threshold, but a-c never gets proposed directly (its cosine is 0). The union-find still
        // chains a, b and c into one component - a and c must not merge into one survivor because
        // they assert opposite polarity about the same shared entity.
        setEmbedding("Jim works at Acme", floatArrayOf(1f, 0f, 0f))
        setEmbedding("Jim is around", floatArrayOf(0.7f, 0.714f, 0f))
        setEmbedding("Jim no longer works at Acme", floatArrayOf(0f, 1f, 0f))
        val a = repo.save(proposition("Jim works at Acme", mentions = listOf(mention("org:acme"))))
        val b = repo.save(proposition("Jim is around"))
        val c = repo.save(proposition("Jim no longer works at Acme", mentions = listOf(mention("org:acme"))))
        val candidates = listOf(a, b, c)

        val marks = vectorAndPolarityStrategy().mark(candidates, repo, CollectorRunContext("run-contradict", contextId))

        val mergedTogether = marks.any { mark ->
            val survivorId = (mark.reason as MarkReason.Duplicate).survivorId
            setOf(mark.propositionId, survivorId) == setOf(a.id, c.id)
        }
        assertTrue(!mergedTogether, "a and c must never be folded into the same survivor")

        // b still merges with whichever of a/c it isn't vetoed against.
        assertTrue(marks.isNotEmpty())
    }

    @Test
    fun `does not merge a and c when a-c was itself proposed, scored and vetoed`() {
        // Unlike the transitive-only case above, here every pair (a-b, b-c, a-c) is above the
        // similarity threshold and gets proposed and scored directly - a-c's edge is vetoed for
        // polarity contradiction, but the component union still chains a and c together via the
        // b bridge (a-b and b-c are eligible). The guard must catch this: a vetoed edge is still
        // a live contradiction, not something already "safely scored".
        setEmbedding("Jim works at Acme", floatArrayOf(1f, 0f, 0f))
        setEmbedding("Jim is around", floatArrayOf(0.9f, 0.4359f, 0f))
        setEmbedding("Jim no longer works at Acme", floatArrayOf(0.7420f, 0.6704f, 0f))
        val a = repo.save(proposition("Jim works at Acme", mentions = listOf(mention("org:acme"))))
        val b = repo.save(proposition("Jim is around"))
        val c = repo.save(proposition("Jim no longer works at Acme", mentions = listOf(mention("org:acme"))))
        val candidates = listOf(a, b, c)

        val marks = vectorAndPolarityStrategy().mark(candidates, repo, CollectorRunContext("run-vetoed-bridge", contextId))

        val mergedTogether = marks.any { mark ->
            val survivorId = (mark.reason as MarkReason.Duplicate).survivorId
            setOf(mark.propositionId, survivorId) == setOf(a.id, c.id)
        }
        assertTrue(!mergedTogether, "a and c must never be folded into the same survivor")

        // b still merges with whichever of a/c it isn't vetoed against.
        assertTrue(marks.isNotEmpty())
    }

    @Test
    fun `a clean chain with no contradiction still merges fully`() {
        // Same a-b-c cosine topology as above, but a and c share an entity with the *same*
        // polarity, so the contradiction guard must not touch this component at all.
        setEmbedding("Jim works at Acme", floatArrayOf(1f, 0f, 0f))
        setEmbedding("Jim is around", floatArrayOf(0.7f, 0.714f, 0f))
        setEmbedding("Jim is employed at Acme", floatArrayOf(0f, 1f, 0f))
        val a = repo.save(proposition("Jim works at Acme", mentions = listOf(mention("org:acme"))))
        val b = repo.save(proposition("Jim is around"))
        val c = repo.save(proposition("Jim is employed at Acme", mentions = listOf(mention("org:acme"))))
        val candidates = listOf(a, b, c)

        val marks = vectorAndPolarityStrategy().mark(candidates, repo, CollectorRunContext("run-clean", contextId))

        // All three end up in one merge group: 2 duplicate marks, one shared survivor.
        assertEquals(2, marks.size)
        val survivors = marks.map { (it.reason as MarkReason.Duplicate).survivorId }.toSet()
        assertEquals(1, survivors.size)
        val allIds = setOf(a.id, b.id, c.id)
        val markedIds = marks.map { it.propositionId }.toSet()
        assertEquals(allIds - survivors.first(), markedIds)
    }

    /** A [CollectorTraceStore] whose every record call throws, simulating a broken trace backend. */
    private class ThrowingCollectorTraceStore : CollectorTraceStore {
        override fun recordRunContext(runId: String, contextId: ContextId): Unit =
            throw RuntimeException("trace store unavailable")

        override fun recordCandidateEdges(runId: String, edges: List<CollectorCandidateEdge>): Unit =
            throw RuntimeException("trace store unavailable")

        override fun recordComponents(runId: String, components: List<CollectorComponent>): Unit =
            throw RuntimeException("trace store unavailable")

        override fun recordDecision(runId: String, decision: CollectorDecision): Unit =
            throw RuntimeException("trace store unavailable")

        override fun deleteTracesForContext(contextId: ContextId): Unit =
            throw RuntimeException("trace store unavailable")
    }

    @Test
    fun `still produces marks when the trace store fails on every write`() {
        setEmbedding("strong statement", floatArrayOf(1f, 0f, 0f))
        setEmbedding("weak statement", floatArrayOf(0.99f, 0.1f, 0f))
        val strong = repo.save(proposition("strong statement", confidence = 0.9))
        val weak = repo.save(proposition("weak statement", confidence = 0.3))
        val candidates = listOf(strong, weak)

        val actual = vectorOnlyStrategy(traceStore = ThrowingCollectorTraceStore())
            .mark(candidates, repo, CollectorRunContext("trace-failure-run", contextId))

        assertEquals(1, actual.size)
        assertEquals(weak.id, actual[0].propositionId)
    }
}

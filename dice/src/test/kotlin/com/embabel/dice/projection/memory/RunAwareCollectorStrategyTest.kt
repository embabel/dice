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
package com.embabel.dice.projection.memory

import com.embabel.agent.core.ContextId
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.dice.projection.lineage.InMemoryCollectorRecordStore
import com.embabel.dice.projection.memory.collector.CollectorRunContext
import com.embabel.dice.projection.memory.collector.MultiSignalCollectorStrategy
import com.embabel.dice.projection.memory.collector.VectorCandidatePairSource
import com.embabel.dice.projection.memory.collector.VectorSignalScorer
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.spi.InMemoryCollectorTraceStore
import com.embabel.dice.spi.InMemoryConnectedComponentsFinder
import com.embabel.dice.spi.MarkReason
import com.embabel.dice.spi.PropositionMark
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
 * Covers the runId wiring between [DefaultCollectorRunner] and a [RunAwareCollectorStrategy]
 * (here, [MultiSignalCollectorStrategy]): the same runId the runner mints must show up in the
 * returned [CollectorRunResult], the [com.embabel.dice.projection.lineage.CollectorRecordStore],
 * and the strategy's own trace store — and a strategy driven with a blank runId (the `collect()`
 * path, or the legacy SAM bridge) must write no trace rows at all.
 */
class RunAwareCollectorStrategyTest {

    private val contextId = ContextId("test-context")
    private val embeddingMap = ConcurrentHashMap<String, FloatArray>()
    private lateinit var embeddingService: EmbeddingService
    private lateinit var repo: InMemoryPropositionRepository
    private lateinit var traceStore: InMemoryCollectorTraceStore
    private lateinit var recordStore: InMemoryCollectorRecordStore

    @BeforeEach
    fun setUp() {
        embeddingMap.clear()
        embeddingService = mock<EmbeddingService>()
        whenever(embeddingService.embed(any<String>())).thenAnswer { invocation ->
            val text = invocation.getArgument<String>(0)
            embeddingMap[text] ?: floatArrayOf(0f, 0f, 0f)
        }
        repo = InMemoryPropositionRepository(embeddingService)
        traceStore = InMemoryCollectorTraceStore()
        recordStore = InMemoryCollectorRecordStore()
    }

    private fun proposition(text: String, confidence: Double = 0.8): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = emptyList<EntityMention>(),
            confidence = confidence,
            decay = 0.0,
        )

    private fun setEmbedding(text: String, embedding: FloatArray) {
        embeddingMap[text] = embedding
    }

    /** Vector-only strategy: matchThreshold <= similarityThreshold so nothing dice kept is dropped. */
    private fun multiSignalStrategy(): MultiSignalCollectorStrategy =
        MultiSignalCollectorStrategy(
            pairSources = listOf(VectorCandidatePairSource(repo, similarityThreshold = 0.7)),
            scorers = listOf(VectorSignalScorer()),
            componentsFinder = InMemoryConnectedComponentsFinder(),
            traceStore = traceStore,
            matchThreshold = 0.6,
        )

    private fun duplicatePair(): Pair<Proposition, Proposition> {
        setEmbedding("strong statement", floatArrayOf(1f, 0f, 0f))
        setEmbedding("weak statement", floatArrayOf(0.99f, 0.1f, 0f))
        val strong = repo.save(proposition("strong statement", confidence = 0.9))
        val weak = repo.save(proposition("weak statement", confidence = 0.3))
        return strong to weak
    }

    private fun runner(): CollectorRunner =
        CollectorRunner
            .withRepository(repo)
            .withStrategy(multiSignalStrategy())
            .withRecordStore(recordStore)
            .build()

    @Test
    fun `run() threads the same runId through the result, record store, and strategy trace store`() {
        duplicatePair()

        val result = runner().run(contextId, dryRun = false)

        assertFalse(result.runId.isBlank())
        assertTrue(recordStore.findByRun(result.runId).isNotEmpty())
        assertTrue(traceStore.edgesFor(result.runId).isNotEmpty())
        assertTrue(traceStore.componentsFor(result.runId).isNotEmpty())
        assertEquals(1, traceStore.decisionsFor(result.runId).size)
    }

    @Test
    fun `collect() produces marks but writes no trace rows (blank runId guard)`() {
        duplicatePair()

        val result = runner().collect(contextId)

        assertTrue(result.runId.isBlank())
        assertTrue(result.marks.isNotEmpty())
        assertTrue(traceStore.edgesFor("").isEmpty())
        assertTrue(traceStore.componentsFor("").isEmpty())
        assertTrue(traceStore.decisionsFor("").isEmpty())
    }

    @Test
    fun `dryRun run() still persists trace rows under the real runId`() {
        duplicatePair()

        val result = runner().run(contextId, dryRun = true)

        assertTrue(result.dryRun)
        assertFalse(result.runId.isBlank())
        assertTrue(traceStore.edgesFor(result.runId).isNotEmpty())
        assertTrue(traceStore.componentsFor(result.runId).isNotEmpty())
        assertEquals(1, traceStore.decisionsFor(result.runId).size)
        // Dry run previews: no proposition status is actually changed.
        assertTrue(result.applied.isEmpty())
    }

    @Test
    fun `a legacy (non-run-aware) CollectorStrategy still works through the runner`() {
        val (strong, weak) = duplicatePair()

        // Wired via withDuplicateDetection(): a plain CollectorStrategy, not RunAwareCollectorStrategy.
        val legacyRunner = CollectorRunner
            .withRepository(repo)
            .withDuplicateDetection()
            .withRecordStore(recordStore)
            .build()

        val result = legacyRunner.run(contextId, dryRun = false)

        assertFalse(result.runId.isBlank())
        assertEquals(1, result.applied.size)
        assertEquals(weak.id, result.applied.single().propositionId)
        assertEquals(MarkReason.Duplicate(survivorId = strong.id), result.applied.single().reason)
    }

    @Test
    fun `the legacy SAM bridge on RunAwareCollectorStrategy uses a blank runId and writes no trace`() {
        val (strong, weak) = duplicatePair()
        val candidates = listOf(strong, weak)

        // Driven directly through the legacy 3-arg CollectorStrategy.mark SAM, bypassing any runner.
        val strategy: CollectorStrategy = multiSignalStrategy()
        val marks: List<PropositionMark> = strategy.mark(candidates, repo, contextId)

        assertEquals(1, marks.size)
        assertEquals(weak.id, marks.single().propositionId)
        assertTrue(traceStore.edgesFor("").isEmpty())
        assertTrue(traceStore.componentsFor("").isEmpty())
        assertTrue(traceStore.decisionsFor("").isEmpty())
    }

    @Test
    fun `trace rows from the mark phase survive a mid-run mutation failure`() {
        duplicatePair()
        val failingRepo = FailingSaveRepository(repo)
        val failingRunner = CollectorRunner
            .withRepository(failingRepo)
            .withStrategy(multiSignalStrategy())
            .withRecordStore(recordStore)
            .build()

        val thrown = runCatching { failingRunner.run(contextId, dryRun = false) }

        assertTrue(thrown.isFailure)
        // Trace writes happen in the mark phase, strictly before any mutation is attempted, so
        // they must have landed even though the later save() blew up. The runId minted for this
        // aborted run is only visible to us via the trace store's own bookkeeping, so recover it
        // from whichever non-blank run the store now knows about.
        val recordedRun = recordStore.runs().single()
        assertTrue(traceStore.edgesFor(recordedRun.runId).isNotEmpty())
        assertTrue(traceStore.componentsFor(recordedRun.runId).isNotEmpty())
        assertEquals(1, traceStore.decisionsFor(recordedRun.runId).size)
    }

    /** Wraps [delegate], throwing on every [save] so a mutation-phase failure can be simulated. */
    private class FailingSaveRepository(
        private val delegate: PropositionRepository,
    ) : PropositionRepository by delegate {
        override fun save(proposition: Proposition): Proposition = throw RuntimeException("storage failure")
    }
}

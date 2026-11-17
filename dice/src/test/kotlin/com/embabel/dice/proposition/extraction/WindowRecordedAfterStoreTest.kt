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
package com.embabel.dice.proposition.extraction

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntity
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.Relations
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SourceAnalysisRequestEvent
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.incremental.BookmarkKey
import com.embabel.dice.incremental.InMemoryChunkHistoryStore
import com.embabel.dice.incremental.IncrementalSource
import com.embabel.dice.pipeline.ChunkPropositionResult
import com.embabel.dice.pipeline.PropositionPipeline
import com.embabel.dice.projection.graph.GraphProjectionService
import com.embabel.dice.projection.graph.ProjectedRelationship
import com.embabel.dice.projection.graph.RelationshipPersistenceResult
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.ProjectionResults
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionPersistenceResult
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.SuggestedPropositions
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * On the event path a window is marked as processed only after its claims are stored, and a
 * failure from the store step onwards reaches whoever called `extractPropositions`.
 *
 * A window recorded ahead of a failed store is never analysed again, so its claims are lost for
 * good. A failure that is logged and swallowed lets a host transaction commit work it should roll
 * back. Each case below checks both halves: what the caller sees, and whether the next event for
 * the same source analyses the window again.
 */
class WindowRecordedAfterStoreTest {

    private val tenant = ContextId("window-tenant")
    private val runRef = ExtractionRunRef("window-run")
    private val sourceId = "window-source"

    /**
     * An in-memory repository that throws from the save calls listed in [failingSaves], counted
     * from one, and runs [beforeFailing] just before it throws.
     */
    private class FlakyRepository(
        private val failingSaves: Set<Int>,
        private val beforeFailing: () -> Unit = {},
        private val delegate: PropositionRepository = InMemoryPropositionRepository(),
    ) : PropositionRepository by delegate {

        private val saves = AtomicInteger()

        override fun save(proposition: Proposition): Proposition {
            if (saves.incrementAndGet() in failingSaves) {
                beforeFailing()
                throw IllegalStateException("store is down")
            }
            return delegate.save(proposition)
        }

        override fun saveAll(propositions: Collection<Proposition>) {
            propositions.forEach { save(it) }
        }

        override fun saveAllReturningCanonical(
            propositions: Collection<Proposition>,
        ): PropositionPersistenceResult {
            val inputs = propositions.toList()
            return PropositionPersistenceResult.of(inputs, inputs.map { save(it) })
        }
    }

    private class Harness(
        val extraction: IncrementalPropositionExtraction,
        val pipeline: PropositionPipeline,
        val chunkResult: ChunkPropositionResult,
        val repository: PropositionRepository,
        val history: InMemoryChunkHistoryStore,
    )

    private val claim = Proposition(
        id = "claim-1",
        contextId = tenant,
        text = "Alice likes coffee",
        mentions = listOf(
            EntityMention(span = "Alice", type = "Person", resolvedId = "e-alice", role = MentionRole.SUBJECT),
        ),
        confidence = 0.9,
        grounding = listOf("chunk-1"),
    )

    private fun user(): NamedEntity = mockk<NamedEntity>(relaxed = true).also {
        every { it.id } returns tenant.value
        every { it.name } returns "Alice"
    }

    private fun harness(
        repository: PropositionRepository = FlakyRepository(failingSaves = emptySet()),
        projectionFails: Boolean = false,
    ): Harness {
        val pipeline = mockk<PropositionPipeline>()
        val result = ChunkPropositionResult.Success(
            chunkId = "chunk-1",
            suggestedPropositions = SuggestedPropositions("chunk-1", emptyList()),
            entityResolutions = Resolutions<SuggestedEntityResolution>(setOf("chunk-1"), emptyList()),
            propositions = listOf(claim),
        )
        every { pipeline.processChunk(any(), any()) } returns result

        val projection = mockk<GraphProjectionService>()
        every { projection.projectAndPersist(any()) } answers {
            if (projectionFails) throw IllegalStateException("projector is down")
            ProjectionResults<ProjectedRelationship>(emptyList()) to
                RelationshipPersistenceResult(persistedCount = 0, failedCount = 0)
        }

        val history = InMemoryChunkHistoryStore()
        val extraction = IncrementalPropositionExtraction(
            propositionPipeline = pipeline,
            chunkHistoryStore = history,
            dataDictionary = DataDictionary.fromClasses("window"),
            relations = Relations.empty(),
            propositionRepository = repository,
            entityRepository = mockk<NamedEntityDataRepository>(relaxed = true),
            entityResolver = mockk<EntityResolver>(relaxed = true),
            graphProjectionService = projection,
            properties = PropositionExtractionProperties(windowSize = 1, overlapSize = 1, triggerInterval = 1),
        )
        return Harness(extraction, pipeline, result, repository, history)
    }

    private fun event(currentRun: ExtractionRunRef? = null, source: String = sourceId): SourceAnalysisRequestEvent {
        val incremental = mockk<IncrementalSource<Message>>(relaxed = true)
        every { incremental.id } returns source
        every { incremental.size } returns 1
        // Each source gets its own text, so the history store's hash check tells sources apart.
        every { incremental.getItems(any(), any()) } returns listOf(UserMessage("Alice writes in $source"))
        return object : SourceAnalysisRequestEvent(this, user()) {
            override fun incrementalSource(): IncrementalSource<Message> = incremental

            override fun currentRun(): ExtractionRunRef? = currentRun
        }
    }

    private fun Harness.bookmark() = history.getLastBookmark(BookmarkKey(tenant, sourceId))

    @Test
    fun `a store failure reaches the caller and the window is analysed again`() {
        val harness = harness(repository = FlakyRepository(failingSaves = setOf(1)))

        assertThatThrownBy { harness.extraction.extractPropositions(event()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("store is down")
        assertThat(harness.bookmark()).isNull()
        assertThat(harness.repository.findById(claim.id)).isNull()

        harness.extraction.extractPropositions(event())
        assertThat(harness.repository.findById(claim.id)).isNotNull()
        assertThat(harness.bookmark()).isNotNull()

        harness.extraction.extractPropositions(event())
        verify(exactly = 2) { harness.pipeline.processChunk(any(), any()) }
    }

    @Test
    fun `an analysis failure is logged and the window is analysed again`() {
        val harness = harness()
        val calls = AtomicInteger()
        every { harness.pipeline.processChunk(any(), any()) } answers {
            if (calls.incrementAndGet() == 1) throw IllegalStateException("model is down")
            harness.chunkResult
        }

        harness.extraction.extractPropositions(event())
        assertThat(harness.bookmark()).isNull()

        harness.extraction.extractPropositions(event())
        verify(exactly = 2) { harness.pipeline.processChunk(any(), any()) }
        assertThat(harness.repository.findById(claim.id)).isNotNull()
        assertThat(harness.bookmark()).isNotNull()
    }

    @Test
    fun `a STRICT lineage failure with no writer bound records the window and still throws`() {
        val harness = harness()
        harness.extraction.withRunLineage(null, LineageFailurePolicy.STRICT)

        assertThatThrownBy { harness.extraction.extractPropositions(event(currentRun = runRef)) }
            .isInstanceOf(LineageNotRecordedException::class.java)
        assertThat(harness.repository.findById(claim.id))
            .describedAs("the claim is stored, only its attribution failed")
            .isNotNull()
        assertThat(harness.bookmark()).isNotNull()

        harness.extraction.extractPropositions(event(currentRun = runRef))
        verify(exactly = 1) { harness.pipeline.processChunk(any(), any()) }
    }

    @Test
    fun `a bound writer that fails leaves the window unrecorded`() {
        val harness = harness()
        val writes = AtomicInteger()
        harness.extraction.withPersistUnitWriter { _ ->
            writes.incrementAndGet()
            PersistOutcome.failed(PersistStep.PROPOSITIONS, IllegalStateException("writer rolled back"), emptySet())
        }

        assertThatThrownBy { harness.extraction.extractPropositions(event()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("writer rolled back")
        assertThat(harness.bookmark()).isNull()

        assertThatThrownBy { harness.extraction.extractPropositions(event()) }
            .hasMessage("writer rolled back")
        verify(exactly = 2) { harness.pipeline.processChunk(any(), any()) }
        assertThat(writes.get()).isEqualTo(2)
    }

    @Test
    fun `a projection failure reaches the caller with the window recorded and the claim stored`() {
        val harness = harness(projectionFails = true)

        assertThatThrownBy { harness.extraction.extractPropositions(event()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("projector is down")
        assertThat(harness.repository.findById(claim.id)).isNotNull()
        assertThat(harness.bookmark()).isNotNull()

        harness.extraction.extractPropositions(event())
        verify(exactly = 1) { harness.pipeline.processChunk(any(), any()) }
    }

    @Test
    fun `an interrupted drain stops after the failing event and leaves the rest queued`() {
        // The first event holds the drain while two more queue behind it. The second save
        // interrupts the draining thread and fails, so the drain must stop there and leave the
        // third event in the queue for a later call.
        val repository = FlakyRepository(
            failingSaves = setOf(2),
            beforeFailing = { Thread.currentThread().interrupt() },
        )
        val harness = harness(repository = repository)
        val firstEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        every { harness.pipeline.processChunk(any(), any()) } answers {
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            harness.chunkResult
        }

        val surfaced = AtomicReference<Throwable?>()
        val stillInterrupted = AtomicReference<Boolean>()
        val drainer = Thread {
            try {
                harness.extraction.extractPropositions(event(source = "source-a"))
            } catch (t: Throwable) {
                surfaced.set(t)
            } finally {
                stillInterrupted.set(Thread.interrupted())
            }
        }
        drainer.start()
        assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue()
        harness.extraction.extractPropositions(event(source = "source-b"))
        harness.extraction.extractPropositions(event(source = "source-c"))
        release.countDown()
        drainer.join(10_000)

        assertThat(surfaced.get()).isInstanceOf(IllegalStateException::class.java).hasMessage("store is down")
        assertThat(stillInterrupted.get()).isTrue()
        verify(exactly = 2) { harness.pipeline.processChunk(any(), any()) }
        assertThat(harness.extraction.isIdle)
            .describedAs("the third event is still queued")
            .isFalse()
        Thread.interrupted()
    }
}

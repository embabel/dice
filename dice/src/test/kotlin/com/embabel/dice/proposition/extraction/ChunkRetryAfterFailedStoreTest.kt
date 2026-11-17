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
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.dice.common.NewEntity
import com.embabel.dice.common.Relations
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SourceAnalysisRequestEvent
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.incremental.BookmarkKey
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.incremental.InMemoryChunkHistoryStore
import com.embabel.dice.incremental.IncrementalSource
import com.embabel.dice.incremental.ProcessedChunkRecord
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
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * When the event path marks a window as processed: only once its unit is stored, or when the
 * analysis found no claims.
 */
class ChunkRetryAfterFailedStoreTest {

    private val tenant = ContextId("retry-tenant")

    private val runRef = ExtractionRunRef("retry-run")

    private val extracted = Proposition(
        id = "p-1",
        contextId = tenant,
        text = "Alice likes coffee",
        mentions = listOf(
            EntityMention(span = "Alice", type = "Person", resolvedId = "e-alice", role = MentionRole.SUBJECT),
        ),
        confidence = 0.9,
        grounding = listOf("chunk-1"),
    )

    private val result = ChunkPropositionResult.Success(
        chunkId = "chunk-1",
        suggestedPropositions = SuggestedPropositions("chunk-1", emptyList()),
        entityResolutions = Resolutions<SuggestedEntityResolution>(
            setOf("chunk-1"),
            listOf(
                NewEntity(SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "test", chunkId = "chunk-1", id = "e-alice")),
                NewEntity(SuggestedEntity(labels = listOf("Person"), name = "Bob", summary = "test", chunkId = "chunk-1", id = "e-bob")),
            ),
        ),
        propositions = listOf(extracted),
    )

    private val history = InMemoryChunkHistoryStore()

    private val bookmark = BookmarkKey(tenant, "retry-source")

    private val pipeline = mockk<PropositionPipeline>().also {
        every { it.processChunk(any(), any()) } returns result
    }

    /**
     * A user whose id is the tenant.
     *
     * @return A relaxed mock user named Alice.
     */
    private fun user(): NamedEntity = mockk<NamedEntity>(relaxed = true).also {
        every { it.id } returns tenant.value
        every { it.name } returns "Alice"
    }

    /**
     * A projection service that projects nothing.
     *
     * @return A mocked projection service.
     */
    private fun projection(): GraphProjectionService = mockk<GraphProjectionService>().also {
        every { it.projectAndPersist(any()) } returns
            (ProjectionResults<ProjectedRelationship>(emptyList()) to RelationshipPersistenceResult(persistedCount = 0, failedCount = 0))
    }

    /**
     * A proposition repository mock that answers each save with the propositions it was given.
     *
     * @return A relaxed mocked repository.
     */
    private fun answeringRepository(): PropositionRepository = mockk<PropositionRepository>(relaxed = true).also {
        every { it.saveAllReturningCanonical(any()) } answers {
            val inputs = firstArg<Collection<Proposition>>().toList()
            PropositionPersistenceResult.of(inputs, inputs)
        }
    }

    /**
     * An extractor over the shared pipeline and history store, with one-message windows.
     *
     * @param propositionRepository The repository given to the constructor.
     * @param entityRepository The entity repository given to the constructor.
     * @param historyStore The chunk history store given to the constructor.
     * @param projection The projection service.
     * @return An extractor with nothing bound.
     */
    private fun extraction(
        propositionRepository: PropositionRepository,
        entityRepository: NamedEntityDataRepository = mockk(relaxed = true),
        historyStore: ChunkHistoryStore = history,
        projection: GraphProjectionService = projection(),
    ) = IncrementalPropositionExtraction(
        propositionPipeline = pipeline,
        chunkHistoryStore = historyStore,
        dataDictionary = DataDictionary.fromClasses("retry"),
        relations = Relations.empty(),
        propositionRepository = propositionRepository,
        entityRepository = entityRepository,
        entityResolver = mockk(relaxed = true),
        graphProjectionService = projection,
        properties = PropositionExtractionProperties(windowSize = 1, overlapSize = 1, triggerInterval = 1),
    )

    /**
     * An event over a source that holds the same one message every time.
     *
     * @param currentRun The run the event carries, or null for none.
     * @return An event for the source `retry-source`.
     */
    private fun event(currentRun: ExtractionRunRef? = null): SourceAnalysisRequestEvent {
        val source = mockk<IncrementalSource<Message>>(relaxed = true)
        every { source.id } returns "retry-source"
        every { source.size } returns 1
        every { source.getItems(any(), any()) } returns listOf(UserMessage("Alice likes coffee"))
        return object : SourceAnalysisRequestEvent(this, user()) {
            override fun incrementalSource(): IncrementalSource<Message> = source

            override fun currentRun(): ExtractionRunRef? = currentRun
        }
    }

    /**
     * A history store that throws on its first record call and delegates every other call.
     *
     * @param delegate The store that holds the history.
     */
    private class FailingOnceHistory(private val delegate: ChunkHistoryStore) : ChunkHistoryStore by delegate {

        private val records = AtomicInteger()

        override fun recordProcessed(record: ProcessedChunkRecord) {
            if (records.incrementAndGet() == 1) throw IllegalStateException("history store down")
            delegate.recordProcessed(record)
        }
    }

    @Test
    fun `E1 a failed write that left nothing leaves the window unmarked and the next event stores the chunk`() {
        val claims = InMemoryPropositionRepository()
        val entities = InMemoryNamedEntityDataRepository(DataDictionary.fromClasses("retry"))
        val inMemory = PersistUnitWriter.inMemory(claims, entities)
        val writes = AtomicInteger()
        val extraction = extraction(claims, entities).withPersistUnitWriter { unit ->
            if (writes.incrementAndGet() == 1) {
                PersistOutcome.failed(PersistStep.PROPOSITIONS, IllegalStateException("claims store down"), emptySet())
            } else {
                inMemory.write(unit)
            }
        }

        extraction.extractPropositions(event())
        assertThat(claims.findAll()).isEmpty()
        assertThat(history.getLastBookmark(bookmark)).isNull()

        extraction.extractPropositions(event())
        assertThat(claims.findAll().map { it.id }).containsExactly("p-1")
        assertThat(history.getLastBookmark(bookmark)?.endIndex).isEqualTo(1)

        extraction.extractPropositions(event())
        verify(exactly = 2) { pipeline.processChunk(any(), any()) }
        assertThat(writes.get()).isEqualTo(2)
    }

    @Test
    fun `E2 with no writer bound a claim save that throws leaves the window unmarked and the next event stores the chunk`() {
        val saves = AtomicInteger()
        val propositions = mockk<PropositionRepository>(relaxed = true)
        every { propositions.saveAllReturningCanonical(any()) } answers {
            if (saves.incrementAndGet() == 1) throw IllegalStateException("claims store down")
            val inputs = firstArg<Collection<Proposition>>().toList()
            PropositionPersistenceResult.of(inputs, inputs)
        }
        val extraction = extraction(propositions)

        repeat(3) { extraction.extractPropositions(event()) }

        verify(exactly = 2) { pipeline.processChunk(any(), any()) }
        assertThat(saves.get()).isEqualTo(2)
        assertThat(history.getLastBookmark(bookmark)?.endIndex).isEqualTo(1)
    }

    @Test
    fun `E3 with no writer bound a failure after the claim save leaves the window unmarked and the next event analyses it again`() {
        val propositions = answeringRepository()
        val entities = mockk<NamedEntityDataRepository>(relaxed = true)
        val merges = AtomicInteger()
        every { entities.mergeRelationship(any(), any(), any()) } answers {
            if (merges.incrementAndGet() == 1) throw IllegalStateException("relationships down")
        }
        val extraction = extraction(propositions, entities)

        extraction.extractPropositions(event())
        assertThat(history.getLastBookmark(bookmark)).isNull()

        repeat(2) { extraction.extractPropositions(event()) }

        verify(exactly = 2) { pipeline.processChunk(any(), any()) }
        verify(exactly = 2) { propositions.saveAllReturningCanonical(any()) }
        assertThat(history.getLastBookmark(bookmark)?.endIndex).isEqualTo(1)
    }

    @Test
    fun `E4 an analysis that produced no claims marks the window`() {
        every { pipeline.processChunk(any(), any()) } returns ChunkPropositionResult.Success(
            chunkId = "chunk-1",
            suggestedPropositions = SuggestedPropositions("chunk-1", emptyList()),
            entityResolutions = Resolutions<SuggestedEntityResolution>(setOf("chunk-1"), emptyList()),
            propositions = emptyList(),
        )
        val propositions = answeringRepository()
        val extraction = extraction(propositions)

        repeat(2) { extraction.extractPropositions(event()) }

        verify(exactly = 1) { pipeline.processChunk(any(), any()) }
        verify(exactly = 0) { propositions.saveAllReturningCanonical(any()) }
    }

    @Test
    fun `E5 a stored chunk is not analysed again`() {
        val propositions = answeringRepository()
        val extraction = extraction(propositions)

        repeat(2) { extraction.extractPropositions(event()) }

        verify(exactly = 1) { pipeline.processChunk(any(), any()) }
        verify(exactly = 1) { propositions.saveAllReturningCanonical(any()) }
    }

    @Test
    fun `E6 a failed analysis leaves the window unmarked and the next event analyses it again`() {
        every { pipeline.processChunk(any(), any()) } returnsMany
            listOf(ChunkPropositionResult.Failed("chunk-1", "model unavailable"), result)
        val propositions = answeringRepository()
        val extraction = extraction(propositions)

        extraction.extractPropositions(event())
        assertThat(history.getLastBookmark(bookmark)).isNull()

        repeat(2) { extraction.extractPropositions(event()) }

        verify(exactly = 2) { pipeline.processChunk(any(), any()) }
        verify(exactly = 1) { propositions.saveAllReturningCanonical(any()) }
        assertThat(history.getLastBookmark(bookmark)?.endIndex).isEqualTo(1)
    }

    @Test
    fun `E7 a history store that throws while a stored window is marked does not stop projection`() {
        val propositions = answeringRepository()
        val projection = projection()
        val extraction = extraction(propositions, historyStore = FailingOnceHistory(history), projection = projection)

        extraction.extractPropositions(event())
        verify(exactly = 1) { propositions.saveAllReturningCanonical(any()) }
        verify(exactly = 1) { projection.projectAndPersist(any()) }
        assertThat(history.getLastBookmark(bookmark)).isNull()

        repeat(2) { extraction.extractPropositions(event()) }

        verify(exactly = 2) { pipeline.processChunk(any(), any()) }
        assertThat(history.getLastBookmark(bookmark)?.endIndex).isEqualTo(1)
    }

    @Test
    fun `E8 under STRICT a run with no run link store fails every event, saves no claim and leaves the window unmarked`() {
        val propositions = answeringRepository()
        val extraction = extraction(propositions)

        repeat(3) {
            assertThatThrownBy { extraction.extractPropositions(event(runRef)) }
                .isInstanceOf(LineageNotRecordedException::class.java)
        }

        verify(exactly = 3) { pipeline.processChunk(any(), any()) }
        verify(exactly = 0) { propositions.saveAllReturningCanonical(any()) }
        assertThat(history.getLastBookmark(bookmark)).isNull()
    }
}

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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.SocketTimeoutException
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
     * from one, and runs [beforeFailing] just before it throws. [failure] builds the exception for
     * a given save number. [onSave] runs on every save that goes through.
     */
    private class FlakyRepository(
        private val failingSaves: Set<Int>,
        private val beforeFailing: () -> Unit = {},
        private val failure: (Int) -> Exception = { IllegalStateException("store is down") },
        private val onSave: (Int) -> Unit = {},
        private val delegate: PropositionRepository = InMemoryPropositionRepository(),
    ) : PropositionRepository by delegate {

        private val saves = AtomicInteger()

        override fun save(proposition: Proposition): Proposition {
            val number = saves.incrementAndGet()
            if (number in failingSaves) {
                beforeFailing()
                throw failure(number)
            }
            val saved = delegate.save(proposition)
            onSave(number)
            return saved
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
        val history: ChunkHistoryStore,
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
        projectionFailure: () -> Exception = { IllegalStateException("projector is down") },
        history: ChunkHistoryStore = InMemoryChunkHistoryStore(),
        mintNewEntities: Boolean = false,
        mintedEntityProperties: (NamedEntity) -> Map<String, Any> = { emptyMap() },
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
            if (projectionFails) throw projectionFailure()
            ProjectionResults<ProjectedRelationship>(emptyList()) to
                RelationshipPersistenceResult(persistedCount = 0, failedCount = 0)
        }

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
            mintNewEntitiesDefault = mintNewEntities,
            mintedEntityPropertiesProvider = mintedEntityProperties,
        )
        return Harness(extraction, pipeline, result, repository, history)
    }

    private fun event(
        currentRun: ExtractionRunRef? = null,
        source: String = sourceId,
        sourceCalls: AtomicInteger = AtomicInteger(),
    ): SourceAnalysisRequestEvent {
        val incremental = mockk<IncrementalSource<Message>>(relaxed = true)
        every { incremental.id } returns source
        every { incremental.size } returns 1
        // Each source gets its own text, so the history store's hash check tells sources apart.
        every { incremental.getItems(any(), any()) } returns listOf(UserMessage("Alice writes in $source"))
        return object : SourceAnalysisRequestEvent(this, user()) {
            override fun incrementalSource(): IncrementalSource<Message> {
                sourceCalls.incrementAndGet()
                return incremental
            }

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

    /** What a drain run on its own thread ended with. */
    private class DrainRun(val surfaced: Throwable?, val stillInterrupted: Boolean)

    /**
     * Runs an event for `source-a` on its own thread and holds it in its first analysis until an
     * event for each of [queued] is queued behind it, then lets the drain finish.
     *
     * @param queued The sources to queue while the drain is held.
     * @return What the draining thread saw.
     */
    private fun Harness.drainWithQueued(vararg queued: String): DrainRun {
        val firstEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        every { pipeline.processChunk(any(), any()) } answers {
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            chunkResult
        }
        val surfaced = AtomicReference<Throwable?>()
        val stillInterrupted = AtomicReference<Boolean>()
        val drainer = Thread {
            try {
                extraction.extractPropositions(event(source = "source-a"))
            } catch (t: Throwable) {
                surfaced.set(t)
            } finally {
                stillInterrupted.set(Thread.interrupted())
            }
        }
        drainer.start()
        assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue()
        queued.forEach { extraction.extractPropositions(event(source = it)) }
        release.countDown()
        drainer.join(10_000)
        return DrainRun(surfaced.get(), stillInterrupted.get())
    }

    /**
     * Queues [events] in order and drains them all in one call on this thread. Every event but the
     * last is queued while the thread is interrupted, so no drain runs until the last call, and the
     * drain counts them all as queued when it started.
     *
     * @param events The events to drain, in queue order.
     * @return What the drain ended with. The interrupt flag is cleared before this returns.
     */
    private fun Harness.drainAll(vararg events: SourceAnalysisRequestEvent): DrainRun {
        try {
            Thread.currentThread().interrupt()
            events.dropLast(1).forEach { extraction.extractPropositions(it) }
        } finally {
            Thread.interrupted()
        }
        var surfaced: Throwable? = null
        try {
            extraction.extractPropositions(events.last())
        } catch (t: Throwable) {
            surfaced = t
        }
        return DrainRun(surfaced, Thread.interrupted())
    }

    /**
     * Runs [block] and collects what the extractor logged at `warn` while it ran.
     *
     * @param block The call to observe.
     * @return The warnings, in order.
     */
    private fun warnings(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(IncrementalPropositionExtraction::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also {
            it.start()
            logger.addAppender(it)
        }
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        return appender.list.filter { it.level == Level.WARN }
    }

    @Test
    fun `a lineage exception from the claim save leaves the window unrecorded`() {
        // The claim save itself throws a LineageNotRecordedException, so nothing is stored. Its
        // type must not be read as "the claims are saved and only the run link failed".
        val lineageFromSave = LineageNotRecordedException(ExtractionRunKey(tenant, runRef), "save failed")
        val harness = harness(
            repository = FlakyRepository(failingSaves = setOf(1), failure = { lineageFromSave }),
        )

        assertThatThrownBy { harness.extraction.extractPropositions(event()) }.isSameAs(lineageFromSave)
        assertThat(harness.repository.findById(claim.id)).isNull()
        assertThat(harness.bookmark()).isNull()

        harness.extraction.extractPropositions(event())
        verify(exactly = 2) { harness.pipeline.processChunk(any(), any()) }
        assertThat(harness.repository.findById(claim.id)).isNotNull()
        assertThat(harness.bookmark()).isNotNull()
    }

    @Test
    fun `a save listener that asks for extraction on the same thread does not analyse the window twice`() {
        lateinit var extraction: IncrementalPropositionExtraction
        val repository = FlakyRepository(
            failingSaves = emptySet(),
            onSave = { number -> if (number == 1) extraction.extractPropositions(event()) },
        )
        val harness = harness(repository = repository)
        extraction = harness.extraction

        harness.extraction.extractPropositions(event())

        verify(exactly = 1) { harness.pipeline.processChunk(any(), any()) }
        assertThat(harness.bookmark()).isNotNull()
        assertThat(harness.extraction.isIdle)
            .describedAs("the nested event ran after the window was recorded")
            .isTrue()
    }

    @Test
    fun `a call that starts interrupted stores nothing and leaves its event queued`() {
        val harness = harness()
        try {
            Thread.currentThread().interrupt()
            harness.extraction.extractPropositions(event())

            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
        verify(exactly = 0) { harness.pipeline.processChunk(any(), any()) }
        assertThat(harness.repository.findById(claim.id)).isNull()
        assertThat(harness.extraction.isIdle).isFalse()
    }

    @Test
    fun `a store that catches an interrupt and sets the flag again stops the drain`() {
        // The store catches the interrupt and sets the flag again before it throws, as is the Java
        // convention. The drain sees the flag and stops.
        val harness = harness(
            repository = FlakyRepository(
                failingSaves = setOf(1),
                beforeFailing = { Thread.currentThread().interrupt() },
                failure = { IllegalStateException("save interrupted", InterruptedException()) },
            ),
        )

        val run = harness.drainWithQueued("source-b")

        assertThat(run.surfaced).isInstanceOf(IllegalStateException::class.java).hasMessage("save interrupted")
        assertThat(run.stillInterrupted).isTrue()
        verify(exactly = 1) { harness.pipeline.processChunk(any(), any()) }
        assertThat(harness.extraction.isIdle)
            .describedAs("the second event is still queued")
            .isFalse()
    }

    @Test
    fun `a checked exception from the store reaches the caller wrapped with its step`() {
        val disk = IOException("disk is full")
        val harness = harness(repository = FlakyRepository(failingSaves = setOf(1), failure = { disk }))

        assertThatThrownBy { harness.extraction.extractPropositions(event()) }
            .isInstanceOf(PersistUnitFailedException::class.java)
            .hasCause(disk)
            .satisfies({ assertThat((it as PersistUnitFailedException).step).isEqualTo(PersistStep.PROPOSITIONS) })
        assertThat(harness.bookmark()).isNull()
    }

    @Test
    fun `a checked exception from projection reaches the caller unchecked with the window recorded`() {
        val disk = IOException("graph is full")
        val harness = harness(projectionFails = true, projectionFailure = { disk })

        assertThatThrownBy { harness.extraction.extractPropositions(event()) }
            .isInstanceOf(RuntimeException::class.java)
            .hasCause(disk)
        assertThat(harness.bookmark()).isNotNull()
    }

    @Test
    fun `a failed window record keeps the deferred STRICT lineage failure as suppressed`() {
        val historyDown = IllegalStateException("history is down")
        val inner = InMemoryChunkHistoryStore()
        val history = object : ChunkHistoryStore by inner {
            override fun recordProcessed(record: ProcessedChunkRecord) =
                throw historyDown
        }
        val harness = harness(history = history)
        harness.extraction.withRunLineage(null, LineageFailurePolicy.STRICT)

        assertThatThrownBy { harness.extraction.extractPropositions(event(currentRun = runRef)) }
            .isSameAs(historyDown)
        assertThat(historyDown.suppressed)
            .hasSize(1)
            .allSatisfy { assertThat(it).isInstanceOf(LineageNotRecordedException::class.java) }
    }

    @Test
    fun `a later failure in the same drain is logged and leaves the first unchanged`() {
        val harness = harness(
            repository = FlakyRepository(
                failingSaves = setOf(1, 2),
                failure = { number -> IllegalStateException("save $number failed") },
            ),
        )

        lateinit var run: DrainRun
        val warned = warnings { run = harness.drainWithQueued("source-b") }

        assertThat(run.surfaced).hasMessage("save 1 failed")
        assertThat(run.surfaced!!.suppressed).isEmpty()
        assertThat(warned.filter { it.throwableProxy?.message == "save 2 failed" }).hasSize(1)
        verify(exactly = 2) { harness.pipeline.processChunk(any(), any()) }
    }

    @Test
    fun `a socket timeout from a save does not stop the next queued event`() {
        val timeout = SocketTimeoutException("Read timed out")
        val harness = harness(repository = FlakyRepository(failingSaves = setOf(1), failure = { timeout }))

        val run = harness.drainAll(event(source = "source-a"), event(source = "source-b"))

        assertThat(run.surfaced).isInstanceOf(PersistUnitFailedException::class.java).hasCause(timeout)
        assertThat(run.stillInterrupted).isFalse()
        verify(exactly = 2) { harness.pipeline.processChunk(any(), any()) }
        assertThat(harness.extraction.isIdle).isTrue()
    }

    @Test
    fun `a save that throws InterruptedException stops the drain with the flag set`() {
        // Throwing InterruptedException clears the flag, so the extractor sets it again.
        val interrupted = InterruptedException("save interrupted")
        val harness = harness(repository = FlakyRepository(failingSaves = setOf(1), failure = { interrupted }))

        val run = harness.drainAll(event(source = "source-a"), event(source = "source-b"))

        assertThat(run.surfaced).isInstanceOf(PersistUnitFailedException::class.java).hasCause(interrupted)
        assertThat(run.stillInterrupted).isTrue()
        verify(exactly = 1) { harness.pipeline.processChunk(any(), any()) }
        assertThat(harness.extraction.isIdle)
            .describedAs("the second event is still queued")
            .isFalse()
    }

    @Test
    fun `a minted entity properties provider that is interrupted stops the drain with the flag set`() {
        // The provider's failure is logged and swallowed, so the extractor has to set the flag
        // again for the drain to see it.
        val calls = AtomicInteger()
        val harness = harness(
            mintNewEntities = true,
            mintedEntityProperties = {
                if (calls.incrementAndGet() == 1) throw InterruptedException("provider interrupted")
                emptyMap()
            },
        )

        val run = harness.drainAll(event(source = "source-a"), event(source = "source-b"))

        assertThat(run.surfaced).isNull()
        assertThat(run.stillInterrupted).isTrue()
        assertThat(calls.get()).isEqualTo(1)
        verify(exactly = 1) { harness.pipeline.processChunk(any(), any()) }
        assertThat(harness.extraction.isIdle)
            .describedAs("the second event is still queued")
            .isFalse()
    }

    @Test
    fun `a save listener that keeps queueing the source cannot hold a failure back from the caller`() {
        // Every save queues the same source again and every window record fails. Without a bound
        // the drain would keep saving forever. The listener gives up after ten saves only so a
        // broken drain still ends.
        val inner = InMemoryChunkHistoryStore()
        val history = object : ChunkHistoryStore by inner {
            override fun recordProcessed(record: ProcessedChunkRecord) =
                throw IllegalStateException("disk full")
        }
        lateinit var extraction: IncrementalPropositionExtraction
        val saves = AtomicInteger()
        val repository = FlakyRepository(
            failingSaves = emptySet(),
            onSave = { number ->
                saves.set(number)
                if (number < 10) extraction.extractPropositions(event())
            },
        )
        val harness = harness(repository = repository, history = history)
        extraction = harness.extraction

        assertThatThrownBy { harness.extraction.extractPropositions(event()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("disk full")
        assertThat(saves.get()).isLessThanOrEqualTo(2)
    }

    @Test
    fun `a later failure is logged without asking its event for the source again`() {
        val firstCalls = AtomicInteger()
        val secondCalls = AtomicInteger()
        val harness = harness(
            repository = FlakyRepository(
                failingSaves = setOf(1, 2),
                failure = { number -> IllegalStateException("save $number failed") },
            ),
        )

        val run = harness.drainAll(
            event(source = "source-a", sourceCalls = firstCalls),
            event(source = "source-b", sourceCalls = secondCalls),
        )

        assertThat(run.surfaced).hasMessage("save 1 failed")
        assertThat(firstCalls.get()).isEqualTo(1)
        assertThat(secondCalls.get()).isEqualTo(1)
    }

    @Test
    fun `a later failure that reuses the first exception is still logged`() {
        val shared = IllegalStateException("store is down")
        val harness = harness(repository = FlakyRepository(failingSaves = setOf(1, 2), failure = { shared }))

        lateinit var run: DrainRun
        val warned = warnings {
            run = harness.drainAll(event(source = "source-a"), event(source = "source-b"))
        }

        assertThat(run.surfaced).isSameAs(shared)
        assertThat(shared.suppressed).isEmpty()
        assertThat(warned.filter { it.throwableProxy?.message == "store is down" }).hasSize(1)
        verify(exactly = 2) { harness.pipeline.processChunk(any(), any()) }
    }

    /** Runs [block] and returns the `error` events logged anywhere under `com.embabel.dice`. */
    private fun errors(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger("com.embabel.dice") as Logger
        val appender = ListAppender<ILoggingEvent>().also {
            it.start()
            logger.addAppender(it)
        }
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        return appender.list.filter { it.level == Level.ERROR }
    }

    private fun assertStuckWindowLogged(logged: List<ILoggingEvent>, times: Int, cause: String) {
        val stuck = logged.filter { it.formattedMessage.contains("source $sourceId") }
        assertThat(stuck).hasSize(times)
        assertThat(stuck).allSatisfy {
            assertThat(it.formattedMessage)
                .contains("items [0, 1)")
                .contains("context ${tenant.value}")
            assertThat(it.throwableProxy?.message).isEqualTo(cause)
        }
    }

    @Test
    fun `a window that keeps failing to store is logged at error with its source each time`() {
        // The failure that reaches the caller may belong to another queued event and need not name
        // its source, so the log is how a host finds the stuck source and decides to skip it.
        val harness = harness(repository = FlakyRepository(failingSaves = setOf(1, 2)))

        val logged = errors {
            repeat(2) {
                assertThatThrownBy { harness.extraction.extractPropositions(event()) }
                    .hasMessage("store is down")
            }
        }

        assertStuckWindowLogged(logged, times = 2, cause = "store is down")
        assertThat(harness.bookmark()).isNull()
    }

    @Test
    fun `a window whose record fails is logged at error with its source`() {
        val inner = InMemoryChunkHistoryStore()
        val history = object : ChunkHistoryStore by inner {
            override fun recordProcessed(record: ProcessedChunkRecord) =
                throw IllegalStateException("disk full")
        }
        val harness = harness(history = history)

        val logged = errors {
            assertThatThrownBy { harness.extraction.extractPropositions(event()) }
                .hasMessage("disk full")
        }

        assertStuckWindowLogged(logged, times = 1, cause = "disk full")
        assertThat(harness.bookmark()).isNull()
    }
}

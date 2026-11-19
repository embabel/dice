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
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.common.PropositionPersisted
import com.embabel.dice.common.PropositionPinned
import com.embabel.dice.common.PropositionStatusChanged
import com.embabel.dice.common.PropositionUnpinned
import com.embabel.dice.common.RecordingDiceEventListener
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.ConcurrentHashMap

/**
 * Contract for the persistence-boundary decorator.
 *
 * The decorator wraps a [PropositionRepository] (`by delegate`) and emits a [PropositionPersisted]
 * *after* the delegate's save completes, carrying the exact instance the delegate returned.
 * It must also override `saveAll` to emit one event per proposition (the default
 * `saveAll` would forward to the delegate's `saveAll` and skip the decorator's `save`).
 */
class EventEmittingPropositionRepositoryTest {

    private val contextId = ContextId("test-context")

    private fun proposition(text: String): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = listOf(EntityMention(span = "Jim", type = "Person", role = MentionRole.SUBJECT)),
            confidence = 0.9,
        )

    /**
     * Recording in-memory repository: records save order so we can assert the
     * persist-happens-before-emit ordering deterministically without mocking.
     */
    private class RecordingRepository : PropositionRepository {
        val saved = mutableListOf<Proposition>()
        private val store = mutableMapOf<String, Proposition>()

        override val luceneSyntaxNotes: String = "test"

        override fun save(proposition: Proposition): Proposition {
            saved.add(proposition)
            store[proposition.id] = proposition
            return proposition
        }

        override fun findById(id: String): Proposition? = store[id]
        override fun findByEntity(entityIdentifier: com.embabel.agent.rag.service.RetrievableIdentifier): List<Proposition> = emptyList()
        override fun findSimilarWithScores(textSimilaritySearchRequest: TextSimilaritySearchRequest): List<SimilarityResult<Proposition>> = emptyList()
        override fun findByStatus(status: PropositionStatus): List<Proposition> = store.values.filter { it.status == status }
        override fun findByGrounding(chunkId: String): List<Proposition> = emptyList()
        override fun findByMinLevel(minLevel: Int): List<Proposition> = emptyList()
        override fun findAll(): List<Proposition> = store.values.toList()
        override fun delete(id: String): Boolean = store.remove(id) != null
        override fun count(): Int = store.size
    }

    @Test
    fun `save emits exactly one PropositionPersisted carrying the saved instance`() {
        val delegate = RecordingRepository()
        val recording = RecordingDiceEventListener()
        val repo = EventEmittingPropositionRepository(delegate, recording)

        val p = proposition("Jim is an expert in GOAP")
        val returned = repo.save(p)

        val emitted = recording.eventsOfType<PropositionPersisted>()
        assertEquals(1, emitted.size, "exactly one PropositionPersisted on save")
        assertSame(returned, emitted.first().proposition, "event carries the exact instance save returned")
    }

    @Test
    fun `save persists before it emits (ordering)`() {
        val delegate = RecordingRepository()
        // Listener asserts the delegate has already recorded the save by emit time.
        var persistedBeforeEmit = false
        val ordering = com.embabel.dice.common.DiceEventListener {
            persistedBeforeEmit = delegate.saved.size == 1
        }
        val repo = EventEmittingPropositionRepository(delegate, ordering)

        repo.save(proposition("ordering check"))

        assertTrue(persistedBeforeEmit, "delegate.save must complete before the event is emitted")
    }

    @Test
    fun `saveAll emits one PropositionPersisted per proposition`() {
        val delegate = RecordingRepository()
        val recording = RecordingDiceEventListener()
        val repo = EventEmittingPropositionRepository(delegate, recording)

        val p1 = proposition("first")
        val p2 = proposition("second")
        repo.saveAll(listOf(p1, p2))

        val emitted = recording.eventsOfType<PropositionPersisted>()
        assertEquals(2, emitted.size, "saveAll must emit one PropositionPersisted per proposition (not delegate to delegate.saveAll)")
    }

    @Test
    fun `re-saving with a changed status emits PropositionStatusChanged carrying prior and new status`() {
        val delegate = RecordingRepository()
        val recording = RecordingDiceEventListener()
        val repo = EventEmittingPropositionRepository(delegate, recording)

        val fresh = proposition("Jim shipped the release")
        repo.save(fresh)
        repo.save(fresh.withStatus(PropositionStatus.CONTRADICTED))

        val changes = recording.eventsOfType<PropositionStatusChanged>()
        assertEquals(1, changes.size, "a status-changing re-save emits exactly one PropositionStatusChanged")
        val change = changes.first()
        assertEquals(PropositionStatus.ACTIVE, change.previousStatus)
        assertEquals(PropositionStatus.CONTRADICTED, change.newStatus)
        assertEquals(fresh.id, change.proposition.id)
    }

    @Test
    fun `a fresh ACTIVE save still emits PropositionPersisted and no status change`() {
        val delegate = RecordingRepository()
        val recording = RecordingDiceEventListener()
        val repo = EventEmittingPropositionRepository(delegate, recording)

        repo.save(proposition("Jim is an expert in GOAP"))

        assertEquals(1, recording.eventsOfType<PropositionPersisted>().size, "fresh ACTIVE insert emits PropositionPersisted")
        assertTrue(recording.eventsOfType<PropositionStatusChanged>().isEmpty(), "fresh ACTIVE insert emits no PropositionStatusChanged")
    }

    /**
     * Builds a vector-backed in-memory repository whose similarity search returns real, non-empty
     * results, using a stub embedder keyed by text so cosine similarity is deterministic.
     */
    private fun vectorBackedDelegate(): InMemoryPropositionRepository {
        val embeddingMap = ConcurrentHashMap<String, FloatArray>()
        embeddingMap["A likes B"] = floatArrayOf(1f, 0f, 0f)
        embeddingMap["A loves B"] = floatArrayOf(0.99f, 0.1f, 0f)
        val embeddingService = mock<EmbeddingService>()
        whenever(embeddingService.embed(any<String>())).thenAnswer { invocation ->
            val text = invocation.getArgument<String>(0)
            embeddingMap[text] ?: floatArrayOf(0f, 0f, 0f)
        }
        val delegate = InMemoryPropositionRepository(embeddingService)
        delegate.save(proposition("A likes B"))
        delegate.save(proposition("A loves B"))
        return delegate
    }

    @Test
    fun `forwards vector similarity search to a vector-backed delegate`() {
        val delegate = vectorBackedDelegate()
        val repo = EventEmittingPropositionRepository(delegate, RecordingDiceEventListener())

        val request = TextSimilaritySearchRequest(query = "A likes B", similarityThreshold = 0.5, topK = 10)

        val throughDecoratorScores = repo.findSimilarWithScores(request)
        val bareDelegateScores = delegate.findSimilarWithScores(request)
        assertFalse(bareDelegateScores.isEmpty(), "sanity: bare delegate returns non-empty similarity results")
        assertEquals(
            bareDelegateScores.map { it.match.id },
            throughDecoratorScores.map { it.match.id },
            "findSimilarWithScores through the decorator must equal the bare delegate's results",
        )

        val throughDecoratorMatches = repo.findSimilar(request)
        val bareDelegateMatches = delegate.findSimilar(request)
        assertFalse(bareDelegateMatches.isEmpty(), "sanity: bare delegate returns non-empty matches")
        assertEquals(
            bareDelegateMatches.map { it.id },
            throughDecoratorMatches.map { it.id },
            "findSimilar through the decorator must equal the bare delegate's results",
        )
    }

    @Test
    fun `forwards clustering to a vector-backed delegate`() {
        val delegate = vectorBackedDelegate()
        val repo = EventEmittingPropositionRepository(delegate, RecordingDiceEventListener())

        val bareClusters = delegate.findClusters(similarityThreshold = 0.5)
        assertFalse(bareClusters.isEmpty(), "sanity: bare delegate produces clusters")

        val throughDecoratorClusters = repo.findClusters(similarityThreshold = 0.5)
        assertEquals(
            bareClusters.size,
            throughDecoratorClusters.size,
            "findClusters through the decorator must match the bare delegate's clusters",
        )
    }

    // ========================================================================
    // Pin and unpin: one event, only when the flag actually changes
    // ========================================================================

    /** A recording repository that can also subtract provenance, so `wrapping` picks that shape. */
    private class SubtractingRecordingRepository(
        private val inner: RecordingRepository = RecordingRepository(),
    ) : PropositionRepository by inner, ProvenanceSubtractionCapable {
        val saved: List<Proposition> get() = inner.saved

        override fun subtractFoldedEvidence(
            propositionId: String,
            provenanceRefs: List<String>,
            grounding: Collection<String>,
            sourceIds: Collection<String>,
        ): Proposition? = inner.findById(propositionId)
    }

    @Test
    fun `pinning an unpinned fact emits one PropositionPinned carrying the saved pinned fact`() {
        val delegate = RecordingRepository()
        val fact = delegate.save(proposition("Jim prefers tea"))
        val recording = RecordingDiceEventListener()
        val repo = EventEmittingPropositionRepository.wrapping(delegate, recording)

        val returned = repo.pin(fact.id)

        assertTrue(returned!!.pinned, "pin returns the pinned fact")
        assertTrue(delegate.findById(fact.id)!!.pinned, "the pinned flag reached the delegate")
        val pinned = recording.eventsOfType<PropositionPinned>()
        assertEquals(1, pinned.size, "exactly one PropositionPinned")
        assertSame(returned, pinned.single().proposition, "the event carries the saved instance")
        assertEquals(1, recording.count(), "pin emits nothing else")
    }

    @Test
    fun `unpinning a pinned fact emits one PropositionUnpinned carrying the saved unpinned fact`() {
        val delegate = RecordingRepository()
        val fact = delegate.save(proposition("Jim prefers tea").withPinned(true))
        val recording = RecordingDiceEventListener()
        val repo = EventEmittingPropositionRepository.wrapping(delegate, recording)

        val returned = repo.unpin(fact.id)

        assertFalse(returned!!.pinned, "unpin returns the unpinned fact")
        assertFalse(delegate.findById(fact.id)!!.pinned, "the cleared flag reached the delegate")
        val unpinned = recording.eventsOfType<PropositionUnpinned>()
        assertEquals(1, unpinned.size, "exactly one PropositionUnpinned")
        assertSame(returned, unpinned.single().proposition, "the event carries the saved instance")
        assertEquals(1, recording.count(), "unpin emits nothing else")
    }

    @Test
    fun `pinning an already pinned fact emits nothing and returns it without saving`() {
        val delegate = RecordingRepository()
        val fact = delegate.save(proposition("Jim prefers tea").withPinned(true))
        val savesBefore = delegate.saved.size
        val recording = RecordingDiceEventListener()
        val repo = EventEmittingPropositionRepository.wrapping(delegate, recording)

        val returned = repo.pin(fact.id)

        assertSame(fact, returned, "the stored fact comes back as is")
        assertEquals(savesBefore, delegate.saved.size, "no save when the flag already matches")
        assertEquals(0, recording.count(), "no event when the flag already matches")
    }

    @Test
    fun `unpinning an unpinned fact emits nothing and returns it without saving`() {
        val delegate = RecordingRepository()
        val fact = delegate.save(proposition("Jim prefers tea"))
        val savesBefore = delegate.saved.size
        val recording = RecordingDiceEventListener()
        val repo = EventEmittingPropositionRepository.wrapping(delegate, recording)

        val returned = repo.unpin(fact.id)

        assertSame(fact, returned, "the stored fact comes back as is")
        assertEquals(savesBefore, delegate.saved.size, "no save when the flag already matches")
        assertEquals(0, recording.count(), "no event when the flag already matches")
    }

    @Test
    fun `pinning or unpinning an unknown id returns null and emits nothing`() {
        val delegate = RecordingRepository()
        val recording = RecordingDiceEventListener()
        val repo = EventEmittingPropositionRepository.wrapping(delegate, recording)

        assertNull(repo.pin("no-such-id"))
        assertNull(repo.unpin("no-such-id"))
        assertTrue(delegate.saved.isEmpty(), "nothing saved for an unknown id")
        assertEquals(0, recording.count(), "nothing emitted for an unknown id")
    }

    @Test
    fun `pin and unpin emit no PropositionPersisted`() {
        val delegate = RecordingRepository()
        val fact = delegate.save(proposition("Jim prefers tea"))
        val recording = RecordingDiceEventListener()
        val repo = EventEmittingPropositionRepository.wrapping(delegate, recording)

        repo.pin(fact.id)
        repo.unpin(fact.id)

        assertTrue(recording.eventsOfType<PropositionPersisted>().isEmpty(), "pin and unpin emit no PropositionPersisted")
        assertEquals(1, recording.eventsOfType<PropositionPinned>().size)
        assertEquals(1, recording.eventsOfType<PropositionUnpinned>().size)
    }

    @Test
    fun `pin and unpin emit through the provenance-subtracting shape`() {
        val delegate = SubtractingRecordingRepository()
        val fact = delegate.save(proposition("Jim prefers tea"))
        val recording = RecordingDiceEventListener()
        val repo = EventEmittingPropositionRepository.wrapping(delegate, recording)
        assertInstanceOf(ProvenanceSubtractingEventEmittingPropositionRepository::class.java, repo)

        val pinned = repo.pin(fact.id)
        repo.pin(fact.id)
        val unpinned = repo.unpin(fact.id)

        assertSame(pinned, recording.eventsOfType<PropositionPinned>().single().proposition)
        assertSame(unpinned, recording.eventsOfType<PropositionUnpinned>().single().proposition)
        assertEquals(2, recording.count(), "only the two flag changes emit")
        assertEquals(3, delegate.saved.size, "the seed save plus one save per flag change")
    }

    @Test
    fun `pin and unpin emit through the source-revision shape`() {
        val delegate = InMemoryPropositionRepository()
        val fact = delegate.save(proposition("Jim prefers tea"))
        val recording = RecordingDiceEventListener()
        val repo = EventEmittingPropositionRepository.wrapping(delegate, recording)
        assertInstanceOf(SourceRevisionEventEmittingPropositionRepository::class.java, repo)

        val pinned = repo.pin(fact.id)
        assertTrue(delegate.findById(fact.id)!!.pinned)
        val unpinned = repo.unpin(fact.id)
        repo.unpin(fact.id)

        assertSame(pinned, recording.eventsOfType<PropositionPinned>().single().proposition)
        assertSame(unpinned, recording.eventsOfType<PropositionUnpinned>().single().proposition)
        assertEquals(2, recording.count(), "only the two flag changes emit")
    }

    @Test
    fun `a store that is not wrapped pins without any listener involved`() {
        val delegate = RecordingRepository()
        val fact = delegate.save(proposition("Jim prefers tea"))

        val returned = delegate.pin(fact.id)

        assertTrue(returned!!.pinned)
        assertEquals(2, delegate.saved.size, "the interface default still saves through the store")
    }
}

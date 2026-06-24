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
import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.common.PropositionStatusChanged
import com.embabel.dice.common.RecordingDiceEventListener
import com.embabel.dice.projection.lineage.CollectorRecordStore
import com.embabel.dice.projection.lineage.InMemoryCollectorRecordStore
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.spi.MarkReason
import com.embabel.dice.spi.PropositionMark
import com.embabel.dice.spi.StatusTransitionSweepPolicy
import com.embabel.dice.spi.SweepAction
import com.embabel.dice.spi.SweepPolicy
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Behavior oracles for the dry-run-first [CollectorRunner] (its default implementation,
 * `DefaultCollectorRunner`). These encode the load-bearing runner contracts:
 *
 *  - `collect()` is a pure mark phase: zero repository writes AND zero record-store writes;
 *  - `run(dryRun = true)` performs no repository status change but persists an auditable
 *    run record (a [com.embabel.dice.projection.lineage.CollectorRun] with `dryRun == true`
 *    plus one record per marked proposition) and emits no status-change event;
 *  - `run(dryRun = false)` transitions each swept proposition to STALE via the normal
 *    status-transition path, persists then emits a [PropositionStatusChanged] per applied
 *    transition, and persists a finished run with records;
 *  - a pinned proposition that would otherwise be marked is skipped, not transitioned;
 *  - a second `run(dryRun = false)` immediately after the first applies zero transitions
 *    (ACTIVE-only candidate selection means already-STALE props are not re-selected);
 *  - the candidate query never selects PROMOTED propositions.
 */
class DefaultCollectorRunnerTest {

    private val contextId = ContextId("test-context")
    private lateinit var repository: PropositionRepository
    private lateinit var recordStore: CollectorRecordStore
    private lateinit var listener: RecordingDiceEventListener

    private fun proposition(
        text: String,
        confidence: Double = 0.9,
        decay: Double = 0.1,
        status: PropositionStatus = PropositionStatus.ACTIVE,
        pinned: Boolean = false,
        contentRevised: Instant = Instant.now(),
    ): Proposition = Proposition(
        contextId = contextId,
        text = text,
        mentions = emptyList(),
        confidence = confidence,
        decay = decay,
        status = status,
        pinned = pinned,
        contentRevised = contentRevised,
        metadataRevised = contentRevised,
    )

    private val stalePast: Instant = Instant.now().minus(365, ChronoUnit.DAYS)

    private fun decayedProp(text: String, pinned: Boolean = false): Proposition =
        proposition(text, confidence = 0.5, decay = 0.5, pinned = pinned, contentRevised = stalePast)

    private fun runner(strategy: CollectorStrategy = DecayCollectorStrategy(retireBelow = 0.3)): CollectorRunner =
        CollectorRunner
            .withRepository(repository)
            .withStrategy(strategy)
            .withRecordStore(recordStore)
            .withEventListener(listener)
            .build()

    @BeforeEach
    fun setup() {
        repository = mockk(relaxed = true)
        recordStore = InMemoryCollectorRecordStore()
        listener = RecordingDiceEventListener()
        every { repository.query(any()) } returns emptyList()
    }

    @Test
    fun `collect performs no repository and no record-store writes`() {
        val decayed = decayedProp("old fact")
        every { repository.query(any()) } returns listOf(decayed)

        val result = runner().collect(contextId)

        assertTrue(result.marks.isNotEmpty())
        // Nothing is persisted on the pure-read path, so the runId is blank (not queryable).
        assertTrue(result.runId.isBlank())
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { repository.saveAll(any()) }
        verify(exactly = 0) { repository.delete(any<String>()) }
        assertTrue(recordStore.all().isEmpty())
        assertTrue(listener.eventsOfType<PropositionStatusChanged>().isEmpty())
    }

    @Test
    fun `dry run persists a run record only and performs no repository write or emit`() {
        val decayed = decayedProp("old fact")
        every { repository.query(any()) } returns listOf(decayed)

        val result = runner().run(contextId, dryRun = true)

        assertTrue(result.dryRun)
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { repository.delete(any<String>()) }
        // One auditable record persisted for the marked proposition.
        assertTrue(recordStore.findByRun(result.runId).isNotEmpty())
        assertTrue(recordStore.findByProposition(decayed.id).isNotEmpty())
        // No lifecycle event emitted on a dry run.
        assertTrue(listener.eventsOfType<PropositionStatusChanged>().isEmpty())
    }

    @Test
    fun `live run transitions to STALE and emits a status change per applied transition`() {
        val decayed = decayedProp("old fact")
        every { repository.query(any()) } returns listOf(decayed)
        val saved = slot<Proposition>()
        every { repository.save(capture(saved)) } answers { saved.captured }

        val result = runner().run(contextId, dryRun = false)

        assertEquals(1, result.applied.size)
        assertEquals(PropositionStatus.STALE, saved.captured.status)
        verify(exactly = 1) { repository.save(any()) }

        val events = listener.eventsOfType<PropositionStatusChanged>()
        assertEquals(1, events.size)
        assertEquals(PropositionStatus.ACTIVE, events[0].previousStatus)
        assertEquals(PropositionStatus.STALE, events[0].newStatus)
        // Persisted run + record for the applied transition.
        assertTrue(recordStore.findByRun(result.runId).isNotEmpty())
    }

    @Test
    fun `a proposition transitioned before a mid-run failure still has an audit record`() {
        // Two decayed candidates. The first transitions cleanly; the second's save throws partway
        // through the run. The first proposition is already mutated and its event emitted, so its
        // audit record must be persisted despite the abort — otherwise a transition (or a hard
        // delete) is unrecoverable.
        val first = decayedProp("first old fact")
        val second = decayedProp("second old fact")
        every { repository.query(any()) } returns listOf(first, second)
        var saves = 0
        every { repository.save(any()) } answers {
            saves++
            if (saves == 1) firstArg() else throw RuntimeException("storage failure")
        }

        runCatching { runner().run(contextId, dryRun = false) }

        // The first really transitioned: exactly one event, and a durable audit record for it.
        assertEquals(1, listener.eventsOfType<PropositionStatusChanged>().size)
        assertTrue(recordStore.findByProposition(first.id).isNotEmpty())
    }

    @Test
    fun `a transition is recorded even when a listener throws after the persist`() {
        // persist-then-emit means a listener runs after the durable write. If it throws, the
        // proposition is already transitioned — its audit record must still be captured rather than
        // lost to the unhandled listener failure.
        val decayed = decayedProp("old fact")
        every { repository.query(any()) } returns listOf(decayed)
        every { repository.save(any()) } answers { firstArg() }
        val throwingListener = DiceEventListener { throw RuntimeException("listener boom") }
        val runner = CollectorRunner
            .withRepository(repository)
            .withStrategy(DecayCollectorStrategy(retireBelow = 0.3))
            .withRecordStore(recordStore)
            .withEventListener(throwingListener)
            .build()

        runCatching { runner.run(contextId, dryRun = false) }

        assertTrue(recordStore.findByProposition(decayed.id).isNotEmpty())
    }

    @Test
    fun `a live run leaves a pinned proposition untouched (decay-immune)`() {
        val pinned = decayedProp("pinned old fact", pinned = true)
        every { repository.query(any()) } returns listOf(pinned)

        val result = runner().run(contextId, dryRun = false)

        // The decay strategy never marks a pinned proposition, so it isn't applied, skipped, or even
        // marked — fully decay-immune — and nothing is written or emitted.
        assertTrue(result.applied.isEmpty())
        assertTrue(result.skipped.isEmpty())
        assertTrue(result.marks.isEmpty())
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { repository.delete(any<String>()) }
        assertTrue(listener.eventsOfType<PropositionStatusChanged>().isEmpty())
    }

    @Test
    fun `a second live run applies zero transitions`() {
        val decayed = decayedProp("old fact")
        // First run sees the ACTIVE candidate; second run sees none
        // (ACTIVE-only selection means the now-STALE proposition is not re-selected).
        every { repository.query(any()) } returnsMany listOf(listOf(decayed), emptyList())
        every { repository.save(any()) } answers { firstArg() }

        val first = runner().run(contextId, dryRun = false)
        val second = runner().run(contextId, dryRun = false)

        assertEquals(1, first.applied.size)
        assertTrue(second.applied.isEmpty())
    }

    @Test
    fun `dry run persists a retrievable run header flagged dryRun`() {
        val decayed = decayedProp("old fact")
        every { repository.query(any()) } returns listOf(decayed)

        val result = runner().run(contextId, dryRun = true)

        val run = recordStore.findRun(result.runId)
        assertEquals(result.runId, run?.runId)
        assertEquals(true, run?.dryRun)
        // A dry run reports nothing as applied or hard-deleted (marks reflect the preview).
        assertTrue(result.applied.isEmpty())
        assertTrue(result.hardDeleted.isEmpty())
    }

    @Test
    fun `live run persists a retrievable run header flagged not dryRun`() {
        val decayed = decayedProp("old fact")
        every { repository.query(any()) } returns listOf(decayed)
        every { repository.save(any()) } answers { firstArg() }

        val result = runner().run(contextId, dryRun = false)

        val run = recordStore.findRun(result.runId)
        assertEquals(result.runId, run?.runId)
        assertEquals(false, run?.dryRun)
    }

    @Test
    fun `a zero-mark run still leaves a retrievable run header`() {
        // No candidates -> no marks -> no records, but the run must still leave a trace.
        every { repository.query(any()) } returns emptyList()

        val result = runner().run(contextId, dryRun = false)

        assertTrue(result.marks.isEmpty())
        assertTrue(recordStore.findByRun(result.runId).isEmpty())
        val run = recordStore.findRun(result.runId)
        assertEquals(result.runId, run?.runId)
        assertEquals(1, recordStore.runs().size)
    }

    @Test
    fun `live run with a hard-delete policy removes the proposition and reports it as hard-deleted`() {
        // The default StatusTransitionSweepPolicy never hard-deletes (recoverable STALE only), but a
        // consumer-supplied policy may opt in to HardDelete. A live run must then actually delete the
        // proposition, populate `hardDeleted` (and not `applied`), and emit no status-change event.
        val decayed = decayedProp("old fact")
        every { repository.query(any()) } returns listOf(decayed)

        val hardDeletePolicy = SweepPolicy { _, marks ->
            if (marks.isEmpty()) SweepAction.Skip else SweepAction.HardDelete
        }
        val runner = CollectorRunner
            .withRepository(repository)
            .withStrategy(DecayCollectorStrategy(retireBelow = 0.3))
            .withPolicy(hardDeletePolicy)
            .withRecordStore(recordStore)
            .withEventListener(listener)
            .build()

        val result = runner.run(contextId, dryRun = false)

        assertEquals(listOf(decayed.id), result.hardDeleted)
        assertTrue(result.applied.isEmpty())
        verify(exactly = 1) { repository.delete(decayed.id) }
        verify(exactly = 0) { repository.save(any()) }
        assertTrue(listener.eventsOfType<PropositionStatusChanged>().isEmpty())
    }

    @Test
    fun `dry run with a hard-delete policy deletes nothing but records the would-be hard delete`() {
        // On a dry run a HardDelete decision must not touch the repository and must leave `hardDeleted`
        // empty, yet still leave an auditable record so the preview is reviewable.
        val decayed = decayedProp("old fact")
        every { repository.query(any()) } returns listOf(decayed)

        val hardDeletePolicy = SweepPolicy { _, marks ->
            if (marks.isEmpty()) SweepAction.Skip else SweepAction.HardDelete
        }
        val runner = CollectorRunner
            .withRepository(repository)
            .withStrategy(DecayCollectorStrategy(retireBelow = 0.3))
            .withPolicy(hardDeletePolicy)
            .withRecordStore(recordStore)
            .withEventListener(listener)
            .build()

        val result = runner.run(contextId, dryRun = true)

        assertTrue(result.hardDeleted.isEmpty())
        verify(exactly = 0) { repository.delete(any<String>()) }
        assertTrue(recordStore.findByProposition(decayed.id).isNotEmpty())
    }

    @Test
    fun `when two strategies mark the same proposition the emitted event carries both reasons combined`() {
        // WR-03 regression guard: a proposition marked by more than one strategy must surface ALL its
        // distinct reasons on the lifecycle event (sorted for run-to-run determinism), not just the
        // reason of whichever strategy happened to run first.
        val decayed = decayedProp("contested fact")
        every { repository.query(any()) } returns listOf(decayed)
        every { repository.save(any()) } answers { firstArg() }

        // Two independent strategies that each mark the same candidate with a different reason.
        val staleStrategy = CollectorStrategy { candidates, _, _ ->
            candidates.map { PropositionMark(it.id, MarkReason.Stale, "decay") }
        }
        val customStrategy = CollectorStrategy { candidates, _, _ ->
            candidates.map { PropositionMark(it.id, MarkReason.Custom("audit", "flagged"), "audit") }
        }
        val runner = CollectorRunner
            .withRepository(repository)
            .withStrategy(staleStrategy)
            .withStrategy(customStrategy)
            .withRecordStore(recordStore)
            .withEventListener(listener)
            .build()

        runner.run(contextId, dryRun = false)

        val events = listener.eventsOfType<PropositionStatusChanged>()
        assertEquals(1, events.size)
        // Distinct keys, sorted: "audit" then "stale".
        assertEquals("audit,stale", events[0].reason)
    }

    @Test
    fun `candidate query never selects PROMOTED propositions`() {
        val querySlot = slot<PropositionQuery>()
        every { repository.query(capture(querySlot)) } returns emptyList()

        runner().run(contextId, dryRun = false)

        // The runner selects ACTIVE candidates only, so PROMOTED is excluded by construction.
        assertEquals(setOf(PropositionStatus.ACTIVE), querySlot.captured.statuses)
        assertTrue(PropositionStatus.PROMOTED !in querySlot.captured.statuses.orEmpty())
    }
}

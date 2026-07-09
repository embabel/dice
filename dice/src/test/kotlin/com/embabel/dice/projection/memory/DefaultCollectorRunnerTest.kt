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
import com.embabel.agent.rag.service.Cluster
import com.embabel.common.core.types.SimilarityResult
import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.common.PropositionStatusChanged
import com.embabel.dice.common.RecordingDiceEventListener
import com.embabel.dice.projection.lineage.CollectorRecordStore
import com.embabel.dice.projection.lineage.InMemoryCollectorRecordStore
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.spi.*
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
        assertTrue(recordStore.findByRun(result.runId).isNotEmpty())
        assertTrue(recordStore.findByProposition(decayed.id).isNotEmpty())
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

    private fun provenance(uri: String): ProvenanceEntry = ProvenanceEntry(UriLocator(uri))

    /** A strategy that marks every candidate as a duplicate of [survivorId]. */
    private fun duplicateStrategy(survivorId: String): CollectorStrategy =
        CollectorStrategy { candidates, _, _ ->
            candidates.map { PropositionMark(it.id, MarkReason.Duplicate(survivorId = survivorId), "duplicate") }
        }

    private fun mergingRunner(survivorId: String): CollectorRunner =
        CollectorRunner
            .withRepository(repository)
            .withStrategy(duplicateStrategy(survivorId))
            .withPolicy(MergingSweepPolicy())
            .withRecordStore(recordStore)
            .withEventListener(listener)
            .build()

    @Test
    fun `live MergeInto folds the loser's evidence onto the survivor then retires the loser`() {
        // The bug: a dedup collapse used to flip the loser STALE with a pure status change, so its
        // grounding/provenance vanished from retrieval. The survivor must instead absorb the loser's
        // evidence (deduped) and get a reinforcement bump before the loser goes STALE.
        val survivor = proposition("survivor").copy(
            grounding = listOf("chunk-s"),
            sourceIds = listOf("src-s"),
            provenanceEntries = listOf(provenance("uri://s")),
            reinforceCount = 2,
        )
        val loser = proposition("loser").copy(
            grounding = listOf("chunk-l"),
            sourceIds = listOf("src-l"),
            provenanceEntries = listOf(provenance("uri://l")),
        )
        every { repository.query(any()) } returns listOf(loser)
        var survivorState = survivor
        val saves = mutableListOf<Proposition>()
        every { repository.findById(survivor.id) } answers { survivorState }
        every { repository.save(any()) } answers {
            val p = firstArg<Proposition>()
            saves += p
            if (p.id == survivor.id) survivorState = p
            p
        }

        val result = mergingRunner(survivor.id).run(contextId, dryRun = false)

        // The collapse counts as an applied transition for the loser.
        assertEquals(1, result.applied.size)

        // Survivor absorbed the union of the loser's evidence, deduped, with reinforce +1.
        assertEquals(setOf("chunk-s", "chunk-l"), survivorState.grounding.toSet())
        assertEquals(setOf("src-s", "src-l"), survivorState.sourceIds.toSet())
        assertEquals(setOf("uri:uri://s", "uri:uri://l"), survivorState.provenanceEntries.map { it.locator.key() }.toSet())
        assertEquals(3, survivorState.reinforceCount)

        // Loser retired to STALE (nothing lost — the survivor now carries its evidence).
        val loserSave = saves.single { it.id == loser.id }
        assertEquals(PropositionStatus.STALE, loserSave.status)

        // Same status-changed event the plain transition emits, for the loser.
        val events = listener.eventsOfType<PropositionStatusChanged>()
        assertEquals(1, events.size)
        assertEquals(loser.id, events[0].proposition.id)
        assertEquals(PropositionStatus.ACTIVE, events[0].previousStatus)
        assertEquals(PropositionStatus.STALE, events[0].newStatus)
        assertEquals("duplicate", events[0].reason)
    }

    @Test
    fun `dry run MergeInto mutates nothing but records the would-be collapse`() {
        val survivor = proposition("survivor").copy(grounding = listOf("chunk-s"))
        val loser = proposition("loser").copy(grounding = listOf("chunk-l"))
        every { repository.query(any()) } returns listOf(loser)

        val result = mergingRunner(survivor.id).run(contextId, dryRun = true)

        assertTrue(result.dryRun)
        assertTrue(result.applied.isEmpty())
        // Neither the survivor merge nor the loser retirement touches the store on a preview.
        verify(exactly = 0) { repository.save(any()) }
        verify(exactly = 0) { repository.findById(any()) }
        assertTrue(listener.eventsOfType<PropositionStatusChanged>().isEmpty())
        // The preview is still auditable.
        assertTrue(recordStore.findByProposition(loser.id).isNotEmpty())
    }

    @Test
    fun `MergeInto with a missing survivor falls back to a plain STALE retirement`() {
        // The survivor was filtered out of the snapshot or deleted since marking. Rather than throw,
        // the loser still leaves ACTIVE via a plain transition (no merge target to absorb it).
        val loser = proposition("loser").copy(grounding = listOf("chunk-l"))
        every { repository.query(any()) } returns listOf(loser)
        every { repository.findById(any()) } returns null
        val saves = mutableListOf<Proposition>()
        every { repository.save(any()) } answers {
            val p = firstArg<Proposition>()
            saves += p
            p
        }

        val result = mergingRunner("ghost-survivor").run(contextId, dryRun = false)

        assertEquals(1, result.applied.size)
        assertEquals(listOf(loser.id), saves.map { it.id })
        assertEquals(PropositionStatus.STALE, saves.single().status)
        assertEquals(1, listener.eventsOfType<PropositionStatusChanged>().size)
    }

    @Test
    fun `a multi-loser cluster has ALL losers' evidence absorbed by the survivor`() {
        // Transitivity guard: two losers collapse into one survivor, which must end up carrying the
        // union of BOTH losers' grounding and a reinforcement per collapse.
        val survivor = proposition("survivor").copy(grounding = listOf("chunk-s"), reinforceCount = 0)
        val loserA = proposition("loserA").copy(grounding = listOf("chunk-a"))
        val loserB = proposition("loserB").copy(grounding = listOf("chunk-b"))
        every { repository.query(any()) } returns listOf(loserA, loserB)
        var survivorState = survivor
        every { repository.findById(survivor.id) } answers { survivorState }
        every { repository.save(any()) } answers {
            val p = firstArg<Proposition>()
            if (p.id == survivor.id) survivorState = p
            p
        }

        val result = mergingRunner(survivor.id).run(contextId, dryRun = false)

        assertEquals(2, result.applied.size)
        // Survivor accumulates both losers' grounding in one grouped read + save (both losers
        // collapse into the same survivor in this run), with one reinforcement per absorbed
        // duplicate.
        assertEquals(setOf("chunk-s", "chunk-a", "chunk-b"), survivorState.grounding.toSet())
        assertEquals(2, survivorState.reinforceCount)
    }

    @Test
    fun `a survivor decayed in the same run as a merge keeps its merged evidence after the transition`() {
        // Finding 1: the loser's MergeInto is marked in the same run as a decay strategy marking
        // the survivor itself. If the survivor's later TransitionStatus were applied against the
        // pre-run candidate snapshot, it would silently overwrite the just-merged
        // grounding/sourceIds/reinforceCount with the stale pre-merge values. It must instead
        // transition from the freshly-merged state.
        val survivor = proposition("survivor").copy(
            grounding = listOf("chunk-s"),
            sourceIds = listOf("src-s"),
            reinforceCount = 2,
        )
        val loser = proposition("loser").copy(grounding = listOf("chunk-l"), sourceIds = listOf("src-l"))
        every { repository.query(any()) } returns listOf(survivor, loser)
        var survivorState = survivor
        val saves = mutableListOf<Proposition>()
        every { repository.findById(survivor.id) } answers { survivorState }
        every { repository.save(any()) } answers {
            val p = firstArg<Proposition>()
            saves += p
            if (p.id == survivor.id) survivorState = p
            p
        }

        // Two independent strategies in one run: one marks only the loser as a duplicate of the
        // survivor; the other marks only the survivor itself (unrelated to the merge, e.g. a decay
        // sweep catching it independently).
        val duplicateOnlyLoser = CollectorStrategy { candidates, _, _ ->
            candidates.filter { it.id == loser.id }
                .map { PropositionMark(it.id, MarkReason.Duplicate(survivorId = survivor.id), "duplicate") }
        }
        val staleOnlySurvivor = CollectorStrategy { candidates, _, _ ->
            candidates.filter { it.id == survivor.id }
                .map { PropositionMark(it.id, MarkReason.Stale, "decay") }
        }
        val runner = CollectorRunner
            .withRepository(repository)
            .withStrategy(duplicateOnlyLoser)
            .withStrategy(staleOnlySurvivor)
            .withPolicy(MergingSweepPolicy())
            .withRecordStore(recordStore)
            .withEventListener(listener)
            .build()

        runner.run(contextId, dryRun = false)

        // The survivor's final saved state is STALE (the decay mark won) but still carries the
        // merged evidence — the transition must not have reverted to the pre-merge snapshot.
        val finalSurvivor = saves.last { it.id == survivor.id }
        assertEquals(PropositionStatus.STALE, finalSurvivor.status)
        assertEquals(setOf("chunk-s", "chunk-l"), finalSurvivor.grounding.toSet())
        assertEquals(setOf("src-s", "src-l"), finalSurvivor.sourceIds.toSet())
        assertEquals(3, finalSurvivor.reinforceCount)
    }

    @Test
    fun `MergeInto onto a survivor that is no longer ACTIVE falls back to a plain retirement`() {
        // Finding 2, the opposite ordering from the test above: the survivor is already retired
        // (e.g. some other mark already transitioned it) by the time the merge is attempted.
        // Folding evidence onto it would leave that evidence stuck on a STALE proposition,
        // invisible to retrieval and silently lost. The loser must instead fall back to a plain
        // retirement, the same way a missing survivor does.
        val retiredSurvivor = proposition("survivor", status = PropositionStatus.STALE)
            .copy(grounding = listOf("chunk-s"))
        val loser = proposition("loser").copy(grounding = listOf("chunk-l"))
        every { repository.query(any()) } returns listOf(loser)
        every { repository.findById(retiredSurvivor.id) } returns retiredSurvivor
        val saves = mutableListOf<Proposition>()
        every { repository.save(any()) } answers {
            val p = firstArg<Proposition>()
            saves += p
            p
        }

        val result = mergingRunner(retiredSurvivor.id).run(contextId, dryRun = false)

        assertEquals(1, result.applied.size)
        // Only the loser is saved (STALE); the survivor is never re-saved with folded evidence.
        assertEquals(listOf(loser.id), saves.map { it.id })
        assertEquals(PropositionStatus.STALE, saves.single().status)
        assertEquals(1, listener.eventsOfType<PropositionStatusChanged>().size)
    }

    @Test
    fun `a survivor that is itself merged away in the same run passes its absorbed evidence on to the final survivor`() {
        // Finding F2: X merges into A, and A separately merges into B, all in the same run
        // (possible once multiple strategies are stacked). Whichever group the runner happened
        // to process first used to decide whether X's evidence reached B or was dropped to the
        // "not ACTIVE" fallback. Chain resolution must land X on B regardless of decision order.
        val finalSurvivor = proposition("B").copy(grounding = listOf("chunk-b"), reinforceCount = 0)
        val middle = proposition("A").copy(grounding = listOf("chunk-a"), reinforceCount = 0)
        val loser = proposition("X").copy(grounding = listOf("chunk-x"))
        every { repository.query(any()) } returns listOf(loser, middle)
        val saved = mutableMapOf(finalSurvivor.id to finalSurvivor, middle.id to middle)
        every { repository.findById(any()) } answers { saved[firstArg()] }
        every { repository.save(any()) } answers {
            val p = firstArg<Proposition>()
            saved[p.id] = p
            p
        }

        // X -> A (marked first) and A -> B (marked second): decision order puts the middle
        // proposition's own merge-away AFTER the merge that targets it.
        val xIntoA = CollectorStrategy { candidates, _, _ ->
            candidates.filter { it.id == loser.id }
                .map { PropositionMark(it.id, MarkReason.Duplicate(survivorId = middle.id), "duplicate") }
        }
        val aIntoB = CollectorStrategy { candidates, _, _ ->
            candidates.filter { it.id == middle.id }
                .map { PropositionMark(it.id, MarkReason.Duplicate(survivorId = finalSurvivor.id), "duplicate") }
        }
        val runner = CollectorRunner
            .withRepository(repository)
            .withStrategy(xIntoA)
            .withStrategy(aIntoB)
            .withPolicy(MergingSweepPolicy())
            .withRecordStore(recordStore)
            .withEventListener(listener)
            .build()

        runner.run(contextId, dryRun = false)

        val finalState = saved.getValue(finalSurvivor.id)
        // X's evidence reached B directly, not just A's — the chain is fully resolved, not just
        // one hop.
        assertEquals(setOf("chunk-b", "chunk-a", "chunk-x"), finalState.grounding.toSet())
        // Both X (into A) and A (into B) collapsed, so B gets two reinforcements.
        assertEquals(2, finalState.reinforceCount)
        // A itself was retired (it merged away) rather than left dangling ACTIVE.
        assertEquals(PropositionStatus.STALE, saved.getValue(middle.id).status)
        // Neither collapse falls back to a plain retirement missing its evidence.
        assertEquals(2, listener.eventsOfType<PropositionStatusChanged>().size)
    }

    @Test
    fun `the same survivor chain resolves identically when the middle proposition's own merge is marked first`() {
        // Same scenario as above, opposite mark order: A -> B is marked before X -> A. Since
        // chain resolution happens before grouping (not during iteration), the outcome must be
        // identical regardless of which mark came first.
        val finalSurvivor = proposition("B").copy(grounding = listOf("chunk-b"), reinforceCount = 0)
        val middle = proposition("A").copy(grounding = listOf("chunk-a"), reinforceCount = 0)
        val loser = proposition("X").copy(grounding = listOf("chunk-x"))
        every { repository.query(any()) } returns listOf(middle, loser)
        val saved = mutableMapOf(finalSurvivor.id to finalSurvivor, middle.id to middle)
        every { repository.findById(any()) } answers { saved[firstArg()] }
        every { repository.save(any()) } answers {
            val p = firstArg<Proposition>()
            saved[p.id] = p
            p
        }

        val aIntoB = CollectorStrategy { candidates, _, _ ->
            candidates.filter { it.id == middle.id }
                .map { PropositionMark(it.id, MarkReason.Duplicate(survivorId = finalSurvivor.id), "duplicate") }
        }
        val xIntoA = CollectorStrategy { candidates, _, _ ->
            candidates.filter { it.id == loser.id }
                .map { PropositionMark(it.id, MarkReason.Duplicate(survivorId = middle.id), "duplicate") }
        }
        val runner = CollectorRunner
            .withRepository(repository)
            .withStrategy(aIntoB)
            .withStrategy(xIntoA)
            .withPolicy(MergingSweepPolicy())
            .withRecordStore(recordStore)
            .withEventListener(listener)
            .build()

        runner.run(contextId, dryRun = false)

        val finalState = saved.getValue(finalSurvivor.id)
        assertEquals(setOf("chunk-b", "chunk-a", "chunk-x"), finalState.grounding.toSet())
        assertEquals(2, finalState.reinforceCount)
        assertEquals(PropositionStatus.STALE, saved.getValue(middle.id).status)
        assertEquals(2, listener.eventsOfType<PropositionStatusChanged>().size)
    }

    @Test
    fun `a merge cycle within one run resolves deterministically instead of merging a survivor into itself`() {
        // A -> B and B -> A in the same run is a defect in the marks (nothing produces this on
        // purpose), but the runner must not corrupt state over it: it should pick a deterministic
        // winner (lowest id), keep that one ACTIVE with the other's evidence folded in, and retire
        // the loser — not attempt to merge a proposition into itself.
        val propA = proposition("A").copy(grounding = listOf("chunk-a"), reinforceCount = 0)
        val propB = proposition("B").copy(grounding = listOf("chunk-b"), reinforceCount = 0)
        val winnerId = minOf(propA.id, propB.id)
        val loserId = maxOf(propA.id, propB.id)
        every { repository.query(any()) } returns listOf(propA, propB)
        val saved = mutableMapOf(propA.id to propA, propB.id to propB)
        every { repository.findById(any()) } answers { saved[firstArg()] }
        every { repository.save(any()) } answers {
            val p = firstArg<Proposition>()
            saved[p.id] = p
            p
        }

        val cyclicStrategy = CollectorStrategy { _, _, _ ->
            listOf(
                PropositionMark(propA.id, MarkReason.Duplicate(survivorId = propB.id), "duplicate"),
                PropositionMark(propB.id, MarkReason.Duplicate(survivorId = propA.id), "duplicate"),
            )
        }
        val runner = CollectorRunner
            .withRepository(repository)
            .withStrategy(cyclicStrategy)
            .withPolicy(MergingSweepPolicy())
            .withRecordStore(recordStore)
            .withEventListener(listener)
            .build()

        runner.run(contextId, dryRun = false)

        // The lower id survives ACTIVE, having absorbed the other's evidence; the higher id is
        // retired. Nothing is left dangling ACTIVE-but-unmerged, and nothing merges into itself.
        assertEquals(PropositionStatus.ACTIVE, saved.getValue(winnerId).status)
        assertEquals(PropositionStatus.STALE, saved.getValue(loserId).status)
        assertEquals(setOf("chunk-a", "chunk-b"), saved.getValue(winnerId).grounding.toSet())
        assertEquals(1, listener.eventsOfType<PropositionStatusChanged>().size)
    }

    @Test
    fun `a chain feeding into a cycle resolves every node to the same terminal survivor`() {
        // X -> A -> Y -> B -> Y: the real cycle is {Y, B}; A is just a tail on the way in, not a
        // cycle member. The tie-break must be computed over the cycle's own members only — if it
        // were computed over the whole walked path (the bug this test guards against), X would
        // resolve to A while A resolves to Y/B, so X's evidence would land on A right before A
        // itself gets retired as a loser elsewhere, silently losing it.
        val x = proposition("X").copy(grounding = listOf("chunk-x"), reinforceCount = 0)
        val a = proposition("A").copy(grounding = listOf("chunk-a"), reinforceCount = 0)
        val y = proposition("Y").copy(grounding = listOf("chunk-y"), reinforceCount = 0)
        val b = proposition("B").copy(grounding = listOf("chunk-b"), reinforceCount = 0)
        val winnerId = minOf(y.id, b.id)
        val loserOfCycleId = maxOf(y.id, b.id)
        every { repository.query(any()) } returns listOf(x, a, y, b)
        val saved = mutableMapOf(x.id to x, a.id to a, y.id to y, b.id to b)
        every { repository.findById(any()) } answers { saved[firstArg()] }
        every { repository.save(any()) } answers {
            val p = firstArg<Proposition>()
            saved[p.id] = p
            p
        }

        val strategy = CollectorStrategy { _, _, _ ->
            listOf(
                PropositionMark(x.id, MarkReason.Duplicate(survivorId = a.id), "duplicate"),
                PropositionMark(a.id, MarkReason.Duplicate(survivorId = y.id), "duplicate"),
                PropositionMark(y.id, MarkReason.Duplicate(survivorId = b.id), "duplicate"),
                PropositionMark(b.id, MarkReason.Duplicate(survivorId = y.id), "duplicate"),
            )
        }
        val runner = CollectorRunner
            .withRepository(repository)
            .withStrategy(strategy)
            .withPolicy(MergingSweepPolicy())
            .withRecordStore(recordStore)
            .withEventListener(listener)
            .build()

        runner.run(contextId, dryRun = false)

        // Every id on the walk — chain and cycle alike — lands on the same terminal survivor, and
        // X's evidence (fed in from outside the cycle) reaches it rather than being dropped on A.
        val winnerState = saved.getValue(winnerId)
        assertEquals(PropositionStatus.ACTIVE, winnerState.status)
        assertEquals(setOf("chunk-x", "chunk-a", "chunk-y", "chunk-b"), winnerState.grounding.toSet())
        assertEquals(PropositionStatus.STALE, saved.getValue(a.id).status)
        assertEquals(PropositionStatus.STALE, saved.getValue(x.id).status)
        assertEquals(PropositionStatus.STALE, saved.getValue(loserOfCycleId).status)
    }

    @Test
    fun `a longer tail of two nodes into a cycle still resolves everyone to the same survivor`() {
        // W -> X -> A -> Y -> B -> Y: two tail nodes (W, X) ahead of the same {Y, B} cycle as
        // above. Guards against an off-by-one in where the tail/cycle boundary is detected.
        val w = proposition("W").copy(grounding = listOf("chunk-w"), reinforceCount = 0)
        val x = proposition("X").copy(grounding = listOf("chunk-x"), reinforceCount = 0)
        val a = proposition("A").copy(grounding = listOf("chunk-a"), reinforceCount = 0)
        val y = proposition("Y").copy(grounding = listOf("chunk-y"), reinforceCount = 0)
        val b = proposition("B").copy(grounding = listOf("chunk-b"), reinforceCount = 0)
        val winnerId = minOf(y.id, b.id)
        every { repository.query(any()) } returns listOf(w, x, a, y, b)
        val saved = mutableMapOf(w.id to w, x.id to x, a.id to a, y.id to y, b.id to b)
        every { repository.findById(any()) } answers { saved[firstArg()] }
        every { repository.save(any()) } answers {
            val p = firstArg<Proposition>()
            saved[p.id] = p
            p
        }

        val strategy = CollectorStrategy { _, _, _ ->
            listOf(
                PropositionMark(w.id, MarkReason.Duplicate(survivorId = x.id), "duplicate"),
                PropositionMark(x.id, MarkReason.Duplicate(survivorId = a.id), "duplicate"),
                PropositionMark(a.id, MarkReason.Duplicate(survivorId = y.id), "duplicate"),
                PropositionMark(y.id, MarkReason.Duplicate(survivorId = b.id), "duplicate"),
                PropositionMark(b.id, MarkReason.Duplicate(survivorId = y.id), "duplicate"),
            )
        }
        val runner = CollectorRunner
            .withRepository(repository)
            .withStrategy(strategy)
            .withPolicy(MergingSweepPolicy())
            .withRecordStore(recordStore)
            .withEventListener(listener)
            .build()

        runner.run(contextId, dryRun = false)

        val winnerState = saved.getValue(winnerId)
        assertEquals(PropositionStatus.ACTIVE, winnerState.status)
        assertEquals(
            setOf("chunk-w", "chunk-x", "chunk-a", "chunk-y", "chunk-b"),
            winnerState.grounding.toSet(),
        )
        assertEquals(PropositionStatus.STALE, saved.getValue(w.id).status)
        assertEquals(PropositionStatus.STALE, saved.getValue(x.id).status)
        assertEquals(PropositionStatus.STALE, saved.getValue(a.id).status)
    }

    @Test
    fun `withDuplicateDetection does not clobber a policy the caller set explicitly`() {
        // A caller who sets a policy explicitly must keep it, whatever the call order. Here a Skip
        // policy must win over the MergingSweepPolicy that withDuplicateDetection would otherwise
        // install, so the marked duplicate is skipped rather than merged.
        val survivor = proposition("survivor")
        val loser = proposition("loser")
        every { repository.query(any()) } returns listOf(survivor, loser)
        every { repository.findClusters(any(), any(), any()) } returns
                listOf(Cluster(survivor, listOf(SimilarityResult.create(loser, 0.99))))

        val skipEverything = SweepPolicy { _, _ -> SweepAction.Skip }
        val runner = CollectorRunner
            .withRepository(repository)
            .withPolicy(skipEverything)
            .withDuplicateDetection()
            .withEventListener(listener)
            .build()

        val result = runner.run(contextId, dryRun = false)

        assertTrue(result.applied.isEmpty())
        assertTrue(result.skipped.isNotEmpty())
        verify(exactly = 0) { repository.save(any()) }
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

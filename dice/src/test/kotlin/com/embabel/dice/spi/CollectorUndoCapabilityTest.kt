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
package com.embabel.dice.spi

import com.embabel.agent.core.ContextId
import com.embabel.dice.common.DiceEvent
import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.common.PropositionPersisted
import com.embabel.dice.projection.lineage.CollectorOutcome
import com.embabel.dice.projection.lineage.CollectorRecord
import com.embabel.dice.projection.lineage.CollectorRecordStore
import com.embabel.dice.projection.lineage.CollectorRun
import com.embabel.dice.projection.lineage.InMemoryCollectorRecordStore
import com.embabel.dice.projection.memory.DefaultCollectorRunner
import com.embabel.dice.projection.memory.RunAwareCollectorStrategy
import com.embabel.dice.projection.memory.collector.CollectorRunContext
import com.embabel.dice.projection.memory.collector.CollectorSurvivorPolicy
import com.embabel.dice.projection.memory.collector.MultiSignalCollectorStrategy
import com.embabel.dice.proposition.EventEmittingPropositionRepository
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.ProvenanceEvidenceKey
import com.embabel.dice.provenance.UriLocator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * What `undoSingleCollapse` owes a store: evidence really comes off the survivor, a fold of
 * revisioned evidence is reversed exactly, and a collapse whose participants have since gone
 * leaves nothing half-written.
 */
class CollectorUndoCapabilityTest {

    private val contextId = ContextId("ctx-undo-capability")
    private val locator = UriLocator("https://example.com/source")
    private val revisionOne = ProvenanceEntry(locator = locator, sourceRevision = "r1")
    private val revisionTwo = ProvenanceEntry(locator = locator, sourceRevision = "r2")

    @Test
    fun `undo uses authoritative provenance replacement through a base store decorator`() {
        val store = AppendPreservingStore()
        val trace = InMemoryCollectorTraceStore()
        val keep = ProvenanceEntry(UriLocator("https://example.com/keep"))
        val folded = ProvenanceEntry(UriLocator("https://example.com/folded"))
        val survivor = store.save(proposition("survivor", listOf(keep, folded)))
        val retired = store.save(
            proposition("retired", listOf(folded), status = PropositionStatus.STALE),
        )
        val runId = "run-base-store-undo"
        trace.recordRunContext(runId, contextId)
        trace.recordDecision(
            runId,
            decisionWith(
                componentId = "component-base-store-undo",
                survivorId = survivor.id,
                retired = RetiredProposition(
                    propositionId = retired.id,
                    priorStatus = PropositionStatus.ACTIVE,
                    foldedProvenanceRefs = listOf(folded.locator.key()),
                ),
            ),
        )

        val result = undoSingleCollapse(trace, store, survivor.id, retired.id)

        assertEquals(listOf(keep), result?.survivor?.provenanceEntries)
        assertEquals(listOf(keep), store.findById(survivor.id)?.provenanceEntries)
        assertEquals(PropositionStatus.ACTIVE, result?.restored?.status)
    }

    @Test
    fun `folding a revisioned loser and undoing leaves the survivor's evidence as it was`() {
        val store = InMemoryPropositionRepository()
        val trace = InMemoryCollectorTraceStore()
        val survivor = store.save(
            proposition("survivor-revision", listOf(revisionOne), text = "Acme signed the agreement"),
        )
        val loser = store.save(
            proposition("loser-revision", listOf(revisionOne, revisionTwo), text = "Acme signed an agreement"),
        )
        val evidenceBeforeTheFold = survivor.provenanceEntries

        collapse(store, trace, "run-revision-undo", survivor, loser)

        // The fold under test: the survivor now also carries r2, and the trace names it exactly.
        assertEquals(listOf(revisionOne, revisionTwo), store.findById(survivor.id)?.provenanceEntries)
        assertEquals(
            listOf(ProvenanceEvidenceKey.encode(revisionTwo)),
            trace.findRetirement(loser.id)?.foldedProvenanceEvidenceKeys,
        )

        val result = undoSingleCollapse(trace, store, survivor.id, loser.id)

        assertEquals(evidenceBeforeTheFold, result?.survivor?.provenanceEntries)
        assertEquals(evidenceBeforeTheFold, store.findById(survivor.id)?.provenanceEntries)
        assertEquals(PropositionStatus.ACTIVE, store.findById(loser.id)?.status)
    }

    @Test
    fun `a legacy locator-key trace leaves revisioned evidence on the survivor`() {
        // A trace recorded before evidence keys existed names its evidence by locator key, which
        // reaches revisionless entries only. So an old undo removes exactly what it always removed
        // and never touches a revision it never saw.
        val store = InMemoryPropositionRepository()
        val trace = InMemoryCollectorTraceStore()
        val survivor = store.save(proposition("survivor-legacy", listOf(revisionOne, revisionTwo)))
        val retired = store.save(
            proposition("retired-legacy", listOf(revisionTwo), status = PropositionStatus.STALE),
        )
        val runId = "run-legacy-trace"
        trace.recordRunContext(runId, contextId)
        trace.recordDecision(
            runId,
            decisionWith(
                componentId = "component-legacy",
                survivorId = survivor.id,
                retired = RetiredProposition(
                    propositionId = retired.id,
                    priorStatus = PropositionStatus.ACTIVE,
                    foldedProvenanceRefs = listOf(locator.key()),
                ),
            ),
        )

        undoSingleCollapse(trace, store, survivor.id, retired.id)

        assertEquals(listOf(revisionOne, revisionTwo), store.findById(survivor.id)?.provenanceEntries)
    }

    @Test
    fun `undoing both members of a shared fold drains the survivor back to its pre-collapse evidence`() {
        // Two losers folded the same revision. The first undo must leave it — the second loser is
        // still retired and the survivor is holding that evidence on its behalf. The second undo
        // must take it, because by then nobody is.
        val store = InMemoryPropositionRepository()
        val trace = InMemoryCollectorTraceStore()
        val evidenceBeforeTheCollapse = listOf(revisionOne)
        val survivor = store.save(proposition("survivor-siblings", listOf(revisionOne, revisionTwo)))
        store.save(proposition("loser-a", listOf(revisionTwo), status = PropositionStatus.STALE))
        store.save(proposition("loser-b", listOf(revisionTwo), status = PropositionStatus.STALE))
        val sharedRevision = ProvenanceEvidenceKey.encode(revisionTwo)
        val runId = "run-siblings"
        trace.recordRunContext(runId, contextId)
        trace.recordDecision(
            runId,
            CollectorDecision(
                runId = runId,
                componentId = "component-siblings",
                survivorId = survivor.id,
                action = "duplicate-merge",
                retired = listOf(
                    RetiredProposition(
                        propositionId = "loser-a",
                        priorStatus = PropositionStatus.ACTIVE,
                        foldedProvenanceEvidenceKeys = listOf(sharedRevision),
                    ),
                    RetiredProposition(
                        propositionId = "loser-b",
                        priorStatus = PropositionStatus.ACTIVE,
                        foldedProvenanceEvidenceKeys = listOf(sharedRevision),
                    ),
                ),
            ),
        )

        undoSingleCollapse(trace, store, survivor.id, "loser-a")

        assertEquals(listOf(revisionOne, revisionTwo), store.findById(survivor.id)?.provenanceEntries)
        assertEquals(PropositionStatus.ACTIVE, store.findById("loser-a")?.status)
        assertEquals(PropositionStatus.STALE, store.findById("loser-b")?.status)

        undoSingleCollapse(trace, store, survivor.id, "loser-b")

        assertEquals(evidenceBeforeTheCollapse, store.findById(survivor.id)?.provenanceEntries)
        assertEquals(PropositionStatus.ACTIVE, store.findById("loser-b")?.status)

        // Undoing an already-restored member is a no-op, not a second subtraction.
        assertNull(undoSingleCollapse(trace, store, survivor.id, "loser-a"))
        assertEquals(evidenceBeforeTheCollapse, store.findById(survivor.id)?.provenanceEntries)
    }

    @Test
    fun `a dry-run fold leaves the survivor untouched when undone`() {
        // The strategy records its trace during the mark phase, before the runner decides whether
        // to apply anything. A dry run therefore leaves a decision that looks exactly like an
        // applied fold. The loser's status is what says otherwise: nothing retired it.
        val store = InMemoryPropositionRepository()
        val trace = InMemoryCollectorTraceStore()
        val survivor = store.save(
            proposition("survivor-dry-run", listOf(revisionOne), text = "Acme signed the agreement"),
        )
        val loser = store.save(
            proposition("loser-dry-run", listOf(revisionOne, revisionTwo), text = "Acme signed an agreement"),
        )

        strategyFor(survivor.id, trace).mark(
            listOf(survivor, loser),
            store,
            CollectorRunContext("run-dry", contextId, dryRun = true),
        )

        // The trace is there, and it names the evidence a real fold would have moved.
        assertEquals(
            listOf(ProvenanceEvidenceKey.encode(revisionTwo)),
            trace.findRetirement(loser.id)?.foldedProvenanceEvidenceKeys,
        )

        // Nothing was applied, and later the survivor is extracted with that revision on its own.
        store.save(store.findById(survivor.id)!!.withProvenanceEntries(listOf(revisionTwo)))

        val result = undoSingleCollapse(trace, store, survivor.id, loser.id)

        assertNull(result)
        assertEquals(listOf(revisionOne, revisionTwo), store.findById(survivor.id)?.provenanceEntries)
        assertEquals(PropositionStatus.ACTIVE, store.findById(loser.id)?.status)
    }

    @Test
    fun `a collapse whose retired member is gone writes nothing`() {
        val store = CountingStore()
        val trace = InMemoryCollectorTraceStore()
        store.seed(proposition("survivor-orphaned", listOf(revisionOne, revisionTwo)))
        trace.recordRunContext("run-missing-retired", contextId)
        trace.recordDecision(
            "run-missing-retired",
            decisionWith("component-missing-retired", "survivor-orphaned", foldedRevisionTwo("gone-retired")),
        )

        val result = undoSingleCollapse(trace, store, "survivor-orphaned", "gone-retired")

        assertNull(result)
        assertEquals(0, store.writes)
        assertEquals(listOf(revisionOne, revisionTwo), store.findById("survivor-orphaned")?.provenanceEntries)
    }

    @Test
    fun `a collapse whose survivor is gone writes nothing`() {
        val store = CountingStore()
        val trace = InMemoryCollectorTraceStore()
        store.seed(proposition("retired-orphaned", listOf(revisionTwo), status = PropositionStatus.STALE))
        trace.recordRunContext("run-missing-survivor", contextId)
        trace.recordDecision(
            "run-missing-survivor",
            decisionWith("component-missing-survivor", "gone-survivor", foldedRevisionTwo("retired-orphaned")),
        )

        val result = undoSingleCollapse(trace, store, "gone-survivor", "retired-orphaned")

        assertNull(result)
        assertEquals(0, store.writes)
        assertEquals(PropositionStatus.STALE, store.findById("retired-orphaned")?.status)
    }

    @Test
    fun `a survivor deleted before the subtraction is not recreated by the undo`() {
        // The survivor is read once, up front. Another writer can delete it before the subtraction
        // lands, and the store then answers null. Continuing from the copy read earlier would save
        // that copy back and recreate a proposition somebody else deleted, folded evidence and all.
        //
        // Which branch this exercises, precisely: InMemoryPropositionRepository overrides neither
        // `subtractProvenance` nor `setProvenance`, so the subtraction here is the base contract's
        // default, and the deletion lands before its opening `findById`. That read misses and the default answers null on the spot.
        // Deletions arriving later are a different matter on each store — the default can recreate
        // the survivor inside its own read-modify-write, and either store's answer is followed by
        // the undo's `save`, which upserts. The design note scopes both; neither is pinned here.
        val store = InMemoryPropositionRepository()
        val trace = InMemoryCollectorTraceStore()
        val records = InMemoryCollectorRecordStore()
        val survivor = store.save(
            proposition("survivor-deleted-midway", listOf(revisionOne), text = "Acme signed the agreement"),
        )
        val loser = store.save(
            proposition("loser-deleted-midway", listOf(revisionOne, revisionTwo), text = "Acme signed an agreement"),
        )
        val runId = sweep(store, trace, records, survivor.id)
        assertEquals(listOf(revisionOne, revisionTwo), store.findById(survivor.id)?.provenanceEntries)

        val racing = DeletingOnSubtract(store, deletes = survivor.id)
        val result = undoSingleCollapse(trace, racing, survivor.id, loser.id, records)

        assertNull(store.findById(survivor.id), "the deletion wins; the undo must not put the survivor back")
        assertNull(result, "an undo that could not subtract anything must not report a survivor")
        assertEquals(
            PropositionStatus.STALE,
            store.findById(loser.id)?.status,
            "no restore was performed, so the member stays retired",
        )
        assertTrue(
            records.findByProposition(loser.id).none { it.runId == runId && it.undoneAt != null },
            "an undo that did nothing must not stamp itself as done",
        )
    }

    @Test
    fun `a dry-run trace and an unrelated status transition do not authorize an undo`() {
        // The status proxy alone cannot tell a retirement from a decay sweep. With the run's audit
        // records in hand it does not have to: the run says it changed nothing.
        val store = InMemoryPropositionRepository()
        val trace = InMemoryCollectorTraceStore()
        val records = InMemoryCollectorRecordStore()
        val runId = "run-dry-then-decay"
        val survivor = store.save(
            proposition("survivor-decay", listOf(revisionOne), text = "Acme signed the agreement"),
        )
        val loser = store.save(
            proposition("loser-decay", listOf(revisionOne, revisionTwo), text = "Acme signed an agreement"),
        )
        strategyFor(survivor.id, trace).mark(
            listOf(survivor, loser),
            store,
            CollectorRunContext(runId, contextId, dryRun = true),
        )
        records.recordRun(CollectorRun(runId = runId, startedAt = Instant.now(), dryRun = true))
        records.record(
            CollectorRecord(
                propositionId = loser.id,
                reason = MarkReason.Duplicate(survivorId = survivor.id),
                outcome = CollectorOutcome.MARKED,
                strategyName = "multi-signal",
                runId = runId,
            ),
        )
        // The survivor picks up that revision on its own, and a later decay sweep — nothing to do
        // with the collector — moves the loser off ACTIVE.
        store.save(store.findById(survivor.id)!!.withProvenanceEntries(listOf(revisionTwo)))
        store.save(store.findById(loser.id)!!.withStatus(PropositionStatus.STALE))

        val result = undoSingleCollapse(trace, store, survivor.id, loser.id, records)

        assertNull(result)
        assertEquals(listOf(revisionOne, revisionTwo), store.findById(survivor.id)?.provenanceEntries)
        assertEquals(PropositionStatus.STALE, store.findById(loser.id)?.status)
    }

    @Test
    fun `a collapse the real collector applied is undone`() {
        // End to end through DefaultCollectorRunner: mark, MergingSweepPolicy, sweep, record.
        val store = InMemoryPropositionRepository()
        val trace = InMemoryCollectorTraceStore()
        val records = InMemoryCollectorRecordStore()
        val survivor = store.save(
            proposition("survivor-applied", listOf(revisionOne), text = "Acme signed the agreement"),
        )
        val loser = store.save(
            proposition("loser-applied", listOf(revisionOne, revisionTwo), text = "Acme signed an agreement"),
        )

        val runId = sweep(store, trace, records, survivor.id)

        // The runner folded the loser's evidence on and wrote down where it went.
        assertEquals(listOf(revisionOne, revisionTwo), store.findById(survivor.id)?.provenanceEntries)
        assertEquals(PropositionStatus.STALE, store.findById(loser.id)?.status)
        assertEquals(
            listOf(survivor.id),
            records.findByProposition(loser.id).filter { it.runId == runId }.map { it.mergedIntoId },
        )

        val result = undoSingleCollapse(trace, store, survivor.id, loser.id, records)

        assertEquals(listOf(revisionOne), result?.survivor?.provenanceEntries)
        assertEquals(listOf(revisionOne), store.findById(survivor.id)?.provenanceEntries)
        assertEquals(PropositionStatus.ACTIVE, store.findById(loser.id)?.status)
    }

    @Test
    fun `retrying an undo that already succeeded takes nothing further from the survivor`() {
        // The records are append-only: the run's record still names the merge target after the undo
        // has run. Only the member's own status says the work is done.
        val store = InMemoryPropositionRepository()
        val trace = InMemoryCollectorTraceStore()
        val records = InMemoryCollectorRecordStore()
        val survivor = store.save(
            proposition("survivor-retry", listOf(revisionOne), text = "Acme signed the agreement"),
        )
        val loser = store.save(
            proposition("loser-retry", listOf(revisionOne, revisionTwo), text = "Acme signed an agreement"),
        )
        sweep(store, trace, records, survivor.id)

        undoSingleCollapse(trace, store, survivor.id, loser.id, records)
        assertEquals(listOf(revisionOne), store.findById(survivor.id)?.provenanceEntries)

        // The survivor is later extracted with that revision again, on its own account.
        store.save(store.findById(survivor.id)!!.withProvenanceEntries(listOf(revisionTwo)))

        val retry = undoSingleCollapse(trace, store, survivor.id, loser.id, records)

        assertNull(retry)
        assertEquals(listOf(revisionOne, revisionTwo), store.findById(survivor.id)?.provenanceEntries)
    }

    @Test
    fun `a sibling re-retired after its own undo stops holding the shared evidence`() {
        // A and B both folded the same revision. A is undone, then a later run retires A again. On
        // status alone A reads as still participating in the original collapse, so B's undo keeps
        // the shared evidence and the survivor never gets back to where it started. A's undoneAt
        // for this run is what says its fold is reversed, whatever happened to it since.
        val store = InMemoryPropositionRepository()
        val trace = InMemoryCollectorTraceStore()
        val records = InMemoryCollectorRecordStore()
        val evidenceBeforeTheCollapse = listOf(revisionOne)
        val survivor = store.save(proposition("survivor-shared", listOf(revisionOne, revisionTwo)))
        store.save(proposition("loser-a", listOf(revisionTwo), status = PropositionStatus.STALE))
        store.save(proposition("loser-b", listOf(revisionTwo), status = PropositionStatus.STALE))
        val sharedRevision = ProvenanceEvidenceKey.encode(revisionTwo)
        val runId = "run-shared-siblings"
        trace.recordRunContext(runId, contextId)
        trace.recordDecision(
            runId,
            CollectorDecision(
                runId = runId,
                componentId = "component-shared-siblings",
                survivorId = survivor.id,
                action = "duplicate-merge",
                retired = listOf(
                    RetiredProposition("loser-a", PropositionStatus.ACTIVE, foldedProvenanceEvidenceKeys = listOf(sharedRevision)),
                    RetiredProposition("loser-b", PropositionStatus.ACTIVE, foldedProvenanceEvidenceKeys = listOf(sharedRevision)),
                ),
            ),
        )
        records.recordRun(CollectorRun(runId = runId, startedAt = Instant.now(), dryRun = false))
        listOf("loser-a", "loser-b").forEach { memberId ->
            records.record(
                CollectorRecord(
                    propositionId = memberId,
                    reason = MarkReason.Duplicate(survivorId = survivor.id),
                    outcome = CollectorOutcome.TRANSITIONED,
                    strategyName = "multi-signal",
                    runId = runId,
                    previousStatus = PropositionStatus.ACTIVE,
                    newStatus = PropositionStatus.STALE,
                    mergedIntoId = survivor.id,
                ),
            )
        }

        undoSingleCollapse(trace, store, survivor.id, "loser-a", records)
        assertEquals(listOf(revisionOne, revisionTwo), store.findById(survivor.id)?.provenanceEntries)

        // A later run retires A again, for reasons of its own.
        retireAgain(store, records, "loser-a")

        undoSingleCollapse(trace, store, survivor.id, "loser-b", records)

        assertEquals(evidenceBeforeTheCollapse, store.findById(survivor.id)?.provenanceEntries)
        assertEquals(PropositionStatus.ACTIVE, store.findById("loser-b")?.status)
        assertEquals(PropositionStatus.STALE, store.findById("loser-a")?.status, "A's later retirement stands")
    }

    @Test
    fun `a later unrelated retirement cannot re-arm an undo that already ran`() {
        // The audit records never expire, so "was this applied" stays true forever. If completion
        // were judged by status alone, any later retirement — a decay sweep, another collector run —
        // would put the member back off its prior status and re-arm run 1's undo, which would then
        // subtract run 1's evidence a second time. The undoneAt stamp is what closes that.
        val store = InMemoryPropositionRepository()
        val trace = InMemoryCollectorTraceStore()
        val records = InMemoryCollectorRecordStore()
        val survivor = store.save(
            proposition("survivor-rearm", listOf(revisionOne), text = "Acme signed the agreement"),
        )
        val loser = store.save(
            proposition("loser-rearm", listOf(revisionOne, revisionTwo), text = "Acme signed an agreement"),
        )
        val runId = sweep(store, trace, records, survivor.id)

        undoSingleCollapse(trace, store, survivor.id, loser.id, records)
        assertEquals(listOf(revisionOne), store.findById(survivor.id)?.provenanceEntries)
        assertTrue(
            records.findByProposition(loser.id).any { it.runId == runId && it.undoneAt != null },
            "finishing the undo must stamp the run's record",
        )

        // Life goes on: the survivor picks that revision up again, and a later decay sweep retires
        // the member a second time.
        store.save(store.findById(survivor.id)!!.withProvenanceEntries(listOf(revisionTwo)))
        retireAgain(store, records, loser.id)

        val retry = undoSingleCollapse(trace, store, survivor.id, loser.id, records)

        assertNull(retry)
        assertEquals(listOf(revisionOne, revisionTwo), store.findById(survivor.id)?.provenanceEntries)
        assertEquals(PropositionStatus.STALE, store.findById(loser.id)?.status)
    }

    @Test
    fun `replaying the original outcome after an undo does not clear its stamp`() {
        // Replaying a collector outcome is supported, and a replayed record carries no stamp. If
        // that cleared the stored one, the collapse would be authorized again.
        val store = InMemoryPropositionRepository()
        val trace = InMemoryCollectorTraceStore()
        val records = InMemoryCollectorRecordStore()
        val survivor = store.save(
            proposition("survivor-replay", listOf(revisionOne), text = "Acme signed the agreement"),
        )
        val loser = store.save(
            proposition("loser-replay", listOf(revisionOne, revisionTwo), text = "Acme signed an agreement"),
        )
        val runId = sweep(store, trace, records, survivor.id)
        val original = records.findByProposition(loser.id).first { it.runId == runId }

        undoSingleCollapse(trace, store, survivor.id, loser.id, records)

        records.record(original)
        assertTrue(
            records.findByProposition(loser.id).any { it.runId == runId && it.undoneAt != null },
            "a replayed outcome must not erase the undo stamp",
        )

        store.save(store.findById(survivor.id)!!.withProvenanceEntries(listOf(revisionTwo)))
        retireAgain(store, records, loser.id)

        assertNull(undoSingleCollapse(trace, store, survivor.id, loser.id, records))
        assertEquals(listOf(revisionOne, revisionTwo), store.findById(survivor.id)?.provenanceEntries)
    }

    /** A later, unrelated sweep retiring the member again — status change plus its own audit record. */
    private fun retireAgain(
        store: InMemoryPropositionRepository,
        records: InMemoryCollectorRecordStore,
        memberId: String,
        runId: String = "run-later-decay",
    ) {
        store.save(store.findById(memberId)!!.withStatus(PropositionStatus.STALE))
        records.recordRun(CollectorRun(runId = runId, startedAt = Instant.now(), dryRun = false))
        records.record(
            CollectorRecord(
                propositionId = memberId,
                reason = MarkReason.Stale,
                outcome = CollectorOutcome.TRANSITIONED,
                strategyName = "decay",
                runId = runId,
                previousStatus = PropositionStatus.ACTIVE,
                newStatus = PropositionStatus.STALE,
            ),
        )
    }

    @Test
    fun `an undo interrupted before its stamp is completed by a retry`() {
        // Crash window (2): the survivor's writes landed, the stamp did not. The member is still
        // retired and nothing is stamped, so a retry re-runs the whole undo — and the evidence
        // subtraction is a no-op because it is recomputed from what the survivor holds now.
        val store = InMemoryPropositionRepository()
        val trace = InMemoryCollectorTraceStore()
        val records = InMemoryCollectorRecordStore()
        val survivor = store.save(
            proposition("survivor-crash-stamp", listOf(revisionOne), text = "Acme signed the agreement"),
        )
        val loser = store.save(
            proposition("loser-crash-stamp", listOf(revisionOne, revisionTwo), text = "Acme signed an agreement"),
        )
        val runId = sweep(store, trace, records, survivor.id)
        val failing = FailingRecordStore(records)

        assertThrows(IllegalStateException::class.java) {
            undoSingleCollapse(trace, store, survivor.id, loser.id, failing)
        }

        // The survivor's evidence is already off; the member is still retired; nothing is stamped.
        assertEquals(listOf(revisionOne), store.findById(survivor.id)?.provenanceEntries)
        assertEquals(PropositionStatus.STALE, store.findById(loser.id)?.status)
        assertTrue(records.findByProposition(loser.id).none { it.undoneAt != null })

        val result = undoSingleCollapse(trace, store, survivor.id, loser.id, records)

        assertEquals(listOf(revisionOne), result?.survivor?.provenanceEntries)
        assertEquals(PropositionStatus.ACTIVE, store.findById(loser.id)?.status)
        assertTrue(records.findByProposition(loser.id).any { it.runId == runId && it.undoneAt != null })
    }

    @Test
    fun `an undo interrupted after its stamp completes only the restore`() {
        // Crash window (3): stamped, but the member never got restored. A retry must finish the
        // restore and touch no evidence — the survivor may have re-gained that revision since.
        val store = InMemoryPropositionRepository()
        val trace = InMemoryCollectorTraceStore()
        val records = InMemoryCollectorRecordStore()
        val survivor = store.save(
            proposition("survivor-crash-restore", listOf(revisionOne), text = "Acme signed the agreement"),
        )
        val loser = store.save(
            proposition("loser-crash-restore", listOf(revisionOne, revisionTwo), text = "Acme signed an agreement"),
        )
        sweep(store, trace, records, survivor.id)

        // Stand in for the crash: run the undo far enough to stamp, then stop before the restore.
        val stoppingBeforeRestore = FailingPropositionStore(store, failOnSaveOf = loser.id)
        assertThrows(IllegalStateException::class.java) {
            undoSingleCollapse(trace, stoppingBeforeRestore, survivor.id, loser.id, records)
        }
        assertEquals(listOf(revisionOne), store.findById(survivor.id)?.provenanceEntries)
        assertEquals(PropositionStatus.STALE, store.findById(loser.id)?.status)

        // The survivor legitimately re-gains that revision before anyone retries.
        store.save(store.findById(survivor.id)!!.withProvenanceEntries(listOf(revisionTwo)))

        val result = undoSingleCollapse(trace, store, survivor.id, loser.id, records)

        assertEquals(PropositionStatus.ACTIVE, store.findById(loser.id)?.status)
        assertEquals(
            listOf(revisionOne, revisionTwo),
            store.findById(survivor.id)?.provenanceEntries,
            "resuming must restore the member without touching evidence",
        )
    }

    @Test
    fun `a later run that skipped the member does not strand an interrupted undo`() {
        // A SKIPPED record is the literal statement that a run left the member alone, and a dry run
        // changes nothing. Counting either as "someone acted since the stamp" would refuse the
        // resumption forever, leaving the member retired with its evidence already off the survivor.
        val store = InMemoryPropositionRepository()
        val trace = InMemoryCollectorTraceStore()
        val records = InMemoryCollectorRecordStore()
        val survivor = store.save(
            proposition("survivor-skip", listOf(revisionOne), text = "Acme signed the agreement"),
        )
        val loser = store.save(
            proposition("loser-skip", listOf(revisionOne, revisionTwo), text = "Acme signed an agreement"),
        )
        sweep(store, trace, records, survivor.id)

        val stoppingBeforeRestore = FailingPropositionStore(store, failOnSaveOf = loser.id)
        assertThrows(IllegalStateException::class.java) {
            undoSingleCollapse(trace, stoppingBeforeRestore, survivor.id, loser.id, records)
        }

        // Inside the crash window: a later real run skips the member, and a dry run previews it.
        records.recordRun(CollectorRun(runId = "run-skipper", startedAt = Instant.now(), dryRun = false))
        records.record(
            CollectorRecord(
                propositionId = loser.id,
                reason = MarkReason.Stale,
                outcome = CollectorOutcome.SKIPPED,
                strategyName = "decay",
                runId = "run-skipper",
            ),
        )
        records.recordRun(CollectorRun(runId = "run-preview", startedAt = Instant.now(), dryRun = true))
        records.record(
            CollectorRecord(
                propositionId = loser.id,
                reason = MarkReason.Stale,
                outcome = CollectorOutcome.TRANSITIONED,
                strategyName = "decay",
                runId = "run-preview",
                previousStatus = PropositionStatus.ACTIVE,
                newStatus = PropositionStatus.STALE,
            ),
        )

        val result = undoSingleCollapse(trace, store, survivor.id, loser.id, records)

        assertEquals(PropositionStatus.ACTIVE, store.findById(loser.id)?.status)
        assertEquals(listOf(revisionOne), result?.survivor?.provenanceEntries)
    }

    @Test
    fun `a stamped record that never recorded where it left the member does not resume`() {
        // A record with no newStatus predates the mechanism, so it cannot say where the collapse
        // left the member. Treating that as "sitting where we left it" would resume against a
        // re-retirement to any status at all, so it refuses instead.
        val store = InMemoryPropositionRepository()
        val trace = InMemoryCollectorTraceStore()
        val records = InMemoryCollectorRecordStore()
        val runId = "run-legacy-stamp"
        val survivor = store.save(proposition("survivor-legacy-stamp", listOf(revisionOne, revisionTwo)))
        store.save(
            proposition("loser-legacy-stamp", listOf(revisionTwo), status = PropositionStatus.STALE),
        )
        trace.recordRunContext(runId, contextId)
        trace.recordDecision(
            runId,
            decisionWith(
                componentId = "component-legacy-stamp",
                survivorId = survivor.id,
                retired = RetiredProposition(
                    propositionId = "loser-legacy-stamp",
                    priorStatus = PropositionStatus.ACTIVE,
                    foldedProvenanceEvidenceKeys = listOf(ProvenanceEvidenceKey.encode(revisionTwo)),
                ),
            ),
        )
        records.recordRun(CollectorRun(runId = runId, startedAt = Instant.now(), dryRun = false))
        records.record(
            CollectorRecord(
                propositionId = "loser-legacy-stamp",
                reason = MarkReason.Duplicate(survivorId = survivor.id),
                outcome = CollectorOutcome.TRANSITIONED,
                strategyName = "multi-signal",
                runId = runId,
                mergedIntoId = survivor.id,
                undoneAt = Instant.now(),
            ),
        )

        val result = undoSingleCollapse(trace, store, survivor.id, "loser-legacy-stamp", records)

        assertNull(result)
        assertEquals(listOf(revisionOne, revisionTwo), store.findById(survivor.id)?.provenanceEntries)
        assertEquals(PropositionStatus.STALE, store.findById("loser-legacy-stamp")?.status)
    }

    @Test
    fun `an applied collapse whose member revived is refused, like any completed undo`() {
        // A revived member and a completed undo are the same observation: status back at
        // priorStatus, records still naming the merge. Refusing loses an undo; accepting would
        // delete evidence the survivor may have re-earned. The refusal is the deliberate choice.
        val store = InMemoryPropositionRepository()
        val trace = InMemoryCollectorTraceStore()
        val records = InMemoryCollectorRecordStore()
        val survivor = store.save(
            proposition("survivor-revived", listOf(revisionOne), text = "Acme signed the agreement"),
        )
        val loser = store.save(
            proposition("loser-revived", listOf(revisionOne, revisionTwo), text = "Acme signed an agreement"),
        )
        sweep(store, trace, records, survivor.id)
        val foldedEvidence = store.findById(survivor.id)!!.provenanceEntries

        store.save(store.findById(loser.id)!!.withStatus(PropositionStatus.ACTIVE))

        val result = undoSingleCollapse(trace, store, survivor.id, loser.id, records)

        assertNull(result)
        assertEquals(foldedEvidence, store.findById(survivor.id)?.provenanceEntries)
    }

    @Test
    fun `a status-transition sweep records no merge, so its trace cannot authorize an undo`() {
        // StatusTransitionSweepPolicy retires the loser and folds nothing. The trace looks the same
        // as an applied merge; the absence of a merge target on the record is what differs.
        val store = InMemoryPropositionRepository()
        val trace = InMemoryCollectorTraceStore()
        val records = InMemoryCollectorRecordStore()
        val survivor = store.save(
            proposition("survivor-transition", listOf(revisionOne), text = "Acme signed the agreement"),
        )
        val loser = store.save(
            proposition("loser-transition", listOf(revisionOne, revisionTwo), text = "Acme signed an agreement"),
        )

        val runId = sweep(store, trace, records, survivor.id, policy = StatusTransitionSweepPolicy())

        assertEquals(PropositionStatus.STALE, store.findById(loser.id)?.status)
        assertEquals(
            listOf<String?>(null),
            records.findByProposition(loser.id).filter { it.runId == runId }.map { it.mergedIntoId },
        )
        // The survivor picks that revision up on its own afterwards.
        store.save(store.findById(survivor.id)!!.withProvenanceEntries(listOf(revisionTwo)))

        val result = undoSingleCollapse(trace, store, survivor.id, loser.id, records)

        assertNull(result)
        assertEquals(listOf(revisionOne, revisionTwo), store.findById(survivor.id)?.provenanceEntries)
    }

    @Test
    fun `a fallback retirement records no merge, so its trace cannot authorize an undo`() {
        // The named survivor is not ACTIVE when the sweep runs, so the runner retires the loser
        // without merging anything onto it. The trace still describes a duplicate-merge collapse,
        // and that is exactly the shape that used to authorize an undo.
        val store = InMemoryPropositionRepository()
        val trace = InMemoryCollectorTraceStore()
        val records = InMemoryCollectorRecordStore()
        val survivor = store.save(
            proposition(
                "survivor-fallback",
                listOf(revisionOne),
                text = "Acme signed the agreement",
                status = PropositionStatus.STALE,
            ),
        )
        val loser = store.save(
            proposition("loser-fallback", listOf(revisionOne, revisionTwo), text = "Acme signed an agreement"),
        )
        val runner = DefaultCollectorRunner(
            repository = store,
            strategies = listOf(
                // A strategy that marks the loser as a duplicate of the retired survivor and
                // records the collapse, the way MultiSignalCollectorStrategy would. It is written
                // out here because that strategy only ever sees ACTIVE candidates, and this
                // scenario needs a survivor that is not one.
                object : RunAwareCollectorStrategy {
                    override fun mark(
                        candidates: List<Proposition>,
                        repository: PropositionRepository,
                        ctx: CollectorRunContext,
                    ): List<PropositionMark> {
                        trace.recordRunContext(ctx.runId, ctx.contextId)
                        trace.recordDecision(
                            ctx.runId,
                            decisionWith(
                                componentId = "component-fallback",
                                survivorId = survivor.id,
                                retired = RetiredProposition(
                                    propositionId = loser.id,
                                    priorStatus = PropositionStatus.ACTIVE,
                                    foldedProvenanceEvidenceKeys = listOf(
                                        ProvenanceEvidenceKey.encode(revisionTwo),
                                    ),
                                ),
                            ),
                        )
                        return listOf(
                            PropositionMark(
                                propositionId = loser.id,
                                reason = MarkReason.Duplicate(survivorId = survivor.id),
                                strategyName = "test-duplicate",
                            ),
                        )
                    }

                    override fun mark(
                        candidates: List<Proposition>,
                        repository: PropositionRepository,
                        contextId: ContextId,
                    ): List<PropositionMark> = emptyList()
                },
            ),
            policy = MergingSweepPolicy(),
            recordStore = records,
            listener = DiceEventListener.DEV_NULL,
        )

        val runId = runner.run(contextId, dryRun = false).runId

        // The loser was retired, and nothing was folded onto the survivor.
        assertEquals(PropositionStatus.STALE, store.findById(loser.id)?.status)
        assertEquals(listOf(revisionOne), store.findById(survivor.id)?.provenanceEntries)
        assertEquals(
            listOf<String?>(null),
            records.findByProposition(loser.id).filter { it.runId == runId }.map { it.mergedIntoId },
        )

        val result = undoSingleCollapse(trace, store, survivor.id, loser.id, records)

        assertNull(result)
        assertEquals(listOf(revisionOne), store.findById(survivor.id)?.provenanceEntries)
    }

    /**
     * Runs the whole collector — mark, decide, sweep, record — over one duplicate pair, the way
     * production does. What lands in [records] is what `DefaultCollectorRunner` really writes.
     */
    private fun sweep(
        store: InMemoryPropositionRepository,
        trace: InMemoryCollectorTraceStore,
        records: InMemoryCollectorRecordStore,
        survivorId: String,
        policy: SweepPolicy = MergingSweepPolicy(),
        dryRun: Boolean = false,
    ): String = DefaultCollectorRunner(
        repository = store,
        strategies = listOf(strategyFor(survivorId, trace)),
        policy = policy,
        recordStore = records,
        listener = DiceEventListener.DEV_NULL,
    ).run(contextId, dryRun = dryRun).runId

    /** Runs the real strategy over one pair, then applies the fold the way a sweep does. */
    private fun collapse(
        store: InMemoryPropositionRepository,
        trace: InMemoryCollectorTraceStore,
        runId: String,
        survivor: Proposition,
        loser: Proposition,
    ) {
        strategyFor(survivor.id, trace).mark(listOf(survivor, loser), store, CollectorRunContext(runId, contextId))

        store.save(store.findById(survivor.id)!!.absorbEvidence(loser))
        store.save(store.findById(loser.id)!!.withStatus(PropositionStatus.STALE))
    }

    /** A deterministic one-pair strategy, so a trace under test comes from the real mark path. */
    private fun strategyFor(survivorId: String, trace: InMemoryCollectorTraceStore) =
        MultiSignalCollectorStrategy(
            pairSources = listOf(
                CandidatePairSource { candidates, _ ->
                    listOf(CandidatePair(anchor = candidates[0], member = candidates[1]))
                },
            ),
            scorers = listOf(CollectorSignalScorer { _, _ -> CollectorSignalScore(signal = "fixed", score = 1.0) }),
            componentsFinder = InMemoryConnectedComponentsFinder(),
            traceStore = trace,
            survivorPolicy = CollectorSurvivorPolicy { members -> members.single { it.id == survivorId } },
            matchThreshold = 0.5,
        )

    private fun foldedRevisionTwo(propositionId: String) = RetiredProposition(
        propositionId = propositionId,
        priorStatus = PropositionStatus.ACTIVE,
        foldedProvenanceEvidenceKeys = listOf(ProvenanceEvidenceKey.encode(revisionTwo)),
    )

    private fun decisionWith(
        componentId: String,
        survivorId: String,
        retired: RetiredProposition,
    ) = CollectorDecision(
        runId = "",
        componentId = componentId,
        survivorId = survivorId,
        action = "duplicate-merge",
        retired = listOf(retired),
    )

    private fun proposition(
        id: String,
        provenance: List<ProvenanceEntry>,
        text: String = "$id proposition",
        status: PropositionStatus = PropositionStatus.ACTIVE,
    ) = Proposition(
        id = id,
        contextId = contextId,
        text = text,
        mentions = emptyList(),
        confidence = 0.9,
        provenanceEntries = provenance,
        status = status,
    )

    /** Models a persistent backend whose ordinary save path never removes unloaded evidence. */
    private class AppendPreservingStore(
        private val delegate: InMemoryPropositionRepository = InMemoryPropositionRepository(),
    ) : PropositionRepository by delegate {

        override fun save(proposition: Proposition): Proposition {
            val existing = delegate.findById(proposition.id) ?: return delegate.save(proposition)
            return delegate.save(
                proposition.copy(
                    provenanceEntries = (existing.provenanceEntries + proposition.provenanceEntries).distinct(),
                ),
            )
        }

        override fun setProvenance(
            propositionId: String,
            entries: List<ProvenanceEntry>,
        ): Proposition? = delegate.findById(propositionId)?.let { existing ->
            delegate.save(existing.withProvenance(entries))
        }
    }

    /** Stops the undo where a crash would, at the `undoneAt` stamp. */
    private class FailingRecordStore(
        private val delegate: InMemoryCollectorRecordStore,
    ) : CollectorRecordStore by delegate {

        override fun record(record: CollectorRecord) {
            if (record.undoneAt != null) error("simulated crash before the undo stamp was persisted")
            delegate.record(record)
        }
    }

    /**
     * Another writer deleting a proposition inside the window the undo cannot see: after the undo
     * read the survivor, before its subtraction reaches the store. Landing it on the call itself is
     * the latest point the subtraction can still report the deletion, which is what the guard under
     * test reads. A deletion arriving after that answer is a different, still-open race.
     */
    private class DeletingOnSubtract(
        private val delegate: InMemoryPropositionRepository,
        private val deletes: String,
    ) : PropositionRepository by delegate {

        override fun subtractProvenance(propositionId: String, provenanceRefs: List<String>): Proposition? {
            delegate.delete(deletes)
            return delegate.subtractProvenance(propositionId, provenanceRefs)
        }
    }

    /** Stops the undo at one proposition's save, standing in for a crash before the restore. */
    private class FailingPropositionStore(
        private val delegate: InMemoryPropositionRepository,
        private val failOnSaveOf: String,
    ) : PropositionRepository by delegate {

        override fun save(proposition: Proposition): Proposition {
            if (proposition.id == failOnSaveOf) error("simulated crash before the member was restored")
            return delegate.save(proposition)
        }
    }

    /** Counts every write an undo attempts, so "nothing was written" is an assertion. */
    private class CountingStore(
        private val delegate: InMemoryPropositionRepository = InMemoryPropositionRepository(),
    ) : PropositionStore by delegate {

        var writes = 0
            private set

        /** Puts a proposition in place without counting it as a write. */
        fun seed(proposition: Proposition): Proposition = delegate.save(proposition)

        override fun save(proposition: Proposition): Proposition {
            writes++
            return delegate.save(proposition)
        }

        override fun setProvenance(propositionId: String, entries: List<ProvenanceEntry>): Proposition? {
            writes++
            return delegate.setProvenance(propositionId, entries)
        }

        override fun subtractProvenance(propositionId: String, provenanceRefs: List<String>): Proposition? {
            writes++
            return delegate.subtractProvenance(propositionId, provenanceRefs)
        }
    }
}

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
import com.embabel.dice.projection.lineage.CollectorOutcome
import com.embabel.dice.projection.lineage.CollectorRecord
import com.embabel.dice.projection.lineage.CollectorRecordStore
import com.embabel.dice.projection.lineage.CollectorRun
import com.embabel.dice.projection.memory.collector.CollectorRunContext
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.spi.PropositionMark
import com.embabel.dice.spi.SweepAction
import com.embabel.dice.spi.SweepPolicy
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * One marked proposition's fate, decided against the pre-run candidate snapshot: which of its
 * marks drove the decision, and what the [SweepPolicy] chose to do about it.
 */
private data class Decision(
    val propositionId: String,
    val marks: List<PropositionMark>,
    val action: SweepAction,
)

/**
 * Default [CollectorRunner] implementation.
 *
 * Fetches ACTIVE candidates once per run (so already-STALE or PROMOTED propositions are
 * never re-selected), gathers marks from every configured [CollectorStrategy], then asks the
 * [SweepPolicy] what to do with each marked proposition.
 *
 * Write behavior by entry point:
 * - [collect] never touches the repository or record store.
 * - [run] with `dryRun = true` saves an auditable run record but applies no status change and
 *   emits no event.
 * - [run] with `dryRun = false` applies each decision, saves the run record, then emits a
 *   [PropositionStatusChanged] per applied transition.
 *
 * [SweepAction.MergeInto] handling: every loser marked into the same survivor in one run is
 * folded on with a single read and save (not one round-trip per loser), and every transition
 * applied afterwards reads the freshest state of its proposition — never the pre-run snapshot —
 * so a survivor just merged into never gets its new evidence clobbered by a later transition
 * reading stale data. If the survivor can't be found, or isn't ACTIVE anymore (e.g. another mark
 * this run already retired it), every loser in that group falls back to a plain retirement
 * instead of folding evidence onto a proposition retrieval will never surface again.
 *
 * Concurrency: no shared mutable state — each [run] call works with its own locals, so runs for
 * different contexts are safe in parallel. Two runs for the *same* context at once aren't
 * corrupting, just wasteful (both read the same ACTIVE set; the second re-applies or skips
 * already-transitioned propositions). Serialize per context at the scheduling layer if that
 * matters (the [DefaultDreamLoopOrchestrator] that normally drives this already locks per context).
 *
 * @param repository Proposition store to read candidates from and write transitions to.
 * @param strategies Mark strategies run during the mark phase.
 * @param policy Policy deciding each marked proposition's fate.
 * @param recordStore Optional audit store; when null, no run record is saved.
 * @param listener Notified after each applied transition; defaults to a no-op.
 */
class DefaultCollectorRunner(
    private val repository: PropositionRepository,
    private val strategies: List<CollectorStrategy>,
    private val policy: SweepPolicy,
    private val recordStore: CollectorRecordStore?,
    private val listener: DiceEventListener,
) : CollectorRunner {

    private val logger = LoggerFactory.getLogger(DefaultCollectorRunner::class.java)

    override fun collect(contextId: ContextId): CollectorRunResult {
        val startedAt = Instant.now()
        val ctx = CollectorRunContext(runId = EPHEMERAL_RUN_ID, contextId = contextId, dryRun = true)
        val (_, marks) = markPhase(ctx)
        logger.debug("collect (read-only): {} mark(s) produced for context {}", marks.size, contextId)
        // Pure-read: nothing persisted, so runId is blank (nothing to cross-reference); flagged
        // dryRun because, like a dry run, it applied no transition.
        return CollectorRunResult(
            runId = EPHEMERAL_RUN_ID,
            dryRun = true,
            marks = marks,
            applied = emptyList(),
            skipped = emptyList(),
            hardDeleted = emptyList(),
            startedAt = startedAt,
        )
    }

    override fun run(contextId: ContextId, dryRun: Boolean): CollectorRunResult {
        val startedAt = Instant.now()
        val runId = newRunId()
        val ctx = CollectorRunContext(runId = runId, contextId = contextId, dryRun = dryRun)
        val (candidatesById, marks) = markPhase(ctx)
        logger.info(
            "Collector run {} started for context {} (dryRun={}, candidates={}, marks={})",
            runId, contextId, dryRun, candidatesById.size, marks.size,
        )
        val marksByProposition = marks.groupBy { it.propositionId }

        val applied = mutableListOf<PropositionMark>()
        val skipped = mutableListOf<PropositionMark>()
        val hardDeleted = mutableListOf<String>()
        val records = mutableListOf<CollectorRecord>()

        try {
        // Decide every marked proposition's fate up front, against the pre-run snapshot, in mark
        // order. A proposition that fell out of the snapshot (e.g. deleted since marking) is
        // silently skipped.
        val decisions = marksByProposition.mapNotNull { (propositionId, propMarks) ->
            val proposition = candidatesById[propositionId] ?: return@mapNotNull null
            Decision(propositionId, propMarks, policy.decide(proposition, propMarks))
        }

        if (dryRun) {
            // Preview only: record what WOULD happen (MARKED, or SKIPPED for an explicit Skip);
            // mutate, merge, delete, and emit nothing.
            for (decision in decisions) {
                val proposition = candidatesById.getValue(decision.propositionId)
                when (val action = decision.action) {
                    is SweepAction.TransitionStatus ->
                        records += records(decision.marks, runId, CollectorOutcome.MARKED, proposition.status, action.newStatus)
                    is SweepAction.MergeInto ->
                        records += records(decision.marks, runId, CollectorOutcome.MARKED, proposition.status, action.thenStatus)
                    SweepAction.HardDelete ->
                        records += records(decision.marks, runId, CollectorOutcome.MARKED, proposition.status, null)
                    SweepAction.Skip -> {
                        skipped.addAll(decision.marks)
                        records += records(decision.marks, runId, CollectorOutcome.SKIPPED, proposition.status, null)
                    }
                }
            }
        } else {
            // Freshest state for any proposition touched this run, seeded from the pre-run
            // snapshot. Later transitions must read from here, not `candidatesById` — else a
            // survivor merged into earlier this run gets its just-folded evidence overwritten by
            // the stale snapshot.
            val freshById = candidatesById.toMutableMap()

            // A proposition can be a survivor for one mark and a loser for another in the same
            // run (stacked strategies via the Builder can produce this). Applying merges by raw
            // survivor id would then be order-dependent: whichever group happened to process
            // first decides whether the in-between proposition's evidence reaches the final
            // survivor or gets dropped as "not ACTIVE". Resolve every merge target to where it
            // ultimately lands before grouping, so the result no longer depends on map iteration
            // order.
            val mergeTargetById: Map<String, String> = decisions
                .mapNotNull { d -> (d.action as? SweepAction.MergeInto)?.let { d.propositionId to it.survivorId } }
                .toMap()

            // Follows survivor pointers (A -> B means "A's survivor is B") to the end of the
            // chain. A cycle within one run (A -> B -> A) is a defect in the marks, not something
            // we can resolve acyclically — break it deterministically by picking the lowest id
            // among the CYCLE'S OWN members (not every id walked to reach it) as the winner, and
            // warn, so repeated runs over the same bad input always land the same way.
            //
            // A chain can feed into a cycle from outside it (X -> A -> Y -> B -> Y, cycle {Y, B},
            // with A just a tail on the way in). Every id on that walk — the tail nodes and the
            // cycle members alike — must resolve to the SAME terminal survivor, or the tail's
            // evidence lands on a node (A) that then gets folded away as a loser somewhere else,
            // silently dropping it. `resolved` memoizes every id we've already pinned down (both
            // previous calls and nodes seen partway through the current walk) so every decision in
            // this run shares one consistent view of where each id ultimately lands.
            val resolved = mutableMapOf<String, String>()

            fun terminalSurvivor(startId: String): String {
                resolved[startId]?.let { return it }
                val path = mutableListOf<String>()
                val indexOnPath = mutableMapOf<String, Int>()
                var current = startId
                while (true) {
                    resolved[current]?.let { cached ->
                        path.forEach { resolved[it] = cached }
                        return cached
                    }
                    val cycleStart = indexOnPath[current]
                    if (cycleStart != null) {
                        val cycle = path.subList(cycleStart, path.size)
                        val winner = cycle.min()
                        logger.warn(
                            "Collector run {}: MergeInto cycle detected among {}; resolving deterministically to {}",
                            runId, cycle, winner,
                        )
                        path.forEach { resolved[it] = winner }
                        return winner
                    }
                    val next = mergeTargetById[current]
                    if (next == null) {
                        path.forEach { resolved[it] = current }
                        resolved[current] = current
                        return current
                    }
                    indexOnPath[current] = path.size
                    path.add(current)
                    current = next
                }
            }

            // Fold every MergeInto's losers onto their terminal survivor first, grouped by that
            // survivor's id, so a survivor merged by several losers in one run (directly or via a
            // chain) gets a single read and save (also avoids re-embedding the survivor's text
            // once per loser on persistent backends).
            val mergesBySurvivor = decisions
                .mapNotNull { d -> (d.action as? SweepAction.MergeInto)?.let { d to it } }
                .mapNotNull { (d, action) ->
                    val terminal = terminalSurvivor(action.survivorId)
                    if (terminal == d.propositionId) {
                        // This decision is the broken link of a cycle: chain resolution kept this
                        // very proposition as the winning survivor, so its own "merge into
                        // someone else" can't be applied without merging it into itself. Drop it
                        // — the proposition just stays put as the survivor everyone else lands on.
                        logger.warn(
                            "Collector run {}: dropping MergeInto {} -> {}; cycle resolution kept {} as the survivor",
                            runId, d.propositionId, action.survivorId, terminal,
                        )
                        null
                    } else {
                        Triple(d, action, terminal)
                    }
                }
                .groupBy({ (_, _, terminal) -> terminal }) { (d, action, _) -> d to action }

            for ((survivorId, group) in mergesBySurvivor) {
                val survivor = freshById[survivorId] ?: repository.findById(survivorId)?.also { freshById[survivorId] = it }
                if (survivor == null || survivor.status != PropositionStatus.ACTIVE) {
                    // Nothing safe to merge onto: the survivor vanished, or it's no longer ACTIVE
                    // (e.g. another mark this run already retired it). Folding evidence onto a
                    // non-ACTIVE proposition would bury it where retrieval never looks, so every
                    // loser in the group falls back to a plain retirement instead.
                    val reason = if (survivor == null) "not found" else "not ACTIVE (status=${survivor.status})"
                    for ((decision, action) in group) {
                        logger.warn(
                            "MergeInto survivor {} {}; retiring {} without merging its evidence",
                            survivorId, reason, decision.propositionId,
                        )
                        val loser = freshById.getValue(decision.propositionId)
                        val saved = retire(loser, action.thenStatus, decision.marks, runId, records, applied)
                        freshById[decision.propositionId] = saved
                    }
                    continue
                }
                var merged: Proposition = survivor
                for ((decision, _) in group) {
                    merged = merged.absorbEvidence(freshById.getValue(decision.propositionId))
                }
                val savedSurvivor = repository.save(merged)
                freshById[survivorId] = savedSurvivor
                for ((decision, action) in group) {
                    val loser = freshById.getValue(decision.propositionId)
                    val saved = retire(loser, action.thenStatus, decision.marks, runId, records, applied)
                    freshById[decision.propositionId] = saved
                }
            }

            // Everything else, in mark order, reading the freshest state — a survivor merged into
            // above must transition from its merged state, not the pre-run snapshot.
            for (decision in decisions) {
                when (val action = decision.action) {
                    is SweepAction.MergeInto -> continue // handled above
                    is SweepAction.TransitionStatus -> {
                        val proposition = freshById.getValue(decision.propositionId)
                        retire(proposition, action.newStatus, decision.marks, runId, records, applied)
                    }
                    SweepAction.HardDelete -> {
                        val proposition = freshById.getValue(decision.propositionId)
                        repository.delete(proposition.id)
                        hardDeleted += proposition.id
                        records += records(decision.marks, runId, CollectorOutcome.HARD_DELETED, proposition.status, null)
                    }
                    SweepAction.Skip -> {
                        val proposition = freshById.getValue(decision.propositionId)
                        skipped.addAll(decision.marks)
                        records += records(decision.marks, runId, CollectorOutcome.SKIPPED, proposition.status, null)
                    }
                }
            }
        }
        } catch (e: Throwable) {
            // A mutation failed partway through. Earlier iterations already saved/deleted and
            // emitted, and their records are buffered in `records` — persist that partial trail
            // before rethrowing so a HARD_DELETED proposition is never lost without an audit
            // record. The failing proposition itself added no record (the throw preempted it).
            logger.warn("Collector run {} aborted mid-run; persisting the {} record(s) gathered so far", runId, records.size, e)
            persistRun(runId, startedAt, Instant.now(), dryRun, records)
            throw e
        }

        // Shared finish instant for both the persisted run header and the returned result, so
        // they agree on the finish time.
        val finishedAt = Instant.now()
        persistRun(runId, startedAt, finishedAt, dryRun, records)

        val result = CollectorRunResult(
            runId = runId,
            dryRun = dryRun,
            marks = marks,
            applied = applied.toList(),
            skipped = skipped.toList(),
            hardDeleted = hardDeleted.toList(),
            startedAt = startedAt,
            finishedAt = finishedAt,
        )
        logger.info(
            "Collector run {} complete: applied={} skipped={} hardDeleted={} (dryRun={})",
            runId, result.applied.size, result.skipped.size, result.hardDeleted.size, dryRun,
        )
        return result
    }

    /**
     * Fetches ACTIVE candidates once and runs every strategy over them. A [RunAwareCollectorStrategy]
     * gets the full [ctx] (so it can tag whatever it writes with this run's id); any other
     * [CollectorStrategy] still gets the bare context id it has always taken.
     * @return the candidates indexed by id, paired with all marks the strategies produced.
     */
    private fun markPhase(ctx: CollectorRunContext): Pair<Map<String, Proposition>, List<PropositionMark>> {
        val candidates = repository.query(
            PropositionQuery.forContextId(ctx.contextId).withStatus(PropositionStatus.ACTIVE),
        )
        val candidatesById = candidates.associateBy { it.id }
        val marks = strategies.flatMap { strategy ->
            if (strategy is RunAwareCollectorStrategy) strategy.mark(candidates, repository, ctx)
            else strategy.mark(candidates, repository, ctx.contextId)
        }
        return candidatesById to marks
    }

    /**
     * Emit the lifecycle event for a transition that has already been persisted and recorded.
     * Kept as the final step so the durable write and its audit record are both in place before any
     * (inline, possibly throwing) listener runs.
     */
    private fun emitStatusChanged(
        saved: Proposition,
        previousStatus: PropositionStatus,
        newStatus: PropositionStatus,
        propMarks: List<PropositionMark>,
    ) {
        // Multiple strategies may mark the same proposition; combine their distinct reason keys
        // (sorted for run-to-run determinism) so the emitted event is order-independent and never
        // silently drops a reason. `reason` stays a nullable String for backward compatibility.
        val reason = propMarks
            .map { it.reason.key }
            .distinct()
            .sorted()
            .joinToString(",")
            .ifEmpty { null }
        listener.onEvent(
            PropositionStatusChanged(
                proposition = saved,
                previousStatus = previousStatus,
                newStatus = newStatus,
                reason = reason,
            ),
        )
    }

    /**
     * Retire one proposition to [newStatus]: persist, buffer its audit record as TRANSITIONED,
     * add its marks to `applied`, then emit the status-changed event. Both a plain sweep and a
     * merge's loser retirement end this way, so the persist-then-record-then-emit ordering
     * invariant (a transition is never persisted without its record, even if the listener throws)
     * lives in exactly one place.
     *
     * @return the saved proposition, so a caller tracking per-run freshest state can cache it.
     */
    private fun retire(
        proposition: Proposition,
        newStatus: PropositionStatus,
        propMarks: List<PropositionMark>,
        runId: String,
        records: MutableList<CollectorRecord>,
        applied: MutableList<PropositionMark>,
    ): Proposition {
        val previousStatus = proposition.status
        val saved = repository.save(proposition.withStatus(newStatus))
        records += records(propMarks, runId, CollectorOutcome.TRANSITIONED, previousStatus, newStatus)
        applied.addAll(propMarks)
        emitStatusChanged(saved, previousStatus, newStatus, propMarks)
        return saved
    }

    private fun records(
        propMarks: List<PropositionMark>,
        runId: String,
        outcome: CollectorOutcome,
        previousStatus: PropositionStatus?,
        newStatus: PropositionStatus?,
    ): List<CollectorRecord> = propMarks.map { mark ->
        CollectorRecord(
            propositionId = mark.propositionId,
            reason = mark.reason,
            outcome = outcome,
            strategyName = mark.strategyName,
            runId = runId,
            previousStatus = previousStatus,
            newStatus = newStatus,
        )
    }

    private fun persistRun(
        runId: String,
        startedAt: Instant,
        finishedAt: Instant,
        dryRun: Boolean,
        records: List<CollectorRecord>,
    ) {
        val store = recordStore ?: return
        // The finished run header groups the per-proposition trail under a shared runId; the
        // record store owns the durable trail, so records carry that runId forward. The header
        // is persisted unconditionally — even a zero-mark run must leave a retrievable trace.
        val run = CollectorRun(runId = runId, startedAt = startedAt, finishedAt = finishedAt, dryRun = dryRun)
        store.recordRun(run)
        records.forEach(store::record)
        logger.debug(
            "Collector run {} finished (dryRun={}, records={})",
            run.runId,
            run.dryRun,
            records.size,
        )
    }

    private fun newRunId(): String = UUID.randomUUID().toString()

    private companion object {
        /** Sentinel runId for the pure-read [collect] path: not persisted, not queryable. */
        const val EPHEMERAL_RUN_ID = ""
    }
}

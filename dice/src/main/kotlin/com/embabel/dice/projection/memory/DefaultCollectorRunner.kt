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
 * Concurrency: holds no shared mutable state — every [run] call works with its own locals — so
 * runs for different contexts are safe in parallel. Two runs for the *same* context at once are
 * not corrupting but are wasteful: both read the same ACTIVE set and the second simply re-applies
 * or skips already-transitioned propositions. Serialize per context at the scheduling layer if that
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
        val (_, marks) = markPhase(contextId)
        logger.debug("collect (read-only): {} mark(s) produced for context {}", marks.size, contextId)
        // Pure-read: no repository write, no run record. Nothing is persisted, so there is no run
        // to cross-reference — the runId is blank to signal it is not queryable in any store. It is
        // flagged dryRun because, like a dry run, it applied no transition.
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
        val (candidatesById, marks) = markPhase(contextId)
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
        for ((propositionId, propMarks) in marksByProposition) {
            val proposition = candidatesById[propositionId] ?: continue
            when (val action = policy.decide(proposition, propMarks)) {
                is SweepAction.TransitionStatus -> {
                    if (dryRun) {
                        // Preview only: record the would-be transition as MARKED (nothing was swept),
                        // mutate nothing, emit nothing, and leave `applied` empty. The record's
                        // newStatus still carries what WOULD happen.
                        records += records(propMarks, runId, CollectorOutcome.MARKED, proposition.status, action.newStatus)
                    } else {
                        // Order matters: persist, then buffer the audit record, then emit. The
                        // record lands between the durable write and the (inline, possibly throwing)
                        // listener call, so a transition can never be persisted without its record —
                        // even if a listener blows up before the run finishes.
                        val previousStatus = proposition.status
                        val saved = repository.save(proposition.withStatus(action.newStatus))
                        records += records(propMarks, runId, CollectorOutcome.TRANSITIONED, previousStatus, action.newStatus)
                        applied.addAll(propMarks)
                        emitStatusChanged(saved, previousStatus, action.newStatus, propMarks)
                    }
                }

                SweepAction.HardDelete -> {
                    if (dryRun) {
                        // Preview only: record what WOULD be removed as MARKED; delete nothing and
                        // leave `hardDeleted` empty.
                        records += records(propMarks, runId, CollectorOutcome.MARKED, proposition.status, null)
                    } else {
                        repository.delete(proposition.id)
                        hardDeleted += proposition.id
                        records += records(propMarks, runId, CollectorOutcome.HARD_DELETED, proposition.status, null)
                    }
                }

                SweepAction.Skip -> {
                    skipped.addAll(propMarks)
                    records += records(propMarks, runId, CollectorOutcome.SKIPPED, proposition.status, null)
                }
            }
        }
        } catch (e: Throwable) {
            // A mutation failed partway through. Earlier iterations have already saved/deleted and
            // emitted their events, and their records are buffered in `records` — persist that
            // partial trail before rethrowing so a HARD_DELETED proposition is never lost without an
            // audit record. The failing proposition added no record (the throw preempts it).
            logger.warn("Collector run {} aborted mid-run; persisting the {} record(s) gathered so far", runId, records.size, e)
            persistRun(runId, startedAt, Instant.now(), dryRun, records)
            throw e
        }

        // Compute the finish instant once and thread it into both the persisted run header and
        // the returned result, so the audit object and the summary agree on the finish time.
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
     * Fetches ACTIVE candidates once and runs every strategy over them.
     * @return the candidates indexed by id, paired with all marks the strategies produced.
     */
    private fun markPhase(contextId: ContextId): Pair<Map<String, Proposition>, List<PropositionMark>> {
        val candidates = repository.query(
            PropositionQuery.forContextId(contextId).withStatus(PropositionStatus.ACTIVE),
        )
        val candidatesById = candidates.associateBy { it.id }
        val marks = strategies.flatMap { it.mark(candidates, repository, contextId) }
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

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
import com.embabel.dice.operations.consolidation.ConsolidationPass
import com.embabel.dice.operations.consolidation.ConsolidationPassResult
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [DreamLoopOrchestrator]: runs a list of [ConsolidationPass]es as a single cycle.
 *
 * Each cycle fetches the ACTIVE snapshot once, runs every pass over it in registration order,
 * then aggregates all the saves into a single [PropositionRepository.saveAll]. Hard deletes are
 * off by default — they only happen when [allowHardDelete] is explicitly enabled.
 *
 * The [consolidate] trigger is best-effort: it tracks the ACTIVE count after each cycle and
 * fires when the delta since the last cycle reaches [changeVolumeThreshold]. Use [consolidateNow]
 * to bypass the threshold and run unconditionally.
 *
 * @param repository Storage backend; the orchestrator is the sole writer per cycle.
 * @param passes Passes executed in registration order.
 * @param allowHardDelete When false (default), delete-intents from passes are silently ignored.
 *   When true, each aggregated delete id is removed from the repository.
 * @param changeVolumeThreshold Minimum active-count delta since the last cycle for [consolidate] to fire.
 */
data class DefaultDreamLoopOrchestrator(
    private val repository: PropositionRepository,
    private val passes: List<ConsolidationPass> = emptyList(),
    private val allowHardDelete: Boolean = false,
    private val changeVolumeThreshold: Int = 10,
) : DreamLoopOrchestrator {

    private val logger = LoggerFactory.getLogger(DefaultDreamLoopOrchestrator::class.java)

    /**
     * Tracks the last-seen ACTIVE count per context, used by the change-volume trigger.
     *
     * Kept outside the primary constructor on purpose: it's mutable runtime state, not part of
     * this orchestrator's value identity. That isolation comes from [copy] re-running this field
     * initializer (it copies constructor properties only), so a copy starts with its own clean
     * baseline rather than sharing this map; two orchestrators whose counters have diverged still
     * compare as equal. Thread-safe via [ConcurrentHashMap].
     */
    private val lastActiveCount: ConcurrentHashMap<ContextId, Int> = ConcurrentHashMap()

    /**
     * One lock per context, so a full cycle for a given context runs start-to-finish without
     * another cycle for the same context interleaving. Without this, two triggers for the same
     * context could both pass the change-volume gate on the same stale count and run overlapping
     * cycles. Different contexts lock independently and still run concurrently. Like
     * [lastActiveCount], this is per-instance runtime state — serialization holds within a single
     * shared orchestrator instance, which is the intended deployment.
     */
    private val cycleLocks: ConcurrentHashMap<ContextId, Any> = ConcurrentHashMap()

    private fun lockFor(contextId: ContextId): Any = cycleLocks.computeIfAbsent(contextId) { Any() }

    override fun withPass(pass: ConsolidationPass): DefaultDreamLoopOrchestrator =
        copy(passes = passes + pass)

    /** Enable or disable irreversible hard deletes (default disabled — soft STALE only). */
    fun withAllowHardDelete(allow: Boolean): DefaultDreamLoopOrchestrator =
        copy(allowHardDelete = allow)

    /** Set the active-count delta required for the threshold-gated [consolidate] to run. */
    fun withChangeVolumeThreshold(threshold: Int): DefaultDreamLoopOrchestrator =
        copy(changeVolumeThreshold = threshold)

    override fun consolidate(contextId: ContextId): DreamLoopReport? = synchronized(lockFor(contextId)) {
        // Hold the per-context lock across the gate check and the cycle so the threshold decision
        // and the count update can't race a concurrent trigger for the same context.
        val currentActive = activeSnapshot(contextId).size
        val delta = currentActive - (lastActiveCount[contextId] ?: 0)
        if (delta < changeVolumeThreshold) {
            // Not enough has accumulated *since the last cycle* to be worth one. Crucially we leave
            // the baseline untouched here: advancing it on a skip would measure the delta since the
            // last call instead, so steady incremental growth (the normal scheduled case) would
            // never accumulate to the threshold and a cycle would never fire. The baseline only
            // moves after a real cycle (step 5 below).
            logger.debug(
                "Skipping consolidation for {}: active-count delta {} < threshold {}",
                contextId, delta, changeVolumeThreshold,
            )
            return null
        }
        // Reentrant: consolidateNow takes the same per-context lock.
        return consolidateNow(contextId)
    }

    override fun consolidateNow(contextId: ContextId): DreamLoopReport = synchronized(lockFor(contextId)) {
        val cycleStarted = Instant.now()

        // (1) Fetch the ACTIVE snapshot once.
        val snapshot = activeSnapshot(contextId)
        logger.debug("Running {} pass(es) over {} active proposition(s) for {}", passes.size, snapshot.size, contextId)

        // (2) Run each pass in registration order, isolating failures into Failed results.
        val passResults = passes.map { pass -> runPass(pass, contextId, snapshot) }

        // (3) Aggregate saves into a single write, reconciling any proposition that more than one
        // pass wants to re-save this cycle. Without this, the same id returned by two passes (e.g.
        // SUPERSEDED from abstraction and CONTRADICTED from contradiction-resolution) would be
        // written twice and the persisted status would depend on saveAll ordering. We collapse to
        // one write per id with a deterministic winner — see [reconcileSaves].
        val snapshotIds = snapshot.mapTo(HashSet()) { it.id }
        val changed = passResults.filterIsInstance<ConsolidationPassResult.Changed>()
        val toSave: List<Proposition> = reconcileSaves(changed.flatMap { it.propositionsToSave })
        if (toSave.isNotEmpty()) {
            repository.saveAll(toSave)
        }

        // (4) Apply deletes only when explicitly opted in.
        if (allowHardDelete) {
            changed.flatMap { it.propositionsToDelete }.forEach { repository.delete(it) }
        }

        // (5) Record the post-cycle ACTIVE count for the change-volume trigger.
        lastActiveCount[contextId] = activeSnapshot(contextId).size

        // (6) Build the report.
        // A "new" proposition is one whose id was not in the examined snapshot (e.g. a fresh
        // abstraction); a re-saved snapshot member (e.g. a source marked SUPERSEDED, or a
        // CONTRADICTED loser) is a transition, not a new proposition. Decay transitions are written
        // by their own pass, so they are counted via externallyApplied rather than the save list.
        val newPropositions = toSave.count { it.id !in snapshotIds }
        val savedTransitions = toSave.count { it.id in snapshotIds }
        // Deletes only count as transitions when they are actually applied. With allowHardDelete
        // off (the default) delete-intents are ignored, so counting them would over-report work
        // that never happened.
        val appliedDeletes = if (allowHardDelete) changed.sumOf { it.propositionsToDelete.size } else 0
        val transitioned = savedTransitions + appliedDeletes +
            changed.sumOf { it.externallyApplied }
        val report = DreamLoopReport(
            contextId = contextId,
            cycleStarted = cycleStarted,
            passResults = passResults,
            totalExamined = snapshot.size,
            totalTransitioned = transitioned,
            totalNewPropositions = newPropositions,
            triggered = true,
        )
        logger.info(
            "Dream-loop cycle complete for {}: examined={} transitioned={} newPropositions={}",
            contextId, report.totalExamined, report.totalTransitioned, report.totalNewPropositions,
        )
        return report
    }

    /**
     * Satisfies the [MemoryMaintenanceOrchestrator] contract by running a full cycle, but the
     * result is always empty in the legacy-shaped [MaintenanceResult] — the real output is a
     * [DreamLoopReport] from [consolidateNow]. For the full consolidation/abstraction/retire
     * pipeline, use [DefaultMemoryMaintenanceOrchestrator] instead.
     */
    override fun maintain(
        contextId: ContextId,
        sessionPropositions: List<Proposition>,
    ): MaintenanceResult {
        consolidateNow(contextId)
        return MaintenanceResult(
            consolidation = null,
            abstractions = emptyList(),
            superseded = emptyList(),
            retired = emptyList(),
        )
    }

    private fun runPass(
        pass: ConsolidationPass,
        contextId: ContextId,
        snapshot: List<Proposition>,
    ): ConsolidationPassResult =
        try {
            pass.run(contextId, snapshot)
        } catch (e: Throwable) {
            // Catch breadth matches the passes themselves (all catch Throwable): a pass that lets a
            // non-Exception Throwable escape is still isolated into a Failed result here, honoring the
            // "a failed pass does not abort the cycle" guarantee for every error class.
            logger.warn("Pass '{}' failed; recording failure and continuing cycle", pass.name, e)
            ConsolidationPassResult.Failed(pass.name, e)
        }

    /**
     * Collapse the cycle's combined save list to one write per proposition id.
     *
     * When two passes each hand back a copy of the same proposition with a different target status,
     * the order they happen to appear in the flat-mapped list must not decide what gets persisted.
     * We pick a deterministic winner by status strength: a contradiction (the belief is now wrong)
     * outranks a supersession (still true, just rolled up into an abstraction), which outranks a
     * decay-to-STALE, and any retirement outranks leaving it ACTIVE. Freshly created propositions
     * (new ids, e.g. abstractions) never collide, so they pass through untouched.
     */
    private fun reconcileSaves(toSave: List<Proposition>): List<Proposition> =
        toSave
            .groupBy { it.id }
            .map { (_, copies) -> copies.maxByOrNull { statusStrength(it.status) }!! }

    private fun statusStrength(status: PropositionStatus): Int = when (status) {
        PropositionStatus.CONTRADICTED -> 4
        PropositionStatus.SUPERSEDED -> 3
        PropositionStatus.STALE -> 2
        PropositionStatus.PROMOTED -> 1
        PropositionStatus.ACTIVE -> 0
    }

    private fun activeSnapshot(contextId: ContextId): List<Proposition> =
        repository.query(
            PropositionQuery.forContextId(contextId).withStatus(PropositionStatus.ACTIVE)
        )

    companion object {
        /** Start building a dream-loop orchestrator with the given repository. */
        @JvmStatic
        fun withRepository(repository: PropositionRepository): DefaultDreamLoopOrchestrator =
            DefaultDreamLoopOrchestrator(repository = repository)
    }
}

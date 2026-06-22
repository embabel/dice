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
     * this orchestrator's value identity. That way [copy]-built instances each start with their
     * own clean baseline (no shared state), and two orchestrators whose counters have diverged
     * still compare as equal. Thread-safe via [ConcurrentHashMap].
     */
    private val lastActiveCount: ConcurrentHashMap<ContextId, Int> = ConcurrentHashMap()

    override fun withPass(pass: ConsolidationPass): DefaultDreamLoopOrchestrator =
        copy(passes = passes + pass)

    /** Enable or disable irreversible hard deletes (default disabled — soft STALE only). */
    fun withAllowHardDelete(allow: Boolean): DefaultDreamLoopOrchestrator =
        copy(allowHardDelete = allow)

    /** Set the active-count delta required for the threshold-gated [consolidate] to run. */
    fun withChangeVolumeThreshold(threshold: Int): DefaultDreamLoopOrchestrator =
        copy(changeVolumeThreshold = threshold)

    override fun consolidate(contextId: ContextId): DreamLoopReport? {
        val currentActive = activeSnapshot(contextId).size
        val delta = currentActive - (lastActiveCount[contextId] ?: 0)
        if (delta < changeVolumeThreshold) {
            // Not enough has changed to be worth a cycle; record the count and skip.
            lastActiveCount[contextId] = currentActive
            logger.debug(
                "Skipping consolidation for {}: active-count delta {} < threshold {}",
                contextId, delta, changeVolumeThreshold,
            )
            return null
        }
        return consolidateNow(contextId)
    }

    override fun consolidateNow(contextId: ContextId): DreamLoopReport {
        val cycleStarted = Instant.now()

        // (1) Fetch the ACTIVE snapshot once.
        val snapshot = activeSnapshot(contextId)
        logger.debug("Running {} pass(es) over {} active proposition(s) for {}", passes.size, snapshot.size, contextId)

        // (2) Run each pass in registration order, isolating failures into Failed results.
        val passResults = passes.map { pass -> runPass(pass, contextId, snapshot) }

        // (3) Aggregate saves into a single write.
        val changed = passResults.filterIsInstance<ConsolidationPassResult.Changed>()
        val toSave: List<Proposition> = changed.flatMap { it.propositionsToSave }
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
        val transitioned = changed.sumOf { it.propositionsToSave.size + it.propositionsToDelete.size }
        val newPropositions = toSave.size
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

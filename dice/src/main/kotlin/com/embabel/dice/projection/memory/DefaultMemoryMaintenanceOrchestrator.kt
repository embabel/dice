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
import com.embabel.dice.operations.abstraction.PropositionAbstractor
import com.embabel.dice.operations.consolidation.AbstractionPass
import com.embabel.dice.operations.consolidation.ConsolidationPassResult
import com.embabel.dice.operations.consolidation.SessionConsolidationPass
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("com.embabel.dice.projection.memory.DefaultMemoryMaintenanceOrchestrator")

/**
 * Default [MemoryMaintenanceOrchestrator]: runs consolidation, abstraction, retirement, and
 * optionally the mark-and-sweep collector as a four-phase pipeline.
 *
 * The consolidation and abstraction logic delegates to [SessionConsolidationPass] and
 * [AbstractionPass] respectively — they own the details of what changes. This orchestrator
 * just drives the sequence and persists results in the legacy per-step call shape so
 * [maintain]'s observable contract stays intact.
 *
 * A few intentional divergences from the dream-loop path worth knowing:
 * - Every qualifying entity group is re-abstracted on every [maintain] call with no level
 *   ceiling. The dream-loop's skip-covered idempotency guard has nothing to match here because
 *   [maintain] only ever sees the level-0 ACTIVE snapshot.
 * - The decay/retirement step **hard-deletes** propositions below [retireBelow], unlike the
 *   dream-loop which soft-transitions them to STALE by default. This preserves the original
 *   behavior for backward compatibility.
 *
 * @param repository Storage backend for propositions.
 * @param consolidator Decides how session propositions are folded into long-term memory.
 * @param abstractor Optional; synthesizes higher-level insights from entity groups.
 * @param abstractionThreshold Minimum propositions per entity group before abstraction runs.
 * @param abstractionTargetCount How many abstractions to generate per group.
 * @param retireBelow Hard-delete propositions whose effective confidence falls below this; null disables retirement.
 * @param retireDecayK Decay-rate multiplier used when computing effective confidence for retirement.
 * @param collector Optional mark-and-sweep collector run as the final phase; null disables it.
 */
data class DefaultMemoryMaintenanceOrchestrator(
    private val repository: PropositionRepository,
    private val consolidator: MemoryConsolidator,
    private val abstractor: PropositionAbstractor? = null,
    private val abstractionThreshold: Int = 5,
    private val abstractionTargetCount: Int = 3,
    private val retireBelow: Double? = null,
    private val retireDecayK: Double = 2.0,
    private val collector: CollectorRunner? = null,
) : MemoryMaintenanceOrchestrator {

    override fun maintain(
        contextId: ContextId,
        sessionPropositions: List<Proposition>,
    ): MaintenanceResult {
        // Phase 1: Consolidate session propositions (single source of truth: SessionConsolidationPass)
        val consolidation = consolidate(contextId, sessionPropositions)

        // Phase 2: Abstract entity groups (single source of truth: AbstractionPass)
        val (abstractions, superseded) = abstract(contextId)

        // Phase 3: Retire expired propositions (legacy hard delete; see class KDoc)
        val retired = retire(contextId)

        // Phase 4 (optional): run the mark-and-sweep collector when one is configured.
        // Default off; when null the behavior is identical to the three-phase pipeline.
        val collectorResult = collector?.run(contextId)

        logger.info(
            "Maintenance complete for {}: promoted={} reinforced={} merged={} abstractions={} superseded={} retired={} collectorApplied={}",
            contextId,
            consolidation?.promoted?.size ?: 0,
            consolidation?.reinforced?.size ?: 0,
            consolidation?.merged?.size ?: 0,
            abstractions.size,
            superseded.size,
            retired.size,
            collectorResult?.applied?.size ?: 0,
        )
        return MaintenanceResult(
            consolidation = consolidation,
            abstractions = abstractions,
            superseded = superseded,
            retired = retired,
            collectorResult = collectorResult,
        )
    }

    private fun consolidate(
        contextId: ContextId,
        sessionPropositions: List<Proposition>,
    ): ConsolidationResult? {
        if (sessionPropositions.isEmpty()) return null

        val existing = repository.query(
            PropositionQuery.Companion.forContextId(contextId)
                .withStatus(PropositionStatus.ACTIVE)
        )

        // SessionConsolidationPass is the single source of truth for *how* a session is folded into
        // long-term memory: it delegates verbatim to the injected consolidator (the identical
        // delegate the dream loop uses), adding no promote/reinforce/merge logic of its own. Because
        // that delegation is exactly one consolidate() call, maintain() makes that call once here to
        // recover the typed ConsolidationResult it must both surface in MaintenanceResult and persist
        // in the legacy per-list call shape, rather than invoking the (potentially LLM-backed)
        // consolidator a second time through the pass. The shared delegate keeps the two paths in
        // lockstep; see [SessionConsolidationPass].
        val result = consolidator.consolidate(sessionPropositions, existing)

        // Persist promoted, reinforced, and merged propositions in the legacy per-list call shape.
        repository.saveAll(result.promoted)
        repository.saveAll(result.reinforced)
        repository.saveAll(result.merged.map { it.result })

        return result
    }

    private fun abstract(contextId: ContextId): Pair<List<Proposition>, List<Proposition>> {
        val abstractor = this.abstractor ?: return emptyList<Proposition>() to emptyList()

        // The level-0-only snapshot is deliberate and load-bearing for the legacy contract: because
        // no higher-level propositions are ever in scope here, the AbstractionPass idempotency guard
        // (skip a group already covered by an existing higher-level proposition) has nothing to match
        // and is therefore an explicit no-op on this path. maintain() re-abstracts every qualifying
        // group on every call, as it did pre-refactor — it does NOT carry the dream-loop's
        // skip-covered semantics. Do not widen this query to include higher levels without revisiting
        // that contract.
        val active = repository.query(
            PropositionQuery.Companion.forContextId(contextId)
                .withStatus(PropositionStatus.ACTIVE)
                .withMaxLevel(0)
        )

        // Recover the per-entity grouping so abstraction runs per group, in the legacy granularity.
        // Each group is abstracted in isolation through its OWN AbstractionPass invocation: a group's
        // abstractions are exactly those returned by that group's abstract() call (no cross-group
        // sourceId matching, so a source mentioning two entities is never saved or counted twice),
        // persisted per group in the legacy call shape (one saveAll for the group's abstractions, one
        // for its superseded sources).
        val entityGroups = active
            .flatMap { prop -> prop.mentions.mapNotNull { it.resolvedId }.map { entityId -> entityId to prop } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, props) -> props.distinct() }
            .filter { (_, props) -> props.size >= abstractionThreshold }

        val allAbstractions = mutableListOf<Proposition>()
        val allSuperseded = mutableListOf<Proposition>()

        // maxLevel = Int.MAX_VALUE preserves the pre-refactor "persist every abstraction the
        // abstractor returns" contract — maintain() applies no level ceiling. Any cap belongs only on
        // the dream-loop path, not here.
        val pass = AbstractionPass(
            abstractor = abstractor,
            abstractionThreshold = abstractionThreshold,
            abstractionTargetCount = abstractionTargetCount,
            maxLevel = Int.MAX_VALUE,
        )

        for ((_, propositions) in entityGroups) {
            val outcome = pass.run(contextId, propositions)
            if (outcome is ConsolidationPassResult.Failed) {
                throw outcome.cause
            }
            if (outcome !is ConsolidationPassResult.Changed) {
                continue
            }

            val groupAbstractions = outcome.propositionsToSave.filter { it.level > 0 }
            val groupSuperseded = outcome.propositionsToSave.filter {
                it.status == PropositionStatus.SUPERSEDED
            }

            repository.saveAll(groupAbstractions)
            allAbstractions.addAll(groupAbstractions)

            repository.saveAll(groupSuperseded)
            allSuperseded.addAll(groupSuperseded)
        }

        return allAbstractions to allSuperseded
    }

    private fun retire(contextId: ContextId): List<Proposition> {
        val threshold = retireBelow ?: return emptyList()

        val active = repository.query(
            PropositionQuery.Companion.forContextId(contextId)
                .withStatus(PropositionStatus.ACTIVE)
        )

        val toRetire = active.filter { it.effectiveConfidence(retireDecayK) < threshold }

        for (prop in toRetire) {
            repository.delete(prop.id)
        }

        if (toRetire.isNotEmpty()) {
            logger.debug("Retired (hard-deleted) {} proposition(s) below confidence {} for {}", toRetire.size, threshold, contextId)
        }
        return toRetire
    }

    fun withAbstractor(abstractor: PropositionAbstractor): DefaultMemoryMaintenanceOrchestrator =
        copy(abstractor = abstractor)

    fun withAbstractionThreshold(threshold: Int): DefaultMemoryMaintenanceOrchestrator =
        copy(abstractionThreshold = threshold)

    fun withAbstractionTargetCount(targetCount: Int): DefaultMemoryMaintenanceOrchestrator =
        copy(abstractionTargetCount = targetCount)

    fun withRetireBelow(threshold: Double): DefaultMemoryMaintenanceOrchestrator =
        copy(retireBelow = threshold)

    fun withRetireDecayK(k: Double): DefaultMemoryMaintenanceOrchestrator =
        copy(retireDecayK = k)

    fun withCollector(collector: CollectorRunner): DefaultMemoryMaintenanceOrchestrator =
        copy(collector = collector)
}

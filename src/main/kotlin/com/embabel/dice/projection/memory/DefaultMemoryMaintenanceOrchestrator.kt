package com.embabel.dice.projection.memory

import com.embabel.agent.core.ContextId
import com.embabel.dice.operations.PropositionGroup
import com.embabel.dice.operations.abstraction.PropositionAbstractor
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus

/**
 * Default implementation of [MemoryMaintenanceOrchestrator].
 *
 * @param repository Storage backend for propositions
 * @param consolidator Consolidation strategy for session → long-term promotion
 * @param abstractor Optional abstractor for synthesizing higher-level insights
 * @param abstractionThreshold Minimum propositions per entity group to trigger abstraction
 * @param abstractionTargetCount How many abstractions to generate per group
 * @param retireBelow Effective confidence threshold for retirement; null disables retirement
 * @param retireDecayK Decay rate multiplier for retirement calculation
 */
data class DefaultMemoryMaintenanceOrchestrator(
    private val repository: PropositionRepository,
    private val consolidator: MemoryConsolidator,
    private val abstractor: PropositionAbstractor? = null,
    private val abstractionThreshold: Int = 5,
    private val abstractionTargetCount: Int = 3,
    private val retireBelow: Double? = null,
    private val retireDecayK: Double = 2.0,
) : MemoryMaintenanceOrchestrator {

    override fun maintain(
        contextId: ContextId,
        sessionPropositions: List<Proposition>,
    ): MaintenanceResult {
        // Phase 1: Consolidate session propositions
        val consolidation = consolidate(contextId, sessionPropositions)

        // Phase 2: Abstract entity groups
        val (abstractions, superseded) = abstract(contextId)

        // Phase 3: Retire expired propositions
        val retired = retire(contextId)

        return MaintenanceResult(
            consolidation = consolidation,
            abstractions = abstractions,
            superseded = superseded,
            retired = retired,
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

        val result = consolidator.consolidate(sessionPropositions, existing)

        // Persist promoted, reinforced, and merged propositions
        repository.saveAll(result.promoted)
        repository.saveAll(result.reinforced)
        repository.saveAll(result.merged.map { it.result })

        return result
    }

    private fun abstract(contextId: ContextId): Pair<List<Proposition>, List<Proposition>> {
        if (abstractor == null) return emptyList<Proposition>() to emptyList()

        val active = repository.query(
            PropositionQuery.Companion.forContextId(contextId)
                .withStatus(PropositionStatus.ACTIVE)
                .withMaxLevel(0)
        )

        // Group by entity resolvedId
        val entityGroups = active
            .flatMap { prop -> prop.mentions.mapNotNull { it.resolvedId }.map { entityId -> entityId to prop } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, props) -> props.distinct() }
            .filter { (_, props) -> props.size >= abstractionThreshold }

        val allAbstractions = mutableListOf<Proposition>()
        val allSuperseded = mutableListOf<Proposition>()

        for ((entityId, propositions) in entityGroups) {
            val group = PropositionGroup.Companion.of(entityId, propositions)
            val abstractions = abstractor.abstract(group, abstractionTargetCount)

            // Persist abstractions
            repository.saveAll(abstractions)
            allAbstractions.addAll(abstractions)

            // Mark source propositions as SUPERSEDED
            val superseded = propositions.map { it.withStatus(PropositionStatus.SUPERSEDED) }
            repository.saveAll(superseded)
            allSuperseded.addAll(superseded)
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
}
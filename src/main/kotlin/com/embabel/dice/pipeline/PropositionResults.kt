package com.embabel.dice.pipeline

import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.common.core.types.HasInfoString
import com.embabel.dice.common.*
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.SuggestedPropositions
import com.embabel.dice.proposition.revision.RevisionResult

/**
 * Statistics about proposition revision outcomes.
 */
data class PropositionExtractionStats(
    /** Number of propositions that were new (not similar to existing) */
    val newCount: Int,
    /** Number of propositions that were merged with existing identical ones */
    val mergedCount: Int,
    /** Number of propositions that reinforced existing similar ones */
    val reinforcedCount: Int,
    /** Number of propositions that contradicted existing ones */
    val contradictedCount: Int,
    /** Number of propositions that generalized existing ones */
    val generalizedCount: Int,
) {
    /** Total number of propositions processed */
    val total: Int get() = newCount + mergedCount + reinforcedCount + contradictedCount + generalizedCount

    companion object {
        fun from(revisionResults: List<RevisionResult>): PropositionExtractionStats = PropositionExtractionStats(
            newCount = revisionResults.count { it is RevisionResult.New },
            mergedCount = revisionResults.count { it is RevisionResult.Merged },
            reinforcedCount = revisionResults.count { it is RevisionResult.Reinforced },
            contradictedCount = revisionResults.count { it is RevisionResult.Contradicted },
            generalizedCount = revisionResults.count { it is RevisionResult.Generalized },
        )
    }
}

/**
 * Common interface for proposition revision statistics.
 */
interface PropositionExtractionResult {
    /** All revision results */
    val revisionResults: List<RevisionResult>

    /** Statistics about revision outcomes */
    val propositionExtractionStats: PropositionExtractionStats get() = PropositionExtractionStats.from(revisionResults)

    /** Whether revision was enabled */
    val hasRevision: Boolean get() = revisionResults.isNotEmpty()

    /**
     * All propositions that need to be persisted after revision.
     * Includes both new propositions and updates to existing ones:
     * - New: the new proposition
     * - Merged: the revised (merged) proposition
     * - Reinforced: the revised (reinforced) proposition
     * - Contradicted: both the original (with reduced confidence) and the new
     * - Generalized: the new generalizing proposition
     */
    val revisedPropositionsToPersist: List<Proposition>
        get() = revisionResults.flatMap { result ->
            when (result) {
                is RevisionResult.New -> listOf(result.proposition)
                is RevisionResult.Merged -> listOf(result.revised)
                is RevisionResult.Reinforced -> listOf(result.revised)
                is RevisionResult.Contradicted -> listOf(result.original, result.new)
                is RevisionResult.Generalized -> listOf(result.proposition)
            }
        }
}

/**
 * Result of processing a single chunk through the proposition pipeline.
 */
data class ChunkPropositionResult(
    val chunkId: String,
    val suggestedPropositions: SuggestedPropositions,
    val entityResolutions: Resolutions<SuggestedEntityResolution>,
    override val propositions: List<Proposition>,
    override val revisionResults: List<RevisionResult> = emptyList(),
) : PersistablePropositions, HasInfoString {

    override fun newEntities(): List<NamedEntityData> =
        entityResolutions.resolutions
            .filterIsInstance<NewEntity>()
            .map { it.suggested.suggestedEntity }

    override fun updatedEntities(): List<NamedEntityData> =
        entityResolutions.resolutions
            .filterIsInstance<ExistingEntity>()
            .map { it.existing }

    override fun infoString(verbose: Boolean?, indent: Int): String {
        val prefix = "  ".repeat(indent)
        val stats = propositionExtractionStats
        val newEntitiesCount = newEntities().size
        val updatedEntitiesCount = updatedEntities().size

        return buildString {
            append("ChunkPropositionResult(chunk=$chunkId, ")
            append("propositions=${propositions.size}, ")
            append("entities: $newEntitiesCount new, $updatedEntitiesCount updated")
            if (hasRevision) {
                append(", revision: ")
                append("${stats.newCount} new, ")
                append("${stats.mergedCount} merged, ")
                append("${stats.reinforcedCount} reinforced, ")
                append("${stats.contradictedCount} contradicted, ")
                append("${stats.generalizedCount} generalized")
            }
            append(")")

            if (verbose == true) {
                appendLine()
                append("${prefix}Propositions:")
                propositions.forEachIndexed { i, prop ->
                    appendLine()
                    val revisionInfo = if (hasRevision && i < revisionResults.size) {
                        when (val result = revisionResults[i]) {
                            is RevisionResult.New -> "[NEW]"
                            is RevisionResult.Merged -> "[MERGED with ${result.original.id.take(8)}]"
                            is RevisionResult.Reinforced -> "[REINFORCED ${result.original.id.take(8)}]"
                            is RevisionResult.Contradicted -> "[CONTRADICTED ${result.original.id.take(8)}]"
                            is RevisionResult.Generalized -> "[GENERALIZED ${result.generalizes.size} props]"
                        }
                    } else ""
                    append("$prefix  • ${prop.text} (conf: ${String.format("%.2f", prop.confidence)}) $revisionInfo")
                }
                if (newEntitiesCount > 0 || updatedEntitiesCount > 0) {
                    appendLine()
                    append("${prefix}Entities:")
                    newEntities().forEach { entity ->
                        appendLine()
                        append("$prefix  • [NEW] ${entity.name} (${entity.labels().joinToString()})")
                    }
                    updatedEntities().forEach { entity ->
                        appendLine()
                        append("$prefix  • [UPDATED] ${entity.name} (${entity.labels().joinToString()})")
                    }
                }
            }
        }
    }
}

/**
 * Result of processing multiple chunks through the proposition pipeline.
 * Implements [EntityExtractionResult] for access to entities needing persistence.
 *
 * This result contains all extracted data but does NOT persist anything.
 * The caller is responsible for persisting entities and propositions as needed.
 */
data class PropositionResults(
    val chunkResults: List<ChunkPropositionResult>,
    val allPropositions: List<Proposition>,
) : PersistablePropositions {

    override val propositions: List<Proposition> get() = allPropositions

    val totalPropositions: Int get() = allPropositions.size
    val fullyResolvedCount: Int get() = allPropositions.count { it.isFullyResolved() }
    val partiallyResolvedCount: Int get() = allPropositions.count { !it.isFullyResolved() && it.mentions.any { m -> m.resolvedId != null } }
    val unresolvedCount: Int get() = allPropositions.count { it.mentions.none { m -> m.resolvedId != null } }

    /** All revision results across all chunks */
    override val revisionResults: List<RevisionResult> get() = chunkResults.flatMap { it.revisionResults }

    override fun newEntities(): List<NamedEntityData> =
        chunkResults.flatMap { it.newEntities() }.distinctBy { it.id }

    override fun updatedEntities(): List<NamedEntityData> =
        chunkResults.flatMap { it.updatedEntities() }.distinctBy { it.id }
}
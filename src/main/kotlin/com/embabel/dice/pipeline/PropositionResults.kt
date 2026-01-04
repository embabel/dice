package com.embabel.dice.pipeline

import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.dice.common.*
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.SuggestedPropositions
import com.embabel.dice.proposition.revision.RevisionResult

/**
 * Common interface for proposition revision statistics.
 */
interface PropositionExtractionResult {
    /** All revision results */
    val revisionResults: List<RevisionResult>

    /** Number of propositions that were new (not similar to existing) */
    val newCount: Int get() = revisionResults.count { it is RevisionResult.New }

    /** Number of propositions that were merged with existing identical ones */
    val mergedCount: Int get() = revisionResults.count { it is RevisionResult.Merged }

    /** Number of propositions that reinforced existing similar ones */
    val reinforcedCount: Int get() = revisionResults.count { it is RevisionResult.Reinforced }

    /** Number of propositions that contradicted existing ones */
    val contradictedCount: Int get() = revisionResults.count { it is RevisionResult.Contradicted }

    /** Number of propositions that generalized existing ones */
    val generalizedCount: Int get() = revisionResults.count { it is RevisionResult.Generalized }

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

interface PersistablePropositionResults : EntityExtractionResult, PropositionExtractionResult {

    /**
     * All propositions extracted (before any revision).
     */
    val propositions: List<Proposition>

    /**
     * Persist extracted entities and propositions to their respective repositories.
     * - New entities are saved to the entity repository
     * - If revision was enabled, saves all revised propositions (new, merged, reinforced, etc.)
     * - If revision was not enabled, saves all extracted propositions
     */
    fun persist(
        propositionRepository: PropositionRepository,
        namedEntityDataRepository: NamedEntityDataRepository
    ) {
        // Save new entities
        newEntities().forEach { entity ->
            namedEntityDataRepository.save(entity)
        }
        // TODO what about updated entities?

        // Save propositions - use revision results if available, otherwise all propositions
        val toSave = if (hasRevision) revisedPropositionsToPersist else propositions
        propositionRepository.saveAll(toSave)
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
) : PersistablePropositionResults {

    override fun newEntities(): List<NamedEntityData> =
        entityResolutions.resolutions
            .filterIsInstance<NewEntity>()
            .map { it.suggested.suggestedEntity }

    override fun updatedEntities(): List<NamedEntityData> =
        entityResolutions.resolutions
            .filterIsInstance<ExistingEntity>()
            .map { it.existing }
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
) : PersistablePropositionResults {

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
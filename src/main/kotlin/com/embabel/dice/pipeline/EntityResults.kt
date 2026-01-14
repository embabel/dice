package com.embabel.dice.pipeline

import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.common.core.types.HasInfoString
import com.embabel.dice.common.*

/**
 * Result of processing a single chunk through the entity pipeline.
 *
 * Implements [EntityExtractionResult] for access to entities that need persistence.
 * Unlike [ChunkPropositionResult], this contains only entity data without propositions.
 *
 * @param chunkId The ID of the processed chunk
 * @param suggestedEntities The entities suggested by the extractor
 * @param entityResolutions The resolution results (new, existing, reference-only, vetoed)
 */
data class ChunkEntityResult(
    val chunkId: String,
    val suggestedEntities: SuggestedEntities,
    val entityResolutions: Resolutions<SuggestedEntityResolution>,
) : EntityExtractionResult, HasInfoString {

    override fun newEntities(): List<NamedEntityData> =
        entityResolutions.resolutions
            .filterIsInstance<NewEntity>()
            .map { it.suggested.suggestedEntity }

    override fun updatedEntities(): List<NamedEntityData> =
        entityResolutions.resolutions
            .filterIsInstance<ExistingEntity>()
            .map { it.recommended }

    override fun referenceOnlyEntities(): List<NamedEntityData> =
        entityResolutions.resolutions
            .filterIsInstance<ReferenceOnlyEntity>()
            .map { it.existing }

    /**
     * All resolved entities (new + existing + reference-only).
     * Excludes vetoed entities.
     */
    fun resolvedEntities(): List<NamedEntityData> =
        entityResolutions.resolutions
            .mapNotNull { it.recommended }

    override fun infoString(verbose: Boolean?, indent: Int): String {
        val prefix = "  ".repeat(indent)
        val stats = entityExtractionStats
        val newCount = stats.newCount
        val updatedCount = stats.updatedCount
        val refOnlyCount = stats.referenceOnlyCount

        return buildString {
            append("ChunkEntityResult(chunk=$chunkId, ")
            append("entities: $newCount new, $updatedCount updated")
            if (refOnlyCount > 0) {
                append(", $refOnlyCount reference-only")
            }
            append(")")

            if (verbose == true) {
                if (newCount > 0 || updatedCount > 0 || refOnlyCount > 0) {
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
                    referenceOnlyEntities().forEach { entity ->
                        appendLine()
                        append("$prefix  • [REF-ONLY] ${entity.name} (${entity.labels().joinToString()})")
                    }
                }
            }
        }
    }

    /**
     * Persist entities to the repository.
     *
     * @param entityRepository Repository for entity persistence
     */
    fun persist(entityRepository: NamedEntityDataRepository) {
        val toPersist = entitiesToPersist()
        if (toPersist.isNotEmpty()) {
            entityRepository.saveAll(toPersist)
        }
    }
}

/**
 * Result of processing multiple chunks through the entity pipeline.
 *
 * Aggregates results across all chunks and provides deduplicated entity lists.
 *
 * @param chunkResults Results from individual chunk processing
 */
data class EntityResults(
    val chunkResults: List<ChunkEntityResult>,
) : EntityExtractionResult, HasInfoString {

    /** Total number of suggested entities across all chunks */
    val totalSuggested: Int get() = chunkResults.sumOf { it.suggestedEntities.suggestedEntities.size }

    /** Total number of resolved entities (deduplicated) */
    val totalResolved: Int get() = resolvedEntities().size

    override fun newEntities(): List<NamedEntityData> =
        chunkResults.flatMap { it.newEntities() }.distinctBy { it.id }

    override fun updatedEntities(): List<NamedEntityData> =
        chunkResults.flatMap { it.updatedEntities() }.distinctBy { it.id }

    override fun referenceOnlyEntities(): List<NamedEntityData> =
        chunkResults.flatMap { it.referenceOnlyEntities() }.distinctBy { it.id }

    /**
     * All resolved entities across all chunks (deduplicated).
     */
    fun resolvedEntities(): List<NamedEntityData> =
        chunkResults.flatMap { it.resolvedEntities() }.distinctBy { it.id }

    override fun infoString(verbose: Boolean?, indent: Int): String {
        val prefix = "  ".repeat(indent)
        val stats = entityExtractionStats

        return buildString {
            append("EntityResults(")
            append("chunks=${chunkResults.size}, ")
            append("suggested=$totalSuggested, ")
            append("resolved=$totalResolved: ")
            append("${stats.newCount} new, ")
            append("${stats.updatedCount} updated")
            if (stats.referenceOnlyCount > 0) {
                append(", ${stats.referenceOnlyCount} reference-only")
            }
            append(")")

            if (verbose == true) {
                chunkResults.forEachIndexed { i, result ->
                    appendLine()
                    append("$prefix  [$i] ${result.infoString(verbose, indent + 2)}")
                }
            }
        }
    }

    /**
     * Persist all entities to the repository.
     *
     * @param entityRepository Repository for entity persistence
     */
    fun persist(entityRepository: NamedEntityDataRepository) {
        val toPersist = entitiesToPersist()
        if (toPersist.isNotEmpty()) {
            entityRepository.saveAll(toPersist)
        }
    }
}

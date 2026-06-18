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
package com.embabel.dice.entity

import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.RelationshipData
import com.embabel.agent.rag.service.RetrievableIdentifier
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
     * Persist entities to the repository and create structural relationships.
     *
     * Creates `(Chunk)-[:HAS_ENTITY]->(Entity)` relationships linking the source
     * chunk to each extracted entity.
     *
     * @param entityRepository Repository for entity persistence
     */
    fun persist(entityRepository: NamedEntityDataRepository) {
        // Save new entities
        newEntities().forEach { entity ->
            entityRepository.save(entity)
        }

        // Update existing entities (merged labels/properties)
        updatedEntities().forEach { entity ->
            entityRepository.update(entity)
        }

        // Create (Chunk)-[:HAS_ENTITY]->(Entity) relationships
        createChunkEntityRelationships(entityRepository)
    }

    /**
     * Create structural relationships linking the chunk to extracted entities.
     */
    private fun createChunkEntityRelationships(entityRepository: NamedEntityDataRepository) {
        val chunk = RetrievableIdentifier.forChunk(chunkId)
        val entitiesToLink = entitiesToPersist()

        for (entity in entitiesToLink) {
            val entityRef = RetrievableIdentifier(entity.id, entity.labels().firstOrNull() ?: "Entity")
            entityRepository.createRelationship(
                chunk,
                entityRef,
                RelationshipData(NamedEntityData.HAS_ENTITY)
            )
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
     * Persist all entities to the repository and create structural relationships.
     *
     * Creates `(Chunk)-[:HAS_ENTITY]->(Entity)` relationships linking each source
     * chunk to the entities extracted from it.
     *
     * Entities are deduplicated across chunks before saving, but relationships
     * are created for each chunk where an entity was mentioned.
     *
     * @param entityRepository Repository for entity persistence
     */
    fun persist(entityRepository: NamedEntityDataRepository) {
        // Save new entities (deduplicated across chunks)
        newEntities().forEach { entity ->
            entityRepository.save(entity)
        }

        // Update existing entities (deduplicated across chunks)
        updatedEntities().forEach { entity ->
            entityRepository.update(entity)
        }

        // Create (Chunk)-[:HAS_ENTITY]->(Entity) relationships for each chunk
        createChunkEntityRelationships(entityRepository)
    }

    /**
     * Create structural relationships linking chunks to their extracted entities.
     * Each chunk gets linked to the entities that were extracted from it.
     */
    private fun createChunkEntityRelationships(entityRepository: NamedEntityDataRepository) {
        // Track chunk-entity pairs to avoid duplicates
        val chunkEntityPairs = mutableSetOf<Pair<String, String>>()

        for (chunkResult in chunkResults) {
            val chunk = RetrievableIdentifier.forChunk(chunkResult.chunkId)

            for (entity in chunkResult.entitiesToPersist()) {
                val pair = chunkResult.chunkId to entity.id
                if (pair !in chunkEntityPairs) {
                    chunkEntityPairs.add(pair)
                    val entityRef = RetrievableIdentifier(entity.id, entity.labels().firstOrNull() ?: "Entity")
                    entityRepository.createRelationship(
                        chunk,
                        entityRef,
                        RelationshipData(NamedEntityData.HAS_ENTITY)
                    )
                }
            }
        }
    }
}

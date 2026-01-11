package com.embabel.dice.pipeline

import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.RelationshipData
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.dice.common.EntityExtractionResult
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.RelationshipTypes

/**
 * Result of entity and proposition extraction that can be persisted.
 * Combines [com.embabel.dice.common.EntityExtractionResult] and [PropositionExtractionResult].
 * Guides callers to know what to persist, within their own transaction scope.
 */
interface PersistablePropositions : EntityExtractionResult, PropositionExtractionResult {

    /**
     * All propositions extracted (before any revision).
     */
    val propositions: List<Proposition>

    fun propositionsToPersist(): List<Proposition> =
        if (hasRevision) revisedPropositionsToPersist else propositions

    /**
     * Persist extracted entities and propositions to their respective repositories.
     * Also creates structural relationships:
     * - `(Chunk)-[:HAS_ENTITY]->(__Entity__)` for each entity mentioned in grounding chunks
     * - `(Chunk)-[:HAS_PROPOSITION]->(Proposition)` for each grounding chunk
     * - `(Proposition)-[:MENTIONS {role}]->(__Entity__)` for each resolved entity mention
     *
     * - Only saves entities that are actually referenced by propositions being persisted
     * - If revision was enabled, saves all revised propositions (new, merged, reinforced, etc.)
     * - If revision was not enabled, saves all extracted propositions
     */
    fun persist(
        propositionRepository: PropositionRepository,
        namedEntityDataRepository: NamedEntityDataRepository
    ) {
        val propsToSave = propositionsToPersist()

        // Only persist entities that are actually referenced by propositions being saved
        val referencedEntityIds = propsToSave
            .flatMap { it.mentions }
            .mapNotNull { it.resolvedId }
            .toSet()

        newEntities()
            .filter { it.id in referencedEntityIds }
            .forEach { entity ->
                namedEntityDataRepository.save(entity)
            }
        updatedEntities()
            .filter { it.id in referencedEntityIds }
            .forEach { entity ->
                namedEntityDataRepository.update(entity)
            }

        // Save propositions - use revision results if available, otherwise all propositions
        propositionRepository.saveAll(propsToSave)

        // Create structural relationships
        createStructuralRelationships(propsToSave, namedEntityDataRepository)
    }

    companion object {
        const val PROPOSITION_LABEL = "Proposition"

        /**
         * Create structural relationships linking chunks, propositions, and entities.
         *
         * Creates:
         * - `(Chunk)-[:HAS_ENTITY]->(__Entity__)` for direct entity extraction
         * - `(Chunk)-[:HAS_PROPOSITION]->(Proposition)` for proposition provenance
         * - `(Proposition)-[:MENTIONS {role}]->(__Entity__)` for entity references
         */
        @JvmStatic
        fun createStructuralRelationships(
            propositions: List<Proposition>,
            namedEntityDataRepository: NamedEntityDataRepository
        ) {
            // Track chunk-entity pairs to avoid duplicates
            val chunkEntityPairs = mutableSetOf<Pair<String, String>>()

            for (proposition in propositions) {
                val propositionId = RetrievableIdentifier(proposition.id, PROPOSITION_LABEL)

                // (Chunk)-[:HAS_PROPOSITION]->(Proposition) for each grounding chunk
                for (chunkId in proposition.grounding) {
                    val chunk = RetrievableIdentifier.Companion.forChunk(chunkId)
                    namedEntityDataRepository.createRelationship(
                        chunk,
                        propositionId,
                        RelationshipData(RelationshipTypes.HAS_PROPOSITION)
                    )
                }

                // (Proposition)-[:MENTIONS {role}]->(Entity) for each resolved mention
                for (mention in proposition.mentions) {
                    val entityId = mention.resolvedId ?: continue
                    val entity = RetrievableIdentifier(entityId, mention.type)

                    namedEntityDataRepository.createRelationship(
                        propositionId,
                        entity,
                        RelationshipData(
                            name = RelationshipTypes.MENTIONS,
                            properties = mapOf(RelationshipTypes.ROLE_PROPERTY to mention.role.name)
                        )
                    )

                    // (Chunk)-[:HAS_ENTITY]->(Entity) for each grounding chunk
                    for (chunkId in proposition.grounding) {
                        val pair = chunkId to entityId
                        if (pair !in chunkEntityPairs) {
                            chunkEntityPairs.add(pair)
                            val chunk = RetrievableIdentifier.Companion.forChunk(chunkId)
                            namedEntityDataRepository.createRelationship(
                                chunk,
                                entity,
                                RelationshipData(NamedEntityData.HAS_ENTITY)
                            )
                        }
                    }
                }
            }
        }
    }
}
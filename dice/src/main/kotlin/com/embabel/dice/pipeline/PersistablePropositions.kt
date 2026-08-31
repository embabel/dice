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
package com.embabel.dice.pipeline

import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.RelationshipData
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.dice.common.EntityExtractionResult
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionPersistenceResult
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

    /**
     * Persist the same things [persist] does, and report which stored proposition each extracted
     * one landed on.
     *
     * The difference that matters is one line: structural relationships are wired against the
     * propositions the repository returned, not the ones extraction minted. On a deduplicating
     * backend those are not the same. `DrivinePropositionRepository` collapses a fresh insert onto
     * an existing proposition with identical `(contextId, text)` and returns that one, so a
     * `HAS_PROPOSITION` or `MENTIONS` edge written against the minted id points at a node that was
     * never stored. Here it points at the node that was.
     *
     * [persist] is left exactly as it was. This is a second entry point rather than a fix applied
     * in place, because changing what [persist] wires would change behaviour for every existing
     * caller — including hosts that never asked for extraction runs.
     *
     * Entity persistence, the referenced-entity filter, and which propositions get saved are all
     * identical to [persist].
     *
     * @param propositionRepository Where propositions go.
     * @param namedEntityDataRepository Where entities go, and what writes the structural edges.
     * @return The stored proposition for each one persisted, and the input-id to stored-id mapping.
     */
    fun persistReturningCanonical(
        propositionRepository: PropositionRepository,
        namedEntityDataRepository: NamedEntityDataRepository,
    ): PropositionPersistenceResult {
        val persisted = persistCanonicalPropositions(propositionRepository, namedEntityDataRepository)
        wireStructuralRelationships(persisted, namedEntityDataRepository)
        return persisted
    }

    /**
     * The first half of [persistReturningCanonical]: entities and propositions are saved, and
     * nothing else happens. Saved means committed only when no transaction wraps the call; inside
     * an ambient transaction the claims land with the caller's commit, not here.
     *
     * **Split out so a caller can act on saved claims before anything fallible runs.** Structural
     * wiring goes through `mergeRelationship`, which can throw, and while it lived inside the same
     * call the propositions were already stored by the time it failed — but a caller could not do
     * anything about that until the call returned, which it never did. Extraction-run lineage is the
     * caller that has to: attribution is a statement about claims that exist, and a failing edge
     * write must not be able to strand a stored claim with no record of the run that produced it.
     *
     * A caller that has no such ordering requirement should use [persistReturningCanonical], which
     * is this followed by [wireStructuralRelationships] and is what it always was.
     *
     * @param propositionRepository Where propositions go.
     * @param namedEntityDataRepository Where entities go.
     * @return The stored proposition for each one persisted, and the input-id to stored-id mapping.
     */
    fun persistCanonicalPropositions(
        propositionRepository: PropositionRepository,
        namedEntityDataRepository: NamedEntityDataRepository,
    ): PropositionPersistenceResult {
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

        return propositionRepository.saveAllReturningCanonical(propsToSave)
    }

    /**
     * The second half of [persistReturningCanonical]: the chunk, proposition and entity edges, wired
     * against the propositions the repository returned so every edge lands on a node the store
     * actually holds.
     *
     * @param persisted What [persistCanonicalPropositions] returned.
     * @param namedEntityDataRepository What writes the edges.
     */
    fun wireStructuralRelationships(
        persisted: PropositionPersistenceResult,
        namedEntityDataRepository: NamedEntityDataRepository,
    ) {
        // The distinct view: two inputs that deduplicated onto one proposition are one
        // proposition, and issuing the same merge twice is duplicate work, not a second edge.
        createStructuralRelationships(
            persisted.distinctCanonicalPropositions,
            namedEntityDataRepository,
        )
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
                    namedEntityDataRepository.mergeRelationship(
                        chunk,
                        propositionId,
                        RelationshipData(RelationshipTypes.HAS_PROPOSITION)
                    )
                }

                // (Proposition)-[:MENTIONS {role}]->(Entity) for each resolved mention
                for (mention in proposition.mentions) {
                    val entityId = mention.resolvedId ?: continue
                    val entity = RetrievableIdentifier(entityId, mention.type)

                    namedEntityDataRepository.mergeRelationship(
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
                            namedEntityDataRepository.mergeRelationship(
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

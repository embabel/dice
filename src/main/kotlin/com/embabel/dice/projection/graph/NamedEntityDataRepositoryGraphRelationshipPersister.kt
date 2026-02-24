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
package com.embabel.dice.projection.graph

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.RelationshipData
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.dice.proposition.ProjectionResults
import com.embabel.dice.proposition.Proposition
import org.slf4j.LoggerFactory

/**
 * Implementation of [GraphRelationshipPersister] that uses [com.embabel.agent.rag.service.NamedEntityDataRepository].
 *
 * Converts projected relationships to the repository's relationship format and
 * stores them in the underlying graph database.
 *
 * Example:
 * ```kotlin
 * val persister = NamedEntityDataRepositoryGraphRelationshipPersister(repository)
 *
 * // Project propositions to relationships
 * val results = graphProjector.projectAll(propositions, schema)
 *
 * // Persist the projected relationships
 * val persistenceResult = persister.persist(results)
 * println("Persisted ${persistenceResult.persistedCount} relationships")
 * ```
 *
 * @param repository The repository to persist relationships to
 */
class NamedEntityDataRepositoryGraphRelationshipPersister(
    private val repository: NamedEntityDataRepository,
) : GraphRelationshipPersister {

    private val logger = LoggerFactory.getLogger(NamedEntityDataRepositoryGraphRelationshipPersister::class.java)

    override fun persist(results: ProjectionResults<ProjectedRelationship>): RelationshipPersistenceResult {
        return persist(results.projected)
    }

    override fun persist(relationships: List<ProjectedRelationship>): RelationshipPersistenceResult {
        var persistedCount = 0
        var failedCount = 0
        val errors = mutableListOf<String>()

        for (relationship in relationships) {
            try {
                persistRelationship(relationship)
                persistedCount++
                logger.info("Persisted relationship: {}", relationship.infoString(true))
            } catch (e: Exception) {
                failedCount++
                val errorMsg = "Failed to persist ${relationship.infoString(false)}: ${e.message}"
                errors.add(errorMsg)
                logger.warn(errorMsg, e)
            }
        }

        logger.info("Persisted {}/{} relationships", persistedCount, relationships.size)
        return RelationshipPersistenceResult(persistedCount, failedCount, errors)
    }

    override fun persistRelationship(relationship: ProjectedRelationship) {
        // Create entity identifiers - type is determined from the relationship context
        val sourceEntity = repository.findById(relationship.sourceId)
        val targetEntity = repository.findById(relationship.targetId)

        val sourceType = sourceEntity?.labels()?.firstOrNull() ?: "Entity"
        val targetType = targetEntity?.labels()?.firstOrNull() ?: "Entity"

        val source = RetrievableIdentifier(
            id = relationship.sourceId,
            type = sourceType,
        )
        val target = RetrievableIdentifier(
            id = relationship.targetId,
            type = targetType,
        )

        // Build relationship properties
        val properties = buildMap {
            put("confidence", relationship.confidence)
            if (relationship.decay > 0) {
                put("decay", relationship.decay)
            }
            relationship.description?.let { put("description", it) }
            if (relationship.sourcePropositionIds.isNotEmpty()) {
                put("sourcePropositions", relationship.sourcePropositionIds)
            }
        }

        val relationshipData = RelationshipData(
            name = relationship.type,
            properties = properties,
        )

        repository.mergeRelationship(source, target, relationshipData)
    }

    override fun projectAndPersist(
        propositions: List<Proposition>,
        graphProjector: GraphProjector,
        schema: DataDictionary,
    ): Pair<ProjectionResults<ProjectedRelationship>, RelationshipPersistenceResult> {
        val projectionResults = graphProjector.projectAll(propositions, schema)
        val persistenceResult = persist(projectionResults)
        return Pair(projectionResults, persistenceResult)
    }

    override fun synthesizeAndUpdateDescriptions(
        entityPairs: List<EntityPairWithPropositions>,
        synthesizer: RelationshipDescriptionSynthesizer,
    ): RelationshipPersistenceResult {
        var persistedCount = 0
        var failedCount = 0
        val errors = mutableListOf<String>()

        for (pair in entityPairs) {
            try {
                val result = synthesizer.synthesize(
                    SynthesisRequest(
                        sourceEntityId = pair.sourceId,
                        sourceEntityName = pair.sourceName,
                        targetEntityId = pair.targetId,
                        targetEntityName = pair.targetName,
                        relationshipType = pair.relationshipType,
                        propositions = pair.propositions,
                        existingDescription = pair.existingDescription,
                    )
                )
                if (result.description.isBlank()) {
                    logger.debug("Synthesis returned blank description for {} -> {}", pair.sourceName, pair.targetName)
                    continue
                }

                val relationship = ProjectedRelationship(
                    sourceId = pair.sourceId,
                    targetId = pair.targetId,
                    type = pair.relationshipType,
                    confidence = result.confidence,
                    description = result.description,
                    sourcePropositionIds = result.sourcePropositionIds,
                )
                persistRelationship(relationship)
                persistedCount++
            } catch (e: Exception) {
                failedCount++
                val errorMsg = "Failed to synthesize description for ${pair.sourceName} -> ${pair.targetName}: ${e.message}"
                errors.add(errorMsg)
                logger.warn(errorMsg, e)
            }
        }

        logger.info("Synthesized and updated {}/{} relationship descriptions", persistedCount, entityPairs.size)
        return RelationshipPersistenceResult(persistedCount, failedCount, errors)
    }
}

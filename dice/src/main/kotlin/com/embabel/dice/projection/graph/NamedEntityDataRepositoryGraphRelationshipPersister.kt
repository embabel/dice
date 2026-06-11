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
import com.embabel.dice.common.AuthorityResolver
import com.embabel.dice.common.StructuralAuthorityResolver
import com.embabel.dice.proposition.ProjectionResults
import com.embabel.dice.proposition.Proposition
import org.slf4j.LoggerFactory

/**
 * Persists projected graph relationships via [NamedEntityDataRepository].
 *
 * Converts each [ProjectedRelationship] to the repository's relationship format
 * and writes it to the underlying graph database.
 *
 * The [authorityResolver] stamps the strongest authority across the source propositions onto
 * each edge written by [synthesizeAndUpdateDescriptions], so authority is preserved through
 * the description-synthesis re-persist cycle and not silently dropped.
 *
 * ```kotlin
 * val persister = NamedEntityDataRepositoryGraphRelationshipPersister(repository)
 * val result = persister.persist(graphProjector.projectAll(propositions, schema))
 * println("Persisted ${result.persistedCount} relationships")
 * ```
 *
 * @param repository the graph repository to write relationships into
 * @param authorityResolver resolves the source authority to stamp on synthesized edges;
 *   defaults to [StructuralAuthorityResolver]
 */
class NamedEntityDataRepositoryGraphRelationshipPersister @JvmOverloads constructor(
    private val repository: NamedEntityDataRepository,
    private val authorityResolver: AuthorityResolver = StructuralAuthorityResolver(),
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

    /**
     * Persists a single projected relationship to the graph.
     *
     * Re-saves each resolved entity verbatim (exactly as returned by the repository)
     * before merging the edge, so multi-label nodes like `(:Person:User)` materialise
     * correctly — the [RetrievableIdentifier] edge endpoint only carries one type string,
     * so the re-save is how the full label set gets written.
     *
     * The three repository calls (source save, target save, mergeRelationship) are not
     * transactional within this module. If you need all-or-nothing semantics, wrap this
     * call in a `@Transactional` boundary in your consuming Spring context.
     */
    override fun persistRelationship(relationship: ProjectedRelationship) {
        // Create entity identifiers - type is determined from the relationship context
        val sourceEntity = repository.findById(relationship.sourceId)
        val targetEntity = repository.findById(relationship.targetId)

        // Re-save each resolved entity exactly as fetched so its full label set
        // (e.g. (:Person:User)) materializes regardless of save ordering. Passing
        // the fetched object verbatim keeps the save additive/non-destructive.
        sourceEntity?.let { repository.save(it) }
        targetEntity?.let { repository.save(it) }

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
            // Carry the source authority onto the edge so downstream queries can tell a
            // strongly-grounded relationship apart from a weak structural one.
            relationship.authority?.let { put("authority", it.name) }
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

                // Resolve the strongest authority across the source propositions so the
                // synthesized description re-persist carries the same grounding stamp as the
                // original projected edge — not a null that silently downgrades it.
                val pairAuthority = pair.propositions
                    .map { authorityResolver.resolve(it) }
                    .minByOrNull { it.ordinal }

                val relationship = ProjectedRelationship(
                    sourceId = pair.sourceId,
                    targetId = pair.targetId,
                    type = pair.relationshipType,
                    confidence = result.confidence,
                    description = result.description,
                    sourcePropositionIds = result.sourcePropositionIds,
                    authority = pairAuthority,
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

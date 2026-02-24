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

import com.embabel.dice.proposition.ProjectionResults
import com.embabel.dice.proposition.Proposition

/**
 * Result of persisting projected relationships.
 *
 * @property persistedCount Number of relationships successfully persisted
 * @property failedCount Number of relationships that failed to persist
 * @property errors List of error messages for failed persistences
 */
data class RelationshipPersistenceResult(
    val persistedCount: Int,
    val failedCount: Int,
    val errors: List<String> = emptyList(),
) {
    val totalAttempted: Int get() = persistedCount + failedCount
    val allSucceeded: Boolean get() = failedCount == 0
}

/**
 * Input for batch relationship description synthesis.
 *
 * @property sourceId Resolved ID of the source entity
 * @property sourceName Display name of the source entity
 * @property targetId Resolved ID of the target entity
 * @property targetName Display name of the target entity
 * @property relationshipType The relationship type (e.g. "KNOWS")
 * @property propositions Propositions mentioning both entities
 * @property existingDescription Current description on the edge, if any
 */
data class EntityPairWithPropositions(
    val sourceId: String,
    val sourceName: String,
    val targetId: String,
    val targetName: String,
    val relationshipType: String,
    val propositions: List<Proposition>,
    val existingDescription: String?,
)

/**
 * Persists [ProjectedRelationship] instances to a graph store.
 */
interface GraphRelationshipPersister {

    /**
     * Persist all successfully projected relationships from the results.
     *
     * @param results The projection results containing relationships to persist
     * @return Statistics about the persistence operation
     */
    fun persist(results: ProjectionResults<ProjectedRelationship>): RelationshipPersistenceResult

    /**
     * Persist a list of projected relationships.
     *
     * @param relationships The relationships to persist
     * @return Statistics about the persistence operation
     */
    fun persist(relationships: List<ProjectedRelationship>): RelationshipPersistenceResult

    /**
     * Persist a single projected relationship.
     *
     * @param relationship The relationship to persist
     */
    fun persistRelationship(relationship: ProjectedRelationship)

    /**
     * Project propositions and persist the resulting relationships in one operation.
     *
     * @param propositions The propositions to project
     * @param graphProjector The projector to use
     * @param schema The data dictionary for validation
     * @return Pair of projection results and persistence results
     */
    fun projectAndPersist(
        propositions: List<Proposition>,
        graphProjector: GraphProjector,
        schema: com.embabel.agent.core.DataDictionary,
    ): Pair<ProjectionResults<ProjectedRelationship>, RelationshipPersistenceResult>

    /**
     * Synthesize descriptions for entity-pair relationships and update the edges.
     * Default implementation is a no-op for implementations that don't support synthesis.
     *
     * @param entityPairs Entity pairs with their propositions
     * @param synthesizer The synthesizer to use
     * @return Statistics about the persistence operation
     */
    fun synthesizeAndUpdateDescriptions(
        entityPairs: List<EntityPairWithPropositions>,
        synthesizer: RelationshipDescriptionSynthesizer,
    ): RelationshipPersistenceResult = RelationshipPersistenceResult(0, 0)
}

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
}


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
import com.embabel.common.core.types.HasInfoString
import com.embabel.dice.common.AuthorityTier
import com.embabel.dice.proposition.*
import com.embabel.dice.text2graph.RelationshipInstance

/**
 * A graph relationship produced by projecting one or more propositions.
 *
 * @property sourceId ID of the source entity
 * @property targetId ID of the target entity
 * @property type relationship type from the schema (e.g., "EXPERT_IN", "OWNS_PET")
 * @property confidence confidence aggregated from the source propositions
 * @property decay decay rate aggregated from the source propositions
 * @property description optional human-readable description of the relationship
 * @property sourcePropositionIds IDs of the propositions this edge was derived from
 * @property authority how authoritative the source is — lets downstream queries
 *   distinguish a strongly-grounded edge from a weak inferred one. Null when not resolved.
 */
data class ProjectedRelationship(
    override val sourceId: String,
    override val targetId: String,
    override val type: String,
    override val confidence: Double,
    override val decay: Double = 0.0,
    override val description: String? = null,
    override val sourcePropositionIds: List<String>,
    val authority: AuthorityTier? = null,
) : RelationshipInstance, Projection, HasInfoString {

    /** Same as [sourceId] — convenience alias. */
    val fromId: String get() = sourceId

    /** Same as [targetId] — convenience alias. */
    val toId: String get() = targetId

    override fun infoString(verbose: Boolean?, indent: Int): String {
        return if (verbose == true) {
            "ProjectedRelationship($sourceId -[$type]-> $targetId, conf=$confidence, " +
                "authority=$authority, sources=${sourcePropositionIds.size})"
        } else {
            "($sourceId)-[:$type]->($targetId)"
        }
    }
}

/**
 * Turns propositions into knowledge graph relationships, using the schema to validate
 * relationship types and entity compatibility.
 */
interface GraphProjector : Projector<ProjectedRelationship> {

    /**
     * Projects a single proposition to a graph relationship.
     *
     * @param proposition the proposition to project
     * @param schema data dictionary defining the allowed relationship types
     * @return the projection result (success, skipped, or failed)
     */
    override fun project(
        proposition: Proposition,
        schema: DataDictionary,
    ): ProjectionResult<ProjectedRelationship>

    /**
     * Projects a list of propositions, applying the configured policy to each.
     *
     * @param propositions propositions to project
     * @param schema data dictionary defining the allowed relationship types
     * @return aggregated results for the whole batch
     */
    override fun projectAll(
        propositions: List<Proposition>,
        schema: DataDictionary,
    ): ProjectionResults<ProjectedRelationship> {
        val results = propositions.map { project(it, schema) }
        return ProjectionResults(results)
    }
}

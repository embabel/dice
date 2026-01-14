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
import com.embabel.dice.proposition.*
import com.embabel.dice.text2graph.RelationshipInstance

/**
 * A relationship projected from one or more propositions.
 * Created when propositions are projected to the knowledge graph.
 *
 * @property sourceId ID of the source entity
 * @property targetId ID of the target entity
 * @property type Relationship type from schema (e.g., "EXPERT_IN", "OWNS_PET")
 * @property confidence Aggregated confidence from source propositions
 * @property decay Aggregated decay rate from source propositions
 * @property description Optional description of the relationship
 * @property sourcePropositionIds IDs of propositions this relationship derives from
 */
data class ProjectedRelationship(
    override val sourceId: String,
    override val targetId: String,
    override val type: String,
    override val confidence: Double,
    override val decay: Double = 0.0,
    override val description: String? = null,
    override val sourcePropositionIds: List<String>,
) : RelationshipInstance, Projection, HasInfoString {

    /** Alias for sourceId */
    val fromId: String get() = sourceId

    /** Alias for targetId */
    val toId: String get() = targetId

    override fun infoString(verbose: Boolean?, indent: Int): String {
        return if (verbose == true) {
            "ProjectedRelationship($sourceId -[$type]-> $targetId, conf=$confidence, sources=${sourcePropositionIds.size})"
        } else {
            "($sourceId)-[:$type]->($targetId)"
        }
    }
}

/**
 * Projects propositions to knowledge graph relationships.
 * Uses the schema to validate relationship types and entity compatibility.
 */
interface GraphProjector : Projector<ProjectedRelationship> {

    /**
     * Project a proposition to a graph relationship.
     *
     * @param proposition The proposition to project
     * @param schema The data dictionary defining allowed relationships
     * @return The projection result
     */
    override fun project(
        proposition: Proposition,
        schema: DataDictionary,
    ): ProjectionResult<ProjectedRelationship>

    /**
     * Project multiple propositions, filtering by policy first.
     *
     * @param propositions The propositions to project
     * @param schema The data dictionary defining allowed relationships
     * @return Aggregated projection results
     */
    override fun projectAll(
        propositions: List<Proposition>,
        schema: DataDictionary,
    ): ProjectionResults<ProjectedRelationship> {
        val results = propositions.map { project(it, schema) }
        return ProjectionResults(results)
    }
}

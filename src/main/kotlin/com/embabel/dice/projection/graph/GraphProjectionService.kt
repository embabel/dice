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
import com.embabel.dice.proposition.ProjectionResults
import com.embabel.dice.proposition.Proposition

/**
 * Facade that bundles a [GraphProjector], [GraphRelationshipPersister], and [DataDictionary]
 * to simplify the common project-and-persist workflow.
 *
 * @param graphProjector The projector to use for converting propositions to relationships
 * @param persister The persister to use for storing projected relationships
 * @param schema The data dictionary for relationship validation
 */
class GraphProjectionService(
    private val graphProjector: GraphProjector,
    private val persister: GraphRelationshipPersister,
    private val schema: DataDictionary,
) {

    companion object {
        @JvmStatic
        fun create(
            graphProjector: GraphProjector,
            persister: GraphRelationshipPersister,
            schema: DataDictionary,
        ): GraphProjectionService = GraphProjectionService(graphProjector, persister, schema)
    }

    /**
     * Project propositions to relationships and persist them in one operation.
     *
     * @param propositions The propositions to project and persist
     * @return Pair of projection results and persistence results
     */
    fun projectAndPersist(
        propositions: List<Proposition>,
    ): Pair<ProjectionResults<ProjectedRelationship>, RelationshipPersistenceResult> =
        persister.projectAndPersist(propositions, graphProjector, schema)
}

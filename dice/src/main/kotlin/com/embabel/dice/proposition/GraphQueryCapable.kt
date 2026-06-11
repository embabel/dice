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
package com.embabel.dice.proposition

import com.embabel.dice.common.AuthorityTier
import com.embabel.dice.query.graph.GraphNeighborhood
import com.embabel.dice.query.graph.GraphPath
import com.embabel.dice.query.graph.PropositionLineage

/**
 * Opt-in capability for querying the entity-relationship axis of the knowledge graph: neighbourhoods,
 * paths between entities, and the provenance lineage behind a single proposition.
 *
 * This is distinct from [GraphTraversalCapable], which navigates the proposition abstraction
 * hierarchy (source/derived links). This fragment treats entities as nodes and propositions that
 * mention two resolved entities as edges.
 *
 * All methods have default bodies that return empty results, making this a pure override seam for
 * graph-native backends. The portable facade builds equivalent results store-agnostically and only
 * routes here when the store declares this capability.
 */
interface GraphQueryCapable {

    /**
     * Whether this backend filters graph edges by source authority on its own.
     *
     * Left false by default. While it is false the portable facade keeps authority filtering on its
     * proposition-edge path, so an authority-filtered query still returns correct results even on a
     * backend that knows nothing about authority. Flip it to true only once the authority-aware
     * [neighborhood] and [pathBetween] below genuinely honour their `minAuthority` argument — that is
     * the signal that lets the facade route filtered queries down here instead of falling back.
     */
    val honorsAuthorityFilter: Boolean get() = false

    /**
     * The entity neighbourhood reachable from [entityId] within [depth] hops.
     *
     * @param entityId the opaque entity identifier to centre the neighbourhood on
     * @param depth maximum hop distance (1 = directly connected entities)
     * @return the neighbourhood; an empty neighbourhood by default
     */
    fun neighborhood(entityId: String, depth: Int = 1): GraphNeighborhood =
        GraphNeighborhood.empty(entityId)

    /**
     * The entity neighbourhood reachable from [entityId], keeping only edges whose source authority is
     * at least [minAuthority] (a null floor keeps everything).
     *
     * The facade only calls this when [honorsAuthorityFilter] is true. The default body ignores the
     * floor and delegates to the plain [neighborhood], so a backend that hasn't opted in never returns
     * silently-unfiltered results through this path.
     *
     * @param minAuthority weakest source authority to keep; null keeps all edges
     */
    fun neighborhood(entityId: String, depth: Int, minAuthority: AuthorityTier?): GraphNeighborhood =
        neighborhood(entityId, depth)

    /**
     * The paths connecting [entityIdA] to [entityIdB].
     *
     * Returns an empty list when no path exists — never throws.
     *
     * @return zero or more paths; an empty list by default
     */
    fun pathBetween(entityIdA: String, entityIdB: String): List<GraphPath> = emptyList()

    /**
     * The paths connecting [entityIdA] to [entityIdB], keeping only edges whose source authority is at
     * least [minAuthority] (a null floor keeps everything).
     *
     * The facade only calls this when [honorsAuthorityFilter] is true; the default body ignores the
     * floor and delegates to the plain [pathBetween].
     */
    fun pathBetween(entityIdA: String, entityIdB: String, minAuthority: AuthorityTier?): List<GraphPath> =
        pathBetween(entityIdA, entityIdB)

    /**
     * The lineage behind the proposition with the given id, assembled from its durable fields.
     *
     * @return the lineage, or `null` if no such proposition exists; `null` by default
     */
    fun whyExplain(propositionId: String): PropositionLineage? = null
}

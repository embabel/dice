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
package com.embabel.dice.query.graph

import com.embabel.dice.proposition.Proposition

/**
 * The entity-relationship neighbourhood around a queried entity.
 *
 * A neighbourhood is derived purely from proposition data: when a proposition mentions two
 * resolved entities, that proposition IS the edge between them. The result is therefore
 * store-agnostic — no graph backend is required to compute it.
 *
 * @property entityId the entity the neighbourhood was computed for (opaque string identifier)
 * @property neighbours the related entities reachable from [entityId], each carrying its hop
 *   distance and the propositions on the edge that directly connects it to its predecessor on the
 *   discovery path
 */
data class GraphNeighborhood(
    val entityId: String,
    val neighbours: List<RelatedEntity>,
) {
    companion object {
        /** An empty neighbourhood for a given entity — the graceful-degradation sentinel. */
        @JvmStatic
        fun empty(entityId: String): GraphNeighborhood = GraphNeighborhood(entityId, emptyList())
    }
}

/**
 * A single related entity within a [GraphNeighborhood].
 *
 * @property entityId the related entity's opaque string identifier
 * @property via the propositions on the edge that directly connects this entity to its immediate
 *   predecessor on the discovery path — each proposition mentions this entity and the node one hop
 *   closer to the origin. At [distance] 1 the predecessor IS the queried entity, so these
 *   propositions mention the origin directly; at greater distances they connect this entity to an
 *   intermediate hop, not to the origin. This keeps attribution honest: a far entity never claims a
 *   direct edge to the origin via a proposition that does not mention it.
 * @property distance the number of hops from the queried entity to this entity (1 = directly
 *   related to the origin). Distinguishes direct relations from transitive ones so a multi-hop
 *   relation is not misread as a direct edge.
 */
data class RelatedEntity(
    val entityId: String,
    val via: List<Proposition>,
    val distance: Int = 1,
)

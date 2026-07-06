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

import com.embabel.agent.core.ContextId
import com.embabel.dice.spi.AuthorityTier
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
     * Whether this backend confines entity-axis graph queries to a supplied context on its own.
     *
     * Left false by default. While it is false the portable facade keeps context scoping on its own
     * proposition-edge path for any context-bound query, so a scoped query still returns correct,
     * context-confined results even on a backend that ignores the context. Flip it to true only once
     * the context-aware overloads below genuinely honour their `contextId` argument — that is the
     * signal that lets the facade route scoped queries down here instead of falling back.
     */
    val honorsContextFilter: Boolean get() = false

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
     * The entity neighbourhood reachable from [entityId], confined to [contextId] (a null context is
     * unscoped — exactly the plain [neighborhood] overload).
     *
     * The facade routes a context-bound query here only when [honorsContextFilter] is true; the
     * default body delegates to the unscoped overload, so a backend that hasn't opted in never sees a
     * non-null context here (a `check` guards that invariant). A null context always delegates.
     *
     * @param contextId the context to confine the walk to; null keeps it unscoped
     */
    fun neighborhood(entityId: String, depth: Int, contextId: ContextId?): GraphNeighborhood {
        check(contextId == null || !honorsContextFilter) {
            "honorsContextFilter is true but the context-aware neighborhood() overload was not overridden"
        }
        return neighborhood(entityId, depth)
    }

    /**
     * The entity neighbourhood reachable from [entityId], keeping only edges whose source authority is
     * at least [minAuthority] (a null floor keeps everything).
     *
     * The facade only calls this when [honorsAuthorityFilter] is true. A backend that opts in by
     * setting that flag MUST override this overload — the default body throws rather than silently
     * returning unfiltered results, which would let a backend claim to honor the floor and then drop
     * it on the floor.
     *
     * @param minAuthority weakest source authority to keep; null keeps all edges
     */
    fun neighborhood(entityId: String, depth: Int, minAuthority: AuthorityTier?): GraphNeighborhood {
        check(!honorsAuthorityFilter) {
            "honorsAuthorityFilter is true but the authority-aware neighborhood() overload was not overridden"
        }
        return neighborhood(entityId, depth)
    }

    /**
     * The paths connecting [entityIdA] to [entityIdB].
     *
     * Returns an empty list when no path exists — never throws.
     *
     * @return zero or more paths; an empty list by default
     */
    fun pathBetween(entityIdA: String, entityIdB: String): List<GraphPath> = emptyList()

    /**
     * The paths connecting [entityIdA] to [entityIdB], confined to [contextId] (a null context is
     * unscoped — exactly the plain [pathBetween] overload). Routed here only when
     * [honorsContextFilter] is true; the default body delegates to the unscoped overload.
     *
     * @param contextId the context to confine the walk to; null keeps it unscoped
     */
    fun pathBetween(entityIdA: String, entityIdB: String, contextId: ContextId?): List<GraphPath> {
        check(contextId == null || !honorsContextFilter) {
            "honorsContextFilter is true but the context-aware pathBetween() overload was not overridden"
        }
        return pathBetween(entityIdA, entityIdB)
    }

    /**
     * The paths connecting [entityIdA] to [entityIdB], keeping only edges whose source authority is at
     * least [minAuthority] (a null floor keeps everything).
     *
     * The facade only calls this when [honorsAuthorityFilter] is true. A backend that opts in MUST
     * override this overload — the default body throws rather than silently returning unfiltered
     * paths.
     */
    fun pathBetween(entityIdA: String, entityIdB: String, minAuthority: AuthorityTier?): List<GraphPath> {
        check(!honorsAuthorityFilter) {
            "honorsAuthorityFilter is true but the authority-aware pathBetween() overload was not overridden"
        }
        return pathBetween(entityIdA, entityIdB)
    }

    /**
     * The lineage behind the proposition with the given id, assembled from its durable fields.
     *
     * @return the lineage, or `null` if no such proposition exists; `null` by default
     */
    fun whyExplain(propositionId: String): PropositionLineage? = null

    /**
     * The lineage behind [propositionId], confined to [contextId]: a proposition in another context is
     * treated as absent (null). A null context is unscoped — exactly the plain [whyExplain] overload.
     * Routed here only when [honorsContextFilter] is true; the default body delegates to the unscoped
     * overload.
     *
     * @param contextId the context the proposition must belong to; null keeps it unscoped
     */
    fun whyExplain(propositionId: String, contextId: ContextId?): PropositionLineage? {
        check(contextId == null || !honorsContextFilter) {
            "honorsContextFilter is true but the context-aware whyExplain() overload was not overridden"
        }
        return whyExplain(propositionId)
    }
}

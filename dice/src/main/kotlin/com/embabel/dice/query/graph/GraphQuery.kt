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

import com.embabel.agent.core.ContextId
import com.embabel.dice.spi.AuthorityResolver
import com.embabel.dice.spi.AuthorityTier
import com.embabel.dice.spi.StructuralAuthorityResolver
import com.embabel.dice.proposition.GraphQueryCapable
import com.embabel.dice.proposition.GraphTraversalCapable
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStore

/**
 * Portable facade giving consumers a graph view over proposition data without requiring a graph
 * backend.
 *
 * Entity neighbourhoods and paths are derived store-agnostically from repeated 1-hop proposition
 * queries: a proposition that mentions two resolved entities IS the edge between them. Proposition
 * lineage is assembled from the proposition's own durable fields. When the wrapped store declares
 * [GraphQueryCapable], each operation routes to that native override instead; otherwise the
 * portable default bodies run. Operations never throw for a missing capability — they degrade to
 * empty/typed/null results.
 *
 * Traversal is bounded by [maxDepth] and guarded by a visited set so cyclic data terminates.
 *
 * @param store the backing proposition store
 * @param contextId optional scope; when present, queries are confined to this context
 * @param maxDepth hop ceiling for the default-body neighbourhood/path traversal; must be >= 1
 * @throws IllegalArgumentException if [maxDepth] is less than 1
 */
class GraphQuery(
    private val store: PropositionStore,
    private val contextId: ContextId? = null,
    private val maxDepth: Int = 5,
    private val authorityResolver: AuthorityResolver = StructuralAuthorityResolver(),
) {

    init {
        // Fail fast at construction so the query methods can uphold their never-throws contract:
        // a maxDepth below 1 would otherwise make depth coercion (coerceIn(1, maxDepth)) throw.
        require(maxDepth >= 1) { "maxDepth must be >= 1 but was $maxDepth" }
    }

    /** Whether the wrapped store natively backs entity-axis graph queries. */
    val supportsNativeGraph: Boolean get() = store is GraphQueryCapable

    /**
     * Return a copy of this query with a different authority resolver.
     *
     * Lets callers swap in a custom resolver (e.g. one that trusts a specific connector tier
     * more highly) without reconstructing the whole query from scratch.
     */
    fun withAuthorityResolver(resolver: AuthorityResolver): GraphQuery =
        GraphQuery(store, contextId, maxDepth, resolver)

    /**
     * The entity neighbourhood reachable from [entityId] within [depth] hops.
     *
     * Routes to a native [GraphQueryCapable] store when present; otherwise builds the neighbourhood
     * from bounded BFS over ACTIVE proposition edges.
     *
     * When [minAuthority] is set, the query routes to a native [GraphQueryCapable] store only if that
     * store declares [GraphQueryCapable.honorsAuthorityFilter] — letting a graph backend apply the
     * floor in its own engine. Otherwise the filter runs on the portable proposition-edge path,
     * re-resolving authority from provenance at query time, so the result is correct even on a backend
     * that ignores authority.
     *
     * **Legacy-edge behaviour:** propositions with no provenance entries resolve to
     * [AuthorityTier.UNKNOWN] (ordinal 3, the weakest tier). Because every named [minAuthority]
     * floor has a lower ordinal than [AuthorityTier.UNKNOWN], those edges are always dropped when
     * any [minAuthority] is set — even a floor of [AuthorityTier.DERIVED]. If you need to retain
     * provenance-free edges alongside authority-filtered ones, query without [minAuthority] and
     * filter the result yourself.
     */
    fun neighborhood(entityId: String, depth: Int = 1, minAuthority: AuthorityTier? = null): GraphNeighborhood {
        val native = store as? GraphQueryCapable
        return when {
            native == null -> defaultNeighborhood(entityId, depth, minAuthority)
            minAuthority == null -> native.neighborhood(entityId, depth)
            native.honorsAuthorityFilter -> native.neighborhood(entityId, depth, minAuthority)
            else -> defaultNeighborhood(entityId, depth, minAuthority)
        }
    }

    /**
     * The paths connecting [entityIdA] to [entityIdB]; an empty list when none exists (never throws).
     *
     * Routes to a native [GraphQueryCapable] store when present; otherwise runs bounded, cycle-safe
     * BFS over ACTIVE proposition edges. When [minAuthority] is set, the native adapter is consulted
     * only if it declares [GraphQueryCapable.honorsAuthorityFilter]; otherwise the portable path
     * applies the floor (re-resolving authority from provenance), as in [neighborhood].
     *
     * The return type is a list because a native graph adapter may enumerate multiple paths, but the
     * portable default body returns at most a single path: the first shortest path BFS discovers (an
     * empty list when the targets are unreachable within the hop ceiling). Full multi-path
     * enumeration is left to native adapters; the default body never fabricates additional paths.
     *
     * **Legacy-edge behaviour:** same as [neighborhood] — propositions with no provenance resolve to
     * [AuthorityTier.UNKNOWN] and are dropped by any non-null [minAuthority] floor.
     */
    fun pathBetween(
        entityIdA: String,
        entityIdB: String,
        minAuthority: AuthorityTier? = null,
    ): List<GraphPath> {
        val native = store as? GraphQueryCapable
        return when {
            native == null -> defaultPathBetween(entityIdA, entityIdB, minAuthority)
            minAuthority == null -> native.pathBetween(entityIdA, entityIdB)
            native.honorsAuthorityFilter -> native.pathBetween(entityIdA, entityIdB, minAuthority)
            else -> defaultPathBetween(entityIdA, entityIdB, minAuthority)
        }
    }

    /**
     * The lineage behind the proposition with the given id, or `null` if it does not exist.
     *
     * Routes to a native [GraphQueryCapable] store when present; otherwise assembles the lineage
     * from the proposition's durable fields (grounding, sources, reinforcement, status, temporal).
     */
    fun whyExplain(propositionId: String): PropositionLineage? =
        (store as? GraphQueryCapable)?.whyExplain(propositionId)
            ?: defaultWhyExplain(propositionId)

    // ========================================================================
    // Default (store-agnostic) bodies
    // ========================================================================

    private fun baseQuery(): PropositionQuery =
        (contextId?.let { PropositionQuery.forContextId(it) } ?: PropositionQuery())
            .withStatuses(PropositionStatus.ACTIVE)

    /**
     * One hop from [entityId]: every ACTIVE proposition mentioning it, paired with each OTHER
     * resolved entity it mentions (the connecting edge). When [minAuthority] is set, edge
     * propositions whose resolved source authority is weaker than the floor are dropped.
     * Propositions with no provenance resolve to [AuthorityTier.UNKNOWN] (ordinal 3) and are
     * dropped by any non-null floor, since UNKNOWN has the highest ordinal of all tiers.
     */
    private fun oneHop(entityId: String, minAuthority: AuthorityTier?): List<Pair<String, Proposition>> =
        store.query(baseQuery().withEntityId(entityId))
            .filter { minAuthority == null || authorityResolver.resolve(it).ordinal <= minAuthority.ordinal }
            .flatMap { prop ->
                prop.mentions
                    .mapNotNull { it.resolvedId }
                    .filter { it != entityId }
                    .distinct()
                    .map { other -> other to prop }
            }

    private fun defaultNeighborhood(entityId: String, depth: Int, minAuthority: AuthorityTier?): GraphNeighborhood {
        val bound = depth.coerceIn(1, maxDepth)
        // For each neighbour, record the hop distance at which it was first discovered and only the
        // edges incident on it from its immediate predecessor — so a far entity's `via` is the edge
        // actually connecting it to that predecessor, never an unrelated intermediate edge.
        val edgesByNeighbour = linkedMapOf<String, MutableList<Proposition>>()
        val distanceByNeighbour = linkedMapOf<String, Int>()
        val visited = mutableSetOf(entityId)
        var frontier = setOf(entityId)
        var hops = 0
        // Terminate as soon as the frontier empties: an empty frontier means no further reachable
        // nodes, so continuing would only re-scan already-visited nodes (the redundant-pass defect).
        while (frontier.isNotEmpty() && hops < bound) {
            val currentDistance = hops + 1
            val next = mutableSetOf<String>()
            for (node in frontier) {
                for ((other, prop) in oneHop(node, minAuthority)) {
                    if (other == entityId) continue
                    // Attribute the edge only when `other` is discovered at this distance, i.e. its
                    // predecessor on the path is the current `node`. Edges seen later (a shorter path
                    // already claimed it) belong to a closer hop and must not be re-attributed here.
                    if (other !in visited) {
                        visited.add(other)
                        next.add(other)
                        distanceByNeighbour[other] = currentDistance
                        edgesByNeighbour.getOrPut(other) { mutableListOf() }.add(prop)
                    } else if (distanceByNeighbour[other] == currentDistance) {
                        // Same-distance parallel edge to an already-discovered neighbour: a genuine
                        // additional edge from a predecessor at this hop, so keep it.
                        edgesByNeighbour.getOrPut(other) { mutableListOf() }.add(prop)
                    }
                }
            }
            frontier = next
            hops++
        }
        val neighbours = edgesByNeighbour.map { (id, edges) ->
            RelatedEntity(
                entityId = id,
                via = edges.distinctBy { it.id },
                distance = distanceByNeighbour.getValue(id),
            )
        }
        return GraphNeighborhood(entityId = entityId, neighbours = neighbours)
    }

    private fun defaultPathBetween(entityIdA: String, entityIdB: String, minAuthority: AuthorityTier?): List<GraphPath> {
        if (entityIdA == entityIdB) {
            return listOf(GraphPath(entityIds = listOf(entityIdA), edges = emptyList()))
        }
        // BFS tracking the entity sequence and edge propositions to each frontier node.
        val visited = mutableSetOf(entityIdA)
        var frontier = listOf(GraphPath(entityIds = listOf(entityIdA), edges = emptyList()))
        repeat(maxDepth) {
            val next = mutableListOf<GraphPath>()
            for (path in frontier) {
                val tail = path.entityIds.last()
                for ((other, prop) in oneHop(tail, minAuthority)) {
                    if (other == entityIdB) {
                        return listOf(
                            GraphPath(
                                entityIds = path.entityIds + other,
                                edges = path.edges + prop,
                            ),
                        )
                    }
                    if (other !in visited) {
                        visited.add(other)
                        next.add(
                            GraphPath(
                                entityIds = path.entityIds + other,
                                edges = path.edges + prop,
                            ),
                        )
                    }
                }
            }
            if (next.isEmpty()) return emptyList()
            frontier = next
        }
        return emptyList()
    }

    private fun defaultWhyExplain(propositionId: String): PropositionLineage? {
        val prop = store.findById(propositionId) ?: return null
        // findById is a global lookup; every other query path goes through baseQuery() which is
        // context-scoped. Honor the same scope here so a context-bound GraphQuery can't return
        // lineage for a proposition belonging to another context.
        if (contextId != null && prop.contextId != contextId) return null
        val sources = (store as? GraphTraversalCapable)?.findSources(prop) ?: emptyList()
        return PropositionLineage(
            proposition = prop,
            provenanceEntries = prop.provenanceEntries,
            groundingChunkIds = prop.grounding,
            sources = sources,
            reinforceCount = prop.reinforceCount,
            status = prop.status,
            temporal = prop.temporal,
        )
    }
}

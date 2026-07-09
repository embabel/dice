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
package com.embabel.dice.storage

import com.embabel.agent.core.ContextId

/**
 * Builds the Cypher that walks the *entity projection* of the proposition graph natively in Neo4j.
 *
 * There is no entity node in the store: an entity is the `resolvedId` value that many `:Mention`
 * nodes share, and the "edge" between two entities is an ACTIVE `:Proposition` that mentions both.
 * Because co-referring mentions are separate nodes with no relationship between them, a plain
 * variable-length relationship pattern can't hop entity-to-entity — every hop has to bridge on
 * `resolvedId` equality. So we generate one MATCH segment per hop, each segment re-matching a fresh
 * `:Mention` with the previous hop's `resolvedId`. Neo4j does the whole walk (the resolvedId index
 * drives every bridge); the caller only receives entity ids, distances, and proposition ids back.
 *
 * The hop count is bounded (`1..depth`, capped at [MAX_DEPTH]) so the generated query is finite and
 * cycles can't run away — matching the portable [com.embabel.dice.query.graph.GraphQuery] walk.
 *
 * When a [ContextId] is supplied the walk is confined to that context: every proposition on the path
 * must carry the matching `contextId` (bound as `$ctx`), so a scoped query can't hop across an edge
 * belonging to another context. A null context adds no predicate (unscoped).
 */
internal object GraphProjectionCypher {

    /** Hop ceiling, mirroring `GraphQuery`'s portable `maxDepth` default of 5. */
    const val MAX_DEPTH = 5

    /**
     * Clamp a requested hop count into `1..MAX_DEPTH`. Cypher can't parameterize a variable-length
     * bound, so the generated query always bakes in a literal hop count — this is the one place that
     * count is pinned to a safe range before it goes into the query string.
     */
    fun clampDepth(requested: Int): Int = requested.coerceIn(1, MAX_DEPTH)

    /**
     * Neighbourhood query: every entity reachable from `$origin` within [bound] hops, with its
     * SHORTEST hop distance and the proposition ids on a shortest final hop into it (a valid `via`).
     *
     * Each per-hop branch reports the entity it reaches, the hop count, and the last proposition on
     * that path. Grouping by entity takes the minimum hop count as the distance; a branch's last
     * proposition is a valid `via` exactly when its hop count equals that minimum, because on a
     * shortest simple path the predecessor is necessarily one hop closer to the origin.
     *
     * Returns rows of `{ entityId, distance, viaIds }`.
     */
    fun neighborhood(bound: Int, contextId: ContextId? = null): String {
        val scoped = contextId != null
        val branches = (1..bound).joinToString("\n  UNION ALL\n") { neighborhoodBranch(it, scoped) }
        return """
            |CALL {
            |  $branches
            |}
            |WITH ent, dist, via
            |WITH ent, min(dist) AS distance, collect({ d: dist, via: via }) AS hits
            |RETURN { entityId: ent, distance: distance, viaIds: [h IN hits WHERE h.d = distance | h.via] } AS row
        """.trimMargin()
    }

    private fun neighborhoodBranch(k: Int, scoped: Boolean): String {
        val matchLines = buildList {
            add("MATCH (m0:Mention {resolvedId: \$origin})<-[:HAS_MENTION]-(p1:Proposition)-[:HAS_MENTION]->(x1:Mention)")
            for (i in 2..k) {
                add("MATCH (b${i - 1}:Mention {resolvedId: x${i - 1}.resolvedId})<-[:HAS_MENTION]-" +
                    "(p$i:Proposition)-[:HAS_MENTION]->(x$i:Mention)")
            }
        }
        val conds = buildList {
            for (i in 1..k) add("p$i.status = 'ACTIVE'")
            if (scoped) for (i in 1..k) add("p$i.contextId = \$ctx")
            for (i in 1..k) add("x$i.resolvedId <> \$origin")
            for (i in 1..k) for (j in i + 1..k) add("x$i.resolvedId <> x$j.resolvedId")
        }
        return (matchLines + "WHERE " + conds.joinToString(" AND ") +
                "RETURN DISTINCT x$k.resolvedId AS ent, $k AS dist, p$k.id AS via").joinToString("\n  ")
    }

    /**
     * Shortest-path query between `$a` and `$b` over the entity projection, up to [bound] hops. Emits
     * one candidate per length (the branches are ordered by length, `LIMIT 1` keeps the shortest);
     * empty result means unreachable within the ceiling.
     *
     * Returns a single row `{ len, entityIds, edgeIds }`: the entity sequence start→end and the
     * proposition id connecting each consecutive pair.
     */
    fun pathBetween(bound: Int, contextId: ContextId? = null): String {
        val scoped = contextId != null
        val branches = (1..bound).joinToString("\n  UNION ALL\n") { pathBranch(it, scoped) }
        return """
            |CALL {
            |  $branches
            |}
            |WITH len, entityIds, edgeIds
            |ORDER BY len
            |LIMIT 1
            |RETURN { len: len, entityIds: entityIds, edgeIds: edgeIds } AS row
        """.trimMargin()
    }

    private fun pathBranch(len: Int, scoped: Boolean): String {
        val matchLines = buildList {
            val firstTail = if (len == 1) "y1:Mention {resolvedId: \$b}" else "y1:Mention"
            add("MATCH (a0:Mention {resolvedId: \$a})<-[:HAS_MENTION]-(p1:Proposition)-[:HAS_MENTION]->($firstTail)")
            for (i in 2..len) {
                val tail = if (i == len) "y$i:Mention {resolvedId: \$b}" else "y$i:Mention"
                add("MATCH (c${i - 1}:Mention {resolvedId: y${i - 1}.resolvedId})<-[:HAS_MENTION]-" +
                    "(p$i:Proposition)-[:HAS_MENTION]->($tail)")
            }
        }
        val conds = buildList {
            for (i in 1..len) add("p$i.status = 'ACTIVE'")
            if (scoped) for (i in 1..len) add("p$i.contextId = \$ctx")
            // Intermediate entities must be simple (distinct, and neither endpoint); the final node is
            // pinned to $b in the pattern, the start is $a.
            for (i in 1 until len) {
                add("y$i.resolvedId <> \$a")
                add("y$i.resolvedId <> \$b")
            }
            for (i in 1 until len) for (j in i + 1 until len) add("y$i.resolvedId <> y$j.resolvedId")
        }

        val entityIds = buildString {
            append("[\$a")
            for (i in 1 until len) append(", y$i.resolvedId")
            append(", \$b]")
        }
        val edgeIds = (1..len).joinToString(", ") { "p$it.id" }

        return buildList {
            addAll(matchLines)
            if (conds.isNotEmpty()) add("WHERE " + conds.joinToString(" AND "))
            // Only one candidate per length survives the outer `ORDER BY len LIMIT 1`, and every path of a
            // given length is an equally valid answer, so a hub entity's many same-length simple paths need
            // not all be enumerated: LIMIT 1 keeps one per branch, bounding the work without changing which
            // length wins.
            add("RETURN $len AS len, $entityIds AS entityIds, [$edgeIds] AS edgeIds")
            add("LIMIT 1")
        }.joinToString("\n  ")
    }
}

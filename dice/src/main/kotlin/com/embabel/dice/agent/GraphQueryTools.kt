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
package com.embabel.dice.agent

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.tool.Tool
import com.embabel.dice.query.graph.GraphQuery
import org.slf4j.LoggerFactory

/**
 * LLM-invocable tools for exploring the graph view over proposition data.
 *
 * Wraps the [GraphQuery] facade as `@LlmTool` methods so an LLM agent can walk entity
 * neighbourhoods, find paths between entities, and ask why a proposition holds — without any
 * graph backend, since the facade derives edges from proposition mentions.
 *
 * Scope is inherited from the delegate: the [GraphQuery] is constructed with its own contextId,
 * so every tool call is confined to that context. The tools deliberately expose no contextId
 * argument, so an agent cannot read across context boundaries (mirroring how `Memory` bakes the
 * context in at construction).
 *
 * All calls return read-only, human-readable [Tool.Result.text]. Empty results yield graceful
 * text ("No related entities found ...", "No path found ...") rather than throwing, and an
 * unknown proposition id yields [Tool.Result.error].
 *
 * Usage — registered by the consuming application alongside `Memory`:
 * ```kotlin
 * val graphQuery = GraphQuery(propositionStore, contextId)
 * val tools: List<Tool> = GraphQueryTools.asTools(graphQuery)
 * // Add to the agent's tool set, e.g. together with Memory.forContext(contextId)...
 * ```
 *
 * @param graphQuery the graph-query facade to delegate to; its contextId scope is inherited
 */
class GraphQueryTools(
    private val graphQuery: GraphQuery,
) {

    private val logger = LoggerFactory.getLogger(GraphQueryTools::class.java)

    /**
     * Explore the entities directly or transitively related to a given entity.
     *
     * @param entityId opaque identifier of the entity to explore around
     * @param depth how many relationship hops to expand (clamped to a sane positive bound)
     * @return human-readable neighbourhood, or graceful text when nothing is related
     */
    @LlmTool(
        name = "entity_neighborhood",
        description = "Explore the entities related to a given entity. Returns the related entities and the " +
            "facts (propositions) that connect them. Provide an entity id and optionally a depth (number of " +
            "relationship hops to expand).",
    )
    fun entityNeighborhood(
        @LlmTool.Param(description = "The id of the entity to explore the neighbourhood of")
        entityId: String,
        @LlmTool.Param(
            description = "Number of relationship hops to expand (defaults to 1; clamped to a small bound)",
            required = false,
        )
        depth: Int = 1,
    ): Tool.Result {
        val safeDepth = clampDepth(depth)
        logger.info("Entity neighbourhood for {} (depth {})", entityId, safeDepth)
        val neighborhood = graphQuery.neighborhood(entityId, safeDepth)
        if (neighborhood.neighbours.isEmpty()) {
            return Tool.Result.text("No related entities found for $entityId.")
        }
        val text = buildString {
            appendLine("Entities related to $entityId (${neighborhood.neighbours.size}):")
            neighborhood.neighbours.forEach { related ->
                val hopLabel = if (related.distance == 1) "direct" else "${related.distance} hops"
                appendLine("- ${related.entityId} ($hopLabel)")
                related.via.forEach { prop ->
                    appendLine("    via: ${prop.text}")
                }
            }
        }.trimEnd()
        return Tool.Result.text(text)
    }

    /**
     * Find how two entities are connected, as a chain of intermediate entities and facts.
     *
     * Over the portable facade this returns at most a single shortest path; the `(N)` count in the
     * output is therefore 0 or 1. A native graph adapter may enumerate multiple paths, in which case
     * the same formatting renders them all.
     *
     * @param entityIdA opaque identifier of the start entity
     * @param entityIdB opaque identifier of the end entity
     * @return human-readable path(s), or graceful text when no path exists
     */
    @LlmTool(
        name = "path_between",
        description = "Find how two entities are connected. Returns the chain of entities and the facts " +
            "(propositions) linking them, or reports that no path exists. Provide the two entity ids.",
    )
    fun pathBetween(
        @LlmTool.Param(description = "The id of the entity to start from")
        entityIdA: String,
        @LlmTool.Param(description = "The id of the entity to reach")
        entityIdB: String,
    ): Tool.Result {
        logger.info("Path between {} and {}", entityIdA, entityIdB)
        val paths = graphQuery.pathBetween(entityIdA, entityIdB)
        if (paths.isEmpty()) {
            return Tool.Result.text("No path found between $entityIdA and $entityIdB.")
        }
        val text = buildString {
            appendLine("Path(s) from $entityIdA to $entityIdB (${paths.size}):")
            paths.forEach { path ->
                appendLine("- ${path.entityIds.joinToString(" -> ")}")
                path.edges.forEach { prop ->
                    appendLine("    via: ${prop.text}")
                }
            }
        }.trimEnd()
        return Tool.Result.text(text)
    }

    /**
     * Explain why a stored fact holds: its grounding, sources, reinforcement, status and validity.
     *
     * @param propositionId opaque identifier of the proposition to explain
     * @return human-readable lineage, or [Tool.Result.error] when the id is unknown
     */
    @LlmTool(
        name = "why_explain",
        description = "Explain why a stored fact (proposition) holds: its source grounding, the facts it was " +
            "abstracted from, how often it has been reinforced, its current status, and its temporal validity. " +
            "Provide the proposition id.",
    )
    fun whyExplain(
        @LlmTool.Param(description = "The id of the proposition to explain")
        propositionId: String,
    ): Tool.Result {
        logger.info("Why-explain for proposition {}", propositionId)
        val lineage = graphQuery.whyExplain(propositionId)
            ?: return Tool.Result.error("Unknown proposition: $propositionId")
        val text = buildString {
            appendLine("Lineage for proposition $propositionId:")
            appendLine("- statement: ${lineage.proposition.text}")
            appendLine("- status: ${lineage.status}")
            appendLine("- reinforced: ${lineage.reinforceCount} time(s)")
            if (lineage.provenanceEntries.isNotEmpty()) {
                appendLine("- grounding entries: ${lineage.provenanceEntries.size}")
            }
            if (lineage.groundingChunkIds.isNotEmpty()) {
                appendLine("- grounding chunks: ${lineage.groundingChunkIds.joinToString(", ")}")
            }
            appendLine("- abstracted from ${lineage.sources.size} source proposition(s)")
            lineage.temporal?.let { appendLine("- temporal validity: $it") }
        }.trimEnd()
        return Tool.Result.text(text)
    }

    private fun clampDepth(depth: Int): Int = depth.coerceIn(MIN_DEPTH, MAX_DEPTH)

    companion object {

        /** Lower bound for a requested traversal depth. */
        private const val MIN_DEPTH = 1

        /** Upper bound for a requested traversal depth, guarding against runaway expansion. */
        private const val MAX_DEPTH = 5

        /**
         * Create [Tool] instances from a [GraphQuery] facade.
         *
         * The returned tools inherit the facade's contextId scope and can be registered with an
         * agent's tool set, e.g. alongside `Memory`.
         *
         * ```kotlin
         * val tools = GraphQueryTools.asTools(graphQuery)
         * ```
         */
        @JvmStatic
        fun asTools(graphQuery: GraphQuery): List<Tool> = Tool.fromInstance(GraphQueryTools(graphQuery))
    }
}

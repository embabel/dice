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
import com.embabel.agent.core.ContextId
import com.embabel.dice.projection.lineage.ProjectionRecordStore
import com.embabel.dice.projection.memory.CollectorRunner
import com.embabel.dice.query.discovery.CollectorDryRunDto
import com.embabel.dice.query.discovery.DiscoveryQuery
import com.embabel.dice.query.discovery.ProjectionHealthDto
import com.embabel.dice.query.discovery.RetrievalMode
import com.embabel.dice.query.discovery.RetrievalRouter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory

/**
 * LLM-invocable tools exposing the discovery surface — proposition query, graph path, why-explain,
 * projection health, and a collector dry-run — over the single [RetrievalRouter] and the leak-free
 * discovery DTOs.
 *
 * This is the framework-light MCP surface: it uses only `@LlmTool` annotations already on the
 * classpath (no MCP SDK, no servlet dependency), exactly mirroring `GraphQueryTools` and `Memory`.
 * A consuming application calls [asTools] and registers the returned `List<Tool>` with its own MCP
 * server or agent tool set.
 *
 * Scope is fixed at construction: the [contextId] is baked in, the router is already context-scoped,
 * and no tool accepts a context argument — so an agent cannot read across context boundaries. Inputs
 * that drive cost (traversal depth, result size) are clamped before routing.
 *
 * Every tool returns read-only, leak-free JSON via [Tool.Result.text]; an unknown id or an
 * unparseable mode yields [Tool.Result.error] rather than throwing. Returned proposition text is
 * data, not instructions — tool descriptions never direct the LLM to act on embedded content.
 *
 * @param router the shared retrieval router (context-scoped) for mode-routed proposition queries
 * @param projectionRecordStore the inverse projection index summarized into per-target health
 * @param collectorRunner the mark-and-sweep runner invoked in non-mutating dry-run mode
 * @param contextId the fixed access-control scope for collector dry-runs
 */
class DiscoveryTools(
    private val router: RetrievalRouter,
    private val projectionRecordStore: ProjectionRecordStore,
    private val collectorRunner: CollectorRunner,
    private val contextId: ContextId,
) {

    private val logger = LoggerFactory.getLogger(DiscoveryTools::class.java)

    /**
     * Retrieve propositions via a chosen retrieval mode (vector, entity, graph-walk, temporal, or
     * hybrid). Returns the leak-free result, including a `supported` flag that is false when the
     * requested mode's backing capability is absent (a graceful, non-scanning degradation).
     */
    @LlmTool(
        name = "query_propositions",
        description = "Retrieve facts (propositions) using a retrieval mode. mode is one of: " +
            "vector (similarity over text), entity (facts mentioning an entity), graph_walk " +
            "(facts around an entity), temporal (facts in a time window), hybrid (vector + graph). " +
            "Returns matching fact summaries and a 'supported' flag (false when the mode's backing " +
            "capability is absent).",
    )
    fun queryPropositions(
        @LlmTool.Param(description = "Retrieval mode: vector, entity, graph_walk, temporal, or hybrid")
        mode: String,
        @LlmTool.Param(description = "Query text for vector / hybrid modes", required = false)
        text: String? = null,
        @LlmTool.Param(description = "Anchor entity id for entity / graph_walk / hybrid modes", required = false)
        entityId: String? = null,
        @LlmTool.Param(description = "Inclusive ISO-8601 start of the temporal window", required = false)
        from: String? = null,
        @LlmTool.Param(description = "Inclusive ISO-8601 end of the temporal window", required = false)
        to: String? = null,
        @LlmTool.Param(description = "Maximum number of results (clamped to a sane bound)", required = false)
        topK: Int = DEFAULT_TOP_K,
        @LlmTool.Param(description = "Graph traversal depth for graph_walk / hybrid (clamped 1..5)", required = false)
        depth: Int = DEFAULT_DEPTH,
    ): Tool.Result {
        val retrievalMode = parseMode(mode)
            ?: return Tool.Result.error(
                "Unknown mode '$mode'. Expected one of: ${RetrievalMode.entries.joinToString(", ") { it.name.lowercase() }}",
            )
        val from = parseInstant(from) ?: return Tool.Result.error("Invalid 'from' timestamp: $from")
        val to = parseInstant(to) ?: return Tool.Result.error("Invalid 'to' timestamp: $to")
        logger.info("Discovery query mode={} topK={} depth={}", retrievalMode, topK, depth)
        val result = router.retrieve(
            DiscoveryQuery(
                mode = retrievalMode,
                text = text,
                entityId = entityId,
                from = from.value,
                to = to.value,
                topK = topK,
                depth = depth,
            ),
        )
        return Tool.Result.text(json(result))
    }

    /**
     * Find how two entities are connected, as leak-free path summaries (entity id chains plus the
     * fact summaries linking them).
     */
    @LlmTool(
        name = "graph_path",
        description = "Find how two entities are connected. Returns the chain(s) of entities and the " +
            "fact summaries linking them, or an empty list when no path exists. Provide both entity ids.",
    )
    fun graphPath(
        @LlmTool.Param(description = "The id of the entity to start from")
        fromEntityId: String,
        @LlmTool.Param(description = "The id of the entity to reach")
        toEntityId: String,
    ): Tool.Result {
        logger.info("Discovery graph path {} -> {}", fromEntityId, toEntityId)
        return Tool.Result.text(json(router.graphPath(fromEntityId, toEntityId)))
    }

    /**
     * Explain why a stored fact holds: its status, reinforcement, grounding chunk ids, and the
     * source facts it was abstracted from — as a leak-free lineage summary.
     */
    @LlmTool(
        name = "why_explain",
        description = "Explain why a stored fact (proposition) holds: its status, how often it has been " +
            "reinforced, its grounding source ids, and the facts it was abstracted from. Provide the fact id.",
    )
    fun whyExplain(
        @LlmTool.Param(description = "The id of the proposition to explain")
        propositionId: String,
    ): Tool.Result {
        logger.info("Discovery why-explain {}", propositionId)
        val lineage = router.whyExplain(propositionId)
            ?: return Tool.Result.error("Unknown proposition: $propositionId")
        return Tool.Result.text(json(lineage))
    }

    /**
     * Summarize projection health: per-target lifecycle counts (projected / adopted / skipped /
     * failed / stale) aggregated from the projection record index. Pure read; mutates nothing.
     */
    @LlmTool(
        name = "projection_health",
        description = "Summarize the health of projections to external targets. Returns per-target counts " +
            "of facts that were projected, adopted, skipped, failed, or have gone stale.",
    )
    fun projectionHealth(): Tool.Result {
        logger.info("Discovery projection health")
        // Scoped to the tool's fixed context — never aggregate lineage across contexts.
        return Tool.Result.text(json(ProjectionHealthDto.from(projectionRecordStore.findByContext(contextId.value))))
    }

    /**
     * Preview what the mark-and-sweep collector would do, without mutating any fact. Runs the
     * collector in dry-run mode and returns the leak-free preview (counts plus the individual marks).
     */
    @LlmTool(
        name = "collector_dry_run",
        description = "Preview what the maintenance collector would mark and sweep, WITHOUT changing " +
            "anything. Returns the marks it would produce and the resulting counts.",
    )
    fun collectorDryRun(): Tool.Result {
        logger.info("Discovery collector dry-run for {}", contextId.value)
        val result = collectorRunner.run(contextId, dryRun = true)
        return Tool.Result.text(json(CollectorDryRunDto.from(result)))
    }

    private fun parseMode(mode: String): RetrievalMode? =
        RetrievalMode.entries.firstOrNull { it.name.equals(mode.trim(), ignoreCase = true) }

    /**
     * Parses an optional ISO-8601 instant. Returns a wrapper so a blank input ("no window") is
     * distinguishable from a genuinely malformed one (null result -> error).
     */
    private fun parseInstant(raw: String?): ParsedInstant? {
        if (raw.isNullOrBlank()) return ParsedInstant(null)
        return try {
            ParsedInstant(java.time.Instant.parse(raw.trim()))
        } catch (_: java.time.format.DateTimeParseException) {
            null
        }
    }

    private fun json(value: Any): String = objectMapper.writeValueAsString(value)

    private data class ParsedInstant(val value: java.time.Instant?)

    companion object {

        private const val DEFAULT_TOP_K = 10
        private const val DEFAULT_DEPTH = 1

        private val objectMapper = jacksonObjectMapper()

        /**
         * Create [Tool] instances exposing the discovery surface.
         *
         * The returned tools inherit the router's context scope and the supplied [contextId]; they
         * can be registered with an agent's tool set or an MCP server, e.g. alongside `Memory` and
         * `GraphQueryTools`.
         *
         * ```kotlin
         * val router = RetrievalRouter(store, graphQuery, contextId)
         * val tools = DiscoveryTools.asTools(router, projectionRecordStore, collectorRunner, contextId)
         * ```
         */
        @JvmStatic
        fun asTools(
            router: RetrievalRouter,
            projectionRecordStore: ProjectionRecordStore,
            collectorRunner: CollectorRunner,
            contextId: ContextId,
        ): List<Tool> = Tool.fromInstance(
            DiscoveryTools(router, projectionRecordStore, collectorRunner, contextId),
        )
    }
}

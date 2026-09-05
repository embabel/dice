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
package com.embabel.dice.mcp

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import org.slf4j.LoggerFactory

/**
 * Simplified DICE tools for external MCP clients.
 *
 * In-process [com.embabel.dice.agent.Memory] and [com.embabel.dice.agent.DiscoveryTools] bake
 * [ContextId] in at construction, so an agent cannot name another tenant. MCP clients are
 * stateless and may serve many sessions, so every tool takes an explicit `context_id`. That is
 * a caller-supplied scope, not a credential: it keeps one call from crossing contexts, and
 * authorization is the host MCP server's job. Recall and list start from
 * [com.embabel.dice.proposition.PropositionQuery.forContextId]; get collapses a missing id and
 * a foreign id into one answer so the tool cannot confirm that an id it does not own exists.
 *
 * Rod's #5: expose tools with simplified parameters. This class is that surface: recall, list,
 * store, get. Extraction and discovery stay on the existing in-process `asTools()` path.
 *
 * Export through embabel-agent's [com.embabel.agent.mcpserver.McpToolExport], or add
 * `dice-mcp-autoconfigure` with `embabel.dice.mcp.enabled=true`.
 *
 * @param repository proposition store (required)
 * @param minConfidence minimum effective confidence for recall/list
 * @param defaultLimit default result cap for recall/list
 */
class DiceMcpTools(
    private val repository: PropositionRepository,
    private val minConfidence: Double = DEFAULT_MIN_CONFIDENCE,
    private val defaultLimit: Int = DEFAULT_LIMIT,
) {

    private val logger = LoggerFactory.getLogger(DiceMcpTools::class.java)

    init {
        require(minConfidence in 0.0..1.0) { "minConfidence must be between 0.0 and 1.0" }
        require(defaultLimit in 1..MAX_LIMIT) { "defaultLimit must be between 1 and $MAX_LIMIT" }
    }

    /**
     * Run a tool body, keeping store and driver detail away from the caller.
     *
     * [IllegalArgumentException] is ours — a blank `context_id`, a blank `text` — so it passes
     * through and tells the model what to fix. Anything else came from the store: the cause is
     * logged here and the exception thrown on has **no cause attached**, so a stack trace
     * serialized back by the MCP layer cannot carry Cypher, hostnames, or credentials to an
     * external client. Same rule `DiscoveryController` applies to its own 500s.
     */
    private fun guarded(tool: String, block: () -> String): String =
        try {
            block()
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            logger.error("MCP tool {} failed", tool, e)
            throw IllegalStateException("$tool failed: the knowledge store is unavailable")
        }

    /**
     * Hybrid semantic + keyword recall over stored propositions in a context.
     */
    @LlmTool(
        name = RECALL,
        description = "Search stored knowledge (propositions) in a DICE context. " +
            "Pass a natural-language query to run hybrid semantic + keyword retrieval. " +
            "Omit query to list memories ordered by confidence.",
    )
    fun recall(
        @LlmTool.Param(description = "Context to search within (session, user, or tenant id).")
        contextId: String,
        @LlmTool.Param(description = "What to recall, in natural language. Omit to list all memories.")
        query: String? = null,
        @LlmTool.Param(description = "Maximum results (default 10, capped at 100).")
        limit: Int = defaultLimit,
    ): String = guarded(RECALL) {
        DiceMcpSupport.recall(
            repository = repository,
            contextId = contextId,
            query = query,
            limit = limit.coerceIn(1, MAX_LIMIT),
            minConfidence = minConfidence,
        )
    }

    /**
     * List active propositions for a context, ordered by effective confidence.
     */
    @LlmTool(
        name = LIST,
        description = "List stored propositions for a DICE context, ordered by effective confidence.",
    )
    fun listMemories(
        @LlmTool.Param(description = "Context to list.")
        contextId: String,
        @LlmTool.Param(description = "Maximum results (default 10, capped at 100).")
        limit: Int = defaultLimit,
    ): String = guarded(LIST) {
        val scoped = DiceMcpSupport.requireContextId(contextId)
        val query = DiceMcpSupport.baseQuery(scoped, minConfidence)
            .orderedByEffectiveConfidence()
            .withLimit(limit.coerceIn(1, MAX_LIMIT))
        val propositions = repository.query(query)
        if (propositions.isEmpty()) {
            "No memories in context '$scoped'."
        } else {
            DiceMcpSupport.render(
                "Found ${propositions.size} memories in context '$scoped':",
                propositions,
            )
        }
    }

    /**
     * Store a proposition directly without running the extraction pipeline.
     */
    @LlmTool(
        name = STORE,
        description = "Store a natural-language proposition in a DICE context without running extraction.",
    )
    fun storeMemory(
        @LlmTool.Param(description = "Context to store into.")
        contextId: String,
        @LlmTool.Param(description = "The fact to remember, in natural language.")
        text: String,
        @LlmTool.Param(description = "Confidence between 0 and 1 (default 0.8).")
        confidence: Double = 0.8,
    ): String = guarded(STORE) {
        val scoped = DiceMcpSupport.requireContextId(contextId)
        require(text.isNotBlank()) { "text must not be blank" }
        val proposition = Proposition(
            contextId = ContextId(scoped),
            text = text.trim(),
            mentions = emptyList(),
            confidence = confidence.coerceIn(0.0, 1.0),
        )
        val saved = repository.save(proposition)
        "Stored proposition ${saved.id}: ${saved.text}"
    }

    /**
     * Fetch a single proposition by id within a context.
     */
    @LlmTool(
        name = GET,
        description = "Get one stored proposition by id within a DICE context.",
    )
    fun getProposition(
        @LlmTool.Param(description = "Context the proposition belongs to.")
        contextId: String,
        @LlmTool.Param(description = "Proposition id returned by recall, list, or store.")
        propositionId: String,
    ): String = guarded(GET) {
        val scoped = DiceMcpSupport.requireContextId(contextId)
        val id = propositionId.trim()
        require(id.isNotBlank()) { "proposition_id must not be blank" }
        val proposition = repository.findById(id)
        // One answer for "no such id" and "that id lives in another context". Distinguishing
        // them would confirm to a caller that an id it does not own exists somewhere, and
        // MemoryController collapses both into a 404 for exactly that reason.
        if (proposition == null || proposition.contextIdValue != scoped) {
            "No proposition with id '$id' in context '$scoped'."
        } else {
            DiceMcpSupport.formatProposition(proposition)
        }
    }

    companion object {
        const val RECALL = "dice_recall"
        const val LIST = "dice_list"
        const val STORE = "dice_store"
        const val GET = "dice_get"

        val TOOL_NAMES: Set<String> = setOf(RECALL, LIST, STORE, GET)

        const val DEFAULT_MIN_CONFIDENCE = 0.5
        const val DEFAULT_LIMIT = 10

        /**
         * Hard ceiling on `limit` for recall/list. An MCP caller is external and its limit
         * arrives as whatever number the client model wrote, so the bound is enforced here
         * rather than trusted. Mirrors the `MAX_TOP_K` that
         * [com.embabel.dice.query.discovery.RetrievalRouter] clamps to — that one is private,
         * so the value is duplicated rather than referenced.
         */
        const val MAX_LIMIT = 100

        /**
         * Create [Tool] instances for agent runtimes that register tools by hand
         * rather than through `dice-mcp-autoconfigure`.
         */
        @JvmStatic
        fun asTools(tools: DiceMcpTools): List<Tool> = Tool.fromInstance(tools)
    }
}

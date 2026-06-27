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

/**
 * Simplified DICE tools for external MCP clients.
 *
 * In-process [com.embabel.dice.agent.Memory] and [com.embabel.dice.agent.DiscoveryTools] bake
 * [ContextId] in at construction so an agent cannot cross a tenant boundary. MCP clients are
 * stateless and may serve many sessions, so every tool takes an explicit `context_id` — the same
 * isolation [com.embabel.dice.incremental.ChunkHistoryStore] gained in #6 / #33.
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

    init {
        require(minConfidence in 0.0..1.0) { "minConfidence must be between 0.0 and 1.0" }
        require(defaultLimit > 0) { "defaultLimit must be positive" }
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
        @LlmTool.Param(description = "Maximum results (default 10).")
        limit: Int = defaultLimit,
    ): String = DiceMcpSupport.recall(
        repository = repository,
        contextId = contextId,
        query = query,
        limit = limit.coerceAtLeast(1),
        minConfidence = minConfidence,
    )

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
        @LlmTool.Param(description = "Maximum results (default 10).")
        limit: Int = defaultLimit,
    ): String {
        val scoped = DiceMcpSupport.requireContextId(contextId)
        val query = DiceMcpSupport.baseQuery(scoped, minConfidence)
            .orderedByEffectiveConfidence()
            .withLimit(limit.coerceAtLeast(1))
        val propositions = repository.query(query)
        if (propositions.isEmpty()) {
            return "No memories in context '$scoped'."
        }
        return buildString {
            appendLine("Found ${propositions.size} memories in context '$scoped':")
            propositions.forEachIndexed { index, proposition ->
                appendLine("${index + 1}. ${DiceMcpSupport.formatProposition(proposition)}")
            }
        }.trimEnd()
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
    ): String {
        val scoped = DiceMcpSupport.requireContextId(contextId)
        require(text.isNotBlank()) { "text must not be blank" }
        val proposition = Proposition(
            contextId = ContextId(scoped),
            text = text.trim(),
            mentions = emptyList(),
            confidence = confidence.coerceIn(0.0, 1.0),
        )
        val saved = repository.save(proposition)
        return "Stored proposition ${saved.id}: ${saved.text}"
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
    ): String {
        val scoped = DiceMcpSupport.requireContextId(contextId)
        require(propositionId.isNotBlank()) { "proposition_id must not be blank" }
        val proposition = repository.findById(propositionId)
            ?: return "No proposition with id '$propositionId'."
        if (proposition.contextIdValue != scoped) {
            return "Proposition '$propositionId' is not in context '$scoped'."
        }
        return DiceMcpSupport.formatProposition(proposition)
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
         * Create [Tool] instances for agent runtimes that register tools by hand
         * rather than through `dice-mcp-autoconfigure`.
         */
        @JvmStatic
        fun asTools(tools: DiceMcpTools): List<Tool> = Tool.fromInstance(tools)
    }
}

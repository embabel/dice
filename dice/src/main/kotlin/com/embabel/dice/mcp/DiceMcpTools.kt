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
import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.SchemaRegistry
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.entity.EntityAssertion
import com.embabel.dice.entity.EntityAssertionRequest
import com.embabel.dice.entity.EntityResolutionService
import com.embabel.dice.entity.EntityResolutionTools
import com.embabel.dice.pipeline.PropositionPipeline
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.revision.RevisionResult

/**
 * MCP-oriented tools for DICE with deliberately simple parameters.
 *
 * Each tool takes a `context_id` so external MCP clients (Claude Desktop, Cursor, etc.)
 * can scope knowledge without baking context into server configuration. Export these
 * through embabel-agent's [com.embabel.agent.mcpserver.McpToolExport]:
 *
 * ```kotlin
 * @Bean
 * fun diceMcpTools(repository: PropositionRepository): DiceMcpTools =
 *     DiceMcpTools(repository)
 *
 * @Bean
 * fun diceMcpExport(tools: DiceMcpTools): McpToolExport =
 *     McpToolExport.fromToolObject(ToolObject(objects = listOf(tools)))
 * ```
 *
 * Or add `dice-mcp-autoconfigure` and `embabel-agent-starter-mcpserver` to auto-wire both.
 *
 * @param repository proposition store (required)
 * @param pipeline optional extraction pipeline for [extract]
 * @param entityResolver required by [extract] when a pipeline is configured
 * @param schemaRegistry required by [extract] when a pipeline is configured
 * @param entityResolutionService optional service backing [assertEntities]
 * @param minConfidence minimum effective confidence for recall/list
 * @param defaultLimit default result cap for recall/list
 */
class DiceMcpTools(
    private val repository: PropositionRepository,
    private val pipeline: PropositionPipeline? = null,
    private val entityResolver: EntityResolver? = null,
    private val schemaRegistry: SchemaRegistry? = null,
    private val entityResolutionService: EntityResolutionService? = null,
    private val minConfidence: Double = DEFAULT_MIN_CONFIDENCE,
    private val defaultLimit: Int = DEFAULT_LIMIT,
) {

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
        val query = DiceMcpSupport.baseQuery(contextId, minConfidence)
            .orderedByEffectiveConfidence()
            .withLimit(limit.coerceAtLeast(1))
        val propositions = repository.query(query)
        if (propositions.isEmpty()) {
            return "No memories in context '$contextId'."
        }
        return buildString {
            appendLine("Found ${propositions.size} memories in context '$contextId':")
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
        require(text.isNotBlank()) { "text must not be blank" }
        val proposition = Proposition(
            contextId = ContextId(contextId),
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
        val proposition = repository.findById(propositionId)
            ?: return "No proposition with id '$propositionId'."
        if (proposition.contextIdValue != contextId) {
            return "Proposition '$propositionId' is not in context '$contextId'."
        }
        return DiceMcpSupport.formatProposition(proposition)
    }

    /**
     * Run the proposition extraction pipeline on raw text.
     */
    @LlmTool(
        name = EXTRACT,
        description = "Extract propositions and resolve entities from raw text into a DICE context. " +
            "Requires a configured PropositionPipeline on the server.",
    )
    fun extract(
        @LlmTool.Param(description = "Context to extract into.")
        contextId: String,
        @LlmTool.Param(description = "Source text to analyse.")
        text: String,
        @LlmTool.Param(description = "Optional source id for provenance (defaults to 'mcp-extract').")
        sourceId: String? = null,
    ): String {
        val activePipeline = pipeline
            ?: error("dice_extract is not available: no PropositionPipeline configured")
        val resolver = entityResolver
            ?: error("dice_extract is not available: no EntityResolver configured")
        val schemas = schemaRegistry
            ?: error("dice_extract is not available: no SchemaRegistry configured")
        require(text.isNotBlank()) { "text must not be blank" }

        val chunk = Chunk.create(text = text.trim(), parentId = sourceId ?: "mcp-extract")
        val context = SourceAnalysisContext(
            schema = schemas.getOrDefault(null),
            entityResolver = resolver,
            contextId = ContextId(contextId),
        )
        val result = activePipeline.processChunk(chunk, context)
        result.propositionsToPersist().forEach { repository.save(it) }

        val propositions = result.propositions
        if (propositions.isEmpty()) {
            return "No propositions extracted from text in context '$contextId'."
        }
        return buildString {
            appendLine("Extracted ${propositions.size} propositions in context '$contextId':")
            propositions.forEachIndexed { index, proposition ->
                appendLine("${index + 1}. ${DiceMcpSupport.formatProposition(proposition)}")
            }
            if (result.revisionResults.isNotEmpty()) {
                appendLine()
                append("Revision: ")
                append("created=${result.revisionResults.count { it is RevisionResult.New }}, ")
                append("merged=${result.revisionResults.count { it is RevisionResult.Merged }}, ")
                append("reinforced=${result.revisionResults.count { it is RevisionResult.Reinforced }}, ")
                append("contradicted=${result.revisionResults.count { it is RevisionResult.Contradicted }}")
            }
        }.trimEnd()
    }

    /**
     * Assert structured entities (and optional relationships) into the knowledge graph.
     */
    @LlmTool(
        name = ASSERT_ENTITIES,
        description = "Assert entities into the knowledge graph with automatic resolution. " +
            "Requires EntityResolutionService on the server.",
    )
    fun assertEntities(
        @LlmTool.Param(description = "Entities to assert. Each needs a name; labels, description, and properties are optional.")
        entities: List<EntityAssertion>,
    ): String {
        val service = entityResolutionService
            ?: error("dice_assert_entities is not available: no EntityResolutionService configured")
        require(entities.isNotEmpty()) { "entities must not be empty" }
        val result = service.resolve(EntityAssertionRequest(entities = entities))
        return buildString {
            appendLine("Asserted ${result.resolutions.size} entities:")
            result.resolutions.forEach { resolution ->
                appendLine("- ${resolution.name}: ${resolution.resolution} → ${resolution.entityId}")
            }
        }.trimEnd()
    }

    /** Whether [extract] can be exported for the current wiring. */
    fun pipelineAvailable(): Boolean =
        pipeline != null && entityResolver != null && schemaRegistry != null

    /** Whether [assertEntities] can be exported for the current wiring. */
    fun entityResolutionAvailable(): Boolean = entityResolutionService != null

    companion object {
        const val RECALL = "dice_recall"
        const val LIST = "dice_list"
        const val STORE = "dice_store"
        const val GET = "dice_get"
        const val EXTRACT = "dice_extract"
        const val ASSERT_ENTITIES = "dice_assert_entities"

        const val DEFAULT_MIN_CONFIDENCE = 0.5
        const val DEFAULT_LIMIT = 10

        /**
         * Create [Tool] instances for agent runtimes (non-MCP).
         */
        @JvmStatic
        fun asTools(tools: DiceMcpTools): List<Tool> = Tool.fromInstance(tools)

        /**
         * Create [Tool] instances including entity-resolution helpers when configured.
         */
        @JvmStatic
        fun asToolsWithEntityResolution(
            tools: DiceMcpTools,
            entityResolutionService: EntityResolutionService,
        ): List<Tool> = asTools(tools) + EntityResolutionTools.asTools(entityResolutionService)
    }
}

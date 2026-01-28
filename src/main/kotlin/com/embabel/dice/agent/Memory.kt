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

import com.embabel.agent.api.common.LlmReference
import com.embabel.agent.api.tool.MatryoshkaTool
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.ContextId
import com.embabel.agent.spi.support.DelegatingTool
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.common.KnowledgeType
import com.embabel.dice.projection.memory.MemoryProjector
import com.embabel.dice.projection.memory.support.DefaultMemoryProjector
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.util.function.UnaryOperator

/**
 * LlmReference providing agent memory search within a context.
 *
 * Provides access to conversation memory through a MatryoshkaTool with three operations:
 * - **searchByTopic**: Vector similarity search for relevant memories
 * - **searchRecent**: Temporal ordering to recall recent memories
 * - **searchByType**: Find memories by knowledge type (facts, events, preferences)
 *
 * All search operations support optional `type` filtering to narrow results to specific
 * knowledge types: semantic (facts), episodic (events), procedural (preferences), or working (session).
 *
 * The context is baked in at construction time, ensuring the LLM
 * can only access memories within the authorized context.
 *
 * The description dynamically reflects how many memories are available.
 *
 * Example usage from Kotlin:
 * ```kotlin
 * val memory = Memory.forContext(contextId)
 *     .withRepository(propositionRepository)
 *     .withProjector(DefaultMemoryProjector.withKnowledgeTypeClassifier(myClassifier))
 *     .withMinConfidence(0.6)
 *     .withEagerQuery { it.orderedByEffectiveConfidence().withLimit(5) }
 *
 * ai.withReference(memory).respond(...)
 * ```
 *
 * Example usage from Java:
 * ```java
 * LlmReference memory = Memory.forContext("user-session-123")
 *     .withRepository(propositionRepository)
 *     .withProjector(DefaultMemoryProjector.withKnowledgeTypeClassifier(myClassifier))
 *     .withMinConfidence(0.6)
 *     .withEagerQuery(query -> query.orderedByEffectiveConfidence().withLimit(5));
 *
 * ai.withReference(memory).respond(...);
 * ```
 *
 * @param contextId The context to search within
 * @param repository The proposition repository to query
 * @param projector Projector for categorizing memories by knowledge type
 * @param minConfidence Minimum confidence threshold for memories
 * @param defaultLimit Default limit for search results
 * @param topic Description of the memories we can retrieve.
 * Should complete with the form "memories about <topic>".
 * @param useWhen Description of when to use the memory tools.
 * @param eagerQuery Optional query transformer to eagerly load key memories into the description.
 * When set, the description will include memories fetched using this query, making them
 * immediately available to the LLM without requiring a tool call.
 */
data class Memory @JvmOverloads constructor(
    private val contextId: ContextId,
    private val repository: PropositionRepository,
    private val projector: MemoryProjector = DefaultMemoryProjector.DEFAULT,
    private val minConfidence: Double = DEFAULT_MIN_CONFIDENCE,
    private val defaultLimit: Int = DEFAULT_LIMIT,
    private val topic: String = "the user & context",
    private val useWhen: String = "whenever you need to recall information about $topic",
    private val eagerQuery: UnaryOperator<PropositionQuery>? = null,
) : LlmReference, DelegatingTool {

    private val logger = LoggerFactory.getLogger(Memory::class.java)

    override val name: String = NAME

    /**
     * Set the topic description for the memories.
     */
    fun withTopic(topic: String): Memory = copy(topic = topic)

    fun withUseWhen(useWhen: String): Memory = copy(useWhen = useWhen)

    override val description: String
        get() {
            val memoryCount = repository.query(
                PropositionQuery.forContextId(contextId)
                    .withMinEffectiveConfidence(minConfidence)
            ).size
            logger.info(
                "Found {} memories > {} confidence in context {}", memoryCount, minConfidence,
                contextId
            )

            val status = when (memoryCount) {
                0 -> "No memories yet"
                1 -> "Search 1 stored memory"
                else -> "Search $memoryCount stored memories"
            }

            val eagerMemories = eagerQuery?.let { queryFn ->
                val baseQuery = PropositionQuery.forContextId(contextId)
                    .withMinEffectiveConfidence(minConfidence)
                repository.query(queryFn.apply(baseQuery))
            } ?: emptyList()

            return buildString {
                appendLine("Memory Tool: $name")
                appendLine("Use when: $useWhen")
                append(status)
                if (eagerMemories.isNotEmpty()) {
                    appendLine()
                    appendLine("Key memories:")
                    eagerMemories.forEach { appendLine("- ${it.text}") }
                }
            }.trimEnd()
        }

    override fun toolPrefix(): String = ""

    /**
     * Set the projector for categorizing memories by knowledge type.
     *
     * @param projector The projector to use
     * @return New Memory with updated projector
     */
    fun withProjector(projector: MemoryProjector): Memory =
        copy(projector = projector)

    /**
     * Set the minimum confidence threshold for returned memories.
     * Memories with effective confidence below this are filtered out.
     *
     * @param minConfidence Minimum confidence (0.0 to 1.0, default 0.5)
     * @return New Memory with updated confidence
     */
    fun withMinConfidence(minConfidence: Double): Memory {
        require(minConfidence in 0.0..1.0) { "minConfidence must be between 0.0 and 1.0" }
        return copy(minConfidence = minConfidence)
    }

    /**
     * Set the default limit for search results.
     *
     * @param limit Default maximum results (default 10)
     * @return New Memory with updated limit
     */
    fun withDefaultLimit(limit: Int): Memory {
        require(limit > 0) { "limit must be positive" }
        return copy(defaultLimit = limit)
    }

    /**
     * Set an eager query to preload key memories into the description.
     *
     * When set, the description will include memories fetched using this query,
     * making them immediately available to the LLM without requiring a tool call.
     * The query transformer receives a base query with contextId and minConfidence
     * already applied.
     *
     * Example (Kotlin):
     * ```kotlin
     * .withEagerQuery { it.orderedByEffectiveConfidence().withLimit(5) }
     * ```
     *
     * Example (Java):
     * ```java
     * .withEagerQuery(query -> query.orderedByEffectiveConfidence().withLimit(5))
     * ```
     *
     * @param eagerQuery Function to transform the base query
     * @return New Memory with eager query configured
     */
    fun withEagerQuery(eagerQuery: UnaryOperator<PropositionQuery>): Memory =
        copy(eagerQuery = eagerQuery)

    override fun notes(): String = """
$name tools support an optional 'type' filter:
- semantic: Facts about entities (e.g., "Alice works at Acme")
- episodic: Events that happened (e.g., "Alice met Bob yesterday")
- procedural: Preferences and habits (e.g., "Alice prefers morning meetings")
- working: Current session context

Memory search is scoped to the current context and filtered by confidence.
Use these tools proactively to personalize responses and maintain continuity.
"""

    // DelegatingTool implementation via lazy MatryoshkaTool
    override val delegate: Tool by lazy {
        MatryoshkaTool.of(
            name = NAME,
            description = description,
            innerTools = listOf(searchByTopicTool(), searchRecentTool(), searchByTypeTool()),
        )
    }

    override fun tools(): List<Tool> = listOf(delegate)

    override val definition: Tool.Definition
        get() = delegate.definition

    override fun call(input: String): Tool.Result =
        delegate.call(input)

    private fun searchByTopicTool(): Tool = object : Tool {
        override val definition = Tool.Definition.create(
            name = "searchByTopic",
            description = """
                |Find memories related to a specific topic (e.g., "music preferences", "work projects")
            """.trimMargin(),
            inputSchema = Tool.InputSchema.of(
                Tool.Parameter.string("topic", "The topic to search for", required = true),
                Tool.Parameter.string(
                    "type",
                    "Optional: filter by knowledge type",
                    required = false,
                    enumValues = KNOWLEDGE_TYPE_VALUES,
                ),
                Tool.Parameter.integer("limit", "Maximum number of results", required = false),
            ),
        )

        override fun call(input: String): Tool.Result {
            val params = parseInput(input)
            val topic = params["topic"] as? String ?: return Tool.Result.error("Missing 'topic' parameter")
            val typeFilter = parseKnowledgeType(params["type"] as? String)
            val limit = (params["limit"] as? Number)?.toInt() ?: defaultLimit

            val query = PropositionQuery.forContextId(contextId)
                .withMinEffectiveConfidence(minConfidence)

            val results = repository.findSimilarWithScores(
                TextSimilaritySearchRequest(
                    query = topic,
                    similarityThreshold = 0.0,
                    topK = if (typeFilter != null) limit * 3 else limit, // Fetch more if filtering
                ),
                query
            )

            val filtered = if (typeFilter != null) {
                val projection = projector.project(results.map { it.match })
                projection[typeFilter].take(limit)
            } else {
                results.take(limit).map { it.match }
            }

            if (filtered.isEmpty()) {
                val typeDesc = typeFilter?.let { " (${it.name.lowercase()})" } ?: ""
                return Tool.Result.text("No memories found about '$topic'$typeDesc.")
            }

            val text = buildString {
                val typeDesc = typeFilter?.let { " (${it.name.lowercase()})" } ?: ""
                appendLine("Memories about '$topic'$typeDesc:")
                filtered.forEach { prop ->
                    appendLine("- ${prop.text}")
                }
            }.trimEnd()

            return Tool.Result.text(text)
        }
    }

    private fun searchRecentTool(): Tool = object : Tool {
        override val definition = Tool.Definition.create(
            name = "searchRecent",
            description = "Recall what was recently discussed or learned in the conversation",
            inputSchema = Tool.InputSchema.of(
                Tool.Parameter.string(
                    "type",
                    "Optional: filter by knowledge type",
                    required = false,
                    enumValues = KNOWLEDGE_TYPE_VALUES,
                ),
                Tool.Parameter.integer("limit", "Maximum number of results", required = false),
            ),
        )

        override fun call(input: String): Tool.Result {
            val params = parseInput(input)
            val typeFilter = parseKnowledgeType(params["type"] as? String)
            val limit = (params["limit"] as? Number)?.toInt() ?: defaultLimit

            val results = repository.query(
                PropositionQuery.forContextId(contextId)
                    .withMinEffectiveConfidence(minConfidence)
                    .orderedByCreated()
                    .withLimit(if (typeFilter != null) limit * 3 else limit)
            )

            val filtered = if (typeFilter != null) {
                val projection = projector.project(results)
                projection[typeFilter].take(limit)
            } else {
                results.take(limit)
            }

            if (filtered.isEmpty()) {
                val typeDesc = typeFilter?.let { " ${it.name.lowercase()}" } ?: ""
                return Tool.Result.text("No recent$typeDesc memories found.")
            }

            val text = buildString {
                val typeDesc = typeFilter?.let { " (${it.name.lowercase()})" } ?: ""
                appendLine("Recent memories$typeDesc:")
                filtered.forEach { prop ->
                    appendLine("- ${prop.text}")
                }
            }.trimEnd()

            return Tool.Result.text(text)
        }
    }

    private fun searchByTypeTool(): Tool = object : Tool {
        override val definition = Tool.Definition.create(
            name = "searchByType",
            description = """
                |Find all memories of a specific type (facts, events, preferences)
            """.trimMargin(),
            inputSchema = Tool.InputSchema.of(
                Tool.Parameter.string(
                    "type",
                    "Knowledge type: semantic (facts), episodic (events), procedural (preferences), working (session)",
                    required = true,
                    enumValues = KNOWLEDGE_TYPE_VALUES,
                ),
                Tool.Parameter.integer("limit", "Maximum number of results", required = false),
            ),
        )

        override fun call(input: String): Tool.Result {
            val params = parseInput(input)
            val typeStr = params["type"] as? String ?: return Tool.Result.error("Missing 'type' parameter")
            val typeFilter = parseKnowledgeType(typeStr)
                ?: return Tool.Result.error("Invalid type '$typeStr'. Use: semantic, episodic, procedural, or working")
            val limit = (params["limit"] as? Number)?.toInt() ?: defaultLimit

            val results = repository.query(
                PropositionQuery.forContextId(contextId)
                    .withMinEffectiveConfidence(minConfidence)
                    .orderedByEffectiveConfidence()
                    .withLimit(limit * 3) // Fetch more since we're filtering
            )

            val projection = projector.project(results)
            val filtered = projection[typeFilter].take(limit)

            if (filtered.isEmpty()) {
                return Tool.Result.text("No ${typeFilter.name.lowercase()} memories found.")
            }

            val typeLabel = when (typeFilter) {
                KnowledgeType.SEMANTIC -> "Facts"
                KnowledgeType.EPISODIC -> "Events"
                KnowledgeType.PROCEDURAL -> "Preferences"
                KnowledgeType.WORKING -> "Session context"
            }

            val text = buildString {
                appendLine("$typeLabel (${filtered.size}):")
                filtered.forEach { prop ->
                    appendLine("- ${prop.text}")
                }
            }.trimEnd()

            return Tool.Result.text(text)
        }
    }

    private fun parseKnowledgeType(value: String?): KnowledgeType? {
        if (value.isNullOrBlank()) return null
        return try {
            KnowledgeType.valueOf(value.uppercase())
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun parseInput(input: String): Map<String, Any?> {
        if (input.isBlank()) return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            objectMapper.readValue(input, Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            emptyMap()
        }
    }

    companion object {

        private const val NAME = "memory"

        /** Default minimum confidence threshold */
        const val DEFAULT_MIN_CONFIDENCE = 0.5

        /** Default result limit */
        const val DEFAULT_LIMIT = 10

        private val KNOWLEDGE_TYPE_VALUES = KnowledgeType.entries.map { it.name.lowercase() }

        private val objectMapper = jacksonObjectMapper()

        /**
         * Start creating a Memory for the given context.
         *
         * @param contextId The context to search within
         * @return Step requiring a repository
         */
        @JvmStatic
        fun forContext(contextId: ContextId): WithContext = WithContext(contextId)

        /**
         * Start creating a Memory for the given context (Java-friendly).
         *
         * @param contextIdValue The context ID string value
         * @return Step requiring a repository
         */
        @JvmStatic
        fun forContext(contextIdValue: String): WithContext = WithContext(ContextId(contextIdValue))
    }

    /**
     * Step: context is set, repository required.
     */
    class WithContext internal constructor(private val contextId: ContextId) {

        /**
         * Set the proposition repository to search.
         *
         * @param repository The repository containing propositions
         * @return Memory ready to use or configure further
         */
        fun withRepository(repository: PropositionRepository): Memory =
            Memory(contextId, repository)
    }
}

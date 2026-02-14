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

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.progressive.UnfoldingTool
import com.embabel.agent.core.ContextId
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.agent.Memory.Companion.DEFAULT_LIMIT
import com.embabel.dice.common.KnowledgeType
import com.embabel.dice.projection.memory.MemoryProjector
import com.embabel.dice.projection.memory.support.DefaultMemoryProjector
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.util.function.UnaryOperator

/**
 * UnfoldingTool providing agent memory search within a context.
 *
 * Provides access to conversation memory with six inner tools:
 * - **listAll**: Retrieve all memories ordered by confidence (best for broad queries)
 * - **searchByTopic**: Vector similarity search for relevant memories
 * - **searchByKeyword**: Case-insensitive text matching on memory content
 * - **searchRecent**: Temporal ordering to recall recent memories
 * - **searchByType**: Find memories by knowledge type (facts, events, preferences)
 * - **drillDown**: Get the detailed source memories behind a summary
 *
 * All search operations support optional `type` filtering to narrow results to specific
 * knowledge types: semantic (facts), episodic (events), procedural (preferences), or working (session).
 *
 * The context is baked in at construction time, ensuring the LLM
 * can only access memories within the authorized context.
 *
 * The description dynamically reflects how many memories are available.
 *
 * Supports a two-tier retrieval strategy:
 * 1. **Eager**: Key memories are preloaded into the description, making them
 *    immediately visible to the LLM with no tool call overhead. Two eager modes are available:
 *    - [withEagerQuery]: Preload by structured query (e.g., top-N by confidence)
 *    - [withEagerTopicSearch]: Preload by vector similarity to the [topic]
 * 2. **On-demand**: The LLM can call search tools for specific or additional memories.
 *
 * When eager memories are loaded, subsequent tool calls automatically deduplicate results
 * so the LLM always receives new information.
 *
 * Example usage from Kotlin:
 * ```kotlin
 * val memory = Memory.forContext(contextId)
 *     .withRepository(propositionRepository)
 *     .withProjector(DefaultMemoryProjector.withKnowledgeTypeClassifier(myClassifier))
 *     .withMinConfidence(0.6)
 *     .withTopic("classical music preferences")
 *     .withEagerTopicSearch(5)
 * ```
 *
 * Example usage from Java:
 * ```java
 * Memory memory = Memory.forContext("user-session-123")
 *     .withRepository(propositionRepository)
 *     .withProjector(DefaultMemoryProjector.withKnowledgeTypeClassifier(myClassifier))
 *     .withMinConfidence(0.6)
 *     .withTopic("classical music preferences")
 *     .withEagerTopicSearch(5);
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
 * @param narrowedBy Optional query transformer that narrows the scope of all queries.
 * Applied on top of the base query (contextId + minConfidence) before any tool-specific
 * additions. Use this to restrict Memory to a subset of propositions (e.g., by entity,
 * level, or temporal range). Cannot widen the base scope, only narrow it.
 * @param eagerQuery Optional query transformer to eagerly load key memories into the description.
 * When set, the description will include memories fetched using this query, making them
 * immediately available to the LLM without requiring a tool call.
 * Applied on top of the narrowed base query.
 * @param eagerTopicSearch Optional limit for eager topic-based similarity search.
 * When set, uses the [topic] to perform a vector similarity search and preloads matching
 * memories into the description. Can be used alongside or instead of [eagerQuery].
 */
data class Memory @JvmOverloads constructor(
    private val contextId: ContextId,
    private val repository: PropositionRepository,
    private val projector: MemoryProjector = DefaultMemoryProjector.DEFAULT,
    private val minConfidence: Double = DEFAULT_MIN_CONFIDENCE,
    private val defaultLimit: Int = DEFAULT_LIMIT,
    private val topic: String = "the user & context",
    private val useWhen: String = "whenever you need to recall information about $topic",
    private val narrowedBy: UnaryOperator<PropositionQuery>? = null,
    private val eagerQuery: UnaryOperator<PropositionQuery>? = null,
    private val eagerTopicSearch: Int? = null,
) : UnfoldingTool {

    private val logger = LoggerFactory.getLogger(Memory::class.java)

    val name: String = NAME

    /**
     * Set the topic description for the memories.
     */
    fun withTopic(topic: String): Memory = copy(topic = topic)

    fun withUseWhen(useWhen: String): Memory = copy(useWhen = useWhen)

    /**
     * Narrow the scope of all memory queries.
     *
     * The transformer receives the base query (contextId + minConfidence already applied)
     * and should add further constraints. This scope is enforced on every query —
     * eager loading, searchByTopic, searchRecent, and searchByType — so the LLM
     * cannot access propositions outside it.
     *
     * Can be called multiple times; each call replaces the previous narrowing.
     *
     * Example (Kotlin):
     * ```kotlin
     * .narrowedBy { it.withEntityId("alice-123") }
     * ```
     *
     * Example (Java):
     * ```java
     * .narrowedBy(query -> query.withEntityId("alice-123"))
     * ```
     *
     * @param narrowedBy Function to narrow the base query
     * @return New Memory with the scope narrowed
     */
    fun narrowedBy(narrowedBy: UnaryOperator<PropositionQuery>): Memory =
        copy(narrowedBy = narrowedBy)

    /**
     * Build the base query that all operations start from.
     * Applies contextId, minConfidence, and any narrowing.
     */
    private fun baseQuery(): PropositionQuery {
        val base = PropositionQuery.forContextId(contextId)
            .withMinEffectiveConfidence(minConfidence)
        return narrowedBy?.apply(base) ?: base
    }

    /**
     * IDs of propositions that were eagerly loaded into the description.
     * Used to deduplicate results from subsequent tool calls.
     */
    private val eagerPropositionIds: Set<String> by lazy {
        loadEagerMemories().map { it.id }.toSet()
    }

    private fun loadEagerMemories(): List<Proposition> {
        val base = baseQuery()

        val topicMemories = eagerTopicSearch?.let { limit ->
            repository.findSimilarWithScores(
                TextSimilaritySearchRequest(
                    query = topic,
                    similarityThreshold = 0.0,
                    topK = limit,
                ),
                base,
            ).map { it.match }
        } ?: emptyList()

        val queryMemories = eagerQuery?.let { queryFn ->
            repository.query(queryFn.apply(base))
        } ?: emptyList()

        // Merge both sources, deduplicating by ID, topic results first
        val seen = mutableSetOf<String>()
        return (topicMemories + queryMemories).filter { seen.add(it.id) }
    }

    val description: String
        get() {
            val memoryCount = repository.query(baseQuery()).size
            logger.info(
                "Found {} memories > {} confidence in context {}", memoryCount, minConfidence,
                contextId
            )

            val status = when (memoryCount) {
                0 -> "No memories stored yet."
                1 -> "1 memory available to search."
                else -> "$memoryCount memories available to search."
            }

            val eagerMemories = loadEagerMemories()

            return buildString {
                appendLine("Memory about $topic ($status)")
                appendLine("Call this to enable search tools, then call listAll, searchByTopic, or searchByKeyword to retrieve memories.")
                append("Use when: $useWhen")
                if (eagerMemories.isNotEmpty()) {
                    appendLine()
                    appendLine("Key memories:")
                    eagerMemories.forEachIndexed { index, memory ->
                        appendLine("${index + 1}. ${memory.text}")
                    }
                    if (eagerMemories.size < memoryCount) {
                        append("[retrievable ${eagerMemories.size + 1}-$memoryCount]")
                    }
                }
            }.trimEnd()
        }

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
     * The query transformer receives the base query (contextId, minConfidence,
     * and any [narrowedBy] scope already applied).
     *
     * Can be combined with [withEagerTopicSearch] — both sets of memories will be
     * merged (deduplicated) in the description.
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

    /**
     * Enable eager topic-based similarity search.
     *
     * Uses the [topic] field to perform a vector similarity search and preloads
     * the top matching memories into the description. This makes the most relevant
     * memories for the topic immediately visible to the LLM without a tool call.
     *
     * Subsequent [searchByTopic][searchByTopicTool] tool calls automatically
     * deduplicate against these eagerly loaded memories, so the LLM always
     * receives new information.
     *
     * Can be combined with [withEagerQuery] — both sets of memories will be
     * merged (deduplicated) in the description.
     *
     * @param limit Maximum number of memories to preload (default [DEFAULT_LIMIT])
     * @return New Memory with eager topic search configured
     */
    @JvmOverloads
    fun withEagerTopicSearch(limit: Int = DEFAULT_LIMIT): Memory {
        require(limit > 0) { "limit must be positive" }
        return copy(eagerTopicSearch = limit)
    }

    override val definition: Tool.Definition by lazy {
        Tool.Definition(
            name = NAME,
            description = description,
            inputSchema = Tool.InputSchema.empty(),
        )
    }

    override val includeContextTool: Boolean = false

    override val innerTools: List<Tool> by lazy {
        listOf(
            listAllTool(),
            searchByTopicTool(),
            searchByKeywordTool(),
            searchRecentTool(),
            searchByTypeTool(),
            drillDownTool(),
        )
    }

    override val childToolUsageNotes: String = """
        |After reading this context, you MUST call one of the search tools below to retrieve actual memories.
        |Choose the right search tool for the question:
        |- **listAll**: Use for broad questions like "tell me about X" or "what do you know about me". Returns all memories.
        |- **searchByTopic**: Use for specific topics like "hobbies" or "work". Uses semantic similarity search.
        |- **searchByKeyword**: Use when looking for a specific word or phrase in memories.
        |- **searchRecent**: Use to recall what was recently discussed.
        |- **searchByType**: Use to find facts, events, or preferences specifically.
        |- **drillDown**: Use to expand a summary into its source details.
        |
        |Tools support an optional 'type' filter:
        |- semantic: Facts about entities (e.g., "Alice works at Acme")
        |- episodic: Events that happened (e.g., "Alice met Bob yesterday")
        |- procedural: Preferences and habits (e.g., "Alice prefers morning meetings")
        |- working: Current session context
        |
        |For open-ended questions, use listAll first to get a complete picture.
    """.trimMargin()

    override fun call(input: String): Tool.Result {
        val toolNames = innerTools.map { it.definition.name }
        return Tool.Result.text(
            buildString {
                appendLine("Memory tools enabled: ${toolNames.joinToString(", ")}")
                appendLine()
                appendLine("Now call one of these tools to search memories:")
                appendLine("- listAll: Get all stored memories (best for broad questions)")
                appendLine("- searchByTopic: Search by semantic topic (e.g., 'hobbies', 'work')")
                appendLine("- searchByKeyword: Search for specific words in memories")
                appendLine("- searchRecent: Get recently added memories")
                appendLine("- searchByType: Filter by type (semantic/episodic/procedural)")
                appendLine("- drillDown: Expand a summary into details")
            }.trimEnd()
        )
    }

    private fun listAllTool(): Tool = object : Tool {
        override val definition = Tool.Definition.create(
            name = "listAll",
            description = """
                |List all stored memories, ordered by confidence.
                |Use this for broad questions like "tell me about X", "what do you know about me",
                |or any question where you need a comprehensive overview of all stored information.
            """.trimMargin(),
            inputSchema = Tool.InputSchema.of(
                Tool.Parameter.string(
                    "type",
                    "Optional: filter by knowledge type",
                    required = false,
                    enumValues = KNOWLEDGE_TYPE_VALUES,
                ),
                Tool.Parameter.integer("limit", "Maximum number of results (default: 50)", required = false),
            ),
        )

        override fun call(input: String): Tool.Result {
            val params = parseInput(input)
            val typeFilter = parseKnowledgeType(params["type"] as? String)
            val limit = (params["limit"] as? Number)?.toInt() ?: 50

            val results = repository.query(
                baseQuery()
                    .orderedByEffectiveConfidence()
                    .withLimit(if (typeFilter != null) limit * 3 else limit)
            )

            val deduped = results.filter { it.id !in eagerPropositionIds }

            val filtered = if (typeFilter != null) {
                val projection = projector.project(deduped)
                projection[typeFilter].take(limit)
            } else {
                deduped.take(limit)
            }

            if (filtered.isEmpty()) {
                val typeDesc = typeFilter?.let { " (${it.name.lowercase()})" } ?: ""
                return if (eagerPropositionIds.isNotEmpty()) {
                    Tool.Result.text("No additional memories found$typeDesc beyond those already provided.")
                } else {
                    Tool.Result.text("No memories stored yet$typeDesc.")
                }
            }

            val text = buildString {
                val typeDesc = typeFilter?.let { " (${it.name.lowercase()})" } ?: ""
                appendLine("All memories$typeDesc (${filtered.size}):")
                filtered.forEach { prop ->
                    appendLine("- ${prop.text}")
                }
            }.trimEnd()

            return Tool.Result.text(text)
        }
    }

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
                Tool.Parameter.integer(
                    "level",
                    "Abstraction level: 0 for raw details, 1+ for summaries",
                    required = false
                ),
            ),
        )

        override fun call(input: String): Tool.Result {
            val params = parseInput(input)
            val topic = params["topic"] as? String ?: return Tool.Result.error("Missing 'topic' parameter")
            val typeFilter = parseKnowledgeType(params["type"] as? String)
            val limit = (params["limit"] as? Number)?.toInt() ?: defaultLimit
            val level = (params["level"] as? Number)?.toInt()

            var query = baseQuery()
            level?.let { query = query.withMinLevel(it).withMaxLevel(it) }

            val results = repository.findSimilarWithScores(
                TextSimilaritySearchRequest(
                    query = topic,
                    similarityThreshold = 0.0,
                    topK = if (typeFilter != null) limit * 3 else limit, // Fetch more if filtering
                ),
                query
            )

            val deduped = results.filter { it.match.id !in eagerPropositionIds }

            val filtered = if (typeFilter != null) {
                val projection = projector.project(deduped.map { it.match })
                projection[typeFilter].take(limit)
            } else {
                deduped.take(limit).map { it.match }
            }

            if (filtered.isEmpty()) {
                val typeDesc = typeFilter?.let { " (${it.name.lowercase()})" } ?: ""
                return if (eagerPropositionIds.isNotEmpty()) {
                    Tool.Result.text("No additional memories found about '$topic'$typeDesc beyond those already provided.")
                } else {
                    Tool.Result.text("No memories found about '$topic'$typeDesc.")
                }
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

    private fun searchByKeywordTool(): Tool = object : Tool {
        override val definition = Tool.Definition.create(
            name = "searchByKeyword",
            description = """
                |Search memories containing a specific keyword or phrase (case-insensitive text match).
                |Use this when you know the exact word to look for (e.g., "guitar", "Java", "Miso").
            """.trimMargin(),
            inputSchema = Tool.InputSchema.of(
                Tool.Parameter.string("keyword", "The keyword or phrase to search for", required = true),
                Tool.Parameter.integer("limit", "Maximum number of results", required = false),
            ),
        )

        override fun call(input: String): Tool.Result {
            val params = parseInput(input)
            val keyword = params["keyword"] as? String
                ?: return Tool.Result.error("Missing 'keyword' parameter")
            val limit = (params["limit"] as? Number)?.toInt() ?: defaultLimit

            val allProps = repository.query(
                baseQuery()
                    .orderedByEffectiveConfidence()
                    .withLimit(limit * 5) // Fetch more since we're filtering in-memory
            )

            val matches = allProps
                .filter { it.text.contains(keyword, ignoreCase = true) }
                .filter { it.id !in eagerPropositionIds }
                .take(limit)

            if (matches.isEmpty()) {
                return Tool.Result.text("No memories found containing '$keyword'.")
            }

            val text = buildString {
                appendLine("Memories containing '$keyword' (${matches.size}):")
                matches.forEach { prop ->
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
                Tool.Parameter.integer(
                    "level",
                    "Abstraction level: 0 for raw details, 1+ for summaries",
                    required = false
                ),
            ),
        )

        override fun call(input: String): Tool.Result {
            val params = parseInput(input)
            val typeFilter = parseKnowledgeType(params["type"] as? String)
            val limit = (params["limit"] as? Number)?.toInt() ?: defaultLimit
            val level = (params["level"] as? Number)?.toInt()

            var query = baseQuery()
            level?.let { query = query.withMinLevel(it).withMaxLevel(it) }

            val results = repository.query(
                query
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
                Tool.Parameter.integer(
                    "level",
                    "Abstraction level: 0 for raw details, 1+ for summaries",
                    required = false
                ),
            ),
        )

        override fun call(input: String): Tool.Result {
            val params = parseInput(input)
            val typeStr = params["type"] as? String ?: return Tool.Result.error("Missing 'type' parameter")
            val typeFilter = parseKnowledgeType(typeStr)
                ?: return Tool.Result.error("Invalid type '$typeStr'. Use: semantic, episodic, procedural, or working")
            val limit = (params["limit"] as? Number)?.toInt() ?: defaultLimit
            val level = (params["level"] as? Number)?.toInt()

            var query = baseQuery()
            level?.let { query = query.withMinLevel(it).withMaxLevel(it) }

            val results = repository.query(
                query
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

    private fun drillDownTool(): Tool = object : Tool {
        override val definition = Tool.Definition.create(
            name = "drillDown",
            description = "Get the detailed source memories behind a summary/abstraction",
            inputSchema = Tool.InputSchema.of(
                Tool.Parameter.string(NAME, "The text of the summary to drill into", required = true),
                Tool.Parameter.integer("limit", "Maximum number of results", required = false),
            ),
        )

        override fun call(input: String): Tool.Result {
            val params = parseInput(input)
            val memoryText = params[NAME] as? String ?: return Tool.Result.error("Missing 'memory' parameter")
            val limit = (params["limit"] as? Number)?.toInt() ?: defaultLimit

            // Find the abstraction by similarity search (level >= 1)
            val abstractionQuery = baseQuery().withMinLevel(1)
            val matches = repository.findSimilarWithScores(
                TextSimilaritySearchRequest(
                    query = memoryText,
                    similarityThreshold = 0.0,
                    topK = 1,
                ),
                abstractionQuery,
            )

            if (matches.isEmpty()) {
                return Tool.Result.text("No matching abstraction found to drill into.")
            }

            val abstraction = matches.first().match
            val sources = repository.findSources(abstraction)

            if (sources.isEmpty()) {
                return Tool.Result.text("This memory has no detailed sources to drill into.")
            }

            val text = buildString {
                appendLine("Sources behind '${abstraction.text}':")
                sources.take(limit).forEach { prop ->
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

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

import com.embabel.agent.api.reference.EagerSearch
import com.embabel.agent.api.reference.LlmReference
import com.embabel.agent.api.tool.Tool
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
 * Tool providing agent memory search within a context.
 *
 * A single tool that the LLM calls with optional parameters to search memories:
 * - **topic**: Vector similarity search (e.g., `{"topic": "hobbies"}`)
 * - **keyword**: Case-insensitive text match (e.g., `{"keyword": "guitar"}`)
 * - **type**: Filter by knowledge type (semantic, episodic, procedural, working)
 * - No parameters or **listAll**: Returns all memories ordered by confidence
 *
 * The context is baked in at construction time, ensuring the LLM
 * can only access memories within the authorized context.
 *
 * The description dynamically reflects how many memories are available.
 *
 * Implements [LlmReference] so that key memories are surfaced directly in the LLM system prompt
 * (via [contribution]) rather than buried in tool metadata. This ensures the LLM can reason
 * about known facts without needing a tool call.
 *
 * Supports a two-tier retrieval strategy:
 * 1. **Eager**: Key memories are preloaded into the system prompt via [contribution], making them
 *    immediately visible to the LLM with no tool call overhead. Three eager modes are available:
 *    - [withEagerSearchAbout]: Preload by vector similarity search request (e.g., recent conversation)
 *    - [withEagerQuery]: Preload by structured query (e.g., top-N by confidence)
 *    - [withEagerTopicSearch]: Preload by vector similarity to the [topic]
 * 2. **On-demand**: The LLM calls this tool with search parameters for specific or additional memories.
 *
 * When eager memories are loaded, subsequent tool calls automatically deduplicate results
 * so the LLM always receives new information.
 *
 * Example: preload memories relevant to the current conversation:
 * ```kotlin
 * val memory = Memory.forContext(contextId)
 *     .withRepository(propositionRepository)
 *     .withEagerSearchAbout(recentConversationText, 10)
 * ```
 *
 * Example: preload by topic similarity and structured query:
 * ```kotlin
 * val memory = Memory.forContext(contextId)
 *     .withRepository(propositionRepository)
 *     .withTopic("classical music preferences")
 *     .withEagerTopicSearch(5)
 *     .withEagerQuery { it.orderedByEffectiveConfidence().withLimit(3) }
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
 * @param eagerTextSearch Optional similarity search request to eagerly preload memories.
 * When set, performs a vector similarity search using this request and preloads matching
 * memories into the description. Ideal for passing recent conversation content so
 * the LLM sees relevant memories without needing a tool call.
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
    private val eagerTextSearch: TextSimilaritySearchRequest? = null,
) : Tool, EagerSearch<Memory> {

    private val logger = LoggerFactory.getLogger(Memory::class.java)

    override val name: String = NAME

    override val description: String
        get() = "Memories about $topic"

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
     * eager loading and all search modes — so the LLM
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
     * Build a query with optional level filtering from call parameters.
     */
    private fun queryWithLevel(params: Map<String, Any?>): PropositionQuery {
        val query = baseQuery()
        val level = (params["level"] as? Number)?.toInt() ?: return query
        return query.withMinLevel(level).withMaxLevel(level)
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

        val aboutMemories = eagerTextSearch?.let { request ->
            repository.findSimilarWithScores(
                request,
                base,
            ).map { it.match }
        } ?: emptyList()

        val queryMemories = eagerQuery?.let { queryFn ->
            repository.query(queryFn.apply(base))
        } ?: emptyList()

        // Merge all sources, deduplicating by ID, aboutMemories first (most contextual)
        val seen = mutableSetOf<String>()
        return (aboutMemories + topicMemories + queryMemories).filter { seen.add(it.id) }
    }

    // -- LlmReference implementation --

    override fun notes(): String = "Use when: $useWhen"

    override fun tools(): List<Tool> = listOf(this)

    override fun contribution(): String {
        val memoryCount = repository.query(baseQuery()).size
        val eagerMemories = loadEagerMemories()

        return buildString {
            appendLine("Reference: $name")
            appendLine("Description: $description. $memoryCount memories available.")
            if (eagerMemories.isNotEmpty()) {
                appendLine()
                appendLine("Key memories about $topic:")
                eagerMemories.forEachIndexed { index, memory ->
                    appendLine("${index + 1}. ${memory.text}")
                }
                if (eagerMemories.size < memoryCount) {
                    appendLine("[${memoryCount - eagerMemories.size} more retrievable via the $NAME tool]")
                }
            }
            appendLine()
            append("Notes: ${notes()}")
        }.trimEnd()
    }

    /**
     * Tool description — lean, without key memories (those are in [contribution]).
     */
    private val toolDescription: String
        get() {
            val memoryCount = repository.query(baseQuery()).size
            logger.info(
                "Found {} memories > {} confidence in context {}", memoryCount, minConfidence,
                contextId
            )
            val status = when (memoryCount) {
                0 -> "No memories stored yet."
                1 -> "1 memory available."
                else -> "$memoryCount memories available."
            }
            return "Search memories about $topic. $status Use when: $useWhen"
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
     * Subsequent tool calls automatically deduplicate against these eagerly loaded
     * memories, so the LLM always receives new information.
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

    override fun withEagerSearchAbout(request: TextSimilaritySearchRequest): Memory {
        require(request.topK > 0) { "topK must be positive" }
        return copy(eagerTextSearch = request)
    }

    override val definition: Tool.Definition by lazy {
        Tool.Definition(
            name = NAME,
            description = toolDescription,
            inputSchema = Tool.InputSchema.of(
                Tool.Parameter.string("topic", "Search memories by topic (semantic similarity)", required = false),
                Tool.Parameter.string(
                    "keyword",
                    "Search memories containing this keyword (exact text match)",
                    required = false
                ),
                Tool.Parameter.string(
                    "type",
                    "Filter by knowledge type",
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
    }

    override fun call(input: String): Tool.Result {
        val params = parseInput(input)
        return when {
            params.containsKey("keyword") -> searchByKeyword(params)
            params.containsKey("topic") -> searchByTopic(params)
            params.containsKey("type") -> searchByType(params)
            else -> listAll(params)
        }
    }

    private fun listAll(params: Map<String, Any?>): Tool.Result {
        val typeFilter = parseKnowledgeType(params["type"] as? String)
        val limit = (params["limit"] as? Number)?.toInt() ?: 50

        val results = repository.query(
            queryWithLevel(params)
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

    private fun searchByTopic(params: Map<String, Any?>): Tool.Result {
        val topic = params["topic"] as? String ?: return Tool.Result.error("Missing 'topic' parameter")
        val typeFilter = parseKnowledgeType(params["type"] as? String)
        val limit = (params["limit"] as? Number)?.toInt() ?: defaultLimit

        val results = repository.findSimilarWithScores(
            TextSimilaritySearchRequest(
                query = topic,
                similarityThreshold = 0.0,
                topK = if (typeFilter != null) limit * 3 else limit,
            ),
            queryWithLevel(params)
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

    private fun searchByKeyword(params: Map<String, Any?>): Tool.Result {
        val keyword = params["keyword"] as? String
            ?: return Tool.Result.error("Missing 'keyword' parameter")
        val limit = (params["limit"] as? Number)?.toInt() ?: defaultLimit

        val allProps = repository.query(
            queryWithLevel(params)
                .orderedByEffectiveConfidence()
                .withLimit(limit * 5)
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

    private fun searchByType(params: Map<String, Any?>): Tool.Result {
        val typeStr = params["type"] as? String ?: return Tool.Result.error("Missing 'type' parameter")
        val typeFilter = parseKnowledgeType(typeStr)
            ?: return Tool.Result.error("Invalid type '$typeStr'. Use: semantic, episodic, procedural, or working")
        val limit = (params["limit"] as? Number)?.toInt() ?: defaultLimit

        val results = repository.query(
            queryWithLevel(params)
                .orderedByEffectiveConfidence()
                .withLimit(limit * 3)
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

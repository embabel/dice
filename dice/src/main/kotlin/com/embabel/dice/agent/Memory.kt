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
import com.embabel.dice.projection.memory.MemoryProjector
import com.embabel.dice.projection.memory.support.DefaultMemoryProjector
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.util.function.UnaryOperator

/**
 * Tool providing agent memory search within a context.
 *
 * A single agentic tool: the LLM passes a freeform natural-language
 * `query` and Memory runs a **hybrid** retrieval — a vector-similarity
 * probe AND a keyword (substring) probe over the same scoped
 * propositions — then unions the hits, tagging each with the probe(s)
 * that found it (`[vector]`, `[keyword]`, `[vector,keyword]`). Vector
 * carries question-shaped queries; keyword adds precision for exact
 * terms, names and phrases. There is no predicate / subject / object
 * parameter surface — the LLM asks in natural language and, if the
 * first query is unconvincing, simply asks again with different
 * wording. Calling with no `query` lists all memories by confidence.
 *
 * Fusion scoring (RRF) and graph-distance reranking are deliberately
 * NOT implemented yet — vector hits keep similarity order, keyword-only
 * hits follow by confidence.
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
    /**
     * Optional provenance hook. When set, every proposition the tool
     * returns is annotated with its source (email subject, meeting
     * title, …) so the LLM can cite where a fact came from without a
     * separate tool. Does not affect what is retrieved. See
     * [ProvenanceResolver].
     */
    private val provenanceResolver: ProvenanceResolver? = null,
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
     * Wire a [ProvenanceResolver] so every returned proposition is
     * annotated with its source(s). Folds citation/"why do you think"
     * answers into ordinary recall — no separate evidence tool.
     */
    fun withProvenance(resolver: ProvenanceResolver): Memory =
        copy(provenanceResolver = resolver)

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
        // Scope retrieval to ACTIVE so STALE/SUPERSEDED/CONTRADICTED never
        // reach LLM context by default. Applied before narrowedBy so a consumer
        // can still explicitly widen the status set via the narrowing operator.
        val base = PropositionQuery.forContextId(contextId)
            .withMinEffectiveConfidence(minConfidence)
            .withStatuses(setOf(PropositionStatus.ACTIVE))
        return narrowedBy?.apply(base) ?: base
    }

    /**
     * IDs of propositions that were eagerly loaded into the description.
     * Used to deduplicate results from subsequent tool calls.
     */
    private val eagerPropositionIds: Set<String> by lazy {
        loadEagerMemories().map { it.id }.toSet()
    }

    private fun loadEagerMemories(): List<Proposition> = try {
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
        (aboutMemories + topicMemories + queryMemories).filter { seen.add(it.id) }
    } catch (t: Throwable) {
        logger.warn("Unable to perform vector search")
        emptyList()
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
            return """
                Search memories about $topic via hybrid semantic + keyword retrieval. $status
                Use when: $useWhen. If a query comes back empty or unconvincing, retry with
                different wording or a broader query before concluding nothing is known.
            """.trimIndent()
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
                Tool.Parameter.string(
                    "query",
                    """
                        What to recall, in natural language (e.g. "where does the user live",
                        "the user's hobbies", "Stripe"). Runs a hybrid semantic + keyword search
                        over stored memories. If the first query returns nothing useful, try again
                        with different wording or a broader query before giving up. Omit to list
                        all memories by confidence.
                    """.trimIndent(),
                    required = false,
                ),
                Tool.Parameter.integer("limit", "Maximum number of results", required = false),
            ),
        )
    }

    override fun call(input: String): Tool.Result {
        val params = parseInput(input)
        // `query` is the canonical freeform parameter; accept `topic`
        // as a silent fallback so any caller still passing the old
        // name keeps working.
        val query = (params["query"] as? String ?: params["topic"] as? String)
            ?.trim()?.takeIf { it.isNotBlank() }
        val limit = (params["limit"] as? Number)?.toInt() ?: defaultLimit
        val retriever = MemoryRetriever(repository, provenanceResolver, topic, eagerPropositionIds)
        return if (query == null) {
            retriever.listAll(baseQuery(), limit.coerceAtLeast(defaultLimit))
        } else {
            retriever.search(query, baseQuery(), limit)
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

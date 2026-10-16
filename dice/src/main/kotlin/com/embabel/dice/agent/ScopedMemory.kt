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

import com.embabel.agent.api.reference.LlmReference
import com.embabel.agent.api.tool.Tool
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.ReadOrder
import com.embabel.dice.proposition.ScopedPropositionReader
import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.ScopedReadLimits
import com.embabel.dice.proposition.ScopedReadSession
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.Collections
import java.util.function.UnaryOperator

/** Provenance is resolved only for selected, owner-qualified proposition keys. */
fun interface ScopedProvenanceResolver {
    fun resolveSources(keys: List<PropositionKey>): Map<PropositionKey, List<String>>
}

internal data class PublishedEagerSnapshot<T>(
    val entries: List<CandidateEnvelope<T>>,
    val keys: Set<PropositionKey>,
    val count: Long,
    val renderedText: String,
)

/** Failed rendering or resolution never reserves keys that the model has not received. */
internal class EagerPublication<T> {
    @Volatile
    private var published: PublishedEagerSnapshot<T>? = null

    fun current(): PublishedEagerSnapshot<T>? = published

    @Synchronized
    fun getOrPublish(factory: () -> PublishedEagerSnapshot<T>): PublishedEagerSnapshot<T> {
        published?.let { return it }
        val candidate = factory()
        val entries = candidate.entries.map { it.copy(entityIds = immutableSet(it.entityIds)) }
        val keys = entries.map { it.key }.toSet()
        if (keys.size != entries.size || keys != candidate.keys || candidate.count < entries.size ||
            keys.any { it.contextIdValue.isBlank() || it.propositionId.isBlank() }
        ) {
            throw ScopedReadException("SCOPED_READ_RESULT_INVALID")
        }
        return PublishedEagerSnapshot(
            immutableList(entries), immutableSet(keys), candidate.count, candidate.renderedText,
        ).also { published = it }
    }
}

@DslMarker
annotation class ScopedMemoryDsl

/** An immutable host-bound memory reference. Scope and membership never come from tool input. */
class ScopedMemory private constructor(
    private val reader: ScopedPropositionReader,
    private val settings: Settings,
) : LlmReference, Tool {
    private val eager = EagerPublication<Proposition>()

    override val name: String = "memory"
    override val description: String get() = "Memories about ${settings.topic}"
    override fun notes(): String = "Use when: whenever you need to recall information about ${settings.topic}"
    override fun tools(): List<Tool> = listOf(this)

    fun withTopic(topic: String): ScopedMemory = configured(settings.copy(topic = topic))
    fun withLimits(limits: ScopedReadLimits): ScopedMemory = configured(settings.copy(limits = limits))
    fun withMinConfidence(value: Double): ScopedMemory = configured(settings.copy(minConfidence = value))
    fun withMinEffectiveConfidence(value: Double): ScopedMemory =
        configured(settings.copy(minEffectiveConfidence = value))

    fun withNarrowedBy(criteria: UnaryOperator<PropositionQuery>): ScopedMemory {
        val narrowed = snapshot(criteria.apply(baseQuery()))
        return ScopedMemory(reader.restrict(narrowed), settings)
    }

    fun withEagerQuery(criteria: UnaryOperator<PropositionQuery>): ScopedMemory =
        configured(settings.copy(eagerQuery = snapshot(criteria.apply(baseQuery()))))

    fun withEagerTopicSearch(limit: Int): ScopedMemory = configured(settings.copy(eagerTopicSearch = limit))
    fun withEagerTextSearch(request: TextSimilaritySearchRequest): ScopedMemory =
        configured(settings.copy(eagerTextSearch = request))
    fun withProvenance(resolver: ScopedProvenanceResolver): ScopedMemory =
        configured(settings.copy(provenance = resolver))

    private fun configured(settings: Settings): ScopedMemory {
        validate(settings)
        settings.eagerQuery?.let { reader.restrict(it) }
        return ScopedMemory(reader, settings.copy(eagerTextSearch = settings.eagerTextSearch?.let { request ->
            TextSimilaritySearchRequest(request.query, request.similarityThreshold, request.topK)
        }))
    }

    private fun baseQuery() = PropositionQuery(
        statuses = setOf(PropositionStatus.ACTIVE), minConfidence = settings.minConfidence,
        minEffectiveConfidence = settings.minEffectiveConfidence,
    )

    private fun session(): ScopedReadSession = reader.withLimits(settings.limits)
        .restrict(baseQuery()).snapshot(Instant.now(), 2.0)

    override val definition: Tool.Definition
        get() = Tool.Definition(
            name = name, description = description,
            inputSchema = Tool.InputSchema.of(
                Tool.Parameter.string("query", "What to recall; omit to list memories", required = false),
                Tool.Parameter.integer("limit", "Maximum number of results", required = false),
            ),
        )

    override fun call(input: String): Tool.Result {
        val (query, limit) = input(input)
        val bound = session().excluding(eager.current()?.keys.orEmpty())
        val selected = ScopedMemoryRetriever(bound).select(query, limit).entries
        val text = if (selected.isEmpty()) "No additional memories available."
        else render(selected)
        return Tool.Result.text(text)
    }

    private fun input(input: String): Pair<String, Int> {
        val node = try { mapper.readTree(input.ifBlank { "{}" }) }
        catch (_: Exception) { throw ScopedReadException("INVALID_SCOPED_READ") }
        if (node == null || !node.isObject || node.fieldNames().asSequence().any { it !in setOf("query", "limit") }) {
            throw ScopedReadException("INVALID_SCOPED_READ")
        }
        val query = node.get("query")
        val limit = node.get("limit")
        if ((query != null && !query.isTextual) ||
            (limit != null && (!limit.isIntegralNumber || !limit.canConvertToLong() || limit.longValue() <= 0))
        ) throw ScopedReadException("INVALID_SCOPED_READ")
        val requested = limit?.longValue() ?: Memory.DEFAULT_LIMIT.toLong()
        return (query?.textValue()?.trim().orEmpty()) to minOf(requested, settings.limits.maxResults.toLong()).toInt()
    }

    override fun contribution(): String = eager.getOrPublish {
        val bound = session()
        val entries = eagerEntries(bound)
        val count = bound.count()
        val rendered = buildString {
            appendLine("Reference: $name")
            appendLine("Description: $description. $count memories available.")
            if (entries.isNotEmpty()) {
                appendLine("Key memories about ${settings.topic}:")
                appendLine(render(entries))
                if (count > entries.size) appendLine("[${count - entries.size} more retrievable via the $name tool]")
            }
            append(notes())
        }
        PublishedEagerSnapshot(entries, entries.map { it.key }.toSet(), count, rendered)
    }.renderedText

    private fun eagerEntries(bound: ScopedReadSession): List<CandidateEnvelope<Proposition>> {
        val cap = settings.limits.maxEager
        if (cap == 0) return emptyList()
        val modes = mutableListOf<List<Proposition>>()
        // Preflight configured vector modes before another mode or count can return partial success.
        if (settings.eagerTopicSearch != null || settings.eagerTextSearch != null) {
            bound.findSimilarWithScores(TextSimilaritySearchRequest(query = "", topK = 0, similarityThreshold = 0.0))
        }
        settings.eagerTextSearch?.let { request ->
            modes += bound.findSimilarWithScores(TextSimilaritySearchRequest(request.query, request.similarityThreshold, minOf(request.topK, cap))).map { it.match }
        }
        settings.eagerTopicSearch?.let { limit ->
            modes += bound.findSimilarWithScores(
                TextSimilaritySearchRequest(query = settings.topic, topK = minOf(limit, cap), similarityThreshold = 0.0),
            ).map { it.match }
        }
        settings.eagerQuery?.let { criteria ->
            modes += bound.restrict(criteria).query(ReadOrder.valueOf(criteria.orderBy.name), minOf(criteria.limit ?: cap, cap))
        }
        val candidates = LinkedHashMap<PropositionKey, Pair<Proposition, Double>>()
        modes.forEach { rows ->
            rows.distinctBy { PropositionKey(it.contextId.value, it.id) }.forEachIndexed { rank, row ->
                val key = PropositionKey(row.contextId.value, row.id)
                val previous = candidates[key]
                candidates[key] = (previous?.first ?: row) to ((previous?.second ?: 0.0) + 1.0 / (61 + rank))
            }
        }
        return candidates.entries.sortedWith(
            compareByDescending<Map.Entry<PropositionKey, Pair<Proposition, Double>>> { it.value.second }
                .thenBy { it.key.contextIdValue }.thenBy { it.key.propositionId },
        ).take(cap).map { (key, value) ->
            CandidateEnvelope(key, value.first, value.first.mentions.mapNotNull { it.resolvedId }.toSet())
        }
    }

    private fun render(entries: List<CandidateEnvelope<Proposition>>): String {
        val keys = immutableList(entries.map { it.key })
        val provenance = try {
            settings.provenance?.resolveSources(keys).orEmpty().mapValues { it.value.toList() }
        } catch (_: Exception) {
            throw ScopedReadException("SCOPED_READ_FAILED")
        }
        if (!keys.containsAll(provenance.keys)) throw ScopedReadException("SCOPED_READ_RESULT_INVALID")
        return entries.joinToString("\n") { entry ->
            val sources = provenance[entry.key].orEmpty().filter { it.isNotBlank() }.distinct().take(2)
                .joinToString("; ") { singleLine(it.trim()).take(80) }
            "- [${singleLine(entry.key.contextIdValue)}/${singleLine(entry.key.propositionId)}] ${singleLine(entry.value.text)}" +
                if (sources.isEmpty()) "" else " (source: $sources)"
        }
    }

    @ScopedMemoryDsl
    class Configuration internal constructor(initialLimits: ScopedReadLimits = ScopedReadLimits.DEFAULT) {
        var topic: String = "the user & context"
        var limits: ScopedReadLimits = initialLimits
        var minConfidence: Double = Memory.DEFAULT_MIN_CONFIDENCE
        var minEffectiveConfidence: Double? = null
        var narrowedBy: UnaryOperator<PropositionQuery>? = null
        var eagerQuery: UnaryOperator<PropositionQuery>? = null
        var eagerTopicSearch: Int? = null
        var eagerTextSearch: TextSimilaritySearchRequest? = null
        var provenance: ScopedProvenanceResolver? = null
    }

    /** Immutable Java configuration; each terminal creates a separate publication cache. */
    class Configuring private constructor(
        private val reader: ScopedPropositionReader,
        private val settings: Settings,
        private val narrowing: List<UnaryOperator<PropositionQuery>>,
        private val eagerQuery: UnaryOperator<PropositionQuery>?,
    ) {
        fun withTopic(topic: String) = copy(settings = settings.copy(topic = topic))
        fun withLimits(limits: ScopedReadLimits) = copy(settings = settings.copy(limits = limits))
        fun withMinConfidence(value: Double) = copy(settings = settings.copy(minConfidence = value))
        fun withMinEffectiveConfidence(value: Double) = copy(settings = settings.copy(minEffectiveConfidence = value))
        fun withNarrowedBy(criteria: UnaryOperator<PropositionQuery>) = copy(narrowing = narrowing + criteria)
        fun withEagerQuery(criteria: UnaryOperator<PropositionQuery>) = copy(eagerQuery = criteria)
        fun withEagerTopicSearch(limit: Int) = copy(settings = settings.copy(eagerTopicSearch = limit))
        fun withEagerTextSearch(request: TextSimilaritySearchRequest) = copy(settings = settings.copy(eagerTextSearch = request))
        fun withProvenance(resolver: ScopedProvenanceResolver) = copy(settings = settings.copy(provenance = resolver))

        fun memory(): ScopedMemory {
            var memory = ScopedMemory(reader, settings).configured(settings)
            narrowing.forEach { memory = memory.withNarrowedBy(it) }
            eagerQuery?.let { memory = memory.withEagerQuery(it) }
            return memory
        }

        private fun copy(
            settings: Settings = this.settings,
            narrowing: List<UnaryOperator<PropositionQuery>> = this.narrowing,
            eagerQuery: UnaryOperator<PropositionQuery>? = this.eagerQuery,
        ) = Configuring(reader, settings, narrowing, eagerQuery)

        internal companion object {
            fun create(reader: ScopedPropositionReader) = Configuring(reader, Settings(limits = reader.limits), emptyList(), null)
        }
    }

    private data class Settings(
        val topic: String = "the user & context",
        val limits: ScopedReadLimits = ScopedReadLimits.DEFAULT,
        val minConfidence: Double = Memory.DEFAULT_MIN_CONFIDENCE,
        val minEffectiveConfidence: Double? = null,
        val eagerQuery: PropositionQuery? = null,
        val eagerTopicSearch: Int? = null,
        val eagerTextSearch: TextSimilaritySearchRequest? = null,
        val provenance: ScopedProvenanceResolver? = null,
    )

    companion object {
        private val mapper = jacksonObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

        @JvmStatic
        fun forContext(contextIdValue: String, repository: PropositionRepository): ScopedMemory =
            Memory.forContexts(ContextReadScope.ofValues(listOf(contextIdValue)), repository)

        @JvmStatic
        fun from(reader: ScopedPropositionReader): ScopedMemory = configuring(reader).memory()

        @JvmStatic
        fun configuring(reader: ScopedPropositionReader): Configuring = Configuring.create(reader)

        @JvmSynthetic
        operator fun invoke(reader: ScopedPropositionReader, configure: Configuration.() -> Unit): ScopedMemory {
            val config = Configuration(reader.limits).apply(configure)
            var chain = configuring(reader).withTopic(config.topic).withLimits(config.limits)
                .withMinConfidence(config.minConfidence)
            config.minEffectiveConfidence?.let { chain = chain.withMinEffectiveConfidence(it) }
            config.narrowedBy?.let { chain = chain.withNarrowedBy(it) }
            config.eagerQuery?.let { chain = chain.withEagerQuery(it) }
            config.eagerTopicSearch?.let { chain = chain.withEagerTopicSearch(it) }
            config.eagerTextSearch?.let { chain = chain.withEagerTextSearch(it) }
            config.provenance?.let { chain = chain.withProvenance(it) }
            return chain.memory()
        }

        private fun validate(settings: Settings) {
            if (listOfNotNull(settings.minConfidence, settings.minEffectiveConfidence).any { !it.isFinite() || it !in 0.0..1.0 } ||
                settings.eagerTopicSearch?.let { it <= 0 || it == Int.MAX_VALUE } == true ||
                settings.eagerQuery?.limit?.let { it < 0 || it == Int.MAX_VALUE } == true ||
                settings.eagerTextSearch?.let { it.topK <= 0 || it.topK == Int.MAX_VALUE ||
                    !it.similarityThreshold.isFinite() || it.similarityThreshold !in 0.0..1.0 } == true
            ) throw ScopedReadException("INVALID_SCOPED_READ")
        }

        private fun snapshot(query: PropositionQuery): PropositionQuery = query.copy(
            anyEntityIds = query.anyEntityIds?.let { immutableList(it) },
            allEntityIds = query.allEntityIds?.let { immutableList(it) },
            statuses = query.statuses?.let { immutableSet(it) },
        )
    }
}

private fun <T> immutableList(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
private fun <T> immutableSet(values: Collection<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))

private fun singleLine(value: String): String = value.replace("\r", " ").replace("\n", " ")

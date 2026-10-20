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
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.ScopedPropositionReader
import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.ScopedReadFailure
import com.embabel.dice.proposition.ScopedReadLimits
import com.embabel.dice.proposition.ScopedReadSession
import com.embabel.dice.proposition.propositionKeyOrder
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
            throw ScopedReadException(ScopedReadFailure.SCOPED_READ_RESULT_INVALID)
        }
        return PublishedEagerSnapshot(
            immutableList(entries), immutableSet(keys), candidate.count, candidate.renderedText,
        ).also { published = it }
    }
}

@DslMarker
annotation class ScopedMemoryDsl

/**
 * A memory tool over several contexts the host has authorized.
 *
 * The host picks the contexts when it opens the reader; the model can only send `query` and
 * `limit`, so it can never widen what it sees. The store must support scoped reads, otherwise
 * opening the reader fails with `SCOPED_READ_UNSUPPORTED`.
 *
 * Unlike [Memory], this never updates `lastAccessed`. The store's touch call takes bare
 * proposition IDs, so it cannot prove a row belongs to one of the authorized contexts. Access
 * tracking stays with [Memory].
 *
 * Use [Memory.forContexts] for the quickest setup, [configuring] from Java, or
 * `ScopedMemory(reader) { }` from Kotlin.
 */
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
    fun withMemoryLimits(limits: MemoryLimits): ScopedMemory = configured(settings.copy(memoryLimits = limits))
    fun withMinConfidence(value: Double): ScopedMemory = configured(settings.copy(minConfidence = value))
    fun withMinEffectiveConfidence(value: Double): ScopedMemory =
        configured(settings.copy(minEffectiveConfidence = value))

    /**
     * Narrows what this memory can see. The hook may add filters only. A sort order or a limit
     * belongs to the read, so either one fails with INVALID_SCOPED_READ.
     */
    fun withNarrowedBy(criteria: UnaryOperator<PropositionQuery>): ScopedMemory =
        configured(settings.copy(narrowing = settings.narrowing + criteria))

    fun withEagerQuery(criteria: UnaryOperator<PropositionQuery>): ScopedMemory =
        configured(settings.copy(eagerQuery = criteria))

    fun withEagerTopicSearch(limit: Int): ScopedMemory = configured(settings.copy(eagerTopicSearch = limit))
    fun withEagerTextSearch(request: TextSimilaritySearchRequest): ScopedMemory =
        configured(settings.copy(eagerTextSearch = request))
    fun withProvenance(resolver: ScopedProvenanceResolver): ScopedMemory =
        configured(settings.copy(provenance = resolver))

    /**
     * The hooks are kept as functions and run again on every read, so a floor set after them still
     * applies. Running them once here against the new settings catches a bad hook before any read.
     */
    private fun configured(settings: Settings): ScopedMemory {
        validate(settings)
        narrowedCriteria(settings).forEach { reader.restrict(it) }
        eagerCriteria(settings)?.let(::requireReadableCriteria)
        // TextSimilaritySearchRequest is an interface, so copying freezes whatever the caller passed in.
        return ScopedMemory(reader, settings.copy(eagerTextSearch = settings.eagerTextSearch?.let { request ->
            TextSimilaritySearchRequest(request.query, request.similarityThreshold, request.topK)
        }))
    }

    /** Restricting validates the criteria; the narrowed reader is not kept. */
    private fun requireReadableCriteria(criteria: PropositionQuery) {
        reader.restrict(criteria.copy(orderBy = PropositionQuery.OrderBy.NONE, limit = null))
    }

    private fun session(): ScopedReadSession {
        var bound = reader.withLimits(settings.limits).restrict(baseQuery(settings))
        narrowedCriteria(settings).forEach { bound = bound.restrict(it) }
        return bound.snapshot(Instant.now(), MEMORY_RANKING_DECAY_K)
    }

    override val definition: Tool.Definition
        get() = memoryToolDefinition(name, description)

    override fun call(input: String): Tool.Result {
        val (query, limit) = when (val parsed = MemoryToolInput.parse(input, settings.memoryLimits.maxResults)) {
            is MemoryToolInput.Invalid -> return Tool.Result.error(parsed.message)
            is MemoryToolInput.Valid -> parsed
        }
        val bound = session().excluding(eager.current()?.keys.orEmpty())
        val selected = ScopedMemoryRetriever(bound, settings.memoryLimits).select(query, limit).entries
        val text = if (selected.isEmpty()) "No additional memories available."
        else render(selected)
        return Tool.Result.text(text)
    }

    override fun contribution(): String = eager.getOrPublish {
        val bound = session()
        val entries = eagerEntries(bound)
        // The count is advisory: rows can change between the two reads, so it never drops below what was read.
        val count = maxOf(bound.count(), entries.size.toLong())
        val body = if (entries.isEmpty()) "" else buildString {
            appendLine("Key memories about ${settings.topic}:")
            append(render(entries))
            if (count > entries.size) append("\n[${count - entries.size} more retrievable via the $name tool]")
        }
        val rendered = memoryReferenceText(name, description, count, body, notes())
        PublishedEagerSnapshot(entries, entries.map { it.key }.toSet(), count, rendered)
    }.renderedText

    private fun eagerEntries(bound: ScopedReadSession): List<CandidateEnvelope<Proposition>> {
        val cap = settings.memoryLimits.maxEager
        if (cap == 0) return emptyList()
        val modes = mutableListOf<List<Proposition>>()
        // Preflight configured vector modes before another mode or count can return partial success.
        if (settings.eagerTopicSearch != null || settings.eagerTextSearch != null) {
            bound.requireScopedVectors()
        }
        settings.eagerTextSearch?.let { request ->
            modes += bound.findSimilarWithScores(TextSimilaritySearchRequest(request.query, request.similarityThreshold, minOf(request.topK, cap))).map { it.match }
        }
        settings.eagerTopicSearch?.let { limit ->
            modes += bound.findSimilarWithScores(
                TextSimilaritySearchRequest(query = settings.topic, topK = minOf(limit, cap), similarityThreshold = 0.0),
            ).map { it.match }
        }
        eagerCriteria(settings)?.let { criteria ->
            modes += bound.restrict(criteria.copy(orderBy = PropositionQuery.OrderBy.NONE, limit = null)).query(criteria.orderBy, minOf(criteria.limit ?: cap, cap))
        }
        val candidates = LinkedHashMap<PropositionKey, Pair<Proposition, Double>>()
        modes.forEach { rows ->
            rows.distinctBy { PropositionKey(it.contextId.value, it.id) }.forEachIndexed { rank, row ->
                val key = PropositionKey(row.contextId.value, row.id)
                val previous = candidates[key]
                candidates[key] = (previous?.first ?: row) to ((previous?.second ?: 0.0) + 1.0 / (MemoryRanking.RRF_K + rank + 1))
            }
        }
        return candidates.entries.sortedWith(
            compareByDescending<Map.Entry<PropositionKey, Pair<Proposition, Double>>> { it.value.second }
                .thenBy(propositionKeyOrder) { it.key },
        ).take(cap).map { (key, value) ->
            CandidateEnvelope(key, value.first, value.first.mentions.mapNotNull { it.resolvedId }.toSet())
        }
    }

    private fun render(entries: List<CandidateEnvelope<Proposition>>): String {
        val keys = immutableList(entries.map { it.key })
        val provenance = try {
            settings.provenance?.resolveSources(keys).orEmpty().mapValues { it.value.toList() }
        } catch (ex: Exception) {
            throw ScopedReadException(ScopedReadFailure.SCOPED_READ_FAILED, ex)
        }
        if (!keys.containsAll(provenance.keys)) throw ScopedReadException(ScopedReadFailure.SCOPED_READ_RESULT_INVALID)
        return entries.joinToString("\n") { entry ->
            val sources = provenance[entry.key].orEmpty().filter { it.isNotBlank() }.distinct().take(2)
                .joinToString("; ") { singleLine(it.trim()).take(80) }
            "- [${singleLine(entry.key.contextIdValue)}/${singleLine(entry.key.propositionId)}] ${singleLine(entry.value.text)}" +
                if (sources.isEmpty()) "" else " (source: $sources)"
        }
    }

    /** The settings block for `ScopedMemory(reader) { }` in Kotlin; anything left alone keeps its default. */
    @ScopedMemoryDsl
    class Configuration internal constructor(initialLimits: ScopedReadLimits = ScopedReadLimits.DEFAULT) {
        private val defaults = Settings(limits = initialLimits)
        var topic: String = defaults.topic
        var limits: ScopedReadLimits = defaults.limits
        var memoryLimits: MemoryLimits = defaults.memoryLimits
        var minConfidence: Double = defaults.minConfidence
        var minEffectiveConfidence: Double? = defaults.minEffectiveConfidence
        var narrowedBy: UnaryOperator<PropositionQuery>? = null
        var eagerQuery: UnaryOperator<PropositionQuery>? = null
        var eagerTopicSearch: Int? = defaults.eagerTopicSearch
        var eagerTextSearch: TextSimilaritySearchRequest? = defaults.eagerTextSearch
        var provenance: ScopedProvenanceResolver? = defaults.provenance
    }

    /** A Java builder for a scoped memory; every `with` call returns a new builder and leaves this one unchanged. */
    class Configuring private constructor(
        private val reader: ScopedPropositionReader,
        private val settings: Settings,
        private val narrowing: List<UnaryOperator<PropositionQuery>>,
        private val eagerQuery: UnaryOperator<PropositionQuery>?,
    ) {
        fun withTopic(topic: String) = copy(settings = settings.copy(topic = topic))
        fun withLimits(limits: ScopedReadLimits) = copy(settings = settings.copy(limits = limits))
        fun withMemoryLimits(limits: MemoryLimits) = copy(settings = settings.copy(memoryLimits = limits))
        fun withMinConfidence(value: Double) = copy(settings = settings.copy(minConfidence = value))
        fun withMinEffectiveConfidence(value: Double) = copy(settings = settings.copy(minEffectiveConfidence = value))
        fun withNarrowedBy(criteria: UnaryOperator<PropositionQuery>) = copy(narrowing = narrowing + criteria)
        fun withEagerQuery(criteria: UnaryOperator<PropositionQuery>) = copy(eagerQuery = criteria)
        fun withEagerTopicSearch(limit: Int) = copy(settings = settings.copy(eagerTopicSearch = limit))
        fun withEagerTextSearch(request: TextSimilaritySearchRequest) = copy(settings = settings.copy(eagerTextSearch = request))
        fun withProvenance(resolver: ScopedProvenanceResolver) = copy(settings = settings.copy(provenance = resolver))

        /** Builds the memory, checking the settings now; each call gives a fresh memory with its own eager cache. */
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
        val topic: String = DEFAULT_MEMORY_TOPIC,
        val limits: ScopedReadLimits = ScopedReadLimits.DEFAULT,
        val memoryLimits: MemoryLimits = MemoryLimits.DEFAULT,
        val minConfidence: Double = Memory.DEFAULT_MIN_CONFIDENCE,
        val minEffectiveConfidence: Double? = null,
        val narrowing: List<UnaryOperator<PropositionQuery>> = emptyList(),
        val eagerQuery: UnaryOperator<PropositionQuery>? = null,
        val eagerTopicSearch: Int? = null,
        val eagerTextSearch: TextSimilaritySearchRequest? = null,
        val provenance: ScopedProvenanceResolver? = null,
    )

    companion object {
        /** A scoped memory over this reader with every setting at its default. */
        @JvmStatic
        fun from(reader: ScopedPropositionReader): ScopedMemory = configuring(reader).memory()

        /** Starts a Java builder over this reader, so settings can be chained before calling `memory()`. */
        @JvmStatic
        fun configuring(reader: ScopedPropositionReader): Configuring = Configuring.create(reader)

        /** Builds a scoped memory in Kotlin, with the settings given in a block: `ScopedMemory(reader) { topic = "..." }`. */
        @JvmSynthetic
        operator fun invoke(reader: ScopedPropositionReader, configure: Configuration.() -> Unit): ScopedMemory {
            val config = Configuration(reader.limits).apply(configure)
            var chain = configuring(reader).withTopic(config.topic).withLimits(config.limits)
                .withMemoryLimits(config.memoryLimits)
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
                eagerCriteria(settings)?.limit?.let { it < 0 || it == Int.MAX_VALUE } == true ||
                settings.eagerTextSearch?.let { it.topK <= 0 || it.topK == Int.MAX_VALUE ||
                    !it.similarityThreshold.isFinite() || it.similarityThreshold !in 0.0..1.0 } == true ||
                exceedsRowCap(settings)
            ) throw ScopedReadException(ScopedReadFailure.INVALID_SCOPED_READ)
        }

        /**
         * True when a read this memory will make could ask for more rows than the read limits allow.
         * A tool call probes one more row than its candidate pool, and the eager load reads up to
         * maxEager, so both must fit under maxRows. Catching it here means a bad pairing fails at
         * configuration instead of on the model's first call.
         */
        private fun exceedsRowCap(settings: Settings): Boolean =
            candidateCapacity(settings.memoryLimits.maxResults) + 1 > settings.limits.maxRows ||
                settings.memoryLimits.maxEager > settings.limits.maxRows

        private fun baseQuery(settings: Settings) = PropositionQuery(
            statuses = setOf(PropositionStatus.ACTIVE), minConfidence = settings.minConfidence,
            minEffectiveConfidence = settings.minEffectiveConfidence,
        )

        /** Each narrowing hook applied to the base query for these settings; every result must also match. */
        private fun narrowedCriteria(settings: Settings): List<PropositionQuery> {
            val base = baseQuery(settings)
            return settings.narrowing.map { snapshot(it.apply(base)) }
        }

        private fun eagerCriteria(settings: Settings): PropositionQuery? =
            settings.eagerQuery?.let { snapshot(it.apply(baseQuery(settings))) }

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

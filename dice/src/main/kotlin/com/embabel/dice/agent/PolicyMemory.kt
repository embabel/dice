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
import com.embabel.dice.proposition.ContextSetVectorSearchCapable
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.proposition.ScopedPropositionReader
import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.ScopedReadFailure
import com.embabel.dice.proposition.ScopedReadLimits
import com.embabel.dice.proposition.ScopedReadSession
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.proposition.StrictPolicyReadCapable
import com.embabel.dice.proposition.StrictReadRow
import com.embabel.dice.proposition.matchesFilters
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.EnumSet
import java.util.Locale

/** Marks the Kotlin configuration block so code inside it can't reach an outer block's settings by accident. */
@DslMarker
annotation class PolicyMemoryDsl

/**
 * The ways a policy memory may search: by embedding similarity, by keywords in approved text, or by
 * entities the approved rows mention. With none, it can only list rows.
 */
enum class PolicySearchTier { VECTOR, KEYWORD, RELATED }

private fun PolicySearchTier.memoryTier(): MemoryTier = when (this) {
    PolicySearchTier.VECTOR -> MemoryTier.VECTOR
    PolicySearchTier.KEYWORD -> MemoryTier.KEYWORD
    PolicySearchTier.RELATED -> MemoryTier.RELATED
}

private fun Set<PolicySearchTier>.memoryTiers(): Set<MemoryTier> = mapTo(EnumSet.noneOf(MemoryTier::class.java)) { it.memoryTier() }

/**
 * What one host request may read: a scoped reader cut down to the rows a row policy grants, plus the
 * memory limits. Build a new view when the grants or the principal change.
 */
class PolicyReadView private constructor(
    private val base: ScopedPropositionReader,
    val policy: RowPolicy,
    val memoryLimits: MemoryLimits,
) {
    val scope: ContextReadScope get() = base.scope
    val limits: ScopedReadLimits get() = base.limits

    fun restrict(criteria: PropositionQuery): PolicyReadView = policyCall {
        PolicyReadView(base.restrict(criteria), policy, memoryLimits)
    }

    /** Fixes the read clock. The view is already narrowed to its grants, and the session checks the policy before its first read. */
    fun snapshot(asOf: Instant, rankingDecayK: Double): PolicyReadSession = policyCall {
        val bound = base.snapshot(asOf, rankingDecayK)
        PolicyReadSession(base.store, policy, bound, memoryLimits)
    }

    companion object {
        /**
         * Opens a view over the rows the policy grants. This is the only place the grant restriction is
         * applied; every later view and session is built from the narrowed reader. Memory caps that would
         * need more candidate rows than the reader allows fail here, not on the first model call.
         */
        @JvmStatic
        @JvmOverloads
        fun open(base: ScopedPropositionReader, policy: RowPolicy, memoryLimits: MemoryLimits = MemoryLimits.DEFAULT): PolicyReadView = policyCall {
            policy.validate(ScopedReadSpec.of(base.scope))
            if (candidateCapacity(maxOf(memoryLimits.maxResults, memoryLimits.maxEager)) + 1 > base.limits.maxRows) {
                throw ScopedReadException(ScopedReadFailure.INVALID_SCOPED_READ)
            }
            PolicyReadView(base.includingOnly(policy.grants.map { it.key }), policy, memoryLimits)
        }
    }
}

/**
 * A view pinned to one read instant. It reads only granted rows and turns each into a projected memory
 * that holds just the fields its grant approves, so nothing else reaches ranking or rendering.
 */
class PolicyReadSession internal constructor(
    private val store: PropositionStore,
    internal val policy: RowPolicy,
    private val bound: ScopedReadSession,
    internal val memoryLimits: MemoryLimits,
) {
    internal val limits: ScopedReadLimits get() = bound.limits
    private val grants = policy.grants.associateBy { it.key }
    private val ordinal = policy.grants.mapIndexed { index, grant -> grant.key to index }.toMap()
    private val strict: Boolean get() = policy.influence == HiddenFieldInfluence.STRICT
    private val rows = policy.grants.map { grant ->
        StrictReadRow(grant.key, grant.projection.text)
    }

    fun count(): Long = policyCall {
        validate()
        if (grants.isEmpty()) return@policyCall 0L
        val count = bound.count()
        if (count > grants.size) throw ScopedReadException(ScopedReadFailure.SCOPED_READ_RESULT_INVALID)
        count
    }

    fun get(key: PropositionKey): ProjectedMemory? = policyCall {
        validate()
        if (key.contextIdValue !in bound.scope.contextIdValues || key.propositionId.isBlank()) {
            throw ScopedReadException(ScopedReadFailure.INVALID_SCOPED_READ)
        }
        if (key !in grants) return@policyCall null
        project(bound.includingOnly(listOf(key)).query(PropositionQuery.OrderBy.NONE, 1), 1,
            bound.spec.includingOnly(listOf(key))).singleOrNull()?.value
    }

    fun list(limit: Int): List<ProjectedMemory> = policyCall {
        if (limit < 0 || limit > memoryLimits.maxResults) throw ScopedReadException(ScopedReadFailure.INVALID_SCOPED_READ)
        listing(limit).map { it.value }
    }

    internal fun excluding(keys: Set<PropositionKey>) = PolicyReadSession(store, policy, bound.excluding(keys), memoryLimits)
    /** Narrows to the rows the criteria match. The caller keeps the criteria's order and limit for itself. */
    internal fun restricted(criteria: PropositionQuery): PolicyReadSession {
        val membership = criteria.copy(orderBy = PropositionQuery.OrderBy.NONE, limit = null)
        return PolicyReadSession(store, policy, bound.restrict(membership), memoryLimits).also { it.validate() }
    }

    internal fun preflight(tiers: Set<MemoryTier>) {
        validate()
        if (grants.isEmpty()) return
        if (strict) strictBackend()
        if (MemoryTier.VECTOR in tiers) {
            policy.validate(bound.spec, PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC,
                vectorSearch = true, scoreInputs = setOf(MemoryField.OWNER, MemoryField.ID))
            val vector = store as? ContextSetVectorSearchCapable
            if (vector == null || !vector.supportsScopedVectors) throw ScopedReadException(ScopedReadFailure.SCOPED_VECTOR_UNSUPPORTED)
        }
        if (MemoryTier.KEYWORD in tiers) {
            policy.validate(bound.spec, keywordSearch = true)
        }
        if (MemoryTier.RELATED in tiers) {
            policy.validate(bound.spec, scoreInputs = setOf(MemoryField.ENTITY_IDS))
        }
    }

    /** Picks rows for a tool call. With no search tiers the query is ignored and the rows are listed. */
    internal fun selection(query: String, limit: Int, tiers: Set<MemoryTier>): MemorySelection<ProjectedMemory> {
        capacity(limit, memoryLimits.maxResults)
        preflight(tiers)
        if (grants.isEmpty()) return MemorySelection(emptyList(), false)
        return engine(tiers).select(if (tiers.isEmpty()) "" else query, limit)
    }

    internal fun eagerSearch(request: TextSimilaritySearchRequest): List<CandidateEnvelope<ProjectedMemory>> {
        capacity(request.topK, memoryLimits.maxEager)
        preflight(setOf(MemoryTier.VECTOR))
        if (grants.isEmpty()) return emptyList()
        return engine(setOf(MemoryTier.VECTOR), request.similarityThreshold).select(request.query, request.topK).entries
    }

    internal fun eagerList(criteria: PropositionQuery, limit: Int): List<CandidateEnvelope<ProjectedMemory>> {
        val session = restricted(criteria)
        session.preflight(emptySet())
        capacity(limit, memoryLimits.maxEager)
        val order = criteria.orderBy
        if (strict && order != PropositionQuery.OrderBy.NONE) throw ScopedReadException(ScopedReadFailure.SCOPED_READ_UNSUPPORTED)
        return if (order == PropositionQuery.OrderBy.NONE) session.engine(emptySet()).select("", limit).entries
        else {
            policy.validate(session.bound.spec, order,
                scoreInputs = if (strict) setOf(MemoryField.OWNER, MemoryField.ID) else emptySet())
            if (grants.isEmpty()) emptyList()
            else session.project(session.bound.query(order, limit), limit)
        }
    }

    internal fun capacity(limit: Int, cap: Int) {
        if (limit < 0 || limit > cap || candidateCapacity(limit) + 1 > limits.maxRows) {
            throw ScopedReadException(ScopedReadFailure.INVALID_SCOPED_READ)
        }
    }

    internal fun validateEager(criteria: PropositionQuery?, vectors: Boolean) {
        preflight(if (vectors) setOf(MemoryTier.VECTOR) else emptySet())
        criteria?.let {
            val restricted = restricted(it)
            val order = it.orderBy
            if (strict && order != PropositionQuery.OrderBy.NONE) throw ScopedReadException(ScopedReadFailure.SCOPED_READ_UNSUPPORTED)
            policy.validate(restricted.bound.spec, order,
                scoreInputs = if (strict && order != PropositionQuery.OrderBy.NONE) setOf(MemoryField.OWNER, MemoryField.ID) else emptySet())
        }
    }

    private fun engine(tiers: Set<MemoryTier>, threshold: Double = 0.0) = MemorySelectionEngine(
        object : CandidateOperations<ProjectedMemory> {
            override fun preflight(tiers: Set<MemoryTier>) { this@PolicyReadSession.preflight(tiers) }
            override fun count(): Long = this@PolicyReadSession.count()
            override fun list(limit: Int) = listing(limit)
            override fun vector(query: String, limit: Int): List<CandidateEnvelope<ProjectedMemory>> {
                val scored = bound.findSimilarWithScores(TextSimilaritySearchRequest(query, threshold, limit))
                // The raw match never enters fusion. Its score is projected only when approved.
                return scored.map { result -> project(listOf(result.match), 1, score = result.score).single() }
            }
            override fun keyword(tokens: List<String>, limit: Int): List<CandidateEnvelope<ProjectedMemory>> {
                if (tokens.isEmpty()) return emptyList()
                val selected = if (strict) strictBackend().keywordByApprovedText(
                    bound.spec, bound.clock, rows, tokens, limit, timeout(),
                ) else bound.keywordOverlap(tokens, limit)
                val projected = project(selected, limit)
                if (strict) {
                    val approvedScores = projected.map { candidate ->
                        tokens.distinct().count { token -> candidate.value.text?.lowercase(Locale.ROOT)?.contains(token) == true }
                    }
                    if (approvedScores.any { it <= 0 } || projected.indices.drop(1).any { index ->
                            approvedScores[index] > approvedScores[index - 1] ||
                                (approvedScores[index] == approvedScores[index - 1] &&
                                    ordinal.getValue(projected[index].key) < ordinal.getValue(projected[index - 1].key))
                        }
                    ) throw ScopedReadException(ScopedReadFailure.SCOPED_READ_RESULT_INVALID)
                }
                return projected
            }
            override fun related(entityIds: Set<String>, limit: Int): List<CandidateEnvelope<ProjectedMemory>> =
                restricted(PropositionQuery(anyEntityIds = entityIds.sorted())).listing(limit)
        },
        compareBy<CandidateEnvelope<ProjectedMemory>> { ordinal.getValue(it.key) }, tiers,
    )

    private fun listing(limit: Int): List<CandidateEnvelope<ProjectedMemory>> {
        validate()
        if (limit < 0 || limit > limits.maxRows) throw ScopedReadException(ScopedReadFailure.INVALID_SCOPED_READ)
        if (grants.isEmpty() || limit == 0) return emptyList()
        val selected = if (strict) strictBackend().queryByGrantOrder(bound.spec, bound.clock, rows, limit, timeout())
        else bound.query(PropositionQuery.OrderBy.NONE, limit)
        val projected = project(selected, limit)
        if (strict && projected.zipWithNext().any { (left, right) ->
                ordinal.getValue(left.key) > ordinal.getValue(right.key)
            }
        ) throw ScopedReadException(ScopedReadFailure.SCOPED_READ_RESULT_INVALID)
        return projected
    }

    private fun strictBackend(): StrictPolicyReadCapable {
        val backend = store as? StrictPolicyReadCapable
        if (backend == null || !backend.supportsStrictPolicyReads) throw ScopedReadException(ScopedReadFailure.SCOPED_READ_UNSUPPORTED)
        return backend
    }

    private fun timeout(): Duration = minOf(limits.vectorBudget.timeout, Duration.ofSeconds(5))
    // The spec never changes within a session, so the policy check runs once, before the first read.
    private val validation by lazy { policy.validate(bound.spec) }
    private fun validate() = validation

    private fun project(
        values: List<Proposition>, limit: Int, spec: ScopedReadSpec = bound.spec, score: Double? = null,
    ): List<CandidateEnvelope<ProjectedMemory>> {
        if (values.size > limit) throw ScopedReadException(ScopedReadFailure.SCOPED_READ_RESULT_INVALID)
        val seen = HashSet<PropositionKey>()
        return values.map { proposition ->
            val key = PropositionKey(proposition.contextId.value, proposition.id)
            val grant = grants[key]
            if (grant == null || !seen.add(key) || key.contextIdValue !in bound.scope.contextIdValues ||
                (spec.includedKeys != null && key !in spec.includedKeys) || key in spec.excludedKeys ||
                spec.fragments.any { !it.matchesFilters(proposition, bound.clock.asOf) }
            ) throw ScopedReadException(ScopedReadFailure.SCOPED_READ_RESULT_INVALID)
            val dto = ProjectedMemory.from(grant, proposition, score, bound.clock)
            CandidateEnvelope(key, dto, dto.entityIds.orEmpty().toSet())
        }
    }
}

/**
 * A memory tool whose reads follow a row policy. It lists, searches and preloads only granted rows, and
 * shows the model each one as JSON with only the approved fields.
 */
class PolicyMemory private constructor(private val view: PolicyReadView, private val settings: Settings) : LlmReference, Tool {
    private val eager = EagerPublication<ProjectedMemory>()
    override val name: String = "memory"
    override val description: String get() = "Memories about ${settings.topic}"
    override fun notes(): String = "Search tiers: " + settings.searchTiers.joinToString { it.name.lowercase(Locale.ROOT) }
        .ifEmpty { "listing only" }
    override fun tools(): List<Tool> = listOf(this)

    override val definition: Tool.Definition
        get() = memoryToolDefinition(name, description, searchable = settings.searchTiers.isNotEmpty())

    override fun call(input: String): Tool.Result {
        val (query, limit) = when (val parsed = MemoryToolInput.parse(input, view.memoryLimits.maxResults)) {
            is MemoryToolInput.Invalid -> return Tool.Result.error(parsed.message)
            is MemoryToolInput.Valid -> parsed
        }
        return policyCall {
            val session = view.snapshot(Instant.now(), MEMORY_RANKING_DECAY_K).excluding(eager.current()?.keys.orEmpty())
            val selected = session.selection(query, limit, settings.searchTiers.memoryTiers()).entries
            Tool.Result.text(if (selected.isEmpty()) "No additional memories available." else render(session, selected))
        }
    }

    override fun contribution(): String = policyCall {
        eager.getOrPublish {
            val bound = view.snapshot(Instant.now(), MEMORY_RANKING_DECAY_K)
            val tiers = settings.searchTiers.memoryTiers()
            val cap = view.memoryLimits.maxEager
            // Complete preflight precedes even the count when no eager mode is configured.
            bound.capacity(cap, cap)
            bound.preflight(tiers)
            bound.validateEager(settings.eagerQuery, settings.eagerTopicSearch != null || settings.eagerTextSearch != null)
            val modes = mutableListOf<List<CandidateEnvelope<ProjectedMemory>>>()
            if (cap > 0) {
                settings.eagerTextSearch?.let { modes += bound.eagerSearch(TextSimilaritySearchRequest(it.query, it.similarityThreshold, minOf(it.topK, cap))) }
                settings.eagerTopicSearch?.let { modes += bound.eagerSearch(TextSimilaritySearchRequest(settings.topic, 0.0, minOf(it, cap))) }
                settings.eagerQuery?.let { modes += bound.eagerList(it, minOf(it.limit ?: cap, cap)) }
            }
            val entries = fuseEager(modes).take(cap)
            // The count is advisory: rows can change between the two reads, so it never drops below what was read.
            val count = maxOf(bound.count(), entries.size.toLong())
            val body = if (entries.isEmpty()) "" else render(bound, entries)
            val rendered = memoryReferenceText(name, description, count, body, notes())
            PublishedEagerSnapshot(entries, entries.map { it.key }.toSet(), count, rendered)
        }.renderedText
    }

    /**
     * Ranks the eager modes together the same way ScopedMemory does: a row at 1-based rank r in a
     * mode earns 1 / (RRF_K + r), and a row several modes found adds up its shares. Equal scores
     * fall back to the host's grant order, so nothing the policy hides can decide a tie.
     */
    private fun fuseEager(modes: List<List<CandidateEnvelope<ProjectedMemory>>>): List<CandidateEnvelope<ProjectedMemory>> {
        val scored = LinkedHashMap<PropositionKey, Pair<CandidateEnvelope<ProjectedMemory>, Double>>()
        modes.forEach { entries ->
            entries.distinctBy { it.key }.forEachIndexed { index, entry ->
                val previous = scored[entry.key]
                scored[entry.key] = (previous?.first ?: entry) to ((previous?.second ?: 0.0) + 1.0 / (MemoryRanking.RRF_K + index + 1))
            }
        }
        val grantOrder = view.policy.grants.mapIndexed { index, grant -> grant.key to index }.toMap()
        return scored.values.sortedWith(
            compareByDescending<Pair<CandidateEnvelope<ProjectedMemory>, Double>> { it.second }
                .thenBy { grantOrder.getValue(it.first.key) },
        ).map { it.first }
    }

    private fun render(session: PolicyReadSession, entries: List<CandidateEnvelope<ProjectedMemory>>): String {
        session.preflight(emptySet())
        val grants = view.policy.grants.associateBy { it.key }
        val requests = entries.flatMap { entry ->
            val grant = grants[entry.key] ?: throw ScopedReadException(ScopedReadFailure.SCOPED_READ_RESULT_INVALID)
            grant.projection.sources.filter { it.descriptor || it.locator }.map { source ->
                PolicyProvenanceRequest(PolicySourceKey(entry.key, source.sourceId), source.descriptor, source.locator)
            }
        }
        val resolved = if (requests.isEmpty()) emptyMap() else
            settings.provenance?.resolve(view.policy.stamp, immutablePolicyList(requests)).orEmpty().toMap()
        val requested = requests.associateBy { it.key }
        resolved.forEach { (key, source) ->
            val request = requested[key] ?: throw ScopedReadException(ScopedReadFailure.SCOPED_READ_RESULT_INVALID)
            if ((!request.descriptor && source.descriptor != null) || (!request.locator && source.locator != null)) {
                throw ScopedReadException(ScopedReadFailure.SCOPED_READ_RESULT_INVALID)
            }
        }
        return entries.joinToString("\n") { entry ->
            val dto = mapper.valueToTree<ObjectNode>(entry.value)
            // A wired host resolver is the authority on sources; without one, the row's own approved sources stand.
            val sources = if (settings.provenance == null) entry.value.sources
            else requests.filter { it.key.proposition == entry.key }.mapNotNull { resolved[it.key] }
            dto.set<JsonNode>("sources", mapper.valueToTree(sources.distinct()))
            "- " + mapper.writeValueAsString(dto)
        }
    }

    /** The settings a Kotlin caller fills in inside the `PolicyMemory(view) { ... }` block. */
    @PolicyMemoryDsl
    class Configuration internal constructor() {
        var topic: String = DEFAULT_MEMORY_TOPIC
        var searchTiers: Set<PolicySearchTier> = emptySet()
        var eagerQuery: PropositionQuery? = null
        var eagerTopicSearch: Int? = null
        var eagerTextSearch: TextSimilaritySearchRequest? = null
        var provenance: PolicyProvenanceResolver? = null
    }

    /** The Java chain started by [configuring]: each `with` method returns a new chain, and [memory] builds the tool. */
    class Configuring private constructor(private val view: PolicyReadView, private val settings: Settings) {
        fun withTopic(topic: String) = Configuring(view, settings.copy(topic = topic))
        fun withSearchTiers(tiers: Set<PolicySearchTier>) = Configuring(view, settings.copy(
            searchTiers = Collections.unmodifiableSet(EnumSet.noneOf(PolicySearchTier::class.java).apply { addAll(tiers) }),
        ))
        fun withEagerQuery(criteria: PropositionQuery) = Configuring(view, settings.copy(eagerQuery = criteria.copy(
            anyEntityIds = criteria.anyEntityIds?.let { immutablePolicyList(it) },
            allEntityIds = criteria.allEntityIds?.let { immutablePolicyList(it) },
            statuses = criteria.statuses?.let { immutablePolicySet(it) },
        )))
        fun withEagerTopicSearch(limit: Int) = Configuring(view, settings.copy(eagerTopicSearch = limit))
        fun withEagerTextSearch(request: TextSimilaritySearchRequest) = Configuring(view,
            settings.copy(eagerTextSearch = TextSimilaritySearchRequest(request.query, request.similarityThreshold, request.topK)))
        fun withProvenance(resolver: PolicyProvenanceResolver) = Configuring(view, settings.copy(provenance = resolver))
        fun memory(): PolicyMemory = configured(view, settings)

        internal companion object {
            fun create(view: PolicyReadView) = Configuring(view, Settings())
        }
    }

    private data class Settings(
        val topic: String = DEFAULT_MEMORY_TOPIC,
        val searchTiers: Set<PolicySearchTier> = emptySet(),
        val eagerQuery: PropositionQuery? = null,
        val eagerTopicSearch: Int? = null,
        val eagerTextSearch: TextSimilaritySearchRequest? = null,
        val provenance: PolicyProvenanceResolver? = null,
    )

    companion object {
        private val mapper = jacksonObjectMapper().registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        /**
         * Checks settings the chain has already snapshotted, then checks them against the view
         * before any memory exists, so a bad configuration fails here and not on first use.
         */
        private fun configured(view: PolicyReadView, settings: Settings): PolicyMemory = policyCall {
            if (settings.eagerTopicSearch?.let { it <= 0 || it == Int.MAX_VALUE || settings.topic.isBlank() } == true ||
                settings.eagerQuery?.limit?.let { it < 0 || it == Int.MAX_VALUE } == true ||
                settings.eagerTextSearch?.let { it.query.isBlank() || it.topK <= 0 || it.topK == Int.MAX_VALUE ||
                    !it.similarityThreshold.isFinite() || it.similarityThreshold !in 0.0..1.0 } == true
            ) throw ScopedReadException(ScopedReadFailure.INVALID_SCOPED_READ)
            if ((settings.eagerTopicSearch != null || settings.eagerTextSearch != null) &&
                PolicySearchTier.VECTOR !in settings.searchTiers
            ) throw ScopedReadException(ScopedReadFailure.SCOPED_READ_UNSUPPORTED)
            val bound = view.snapshot(Instant.now(), MEMORY_RANKING_DECAY_K)
            bound.preflight(settings.searchTiers.memoryTiers())
            bound.validateEager(settings.eagerQuery, settings.eagerTopicSearch != null || settings.eagerTextSearch != null)
            PolicyMemory(view, settings)
        }

        /**
         * The shortest way in from Java or Kotlin: a memory over a view with default settings and the given search tiers.
         *
         * @param searchTiers how the tool may search; leave empty for a tool that only lists
         */
        @JvmStatic
        @JvmOverloads
        fun from(view: PolicyReadView, searchTiers: Set<PolicySearchTier> = emptySet()): PolicyMemory =
            configuring(view).withSearchTiers(searchTiers).memory()

        /** Starts the Java chain: call the `with` methods you need, then `memory()`. */
        @JvmStatic
        fun configuring(view: PolicyReadView): Configuring = Configuring.create(view)

        /** Builds a memory from Kotlin, setting options in a block such as `PolicyMemory(view) { topic = "orders" }`. */
        @JvmSynthetic
        operator fun invoke(view: PolicyReadView, configure: Configuration.() -> Unit): PolicyMemory {
            val config = Configuration().apply(configure)
            var chain = configuring(view).withTopic(config.topic).withSearchTiers(config.searchTiers)
            config.eagerQuery?.let { chain = chain.withEagerQuery(it) }
            config.eagerTopicSearch?.let { chain = chain.withEagerTopicSearch(it) }
            config.eagerTextSearch?.let { chain = chain.withEagerTextSearch(it) }
            config.provenance?.let { chain = chain.withProvenance(it) }
            return chain.memory()
        }
    }
}

private val logger = LoggerFactory.getLogger(PolicyMemory::class.java)

/** Turns a failure in a public policy call into a scoped read failure, keeping the cause for operators. */
private inline fun <T> policyCall(operation: () -> T): T = try {
    operation()
} catch (ex: ScopedReadException) {
    throw ex
} catch (ex: IllegalArgumentException) {
    logger.debug("Policy memory rejected its input", ex)
    throw ScopedReadException(ScopedReadFailure.INVALID_SCOPED_READ, ex)
} catch (ex: Exception) {
    logger.warn("Policy memory read failed", ex)
    throw ScopedReadException(ScopedReadFailure.SCOPED_READ_FAILED, ex)
}

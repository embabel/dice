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
import com.embabel.dice.proposition.ApprovedSearchText
import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.ContextSetVectorSearchCapable
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.proposition.ReadOrder
import com.embabel.dice.proposition.ScopedPropositionReader
import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.ScopedReadLimits
import com.embabel.dice.proposition.ScopedReadSession
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.proposition.StrictPolicyReadCapable
import com.embabel.dice.proposition.StrictReadRow
import com.embabel.dice.proposition.matchesFilters
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.Locale

@DslMarker
annotation class PolicyMemoryDsl

enum class PolicySearchTier { VECTOR, KEYWORD, RELATED }

/** Listing is available without permitting any search influence. */
class PolicyMemoryPlan private constructor(tiers: Set<PolicySearchTier>) {
    val searchTiers: Set<PolicySearchTier> = immutablePolicySet(tiers)

    init { require((searchTiers as Set<*>).all { it is PolicySearchTier }) { "Invalid policy plan" } }

    fun withSearchTiers(tiers: Set<PolicySearchTier>): PolicyMemoryPlan = policyCall { PolicyMemoryPlan(tiers) }

    class Configuring internal constructor(tiers: Set<PolicySearchTier> = emptySet()) {
        private val tiers = immutablePolicySet(tiers)
        fun withSearchTiers(tiers: Set<PolicySearchTier>) = Configuring(tiers)
        fun plan(): PolicyMemoryPlan = policyCall { PolicyMemoryPlan(tiers) }
    }

    @PolicyMemoryDsl
    class Configuration internal constructor() {
        var searchTiers: Set<PolicySearchTier> = emptySet()
    }

    companion object {
        @JvmStatic
        fun listingOnly(): PolicyMemoryPlan = PolicyMemoryPlan(emptySet())

        @JvmStatic
        fun configuring(): Configuring = Configuring()

        @JvmSynthetic
        operator fun invoke(configure: Configuration.() -> Unit): PolicyMemoryPlan = policyCall {
            PolicyMemoryPlan(Configuration().apply(configure).searchTiers)
        }
    }
}

/** One immutable host-resolved view; callers replace it when grants or principal change. */
class PolicyReadView private constructor(
    private val base: ScopedPropositionReader,
    val policy: RowPolicy,
) {
    val scope: ContextReadScope get() = base.scope
    val limits: ScopedReadLimits get() = base.limits

    fun restrict(criteria: PropositionQuery): PolicyReadView = policyCall {
        PolicyReadView(base.restrict(criteria), policy)
    }

    fun snapshot(asOf: Instant, rankingDecayK: Double): PolicyReadSession = policyCall {
        val bound = base.includingOnly(policy.grants.map { it.key }).snapshot(asOf, rankingDecayK)
        policy.validate(scope, bound.spec)
        PolicyReadSession(base.store, policy, bound)
    }

    companion object {
        @JvmStatic
        fun open(base: ScopedPropositionReader, policy: RowPolicy): PolicyReadView = policyCall {
            policy.validate(base.scope, ScopedReadSpec.at(Instant.EPOCH, 2.0))
            PolicyReadView(base.includingOnly(policy.grants.map { it.key }), policy)
        }
    }
}

/** All raw rows terminate here, before a candidate envelope or renderer can observe them. */
class PolicyReadSession internal constructor(
    private val store: PropositionStore,
    internal val policy: RowPolicy,
    private val bound: ScopedReadSession,
) {
    internal val limits: ScopedReadLimits get() = bound.limits
    private val grants = policy.grants.associateBy { it.key }
    private val ordinal = policy.grants.mapIndexed { index, grant -> grant.key to index }.toMap()
    private val strict: Boolean get() = policy.influence == HiddenFieldInfluence.STRICT
    private val rows = policy.grants.mapIndexed { index, grant ->
        StrictReadRow(grant.key, index, when (val text = grant.projection.text) {
            ApprovedText.Omitted -> ApprovedSearchText.Omitted
            ApprovedText.Stored -> ApprovedSearchText.Stored
            is ApprovedText.Literal -> ApprovedSearchText.Literal(text.value)
        })
    }

    fun count(): Long = policyCall {
        validate()
        if (grants.isEmpty()) return@policyCall 0L
        val count = bound.count()
        if (count > grants.size) throw ScopedReadException("SCOPED_READ_RESULT_INVALID")
        count
    }

    fun get(key: PropositionKey): ProjectedMemory? = policyCall {
        validate()
        if (key.contextIdValue !in bound.scope.contextIdValues || key.propositionId.isBlank()) {
            throw ScopedReadException("INVALID_SCOPED_READ")
        }
        if (key !in grants) return@policyCall null
        project(bound.includingOnly(listOf(key)).query(ReadOrder.NONE, 1), 1,
            bound.spec.includingOnly(bound.scope, listOf(key))).singleOrNull()?.value
    }

    fun list(limit: Int): List<ProjectedMemory> = policyCall {
        if (limit < 0 || limit > limits.maxResults) throw ScopedReadException("INVALID_SCOPED_READ")
        listing(limit).map { it.value }
    }

    internal fun excluding(keys: Set<PropositionKey>) = PolicyReadSession(store, policy, bound.excluding(keys))
    internal fun restricted(criteria: PropositionQuery): PolicyReadSession = policyCall {
        PolicyReadSession(store, policy, bound.restrict(criteria)).also { it.validate() }
    }

    internal fun preflight(tiers: Set<MemoryTier>) = policyCall {
        validate()
        if (grants.isEmpty()) return@policyCall
        if (strict) strictBackend()
        if (MemoryTier.VECTOR in tiers) {
            policy.validate(bound.scope, bound.spec, ReadOrder.EFFECTIVE_CONFIDENCE_DESC,
                vectorSearch = true, scoreInputs = setOf(MemoryField.OWNER, MemoryField.ID))
            val vector = store as? ContextSetVectorSearchCapable
            if (vector == null || !vector.supportsScopedVectors) throw ScopedReadException("SCOPED_VECTOR_UNSUPPORTED")
        }
        if (MemoryTier.KEYWORD in tiers) {
            policy.validate(bound.scope, bound.spec, keywordSearch = true)
        }
        if (MemoryTier.RELATED in tiers) {
            policy.validate(bound.scope, bound.spec, scoreInputs = setOf(MemoryField.ENTITY_IDS))
        }
    }

    internal fun selection(query: String, limit: Int, plan: PolicyMemoryPlan): MemorySelection<ProjectedMemory> = policyCall {
        capacity(limit, limits.maxResults)
        if (query.isNotBlank() && plan.searchTiers.isEmpty()) throw ScopedReadException("SCOPED_READ_UNSUPPORTED")
        val tiers = plan.searchTiers.map { MemoryTier.valueOf(it.name) }.toSet()
        preflight(tiers)
        if (grants.isEmpty()) return@policyCall MemorySelection(emptyList(), false)
        engine(tiers).select(query, limit)
    }

    internal fun eagerSearch(request: TextSimilaritySearchRequest): List<CandidateEnvelope<ProjectedMemory>> = policyCall {
        capacity(request.topK, limits.maxEager)
        preflight(setOf(MemoryTier.VECTOR))
        if (grants.isEmpty()) return@policyCall emptyList()
        engine(setOf(MemoryTier.VECTOR), request.similarityThreshold).select(request.query, request.topK).entries
    }

    internal fun eagerList(criteria: PropositionQuery, limit: Int): List<CandidateEnvelope<ProjectedMemory>> = policyCall {
        val session = restricted(criteria)
        session.preflight(emptySet())
        capacity(limit, limits.maxEager)
        val order = ReadOrder.valueOf(criteria.orderBy.name)
        if (strict && order != ReadOrder.NONE) throw ScopedReadException("SCOPED_READ_UNSUPPORTED")
        if (order == ReadOrder.NONE) session.engine(emptySet()).select("", limit).entries
        else {
            policy.validate(bound.scope, session.bound.spec, order,
                scoreInputs = if (strict) setOf(MemoryField.OWNER, MemoryField.ID) else emptySet())
            if (grants.isEmpty()) emptyList()
            else session.project(session.bound.query(order, limit), limit)
        }
    }

    internal fun capacity(limit: Int, cap: Int) {
        if (limit < 0 || limit > cap || candidateCapacity(limit) + 1 > limits.maxProbe) {
            throw ScopedReadException("INVALID_SCOPED_READ")
        }
    }

    internal fun validateEager(criteria: PropositionQuery?, vectors: Boolean) = policyCall {
        preflight(if (vectors) setOf(MemoryTier.VECTOR) else emptySet())
        criteria?.let {
            val restricted = restricted(it)
            val order = ReadOrder.valueOf(it.orderBy.name)
            if (strict && order != ReadOrder.NONE) throw ScopedReadException("SCOPED_READ_UNSUPPORTED")
            policy.validate(bound.scope, restricted.bound.spec, order,
                scoreInputs = if (strict && order != ReadOrder.NONE) setOf(MemoryField.OWNER, MemoryField.ID) else emptySet())
        }
    }

    private fun engine(tiers: Set<MemoryTier>, threshold: Double = 0.0) = MemorySelectionEngine(
        object : CandidateOperations<ProjectedMemory> {
            override fun preflight(tiers: Set<MemoryTier>) { this@PolicyReadSession.preflight(tiers) }
            override fun count(): Long = this@PolicyReadSession.count()
            override fun list(limit: Int) = listing(limit)
            override fun vector(query: String, limit: Int): List<CandidateEnvelope<ProjectedMemory>> = policyCall {
                val scored = bound.findSimilarWithScores(TextSimilaritySearchRequest(query, threshold, limit))
                // The raw match never enters fusion. Its score is projected only when approved.
                scored.map { result -> project(listOf(result.match), 1, score = result.score).single() }
            }
            override fun keyword(tokens: List<String>, limit: Int): List<CandidateEnvelope<ProjectedMemory>> = policyCall {
                if (tokens.isEmpty()) return@policyCall emptyList()
                val selected = if (strict) strictBackend().keywordByApprovedText(
                    bound.scope, bound.spec, rows, tokens, limit, timeout(),
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
                    ) throw ScopedReadException("SCOPED_READ_RESULT_INVALID")
                }
                projected
            }
            override fun related(entityIds: Set<String>, limit: Int): List<CandidateEnvelope<ProjectedMemory>> =
                restricted(PropositionQuery(anyEntityIds = entityIds.sorted())).listing(limit)
        },
        compareBy<CandidateEnvelope<ProjectedMemory>> { ordinal.getValue(it.key) }, tiers,
    )

    private fun listing(limit: Int): List<CandidateEnvelope<ProjectedMemory>> = policyCall {
        validate()
        if (limit < 0 || limit > limits.maxProbe) throw ScopedReadException("INVALID_SCOPED_READ")
        if (grants.isEmpty() || limit == 0) return@policyCall emptyList()
        val selected = if (strict) strictBackend().queryByGrantOrder(bound.scope, bound.spec, rows, limit, timeout())
        else bound.query(ReadOrder.NONE, limit)
        val projected = project(selected, limit)
        if (strict && projected.zipWithNext().any { (left, right) ->
                ordinal.getValue(left.key) > ordinal.getValue(right.key)
            }
        ) throw ScopedReadException("SCOPED_READ_RESULT_INVALID")
        projected
    }

    private fun strictBackend(): StrictPolicyReadCapable {
        val backend = store as? StrictPolicyReadCapable
        if (backend == null || !backend.supportsStrictPolicyReads) throw ScopedReadException("SCOPED_READ_UNSUPPORTED")
        return backend
    }

    private fun timeout(): Duration = minOf(limits.vectorBudget.timeout, Duration.ofSeconds(5))
    private fun validate() { policy.validate(bound.scope, bound.spec) }

    private fun project(
        values: List<Proposition>, limit: Int, spec: ScopedReadSpec = bound.spec, score: Double? = null,
    ): List<CandidateEnvelope<ProjectedMemory>> {
        if (values.size > limit) throw ScopedReadException("SCOPED_READ_RESULT_INVALID")
        val seen = HashSet<PropositionKey>()
        return values.map { proposition ->
            val key = PropositionKey(proposition.contextId.value, proposition.id)
            val grant = grants[key]
            if (grant == null || !seen.add(key) || key.contextIdValue !in bound.scope.contextIdValues ||
                (spec.includedKeys != null && key !in spec.includedKeys) || key in spec.excludedKeys ||
                spec.fragments.any { !it.matchesFilters(proposition, spec.asOf) }
            ) throw ScopedReadException("SCOPED_READ_RESULT_INVALID")
            val dto = ProjectedMemory.from(grant, proposition, score, spec.asOf, spec.rankingDecayK)
            CandidateEnvelope(key, dto, dto.entityHandles.toSet())
        }
    }
}

/** Request-bound policy DTO reference. Every wither starts with an unpublished cache. */
class PolicyMemory private constructor(private val view: PolicyReadView, private val settings: Settings) : LlmReference, Tool {
    private val eager = EagerPublication<ProjectedMemory>()
    override val name: String = "memory"
    override val description: String get() = "Memories about ${settings.topic}"
    override fun notes(): String = "Search tiers: " + settings.plan.searchTiers.joinToString { it.name.lowercase(Locale.ROOT) }
        .ifEmpty { "listing only" }
    override fun tools(): List<Tool> = listOf(this)

    fun withTopic(topic: String): PolicyMemory = configured(settings.copy(topic = topic))
    fun withEagerQuery(criteria: PropositionQuery): PolicyMemory = configured(settings.copy(eagerQuery = criteria))
    fun withEagerTopicSearch(limit: Int): PolicyMemory = configured(settings.copy(eagerTopicSearch = limit))
    fun withEagerTextSearch(request: TextSimilaritySearchRequest): PolicyMemory = configured(settings.copy(eagerTextSearch = request))
    fun withProvenance(resolver: PolicyProvenanceResolver): PolicyMemory = configured(settings.copy(provenance = resolver))

    private fun configured(settings: Settings): PolicyMemory = policyCall {
        val snapshot = settings.copy(eagerQuery = settings.eagerQuery?.let { query -> query.copy(
            anyEntityIds = query.anyEntityIds?.let { immutablePolicyList(it) },
            allEntityIds = query.allEntityIds?.let { immutablePolicyList(it) },
            statuses = query.statuses?.let { immutablePolicySet(it) },
        ) }, eagerTextSearch = settings.eagerTextSearch?.let { TextSimilaritySearchRequest(it.query, it.similarityThreshold, it.topK) })
        if (snapshot.eagerTopicSearch?.let { it <= 0 || it == Int.MAX_VALUE || snapshot.topic.isBlank() } == true ||
            snapshot.eagerQuery?.limit?.let { it < 0 || it == Int.MAX_VALUE } == true ||
            snapshot.eagerTextSearch?.let { it.query.isBlank() || it.topK <= 0 || it.topK == Int.MAX_VALUE ||
                !it.similarityThreshold.isFinite() || it.similarityThreshold !in 0.0..1.0 } == true
        ) throw ScopedReadException("INVALID_SCOPED_READ")
        if ((snapshot.eagerTopicSearch != null || snapshot.eagerTextSearch != null) &&
            PolicySearchTier.VECTOR !in snapshot.plan.searchTiers
        ) throw ScopedReadException("SCOPED_READ_UNSUPPORTED")
        val bound = view.snapshot(Instant.now(), 2.0)
        bound.preflight(snapshot.plan.searchTiers.map { MemoryTier.valueOf(it.name) }.toSet())
        bound.validateEager(snapshot.eagerQuery, snapshot.eagerTopicSearch != null || snapshot.eagerTextSearch != null)
        PolicyMemory(view, snapshot)
    }

    override val definition: Tool.Definition get() = Tool.Definition(
        name = name, description = description,
        inputSchema = Tool.InputSchema.of(
            Tool.Parameter.string("query", "What to recall; omit to list memories", required = false),
            Tool.Parameter.integer("limit", "Maximum number of results", required = false),
        ),
    )

    override fun call(input: String): Tool.Result = policyCall {
        val (query, limit) = parseInput(input)
        val session = view.snapshot(Instant.now(), 2.0).excluding(eager.current()?.keys.orEmpty())
        val selected = session.selection(query, limit, settings.plan).entries
        Tool.Result.text(if (selected.isEmpty()) "No additional memories available." else render(session, selected))
    }

    override fun contribution(): String = policyCall {
        eager.getOrPublish {
            val bound = view.snapshot(Instant.now(), 2.0)
            val tiers = settings.plan.searchTiers.map { MemoryTier.valueOf(it.name) }.toSet()
            val cap = view.limits.maxEager
            // Complete preflight precedes even the count when no eager mode is configured.
            bound.capacity(cap, cap)
            bound.preflight(tiers)
            bound.validateEager(settings.eagerQuery, settings.eagerTopicSearch != null || settings.eagerTextSearch != null)
            val candidates = LinkedHashMap<PropositionKey, CandidateEnvelope<ProjectedMemory>>()
            fun add(entries: List<CandidateEnvelope<ProjectedMemory>>) { entries.forEach { candidates.putIfAbsent(it.key, it) } }
            if (cap > 0) {
                settings.eagerTextSearch?.let { add(bound.eagerSearch(TextSimilaritySearchRequest(it.query, it.similarityThreshold, minOf(it.topK, cap)))) }
                settings.eagerTopicSearch?.let { add(bound.eagerSearch(TextSimilaritySearchRequest(settings.topic, 0.0, minOf(it, cap)))) }
                settings.eagerQuery?.let { add(bound.eagerList(it, minOf(it.limit ?: cap, cap))) }
            }
            val entries = candidates.values.take(cap)
            val count = bound.count()
            val rendered = buildString {
                appendLine("Reference: $name")
                appendLine("Description: $description. $count memories available.")
                if (entries.isNotEmpty()) appendLine(render(bound, entries))
                append(notes())
            }
            PublishedEagerSnapshot(entries, entries.map { it.key }.toSet(), count, rendered)
        }.renderedText
    }

    private fun parseInput(input: String): Pair<String, Int> {
        val node = try { mapper.readTree(input.ifBlank { "{}" }) } catch (_: Exception) {
            throw ScopedReadException("INVALID_SCOPED_READ")
        }
        if (node == null || !node.isObject || node.fieldNames().asSequence().any { it !in setOf("query", "limit") }) {
            throw ScopedReadException("INVALID_SCOPED_READ")
        }
        val query = node.get("query")
        val limit = node.get("limit")
        if (query?.let { !it.isTextual } == true || limit?.let {
                !it.isIntegralNumber || !it.canConvertToLong() || it.longValue() <= 0
            } == true) throw ScopedReadException("INVALID_SCOPED_READ")
        return query?.textValue()?.trim().orEmpty() to
            minOf(limit?.longValue() ?: Memory.DEFAULT_LIMIT.toLong(), view.limits.maxResults.toLong()).toInt()
    }

    private fun render(session: PolicyReadSession, entries: List<CandidateEnvelope<ProjectedMemory>>): String {
        session.preflight(emptySet())
        val grants = view.policy.grants.associateBy { it.key }
        val requests = entries.flatMap { entry ->
            val grant = grants[entry.key] ?: throw ScopedReadException("SCOPED_READ_RESULT_INVALID")
            grant.projection.sources.filter { it.descriptor || it.locator }.map { source ->
                PolicyProvenanceRequest(PolicySourceKey(entry.key, source.sourceId), source.descriptor, source.locator)
            }
        }
        val resolved = if (requests.isEmpty()) emptyMap() else
            settings.provenance?.resolve(view.policy.stamp, immutablePolicyList(requests)).orEmpty().toMap()
        val requested = requests.associateBy { it.key }
        resolved.forEach { (key, source) ->
            val request = requested[key] ?: throw ScopedReadException("SCOPED_READ_RESULT_INVALID")
            if ((!request.descriptor && source.descriptor != null) || (!request.locator && source.locator != null)) {
                throw ScopedReadException("SCOPED_READ_RESULT_INVALID")
            }
        }
        return entries.joinToString("\n") { entry ->
            val dto = mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(entry.value)
            val sources = entry.value.sources + requests.filter { it.key.proposition == entry.key }.mapNotNull { resolved[it.key] }
            dto.set<com.fasterxml.jackson.databind.JsonNode>("sources", mapper.valueToTree(sources.distinct()))
            "- " + mapper.writeValueAsString(dto)
        }
    }

    @PolicyMemoryDsl
    class Configuration internal constructor() {
        var topic: String = "the user & context"
        var plan: PolicyMemoryPlan = PolicyMemoryPlan.listingOnly()
        var eagerQuery: PropositionQuery? = null
        var eagerTopicSearch: Int? = null
        var eagerTextSearch: TextSimilaritySearchRequest? = null
        var provenance: PolicyProvenanceResolver? = null
    }

    class Configuring private constructor(private val view: PolicyReadView, private val settings: Settings) {
        fun withTopic(topic: String) = Configuring(view, settings.copy(topic = topic))
        fun withPlan(plan: PolicyMemoryPlan) = Configuring(view, settings.copy(plan = plan))
        fun withEagerQuery(criteria: PropositionQuery) = Configuring(view, settings.copy(eagerQuery = criteria.copy(
            anyEntityIds = criteria.anyEntityIds?.let { immutablePolicyList(it) },
            allEntityIds = criteria.allEntityIds?.let { immutablePolicyList(it) },
            statuses = criteria.statuses?.let { immutablePolicySet(it) },
        )))
        fun withEagerTopicSearch(limit: Int) = Configuring(view, settings.copy(eagerTopicSearch = limit))
        fun withEagerTextSearch(request: TextSimilaritySearchRequest) = Configuring(view,
            settings.copy(eagerTextSearch = TextSimilaritySearchRequest(request.query, request.similarityThreshold, request.topK)))
        fun withProvenance(resolver: PolicyProvenanceResolver) = Configuring(view, settings.copy(provenance = resolver))
        fun memory(): PolicyMemory = PolicyMemory(view, settings).configured(settings)

        internal companion object {
            fun create(view: PolicyReadView) = Configuring(view, Settings())
        }
    }

    private data class Settings(
        val topic: String = "the user & context",
        val plan: PolicyMemoryPlan = PolicyMemoryPlan.listingOnly(),
        val eagerQuery: PropositionQuery? = null,
        val eagerTopicSearch: Int? = null,
        val eagerTextSearch: TextSimilaritySearchRequest? = null,
        val provenance: PolicyProvenanceResolver? = null,
    )

    companion object {
        private val mapper = jacksonObjectMapper().findAndRegisterModules()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

        @JvmStatic
        fun from(view: PolicyReadView, plan: PolicyMemoryPlan): PolicyMemory = configuring(view).withPlan(plan).memory()

        @JvmStatic
        fun configuring(view: PolicyReadView): Configuring = Configuring.create(view)

        @JvmSynthetic
        operator fun invoke(view: PolicyReadView, configure: Configuration.() -> Unit): PolicyMemory {
            val config = Configuration().apply(configure)
            var chain = configuring(view).withTopic(config.topic).withPlan(config.plan)
            config.eagerQuery?.let { chain = chain.withEagerQuery(it) }
            config.eagerTopicSearch?.let { chain = chain.withEagerTopicSearch(it) }
            config.eagerTextSearch?.let { chain = chain.withEagerTextSearch(it) }
            config.provenance?.let { chain = chain.withProvenance(it) }
            return chain.memory()
        }
    }
}

private inline fun <T> policyCall(operation: () -> T): T = try {
    operation()
} catch (ex: ScopedReadException) {
    throw ex
} catch (_: IllegalArgumentException) {
    throw ScopedReadException("INVALID_SCOPED_READ")
} catch (_: Exception) {
    throw ScopedReadException("SCOPED_READ_FAILED")
}

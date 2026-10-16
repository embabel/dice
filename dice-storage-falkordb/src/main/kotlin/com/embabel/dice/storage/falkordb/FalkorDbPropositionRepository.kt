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
package com.embabel.dice.storage.falkordb

import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.service.Cluster
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.core.types.ZeroToOne
import com.embabel.dice.proposition.ConsecutiveFailureBreaker
import com.embabel.dice.proposition.ContextSetReadCapable
import com.embabel.dice.proposition.ExactVectorBudget
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionQuery.OrderBy
import com.embabel.dice.proposition.PropositionReembedIncompleteException
import com.embabel.dice.proposition.PropositionReembedReport
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStoreType
import com.embabel.dice.proposition.ReadClock
import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.ScopedReadFailure
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.proposition.store.ScopedReadRules
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.storage.falkordb.FalkorDbPropositionMapper.toNode
import com.embabel.dice.storage.falkordb.FalkorDbPropositionMapper.toProposition
import com.embabel.dice.storage.falkordb.model.FalkorDbPropositionNode
import com.embabel.dice.storage.falkordb.model.FalkorDbPropositionNodeQueryDsl
import com.embabel.dice.storage.falkordb.model.PropositionAccess
import com.embabel.dice.storage.falkordb.model.count
import com.embabel.dice.storage.falkordb.model.deleteAll
import com.embabel.dice.storage.falkordb.model.loadAll
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.CypherGenerator
import org.drivine.query.dsl.OrderBuilder
import org.drivine.query.dsl.OrderExpression
import org.drivine.query.dsl.Operand
import org.drivine.query.dsl.WhereBuilder
import org.drivine.query.dsl.above
import org.drivine.query.dsl.anyOf
import org.drivine.query.dsl.atMost
import org.drivine.query.dsl.case
import org.drivine.query.dsl.div
import org.drivine.query.dsl.exp
import org.drivine.query.dsl.floor
import org.drivine.query.dsl.hasItem
import org.drivine.query.dsl.hasValue
import org.drivine.query.dsl.minus
import org.drivine.query.dsl.notIn
import org.drivine.query.dsl.times
import org.drivine.query.dsl.unaryMinus
import java.time.Instant
import java.util.Locale

private typealias Node = FalkorDbPropositionNode

/** The generated query DSL of the proposition node: property references for where and order blocks. */
private val node = FalkorDbPropositionNodeQueryDsl.INSTANCE

/**
 * Durable proposition storage on FalkorDB through Drivine's [StatelessGraphObjectManager].
 *
 * Reads go through the typed query DSL. Raw Cypher is used only where the DSL cannot say it yet:
 * filtering on effective confidence, ranking by keyword overlap, and exact vector scoring. Those
 * statements take their filters from the same DSL, rendered by Drivine, and return ids that are then
 * loaded through the object manager.
 *
 * FalkorDB runs each statement on its own, with no transaction around several, so every write here is
 * a single statement. Configure the datasource with `falkorDbTransactionMode: STRICT` so Drivine
 * refuses anything that is not; the starter does this.
 *
 * With an [EmbeddingService], saves store an embedding of the text and vector searches score every
 * eligible proposition exactly. Without one, vector searches return nothing. A filtered vector search
 * scores at most [exactVectorBudget]'s candidate count and refuses a filter that lets more through.
 */
class FalkorDbPropositionRepository @JvmOverloads constructor(
    private val objects: StatelessGraphObjectManager,
    private val persistence: PersistenceManager,
    private val embeddingService: EmbeddingService? = null,
    private val exactVectorBudget: ExactVectorBudget = ExactVectorBudget.DEFAULT,
) : PropositionRepository, ContextSetReadCapable {

    /** Returns a new repository on the same graph whose filtered vector searches score at most [budget]'s candidates. */
    fun withExactVectorBudget(budget: ExactVectorBudget): FalkorDbPropositionRepository =
        FalkorDbPropositionRepository(objects, persistence, embeddingService, budget)

    override val storeType: PropositionStoreType get() = PropositionStoreType.STORED
    override val supportsVector: Boolean get() = embeddingService != null
    override val luceneSyntaxNotes: String get() = "no lucene support"

    /**
     * Creates the proposition, or merges it into the one already stored: its fields replace the
     * stored ones and its evidence is added to the stored evidence. Concurrent first saves of one id
     * therefore keep every writer's evidence. If the node is deleted between the two steps, it is
     * created again. A merge loses only to another writer finishing first, so Drivine is given more
     * attempts here than its default before it gives up with a `StaleObjectException`.
     */
    override fun save(proposition: Proposition): Proposition {
        val embedding = embeddingService?.let { embed(it, proposition.text) }
        val created = toNode(proposition, embedding)
        while (true) {
            objects.create(created)?.let { return toProposition(it) }
            objects.update(proposition.id, Node::class.java, SAVE_ATTEMPTS) { current -> merged(current, proposition, embedding) }
                ?.let { return toProposition(it) }
        }
    }

    private fun merged(current: Node, incoming: Proposition, embedding: List<Float>?): Node {
        val evidence = (toProposition(current).provenanceEntries + incoming.provenanceEntries).distinct()
        val proposition = incoming.copy(provenanceEntries = evidence)
        return when {
            embedding != null -> toNode(proposition, embedding)
            current.text == incoming.text -> toNode(proposition, current.embedding, current.embeddingDimensions)
            // The stored vector no longer matches the text: keep it, but leave it out of searches until re-embedded.
            else -> toNode(proposition, current.embedding, null)
        }
    }

    /** Applies [change] to the stored proposition, checked against its stamp and reapplied if another writer got there first. */
    private fun change(id: String, change: (Proposition) -> Proposition): Proposition? =
        objects.update(id, Node::class.java) { current ->
            toNode(change(toProposition(current)), current.embedding, current.embeddingDimensions)
        }?.let(::toProposition)

    override fun addProvenance(propositionId: String, entries: List<ProvenanceEntry>): Proposition? =
        change(propositionId) { it.withProvenanceEntries(entries) }

    override fun setProvenance(propositionId: String, entries: List<ProvenanceEntry>): Proposition? =
        change(propositionId) { it.withProvenance(entries) }

    override fun pin(id: String): Proposition? = change(id) { it.withPinned(true) }

    override fun unpin(id: String): Proposition? = change(id) { it.withPinned(false) }

    /** Patches the access time of every id in one statement per thousand ids; unknown ids are skipped. */
    override fun touchAccessed(ids: Collection<String>) {
        val now = Instant.now()
        val nanos = FalkorDbPropositionMapper.epochNanos(now)
        ids.distinct().chunked(ROWS_PER_PATCH).forEach { chunk ->
            objects.patchAll(
                chunk.map { PropositionAccess(it, now, nanos) },
                only = setOf(PropositionAccess::lastAccessed, PropositionAccess::lastAccessedNanos),
            )
        }
    }

    override fun delete(id: String): Boolean = objects.delete(id, Node::class.java) > 0

    override fun clearAll(): Int = objects.deleteAll(Node::class.java)

    override fun clearByContext(contextId: String): Int =
        objects.deleteAll<Node> { where { node.contextId eq contextId } }

    override fun clearByContextPrefix(contextIdPrefix: String): Int =
        objects.deleteAll<Node> { where { node.contextId startsWith contextIdPrefix } }

    override fun findById(id: String): Proposition? = objects.load(id, Node::class.java)?.let(::toProposition)

    override fun findAll(): List<Proposition> = objects.loadAll(Node::class.java).map(::toProposition)

    override fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition> =
        query(PropositionQuery(entityId = entityIdentifier.id))

    override fun findByStatus(status: PropositionStatus): List<Proposition> =
        query(PropositionQuery(statuses = setOf(status)))

    override fun findByGrounding(chunkId: String): List<Proposition> =
        objects.loadAll<Node> { where { node.grounding hasItem chunkId } }.map(::toProposition)

    override fun findByMinLevel(minLevel: Int): List<Proposition> = query(PropositionQuery(minLevel = minLevel))

    override fun findByContextId(contextId: ContextId): List<Proposition> = query(PropositionQuery.forContextId(contextId))

    override fun findByContextIdValue(contextIdValue: String): List<Proposition> =
        query(PropositionQuery.againstContext(contextIdValue))

    override fun count(): Int = objects.count(Node::class.java).toInt()

    override fun query(query: PropositionQuery): List<Proposition> {
        val asOf = query.effectiveConfidenceAsOf ?: Instant.now()
        val ids = idsByEffectiveConfidence(listOf(query), asOf) { applyFilters(query) }
        return objects.loadAll<Node> {
            where { if (ids == null) applyFilters(query) else node.id inList ids }
            orderBy { applyOrder(query.orderBy, asOf, query.decayK) }
            query.limit?.let { limit(it) }
        }.map(::toProposition)
    }

    /** Counts what [query] would return, its limit included. */
    override fun count(query: PropositionQuery): Int {
        val asOf = query.effectiveConfidenceAsOf ?: Instant.now()
        val total = idsByEffectiveConfidence(listOf(query), asOf) { applyFilters(query) }?.size?.toLong()
            ?: objects.count<Node> { where { applyFilters(query) } }
        return (query.limit?.let { minOf(total, it.toLong()) } ?: total).toInt()
    }

    override fun query(spec: ScopedReadSpec, clock: ReadClock, order: OrderBy, limit: Int): List<Proposition> {
        require(limit >= 0) { "Limit must not be negative" }
        if (limit == 0) return emptyList()
        val ids = idsByEffectiveConfidence(spec.fragments, clock.asOf) { scoped(spec) }
        return objects.loadAll<Node> {
            where { if (ids == null) scoped(spec) else node.id inList ids }
            // Ties break by context, then id, mirroring ScopedReadRules.CODE_POINT_ORDER.
            orderBy {
                applyOrder(order, clock.asOf, clock.rankingDecayK)
                node.contextId.asc()
                node.id.asc()
            }
            limit(limit)
        }.map(::toProposition)
    }

    override fun count(spec: ScopedReadSpec, clock: ReadClock): Long =
        idsByEffectiveConfidence(spec.fragments, clock.asOf) { scoped(spec) }?.size?.toLong()
            ?: objects.count<Node> { where { scoped(spec) } }

    /**
     * Ranks [base]'s propositions by how many [tokens] their text contains, case-insensitively. As
     * the default does, [base]'s own limit first keeps its best propositions by effective confidence.
     */
    override fun keywordOverlap(base: PropositionQuery, tokens: List<String>, limit: Int): List<Proposition> {
        if (tokens.isEmpty() || limit <= 0) return emptyList()
        base.limit?.let { require(it >= 0) { "Limit must not be negative" } }
        val asOf = base.effectiveConfidenceAsOf ?: Instant.now()
        return hydrate(
            keywordIds(
                where = render { applyFilters(base) },
                filters = effectiveFilters(listOf(base), asOf),
                rank = render(effectiveConfidence(asOf, base.decayK), "rank"),
                baseLimit = base.limit,
                tokens = tokens.map { it.lowercase(Locale.ROOT) },
                limit = limit,
            ),
        )
    }

    override fun keywordOverlap(spec: ScopedReadSpec, clock: ReadClock, tokens: List<String>, limit: Int): List<Proposition> {
        require(limit >= 0) { "Limit must not be negative" }
        val normalized = ScopedReadRules.keywordTokens(tokens)
        if (limit == 0 || normalized.isEmpty()) return emptyList()
        return hydrate(
            keywordIds(
                where = render { scoped(spec) },
                filters = effectiveFilters(spec.fragments, clock.asOf),
                rank = render(effectiveConfidence(clock.asOf, clock.rankingDecayK), "rank"),
                baseLimit = null,
                tokens = normalized,
                limit = limit,
            ),
        )
    }

    /** Scores every proposition with a usable vector and keeps the best, in a full scan with no candidate cap. */
    override fun findSimilarWithScores(textSimilaritySearchRequest: TextSimilaritySearchRequest): List<SimilarityResult<Proposition>> =
        search(textSimilaritySearchRequest, PropositionQuery(), capped = false)

    /**
     * Scores every proposition that passes [query]'s filters against the query's embedding, then keeps
     * the best. Filtering comes first, so a filter never hides a match that a global top-K pushed out.
     * Propositions whose vector has another length, or no longer matches their text, are skipped.
     * FalkorDB scores in single precision, so a score can differ from DICE's in-memory one in the
     * seventh decimal place. A zero vector has no direction: FalkorDB calls its distance NaN, and it
     * scores 0 here, as it does in memory.
     *
     * Before embedding anything, any query but the empty one counts the rows it lets through, with or
     * without a vector, and fails when there are more than the budget's candidate count. The empty
     * query is the unfiltered search and has no cap. The count and the scoring are two statements,
     * so a row written between them is scored without being counted.
     *
     * The budget's timeout is not applied: Drivine's FalkorDB path has no per-query deadline, so the
     * FalkorDB server's own `TIMEOUT` setting is the only limit on how long a search runs.
     *
     * @throws ScopedReadException with [ScopedReadFailure.EXACT_VECTOR_BUDGET_EXCEEDED] when more rows
     * pass the filters than the budget allows.
     */
    override fun findSimilarWithScores(
        textSimilaritySearchRequest: TextSimilaritySearchRequest,
        query: PropositionQuery,
    ): List<SimilarityResult<Proposition>> =
        search(textSimilaritySearchRequest, query, capped = query != PropositionQuery())

    private fun search(
        request: TextSimilaritySearchRequest,
        query: PropositionQuery,
        capped: Boolean,
    ): List<SimilarityResult<Proposition>> {
        require(request.topK >= 0) { "topK must not be negative" }
        if (request.topK == 0) return emptyList()
        val service = embeddingService ?: return emptyList()
        val asOf = query.effectiveConfidenceAsOf ?: Instant.now()
        val effective = effectiveFilters(listOf(query), asOf)
        if (capped) requireWithinBudget(listOf(render { applyFilters(query) }) + effective)
        val vector = embed(service, request.query)
        val predicates = listOf(render { applyFilters(query); node.embeddingDimensions eq vector.size }) + effective
        // Score ties break by context, then id, mirroring ScopedReadRules.CODE_POINT_ORDER.
        val statement = """
            MATCH (n:$LABEL)
            WHERE ${predicates.joined()}
            WITH n, vec.cosineDistance(n.embedding, vecf32(${'$'}vs_vector)) AS distance
            WITH n, CASE WHEN distance <> distance THEN 0.0 ELSE 1 - distance END AS score
            WHERE score >= ${'$'}vs_threshold
            WITH n, score
            ORDER BY score DESC, n.contextId, n.id
            LIMIT ${'$'}vs_topK
            RETURN {id: n.id, score: score} AS result
        """.trimIndent()
        val parameters = predicates.parameters() + mapOf(
            "vs_vector" to vector, "vs_threshold" to request.similarityThreshold, "vs_topK" to request.topK,
        )
        val rows = rows(statement, parameters)
        val matches = hydrate(rows.map { it["id"] as String }).associateBy { it.id }
        return rows.mapNotNull { row ->
            matches[row["id"]]?.let { SimilarityResult(match = it, score = (row["score"] as Number).toDouble()) }
        }
    }

    /**
     * Counts the rows that pass [filters], stopping one past the budget, and fails when there are
     * more than the budget allows.
     */
    private fun requireWithinBudget(filters: List<Cypher>) {
        val statement = """
            MATCH (n:$LABEL)
            WHERE ${filters.joined()}
            WITH n LIMIT ${'$'}vs_cap
            RETURN {eligible: count(n)} AS result
        """.trimIndent()
        val cap = exactVectorBudget.maxCandidates.toLong() + 1
        val eligible = (rows(statement, filters.parameters() + ("vs_cap" to cap)).single()["eligible"] as Number).toLong()
        if (eligible > exactVectorBudget.maxCandidates) throw ScopedReadException(ScopedReadFailure.EXACT_VECTOR_BUDGET_EXCEEDED)
    }

    /**
     * Clusters [query]'s propositions in one statement: every pair is scored once, the anchor being
     * the one whose id sorts first, and each anchor keeps its [topK] closest matches. Clustering has
     * no candidate cap: it pairs every proposition [query] returns, so a [PropositionQuery.limit] is
     * the way to bound it.
     */
    override fun findClusters(similarityThreshold: ZeroToOne, topK: Int, query: PropositionQuery): List<Cluster<Proposition>> {
        require(topK >= 0) { "topK must not be negative" }
        if (topK == 0 || !supportsVector) return emptyList()
        val candidates = query(query)
        if (candidates.size < 2) return emptyList()
        // Score ties break by the match's context, then id, mirroring ScopedReadRules.CODE_POINT_ORDER.
        val statement = """
            MATCH (a:$LABEL), (b:$LABEL)
            WHERE a.id IN ${'$'}ids AND b.id IN ${'$'}ids AND a.id < b.id
              AND a.embeddingDimensions = b.embeddingDimensions
            WITH a, b, vec.cosineDistance(a.embedding, b.embedding) AS distance
            WITH a, b, CASE WHEN distance <> distance THEN 0.0 ELSE 1 - distance END AS score
            WHERE score >= ${'$'}threshold
            WITH a, b, score
            ORDER BY score DESC, b.contextId, b.id
            WITH a, collect({id: b.id, score: score}) AS similar
            RETURN {anchor: a.id, similar: similar[0..${'$'}topK]} AS result
        """.trimIndent()
        val rows = rows(statement, mapOf("ids" to candidates.map { it.id }, "threshold" to similarityThreshold, "topK" to topK))
        val byId = candidates.associateBy { it.id }
        val similarByAnchor = rows.associate { row ->
            @Suppress("UNCHECKED_CAST")
            val similar = (row["similar"] as List<Map<String, Any?>>).map {
                SimilarityResult(match = byId.getValue(it["id"] as String), score = (it["score"] as Number).toDouble())
            }
            row["anchor"] as String to similar
        }
        return candidates.mapNotNull { anchor ->
            similarByAnchor[anchor.id]?.takeIf { it.isNotEmpty() }?.let { Cluster(anchor = anchor, similar = it) }
        }.sortedByDescending { it.similar.size }
    }

    /**
     * Embeds every proposition's text again and patches the vectors in batches, without touching
     * any other field. A failed embedding leaves that proposition's vector as it was and is reported
     * once the rest have been tried; repeated failures stop the run through DICE's breaker.
     */
    override fun reembedAll(): PropositionReembedReport {
        val service = embeddingService ?: return PropositionReembedReport(0, false)
        val breaker = ConsecutiveFailureBreaker()
        val failures = linkedMapOf<String, Throwable>()
        val notAttempted = mutableListOf<String>()
        var completed = 0
        objects.loadAll(Node::class.java).chunked(VECTOR_ROWS_PER_PATCH).forEach { batch ->
            val embedded = batch.mapNotNull { current ->
                val result = breaker.attempt { embed(service, current.text) }
                when {
                    result == null -> null.also { notAttempted += current.id }
                    result.isFailure -> null.also { failures[current.id] = result.exceptionOrNull()!! }
                    else -> result.getOrThrow().let { current.copy(embedding = it, embeddingDimensions = it.size) }
                }
            }
            if (embedded.isNotEmpty()) {
                completed += objects.patchAll(embedded, only = setOf(Node::embedding, Node::embeddingDimensions))
                    .count { it.found }
            }
        }
        val report = PropositionReembedReport(completed, false)
        if (failures.isNotEmpty() || notAttempted.isNotEmpty()) {
            throw PropositionReembedIncompleteException.of(report, failures, notAttempted)
        }
        return report
    }

    private fun embed(service: EmbeddingService, text: String): List<Float> {
        val vector = service.embed(text)
        require(vector.isNotEmpty() && vector.all { it.isFinite() }) {
            "Vectors must have a positive dimension and finite components"
        }
        return vector.toList()
    }

    context(builder: WhereBuilder<FalkorDbPropositionNodeQueryDsl>)
    private fun applyFilters(criteria: PropositionQuery) {
        criteria.contextId?.let { node.contextId eq it.value }
        criteria.entityId?.let { node.entityIds hasItem it }
        criteria.anyEntityIds?.let { ids ->
            // An empty list matches nothing, as it does in memory; `IN []` says so.
            if (ids.isEmpty()) node.id inList emptyList() else anyOf { ids.forEach { node.entityIds hasItem it } }
        }
        criteria.allEntityIds?.forEach { node.entityIds hasItem it }
        criteria.statuses?.takeIf { it.isNotEmpty() }?.let { statuses -> node.status inList statuses.map { it.name } }
        criteria.minLevel?.let { node.level gte it }
        criteria.maxLevel?.let { node.level lte it }
        criteria.createdAfter?.let { node.created gte it }
        criteria.createdBefore?.let { node.created lte it }
        criteria.revisedAfter?.let { node.lastTouched gte it }
        criteria.revisedBefore?.let { node.lastTouched lte it }
        criteria.accessedAfter?.let { node.lastAccessed gte it }
        criteria.accessedBefore?.let { node.lastAccessed lte it }
        criteria.minConfidence?.let { node.confidence gte it }
        criteria.minImportance?.let { node.importance gte it }
        criteria.minReinforceCount?.let { node.reinforceCount gte it }
        criteria.pinned?.let { node.pinned eq it }
        criteria.minTrustScore?.let { threshold ->
            // An unscored proposition passes, as it does in memory.
            anyOf {
                node.trustScore.isNull()
                node.trustScore gte threshold
            }
        }
    }

    /**
     * The scope, every fragment's filters, and the grants. Ids are unique across contexts, so a key
     * (context, id) admits a row exactly when the row is in another context or its id is listed for
     * that context. That needs one test per context in scope rather than one per key.
     */
    context(builder: WhereBuilder<FalkorDbPropositionNodeQueryDsl>)
    private fun scoped(spec: ScopedReadSpec) {
        val contexts = spec.scope.contextIdValues
        node.contextId inList contexts.toList()
        spec.fragments.forEach { applyFilters(it) }
        spec.includedKeys?.let { keys ->
            val included = keys.groupBy({ it.contextIdValue }, { it.propositionId })
            contexts.forEach { context ->
                anyOf {
                    node.contextId neq context
                    node.id inList (included[context] ?: emptyList())
                }
            }
        }
        spec.excludedKeys.groupBy({ it.contextIdValue }, { it.propositionId })
            .filterKeys { it in contexts }
            .forEach { (context, ids) ->
                anyOf {
                    node.contextId neq context
                    node.id notIn ids
                }
            }
    }

    context(builder: OrderBuilder<FalkorDbPropositionNodeQueryDsl>)
    private fun applyOrder(order: OrderBy, asOf: Instant, k: Double) {
        when (order) {
            OrderBy.NONE -> Unit
            OrderBy.EFFECTIVE_CONFIDENCE_DESC -> effectiveConfidence(asOf, k).desc()
            OrderBy.CREATED_DESC -> node.created.desc()
            OrderBy.REVISED_DESC -> node.lastTouched.desc()
            OrderBy.LAST_ACCESSED_DESC -> node.lastAccessed.desc()
            OrderBy.REINFORCE_COUNT_DESC -> node.reinforceCount.desc()
            OrderBy.IMPORTANCE_DESC -> node.importance.desc()
        }
    }

    /**
     * `Proposition.effectiveConfidenceAt` as an expression the database evaluates. Every branch of
     * that function is here: retraction, a closed or open valid window, the later of the content and
     * access times as the anchor, whole days only, and no decay for a time in the future.
     */
    private fun effectiveConfidence(asOf: Instant, k: Double): OrderExpression {
        val now = FalkorDbPropositionMapper.epochNanos(asOf)
        fun daysSince(anchor: Operand): OrderExpression =
            case(anchor above now then 0.0, otherwise = floor((now - anchor) / NANOS_PER_DAY))
        val latest = case(
            node.contentRevisedNanos above node.lastAccessedNanos then node.contentRevisedNanos,
            otherwise = node.lastAccessedNanos,
        )
        return case(
            node.invalidatedAt atMost asOf then 0.0,
            node.validFrom above asOf then 0.0,
            node.validFrom.hasValue() and (node.validTo atMost asOf) then 0.0,
            node.validFrom.hasValue() and node.validTo.hasValue() then node.confidence,
            node.validFrom.hasValue() then node.confidence * exp(-node.decay * k * daysSince(node.validFromNanos)),
            otherwise = node.confidence * exp(-node.decay * k * daysSince(latest)),
        )
    }

    /** A piece of Cypher and the parameters it binds. */
    private data class Cypher(val text: String, val parameters: Map<String, Any?>)

    private fun List<Cypher>.joined(): String = joinToString(" AND ") { "(${it.text})" }

    private fun List<Cypher>.parameters(): Map<String, Any?> = fold(emptyMap()) { all, it -> all + it.parameters }

    /** The where block, written by Drivine as it would write it for a load. */
    private fun render(block: context(WhereBuilder<FalkorDbPropositionNodeQueryDsl>) () -> Unit): Cypher {
        val builder = WhereBuilder(node)
        block(builder)
        val clause = CypherGenerator.buildWhereClause(builder.conditions, null, persistence.grammar).whereClause
        return Cypher(clause ?: "true", CypherGenerator.extractBindings(builder.conditions))
    }

    /** The expression, written by Drivine as it would write it in an order, with its parameters renamed under [prefix]. */
    private fun render(expression: OrderExpression, prefix: String): Cypher {
        val written = CypherGenerator.processOrders(listOf(expression.ascending()), emptySet(), null, persistence.grammar)
        val text = checkNotNull(written.orderByClause).removeSuffix(" ASC")
        return Cypher(
            ORDER_PARAMETER.replace(text) { "\$${prefix}_${it.groupValues[1]}" },
            written.bindings.mapKeys { (name, _) -> "${prefix}_${name.removePrefix("_order_")}" },
        )
    }

    /** The effective-confidence thresholds of [criteria] as predicates, each fragment's with its own decay rate. */
    private fun effectiveFilters(criteria: List<PropositionQuery>, asOf: Instant): List<Cypher> =
        criteria.flatMapIndexed { index, it ->
            if (it.minEffectiveConfidence == null && it.belowEffectiveConfidence == null) return@flatMapIndexed emptyList()
            val prefix = "ec$index"
            val effective = render(effectiveConfidence(asOf, it.decayK), prefix)
            listOfNotNull(
                it.minEffectiveConfidence?.let { min ->
                    Cypher("(${effective.text}) >= \$${prefix}_min", effective.parameters + ("${prefix}_min" to min))
                },
                it.belowEffectiveConfidence?.let { below ->
                    Cypher("(${effective.text}) < \$${prefix}_below", effective.parameters + ("${prefix}_below" to below))
                },
            )
        }

    /**
     * The ids that pass [where] and the effective-confidence thresholds of [criteria], or null when
     * none of them sets one, in which case the DSL alone can answer.
     */
    private fun idsByEffectiveConfidence(
        criteria: List<PropositionQuery>,
        asOf: Instant,
        where: context(WhereBuilder<FalkorDbPropositionNodeQueryDsl>) () -> Unit,
    ): List<String>? {
        val filters = effectiveFilters(criteria, asOf)
        if (filters.isEmpty()) return null
        val predicates = listOf(render(where)) + filters
        val statement = """
            MATCH (n:$LABEL)
            WHERE ${predicates.joined()}
            RETURN n.id AS id
        """.trimIndent()
        return persistence.query(
            QuerySpecification.withStatement(statement).bind(predicates.parameters()).transform(String::class.java),
        )
    }

    private fun keywordIds(
        where: Cypher,
        filters: List<Cypher>,
        rank: Cypher,
        baseLimit: Int?,
        tokens: List<String>,
        limit: Int,
    ): List<String> {
        val predicates = listOf(where) + filters
        // Both orders below break ties by context, then id, mirroring ScopedReadRules.CODE_POINT_ORDER.
        val cut = if (baseLimit == null) "" else """
            ORDER BY effective DESC, n.contextId, n.id
            LIMIT ${'$'}kw_baseLimit
        """.trimIndent()
        val statement = """
            MATCH (n:$LABEL)
            WHERE ${predicates.joined()}
            WITH n, ${rank.text} AS effective
            $cut
            WITH n, effective, size([token IN ${'$'}kw_tokens WHERE toLower(n.text) CONTAINS token]) AS overlap
            WHERE overlap > 0
            WITH n, overlap, effective
            ORDER BY overlap DESC, effective DESC, n.contextId, n.id
            LIMIT ${'$'}kw_limit
            RETURN n.id AS id
        """.trimIndent()
        val parameters = predicates.parameters() + rank.parameters +
            mapOf("kw_tokens" to tokens, "kw_limit" to limit, "kw_baseLimit" to baseLimit)
        return persistence.query(QuerySpecification.withStatement(statement).bind(parameters).transform(String::class.java))
    }

    @Suppress("UNCHECKED_CAST")
    private fun rows(statement: String, parameters: Map<String, Any?>): List<Map<String, Any?>> =
        persistence.query(QuerySpecification.withStatement(statement).bind(parameters).transform(Map::class.java))
            as List<Map<String, Any?>>

    /** Loads [ids] through the object manager, in the order given. */
    private fun hydrate(ids: List<String>): List<Proposition> {
        if (ids.isEmpty()) return emptyList()
        val byId = objects.loadAll<Node> { where { node.id inList ids } }.associateBy { it.id }
        return ids.mapNotNull { id -> byId[id]?.let(::toProposition) }
    }

    private companion object {
        const val LABEL = "DiceProposition"
        const val NANOS_PER_DAY = 86_400_000_000_000L
        const val SAVE_ATTEMPTS = 10

        /** Drivine writes up to this many rows in one patch statement, and a hundred when they carry a vector. */
        const val ROWS_PER_PATCH = 1_000
        const val VECTOR_ROWS_PER_PATCH = 100

        val ORDER_PARAMETER = Regex("""\${'$'}_order_(\d+)""")
    }
}

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
import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.ContextSetReadCapable
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionReembedIncompleteException
import com.embabel.dice.proposition.PropositionReembedReport
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStoreType
import com.embabel.dice.proposition.ReadOrder
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.provenance.ProvenanceEntry
import org.drivine.connection.ConnectionProvider
import org.drivine.transaction.TransactionContextHolder
import java.time.Instant
import java.util.UUID

/**
 * Durable aggregate storage with native structured and context-set reads through Drivine.
 *
 * Each mutation is one atomic FalkorDB statement. Caller transactions are rejected because
 * FalkorDB does not provide rollback across commands. Supply the application's Drivine context
 * holder when using its transaction manager. The application owns the connection provider.
 * Construction waits for the native ID constraint to be operational.
 * IDs are preserved; saving an existing ID replaces its fields while appending evidence.
 * An optional [EmbeddingService] enables native exact cosine search over persisted embeddings.
 * Without it, legacy vector reads return empty results. Embedding failures leave existing data
 * intact. Vectors of a different dimension are excluded until [reembedAll] succeeds for them.
 */
class FalkorDbPropositionRepository @JvmOverloads constructor(
    private val connectionProvider: ConnectionProvider,
    private val transactionContextHolder: TransactionContextHolder? = null,
    private val embeddingService: EmbeddingService? = null,
) : PropositionRepository, ContextSetReadCapable {
    private val access = FalkorDbAccess(connectionProvider, transactionContextHolder)

    init {
        access.checkWriteAllowed()
        FalkorDbStorageSchema.propositions(connectionProvider)
    }

    override val storeType: PropositionStoreType get() = PropositionStoreType.STORED
    override val supportsVector: Boolean get() = embeddingService != null
    override val luceneSyntaxNotes: String get() = "no lucene support"

    /** Returns a new repository using [embeddingService]; existing vectors change only on save or re-embed. */
    fun withEmbeddingService(embeddingService: EmbeddingService): FalkorDbPropositionRepository =
        FalkorDbPropositionRepository(connectionProvider, transactionContextHolder, embeddingService)

    override fun save(proposition: Proposition): Proposition {
        access.checkWriteAllowed()
        val embedding = embeddingService?.let { embed(it, proposition.text) }
        repeat(MAX_ATTEMPTS) {
            val previous = readVersion(proposition.id)
            val candidate = proposition.copy(
                provenanceEntries = ((previous?.first?.provenanceEntries ?: emptyList()) + proposition.provenanceEntries).distinct(),
            )
            if (compareAndSet(candidate, previous?.second, embedding,
                    embeddingService != null || previous?.first?.text != candidate.text)) return candidate
        }
        error("Concurrent changes prevented saving proposition ${proposition.id}")
    }

    private fun readVersion(id: String): Pair<Proposition, String>? = access.query(
        "MATCH (p:DiceProposition {id: \$id}) RETURN {aggregate:p.aggregate, version:p.version} AS result",
        mapOf("id" to id),
    ).singleOrNull()?.let { FalkorDbPropositionCodec.decode(it["aggregate"] as String) to (it["version"] as String) }

    private fun compareAndSet(
        proposition: Proposition, expected: String?, embedding: List<Double>? = null, replaceEmbedding: Boolean = false,
    ): Boolean {
        val version = UUID.randomUUID().toString()
        val parameters = mapOf(
            "id" to proposition.id, "expected" to expected,
            "properties" to (FalkorDbPropositionCodec.properties(proposition, version) + ("embedding" to embedding)), "version" to version,
            "embedding" to embedding, "replaceEmbedding" to replaceEmbedding,
        )
        val statement = if (expected == null) {
            "MERGE (p:DiceProposition {id: \$id}) ON CREATE SET p = \$properties " +
                "RETURN {accepted:p.version = \$version} AS result"
        } else {
            // A missing row must stay missing; MERGE here would create a placeholder on a stale CAS.
            "OPTIONAL MATCH (p:DiceProposition {id: \$id}) " +
                "WITH p, p.embedding AS previousEmbedding, p IS NOT NULL AND p.version = \$expected AS accepted " +
                "FOREACH (ignored IN CASE WHEN accepted THEN [1] ELSE [] END | " +
                "SET p = \$properties, p.embedding = CASE WHEN \$replaceEmbedding THEN \$embedding ELSE previousEmbedding END) " +
                "RETURN {accepted:accepted} AS result"
        }
        return try {
            access.write(statement, parameters).single()["accepted"] == true
        } catch (ex: Exception) {
            if (expected != null || !access.isConstraintViolation(ex)) throw ex
            false
        }
    }

    private fun mutate(id: String, change: (Proposition) -> Proposition): Proposition? {
        access.checkWriteAllowed()
        repeat(MAX_ATTEMPTS) {
            val (previous, version) = readVersion(id) ?: return null
            val candidate = change(previous)
            if (compareAndSet(candidate, version)) return candidate
        }
        error("Concurrent changes prevented updating proposition $id")
    }

    override fun findById(id: String): Proposition? = readVersion(id)?.first
    override fun findAll(): List<Proposition> = read(FalkorDbQueryCompiler().legacy(PropositionQuery()))
    override fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition> =
        query(PropositionQuery(entityId = entityIdentifier.id))
    override fun findByStatus(status: PropositionStatus): List<Proposition> = query(PropositionQuery(statuses = setOf(status)))
    override fun findByGrounding(chunkId: String): List<Proposition> = access.query(
        "MATCH (p:DiceProposition) WHERE \$chunk IN p.grounding RETURN {aggregate:p.aggregate} AS result",
        mapOf("chunk" to chunkId),
    ).map { FalkorDbPropositionCodec.decode(it["aggregate"] as String) }
    override fun findByMinLevel(minLevel: Int): List<Proposition> = query(PropositionQuery(minLevel = minLevel))
    override fun findByContextId(contextId: ContextId): List<Proposition> = query(PropositionQuery.forContextId(contextId))
    override fun findByContextIdValue(contextIdValue: String): List<Proposition> = query(PropositionQuery.againstContext(contextIdValue))
    override fun query(query: PropositionQuery): List<Proposition> = read(FalkorDbQueryCompiler().legacy(query))
    override fun count(): Int = count(PropositionQuery())
    override fun count(query: PropositionQuery): Int = number(FalkorDbQueryCompiler().legacy(query, count = true)).toInt()

    override fun keywordOverlap(base: PropositionQuery, tokens: List<String>, limit: Int): List<Proposition> {
        if (tokens.isEmpty() || limit <= 0) return emptyList()
        return read(FalkorDbQueryCompiler().legacy(base.orderedByEffectiveConfidence(), tokens = tokens, limit = limit))
    }

    override fun query(scope: ContextReadScope, spec: ScopedReadSpec, order: ReadOrder, limit: Int): List<Proposition> =
        read(FalkorDbQueryCompiler().scoped(scope, spec, order, limit))
    override fun count(scope: ContextReadScope, spec: ScopedReadSpec): Long =
        number(FalkorDbQueryCompiler().scoped(scope, spec, count = true))
    override fun keywordOverlap(scope: ContextReadScope, spec: ScopedReadSpec, tokens: List<String>, limit: Int): List<Proposition> {
        require(limit >= 0) { "Limit must not be negative" }
        if (tokens.isEmpty() || limit == 0) return emptyList()
        return read(FalkorDbQueryCompiler().scoped(scope, spec, ReadOrder.EFFECTIVE_CONFIDENCE_DESC, limit, tokens = tokens))
    }

    private fun read(query: FalkorDbRead): List<Proposition> = access.query(query.statement, query.parameters)
        .map { FalkorDbPropositionCodec.decode(it["aggregate"] as String) }
    private fun number(query: FalkorDbRead): Long =
        (access.query(query.statement, query.parameters).single()["value"] as Number).toLong()

    override fun addProvenance(propositionId: String, entries: List<ProvenanceEntry>): Proposition? =
        mutate(propositionId) { it.withProvenanceEntries(entries) }
    override fun setProvenance(propositionId: String, entries: List<ProvenanceEntry>): Proposition? =
        mutate(propositionId) { it.withProvenance(entries) }
    override fun pin(id: String): Proposition? = mutate(id) { it.withPinned(true) }
    override fun unpin(id: String): Proposition? = mutate(id) { it.withPinned(false) }
    override fun touchAccessed(ids: Collection<String>) {
        access.checkWriteAllowed()
        val now = Instant.now()
        ids.distinct().forEach { id -> mutate(id) { it.copy(lastAccessed = now) } }
    }

    override fun delete(id: String): Boolean = deleteWhere("p.id = \$id", mapOf("id" to id)) > 0
    override fun clearAll(): Int = deleteWhere("true", emptyMap())
    override fun clearByContext(contextId: String): Int = deleteWhere("p.context = \$context", mapOf("context" to contextId))
    override fun clearByContextPrefix(contextIdPrefix: String): Int =
        deleteWhere("p.context STARTS WITH \$prefix", mapOf("prefix" to contextIdPrefix))

    private fun deleteWhere(predicate: String, parameters: Map<String, Any?>): Int = (access.write(
        "MATCH (p:DiceProposition) WITH p, $predicate AS accepted " +
            "FOREACH (ignored IN CASE WHEN accepted THEN [1] ELSE [] END | DELETE p) " +
            "RETURN {value:sum(CASE WHEN accepted THEN 1 ELSE 0 END)} AS result", parameters,
    ).single()["value"] as Number).toInt()

    override fun findSimilarWithScores(textSimilaritySearchRequest: TextSimilaritySearchRequest): List<SimilarityResult<Proposition>> =
        findSimilarWithScores(textSimilaritySearchRequest, PropositionQuery())

    override fun findSimilarWithScores(
        textSimilaritySearchRequest: TextSimilaritySearchRequest, query: PropositionQuery,
    ): List<SimilarityResult<Proposition>> {
        require(textSimilaritySearchRequest.topK >= 0) { "topK must not be negative" }
        if (textSimilaritySearchRequest.topK == 0) return emptyList()
        val service = embeddingService ?: return emptyList()
        return similar(textSimilaritySearchRequest, query, embed(service, textSimilaritySearchRequest.query))
    }

    private fun similar(
        request: TextSimilaritySearchRequest, query: PropositionQuery, embedding: List<Double>?,
        candidateIds: List<String>? = null, afterId: String? = null,
    ): List<SimilarityResult<Proposition>> {
        val compiled = FalkorDbQueryCompiler().vector(query, embedding, request.similarityThreshold, request.topK, candidateIds, afterId)
        return access.query(compiled.statement, compiled.parameters).map {
            SimilarityResult(match = FalkorDbPropositionCodec.decode(it["aggregate"] as String), score = selectedScore(it))
        }
    }

    // FalkorDB rounds wire Doubles; recover the original Floats only for the native-selected top-K.
    // Membership, thresholds and ordering have already run on the server and are never repeated here.
    private fun selectedScore(row: Map<String, Any?>): Double {
        fun vector(key: String) = (row[key] as List<*>).map { (it as Number).toFloat().toDouble() }
        val a = vector("queryEmbedding")
        val b = vector("embedding")
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denominator == 0.0) 0.0 else (dot / denominator).coerceIn(-1.0, 1.0)
    }

    /** Candidate membership and pair deduplication are applied natively before each cluster's top-K. */
    override fun findClusters(similarityThreshold: ZeroToOne, topK: Int, query: PropositionQuery): List<Cluster<Proposition>> {
        require(topK >= 0) { "topK must not be negative" }
        if (topK == 0) return emptyList()
        if (!supportsVector) return emptyList()
        val candidates = query(query)
        val ids = candidates.map { it.id }
        return candidates.mapNotNull { anchor ->
            val request = TextSimilaritySearchRequest(anchor.text, similarityThreshold, topK)
            val similar = similar(request, query, null, ids, anchor.id)
            if (similar.isEmpty()) null else Cluster(anchor = anchor, similar = similar)
        }.sortedByDescending { it.similar.size }
    }

    /**
     * Replaces vectors without rewriting a stale aggregate over concurrent evidence changes.
     * Ordinary failures preserve the old row and are reported after the remaining rows are attempted;
     * repeated failures use DICE's breaker. No vector index is owned or recreated.
     */
    override fun reembedAll(): PropositionReembedReport {
        access.checkWriteAllowed()
        val service = embeddingService ?: return PropositionReembedReport(0, false)
        val breaker = ConsecutiveFailureBreaker()
        val failures = linkedMapOf<String, Throwable>()
        val notAttempted = mutableListOf<String>()
        var completed = 0
        for (proposition in findAll()) {
            val result = breaker.attempt {
                repeat(MAX_ATTEMPTS) {
                    val (current, version) = readVersion(proposition.id) ?: return@attempt false
                    val embedding = embed(service, current.text)
                    if (compareAndSet(current, version, embedding, true)) return@attempt true
                }
                error("Concurrent changes prevented re-embedding proposition ${proposition.id}")
            }
            when {
                result == null -> notAttempted += proposition.id
                result.isFailure -> failures[proposition.id] = result.exceptionOrNull()!!
                result.getOrNull() == true -> completed++
            }
        }
        val report = PropositionReembedReport(completed, false)
        if (failures.isNotEmpty() || notAttempted.isNotEmpty()) {
            throw PropositionReembedIncompleteException.of(report, failures, notAttempted)
        }
        return report
    }

    private fun embed(service: EmbeddingService, text: String): List<Double> {
        val vector = service.embed(text)
        require(vector.isNotEmpty() && vector.all { it.isFinite() }) {
            "Vectors must have a positive dimension and finite components"
        }
        return vector.map { it.toDouble() }
    }

    private companion object {
        const val MAX_ATTEMPTS = 64
    }
}

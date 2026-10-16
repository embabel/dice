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
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.ContextSetReadCapable
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
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
 * Vector search is unconfigured: legacy vector reads return empty results.
 */
class FalkorDbPropositionRepository @JvmOverloads constructor(
    connectionProvider: ConnectionProvider,
    transactionContextHolder: TransactionContextHolder? = null,
) : PropositionRepository, ContextSetReadCapable {
    private val access = FalkorDbAccess(connectionProvider, transactionContextHolder)

    init {
        access.checkWriteAllowed()
        FalkorDbStorageSchema.propositions(connectionProvider)
    }

    override val storeType: PropositionStoreType get() = PropositionStoreType.STORED
    override val supportsVector: Boolean get() = false
    override val luceneSyntaxNotes: String get() = "no lucene support"

    override fun save(proposition: Proposition): Proposition {
        access.checkWriteAllowed()
        repeat(MAX_ATTEMPTS) {
            val previous = readVersion(proposition.id)
            val candidate = proposition.copy(
                provenanceEntries = ((previous?.first?.provenanceEntries ?: emptyList()) + proposition.provenanceEntries).distinct(),
            )
            if (compareAndSet(candidate, previous?.second)) return candidate
        }
        error("Concurrent changes prevented saving proposition ${proposition.id}")
    }

    private fun readVersion(id: String): Pair<Proposition, String>? = access.query(
        "MATCH (p:DiceProposition {id: \$id}) RETURN {aggregate:p.aggregate, version:p.version} AS result",
        mapOf("id" to id),
    ).singleOrNull()?.let { FalkorDbPropositionCodec.decode(it["aggregate"] as String) to (it["version"] as String) }

    private fun compareAndSet(proposition: Proposition, expected: String?): Boolean {
        val version = UUID.randomUUID().toString()
        val parameters = mapOf(
            "id" to proposition.id, "expected" to expected,
            "properties" to FalkorDbPropositionCodec.properties(proposition, version), "version" to version,
        )
        val statement = if (expected == null) {
            "MERGE (p:DiceProposition {id: \$id}) ON CREATE SET p = \$properties " +
                "RETURN {accepted:p.version = \$version} AS result"
        } else {
            // A missing row must stay missing; MERGE here would create a placeholder on a stale CAS.
            "OPTIONAL MATCH (p:DiceProposition {id: \$id}) " +
                "WITH p, p IS NOT NULL AND p.version = \$expected AS accepted " +
                "FOREACH (ignored IN CASE WHEN accepted THEN [1] ELSE [] END | SET p = \$properties) " +
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

    override fun findSimilarWithScores(textSimilaritySearchRequest: TextSimilaritySearchRequest): List<SimilarityResult<Proposition>> {
        require(textSimilaritySearchRequest.topK >= 0) { "topK must not be negative" }
        return emptyList()
    }
    override fun findSimilarWithScores(
        textSimilaritySearchRequest: TextSimilaritySearchRequest, query: PropositionQuery,
    ): List<SimilarityResult<Proposition>> = findSimilarWithScores(textSimilaritySearchRequest)

    private companion object {
        const val MAX_ATTEMPTS = 64
    }
}

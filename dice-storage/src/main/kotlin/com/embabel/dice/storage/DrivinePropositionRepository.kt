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
package com.embabel.dice.storage

import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.service.Cluster
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.core.types.ZeroToOne
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionQuery.OrderBy
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStoreType
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.storage.model.*
import org.drivine.manager.*
import org.drivine.query.CypherStatement
import org.drivine.query.QueryLoader
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.*
import org.slf4j.LoggerFactory
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * Graph-backed [PropositionRepository] over Drivine / Neo4j.
 *
 * Filtering, ordering, limiting, vector search, and entity (`HAS_MENTION`) predicates all push into
 * the database via the high-level [GraphObjectManager] DSL — no whole-store scans. A few operations the
 * DSL can't express drop to hand-written Cypher: the [findClusters] correlation, the cascade-aware bulk
 * clear (`queries/clear_propositions.cypher`), the dedup lookup, and the batch re-embed.
 *
 * Embeddings are derived from [Proposition.embeddableValue] and owned here, not by the model mapper.
 *
 * **v1 notes:**
 * - [findClusters] runs as a single correlated Cypher statement (one round trip) rather than the
 *   interface default's vector query per candidate.
 * - Detached re-saves with changed mentions may leave orphan Mention nodes (load-then-save avoids it).
 */
open class DrivinePropositionRepository(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager,
    private val embeddingService: EmbeddingService,
    transactionManager: PlatformTransactionManager,
    /**
     * Name of the Neo4j vector index backing `Proposition.embedding`, used by the hand-written
     * [findClusters] Cypher. Defaults to Drivine's derived `{label}_{property}_vector`; the
     * autoconfigure passes the configured name so a customised index stays in sync.
     */
    private val vectorIndexName: String = VECTOR_INDEX,
) : PropositionRepository {

    private val logger = LoggerFactory.getLogger(DrivinePropositionRepository::class.java)

    /** Runs the dedup find-then-insert as one programmatic transaction (see [save]). */
    private val txTemplate = TransactionTemplate(transactionManager)

    /**
     * Striped locks for save-time exact-text dedup. Bounded (no per-key leak): a proposition's
     * `(contextId|text)` maps to one stripe, so two threads minting the same fact serialise on the
     * same monitor and the find-then-insert is effectively atomic within this JVM.
     */
    private val dedupLocks: Array<Any> = Array(DEDUP_STRIPES) { Any() }

    override val storeType: PropositionStoreType get() = PropositionStoreType.STORED

    override val luceneSyntaxNotes: String get() = "no lucene support"

    /**
     * Save with exact-text dedup. Parallel chunk extraction mints the same fact as two propositions
     * with identical `(contextId, text)` but different ids; a bare MERGE-by-id persists both, leaving
     * duplicate rows. The stripe lock is held across the transaction COMMIT — the find-then-insert
     * runs inside [txTemplate], which commits before the lock is released — so a concurrent sibling
     * on the same stripe cannot read pre-commit and slip a duplicate past the existence check.
     *
     * Propositions with blank text have nothing to dedup on and are persisted directly.
     */
    override fun save(proposition: Proposition): Proposition {
        val text = proposition.text
        if (text.isBlank()) {
            return txTemplate.execute { doPersist(proposition) }!!
        }
        val contextId = proposition.contextId.value
        return synchronized(lockFor(contextId, text)) {
            try {
                txTemplate.execute { findOrPersist(proposition, contextId, text) }!!
            } catch (e: RuntimeException) {
                if (!isUniquenessViolation(e)) throw e
                // Cross-instance race: another writer inserted the same (contextId, text) and the DB
                // (contextId, text) uniqueness constraint rejected ours. The dupe now exists — reuse it.
                logger.debug("Dedup constraint hit for context {} — reusing existing: '{}'", contextId, text)
                txTemplate.execute { findDuplicateId(contextId, text, proposition.id)?.let(::findById) } ?: throw e
            }
        }
    }

    private fun findOrPersist(proposition: Proposition, contextId: String, text: String): Proposition {
        val existingId = findDuplicateId(contextId, text, proposition.id)
        val existing = existingId?.let(::findById)
        return if (existing != null) {
            logger.debug(
                "Dedup: proposition already present as {} in context {} — reusing: '{}'",
                existingId, contextId, text,
            )
            existing
        } else {
            doPersist(proposition)
        }
    }

    /** Best-effort detection of a Neo4j uniqueness-constraint violation anywhere in the cause chain. */
    private fun isUniquenessViolation(error: Throwable?): Boolean {
        var t: Throwable? = error
        while (t != null) {
            val msg = t.message ?: ""
            if (msg.contains("ConstraintValidationFailed", ignoreCase = true) ||
                msg.contains("already exists", ignoreCase = true)
            ) return true
            t = t.cause
        }
        return false
    }

    /**
     * Persist node, mentions, and (append-only) provenance.
     *
     * Two writes with deliberately different cascades — Drivine applies one cascade per `save`:
     * - **Node + mentions** via the lean [PropositionView] with `DELETE_ORPHAN`: authoritative, so a
     *   changed mention set is reconciled and stale Mention nodes are cleaned. Provenance is *not* in
     *   this view, so existing `DERIVED_FROM` edges are left intact.
     * - **Provenance** via [PropositionWithProvenanceView] with `PRESERVE`: additive — edges are merged,
     *   never deleted, and idempotent by the shared `:Source` key. So the all-in-one save never drops
     *   evidence it didn't load (the lean query/findAll paths, the decay sweep). Authoritative
     *   replacement/removal is the job of [setProvenance] / [clearProvenance].
     */
    private fun doPersist(proposition: Proposition): Proposition {
        val embedding = embeddingFor(proposition)
        graphObjectManager.save(PropositionGraphMapper.toView(proposition, embedding), CascadeType.DELETE_ORPHAN)
        if (proposition.provenanceEntries.isNotEmpty()) {
            graphObjectManager.save(
                PropositionGraphMapper.toProvenanceView(proposition, embedding),
                CascadeType.PRESERVE,
            )
        }
        return proposition
    }

    private fun embeddingFor(proposition: Proposition): List<Float>? =
        proposition.text.takeIf { it.isNotBlank() }?.let { embeddingService.embed(it).toList() }

    /**
     * Authoritative provenance replace (unlike the append-only [save]): save the provenance view with
     * `DELETE_ORPHAN`, so `DERIVED_FROM` edges — and any thereby-orphaned `:Source` nodes — not in
     * [entries] are removed. [clearProvenance] funnels here with an empty list.
     */
    @Transactional
    override fun setProvenance(propositionId: String, entries: List<ProvenanceEntry>): Proposition? {
        val updated = (findById(propositionId) ?: return null).withProvenance(entries)
        graphObjectManager.save(
            PropositionGraphMapper.toProvenanceView(updated, embeddingFor(updated)),
            CascadeType.DELETE_ORPHAN,
        )
        return updated
    }

    /**
     * Id of an existing proposition with the same `contextId` and exact `text` (excluding the
     * candidate's own id), or null if there is no duplicate. Matches on text alone — an identical
     * sentence is the same fact regardless of status; collapsing them is always correct.
     */
    private fun findDuplicateId(contextId: String, text: String, excludeId: String): String? =
        persistenceManager.maybeGetOne(
            QuerySpecification
                .withStatement(
                    "MATCH (p:Proposition {contextId: \$contextId}) " +
                        "WHERE p.text = \$text AND p.id <> \$excludeId " +
                        "RETURN p.id AS id LIMIT 1"
                )
                .bind(mapOf("contextId" to contextId, "text" to text, "excludeId" to excludeId))
                .transform(String::class.java)
        )

    private fun lockFor(contextId: String, text: String): Any =
        dedupLocks[Math.floorMod("$contextId $text".hashCode(), DEDUP_STRIPES)]

    @Transactional(readOnly = true)
    override fun findById(id: String): Proposition? =
        graphObjectManager.load<PropositionWithProvenanceView>(id)?.let(PropositionGraphMapper::toProposition)

    @Transactional(readOnly = true)
    override fun findAll(): List<Proposition> =
        graphObjectManager.loadAll<PropositionView>().map(PropositionGraphMapper::toProposition)

    @Transactional(readOnly = true)
    override fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition> =
        graphObjectManager.loadAll<PropositionView> {
            where { mentions.any { resolvedId eq entityIdentifier.id } }
        }.map(PropositionGraphMapper::toProposition)

    @Transactional(readOnly = true)
    override fun findByStatus(status: PropositionStatus): List<Proposition> =
        graphObjectManager.loadAll<PropositionView> {
            where { proposition.status eq status.name }
        }.map(PropositionGraphMapper::toProposition)

    @Transactional(readOnly = true)
    override fun findByMinLevel(minLevel: Int): List<Proposition> =
        graphObjectManager.loadAll<PropositionView> {
            where { proposition.level gte minLevel }
        }.map(PropositionGraphMapper::toProposition)

    @Transactional(readOnly = true)
    override fun findByContextId(contextId: ContextId): List<Proposition> =
        graphObjectManager.loadAll<PropositionView> {
            where { proposition.contextId eq contextId.value }
        }.map(PropositionGraphMapper::toProposition)

    @Transactional(readOnly = true)
    override fun findByGrounding(chunkId: String): List<Proposition> =
        graphObjectManager.loadAll<PropositionView> { where { proposition.grounding hasItem chunkId } }
            .map(PropositionGraphMapper::toProposition)

    @Transactional(readOnly = true)
    override fun query(query: PropositionQuery): List<Proposition> =
        if (needsLiveDecay(query)) queryWithLiveDecay(query)
        else graphObjectManager.loadAll<PropositionView> {
            where { applyFilters(query, includeEffectiveConfidence = true) }
            orderBy { applyOrder(query.orderBy) }
            query.limit?.let { limit(it) }
        }.map(PropositionGraphMapper::toProposition)

    @Transactional(readOnly = true)
    override fun query(query: PropositionQuery, withProvenance: Boolean): List<Proposition> {
        val lean = query(query)
        return if (withProvenance) enrichWithProvenance(lean) else lean
    }

    @Transactional(readOnly = true)
    override fun findAll(withProvenance: Boolean): List<Proposition> =
        if (!withProvenance) findAll()
        else graphObjectManager.loadAll<PropositionWithProvenanceView>().map(PropositionGraphMapper::toProposition)

    /**
     * The materialised `effectiveConfidence` (default k = 2.0, as of the last sweep) only matches a
     * query that uses those defaults. When [PropositionQuery.decayK] or
     * [PropositionQuery.effectiveConfidenceAsOf] is non-default AND effective confidence actually drives
     * the query, fall back to live computation — see [queryWithLiveDecay].
     */
    private fun needsLiveDecay(query: PropositionQuery): Boolean =
        (query.decayK != DEFAULT_DECAY_K || query.effectiveConfidenceAsOf != null) &&
            (query.minEffectiveConfidence != null || query.orderBy == OrderBy.EFFECTIVE_CONFIDENCE_DESC)

    /**
     * Push every *non-decay* filter into the DB, then apply `effectiveConfidenceAt(asOf, decayK)` as the
     * effective-confidence filter/sort in memory over that bounded candidate set — matching the
     * in-memory backend exactly for non-default `decayK`/`asOf`. The limit is applied last so it
     * truncates the decayed result, not the candidate set.
     */
    private fun queryWithLiveDecay(query: PropositionQuery): List<Proposition> {
        val candidates = graphObjectManager.loadAll<PropositionView> {
            where { applyFilters(query, includeEffectiveConfidence = false) }
            if (query.orderBy != OrderBy.EFFECTIVE_CONFIDENCE_DESC) orderBy { applyOrder(query.orderBy) }
        }.map(PropositionGraphMapper::toProposition)

        val asOf = query.effectiveConfidenceAsOf ?: Instant.now()
        var seq = candidates.asSequence()
        query.minEffectiveConfidence?.let { threshold ->
            seq = seq.filter { it.effectiveConfidenceAt(asOf, query.decayK) >= threshold }
        }
        val ordered = if (query.orderBy == OrderBy.EFFECTIVE_CONFIDENCE_DESC) {
            seq.sortedByDescending { it.effectiveConfidenceAt(asOf, query.decayK) }
        } else {
            seq
        }
        val list = ordered.toList()
        return query.limit?.let { list.take(it) } ?: list
    }

    /** Re-load a lean result set's ids through the provenance view (one batch query), preserving order. */
    private fun enrichWithProvenance(lean: List<Proposition>): List<Proposition> {
        if (lean.isEmpty()) return lean
        val ids = lean.map { it.id }
        val byId = graphObjectManager.loadAll<PropositionWithProvenanceView> { where { proposition.id inList ids } }
            .associate { it.proposition.id to PropositionGraphMapper.toProposition(it) }
        return lean.map { byId[it.id] ?: it }
    }

    /** Shared `where { }` filter block (PropositionView DSL); reused by query and the filtered-vector path. */
    context(builder: WhereBuilder<PropositionViewQueryDsl>)
    private fun applyFilters(query: PropositionQuery, includeEffectiveConfidence: Boolean) {
        query.contextId?.let { proposition.contextId eq it.value }
        query.statuses?.takeIf { it.isNotEmpty() }?.let { statuses ->
            proposition.status inList statuses.map { it.name }
        }
        query.minLevel?.let { proposition.level gte it }
        query.maxLevel?.let { proposition.level lte it }
        query.createdAfter?.let { proposition.created gte it }
        query.createdBefore?.let { proposition.created lte it }
        query.revisedAfter?.let { proposition.contentRevised gte it }
        query.revisedBefore?.let { proposition.contentRevised lte it }
        query.accessedAfter?.let { proposition.lastAccessed gte it }
        query.accessedBefore?.let { proposition.lastAccessed lte it }
        query.minImportance?.let { proposition.importance gte it }
        query.minReinforceCount?.let { proposition.reinforceCount gte it }
        if (includeEffectiveConfidence) query.minEffectiveConfidence?.let { proposition.effectiveConfidence gte it }
        query.entityId?.let { id -> mentions.any { resolvedId eq id } }
        query.anyEntityIds?.let { ids -> mentions.any { resolvedId inList ids } }
        query.allEntityIds?.forEach { id -> mentions.any { resolvedId eq id } }
    }

    context(builder: OrderBuilder<PropositionViewQueryDsl>)
    private fun applyOrder(orderBy: OrderBy) {
        when (orderBy) {
            OrderBy.EFFECTIVE_CONFIDENCE_DESC -> orderByEffectiveConfidenceDescNullsLast()
            OrderBy.CREATED_DESC -> proposition.created.desc()
            OrderBy.REVISED_DESC -> proposition.contentRevised.desc()
            OrderBy.LAST_ACCESSED_DESC -> proposition.lastAccessed.desc()
            OrderBy.REINFORCE_COUNT_DESC -> proposition.reinforceCount.desc()
            OrderBy.IMPORTANCE_DESC -> proposition.importance.desc()
            OrderBy.NONE -> Unit
        }
    }

    @Transactional(readOnly = true)
    override fun findSimilarWithScores(
        textSimilaritySearchRequest: TextSimilaritySearchRequest,
    ): List<SimilarityResult<Proposition>> {
        val vector = embeddingService.embed(textSimilaritySearchRequest.query).toList()
        val threshold = textSimilaritySearchRequest.similarityThreshold.takeIf { it > 0.0 }
        return graphObjectManager
            .loadNearest<PropositionView>(vector, textSimilaritySearchRequest.topK, threshold)
            .map { SimilarityResult(match = PropositionGraphMapper.toProposition(it.value), score = it.score) }
    }

    @Transactional(readOnly = true)
    override fun findSimilarWithScores(
        textSimilaritySearchRequest: TextSimilaritySearchRequest,
        query: PropositionQuery,
    ): List<SimilarityResult<Proposition>> {
        // Non-default decay can't push onto the materialised column; the interface default
        // (vector ∩ query()) routes the filter through query()'s live-decay fallback.
        if (needsLiveDecay(query)) {
            return super<PropositionRepository>.findSimilarWithScores(textSimilaritySearchRequest, query)
        }
        val vector = embeddingService.embed(textSimilaritySearchRequest.query).toList()
        val threshold = textSimilaritySearchRequest.similarityThreshold.takeIf { it > 0.0 }
        return graphObjectManager.loadNearest<PropositionView>(
            vector,
            textSimilaritySearchRequest.topK,
            threshold,
        ) {
            where { applyFilters(query, includeEffectiveConfidence = true) }
        }.map { SimilarityResult(match = PropositionGraphMapper.toProposition(it.value), score = it.score) }
    }

    /**
     * Single correlated statement: select candidates DB-side via [query], then within that set run
     * the vector index once per seed using the seed's own embedding, keeping `seed.id < m.id` so each
     * pair appears once. No N+1 round trips; membership and dedup stay server-side.
     */
    @Transactional(readOnly = true)
    override fun findClusters(
        similarityThreshold: ZeroToOne,
        topK: Int,
        query: PropositionQuery,
    ): List<Cluster<Proposition>> {
        val candidates = query(query)
        if (candidates.size < 2) return emptyList()
        val byId = candidates.associateBy { it.id }
        val ids = candidates.map { it.id }

        @Suppress("UNCHECKED_CAST")
        val rows = persistenceManager.query(
            QuerySpecification
                .withStatement(
                    """
                    UNWIND ${'$'}ids AS sid
                    MATCH (seed:Proposition {id: sid}) WHERE seed.embedding IS NOT NULL
                    CALL db.index.vector.queryNodes('$vectorIndexName', ${'$'}k, seed.embedding) YIELD node AS m, score
                    WHERE m.id IN ${'$'}ids AND sid < m.id AND score >= ${'$'}threshold
                    RETURN { anchorId: sid, otherId: m.id, score: score } AS row
                    """.trimIndent()
                )
                .bind(mapOf("ids" to ids, "k" to topK + 1, "threshold" to similarityThreshold))
        ) as List<Map<String, Any>>

        return rows
            .groupBy { it["anchorId"] as String }
            .mapNotNull { (anchorId, group) ->
                val anchor = byId[anchorId] ?: return@mapNotNull null
                val similar = group
                    .sortedByDescending { (it["score"] as Number).toDouble() }
                    .take(topK)
                    .mapNotNull { row ->
                        byId[row["otherId"] as String]?.let { other ->
                            SimilarityResult(match = other, score = (row["score"] as Number).toDouble())
                        }
                    }
                if (similar.isNotEmpty()) Cluster(anchor = anchor, similar = similar) else null
            }
            .sortedByDescending { it.similar.size }
    }

    /** DELETE_ORPHAN (not DELETE_ALL) so shared `:Source` nodes survive unless this was their last reference. */
    @Transactional
    override fun delete(id: String): Boolean =
        graphObjectManager.delete<PropositionWithProvenanceView>(id, CascadeType.DELETE_ORPHAN) > 0

    @Transactional(readOnly = true)
    override fun count(): Int = graphObjectManager.count<PropositionView>().toInt()

    /**
     * Re-embed every proposition by writing a fresh vector onto each node. Lighter than the
     * interface default (which re-saves the whole view): it SETs only `embedding`, leaving mentions
     * and other properties untouched. The `@VectorIndex`-declared index is owned by Drivine, so a
     * same-dimension re-embed needs no index DDL here.
     *
     * Vectors are computed in memory then written in a single batch in one transaction — sized for
     * ~10–50K propositions. A larger store would want chunked / periodic-commit writes to bound the
     * transaction; not implemented until a store that big exists.
     */
    @Transactional
    override fun reembedAll(): Int {
        logger.info("reembedAll start: model={} dim={}", embeddingService.name, embeddingService.dimensions)
        val specs = graphObjectManager.loadAll<PropositionView>().mapNotNull { view ->
            view.proposition.text.takeIf { it.isNotBlank() }?.let { text ->
                QuerySpecification
                    .withStatement("MATCH (p:Proposition {id: \$id}) SET p.embedding = \$embedding")
                    .bind(mapOf("id" to view.proposition.id, "embedding" to embeddingService.embed(text).toList()))
            }
        }
        if (specs.isNotEmpty()) persistenceManager.executeBatch(specs)
        logger.info("reembedAll done: propositions={} model={}", specs.size, embeddingService.name)
        return specs.size
    }

    @Transactional
    override fun clearAll(): Int = clearMatching(contextId = null, contextIdPrefix = null)

    @Transactional
    override fun clearByContext(contextId: String): Int = clearMatching(contextId = contextId, contextIdPrefix = null)

    @Transactional
    override fun clearByContextPrefix(contextIdPrefix: String): Int =
        clearMatching(contextId = null, contextIdPrefix = contextIdPrefix)

    /**
     * Cascade-aware bulk clear via [CLEAR_PROPOSITIONS] (`queries/clear_propositions.cypher`): deletes the
     * matched propositions and their `:Mention` nodes, then prunes any `:Source` left with no remaining
     * `DERIVED_FROM` edge — a plain `DETACH DELETE p` would orphan both. Both filters are optional (null
     * skips that predicate; both null clears everything); returns the number of propositions deleted.
     */
    private fun clearMatching(contextId: String?, contextIdPrefix: String?): Int {
        val deleted = persistenceManager.getOne(
            QuerySpecification.withStatement(CLEAR_PROPOSITIONS)
                .bind(mapOf("contextId" to contextId, "contextIdPrefix" to contextIdPrefix))
                .transform(Long::class.java)
        ).toInt()
        logger.info("Cleared {} propositions (contextId={}, prefix={})", deleted, contextId, contextIdPrefix)
        return deleted
    }

    /**
     * Order by effective confidence descending, ranking a null (never-materialised) value LAST. Cypher
     * treats null as greater than any value, so a bare `effectiveConfidence DESC` would float nulls to
     * the top; effectiveConfidence is in [0,1], so coalescing nulls to -1.0 floors them. The high-level
     * `orderBy { }` DSL can't express coalesce, so add the order expression straight to the builder —
     * the in-scope context parameter, mirroring the generated property accessors.
     */
    context(builder: OrderBuilder<PropositionViewQueryDsl>)
    private fun orderByEffectiveConfidenceDescNullsLast() {
        builder(OrderSpec("coalesce(proposition.effectiveConfidence, -1.0)", OrderDirection.DESC))
    }

    private companion object {
        /** Stripe count for save-time dedup locks. */
        const val DEDUP_STRIPES = 64

        /** Default vector-index name for `@VectorIndex` on `Proposition.embedding` (`{label}_{property}_vector`). */
        const val VECTOR_INDEX = "Proposition_embedding_vector"

        /** Mirrors the [PropositionQuery.decayK] default; the materialised column is computed at this k. */
        const val DEFAULT_DECAY_K = 2.0

        /**
         * Cascade-aware bulk-clear Cypher, externalised to `queries/clear_propositions.cypher` so it runs
         * standalone for bench-testing. Loaded once via Drivine's [QueryLoader] (which doesn't cache).
         */
        val CLEAR_PROPOSITIONS: CypherStatement = CypherStatement(QueryLoader.loadQuery("clear_propositions"))
    }
}

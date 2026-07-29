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
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionQuery.OrderBy
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.GraphQueryCapable
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStoreType
import com.embabel.dice.provenance.ConnectorRef
import com.embabel.dice.provenance.ContentAddressedLocator
import com.embabel.dice.provenance.FileLocator
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.SourceLocator
import com.embabel.dice.provenance.SourceRevisionRef
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.query.graph.GraphNeighborhood
import com.embabel.dice.query.graph.GraphPath
import com.embabel.dice.query.graph.PropositionLineage
import com.embabel.dice.query.graph.RelatedEntity
import com.embabel.dice.storage.model.*
import org.drivine.manager.*
import org.drivine.query.CypherStatement
import org.drivine.query.QueryLoader
import org.drivine.query.QuerySpecification
import org.drivine.query.dsl.*
import org.slf4j.LoggerFactory
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

internal object SourceProvenanceQueryStatements {
    // The existing uniqueness index is composite `(contextId, text)`. Every repository proposition
    // has non-null text, so the empty-string lower bound preserves membership while letting Neo4j
    // seek the tenant prefix before expanding provenance relationships.
    val bySourceKey = """
        MATCH (p:Proposition {contextId: ${'$'}contextId})
        USING INDEX p:Proposition(contextId, text)
        WHERE p.text >= '' AND EXISTS {
            MATCH (p)-[:DERIVED_FROM]->(:Source {key: ${'$'}sourceKey})
        }
        RETURN p.id AS id
    """.trimIndent()

    val bySourceRevision = """
        MATCH (p:Proposition {contextId: ${'$'}contextId})
        USING INDEX p:Proposition(contextId, text)
        WHERE p.text >= '' AND EXISTS {
            MATCH (p)-[r:DERIVED_FROM]->(:Source {key: ${'$'}sourceKey})
            WHERE r.sourceRevision = ${'$'}sourceRevision
        }
        RETURN p.id AS id
    """.trimIndent()

    val revisionlessBySourceKey = """
        MATCH (p:Proposition {contextId: ${'$'}contextId})
        USING INDEX p:Proposition(contextId, text)
        WHERE p.text >= '' AND EXISTS {
            MATCH (p)-[r:DERIVED_FROM]->(:Source {key: ${'$'}sourceKey})
            WHERE r.sourceRevision IS NULL
        }
        RETURN p.id AS id
    """.trimIndent()
}

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
@Transactional
class DrivinePropositionRepository(
    private val graphObjectManager: GraphObjectManager,
    private val persistenceManager: PersistenceManager,
    private val embeddingService: EmbeddingService,
    transactionManager: PlatformTransactionManager,
    /**
     * Name of the Neo4j vector index backing `Proposition.embedding`, used by the hand-written
     * [findClusters] Cypher. Defaults to the canonical [VECTOR_INDEX]; overridable only for tests.
     */
    private val vectorIndexName: String = VECTOR_INDEX,
) : PropositionRepository, GraphQueryCapable {

    private val logger = LoggerFactory.getLogger(DrivinePropositionRepository::class.java)

    /** Owns each standalone dedup attempt or recovery transaction. */
    private val txTemplate = TransactionTemplate(transactionManager)

    /**
     * Striped locks for save-time exact-text dedup. Bounded (no per-key leak): a proposition's
     * `(contextId|text)` maps to one stripe, so two threads minting the same fact serialise on the
     * same monitor and the find-then-insert is effectively atomic within this JVM.
     */
    private val dedupLocks: Array<Any> = Array(DEDUP_STRIPES) { Any() }

    /** Integration-test seam for forcing the cross-repository uniqueness-recovery race. */
    @Volatile
    internal var beforeDedupInsert: (() -> Unit)? = null

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
     *
     * An update to an id already stored always writes to that id — it never redirects to a same-text
     * sibling. If a foreign sibling turns out to be ACTIVE too (only reachable without the
     * `(contextId, text)` constraint), that's a live duplicate this method won't silently create by
     * redirecting, but won't collapse either — it logs a WARN naming both ids for the dedup sweep.
     *
     * An active caller transaction remains authoritative: [save] joins it, and a caller rollback
     * rolls back the save. Without one, the programmatic transactions own the attempted insert and,
     * after a cross-instance uniqueness rollback, the independent recovery. This preserves the
     * repository's historical transaction boundary while still making standalone concurrent
     * extraction retry-safe.
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    override fun save(proposition: Proposition): Proposition {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return saveInCallerTransaction(proposition)
        }
        return saveInOwnedTransaction(proposition)
    }

    private fun saveInCallerTransaction(proposition: Proposition): Proposition {
        val text = proposition.text
        if (text.isBlank()) {
            return doPersist(proposition)
        }
        val contextId = proposition.contextId.value
        return synchronized(lockFor(contextId, text)) {
            // A database uniqueness race aborts the caller's transaction. Recovery cannot safely
            // run until that transaction has rolled back, so preserve atomicity and propagate it.
            findOrPersist(proposition, contextId, text)
        }
    }

    private fun saveInOwnedTransaction(proposition: Proposition): Proposition {
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
                txTemplate.execute { recoverDuplicate(proposition, contextId, text) } ?: throw e
            }
        }
    }

    private fun findOrPersist(proposition: Proposition, contextId: String, text: String): Proposition {
        val existingId = findDuplicateId(contextId, text, proposition.id)
        val existing = existingId?.let(::findById)
        val isUpdate = existsById(proposition.id)
        // A same-text sibling only collapses a brand-new insert (parallel writers minting one fact as
        // two ids). An in-place update — reinforce, status change, dedup fold — must write to its own
        // node, never redirect to the sibling and drop the update. The (contextId, text) constraint
        // normally makes a foreign sibling impossible, so this only bites when it's absent; genuine
        // inserts skip it.
        return if (existing != null && !isUpdate) {
            logger.debug(
                "Dedup: proposition already present as {} in context {} — reusing: '{}'",
                existingId, contextId, text,
            )
            mergeDeduplicatedProvenance(existing, proposition.provenanceEntries)
        } else {
            // An update lands on its own node even when a same-text sibling exists. If that sibling is
            // also ACTIVE, this mints a second live copy of the same fact (only reachable without the
            // (contextId, text) constraint) — we still write to the caller's id, but flag the pair so
            // the dedup sweep or an operator can collapse them. A non-ACTIVE sibling stays quiet.
            if (existing != null && existing.status == PropositionStatus.ACTIVE &&
                proposition.status == PropositionStatus.ACTIVE
            ) {
                logger.warn(
                    "Update to proposition {} in context {} leaves an ACTIVE same-text sibling {} unmerged — " +
                        "dedup sweep should collapse them: '{}'",
                    proposition.id, contextId, existingId, text,
                )
            }
            if (!isUpdate) beforeDedupInsert?.invoke()
            doPersist(proposition)
        }
    }

    /**
     * Re-find the winning proposition after a cross-instance uniqueness race and union the losing
     * writer's evidence into it. The raw relationship MERGE makes retries harmless.
     */
    private fun recoverDuplicate(proposition: Proposition, contextId: String, text: String): Proposition? {
        val winnerId = findDuplicateId(contextId, text, proposition.id) ?: return null
        val winner = findById(winnerId) ?: return null
        return mergeDeduplicatedProvenance(winner, proposition.provenanceEntries)
    }

    /**
     * Union evidence from a deduplicated insert into its winner. A genuinely new entry is a
     * metadata change and must advance `lastTouched`; an exact replay remains a no-op.
     */
    private fun mergeDeduplicatedProvenance(
        winner: Proposition,
        incomingEntries: List<ProvenanceEntry>,
    ): Proposition {
        ensureCompatibleSources(winner.id, incomingEntries)
        val knownEntries = winner.provenanceEntries.toHashSet()
        val novelEntries = incomingEntries.filterNot(knownEntries::contains)
        if (novelEntries.isEmpty()) return winner
        val revisedWinner = winner.withProvenanceEntries(novelEntries)
        doPersist(revisedWinner)
        return findById(winner.id) ?: revisedWinner
    }

    /**
     * Storage identity equality deliberately uses canonical source keys, so validate the structural
     * locator identity before an exact replay can take the no-op path.
     */
    private fun ensureCompatibleSources(propositionId: String, entries: List<ProvenanceEntry>) {
        entries.map(PropositionGraphMapper::toDerivedFrom).forEach { edge ->
            val entryKey = requireNotNull(edge.entryKey) { "Provenance entryKey must be computed before persistence" }
            ensureCompatibleSource(edge.source, provenanceParameters(propositionId, entryKey, edge))
        }
    }

    /**
     * Best-effort detection of a Neo4j uniqueness-constraint violation anywhere in the cause chain.
     * Matches on message substrings, since which form (error code vs. prose) shows up in
     * `getMessage()` isn't guaranteed across driver versions. [findOrPersist] pre-checks for a
     * same-text sibling before writing, so this is just the cross-instance-race backstop now.
     */
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
     * Two writes with deliberately different ownership:
     * - **Node + mentions** via the lean [PropositionView] with `DELETE_ORPHAN`: authoritative, so a
     *   changed mention set is reconciled and stale Mention nodes are cleaned. Provenance is *not* in
     *   this view, so existing `DERIVED_FROM` edges are left intact.
     * - **Provenance** via raw Cypher keyed by the full evidence tuple: additive, idempotent, and able
     *   to preserve parallel revisions that relationship-fragment mapping otherwise collapses.
     *   Authoritative replacement/removal is the job of [setProvenance] / [clearProvenance].
     */
    private fun doPersist(proposition: Proposition): Proposition {
        val embedding = embeddingFor(proposition)
        graphObjectManager.save(PropositionGraphMapper.toView(proposition, embedding), CascadeType.DELETE_ORPHAN)
        appendProvenance(proposition.id, proposition.provenanceEntries)
        return proposition
    }

    private fun embeddingFor(proposition: Proposition): List<Float>? =
        proposition.text.takeIf { it.isNotBlank() }?.let { embeddingService.embed(it).toList() }

    /**
     * Authoritative provenance replace (unlike the append-only [save]). Desired evidence is upserted
     * first, omitted edges are deleted by their storage identity, and only globally unreferenced
     * Source nodes are pruned. [clearProvenance] funnels here with an empty list.
     */
    @Transactional
    override fun setProvenance(propositionId: String, entries: List<ProvenanceEntry>): Proposition? {
        val updated = (findById(propositionId) ?: return null).withProvenance(entries)
        graphObjectManager.save(PropositionGraphMapper.toView(updated, embeddingFor(updated)), CascadeType.DELETE_ORPHAN)
        replaceProvenance(propositionId, entries)
        return updated
    }

    /**
     * Append evidence without routing relationship identity through Drivine. `entryKey` is computed
     * by the shared graph mapper and is always non-null before it reaches MERGE.
     */
    private fun appendProvenance(propositionId: String, entries: List<ProvenanceEntry>) {
        val derived = entries.map(PropositionGraphMapper::toDerivedFrom)
        derived.forEach { edge ->
            val entryKey = requireNotNull(edge.entryKey) { "Provenance entryKey must be computed before persistence" }
            val params = provenanceParameters(propositionId, entryKey, edge)
            ensureCompatibleSource(edge.source, params)
            if (edge.sourceRevision == null && adoptExactLegacyEdge(params)) return@forEach
            persistenceManager.execute(
                QuerySpecification.withStatement(
                    """
                    MATCH (p:Proposition {id: ${'$'}propositionId})
                    MATCH (s:Source {key: ${'$'}sourceKey})
                    MERGE (p)-[r:DERIVED_FROM {entryKey: ${'$'}entryKey}]->(s)
                    SET r.sourceRevision = ${'$'}sourceRevision,
                        r.chunkId = ${'$'}chunkId,
                        r.startOffset = ${'$'}startOffset,
                        r.endOffset = ${'$'}endOffset,
                        r.contentHash = ${'$'}contentHash
                    """.trimIndent()
                ).bind(params),
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun ensureCompatibleSource(source: SourceNode, params: Map<String, Any?>) {
        val stored = (persistenceManager.query(
            QuerySpecification.withStatement(
                """
                MERGE (s:Source {key: ${'$'}sourceKey})
                ON CREATE SET s.kind = ${'$'}sourceKind,
                              s.uri = ${'$'}sourceUri,
                              s.path = ${'$'}sourcePath,
                              s.contentHash = ${'$'}sourceContentHash,
                              s.connectorId = ${'$'}connectorId,
                              s.externalId = ${'$'}externalId
                SET s.display = ${'$'}sourceDisplay
                RETURN {
                    kind: s.kind,
                    uri: s.uri,
                    path: s.path,
                    contentHash: s.contentHash,
                    connectorId: s.connectorId,
                    externalId: s.externalId
                } AS row
                """.trimIndent()
            ).bind(params)
        ) as List<Map<String, Any?>>).single()
        val compatible = stored["kind"] == source.kind &&
            stored["uri"] == source.uri &&
            stored["path"] == source.path &&
            stored["contentHash"] == source.contentHash &&
            stored["connectorId"] == source.connectorId &&
            stored["externalId"] == source.externalId
        require(compatible) {
            "Source key collision for '${source.key}': stored source identity differs from incoming locator"
        }
    }

    /**
     * A pre-revision edge may be adopted only by an exactly equal revisionless entry. Revisioned
     * evidence deliberately cannot claim legacy state.
     */
    private fun adoptExactLegacyEdge(params: Map<String, Any?>): Boolean {
        val adopted = persistenceManager.maybeGetOne(
            QuerySpecification.withStatement(
                """
                MATCH (p:Proposition {id: ${'$'}propositionId})-[r:DERIVED_FROM]->(s:Source {key: ${'$'}sourceKey})
                WHERE r.entryKey IS NULL
                  AND r.sourceRevision IS NULL
                  AND ((r.chunkId IS NULL AND ${'$'}chunkId IS NULL) OR r.chunkId = ${'$'}chunkId)
                  AND ((r.startOffset IS NULL AND ${'$'}startOffset IS NULL) OR r.startOffset = ${'$'}startOffset)
                  AND ((r.endOffset IS NULL AND ${'$'}endOffset IS NULL) OR r.endOffset = ${'$'}endOffset)
                  AND ((r.contentHash IS NULL AND ${'$'}contentHash IS NULL) OR r.contentHash = ${'$'}contentHash)
                WITH r LIMIT 1
                SET r.entryKey = ${'$'}entryKey
                RETURN count(r) AS adopted
                """.trimIndent()
            ).bind(params).transform(Long::class.java)
        ) ?: 0L
        return adopted > 0
    }

    private fun replaceProvenance(propositionId: String, entries: List<ProvenanceEntry>) {
        appendProvenance(propositionId, entries)
        val entryKeys = entries.map(::provenanceStorageEntryKey)
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                MATCH (p:Proposition {id: ${'$'}propositionId})-[r:DERIVED_FROM]->()
                WHERE r.entryKey IS NULL OR NOT r.entryKey IN ${'$'}entryKeys
                DELETE r
                """.trimIndent()
            ).bind(mapOf("propositionId" to propositionId, "entryKeys" to entryKeys)),
        )
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                MATCH (s:Source)
                WHERE NOT (s)<-[:DERIVED_FROM]-()
                DELETE s
                """.trimIndent()
            ),
        )
    }

    private fun provenanceParameters(
        propositionId: String,
        entryKey: String,
        edge: DerivedFrom,
    ): Map<String, Any?> = mapOf(
        "propositionId" to propositionId,
        "entryKey" to entryKey,
        "sourceKey" to edge.source.key,
        "sourceKind" to edge.source.kind,
        "sourceDisplay" to edge.source.display,
        "sourceUri" to edge.source.uri,
        "sourcePath" to edge.source.path,
        "sourceContentHash" to edge.source.contentHash,
        "connectorId" to edge.source.connectorId,
        "externalId" to edge.source.externalId,
        "sourceRevision" to edge.sourceRevision,
        "chunkId" to edge.chunkId,
        "startOffset" to edge.startOffset,
        "endOffset" to edge.endOffset,
        "contentHash" to edge.contentHash,
    )

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

    /** True if a proposition with this exact id is already stored, so a [save] is an update, not an insert. */
    private fun existsById(id: String): Boolean =
        persistenceManager.maybeGetOne(
            QuerySpecification
                .withStatement("MATCH (p:Proposition {id: \$id}) RETURN p.id AS id LIMIT 1")
                .bind(mapOf("id" to id))
                .transform(String::class.java)
        ) != null

    private fun lockFor(contextId: String, text: String): Any =
        dedupLocks[Math.floorMod("$contextId $text".hashCode(), DEDUP_STRIPES)]

    @Transactional(readOnly = true)
    override fun findById(id: String): Proposition? =
        graphObjectManager.load<PropositionView>(id)
            ?.let(PropositionGraphMapper::toProposition)
            ?.let { withRawProvenance(listOf(it)).single() }

    @Transactional(readOnly = true)
    override fun findAll(): List<Proposition> =
        graphObjectManager.loadAll<PropositionView> {
            // Only hydrate real DICE propositions. A foreign or half-written `:Proposition` node
            // (label collision, or a stub missing `contextId`) fails Drivine hydration of the WHOLE
            // query because `contextId` is non-nullable — which otherwise 500s every full scan
            // (decay sweep, re-embed). Every proposition this store writes has a `contextId`, so this
            // guard excludes only non-propositions; it matches `findByContextId`'s implicit assumption.
            where { proposition.contextId.isNotNull() }
        }.map(PropositionGraphMapper::toProposition)

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
    override fun findBySourceKey(contextId: ContextId, sourceKey: String): List<Proposition> =
        executeSourceQuery(
            SourceProvenanceQueryStatements.bySourceKey,
            mapOf("contextId" to contextId.value, "sourceKey" to sourceKey),
        )

    @Transactional(readOnly = true)
    override fun findBySourceRevision(contextId: ContextId, ref: SourceRevisionRef): List<Proposition> =
        executeSourceQuery(
            SourceProvenanceQueryStatements.bySourceRevision,
            mapOf(
                "contextId" to contextId.value,
                "sourceKey" to ref.sourceKey,
                "sourceRevision" to ref.sourceRevision,
            ),
        )

    @Transactional(readOnly = true)
    override fun findRevisionlessBySourceLocator(
        contextId: ContextId,
        locator: SourceLocator,
    ): List<Proposition> =
        executeSourceQuery(
            SourceProvenanceQueryStatements.revisionlessBySourceKey,
            mapOf("contextId" to contextId.value, "sourceKey" to locator.key()),
        )

    /**
     * The single construction and execution path for source queries. Internal overridability lets
     * integration tests observe the immutable statement/parameter pair selected by a real public
     * invocation without adding mutable callbacks to the production repository.
     */
    @Suppress("UNCHECKED_CAST")
    internal open fun executeSourceQuery(statement: String, params: Map<String, Any>): List<Proposition> {
        val ids = persistenceManager.query(
            QuerySpecification.withStatement(statement).bind(params)
        ) as List<String>
        val byId = hydrate(ids)
        return withRawProvenance(ids.distinct().mapNotNull { byId[it] })
    }

    @Transactional(readOnly = true)
    override fun findByGrounding(chunkId: String): List<Proposition> =
        graphObjectManager.loadAll<PropositionView> { where { proposition.grounding hasItem chunkId } }
            .map(PropositionGraphMapper::toProposition)

    /**
     * Abstractions of a proposition, pushed down: the default in [GraphTraversalCapable] scans the
     * whole store, so match on the stored `sourceIds` list directly — `WHERE $propositionId IN
     * p.sourceIds`, the same shape as [findByGrounding].
     */
    @Transactional(readOnly = true)
    override fun findAbstractionsOf(propositionId: String): List<Proposition> =
        graphObjectManager.loadAll<PropositionView> { where { proposition.sourceIds hasItem propositionId } }
            .map(PropositionGraphMapper::toProposition)

    @Transactional(readOnly = true)
    override fun query(query: PropositionQuery): List<Proposition> {
        val liveDecay = needsLiveDecay(query)
        val results = if (liveDecay) queryWithLiveDecay(query)
        else graphObjectManager.loadAll<PropositionView> {
            where { applyFilters(query, includeEffectiveConfidence = true) }
            orderBy { applyOrder(query.orderBy) }
            query.limit?.let { limit(it) }
        }.map(PropositionGraphMapper::toProposition)
        logger.debug("query returned {} proposition(s) (liveDecay={}, limit={})", results.size, liveDecay, query.limit)
        return results
    }

    @Transactional(readOnly = true)
    override fun query(query: PropositionQuery, withProvenance: Boolean): List<Proposition> {
        val lean = query(query)
        return if (withProvenance) enrichWithProvenance(lean) else lean
    }

    @Transactional(readOnly = true)
    override fun findAll(withProvenance: Boolean): List<Proposition> =
        if (!withProvenance) findAll() else withRawProvenance(findAll())

    /**
     * The materialised `effectiveConfidence` (default k = 2.0, as of the last sweep) only matches a
     * query that uses those defaults. When [PropositionQuery.decayK] or
     * [PropositionQuery.effectiveConfidenceAsOf] is non-default AND effective confidence actually drives
     * the query, fall back to live computation — see [queryWithLiveDecay].
     */
    private fun needsLiveDecay(query: PropositionQuery): Boolean =
        (query.decayK != DEFAULT_DECAY_K || query.effectiveConfidenceAsOf != null) &&
            (query.minEffectiveConfidence != null || query.belowEffectiveConfidence != null ||
                query.orderBy == OrderBy.EFFECTIVE_CONFIDENCE_DESC)

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
        query.belowEffectiveConfidence?.let { threshold ->
            seq = seq.filter { it.effectiveConfidenceAt(asOf, query.decayK) < threshold }
        }
        val ordered = if (query.orderBy == OrderBy.EFFECTIVE_CONFIDENCE_DESC) {
            seq.sortedByDescending { it.effectiveConfidenceAt(asOf, query.decayK) }
        } else {
            seq
        }
        val list = ordered.toList()
        return query.limit?.let { list.take(it) } ?: list
    }

    /** Add raw provenance to a lean result set in one batch query, preserving order. */
    private fun enrichWithProvenance(lean: List<Proposition>): List<Proposition> {
        return withRawProvenance(lean)
    }

    /**
     * Raw fallback for full reads. Relationship fragments are mapped by endpoint/type, so raw Cypher
     * rows preserve every parallel revision relationship.
     */
    @Suppress("UNCHECKED_CAST")
    private fun withRawProvenance(lean: List<Proposition>): List<Proposition> {
        if (lean.isEmpty()) return lean
        val rows = persistenceManager.query(
            QuerySpecification.withStatement(
                """
                MATCH (p:Proposition)-[r:DERIVED_FROM]->(s:Source)
                WHERE p.id IN ${'$'}ids
                RETURN {
                    propositionId: p.id,
                    chunkId: r.chunkId,
                    startOffset: r.startOffset,
                    endOffset: r.endOffset,
                    contentHash: r.contentHash,
                    sourceRevision: r.sourceRevision,
                    sourceKey: s.key,
                    sourceKind: s.kind,
                    sourceDisplay: s.display,
                    sourceUri: s.uri,
                    sourcePath: s.path,
                    sourceContentHash: s.contentHash,
                    connectorId: s.connectorId,
                    externalId: s.externalId
                } AS row
                """.trimIndent()
            ).bind(mapOf("ids" to lean.map { it.id }))
        ) as List<Map<String, Any?>>
        val byId = rows.groupBy { it["propositionId"] as String }
        return lean.map { proposition ->
            proposition.copy(provenanceEntries = byId[proposition.id].orEmpty().map(::toProvenanceEntry))
        }
    }

    private fun toProvenanceEntry(row: Map<String, Any?>): ProvenanceEntry =
        PropositionGraphMapper.toProvenanceEntry(
            DerivedFrom(
                source = SourceNode(
                    key = row["sourceKey"] as String,
                    kind = row["sourceKind"] as String,
                    display = row["sourceDisplay"] as? String,
                    uri = row["sourceUri"] as? String,
                    path = row["sourcePath"] as? String,
                    contentHash = row["sourceContentHash"] as? String,
                    connectorId = row["connectorId"] as? String,
                    externalId = row["externalId"] as? String,
                ),
            chunkId = row["chunkId"] as? String,
            startOffset = (row["startOffset"] as? Number)?.toInt(),
            endOffset = (row["endOffset"] as? Number)?.toInt(),
            contentHash = row["contentHash"] as? String,
            sourceRevision = row["sourceRevision"] as? String,
            ),
        )

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
        query.revisedAfter?.let { proposition.lastTouched gte it }
        query.revisedBefore?.let { proposition.lastTouched lte it }
        query.accessedAfter?.let { proposition.lastAccessed gte it }
        query.accessedBefore?.let { proposition.lastAccessed lte it }
        query.minConfidence?.let { proposition.confidence gte it }
        query.minImportance?.let { proposition.importance gte it }
        query.minReinforceCount?.let { proposition.reinforceCount gte it }
        if (includeEffectiveConfidence) {
            query.minEffectiveConfidence?.let { proposition.effectiveConfidence gte it }
            query.belowEffectiveConfidence?.let { proposition.effectiveConfidence lt it }
        }
        query.minTrustScore?.let { threshold ->
            // Fail-open, matching the in-memory backend's passesMinTrust: an unscored proposition (no
            // cached trust property) passes the gate, so the predicate is "missing OR >= threshold".
            // Trust rides the @PropertyBag, stored flat as `metadata.<key>`, so it pushes into the DB.
            anyOf {
                proposition.metadata.key(DiceMetadataKeys.TRUST_SCORE).isNull()
                proposition.metadata.key(DiceMetadataKeys.TRUST_SCORE) gte threshold
            }
        }
        query.entityId?.let { id -> mentions.any { resolvedId eq id } }
        query.anyEntityIds?.let { ids -> mentions.any { resolvedId inList ids } }
        query.allEntityIds?.forEach { id -> mentions.any { resolvedId eq id } }
    }

    context(builder: OrderBuilder<PropositionViewQueryDsl>)
    private fun applyOrder(orderBy: OrderBy) {
        when (orderBy) {
            OrderBy.EFFECTIVE_CONFIDENCE_DESC -> orderByEffectiveConfidenceDescNullsLast()
            OrderBy.CREATED_DESC -> proposition.created.desc()
            OrderBy.REVISED_DESC -> proposition.lastTouched.desc()
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
        val rawThreshold = textSimilaritySearchRequest.similarityThreshold.takeIf { it > 0.0 }
        val engineThreshold = rawThreshold?.let(::toEngineScore)
        val results = graphObjectManager
            .loadNearest<PropositionView>(vector, textSimilaritySearchRequest.topK, engineThreshold)
            .map { SimilarityResult(match = PropositionGraphMapper.toProposition(it.value), score = toRawCosine(it.score)) }
        logger.debug("findSimilarWithScores: {} hit(s) (topK={}, threshold={})", results.size, textSimilaritySearchRequest.topK, rawThreshold)
        return results
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
        val threshold = textSimilaritySearchRequest.similarityThreshold.takeIf { it > 0.0 }?.let(::toEngineScore)
        return graphObjectManager.loadNearest<PropositionView>(
            vector,
            textSimilaritySearchRequest.topK,
            threshold,
        ) {
            where { applyFilters(query, includeEffectiveConfidence = true) }
        }.map { SimilarityResult(match = PropositionGraphMapper.toProposition(it.value), score = toRawCosine(it.score)) }
    }

    /**
     * Single correlated statement: select candidates DB-side via [query], then within that set run
     * the vector index once per seed using the seed's own embedding, keeping `seed.id < m.id` so each
     * pair appears once. No N+1 round trips; membership and dedup stay server-side.
     *
     * [similarityThreshold] is raw cosine — a candidate pair is admitted at cosine >= threshold, same
     * scale as [findSimilarWithScores]. Older deployments that tuned a threshold against Neo4j's raw
     * `(1 + cosine) / 2` index score (before this method converted back to cosine) admitted down to
     * cosine `2*t - 1` for a stored `t`; that threshold now means something stricter and should be
     * retuned.
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

        // A COSINE index scores as (1 + cosine) / 2 (see toRawCosine below); the `cosine` binding here
        // converts back to raw cosine so the gate and the returned score are both cosine, as the
        // in-memory store returns. COSINE-only: a EUCLIDEAN index normalizes differently.
        @Suppress("UNCHECKED_CAST")
        val rows = persistenceManager.query(
            QuerySpecification
                .withStatement(
                    """
                    UNWIND ${'$'}ids AS sid
                    MATCH (seed:Proposition {id: sid}) WHERE seed.embedding IS NOT NULL
                    CALL db.index.vector.queryNodes('$vectorIndexName', ${'$'}k, seed.embedding) YIELD node AS m, score
                    WITH sid, m, 2.0 * score - 1.0 AS cosine
                    WHERE m.id IN ${'$'}ids AND sid < m.id AND cosine >= ${'$'}threshold
                    RETURN { anchorId: sid, otherId: m.id, score: cosine } AS row
                    """.trimIndent()
                )
                .bind(mapOf("ids" to ids, "k" to topK + 1, "threshold" to similarityThreshold))
        ) as List<Map<String, Any>>

        val clusters = rows
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
        logger.debug(
            "findClusters: {} candidate(s) -> {} cluster(s) (threshold={}, topK={})",
            candidates.size, clusters.size, similarityThreshold, topK,
        )
        return clusters
    }

    /**
     * A Neo4j COSINE vector index reports `(1 + cosine) / 2`, not raw cosine — this converts an
     * engine score back to the raw cosine every score in this repository's public API uses (matching
     * the in-memory store). Paired with [toEngineScore]. COSINE-only: a EUCLIDEAN index normalizes
     * differently.
     */
    private fun toRawCosine(engineScore: Double): Double = 2.0 * engineScore - 1.0

    /**
     * Converts a raw-cosine similarity threshold to the engine-normalized scale Drivine's
     * `loadNearest` gates on, so a caller-supplied threshold (always raw cosine here) means the same
     * thing whether it's applied by us (as in [findClusters]) or by the index itself (as in
     * [findSimilarWithScores]). Inverse of [toRawCosine].
     */
    private fun toEngineScore(rawCosineThreshold: Double): Double = (1.0 + rawCosineThreshold) / 2.0

    /** DELETE_ORPHAN (not DELETE_ALL) so shared `:Source` nodes survive unless this was their last reference. */
    @Transactional
    override fun delete(id: String): Boolean =
        graphObjectManager.delete<PropositionWithProvenanceView>(id, CascadeType.DELETE_ORPHAN) > 0

    @Transactional(readOnly = true)
    override fun count(): Int = graphObjectManager.count<PropositionView>().toInt()

    /**
     * Batch `SET` in one round trip instead of the SPI default's per-id find-then-save. Runs in its
     * own [Propagation.REQUIRES_NEW] transaction: callers commonly invoke this from inside a
     * `readOnly = true` query transaction (e.g. Memory's eager load), which cannot itself take a write.
     * Empty [ids] is a no-op — no need to open a transaction for nothing.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    override fun touchAccessed(ids: Collection<String>) {
        if (ids.isEmpty()) return
        persistenceManager.execute(
            QuerySpecification
                .withStatement("MATCH (p:Proposition) WHERE p.id IN \$ids SET p.lastAccessed = \$now")
                .bind(mapOf("ids" to ids.distinct(), "now" to Instant.now())),
        )
    }

    /**
     * Filtered count pushed into the DB: same `where` block as [query], counted server-side instead
     * of materialising and sizing the rows. Non-default decay can't push onto the materialised
     * `effectiveConfidence` column, so that case falls back to counting [query]'s live-decay result.
     *
     * A [PropositionQuery.limit] caps the count, mirroring the SPI default (`query(query).size`) and
     * the in-memory store, which both size a limit-truncated result — a server-side `count()` would
     * otherwise count every match and ignore the cap.
     */
    @Transactional(readOnly = true)
    override fun count(query: PropositionQuery): Int {
        if (needsLiveDecay(query)) return query(query).size
        val counted = graphObjectManager.count<PropositionView> {
            where { applyFilters(query, includeEffectiveConfidence = true) }
        }.toInt()
        return query.limit?.let { minOf(it, counted) } ?: counted
    }

    /**
     * Case-insensitive keyword-overlap probe pushed into the DB. The typed DSL has no
     * case-insensitive `CONTAINS` and no list-comprehension, and there's no full-text index (see
     * [luceneSyntaxNotes]), so this drops to hand-written Cypher: `size([t IN $tokens WHERE
     * toLower(p.text) CONTAINS t]) > 0`, ordered by that overlap then effective confidence. Only the
     * structured filters this statement understands are handled; a [base] carrying anything else
     * (entity, level, temporal, non-default decay, …) falls back to the portable default, which still
     * pushes [base]'s filters through [query].
     */
    @Transactional(readOnly = true)
    override fun keywordOverlap(base: PropositionQuery, tokens: List<String>, limit: Int): List<Proposition> {
        if (tokens.isEmpty() || limit <= 0) return emptyList()
        if (!keywordPushable(base)) return super.keywordOverlap(base, tokens, limit)

        val lowered = tokens.map { it.lowercase() }.distinct()
        val conds = listOfNotNull(
            base.contextId?.let { "p.contextId = \$contextId" },
            base.statuses?.takeIf { it.isNotEmpty() }?.let { "p.status IN \$statuses" },
            base.minEffectiveConfidence?.let { "p.effectiveConfidence >= \$minEc" },
            base.minImportance?.let { "p.importance >= \$minImp" }
        )
        val params = buildMap {
            put("tokens", lowered)
            put("limit", limit)
            base.contextId?.let { put("contextId", it.value) }
            base.statuses?.takeIf { it.isNotEmpty() }?.let { statuses ->
                put("statuses", statuses.map { it.name })
            }
            base.minEffectiveConfidence?.let { put("minEc", it) }
            base.minImportance?.let { put("minImp", it) }
        }
        val whereHead = if (conds.isEmpty()) "" else "WHERE " + conds.joinToString(" AND ")

        // A single-column RETURN yields the raw column values (here proposition ids), not row maps.
        @Suppress("UNCHECKED_CAST")
        val ids = persistenceManager.query(
            QuerySpecification.withStatement(
                """
                MATCH (p:Proposition)
                $whereHead
                WITH p, size([t IN ${'$'}tokens WHERE toLower(p.text) CONTAINS t]) AS overlap
                WHERE overlap > 0
                RETURN p.id AS id
                ORDER BY overlap DESC, coalesce(p.effectiveConfidence, -1.0) DESC
                LIMIT ${'$'}limit
                """.trimIndent()
            ).bind(params)
        ) as List<String>

        val byId = hydrate(ids)
        return ids.mapNotNull { byId[it] }
    }

    /**
     * True when [keywordOverlap]'s hand-written Cypher covers every filter [base] carries. It handles
     * contextId, statuses, minEffectiveConfidence, and minImportance; anything else routes to the
     * portable default so the result stays correct (just less optimal).
     */
    private fun keywordPushable(base: PropositionQuery): Boolean =
        base.entityId == null && base.anyEntityIds == null && base.allEntityIds == null &&
            base.minLevel == null && base.maxLevel == null &&
            base.createdAfter == null && base.createdBefore == null &&
            base.revisedAfter == null && base.revisedBefore == null &&
            base.accessedAfter == null && base.accessedBefore == null &&
            base.minReinforceCount == null && base.minTrustScore == null && base.pinned == null &&
            base.minConfidence == null && base.belowEffectiveConfidence == null &&
            base.effectiveConfidenceAsOf == null && base.decayK == DEFAULT_DECAY_K

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
        val specs = graphObjectManager.loadAll<PropositionView> {
            where { proposition.contextId.isNotNull() } // skip malformed/foreign :Proposition nodes (see findAll)
        }.mapNotNull { view ->
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
    // ========================================================================
    // GraphQueryCapable — entity-axis graph queries pushed down to Cypher
    // ========================================================================

    /**
     * This backend confines every entity-axis walk to a supplied context in its own Cypher (a
     * `p.contextId = $ctx` predicate on each hop), so the portable facade routes context-scoped
     * queries straight down here instead of falling back to the proposition-edge path.
     */
    override val honorsContextFilter: Boolean get() = true

    @Transactional(readOnly = true)
    override fun neighborhood(entityId: String, depth: Int): GraphNeighborhood =
        neighborhood(entityId, depth, null as ContextId?)

    /**
     * Native entity neighbourhood: [GraphProjectionCypher.neighborhood] walks the entity projection
     * in Neo4j and hands back each reachable entity, its shortest hop distance, and the proposition
     * ids on a shortest final hop into it. We only hydrate those `via` propositions (via the lean
     * view) — the traversal itself never leaves the database. A non-null [contextId] confines the
     * walk to that context (every hop's proposition must match); null is unscoped. Called directly
     * (bypassing the facade's own ceiling), this bounds the walk at [GraphProjectionCypher.MAX_DEPTH].
     */
    @Transactional(readOnly = true)
    override fun neighborhood(entityId: String, depth: Int, contextId: ContextId?): GraphNeighborhood =
        neighborhoodNative(entityId, depth, contextId, GraphProjectionCypher.MAX_DEPTH)

    /**
     * Same walk as above, but bounded by the caller's own [maxDepth] ceiling instead of the store's
     * hard cap — this is the overload [com.embabel.dice.query.graph.GraphQuery] actually calls, so a
     * facade configured with a smaller (or larger) ceiling than [GraphProjectionCypher.MAX_DEPTH] gets
     * a walk that honors it, clamped at that hard cap.
     */
    @Transactional(readOnly = true)
    override fun neighborhood(entityId: String, depth: Int, contextId: ContextId?, maxDepth: Int): GraphNeighborhood =
        neighborhoodNative(entityId, depth, contextId, clampToNativeCeiling(maxDepth))

    private fun neighborhoodNative(entityId: String, depth: Int, contextId: ContextId?, ceiling: Int): GraphNeighborhood {
        val bound = depth.coerceIn(1, ceiling)
        val params = mutableMapOf<String, Any>("origin" to entityId)
        contextId?.let { params["ctx"] = it.value }
        @Suppress("UNCHECKED_CAST")
        val rows = persistenceManager.query(
            QuerySpecification.withStatement(GraphProjectionCypher.neighborhood(bound, contextId))
                .bind(params) as QuerySpecification<Any>,
        ).filterIsInstance<Map<*, *>>()

        val byId = hydrate(rows.flatMap { it["viaIds"].asStringList() })
        val neighbours = rows.map { row ->
            RelatedEntity(
                entityId = row["entityId"] as String,
                via = row["viaIds"].asStringList().distinct().mapNotNull { byId[it] },
                distance = (row["distance"] as Number).toInt(),
            )
        }
        return GraphNeighborhood(entityId = entityId, neighbours = neighbours)
    }

    @Transactional(readOnly = true)
    override fun pathBetween(entityIdA: String, entityIdB: String): List<GraphPath> =
        pathBetween(entityIdA, entityIdB, null as ContextId?)

    /**
     * Native shortest path: [GraphProjectionCypher.pathBetween] returns the shortest entity sequence
     * (up to [GraphProjectionCypher.MAX_DEPTH] hops) and the connecting proposition ids; empty when
     * the two entities are unreachable. Same-entity is the trivial one-node path, as in the portable
     * facade. A non-null [contextId] confines every hop to that context; null is unscoped. Called
     * directly (bypassing the facade's own ceiling), this bounds the walk at
     * [GraphProjectionCypher.MAX_DEPTH].
     */
    @Transactional(readOnly = true)
    override fun pathBetween(entityIdA: String, entityIdB: String, contextId: ContextId?): List<GraphPath> =
        pathBetweenNative(entityIdA, entityIdB, contextId, GraphProjectionCypher.MAX_DEPTH)

    /**
     * Same walk as above, but bounded by the caller's own [maxDepth] ceiling instead of the store's
     * hard cap — this is the overload [com.embabel.dice.query.graph.GraphQuery] actually calls, so a
     * facade configured with a smaller (or larger) ceiling than [GraphProjectionCypher.MAX_DEPTH] gets
     * a walk that honors it, clamped at that hard cap.
     */
    @Transactional(readOnly = true)
    override fun pathBetween(entityIdA: String, entityIdB: String, contextId: ContextId?, maxDepth: Int): List<GraphPath> =
        pathBetweenNative(entityIdA, entityIdB, contextId, clampToNativeCeiling(maxDepth))

    private fun pathBetweenNative(entityIdA: String, entityIdB: String, contextId: ContextId?, ceiling: Int): List<GraphPath> {
        if (entityIdA == entityIdB) return listOf(GraphPath(entityIds = listOf(entityIdA), edges = emptyList()))
        val params = mutableMapOf<String, Any>("a" to entityIdA, "b" to entityIdB)
        contextId?.let { params["ctx"] = it.value }
        @Suppress("UNCHECKED_CAST")
        val row = persistenceManager.query(
            QuerySpecification.withStatement(GraphProjectionCypher.pathBetween(ceiling, contextId))
                .bind(params) as QuerySpecification<Any>,
        ).filterIsInstance<Map<*, *>>().firstOrNull() ?: return emptyList()

        val edgeIds = row["edgeIds"].asStringList()
        val byId = hydrate(edgeIds)
        return listOf(
            GraphPath(
                entityIds = row["entityIds"].asStringList(),
                edges = edgeIds.mapNotNull { byId[it] },
            ),
        )
    }

    /**
     * Clamp a caller-supplied depth ceiling to this store's hard cap, warning when the caller asked
     * for more than the walk can give. A silent truncation here is exactly the bug this clamp exists
     * to avoid, so it logs instead of quietly dropping hops.
     */
    private fun clampToNativeCeiling(requestedMaxDepth: Int): Int {
        if (requestedMaxDepth > GraphProjectionCypher.MAX_DEPTH) {
            logger.warn(
                "Requested maxDepth {} exceeds native graph query ceiling {}; clamping to the ceiling",
                requestedMaxDepth,
                GraphProjectionCypher.MAX_DEPTH,
            )
        }
        return GraphProjectionCypher.clampDepth(requestedMaxDepth)
    }

    @Transactional(readOnly = true)
    override fun whyExplain(propositionId: String): PropositionLineage? =
        whyExplain(propositionId, null)

    /**
     * Native lineage: read the proposition's own durable fields (provenance, grounding, reinforcement,
     * status, temporal) and resolve its abstraction sources via [findSources]. A non-null [contextId]
     * treats a proposition in another context as absent (null), matching the portable facade's scoped
     * lineage. Null when no such proposition exists.
     */
    @Transactional(readOnly = true)
    override fun whyExplain(propositionId: String, contextId: ContextId?): PropositionLineage? {
        val prop = findById(propositionId) ?: return null
        if (contextId != null && prop.contextId != contextId) return null
        return PropositionLineage(
            proposition = prop,
            provenanceEntries = prop.provenanceEntries,
            groundingChunkIds = prop.grounding,
            sources = findSources(prop),
            reinforceCount = prop.reinforceCount,
            status = prop.status,
            temporal = prop.temporal,
        )
    }

    /** Load the given proposition ids through the lean view, mapped and keyed by id. */
    private fun hydrate(ids: List<String>): Map<String, Proposition> {
        val distinct = ids.distinct()
        if (distinct.isEmpty()) return emptyMap()
        return graphObjectManager.loadAll<PropositionView> { where { proposition.id inList distinct } }
            .associate { it.proposition.id to PropositionGraphMapper.toProposition(it) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asStringList(): List<String> = (this as? List<String>) ?: emptyList()

    context(builder: OrderBuilder<PropositionViewQueryDsl>)
    private fun orderByEffectiveConfidenceDescNullsLast() {
        builder(OrderSpec("coalesce(proposition.effectiveConfidence, -1.0)", OrderDirection.DESC))
    }

    companion object {
        /** Stripe count for save-time dedup locks. */
        private const val DEDUP_STRIPES = 64

        /**
         * The node label and embedding property the proposition vector index covers. These mirror
         * the `@VectorIndex` annotation on `PropositionNode.embedding`, which `loadNearest` reads but
         * can't be configured — so they're the canonical identity every other path derives from.
         */
        const val VECTOR_INDEX_LABEL = "Proposition"
        const val VECTOR_INDEX_PROPERTY = "embedding"

        /**
         * The one vector-index name every search path must agree on. Drivine derives it as
         * `{label}_{property}_vector` from the annotation, and `findClusters` and the schema (DDL)
         * are wired to this same value, so all three (`loadNearest`, `findClusters`, schema) hit the
         * same index.
         */
        const val VECTOR_INDEX = VECTOR_INDEX_LABEL + "_" + VECTOR_INDEX_PROPERTY + "_vector"

        /** Mirrors the [PropositionQuery.decayK] default; the materialised column is computed at this k. */
        private const val DEFAULT_DECAY_K = 2.0

        /**
         * Cascade-aware bulk-clear Cypher, externalised to `queries/clear_propositions.cypher` so it runs
         * standalone for bench-testing. Loaded once via Drivine's [QueryLoader] (which doesn't cache).
         */
        private val CLEAR_PROPOSITIONS: CypherStatement = CypherStatement(QueryLoader.loadQuery("clear_propositions"))
    }
}

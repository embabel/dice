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
package com.embabel.dice.proposition.store

import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.service.Cluster
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.core.types.ZeroToOne
import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.ContextSetVectorSearchCapable
import com.embabel.dice.proposition.ExactVectorBudget
import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.ProvenanceScanningSourceRevisionQueries
import com.embabel.dice.proposition.ProvenanceSubtractionCapable
import com.embabel.dice.proposition.ReadOrder
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.proposition.StrictPolicyReadCapable
import com.embabel.dice.proposition.StrictReadRow
import com.embabel.dice.proposition.matchesFilters
import com.embabel.dice.provenance.ProvenanceEntry
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory implementation of PropositionRepository with optional vector similarity search.
 * Thread-safe using ConcurrentHashMap, but not intended for production use.
 *
 * The embedding service is optional. When one is supplied, embeddings are computed and cached
 * for cosine similarity search and clustering. When it is absent (the default), the
 * legacy vector similarity and clustering paths return empty results. Scoped vector reads fail
 * explicitly as unsupported. Strict grant-order and approved-text reads need no embedder. Plain storage and
 * lookups (save, findById, findAll, findByContextIdValue, etc.) keep working. This lets consumers
 * that only need store-and-retrieve construct the repository without supplying an embedder.
 *
 * @param embeddingService optional embedder; when null, legacy vector reads return empty results
 *   and scoped vector reads fail explicitly as unsupported.
 */
class InMemoryPropositionRepository(
    private val embeddingService: EmbeddingService? = null,
) : PropositionRepository, ContextSetVectorSearchCapable, StrictPolicyReadCapable, ProvenanceScanningSourceRevisionQueries, ProvenanceSubtractionCapable {

    private val logger = LoggerFactory.getLogger(InMemoryPropositionRepository::class.java)

    private val propositions = ConcurrentHashMap<String, Proposition>()
    private val embeddings = ConcurrentHashMap<String, FloatArray>()

    private val scopedReads = LocalScopedReadOperations { propositions.values }
    private val policyReads = LocalPolicyReadOperations(scopedReads)

    override fun queryByGrantOrder(
        scope: ContextReadScope,
        spec: ScopedReadSpec,
        rows: List<StrictReadRow>,
        limit: Int,
        timeout: Duration,
    ): List<Proposition> = policyReads.queryByGrantOrder(scope, spec, rows, limit, timeout)

    override fun keywordByApprovedText(
        scope: ContextReadScope,
        spec: ScopedReadSpec,
        rows: List<StrictReadRow>,
        tokens: List<String>,
        limit: Int,
        timeout: Duration,
    ): List<Proposition> = policyReads.keywordByApprovedText(scope, spec, rows, tokens, limit, timeout)

    override fun query(
        scope: ContextReadScope,
        spec: ScopedReadSpec,
        order: ReadOrder,
        limit: Int,
    ): List<Proposition> = scopedReads.query(scope, spec, order, limit)

    override fun count(scope: ContextReadScope, spec: ScopedReadSpec): Long = scopedReads.count(scope, spec)

    override fun keywordOverlap(
        scope: ContextReadScope,
        spec: ScopedReadSpec,
        tokens: List<String>,
        limit: Int,
    ): List<Proposition> = scopedReads.keywordOverlap(scope, spec, tokens, limit)

    override val supportsScopedVectors: Boolean get() = embeddingService != null

    override fun findSimilarWithScores(
        request: TextSimilaritySearchRequest,
        scope: ContextReadScope,
        spec: ScopedReadSpec,
        budget: ExactVectorBudget,
    ): List<SimilarityResult<Proposition>> {
        val embedder = embeddingService ?: throw ScopedReadException("SCOPED_VECTOR_UNSUPPORTED")
        return scopedReads.findSimilarWithScores(request, scope, spec, budget, embedder::embed) { key ->
            embeddings[key.propositionId]
        }
    }

    override val luceneSyntaxNotes: String
        get() = "no lucene support"

    /** Vector search only works when an embedder was supplied; otherwise the type claims it but can't. */
    override val supportsVector: Boolean get() = embeddingService != null

    /**
     * Embeds BEFORE storing, as [JsonFilePropositionRepository] does: a failing embedder throws
     * with nothing changed, rather than leaving the proposition stored beside a missing or stale
     * vector that vector search would then silently disagree with.
     */
    override fun save(proposition: Proposition): Proposition {
        val embedding = embeddingService?.embed(proposition.text)
        propositions[proposition.id] = proposition
        embedding?.let { embeddings[proposition.id] = it }
        return proposition
    }

    override fun findById(id: String): Proposition? = propositions[id]

    override fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition> =
        propositions.values.filter { proposition ->
            proposition.mentions.any { it.resolvedId == entityIdentifier.id }
        }

    override fun findSimilar(textSimilaritySearchRequest: TextSimilaritySearchRequest): List<Proposition> =
        findSimilarWithScores(textSimilaritySearchRequest).map { it.match }

    override fun findSimilarWithScores(
        textSimilaritySearchRequest: TextSimilaritySearchRequest,
    ): List<SimilarityResult<Proposition>> {
        require(textSimilaritySearchRequest.topK >= 0) { "topK must not be negative" }
        if (textSimilaritySearchRequest.topK == 0 || propositions.isEmpty()) return emptyList()

        val embedder = embeddingService ?: run {
            logger.debug("Vector search requested but no embedder is configured; returning empty results")
            return emptyList()
        }
        val queryEmbedding = embedder.embed(textSimilaritySearchRequest.query)
        val minSimilarity = textSimilaritySearchRequest.similarityThreshold

        return propositions.values
            .mapNotNull { prop ->
                val propEmbedding = embeddings[prop.id] ?: return@mapNotNull null
                val similarity = cosineSimilarity(queryEmbedding, propEmbedding)
                if (similarity >= minSimilarity) SimilarityResult(match = prop, score = similarity) else null
            }
            .sortedWith(LocalScopedReadOperations.similarityOrder)
            .take(textSimilaritySearchRequest.topK)
    }

    override fun findSimilarWithScores(
        textSimilaritySearchRequest: TextSimilaritySearchRequest,
        query: PropositionQuery,
    ): List<SimilarityResult<Proposition>> {
        require(textSimilaritySearchRequest.topK >= 0) { "topK must not be negative" }
        if (textSimilaritySearchRequest.topK == 0 || propositions.isEmpty()) return emptyList()

        val embedder = embeddingService ?: run {
            logger.debug("Vector search requested but no embedder is configured; returning empty results")
            return emptyList()
        }
        val queryEmbedding = embedder.embed(textSimilaritySearchRequest.query)
        val minSimilarity = textSimilaritySearchRequest.similarityThreshold

        // Pre-filter propositions before computing similarities. Delegates to the same shared
        // predicate as query() so the vector pre-filter honours every filter the composable query
        // does (temporal windows, effective-confidence, importance, trust) — not just a subset.
        // One evaluation instant for the whole pre-filter, so every candidate is judged against the same now.
        val asOf = query.effectiveConfidenceAsOf ?: Instant.now()
        val candidates = propositions.values.filter { query.matchesFilters(it, asOf) }

        return candidates
            .mapNotNull { prop ->
                val propEmbedding = embeddings[prop.id] ?: return@mapNotNull null
                val similarity = cosineSimilarity(queryEmbedding, propEmbedding)
                if (similarity >= minSimilarity) SimilarityResult(match = prop, score = similarity) else null
            }
            .sortedWith(LocalScopedReadOperations.similarityOrder)
            .take(textSimilaritySearchRequest.topK)
    }

    override fun findClusters(
        similarityThreshold: ZeroToOne,
        topK: Int,
        query: PropositionQuery,
    ): List<Cluster<Proposition>> {
        val candidates = query(query)
        val candidateIds = candidates.map { it.id }.toSet()

        return candidates.mapNotNull { anchor ->
            val anchorEmbedding = embeddings[anchor.id] ?: return@mapNotNull null

            val similar = candidates
                .filter { it.id != anchor.id && anchor.id < it.id }
                .mapNotNull { other ->
                    val otherEmbedding = embeddings[other.id] ?: return@mapNotNull null
                    val score = cosineSimilarity(anchorEmbedding, otherEmbedding)
                    if (score >= similarityThreshold) SimilarityResult(match = other, score = score) else null
                }
                .sortedWith(LocalScopedReadOperations.similarityOrder)
                .take(topK)

            if (similar.isNotEmpty()) Cluster(anchor = anchor, similar = similar) else null
        }.sortedByDescending { it.similar.size }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Double =
        LocalScopedReadOperations.cosineSimilarity(a, b)

    override fun findByStatus(status: PropositionStatus): List<Proposition> =
        propositions.values.filter { it.status == status }

    override fun findByGrounding(chunkId: String): List<Proposition> =
        propositions.values.filter { chunkId in it.grounding }

    override fun findByMinLevel(minLevel: Int): List<Proposition> =
        propositions.values.filter { it.level >= minLevel }

    override fun findByContextId(contextId: ContextId): List<Proposition> =
        propositions.values.filter { it.contextId == contextId }

    override fun findAll(): List<Proposition> = propositions.values.toList()

    override fun delete(id: String): Boolean {
        embeddings.remove(id)
        return propositions.remove(id) != null
    }

    override fun count(): Int = propositions.size

    // ========================================================================
    // Provenance management
    // ========================================================================

    /**
     * Takes a whole collapse's fold off in one atomic step, honouring
     * [ProvenanceSubtractionCapable]'s contract.
     *
     * `ConcurrentHashMap.compute` holds the bin for this id while the remapping function runs, and
     * every other write here goes through the same map, so a concurrent [addProvenance] or [save]
     * on this proposition waits its turn. Whichever of the two lands first, both effects are on the
     * proposition when they finish.
     *
     * Handing back null for an absent id also leaves the map alone, so a proposition another writer
     * has deleted stays deleted. Embeddings are keyed on text, which a subtraction never changes,
     * so the embedding cache needs no attention here.
     */
    override fun subtractFoldedEvidence(
        propositionId: String,
        provenanceRefs: List<String>,
        grounding: Collection<String>,
        sourceIds: Collection<String>,
    ): Proposition? =
        propositions.compute(propositionId) { _, current ->
            if (current == null) {
                null
            } else {
                val updated = current.withoutFoldedEvidence(
                    groundingToRemove = grounding.toList(),
                    provenanceRefsToRemove = provenanceRefs,
                    sourceIdsToRemove = sourceIds.toList(),
                )
                val nothingMatched = updated.grounding == current.grounding &&
                    updated.provenanceEntries == current.provenanceEntries &&
                    updated.sourceIds == current.sourceIds
                if (nothingMatched) current else updated
            }
        }

    /**
     * Appends evidence in one atomic step, so an entry added while a [subtractProvenance] is in
     * flight survives it.
     *
     * The base contract's default reads through [findById] and writes through [save], which leaves
     * a window where a subtraction running alongside can replace the append away. Both operations
     * go through `compute` here, which closes it — and makes the pair testable under real threads.
     */
    override fun addProvenance(propositionId: String, entries: List<ProvenanceEntry>): Proposition? =
        propositions.compute(propositionId) { _, current ->
            current?.withProvenanceEntries(entries)
        }

    /**
     * Clear all propositions and cached embeddings. Useful for testing.
     */
    fun clear() {
        propositions.clear()
        embeddings.clear()
    }
}

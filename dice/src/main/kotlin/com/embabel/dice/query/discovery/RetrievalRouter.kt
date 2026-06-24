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
package com.embabel.dice.query.discovery

import com.embabel.agent.core.ContextId
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.proposition.TemporalQueryCapable
import com.embabel.dice.proposition.VectorSearchCapable
import com.embabel.dice.query.graph.GraphQuery
import org.slf4j.LoggerFactory

/**
 * The single retrieval router shared by every discovery presentation tier (MCP tools, REST).
 *
 * Routes each [RetrievalMode] to its capability fragment via `as?`-checks and degrades gracefully:
 * an absent fragment yields a typed-empty result with `supported = false`, never a silent full
 * scan. The context is baked in at construction so a caller cannot read across contexts — only the
 * query's mode/text/entity/window/bounds are honoured, never a context override.
 *
 * Traversal depth and result size are clamped before routing to bound cost.
 *
 * @param store the backing proposition store; its declared fragments determine which modes are
 *   natively supported
 * @param graphQuery the portable graph facade for GRAPH_WALK and the HYBRID expansion arm
 * @param contextId the fixed access-control scope for this router
 */
class RetrievalRouter(
    private val store: PropositionStore,
    private val graphQuery: GraphQuery,
    private val contextId: ContextId,
) {

    private val logger = LoggerFactory.getLogger(RetrievalRouter::class.java)

    /** Whether the fragment backing [mode] is present on the wrapped store. */
    fun supports(mode: RetrievalMode): Boolean = when (mode) {
        RetrievalMode.VECTOR -> store is VectorSearchCapable
        RetrievalMode.TEMPORAL -> store is TemporalQueryCapable
        RetrievalMode.ENTITY -> true
        RetrievalMode.GRAPH_WALK -> true
        RetrievalMode.HYBRID -> store is VectorSearchCapable
    }

    /**
     * Execute [query], returning a leak-free [DiscoveryResult]. Never throws for an absent fragment;
     * degrades to typed-empty with `supported = false` instead.
     */
    fun retrieve(query: DiscoveryQuery): DiscoveryResult {
        val topK = clampTopK(query.topK)
        val depth = clampDepth(query.depth)
        logger.debug("Routing {} query (topK={} depth={}) for context {}", query.mode, topK, depth, contextId.value.take(8))
        val result = when (query.mode) {
            RetrievalMode.VECTOR -> vector(query.text, topK)
            RetrievalMode.ENTITY -> entity(query.entityId, topK)
            RetrievalMode.GRAPH_WALK -> graphWalk(query.entityId, depth, topK)
            RetrievalMode.TEMPORAL -> temporal(query, topK)
            RetrievalMode.HYBRID -> hybrid(query.text, query.entityId, depth, topK)
        }
        logger.debug("Retrieval {} returned {} results (supported={})", query.mode, result.propositions.size, result.supported)
        return result
    }

    /**
     * Path query mapped to leak-free path DTOs. The path edges are filtered to the bound context so
     * a caller can never observe an edge proposition belonging to another context.
     */
    fun graphPath(entityIdA: String, entityIdB: String): List<PathDto> {
        val found = graphQuery.pathBetween(entityIdA, entityIdB)
        val kept = found.filter { path -> path.edges.all { it.contextId == contextId } }
        val dropped = found.size - kept.size
        if (dropped > 0) {
            // A path crossing into another context is dropped wholesale for isolation. Surface it so
            // an empty result is distinguishable from a genuinely disconnected graph.
            logger.debug("Graph path {} -> {}: dropped {} cross-context path(s)", entityIdA, entityIdB, dropped)
        }
        logger.debug("Graph path {} -> {}: {} path(s) kept", entityIdA, entityIdB, kept.size)
        return kept.map { PathDto.from(it) }
    }

    /**
     * Lineage query mapped to a leak-free lineage DTO (null when absent). Returns not-found (null)
     * when the resolved proposition belongs to another context, so a caller bound to one context can
     * never read lineage for a foreign-context proposition id.
     */
    fun whyExplain(propositionId: String): LineageDto? =
        graphQuery.whyExplain(propositionId)
            ?.takeIf { it.proposition.contextId == contextId }
            ?.let { LineageDto.from(it) }

    // ------------------------------------------------------------------------
    // Per-mode routing
    // ------------------------------------------------------------------------

    private fun vector(text: String?, topK: Int): DiscoveryResult {
        val capable = store as? VectorSearchCapable
            ?: run {
                logger.debug("VECTOR mode not supported by store {}", store::class.simpleName)
                return empty(RetrievalMode.VECTOR, supported = false)
            }
        if (text.isNullOrBlank()) return DiscoveryResult(RetrievalMode.VECTOR, supported = true, propositions = emptyList())
        val hits = capable.findSimilarWithScores(searchRequest(text, topK), scope()).map { it.match }
        return result(RetrievalMode.VECTOR, supported = true, props = hits)
    }

    private fun entity(entityId: String?, topK: Int): DiscoveryResult {
        if (entityId.isNullOrBlank()) {
            return DiscoveryResult(RetrievalMode.ENTITY, supported = true, propositions = emptyList())
        }
        val props = store.query(scope().withEntityId(entityId)).take(topK)
        return result(RetrievalMode.ENTITY, supported = true, props = props)
    }

    private fun graphWalk(entityId: String?, depth: Int, topK: Int): DiscoveryResult {
        if (entityId.isNullOrBlank()) {
            return DiscoveryResult(RetrievalMode.GRAPH_WALK, supported = true, propositions = emptyList())
        }
        val props = neighbourhoodVia(entityId, depth).take(topK)
        return result(RetrievalMode.GRAPH_WALK, supported = true, props = props)
    }

    private fun temporal(query: DiscoveryQuery, topK: Int): DiscoveryResult {
        val capable = store as? TemporalQueryCapable
            ?: run {
                logger.debug("TEMPORAL mode not supported by store {}", store::class.simpleName)
                return empty(RetrievalMode.TEMPORAL, supported = false)
            }
        val from = query.from
        val to = query.to
        if (from == null || to == null) {
            return DiscoveryResult(RetrievalMode.TEMPORAL, supported = true, propositions = emptyList())
        }
        // Scope to the bound context: never return another context's propositions. The fragment may
        // over-fetch across contexts (its default body scans findAll()), so filter before truncating.
        val props = capable.findByCreatedBetween(from, to)
            .filter { it.contextId == contextId }
            .take(topK)
        return result(RetrievalMode.TEMPORAL, supported = true, props = props)
    }

    private fun hybrid(text: String?, entityId: String?, depth: Int, topK: Int): DiscoveryResult {
        val capable = store as? VectorSearchCapable
        val vectorHits: List<SimilarityResult<Proposition>> =
            if (capable != null && !text.isNullOrBlank()) {
                capable.findSimilarWithScores(searchRequest(text, topK), scope())
            } else {
                emptyList()
            }
        val graphOnly: List<Proposition> =
            if (!entityId.isNullOrBlank()) neighbourhoodVia(entityId, depth) else emptyList()

        // Merge by proposition id. Vector hits keep their score and a higher tier; graph-only edges
        // fall to a lower tier with a sentinel score. Sort deterministically by (tier, score desc,
        // id asc), then truncate to topK.
        val merged = LinkedHashMap<String, MergeEntry>()
        vectorHits.forEach { hit ->
            merged[hit.match.id] = MergeEntry(hit.match, tier = 0, score = hit.score)
        }
        graphOnly.forEach { prop ->
            if (prop.id !in merged) {
                merged[prop.id] = MergeEntry(prop, tier = 1, score = Double.NEGATIVE_INFINITY)
            }
        }
        val ordered = merged.values
            .sortedWith(
                compareBy<MergeEntry> { it.tier }
                    .thenByDescending { it.score }
                    .thenBy { it.proposition.id },
            )
            .take(topK)
            .map { it.proposition }

        return result(RetrievalMode.HYBRID, supported = capable != null, props = ordered)
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /**
     * The edge propositions reachable from [entityId], deduped by id, preserving discovery order.
     *
     * Filtered to the bound context so the router enforces its own scope even if it was wired with a
     * [GraphQuery] that is not context-scoped — a caller can never observe a foreign-context edge.
     */
    private fun neighbourhoodVia(entityId: String, depth: Int): List<Proposition> =
        graphQuery.neighborhood(entityId, depth).neighbours
            .flatMap { it.via }
            .filter { it.contextId == contextId }
            .distinctBy { it.id }

    /** A query bound to this router's context — the access-control scope applied to every mode. */
    private fun scope(): PropositionQuery = PropositionQuery.forContextId(contextId)

    private fun searchRequest(text: String, topK: Int): TextSimilaritySearchRequest =
        TextSimilaritySearchRequest(query = text, similarityThreshold = 0.0, topK = topK)

    private fun result(mode: RetrievalMode, supported: Boolean, props: List<Proposition>): DiscoveryResult =
        DiscoveryResult(mode, supported, props.map { PropositionSummaryDto.from(it) })

    private fun empty(mode: RetrievalMode, supported: Boolean): DiscoveryResult =
        DiscoveryResult(mode, supported, emptyList())

    private fun clampDepth(depth: Int): Int = depth.coerceIn(MIN_DEPTH, MAX_DEPTH)

    private fun clampTopK(topK: Int): Int = topK.coerceIn(1, MAX_TOP_K)

    private data class MergeEntry(val proposition: Proposition, val tier: Int, val score: Double)

    companion object {
        private const val MIN_DEPTH = 1
        private const val MAX_DEPTH = 5
        private const val MAX_TOP_K = 100
    }
}

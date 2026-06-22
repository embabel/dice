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
package com.embabel.dice.proposition

import com.embabel.agent.rag.service.Cluster
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.core.types.ZeroToOne
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(VectorSearchCapable::class.java)

/**
 * Opt-in capability for proposition stores that support vector similarity search and clustering.
 *
 * Only implement this if the store has a real embedder backing it. Consumers can check for the
 * capability with an `is`/`as?` test rather than calling and guessing from an empty result.
 */
interface VectorSearchCapable {

    /**
     * Whether this particular instance can actually run vector search. Implementing the interface is a
     * type-level promise; this is the runtime truth. A store that can be wired without an embedder
     * (so it satisfies the type but has nothing to embed with) should override this to report whether
     * it was given one. Lets a caller — e.g. [PropositionStoreTemplate.supportsVector] — distinguish
     * "configured for vectors" from "vector search will quietly return empty".
     */
    val supportsVector: Boolean get() = true

    /**
     * Query propositions by specification. Required by [findSimilarWithScores] (filtered overload)
     * and [findClusters]; satisfied automatically when a type also implements [PropositionStore].
     */
    fun query(query: PropositionQuery): List<Proposition>

    /**
     * Find propositions similar to the given text using vector similarity.
     * @return Similar propositions ordered by similarity (most similar first)
     */
    fun findSimilar(textSimilaritySearchRequest: TextSimilaritySearchRequest): List<Proposition> =
        findSimilarWithScores(textSimilaritySearchRequest).map { it.match }

    /**
     * Find propositions similar to the given text with similarity scores.
     * @return Pairs of (proposition, similarity) ordered by similarity (most similar first)
     */
    fun findSimilarWithScores(
        textSimilaritySearchRequest: TextSimilaritySearchRequest,
    ): List<SimilarityResult<Proposition>>

    /**
     * Vector similarity search with an additional [PropositionQuery] filter applied to results.
     *
     * The default runs vector search then filters in memory via [query]. Override to push the
     * filter to the backend for better performance.
     *
     * @param textSimilaritySearchRequest The similarity search parameters
     * @param query Additional filter to apply to the vector results
     * @return Matching propositions ordered by similarity
     */
    fun findSimilarWithScores(
        textSimilaritySearchRequest: TextSimilaritySearchRequest,
        query: PropositionQuery,
    ): List<SimilarityResult<Proposition>> {
        // Default: get vector results then filter using query criteria
        val vectorResults = findSimilarWithScores(textSimilaritySearchRequest)
        val matchingIds = this.query(query).map { it.id }.toSet()
        val filtered = vectorResults.filter { it.match.id in matchingIds }
        logger.debug("findSimilarWithScores+query: {} vector hit(s) -> {} after query filter", vectorResults.size, filtered.size)
        return filtered
    }

    // ========================================================================
    // Clustering - discover natural groupings of similar propositions
    // ========================================================================

    /**
     * Find clusters of similar propositions.
     *
     * Each cluster has an anchor proposition and a list of similar propositions
     * above the similarity threshold. Clusters are deduplicated so that each
     * pair appears only once (the proposition with the lower ID is the anchor).
     *
     * @param similarityThreshold Minimum cosine similarity to include in a cluster
     * @param topK Maximum number of similar items per cluster
     * @param query Optional query to pre-filter which propositions participate
     * @return Clusters ordered by size (largest first), excluding empty clusters
     */
    fun findClusters(
        similarityThreshold: ZeroToOne = 0.7,
        topK: Int = 10,
        query: PropositionQuery = PropositionQuery(),
    ): List<Cluster<Proposition>> {
        val candidates = query(query)
        val candidateIds = candidates.map { it.id }.toSet()

        val clusters = candidates.mapNotNull { anchor ->
            val similar = findSimilarWithScores(
                TextSimilaritySearchRequest(
                    query = anchor.text,
                    similarityThreshold = similarityThreshold,
                    topK = topK + 1, // +1 because the anchor itself may appear
                ),
            )
                .filter { it.match.id != anchor.id && it.match.id in candidateIds }
                .filter { anchor.id < it.match.id }
                .take(topK)

            if (similar.isNotEmpty()) Cluster(anchor = anchor, similar = similar) else null
        }.sortedByDescending { it.similar.size }
        logger.debug(
            "findClusters: {} candidate(s) -> {} cluster(s) (threshold={}, topK={})",
            candidates.size, clusters.size, similarityThreshold, topK,
        )
        return clusters
    }
}

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
package com.embabel.dice.conformance

import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.store.CapabilityReporting
import com.embabel.dice.store.FilteredVectorSearch
import com.embabel.dice.store.SimilarityScore
import com.embabel.dice.store.StoreCapabilities
import com.embabel.dice.store.StoreCapability
import com.embabel.dice.store.VectorMatch
import com.embabel.dice.store.VectorSearchRequest
import kotlin.math.sqrt

/** What a [FilterFirstReferenceStore] does wrong, so a test can show a suite case fail. */
internal enum class ReferenceFault {
    /** Keeps every rule. */
    NONE,

    /** Cuts to K over every proposition and then drops the ones that fail the filter. */
    FILTERS_AFTER_CUT,

    /** Takes the nearest 30 times K of every proposition, drops the ones that fail the filter and cuts to K. */
    FILTERS_AFTER_OVER_FETCH,

    /** Applies every filter field except `pinned`. */
    IGNORES_PINNED,

    /** Drops a match that scores exactly the floor. */
    EXCLUSIVE_FLOOR,

    /** Reports `(1 + cosine) / 2` as the score. */
    HALVED_SCALE,

    /** Scores a proposition with an all-zero embedding as 0.0 and returns it. */
    KEEPS_ZERO_VECTORS,

    /** Returns the matches in the order they were saved and cuts to K with no sort by score. */
    UNSORTED,

    /** Applies context and status, takes the nearest 2 times K and then applies the other filter fields. */
    FIELDS_AFTER_OVER_FETCH,
}

/**
 * An in-memory proposition store with filtered vector search, for running the filtered vector
 * search suite inside the kit. It scans every candidate and embeds texts with
 * [ConformanceEmbeddings.embed] at 8 dimensions.
 *
 * With [ReferenceFault.NONE] it keeps the rules the suite checks. Each other fault breaks one of
 * them, so a test can show which suite case catches it.
 *
 * @param fault The rule this store breaks, or [ReferenceFault.NONE].
 * @param delegate The store that holds the propositions and applies the filter.
 */
internal class FilterFirstReferenceStore(
    private val fault: ReferenceFault = ReferenceFault.NONE,
    private val delegate: InMemoryPropositionRepository = InMemoryPropositionRepository(),
) : PropositionStore by delegate, CapabilityReporting, FilteredVectorSearch {

    override val capabilities: StoreCapabilities = StoreCapabilities.of(StoreCapability.FILTERED_VECTOR_SEARCH)

    /** The position each id was first saved at. The delegate keeps its propositions in hash order. */
    private val savedAt = mutableMapOf<String, Int>()

    override fun save(proposition: Proposition): Proposition {
        savedAt.putIfAbsent(proposition.id, savedAt.size)
        return delegate.save(proposition)
    }

    override fun findNearest(request: VectorSearchRequest): List<VectorMatch> {
        val query = embed(request.text)
        return when {
            query.all { it == 0.0f } -> emptyList()
            fault == ReferenceFault.IGNORES_PINNED ->
                ranked(delegate.query(request.filter.copy(pinned = null)), query, request).take(request.topK)
            fault == ReferenceFault.FILTERS_AFTER_CUT ->
                filterAfterWindow(request, query, request.topK)
            fault == ReferenceFault.FILTERS_AFTER_OVER_FETCH ->
                filterAfterWindow(request, query, request.topK * 30).take(request.topK)
            fault == ReferenceFault.FIELDS_AFTER_OVER_FETCH ->
                filterAfterWindow(
                    request,
                    query,
                    request.topK * 2,
                    delegate.query(PropositionQuery(contextId = request.filter.contextId, statuses = request.filter.statuses)),
                ).take(request.topK)
            else ->
                ranked(delegate.query(request.filter), query, request).take(request.topK)
        }.map { (proposition, cosine) -> VectorMatch.of(proposition, score(cosine)) }
    }

    /**
     * Turns a cosine into the score the store reports.
     *
     * @param cosine The cosine similarity of a match.
     * @return The cosine as a score, or `(1 + cosine) / 2` under [ReferenceFault.HALVED_SCALE].
     */
    private fun score(cosine: Double): SimilarityScore =
        SimilarityScore.ofCosine(if (fault == ReferenceFault.HALVED_SCALE) (1.0 + cosine) / 2.0 else cosine)

    /**
     * Picks the nearest [window] of the candidates and keeps the ones that match the filter.
     *
     * @param request The search request.
     * @param query The embedding of the request's text.
     * @param window How many of the nearest candidates to keep before the filter.
     * @param candidates The propositions the window is taken from. The default is every proposition.
     * @return The matching propositions in the window with their cosine, closest first.
     */
    private fun filterAfterWindow(
        request: VectorSearchRequest,
        query: FloatArray,
        window: Int,
        candidates: List<Proposition> = delegate.findAll(),
    ): List<Pair<Proposition, Double>> {
        val matchingIds = delegate.query(request.filter).map { it.id }.toSet()
        return ranked(candidates, query, request)
            .take(window)
            .filter { (proposition, _) -> proposition.id in matchingIds }
    }

    /**
     * Scores candidates against the query, drops the ones without a direction or below the score
     * floor, and orders the rest closest first, then by id. Under [ReferenceFault.UNSORTED] it
     * orders them by when they were saved.
     *
     * @param candidates The propositions to score.
     * @param query The embedding of the request's text.
     * @param request The search request, for its score floor.
     * @return Each kept proposition with its cosine, in that order.
     */
    private fun ranked(
        candidates: List<Proposition>,
        query: FloatArray,
        request: VectorSearchRequest,
    ): List<Pair<Proposition, Double>> =
        candidates
            .map { it to embed(it.text) }
            .filterNot { (_, vector) -> fault != ReferenceFault.KEEPS_ZERO_VECTORS && vector.all { it == 0.0f } }
            .map { (proposition, vector) -> proposition to cosineOrZero(query, vector) }
            .filter { (_, cosine) -> request.minScore?.let { aboveFloor(cosine, it.cosine) } ?: true }
            .sortedWith(
                if (fault == ReferenceFault.UNSORTED) {
                    compareBy<Pair<Proposition, Double>> { savedAt[it.first.id] }
                } else {
                    compareByDescending<Pair<Proposition, Double>> { it.second }.thenBy { it.first.id }
                },
            )

    /**
     * Checks a cosine against the score floor.
     *
     * @param cosine The cosine of a candidate.
     * @param floor The cosine of the request's score floor.
     * @return True when the candidate is at or above the floor, or strictly above it under
     *   [ReferenceFault.EXCLUSIVE_FLOOR].
     */
    private fun aboveFloor(cosine: Double, floor: Double): Boolean =
        if (fault == ReferenceFault.EXCLUSIVE_FLOOR) cosine > floor else cosine >= floor

    /**
     * Computes the cosine of a candidate, scoring an all-zero candidate as 0.0.
     *
     * @param query The embedding of the request's text.
     * @param vector The embedding of the candidate.
     * @return The cosine, or 0.0 when the candidate has no direction.
     */
    private fun cosineOrZero(query: FloatArray, vector: FloatArray): Double =
        if (vector.all { it == 0.0f }) 0.0 else cosine(query, vector)

    /**
     * Embeds a text the way the suite expects.
     *
     * @param text The text to embed.
     * @return The text's 8-dimension embedding.
     */
    private fun embed(text: String): FloatArray = ConformanceEmbeddings.embed(text, 8)

    /**
     * Computes the cosine similarity of two vectors that are not all zeros.
     *
     * @param a The first vector.
     * @param b The second vector.
     * @return The cosine, moved into -1.0 to 1.0 when rounding lands outside.
     */
    private fun cosine(a: FloatArray, b: FloatArray): Double =
        (a.indices.sumOf { a[it].toDouble() * b[it] } / (norm(a) * norm(b))).coerceIn(-1.0, 1.0)

    /**
     * Computes the length of a vector.
     *
     * @param v The vector.
     * @return Its Euclidean length.
     */
    private fun norm(v: FloatArray): Double = sqrt(v.sumOf { it.toDouble() * it })
}

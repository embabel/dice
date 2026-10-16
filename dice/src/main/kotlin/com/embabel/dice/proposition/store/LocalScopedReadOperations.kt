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

import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.CodePointOrder
import com.embabel.dice.proposition.ExactVectorBudget
import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.ScopedReadFailure
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.ReadClock
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.proposition.matchesFilters
import com.embabel.dice.proposition.rowComparator
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(LocalScopedReadOperations::class.java)

/**
 * The scoped read engine both local stores share. Every call reads the store's live map, so two
 * reads in one session can see different rows when a write lands between them.
 */
internal class LocalScopedReadOperations(
    internal val nanoTime: () -> Long = System::nanoTime,
    private val rows: () -> Collection<Proposition>,
) {

    fun query(spec: ScopedReadSpec, clock: ReadClock, order: PropositionQuery.OrderBy, limit: Int): List<Proposition> {
        require(limit >= 0) { "Limit must not be negative" }
        if (limit == 0) return emptyList()
        val ranking = order.rowComparator(clock.asOf, clock.rankingDecayK)?.then(keyOrder) ?: keyOrder
        return eligible(spec, clock).sortedWith(ranking).take(limit)
    }

    fun count(spec: ScopedReadSpec, clock: ReadClock): Long = eligible(spec, clock).size.toLong()

    fun keywordOverlap(
        spec: ScopedReadSpec,
        clock: ReadClock,
        tokens: List<String>,
        limit: Int,
    ): List<Proposition> {
        require(limit >= 0) { "Limit must not be negative" }
        val normalized = keywordTokens(tokens)
        if (limit == 0 || normalized.isEmpty()) return emptyList()
        return eligible(spec, clock)
            .map { proposition -> proposition to keywordHits(proposition.text, normalized) }
            .filter { it.second > 0 }
            .sortedWith(
                compareByDescending<Pair<Proposition, Int>> { it.second }
                    .thenByDescending { it.first.effectiveConfidenceAt(clock.asOf, clock.rankingDecayK) }
                    .thenBy(keyOrder) { it.first },
            )
            .take(limit)
            .map { it.first }
    }

    /**
     * Exact vector search over the eligible rows. It fails rather than sampling when more rows are eligible
     * than the budget allows. A stored vector of the wrong length or with a NaN or infinite component is
     * skipped, though it still counts against the budget; a bad query vector fails. The time limit covers
     * admitting and scoring rows and is checked as each row is admitted, before each stored vector is read
     * and once at the end. Time spent in the embedder is not charged.
     */
    fun findSimilarWithScores(
        request: TextSimilaritySearchRequest,
        spec: ScopedReadSpec,
        clock: ReadClock,
        budget: ExactVectorBudget,
        embed: (String) -> FloatArray,
        embeddingFor: (PropositionKey) -> FloatArray?,
    ): List<SimilarityResult<Proposition>> {
        require(request.topK >= 0) { "topK must not be negative" }
        require(request.similarityThreshold.isFinite() && request.similarityThreshold in 0.0..1.0) {
            "Similarity threshold must be finite and between zero and one"
        }
        if (request.topK == 0) return emptyList()
        val deadline = LocalReadDeadline(budget.timeout, ScopedReadFailure.EXACT_VECTOR_DEADLINE_EXCEEDED, nanoTime)
        // Count every eligible row, with or without a vector, before embedding: a budget is never a sample size.
        val candidates = eligibleRows(spec, clock, deadline::check)
            .take(minOf(budget.maxCandidates.toLong() + 1, Int.MAX_VALUE.toLong()).toInt()).toList()
        if (candidates.size > budget.maxCandidates) throw ScopedReadException(ScopedReadFailure.EXACT_VECTOR_BUDGET_EXCEEDED)
        if (candidates.isEmpty()) return emptyList()
        // The budget's time covers admitting and scoring rows, never the embedder, as on the graph store.
        val queryEmbedding = deadline.excluding { embed(request.query) }
        requireUsableVector(queryEmbedding)
        var skipped = 0
        val results = candidates.mapNotNull { proposition ->
            deadline.check()
            val vector = embeddingFor(PropositionKey(proposition.contextId.value, proposition.id)) ?: return@mapNotNull null
            if (!isUsableVector(vector, queryEmbedding.size)) {
                skipped++
                return@mapNotNull null
            }
            val score = cosineSimilarity(queryEmbedding, vector)
            if (score >= request.similarityThreshold) SimilarityResult(proposition, score) else null
        }
        deadline.check()
        if (skipped > 0) logger.warn("Scoped vector search skipped {} stored embeddings it could not score", skipped)
        return results.sortedWith(similarityOrder).take(request.topK)
    }

    private fun eligible(spec: ScopedReadSpec, clock: ReadClock): List<Proposition> =
        eligibleRows(spec, clock).toList()

    internal fun eligibleRows(
        spec: ScopedReadSpec,
        clock: ReadClock,
        checkDeadline: () -> Unit = {},
    ): Sequence<Proposition> = rows().asSequence()
        .filter { proposition ->
            checkDeadline()
            admits(spec, clock, proposition)
        }

    /** True when the row is in scope, granted, not excluded, and matches every fragment at the clock's instant. */
    internal fun admits(spec: ScopedReadSpec, clock: ReadClock, proposition: Proposition): Boolean {
        val key = PropositionKey(proposition.contextId.value, proposition.id)
        return key.contextIdValue in spec.scope.contextIdValues &&
            (spec.includedKeys == null || key in spec.includedKeys) &&
            key !in spec.excludedKeys &&
            spec.fragments.all { it.matchesFilters(proposition, clock.asOf) }
    }

    private val keyOrder = compareBy(CodePointOrder) { p: Proposition -> p.contextId.value }.thenBy(CodePointOrder) { it.id }
}

/** Normalises search tokens; see [ScopedReadRules.keywordTokens]. */
internal fun keywordTokens(tokens: List<String>): List<String> = ScopedReadRules.keywordTokens(tokens)

/** How many of the normalised [tokens] appear in [text]; see [ScopedReadRules.keywordHits]. */
internal fun keywordHits(text: String, tokens: List<String>): Int = ScopedReadRules.keywordHits(text, tokens)

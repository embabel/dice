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
package com.embabel.dice.agent

import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.CodePointOrder
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.ScopedReadFailure
import com.embabel.dice.proposition.ScopedReadSession
import java.util.Locale

internal enum class MemoryTier { VECTOR, KEYWORD, RELATED }

internal data class CandidateEnvelope<T>(val key: PropositionKey, val value: T, val entityIds: Set<String>)

internal interface CandidateOperations<T> {
    fun preflight(tiers: Set<MemoryTier>)
    fun count(): Long
    fun list(limit: Int): List<CandidateEnvelope<T>>
    fun vector(query: String, limit: Int): List<CandidateEnvelope<T>>
    fun keyword(tokens: List<String>, limit: Int): List<CandidateEnvelope<T>>
    fun related(entityIds: Set<String>, limit: Int): List<CandidateEnvelope<T>>
}

internal data class MemorySelection<T>(val entries: List<CandidateEnvelope<T>>, val boundedPool: Boolean)

/**
 * Global reciprocal rank fusion over bounded eligible tier pools. For output L, each tier
 * probes C+1 rows, where C=min(400,max(32,4*L)), and contributes only its first C unique
 * rows with weight 1/([MemoryRanking.RRF_K]+rank). Overflow marks a bounded pool, not exact
 * fusion over all authorized rows. No per-context quota applies. Operations own eligibility
 * and exclusions.
 */
internal class MemorySelectionEngine<T>(
    private val operations: CandidateOperations<T>,
    private val tieOrder: Comparator<CandidateEnvelope<T>>,
    tiers: Set<MemoryTier>,
) {
    private val tiers = tiers.toSet()

    fun select(query: String, limit: Int): MemorySelection<T> {
        if (limit < 0 || limit == Int.MAX_VALUE) throw ScopedReadException(ScopedReadFailure.INVALID_SCOPED_READ)
        if (limit == 0) return MemorySelection(emptyList(), false)
        operations.preflight(if (query.isBlank()) emptySet() else tiers)
        val capacity = candidateCapacity(limit)
        var bounded = false
        fun capture(rows: List<CandidateEnvelope<T>>): List<CandidateEnvelope<T>> {
            if (rows.size > capacity + 1 || rows.any {
                    it.key.contextIdValue.isBlank() || it.key.propositionId.isBlank()
                }
            ) throw ScopedReadException(ScopedReadFailure.SCOPED_READ_RESULT_INVALID)
            val unique = rows.distinctBy { it.key }
            bounded = bounded || unique.size > capacity
            return unique.take(capacity)
        }
        if (query.isBlank()) {
            return MemorySelection(capture(operations.list(capacity + 1)).take(limit), bounded)
        }
        val hits = LinkedHashMap<PropositionKey, Fused<T>>()
        fun fuse(rows: List<CandidateEnvelope<T>>, tier: MemoryTier) {
            capture(rows).forEachIndexed { index, candidate ->
                val hit = hits.getOrPut(candidate.key) { Fused(candidate, tier.ordinal) }
                hit.score += 1.0 / (MemoryRanking.RRF_K + index + 1)
            }
        }
        if (MemoryTier.VECTOR in tiers) fuse(operations.vector(query, capacity + 1), MemoryTier.VECTOR)
        if (MemoryTier.KEYWORD in tiers) {
            fuse(operations.keyword(MemoryRanking.tokenize(query), capacity + 1), MemoryTier.KEYWORD)
        }
        if (MemoryTier.RELATED in tiers && hits.size < limit) {
            val seeds = hits.values.flatMap { it.candidate.entityIds }.filter { it.isNotBlank() }
                .distinct().sortedWith(CodePointOrder).take(MemoryRanking.MAX_SCOPED_RELATED_SEEDS).toSet()
            if (seeds.isNotEmpty()) fuse(operations.related(seeds, capacity + 1), MemoryTier.RELATED)
        }
        val ranked = hits.values.sortedWith(
            compareByDescending<Fused<T>> { it.score }.thenBy { it.firstTier }
                .thenComparator { left, right -> tieOrder.compare(left.candidate, right.candidate) },
        ).take(limit).map { it.candidate }
        return MemorySelection(ranked, bounded)
    }

    fun count(): Long {
        operations.preflight(emptySet())
        val total = operations.count()
        if (total < 0) throw ScopedReadException(ScopedReadFailure.SCOPED_READ_RESULT_INVALID)
        return total
    }

    private class Fused<T>(val candidate: CandidateEnvelope<T>, val firstTier: Int, var score: Double = 0.0)
}

internal fun candidateCapacity(limit: Int): Int = minOf(400L, maxOf(32L, 4L * limit)).toInt()

/** Ranking settings both memory engines share, so the legacy and scoped tools rank alike. */
internal object MemoryRanking {
    /** Reciprocal rank fusion damping: a hit at 1-based rank r adds 1 / (RRF_K + r). */
    const val RRF_K = 60
    /** Keyword tokens shorter than this are dropped, so "a" or "of" do not swamp the overlap count. */
    const val MIN_TOKEN_LEN = 3
    /** The scoped related tier reads all seeds in one ANY query, so it can afford more seeds than the legacy per-seed reads. */
    const val MAX_SCOPED_RELATED_SEEDS = 16
    /** Lower-cased, de-duplicated runs of letters and digits at least [MIN_TOKEN_LEN] long. */
    fun tokenize(query: String): List<String> = query.lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= MIN_TOKEN_LEN }.distinct()
}

/** Fails unless the session can run scoped vector search. A zero-row request runs the checks and reads nothing. */
internal fun ScopedReadSession.requireScopedVectors() {
    findSimilarWithScores(TextSimilaritySearchRequest(query = "", similarityThreshold = 0.0, topK = 0))
}

/** True when the session can run scoped vector search; false only when the store says it cannot. Other failures propagate. */
internal fun ScopedReadSession.supportsScopedVectors(): Boolean = try {
    requireScopedVectors()
    true
} catch (e: ScopedReadException) {
    if (e.failure != ScopedReadFailure.SCOPED_VECTOR_UNSUPPORTED) throw e
    false
}

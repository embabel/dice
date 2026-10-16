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
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.ReadOrder
import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.ScopedReadSession
import java.util.Locale

/**
 * Global reciprocal rank fusion over bounded eligible tier pools. For output L, each tier
 * probes C+1 rows, where C=min(400,max(32,4*L)), and contributes only its first C unique
 * rows with weight 1/(60+rank). Overflow marks a bounded pool, not exact fusion over all
 * authorized rows. No per-context quota applies. Operations own eligibility and exclusions.
 */
internal class MemorySelectionEngine<T>(
    private val operations: CandidateOperations<T>,
    private val tieOrder: Comparator<CandidateEnvelope<T>>,
    tiers: Set<MemoryTier>,
) {
    private val tiers = tiers.toSet()

    fun select(query: String, limit: Int): MemorySelection<T> {
        if (limit < 0 || limit == Int.MAX_VALUE) throw ScopedReadException("INVALID_SCOPED_READ")
        if (limit == 0) return MemorySelection(emptyList(), false)
        operations.preflight(if (query.isBlank()) emptySet() else tiers)
        val capacity = candidateCapacity(limit)
        var bounded = false
        fun capture(rows: List<CandidateEnvelope<T>>): List<CandidateEnvelope<T>> {
            if (rows.size > capacity + 1 || rows.any {
                    it.key.contextIdValue.isBlank() || it.key.propositionId.isBlank()
                }
            ) throw ScopedReadException("SCOPED_READ_RESULT_INVALID")
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
                hit.score += 1.0 / (60 + index + 1)
            }
        }
        if (MemoryTier.VECTOR in tiers) fuse(operations.vector(query, capacity + 1), MemoryTier.VECTOR)
        if (MemoryTier.KEYWORD in tiers) {
            val tokens = query.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length >= 3 }.distinct()
            fuse(operations.keyword(tokens, capacity + 1), MemoryTier.KEYWORD)
        }
        if (MemoryTier.RELATED in tiers && hits.size < limit) {
            val seeds = hits.values.flatMap { it.candidate.entityIds }.filter { it.isNotBlank() }
                .distinct().sorted().take(16).toSet()
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
        if (total < 0) throw ScopedReadException("SCOPED_READ_RESULT_INVALID")
        return total
    }

    private class Fused<T>(val candidate: CandidateEnvelope<T>, val firstTier: Int, var score: Double = 0.0)
}

internal fun candidateCapacity(limit: Int): Int = minOf(400L, maxOf(32L, 4L * limit)).toInt()

/** Context-only adapter; the bound session keeps every restriction before candidate selection. */
internal class ScopedMemoryRetriever(private val session: ScopedReadSession) {
    private val operations = object : CandidateOperations<Proposition> {
        override fun preflight(tiers: Set<MemoryTier>) {
            if (MemoryTier.VECTOR in tiers) {
                session.findSimilarWithScores(TextSimilaritySearchRequest(query = "", topK = 0, similarityThreshold = 0.0))
            }
        }
        override fun count(): Long = session.count()
        override fun list(limit: Int) = envelopes(session.query(ReadOrder.EFFECTIVE_CONFIDENCE_DESC, limit))
        override fun vector(query: String, limit: Int) = envelopes(
            session.findSimilarWithScores(
                TextSimilaritySearchRequest(query = query, topK = limit, similarityThreshold = 0.0),
            ).map { it.match },
        )
        override fun keyword(tokens: List<String>, limit: Int) = envelopes(session.keywordOverlap(tokens, limit))
        override fun related(entityIds: Set<String>, limit: Int) = envelopes(
            session.restrict(PropositionQuery(anyEntityIds = entityIds.sorted()))
                .query(ReadOrder.EFFECTIVE_CONFIDENCE_DESC, limit),
        )
    }
    private val engine = MemorySelectionEngine(
        operations,
        compareBy<CandidateEnvelope<Proposition>> { it.key.contextIdValue }.thenBy { it.key.propositionId },
        MemoryTier.entries.toSet(),
    )

    fun select(query: String, limit: Int): MemorySelection<Proposition> {
        if (limit < 0 || limit > session.limits.maxResults ||
            candidateCapacity(limit) + 1 > session.limits.maxProbe
        ) throw ScopedReadException("INVALID_SCOPED_READ")
        return engine.select(query, limit)
    }

    fun count(): Long = engine.count()

    private fun envelopes(rows: List<Proposition>): List<CandidateEnvelope<Proposition>> = rows.map { proposition ->
        val key = PropositionKey(proposition.contextId.value, proposition.id)
        if (key.contextIdValue !in session.scope.contextIdValues || key.propositionId.isBlank()) {
            throw ScopedReadException("SCOPED_READ_RESULT_INVALID")
        }
        CandidateEnvelope(key, proposition, proposition.mentions.mapNotNull { it.resolvedId }.toSet())
    }
}

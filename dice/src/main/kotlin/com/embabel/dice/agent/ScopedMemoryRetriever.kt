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
import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.ScopedReadFailure
import com.embabel.dice.proposition.ScopedReadSession
import com.embabel.dice.proposition.propositionKeyOrder
import org.slf4j.LoggerFactory

/** How many memories one call may return and one eager load may hold. */
class MemoryLimits(val maxResults: Int, val maxEager: Int) {
    init {
        require(maxResults >= 0) { "maxResults must not be negative" }
        require(maxEager >= 0) { "maxEager must not be negative" }
    }

    fun withMaxResults(value: Int): MemoryLimits = MemoryLimits(value, maxEager)

    fun withMaxEager(value: Int): MemoryLimits = MemoryLimits(maxResults, value)

    companion object {
        /** The most memories one memory tool call returns when the host sets no cap. */
        const val DEFAULT_MAX_RESULTS = 100

        /** The most memories one eager load holds when the host sets no cap. */
        const val DEFAULT_MAX_EAGER = 100

        @JvmField
        val DEFAULT = MemoryLimits(DEFAULT_MAX_RESULTS, DEFAULT_MAX_EAGER)
    }
}

/** Context-only adapter; the bound session keeps every restriction before candidate selection. */
internal class ScopedMemoryRetriever(private val session: ScopedReadSession, private val limits: MemoryLimits = MemoryLimits.DEFAULT) {
    private val operations = object : CandidateOperations<Proposition> {
        override fun preflight(tiers: Set<MemoryTier>) {
            if (MemoryTier.VECTOR in tiers) session.requireScopedVectors()
        }
        override fun count(): Long = session.count()
        override fun list(limit: Int) = envelopes(session.query(PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC, limit))
        override fun vector(query: String, limit: Int) = envelopes(
            session.findSimilarWithScores(
                TextSimilaritySearchRequest(query = query, topK = limit, similarityThreshold = 0.0),
            ).map { it.match },
        )
        override fun keyword(tokens: List<String>, limit: Int) = envelopes(session.keywordOverlap(tokens, limit))
        override fun related(entityIds: Set<String>, limit: Int) = envelopes(
            session.restrict(PropositionQuery(anyEntityIds = entityIds.sorted()))
                .query(PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC, limit),
        )
    }
    private fun engine(tiers: Set<MemoryTier>) = MemorySelectionEngine(
        operations,
        compareBy<CandidateEnvelope<Proposition>, PropositionKey>(propositionKeyOrder) { it.key },
        tiers,
    )

    /**
     * Searches every tier the store can run. A store that says it has no scoped vector search
     * still answers from the keyword and related tiers; any other failure is thrown as is.
     */
    fun select(query: String, limit: Int): MemorySelection<Proposition> {
        if (limit < 0 || limit > limits.maxResults ||
            candidateCapacity(limit) + 1 > session.limits.maxRows
        ) throw ScopedReadException(ScopedReadFailure.INVALID_SCOPED_READ)
        var tiers = MemoryTier.entries.toSet()
        // Listing and empty output read no tier, so they need no capability check.
        if (limit > 0 && query.isNotBlank() && !session.supportsScopedVectors()) {
            logger.debug("Scoped memory search skips the vector tier: the store does not support scoped vector search")
            tiers = tiers - MemoryTier.VECTOR
        }
        return engine(tiers).select(query, limit)
    }

    fun count(): Long = engine(MemoryTier.entries.toSet()).count()

    private fun envelopes(rows: List<Proposition>): List<CandidateEnvelope<Proposition>> = rows.map { proposition ->
        CandidateEnvelope(
            PropositionKey(proposition.contextId.value, proposition.id),
            proposition,
            proposition.mentions.mapNotNull { it.resolvedId }.toSet(),
        )
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(ScopedMemoryRetriever::class.java)
    }
}

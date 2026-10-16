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

import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.ExactVectorBudget
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.ReadClock
import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.ScopedReadFailure
import com.embabel.dice.proposition.ScopedReadSpec
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.math.sqrt

/**
 * Exact vector search over the rows a scoped read allows: count them against the budget,
 * score them, keep the top K, all in one statement.
 */
internal class DrivineScopedVectorSearch(
    private val executor: Neo4jScopedReadExecutor,
    private val embeddingService: EmbeddingService,
) {
    /** The scoped search. Its threshold must be a finite number from zero to one. */
    fun findSimilarWithScores(
        request: TextSimilaritySearchRequest, spec: ScopedReadSpec, clock: ReadClock, budget: ExactVectorBudget,
    ): List<SimilarityResult<Proposition>> {
        require(request.similarityThreshold.isFinite() && request.similarityThreshold in 0.0..1.0) {
            "Similarity threshold must be finite and between zero and one"
        }
        return findSimilarWithScores(request, spec, clock, budget, request.similarityThreshold)
    }

    /**
     * The same search, keeping rows that score at least [minimumScore] and ignoring the request's
     * own threshold. The older filtered search uses it so that a threshold of zero or less still
     * keeps every row, as it always has; [NO_MINIMUM_SCORE] does that.
     */
    fun findSimilarWithScores(
        request: TextSimilaritySearchRequest, spec: ScopedReadSpec, clock: ReadClock, budget: ExactVectorBudget,
        minimumScore: Double,
    ): List<SimilarityResult<Proposition>> {
        require(request.topK >= 0) { "topK must not be negative" }
        if (request.topK == 0) return emptyList()
        // Embed before the read opens: the server's timeout starts with the transaction, so a slow
        // embedder never eats into the database's budget.
        val vector = embeddingService.embed(request.query)
        require(vector.isNotEmpty() && vector.all { it.isFinite() }) { "Invalid query vector" }
        val (statement, parameters) = statement(spec, clock, request, budget, vector.toList(), minimumScore)
        val timeout = minOf(budget.timeout, Duration.ofMillis(Long.MAX_VALUE))
        return executor.read(timeout, ScopedReadFailure.EXACT_VECTOR_DEADLINE_EXCEEDED) { tx ->
            val row = tx.run(statement, parameters).single()
            if (row["overflow"].asBoolean()) throw ScopedReadException(ScopedReadFailure.EXACT_VECTOR_BUDGET_EXCEEDED)
            val skipped = row["skipped"].asLong()
            if (skipped > 0) logger.warn("Scoped vector search skipped {} stored embeddings it could not score", skipped)
            row["results"].asList {
                SimilarityResult(DrivineScopedReadOperations.proposition(it["p"], it["mentions"]), it["score"].asDouble())
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(DrivineScopedVectorSearch::class.java)

        /** A minimum score every row meets, because the statement clamps scores to -1..1. */
        const val NO_MINIMUM_SCORE = -1.0

        /**
         * True when a stored embedding can be scored against the query: a list of finite numbers
         * the same length as the query vector. Anything else, such as a leftover from an older
         * embedding model, is skipped rather than failing the whole search.
         */
        private val USABLE = "coalesce(p.embedding IS :: LIST<INTEGER | FLOAT> NOT NULL AND " +
            "size(p.embedding) = size(${'$'}queryVector) AND " +
            "all(x IN p.embedding WHERE abs(x) <= ${'$'}finiteMax), false)"

        internal fun statement(
            spec: ScopedReadSpec, clock: ReadClock, request: TextSimilaritySearchRequest,
            budget: ExactVectorBudget, vector: List<Float>, minimumScore: Double = request.similarityThreshold,
        ): Pair<String, Map<String, Any?>> {
            val compiled = ScopedReadCypher.compile(spec, clock)
            // An overflowing candidate set scores nothing, so no partial ranking escapes. Every eligible
            // row counts against the budget, but only rows with a usable embedding are scored, and each
            // row's usability is worked out once. Scores are computed in Double, the same way the
            // in-memory store computes them; the query's norm is the same for every row, so it is
            // computed once up front.
            // Score ties break by context, then id, mirroring ScopedReadRules.CODE_POINT_ORDER.
            val statement = """
                CALL {
                    MATCH (p:Proposition) WHERE ${compiled.predicate}
                    RETURN p LIMIT ${'$'}admissionLimit
                }
                WITH collect(p) AS candidates
                WITH candidates, size(candidates) > ${'$'}candidateCap AS overflow
                WITH overflow, [p IN CASE WHEN overflow THEN [] ELSE candidates END | {p: p, usable: $USABLE}] AS checked
                WITH overflow, checked, size([c IN checked WHERE c.p.embedding IS NOT NULL AND NOT c.usable]) AS skipped
                CALL {
                    WITH checked
                    UNWIND checked AS c
                    WITH c.p AS p WHERE c.usable
                    WITH p,
                        reduce(dot = 0.0, i IN range(0, size(${'$'}queryVector) - 1) |
                            dot + toFloat(${'$'}queryVector[i]) * toFloat(p.embedding[i])) AS dot,
                        reduce(norm = 0.0, x IN p.embedding | norm + toFloat(x) * toFloat(x)) AS storedNorm
                    WITH p, dot, ${'$'}queryNorm * sqrt(storedNorm) AS denominator
                    WITH p, CASE WHEN denominator = 0.0 THEN 0.0 ELSE dot / denominator END AS raw
                    WITH p, CASE WHEN raw > 1.0 THEN 1.0 WHEN raw < -1.0 THEN -1.0 ELSE raw END AS score
                    WHERE score >= ${'$'}threshold
                    WITH p, score ORDER BY score DESC, ${'$'}contextRank[p.contextId] ASC, p.id ASC LIMIT ${'$'}topK
                    RETURN collect({p: p, mentions: [(p)-[:HAS_MENTION]->(m:Mention) | m], score: score}) AS results
                }
                RETURN overflow, skipped, results
            """.trimIndent()
            return statement to (compiled.parameters + mapOf(
                "admissionLimit" to budget.maxCandidates.toLong() + 1, "candidateCap" to budget.maxCandidates,
                "queryVector" to vector, "queryNorm" to sqrt(vector.sumOf { it.toDouble() * it.toDouble() }),
                "finiteMax" to Float.MAX_VALUE.toDouble(), "threshold" to minimumScore, "topK" to request.topK,
            ))
        }
    }
}

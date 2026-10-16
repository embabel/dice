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
import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.ExactVectorBudget
import com.embabel.dice.proposition.ExactVectorBudgetExceededException
import com.embabel.dice.proposition.ExactVectorDeadlineExceededException
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.ScopedReadSpec
import org.neo4j.driver.Transaction
import org.neo4j.driver.exceptions.Neo4jException
import java.time.Duration
import java.util.concurrent.TimeoutException

/** Exact native ranking over a finite, membership-eligible set, before bounded hydration. */
internal class DrivineScopedVectorSearch(
    private val executor: Neo4jScopedReadExecutor,
    private val reads: DrivineScopedReadOperations,
    private val embeddingService: EmbeddingService,
) {
    fun findSimilarWithScores(
        request: TextSimilaritySearchRequest, scope: ContextReadScope, spec: ScopedReadSpec, budget: ExactVectorBudget,
    ): List<SimilarityResult<Proposition>> {
        require(request.topK >= 0) { "topK must not be negative" }
        require(request.similarityThreshold.isFinite() && request.similarityThreshold in 0.0..1.0) {
            "Similarity threshold must be finite and between zero and one"
        }
        if (request.topK == 0) return emptyList()
        val started = System.nanoTime()
        fun remaining(): Duration {
            val nanos = budget.timeout.toNanos() - (System.nanoTime() - started)
            if (nanos <= 0) throw ExactVectorDeadlineExceededException()
            return Duration.ofNanos(nanos)
        }
        remaining()
        val vector = embeddingService.embed(request.query)
        remaining()
        require(vector.isNotEmpty() && vector.all { it.isFinite() }) { "Invalid query vector" }
        val (statement, parameters) = statement(scope, spec, request, budget, vector.toList())
        return nativeRead(executor, remaining()) { tx ->
            remaining()
            val row = tx.run(statement, parameters).single()
            remaining()
            if (row["overflow"].asBoolean()) throw ExactVectorBudgetExceededException()
            if (row["invalid"].asBoolean()) throw ScopedReadException("SCOPED_READ_FAILED")
            val selected = row["results"].asList { value ->
                PropositionKey(value["contextId"].asString(), value["id"].asString()) to value["score"].asDouble()
            }
            remaining()
            val hydrated = reads.hydrate(tx, scope, spec, selected.map { it.first })
            remaining()
            hydrated.zip(selected) { proposition, scored -> SimilarityResult(proposition, scored.second) }
        }.also { remaining() }
    }

    companion object {
        /** Preserve driver classification until this fixed public boundary discards backend details. */
        internal fun <T> nativeRead(
            executor: Neo4jScopedReadExecutor, timeout: Duration, operation: (Transaction) -> T,
        ): T = try {
            executor.read(timeout, operation)
        } catch (failure: Exception) {
            if (generateSequence<Throwable>(failure) { it.cause }.any {
                    it is TimeoutException || it is ExactVectorDeadlineExceededException ||
                        (it is Neo4jException && it.code().contains("TransactionTimedOut"))
                }
            ) throw ExactVectorDeadlineExceededException()
            when (failure) {
                is ExactVectorBudgetExceededException, is ScopedReadException -> throw failure
                else -> throw ScopedReadException("SCOPED_READ_FAILED")
            }
        }

        internal fun statement(
            scope: ContextReadScope, spec: ScopedReadSpec, request: TextSimilaritySearchRequest,
            budget: ExactVectorBudget, vector: List<Float>,
        ): Pair<String, Map<String, Any?>> {
            val compiled = ScopedReadCypher.compile(scope, spec)
            // CASE removes the whole candidate set on exhaustion or corruption, before UNWIND can score any row.
            // Ordered scalar reductions preserve resident Double threshold and tie decisions.
            val statement = """
                CALL {
                    MATCH (p:Proposition) WHERE ${compiled.predicate}
                    RETURN p LIMIT ${'$'}admissionLimit
                }
                WITH collect(p) AS candidates
                WITH candidates, size(candidates) > ${'$'}candidateCap AS overflow
                WITH candidates, overflow, CASE WHEN overflow THEN false ELSE
                    any(p IN candidates WHERE p.embedding IS NOT NULL AND
                        CASE WHEN p.embedding IS :: LIST<ANY> THEN
                            size(p.embedding) <> size(${'$'}queryVector) OR
                            any(x IN p.embedding WHERE CASE WHEN x IS :: INTEGER OR x IS :: FLOAT
                                THEN NOT (abs(x) <= ${'$'}finiteMax) ELSE true END)
                        ELSE true END) END AS invalid
                CALL {
                    WITH candidates, overflow, invalid
                    UNWIND CASE WHEN overflow OR invalid THEN [] ELSE candidates END AS p
                    WITH p WHERE p.embedding IS NOT NULL
                    WITH p,
                        reduce(dot = 0.0, i IN range(0, size(${'$'}queryVector) - 1) |
                            dot + toFloat(${'$'}queryVector[i]) * toFloat(p.embedding[i])) AS dot,
                        reduce(norm = 0.0, x IN ${'$'}queryVector | norm + toFloat(x) * toFloat(x)) AS queryNorm,
                        reduce(norm = 0.0, x IN p.embedding | norm + toFloat(x) * toFloat(x)) AS storedNorm
                    WITH p, dot, sqrt(queryNorm) * sqrt(storedNorm) AS denominator
                    WITH p, CASE WHEN denominator = 0.0 THEN 0.0 ELSE dot / denominator END AS raw
                    WITH p, CASE WHEN raw > 1.0 THEN 1.0 WHEN raw < -1.0 THEN -1.0 ELSE raw END AS score
                    WHERE score >= ${'$'}threshold
                    WITH p, score ORDER BY score DESC, p.contextId ASC, p.id ASC LIMIT ${'$'}topK
                    RETURN collect({contextId: p.contextId, id: p.id, score: score}) AS results
                }
                RETURN overflow, invalid, results
            """.trimIndent()
            return statement to (compiled.parameters + mapOf(
                "admissionLimit" to budget.maxCandidates + 1, "candidateCap" to budget.maxCandidates,
                "queryVector" to vector,
                "finiteMax" to Float.MAX_VALUE.toDouble(), "threshold" to request.similarityThreshold, "topK" to request.topK,
            ))
        }
    }
}

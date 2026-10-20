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
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.ContextSetVectorSearchCapable
import com.embabel.dice.proposition.ExactVectorBudget
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionKey
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.ReadClock
import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.ScopedReadFailure
import com.embabel.dice.proposition.ScopedReadSpec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * The scoped vector search rules every store has to agree on, written once and run against each store
 * through a subclass.
 *
 * Some engines score in single precision, so scores are compared with a tolerance, and the only exact
 * ties the suite relies on come from identical vectors.
 */
abstract class AbstractScopedVectorConformanceTest {

    /**
     * A store holding exactly the given rows, each stored with its vector, whose embedder turns the
     * search text [QUERY] into [query]. [embedder] builds an embedding service that does both.
     */
    protected abstract fun store(rows: List<Pair<Proposition, FloatArray>>, query: FloatArray): ContextSetVectorSearchCapable

    /**
     * Whether the store fails a search that would score more eligible rows than its budget allows.
     * A store that cannot tell may turn this off; its results are then only checked for staying inside K.
     */
    protected open val enforcesExactVectorBudget: Boolean = true

    /** An embedder that returns the vector stored for each text, and [query] for the search text. */
    protected fun embedder(rows: List<Pair<Proposition, FloatArray>>, query: FloatArray): EmbeddingService {
        val vectors = rows.associate { (row, vector) -> row.text to vector } + (QUERY to query)
        return object : EmbeddingService {
            override val name = "conformance-vectors"
            override val provider = "test"
            override val pricingModel: PricingModel? = null
            override val dimensions = DIMENSIONS
            override fun embed(text: String): FloatArray = vectors[text] ?: error("No test vector for '$text'")
            override fun embed(texts: List<String>): List<FloatArray> = texts.map(::embed)
        }
    }

    private val asOf = Instant.parse("2026-01-20T00:00:00Z")
    private val old = asOf.minusSeconds(10 * 86400L)
    private val clock = ReadClock(asOf, 0.7)
    private val budget = ExactVectorBudget(100, Duration.ofSeconds(20))
    private val keyOrder = compareBy(ScopedReadRules.CODE_POINT_ORDER) { p: Proposition -> p.contextId.value }
        .thenBy(ScopedReadRules.CODE_POINT_ORDER) { it.id }

    private fun row(id: String, context: String, level: Int = 1) = Proposition(
        id = id, contextId = ContextId(context), text = "text $id in $context", mentions = emptyList(),
        confidence = 0.8, decay = 0.1, level = level, sourceIds = if (level > 0) listOf("source") else emptyList(),
        created = old, contentRevised = old, metadataRevised = old, lastAccessed = old,
    )

    /** A vector along the first axis, tilted toward the second by [tilt]. */
    private fun vector(tilt: Float = 0f) = FloatArray(DIMENSIONS) { if (it == 0) 1f else if (it == 1) tilt else 0f }

    private fun request(k: Int) = TextSimilaritySearchRequest(query = QUERY, topK = k, similarityThreshold = 0.0)

    private fun ids(results: List<SimilarityResult<Proposition>>) = results.map { it.match.id }

    @Test
    fun `equal scores break by context then id in code point order`() {
        // U+FF61 sorts before U+10000 by code point, but after it in UTF-16, where U+10000 is U+D800 U+DC00.
        val tied = listOf(row("x𐀀", "｡"), row("x｡", "｡"), row("c1", "𐀀"), row("c2", "ctx-a"), row("c3", "ctx-a"))
        // A lower score must still come after every tie, whatever its key.
        val lower = row("a-lower", "ctx-a")
        val vectors = store(tied.map { it to vector() } + (lower to vector(1f)), vector())
        val spec = ScopedReadSpec.of(ContextReadScope.ofValues(listOf("𐀀", "ctx-a", "｡")))
        val byKey = tied.sortedWith(keyOrder).map { it.id }
        // The premise: plain UTF-16 order disagrees, or this test would prove nothing.
        assertNotEquals(byKey, tied.sortedWith(compareBy<Proposition> { it.contextId.value }.thenBy { it.id }).map { it.id })
        assertEquals(listOf("c2", "c3", "x｡", "x𐀀", "c1"), byKey)
        val expected = byKey + "a-lower"
        for (k in 1..expected.size + 1) {
            assertEquals(expected.take(k), ids(vectors.findSimilarWithScores(request(k), spec, clock, budget)), "k=$k")
        }
        val scores = vectors.findSimilarWithScores(request(10), spec, clock, budget).map { it.score }
        scores.dropLast(1).forEach { assertEquals(1.0, it, TOLERANCE) }
        assertEquals(ScopedReadRules.cosineSimilarity(vector(), vector(1f)), scores.last(), TOLERANCE)
    }

    @Test
    fun `rows the read does not allow never take a top K slot`() {
        // Each decoy matches the query exactly, so a store that ranked before filtering would return it.
        val rows = listOf(
            row("outside", "C") to vector(), row("excluded", "A") to vector(), row("low-level", "B", level = 0) to vector(),
            row("near", "B") to vector(0.5f), row("far", "A") to vector(1f), row("farther", "A") to vector(2f),
        )
        val vectors = store(rows, vector())
        val spec = ScopedReadSpec.of(ContextReadScope.ofValues(listOf("A", "B")))
            .restrict(PropositionQuery(minLevel = 1))
            .excluding(listOf(PropositionKey("A", "excluded")))
        val expected = listOf("near", "far", "farther")
        for (k in 1..4) {
            val results = vectors.findSimilarWithScores(request(k), spec, clock, budget)
            assertEquals(expected.take(k), ids(results), "k=$k")
            results.forEach { result ->
                val stored = rows.single { it.first.id == result.match.id }.second
                assertEquals(ScopedReadRules.cosineSimilarity(vector(), stored), result.score, TOLERANCE, result.match.id)
            }
        }
        val granted = spec.includingOnly(listOf(PropositionKey("A", "far"), PropositionKey("A", "farther"), PropositionKey("A", "excluded")))
        assertEquals(listOf("far"), ids(vectors.findSimilarWithScores(request(1), granted, clock, budget)))
        assertTrue(vectors.findSimilarWithScores(request(5), spec.includingOnly(emptyList()), clock, budget).isEmpty())
    }

    @Test
    fun `the candidate budget counts only eligible rows`() {
        val eligible = listOf(row("a1", "A") to vector(), row("a2", "A") to vector(0.5f), row("a3", "A") to vector(1f))
        val decoys = (1..5).map { row("outside-$it", "C") to vector() }
        val vectors = store(eligible + decoys, vector())
        val spec = ScopedReadSpec.of(ContextReadScope.ofValues(listOf("A")))
        // Exactly as many candidates as eligible rows is enough, however many rows sit outside the read.
        val exact = ExactVectorBudget(eligible.size, Duration.ofSeconds(20))
        assertEquals(listOf("a1", "a2"), ids(vectors.findSimilarWithScores(request(2), spec, clock, exact)))
        val tight = ExactVectorBudget(eligible.size - 1, Duration.ofSeconds(20))
        if (enforcesExactVectorBudget) {
            for (k in listOf(1, 10)) {
                val failure = assertThrows(ScopedReadException::class.java) {
                    vectors.findSimilarWithScores(request(k), spec, clock, tight)
                }
                assertEquals(ScopedReadFailure.EXACT_VECTOR_BUDGET_EXCEEDED, failure.failure, "k=$k")
            }
        } else {
            val results = vectors.findSimilarWithScores(request(2), spec, clock, tight)
            assertTrue(results.size <= 2)
            assertTrue(results.all { result -> eligible.any { it.first.id == result.match.id } })
        }
    }

    companion object {
        /** The text every search in this suite asks for. */
        const val QUERY = "conformance query"

        /** Vector length used throughout, matching the graph test indexes. */
        const val DIMENSIONS = 16

        private const val TOLERANCE = 1e-6
    }
}

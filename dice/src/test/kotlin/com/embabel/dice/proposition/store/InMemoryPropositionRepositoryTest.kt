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
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.ConcurrentHashMap

class InMemoryPropositionRepositoryTest {

    private val embeddingMap = ConcurrentHashMap<String, FloatArray>()
    private lateinit var embeddingService: EmbeddingService
    private lateinit var repo: InMemoryPropositionRepository

    private val testContext = ContextId("test")

    @BeforeEach
    fun setUp() {
        embeddingMap.clear()
        embeddingService = mock<EmbeddingService>()
        whenever(embeddingService.embed(any<String>())).thenAnswer { invocation ->
            val text = invocation.getArgument<String>(0)
            embeddingMap[text] ?: floatArrayOf(0f, 0f, 0f)
        }
        repo = InMemoryPropositionRepository(embeddingService)
    }

    private fun proposition(
        text: String,
        trustValue: Any? = null,
        confidence: Double = 0.9,
        importance: Double = 0.5,
    ): Proposition {
        val base = Proposition(
            contextId = testContext,
            text = text,
            mentions = emptyList(),
            confidence = confidence,
            importance = importance,
        )
        return if (trustValue == null) base
        else base.withMetadataValue(DiceMetadataKeys.TRUST_SCORE, trustValue)
    }

    private fun setEmbedding(text: String, embedding: FloatArray) {
        embeddingMap[text] = embedding
    }

    @Test
    fun `vector pre-filter excludes low-trust propositions when minTrustScore is set`() {
        // Both propositions are highly similar to the query vector — only trust separates them.
        setEmbedding("query text", floatArrayOf(1f, 0f, 0f))
        setEmbedding("high trust match", floatArrayOf(1f, 0f, 0f))
        setEmbedding("low trust match", floatArrayOf(0.99f, 0.1f, 0f))

        repo.save(proposition("high trust match", trustValue = 0.8))
        repo.save(proposition("low trust match", trustValue = 0.2))

        val results = repo.findSimilarWithScores(
            TextSimilaritySearchRequest(query = "query text", topK = 10, similarityThreshold = 0.5),
            PropositionQuery.forContextId(testContext).withMinTrustScore(0.5),
        )

        val texts = results.map { it.match.text }.toSet()
        assertTrue("high trust match" in texts)
        assertFalse("low trust match" in texts)
    }

    @Test
    fun `vector pre-filter keeps unscored propositions when minTrustScore is set`() {
        setEmbedding("query text", floatArrayOf(1f, 0f, 0f))
        setEmbedding("unscored match", floatArrayOf(1f, 0f, 0f))

        repo.save(proposition("unscored match"))

        val results = repo.findSimilarWithScores(
            TextSimilaritySearchRequest(query = "query text", topK = 10, similarityThreshold = 0.5),
            PropositionQuery.forContextId(testContext).withMinTrustScore(0.5),
        )

        assertEquals(setOf("unscored match"), results.map { it.match.text }.toSet())
    }

    @Test
    fun `vector pre-filter with null statuses does not throw and returns all matching propositions`() {
        // A query with no statuses constraint (null) must treat all statuses as passing,
        // matching the behaviour of PropositionStore#query where null statuses = no filter.
        setEmbedding("query text", floatArrayOf(1f, 0f, 0f))
        setEmbedding("active prop", floatArrayOf(1f, 0f, 0f))

        repo.save(proposition("active prop"))

        // PropositionQuery() has statuses = null — must not throw NullPointerException
        val results = repo.findSimilarWithScores(
            TextSimilaritySearchRequest(query = "query text", topK = 10, similarityThreshold = 0.5),
            PropositionQuery(contextId = testContext),
        )

        assertEquals(1, results.size, "Null-statuses query must not filter out any propositions")
    }

    @Test
    fun `vector pre-filter with empty statuses set does not throw and returns all matching propositions`() {
        // An explicitly empty set must also be treated as no-status-filter (consistent with query()).
        setEmbedding("query text", floatArrayOf(1f, 0f, 0f))
        setEmbedding("any status prop", floatArrayOf(1f, 0f, 0f))

        repo.save(proposition("any status prop"))

        val results = repo.findSimilarWithScores(
            TextSimilaritySearchRequest(query = "query text", topK = 10, similarityThreshold = 0.5),
            PropositionQuery(contextId = testContext, statuses = emptySet()),
        )

        assertEquals(1, results.size, "Empty-statuses query must not filter out any propositions")
    }

    @Test
    fun `vector pre-filter applies minImportance just like query`() {
        // Both propositions match the query vector equally — only importance differs.
        setEmbedding("query text", floatArrayOf(1f, 0f, 0f))
        setEmbedding("important match", floatArrayOf(1f, 0f, 0f))
        setEmbedding("trivial match", floatArrayOf(1f, 0f, 0f))

        repo.save(proposition("important match", importance = 0.9))
        repo.save(proposition("trivial match", importance = 0.1))

        val query = PropositionQuery.forContextId(testContext).withMinImportance(0.5)
        val similar = repo.findSimilarWithScores(
            TextSimilaritySearchRequest(query = "query text", topK = 10, similarityThreshold = 0.5),
            query,
        ).map { it.match.text }.toSet()

        assertEquals(setOf("important match"), similar, "minImportance must drop the low-importance match")
        // Parity: the vector pre-filter and the composable query agree on the candidate set.
        assertEquals(repo.query(query).map { it.text }.toSet(), similar)
    }

    @Test
    fun `vector pre-filter applies minEffectiveConfidence just like query`() {
        setEmbedding("query text", floatArrayOf(1f, 0f, 0f))
        setEmbedding("confident match", floatArrayOf(1f, 0f, 0f))
        setEmbedding("doubtful match", floatArrayOf(1f, 0f, 0f))

        repo.save(proposition("confident match", confidence = 0.9))
        repo.save(proposition("doubtful match", confidence = 0.2))

        val query = PropositionQuery.forContextId(testContext).withMinEffectiveConfidence(0.5)
        val similar = repo.findSimilarWithScores(
            TextSimilaritySearchRequest(query = "query text", topK = 10, similarityThreshold = 0.5),
            query,
        ).map { it.match.text }.toSet()

        assertEquals(setOf("confident match"), similar, "minEffectiveConfidence must drop the low-confidence match")
        assertEquals(repo.query(query).map { it.text }.toSet(), similar)
    }
}

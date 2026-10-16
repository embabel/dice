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

import com.embabel.agent.core.ContextId
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.ExactVectorBudgetExceededException
import com.embabel.dice.proposition.ExactVectorDeadlineExceededException
import com.embabel.dice.proposition.FilteredVectorUnsupportedException
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.matchesFilters
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MemoryRetrieverTest {
    private val context = ContextId("A")
    private val base = PropositionQuery.forContextId(context).withStatus(PropositionStatus.ACTIVE)
        .withMinConfidence(0.5)

    private fun proposition(id: String, vararg entities: String): Proposition = Proposition(
        id = id, text = "jazz $id", contextId = context, confidence = 0.9,
        mentions = entities.map { EntityMention(span = it, type = "Person", resolvedId = it) },
    )

    @Test
    fun `ranked MCP and rendered tool entrypoints propagate every typed vector failure`() {
        listOf(FilteredVectorUnsupportedException(), ExactVectorBudgetExceededException(),
            ExactVectorDeadlineExceededException()).forEach { failure ->
            val repository = mockk<PropositionRepository>(relaxed = true)
            every { repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>()) } throws failure
            val retriever = MemoryRetriever(repository, null, "jazz", emptySet())
            assertSame(failure, assertThrows(failure.javaClass) { retriever.rankedPropositions("jazz", base, 3) })
            assertSame(failure, assertThrows(failure.javaClass) { retriever.search("jazz", base, 3) })
            verify(exactly = 0) { repository.keywordOverlap(any(), any(), any()) }
            verify(exactly = 0) { repository.query(any()) }
            verify(exactly = 0) { repository.count(any<PropositionQuery>()) }
        }
    }

    @Test
    fun `related expansion preserves prior ANY using seed ALL before limit`() {
        val seed = proposition("seed", "alice")
        val permitted = proposition("permitted", "alice", "bob")
        val decoy = proposition("decoy", "charlie")
        val outsider = proposition("outsider", "alice").copy(contextId = ContextId("C"))
        val repository = mockk<PropositionRepository>(relaxed = true)
        every { repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>()) } returns listOf(SimilarityResult(seed, 0.9))
        every { repository.keywordOverlap(any(), any(), any()) } returns emptyList()
        val reads = mutableListOf<PropositionQuery>()
        every { repository.query(any()) } answers {
            val query = firstArg<PropositionQuery>()
            reads += query
            listOf(decoy, outsider, permitted).filter { query.matchesFilters(it) }.take(query.limit ?: 100)
        }
        val retriever = MemoryRetriever(repository, null, "jazz", emptySet())
        val result = retriever.rankedPropositions("jazz", base.withAnyEntity("alice", "bob"), 3)
        assertEquals(setOf("seed", "permitted"), result.map { it.id }.toSet())
        assertEquals(listOf("alice", "bob"), reads.single().anyEntityIds)
        assertEquals(listOf("alice"), reads.single().allEntityIds)
        assertEquals(context, reads.single().contextId)
    }

    @Test
    fun `related expansion represents singleton prior ANY with multiple seeds`() {
        val seed = proposition("seed", "alice", "charlie")
        val repository = mockk<PropositionRepository>(relaxed = true)
        every { repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>()) } returns listOf(SimilarityResult(seed, 0.9))
        every { repository.keywordOverlap(any(), any(), any()) } returns emptyList()
        val reads = mutableListOf<PropositionQuery>()
        every { repository.query(any()) } answers { reads += firstArg<PropositionQuery>(); emptyList() }
        MemoryRetriever(repository, null, "jazz", emptySet())
            .rankedPropositions("jazz", base.withAnyEntity("alice"), 3)
        assertEquals(listOf("alice"), reads.single().allEntityIds)
        assertEquals(listOf("alice", "charlie"), reads.single().anyEntityIds)
    }

    @Test
    fun `related overlapping multi ANY predicates fail before expansion read`() {
        val seed = proposition("seed", "alice", "charlie")
        val repository = mockk<PropositionRepository>(relaxed = true)
        every { repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>()) } returns listOf(SimilarityResult(seed, 0.9))
        every { repository.keywordOverlap(any(), any(), any()) } returns emptyList()
        val retriever = MemoryRetriever(repository, null, "jazz", emptySet())
        assertThrows(IllegalArgumentException::class.java) {
            retriever.rankedPropositions("jazz", base.withAnyEntity("alice", "bob"), 3)
        }
        verify(exactly = 0) { repository.query(any()) }
    }

    @Test
    fun `related expansion accepts guaranteed seed membership without replacing prior ANY`() {
        val seed = proposition("seed", "alice", "charlie")
        val repository = mockk<PropositionRepository>(relaxed = true)
        every { repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>()) } returns listOf(SimilarityResult(seed, 0.9))
        every { repository.keywordOverlap(any(), any(), any()) } returns emptyList()
        val reads = mutableListOf<PropositionQuery>()
        every { repository.query(any()) } answers { reads += firstArg<PropositionQuery>(); emptyList() }
        val query = base.withAnyEntity("alice", "bob").withEntityId("charlie").withAllEntities("dana")
        MemoryRetriever(repository, null, "jazz", emptySet()).rankedPropositions("jazz", query, 3)
        assertEquals(query.copy(orderBy = PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC, limit = 9), reads.single())
    }
}

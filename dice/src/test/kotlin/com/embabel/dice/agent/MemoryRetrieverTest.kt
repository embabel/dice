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

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.ContextId
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.ScopedReadFailure
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
import java.time.Duration
import java.time.Instant

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
        listOf(FilteredVectorUnsupportedException(), ScopedReadException(ScopedReadFailure.EXACT_VECTOR_BUDGET_EXCEEDED),
            ScopedReadException(ScopedReadFailure.EXACT_VECTOR_DEADLINE_EXCEEDED)).forEach { failure ->
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

    /**
     * A store that finds [hits] by vector, nothing by keyword, and answers every query read with
     * the [rows] that really match its filters, in list order, recording each read in [reads].
     */
    private fun store(
        hits: List<Proposition>,
        rows: List<Proposition>,
        reads: MutableList<PropositionQuery> = mutableListOf(),
    ): PropositionRepository {
        val repository = mockk<PropositionRepository>(relaxed = true)
        every { repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>()) } answers {
            val query = secondArg<PropositionQuery>()
            hits.filter { query.matchesFilters(it) }.map { SimilarityResult(it, 0.9) }
        }
        every { repository.keywordOverlap(any(), any(), any()) } returns emptyList()
        every { repository.query(any()) } answers {
            val query = firstArg<PropositionQuery>()
            reads += query
            rows.filter { query.matchesFilters(it) }.take(query.limit ?: Int.MAX_VALUE)
        }
        return repository
    }

    @Test
    fun `E1 a seed overlapping a multi entity ANY narrowing still answers without widening`() {
        val hit = proposition("hit", "alice", "charlie")
        val friend = proposition("friend", "alice", "bob")
        val decoy = proposition("decoy", "charlie")
        val repository = store(hits = listOf(hit), rows = listOf(hit, friend, decoy))

        val ids = MemoryRetriever(repository, null, "jazz", emptySet())
            .rankedPropositions("jazz", base.withAnyEntity("alice", "bob"), 3)
            .map { it.id }
        assertTrue("hit" in ids, "the vector hit survives: $ids")
        assertTrue("friend" in ids, "the related tier still runs: $ids")
        assertFalse("decoy" in ids, "charlie alone is outside the narrowing: $ids")

        val result = Memory.forContext(context).withRepository(repository)
            .narrowedBy { it.withAnyEntity("alice", "bob") }
            .call("""{"query":"jazz"}""")
        assertTrue(result is Tool.Result.Text, "Memory.call answers instead of throwing")
        assertFalse("jazz decoy" in (result as Tool.Result.Text).content)
    }

    @Test
    fun `I1 every expansion read keeps the ANY list, the entity id and every ALL member`() {
        val narrowed = base.withAnyEntity("alice", "bob").withEntityId("dana").withAllEntities("erin")
        val hit = proposition("hit", "alice", "dana", "erin", "charlie")
        val outsider = proposition("outsider", "charlie", "dana", "erin")
        val reads = mutableListOf<PropositionQuery>()
        val ids = MemoryRetriever(store(listOf(hit), listOf(hit, outsider), reads), null, "jazz", emptySet())
            .rankedPropositions("jazz", narrowed, 3)
            .map { it.id }

        assertFalse("outsider" in ids, "no read may drop the ANY narrowing: $ids")
        assertEquals(4, reads.size)
        reads.forEach { read ->
            assertEquals(narrowed.anyEntityIds, read.anyEntityIds)
            assertEquals(narrowed.entityId, read.entityId)
            assertTrue(read.allEntityIds.orEmpty().containsAll(narrowed.allEntityIds.orEmpty()), "$read")
            // Apart from the ALL list, the pinned clock, order and limit, the read is the narrowed query itself.
            assertEquals(
                narrowed,
                read.copy(
                    allEntityIds = narrowed.allEntityIds,
                    effectiveConfidenceAsOf = narrowed.effectiveConfidenceAsOf,
                    orderBy = narrowed.orderBy,
                    limit = narrowed.limit,
                ),
            )
        }
    }

    @Test
    fun `I2 related rows are ranked on the base query's decay clock`() {
        val tenDaysAgo = Instant.now().minus(Duration.ofDays(10))
        val hit = proposition("hit", "alice")
        // At the default clock (now, k = 2.0) "old" has decayed to almost nothing, so "fresh" leads.
        // With no decay, or judged ten days ago, "old" is still the more confident of the two.
        val fresh = proposition("fresh", "alice").copy(confidence = 0.8)
        val old = proposition("old", "alice").copy(
            confidence = 0.95, decay = 1.0, contentRevised = tenDaysAgo, lastAccessed = tenDaysAgo,
        )
        listOf(base.withDecayK(0.0), base.withEffectiveConfidenceAsOf(tenDaysAgo)).forEach { clocked ->
            val reads = mutableListOf<PropositionQuery>()
            val ids = MemoryRetriever(store(listOf(hit), listOf(fresh, old), reads), null, "jazz", emptySet())
                .rankedPropositions("jazz", clocked, 3)
                .map { it.id }
            assertEquals(listOf("hit", "old", "fresh"), ids, "related order follows $clocked")
            // Every expansion read is judged at the instant the merge uses.
            val asOf = reads.map { it.effectiveConfidenceAsOf }.distinct().single()
            assertNotNull(asOf, "expansion reads pin the clock: $reads")
            clocked.effectiveConfidenceAsOf?.let { assertEquals(it, asOf) }
        }
    }

    @Test
    fun `I3 related rows tied on confidence break by code point id, whatever order the reads return them in`() {
        val supplementary = "\uD800\uDC00" // U+10000 sorts before U+FF61 as UTF-16 units, after it by code point
        val halfwidth = "\uFF61"
        val hit = proposition("hit", "bob", "s1", "s2")
        // One read per seed: s1 returns the U+10000 row first, s2 returns the U+FF61 row after it.
        val rows = listOf(proposition(halfwidth, "alice", "s2"), proposition(supplementary, "alice", "s1"))
        val reads = mutableListOf<PropositionQuery>()
        val ids = MemoryRetriever(store(listOf(hit), rows, reads), null, "jazz", emptySet())
            .rankedPropositions("jazz", base.withAnyEntity("alice", "bob"), 3)
            .map { it.id }

        assertEquals(3, reads.size)
        assertEquals(listOf("hit", halfwidth, supplementary), ids)
    }

    @Test
    fun `E2 with no ANY narrowing the related tier is one read over every seed`() {
        val hit = proposition("hit", "alice", "charlie")
        val reads = mutableListOf<PropositionQuery>()
        MemoryRetriever(store(listOf(hit), listOf(hit), reads), null, "jazz", emptySet())
            .rankedPropositions("jazz", base, 3)

        assertEquals(1, reads.size)
        assertEquals(listOf("alice", "charlie"), reads.single().anyEntityIds)
        val asOf = checkNotNull(reads.single().effectiveConfidenceAsOf) { "the read pins the clock: ${reads.single()}" }
        assertEquals(
            base.withAnyEntityIds(listOf("alice", "charlie")).withEffectiveConfidenceAsOf(asOf)
                .orderedByEffectiveConfidence().withLimit(9),
            reads.single(),
        )
    }

    @Test
    fun `E3 per seed reads are capped, merged by id, ranked by confidence and cut at three times the limit`() {
        fun row(id: String, confidence: Double, vararg entities: String) =
            proposition(id, *entities).copy(confidence = confidence)
        // Five candidate seeds; only the first four are read, so r8 (reachable only through c4) never shows.
        val hit = proposition("hit", "alice", "c1", "c2", "c3", "c4")
        val rows = listOf(
            row("r8", 0.995, "bob", "c4"),
            row("r1", 0.99, "alice", "c1"),
            row("r2", 0.98, "alice", "c2"),
            row("r3", 0.97, "bob", "c3"),
            row("r4", 0.96, "alice"),
            row("r5", 0.95, "bob", "c1"),
            row("r6", 0.94, "bob", "c2"),
            row("r7", 0.93, "alice", "c3"),
            hit,
        )
        val reads = mutableListOf<PropositionQuery>()
        // With limit 2 the merge keeps six rows: r1 to r6 once each. Hiding hit and r1 to r5 as
        // already eager leaves exactly r6, which only survives a distinct, ranked, capped merge.
        val eager = setOf("hit", "r1", "r2", "r3", "r4", "r5")
        val ids = MemoryRetriever(store(listOf(hit), rows, reads), null, "jazz", eager)
            .rankedPropositions("jazz", base.withAnyEntity("alice", "bob"), 2)
            .map { it.id }

        assertEquals(4, reads.size)
        assertEquals(listOf("r6"), ids)
    }
}

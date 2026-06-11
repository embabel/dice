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

import com.embabel.agent.api.reference.LlmReference
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.ContextId
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.projection.memory.MemoryProjector
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MemoryTest {

    private lateinit var repository: PropositionRepository
    private lateinit var projector: MemoryProjector
    private val contextId = ContextId("test-context")

    private fun createProposition(text: String, confidence: Double = 0.9, decay: Double = 0.1): Proposition {
        return Proposition(
            contextId = contextId,
            text = text,
            mentions = emptyList(),
            confidence = confidence,
            decay = decay,
        )
    }

    @BeforeEach
    fun setup() {
        repository = mockk(relaxed = true)
        projector = mockk(relaxed = true)
    }

    @Nested
    inner class BuilderTests {

        @Test
        fun `creates memory with defaults`() {
            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            assertNotNull(memory)
            assertEquals("memory", memory.name)
        }

        @Test
        fun `creates memory with string context id`() {
            val memory = Memory.forContext("my-context")
                .withRepository(repository)

            assertNotNull(memory)
        }

        @Test
        fun `creates memory with custom projector`() {
            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withProjector(projector)

            assertNotNull(memory)
        }

        @Test
        fun `creates memory with custom confidence`() {
            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withMinConfidence(0.8)

            assertNotNull(memory)
        }

        @Test
        fun `creates memory with custom limit`() {
            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withDefaultLimit(20)

            assertNotNull(memory)
        }

        @Test
        fun `rejects invalid confidence`() {
            assertThrows(IllegalArgumentException::class.java) {
                Memory.forContext(contextId)
                    .withRepository(repository)
                    .withMinConfidence(1.5)
            }

            assertThrows(IllegalArgumentException::class.java) {
                Memory.forContext(contextId)
                    .withRepository(repository)
                    .withMinConfidence(-0.1)
            }
        }

        @Test
        fun `rejects invalid limit`() {
            assertThrows(IllegalArgumentException::class.java) {
                Memory.forContext(contextId)
                    .withRepository(repository)
                    .withDefaultLimit(0)
            }
        }

        @Test
        fun `creates memory with eager query`() {
            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withEagerQuery { it.orderedByEffectiveConfidence().withLimit(5) }

            assertNotNull(memory)
        }
    }

    @Nested
    inner class ContributionTests {

        @Test
        fun `contribution shows zero memories when empty`() {
            every { repository.query(any()) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
            val contribution = memory.contribution()
            assertTrue(contribution.contains("0 memories available"), contribution)
        }

        @Test
        fun `contribution shows singular for one memory`() {
            every { repository.query(any()) } returns listOf(createProposition("test"))
            val memory = Memory.forContext(contextId)
                .withRepository(repository)
            val contribution = memory.contribution()
            assertTrue(contribution.contains("1 memories available"), contribution)
        }

        @Test
        fun `contribution shows count for multiple memories`() {
            every { repository.query(any()) } returns listOf(
                createProposition("memory 1"),
                createProposition("memory 2"),
                createProposition("memory 3"),
            )

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val contribution = memory.contribution()
            assertTrue(contribution.contains("3 memories available"), contribution)
        }

        @Test
        fun `contribution includes eager memories when configured`() {
            val eagerMemories = listOf(
                createProposition("User likes jazz music"),
                createProposition("User works at Acme Corp"),
            )
            every { repository.query(any()) } returns eagerMemories

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withEagerQuery { it.orderedByEffectiveConfidence().withLimit(2) }

            val contribution = memory.contribution()
            assertTrue(contribution.contains("Key memories"), contribution)
            assertTrue(contribution.contains("1. User likes jazz music"), contribution)
            assertTrue(contribution.contains("2. User works at Acme Corp"), contribution)
        }

        @Test
        fun `contribution omits eager section when no memories match`() {
            every { repository.query(any()) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withEagerQuery { it.orderedByEffectiveConfidence().withLimit(5) }

            val contribution = memory.contribution()
            assertFalse(contribution.contains("Key memories"), contribution)
        }

        @Test
        fun `contribution omits eager section when not configured`() {
            every { repository.query(any()) } returns listOf(createProposition("test"))

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val contribution = memory.contribution()
            assertFalse(contribution.contains("Key memories"), contribution)
        }

        @Test
        fun `description returns brief summary`() {
            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withTopic("music preferences")

            assertEquals("Memories about music preferences", memory.description)
        }
    }

    @Nested
    inner class ToolTests {

        @Test
        fun `is a Tool with correct name and parameters`() {
            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            assertTrue(memory is Tool)
            assertEquals("memory", memory.definition.name)
            val schema = memory.definition.inputSchema
            assertNotNull(schema)
        }
    }

    @Nested
    inner class HybridSearchTests {

        @Test
        fun `returns vector hits tagged by probe`() {
            val props = listOf(
                createProposition("User likes jazz music"),
                createProposition("User prefers acoustic instruments"),
            )
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>())
            } returns props.map { SimilarityResult(it, 0.9) }
            every { repository.query(any()) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val result = memory.call("""{"query": "jazz"}""")
            val text = (result as Tool.Result.Text).content

            assertTrue(text.contains("jazz"), text)
            assertTrue(text.contains("User likes jazz music"), text)
            assertTrue(text.contains("User prefers acoustic instruments"), text)
            assertTrue(text.contains("[vector]"), text)
        }

        @Test
        fun `still accepts the legacy topic parameter`() {
            val props = listOf(createProposition("User likes jazz music"))
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>())
            } returns props.map { SimilarityResult(it, 0.9) }
            every { repository.query(any()) } returns emptyList()

            val memory = Memory.forContext(contextId).withRepository(repository)

            val result = memory.call("""{"topic": "jazz"}""")
            val text = (result as Tool.Result.Text).content
            assertTrue(text.contains("User likes jazz music"), text)
        }

        @Test
        fun `keyword-only hit is tagged keyword and merged in`() {
            // Vector finds nothing; keyword substring finds a proposition.
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>())
            } returns emptyList()
            every { repository.query(any()) } returns listOf(
                createProposition("User bought a guitar amp"),
                createProposition("Unrelated fact about coffee"),
            )

            val memory = Memory.forContext(contextId).withRepository(repository)

            val result = memory.call("""{"query": "guitar"}""")
            val text = (result as Tool.Result.Text).content
            assertTrue(text.contains("[keyword] User bought a guitar amp"), text)
            assertFalse(text.contains("coffee"), text)
        }

        @Test
        fun `proposition found by both probes carries both tags`() {
            val both = createProposition("User loves guitar")
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>())
            } returns listOf(SimilarityResult(both, 0.95))
            every { repository.query(any()) } returns listOf(both)

            val memory = Memory.forContext(contextId).withRepository(repository)

            val result = memory.call("""{"query": "guitar"}""")
            val text = (result as Tool.Result.Text).content
            assertTrue(text.contains("[keyword,vector] User loves guitar"), text)
        }

        @Test
        fun `empty result nudges the LLM to retry`() {
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>())
            } returns emptyList()
            every { repository.query(any()) } returns emptyList()

            val memory = Memory.forContext(contextId).withRepository(repository)

            val result = memory.call("""{"query": "unknown topic"}""")
            val text = (result as Tool.Result.Text).content
            assertTrue(text.contains("No memories matched"), text)
            assertTrue(text.contains("Try rephrasing"), text)
        }

        @Test
        fun `passes correct parameters to repository`() {
            val requestSlot = slot<TextSimilaritySearchRequest>()
            val querySlot = slot<PropositionQuery>()

            every {
                repository.findSimilarWithScores(capture(requestSlot), capture(querySlot))
            } returns emptyList()
            every { repository.query(any()) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withMinConfidence(0.7)

            memory.call("""{"query": "jazz", "limit": 5}""")

            assertEquals("jazz", requestSlot.captured.query)
            assertEquals(5, requestSlot.captured.topK)
            assertEquals(contextId, querySlot.captured.contextId)
            assertEquals(0.7, querySlot.captured.minEffectiveConfidence)
        }

        @Test
        fun `keyword probe matches by term overlap not whole-string substring`() {
            // A phrase query never substring-matches a proposition, but
            // its salient token ("Canva") does.
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>())
            } returns emptyList()
            every { repository.query(any()) } returns listOf(
                createProposition("SimTheory was recently acquired by Canva"),
                createProposition("User prefers tea over juice"),
            )

            val memory = Memory.forContext(contextId).withRepository(repository)

            val result = memory.call("""{"query": "what evidence shows interest in Canva"}""")
            val text = (result as Tool.Result.Text).content
            assertTrue(text.contains("[keyword] SimTheory was recently acquired by Canva"), text)
            assertFalse(text.contains("tea over juice"), text)
        }

        @Test
        fun `expands to propositions about the same entity when direct hits are thin`() {
            val mention = EntityMention(span = "Sushila", type = "Person", resolvedId = "sushila-1")
            val seed = Proposition(
                contextId = contextId,
                text = "Sushila works at Embabel",
                mentions = listOf(mention),
                confidence = 0.9,
            )
            val related = Proposition(
                contextId = contextId,
                text = "Sushila joined the GitHub org",
                mentions = listOf(mention),
                confidence = 0.8,
            )
            // Vector finds the seed. The keyword-candidate query (no
            // entity filter) returns nothing; the expansion query (entity
            // filter set) returns the related proposition.
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>())
            } returns listOf(SimilarityResult(seed, 0.9))
            every { repository.query(any()) } answers {
                val q = firstArg<PropositionQuery>()
                if (q.anyEntityIds != null) listOf(seed, related) else emptyList()
            }

            val memory = Memory.forContext(contextId).withRepository(repository)

            val result = memory.call("""{"query": "Sushila"}""")
            val text = (result as Tool.Result.Text).content
            assertTrue(text.contains("[related] Sushila joined the GitHub org"), text)
        }

        @Test
        fun `surfaces resolved entity ids so the LLM can drill in`() {
            val p = Proposition(
                contextId = contextId,
                text = "Sushila works at Embabel",
                mentions = listOf(
                    EntityMention(span = "Sushila", type = "Person", resolvedId = "sushila-1"),
                    EntityMention(span = "Embabel", type = "Organization", resolvedId = "embabel-1"),
                ),
                confidence = 0.9,
            )
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>())
            } returns listOf(SimilarityResult(p, 0.9))
            every { repository.query(any()) } returns emptyList()

            val memory = Memory.forContext(contextId).withRepository(repository)

            val result = memory.call("""{"query": "Sushila employer"}""")
            val text = (result as Tool.Result.Text).content
            assertTrue(text.contains("entities:"), text)
            assertTrue(text.contains("sushila-1"), text)
            assertTrue(text.contains("embabel-1"), text)
        }

        @Test
        fun `annotates results with provenance when a resolver is wired`() {
            val p = createProposition("Sushila works at Embabel")
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>())
            } returns listOf(SimilarityResult(p, 0.9))
            every { repository.query(any()) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withProvenance { ids -> ids.associateWith { listOf("Contractor agreement email") } }

            val result = memory.call("""{"query": "Sushila employer"}""")
            val text = (result as Tool.Result.Text).content
            assertTrue(text.contains("— source: Contractor agreement email"), text)
        }

        @Test
        fun `RRF floats a consensus hit above a higher-ranked single-probe hit`() {
            val a = createProposition("Alpha unrelated fact")   // vector rank 1 only
            val b = createProposition("Beta canva fact")        // vector rank 2 + keyword rank 1
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>())
            } returns listOf(SimilarityResult(a, 0.9), SimilarityResult(b, 0.8))
            // Keyword candidate scan returns both; only `b` contains the token "canva".
            every { repository.query(any()) } returns listOf(a, b)

            val memory = Memory.forContext(contextId).withRepository(repository)

            val result = memory.call("""{"query": "canva"}""")
            val text = (result as Tool.Result.Text).content
            // `b` (vector rank 2 + keyword rank 1) fuses to a higher RRF score
            // than `a` (vector rank 1 only), so it is rendered first.
            assertTrue(
                text.indexOf("Beta canva fact") < text.indexOf("Alpha unrelated fact"),
                text,
            )
            assertTrue(text.contains("[keyword,vector] Beta canva fact"), text)
        }

        @Test
        fun `RRF ties keep tier order - vector before keyword`() {
            val a = createProposition("alpha")  // found only by vector, rank 1
            val b = createProposition("beta")   // found only by keyword, rank 1
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>())
            } returns listOf(SimilarityResult(a, 0.9))
            every { repository.query(any()) } returns listOf(a, b)  // token "beta" matches only `b`

            val memory = Memory.forContext(contextId).withRepository(repository)

            val result = memory.call("""{"query": "beta"}""")
            val text = (result as Tool.Result.Text).content
            // Equal RRF (both rank 1 in their single tier); stable sort keeps the
            // vector tier — fused first — ahead of the keyword tier. Assert on the
            // tagged bullet lines (the bare query word "beta" also appears in the header).
            assertTrue(text.indexOf("[vector] alpha") < text.indexOf("[keyword] beta"), text)
        }
    }

    @Nested
    inner class ContextIsolationTests {

        @Test
        fun `only searches within configured context`() {
            val querySlot = slot<PropositionQuery>()
            every { repository.query(capture(querySlot)) } returns emptyList()

            val memory = Memory.forContext("isolated-context")
                .withRepository(repository)

            memory.call("{}")

            assertEquals(ContextId("isolated-context"), querySlot.captured.contextId)
        }

        @Test
        fun `different memories have different contexts`() {
            val queries = mutableListOf<PropositionQuery>()
            every { repository.query(capture(queries)) } returns emptyList()

            val memory1 = Memory.forContext("context-1")
                .withRepository(repository)

            val memory2 = Memory.forContext("context-2")
                .withRepository(repository)

            memory1.call("{}")
            memory2.call("{}")

            // Filter to just the ordered queries (from listAll) - description also queries
            val orderedQueries = queries.filter { it.orderBy == PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC }

            assertEquals(ContextId("context-1"), orderedQueries[0].contextId)
            assertEquals(ContextId("context-2"), orderedQueries[1].contextId)
        }
    }

    @Nested
    inner class ToolInterfaceTests {

        @Test
        fun `Memory implements Tool interface`() {
            every { repository.query(any()) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            assertTrue(memory is Tool)
        }

        @Test
        fun `definition has correct name`() {
            every { repository.query(any()) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            assertEquals("memory", memory.definition.name)
        }

        @Test
        fun `definition description contains memory count`() {
            every { repository.query(any()) } returns listOf(
                createProposition("memory 1"),
                createProposition("memory 2"),
            )

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            assertTrue(memory.definition.description.contains("2 memories available"))
        }

        @Test
        fun `call with no params lists all memories`() {
            every { repository.query(any()) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val result = memory.call("{}")
            assertTrue(result is Tool.Result.Text)
            val text = (result as Tool.Result.Text).content
            assertTrue(text.contains("No memories stored yet"))
        }

        @Test
        fun `definition is cached and reused`() {
            every { repository.query(any()) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val definition1 = memory.definition
            val definition2 = memory.definition

            assertSame(definition1, definition2)
        }

        @Test
        fun `can use Memory directly as Tool`() {
            every { repository.query(any()) } returns emptyList()

            val memory: Tool = Memory.forContext(contextId)
                .withRepository(repository)

            // Should work as a Tool
            assertNotNull(memory.definition)
            assertNotNull(memory.definition.name)
            assertNotNull(memory.definition.description)
        }
    }

    @Nested
    inner class NarrowedByTests {

        @Test
        fun `narrowedBy applies entity filter to hybrid search`() {
            val querySlot = slot<PropositionQuery>()
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), capture(querySlot))
            } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .narrowedBy { it.withEntityId("alice-123") }

            memory.call("""{"query": "jazz"}""")

            assertEquals("alice-123", querySlot.captured.entityId)
            assertEquals(contextId, querySlot.captured.contextId)
        }

        @Test
        fun `narrowedBy applies entity filter to listAll`() {
            val queries = mutableListOf<PropositionQuery>()
            every { repository.query(capture(queries)) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .narrowedBy { it.withEntityId("alice-123") }

            memory.call("{}")

            // Filter to the ordered query from listAll (description also queries)
            val listAllQuery = queries.first { it.orderBy == PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC }
            assertEquals("alice-123", listAllQuery.entityId)
            assertEquals(contextId, listAllQuery.contextId)
        }

        @Test
        fun `narrowedBy applies to contribution count query`() {
            val queries = mutableListOf<PropositionQuery>()
            every { repository.query(capture(queries)) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .narrowedBy { it.withEntityId("alice-123") }

            // Accessing contribution triggers the count query
            memory.contribution()

            assertTrue(queries.any { it.entityId == "alice-123" })
        }

        @Test
        fun `narrowedBy applies to eager query`() {
            val queries = mutableListOf<PropositionQuery>()
            every { repository.query(capture(queries)) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .narrowedBy { it.withEntityId("alice-123") }
                .withEagerQuery { it.orderedByEffectiveConfidence().withLimit(5) }

            // Accessing contribution triggers eager loading
            memory.contribution()

            val eagerQuery = queries.first {
                it.orderBy == PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC && it.limit == 5
            }
            assertEquals("alice-123", eagerQuery.entityId)
        }

        @Test
        fun `narrowedBy applies to eager topic search`() {
            val querySlot = slot<PropositionQuery>()
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), capture(querySlot))
            } returns emptyList()
            every { repository.query(any()) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .narrowedBy { it.withEntityId("alice-123") }
                .withTopic("music")
                .withEagerTopicSearch(3)

            // Accessing contribution triggers eager topic search
            memory.contribution()

            assertEquals("alice-123", querySlot.captured.entityId)
        }

        @Test
        fun `narrowedBy supports arbitrary query constraints`() {
            val queries = mutableListOf<PropositionQuery>()
            every { repository.query(capture(queries)) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .narrowedBy { it.withMinLevel(1).withStatus(PropositionStatus.ACTIVE) }

            memory.call("{}")

            val listAllQuery = queries.first { it.orderBy == PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC }
            assertEquals(1, listAllQuery.minLevel)
            assertEquals(setOf(PropositionStatus.ACTIVE), listAllQuery.statuses)
        }

        @Test
        fun `without narrowedBy queries are unscoped beyond context`() {
            val querySlot = slot<PropositionQuery>()
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), capture(querySlot))
            } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            memory.call("""{"query": "jazz"}""")

            assertEquals(contextId, querySlot.captured.contextId)
            assertNull(querySlot.captured.entityId)
            assertNull(querySlot.captured.minLevel)
            // baseQuery now scopes retrieval to {ACTIVE} so STALE never reaches LLM context.
            assertEquals(setOf(PropositionStatus.ACTIVE), querySlot.captured.statuses)
        }
    }

    @Nested
    inner class EagerSearchAboutTests {

        @Test
        fun `contribution includes eager search about memories`() {
            val memories = listOf(
                createProposition("User plays guitar since age 12"),
                createProposition("User is in a band called The Resets"),
            )
            every { repository.query(any()) } returns memories
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>())
            } returns memories.map { SimilarityResult(it, 0.9) }

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withEagerSearchAbout("What music stuff am I into?", 10)

            val contribution = memory.contribution()
            assertTrue(contribution.contains("Key memories"), contribution)
            assertTrue(contribution.contains("User plays guitar since age 12"), contribution)
            assertTrue(contribution.contains("User is in a band called The Resets"), contribution)
        }

        @Test
        fun `eager search about passes correct query text`() {
            val requestSlot = slot<TextSimilaritySearchRequest>()
            every { repository.query(any()) } returns emptyList()
            every {
                repository.findSimilarWithScores(capture(requestSlot), any<PropositionQuery>())
            } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withEagerSearchAbout("Tell me about my hobbies", 10)

            memory.contribution()

            assertEquals("Tell me about my hobbies", requestSlot.captured.query)
        }

        @Test
        fun `eager search about respects limit`() {
            val requestSlot = slot<TextSimilaritySearchRequest>()
            every { repository.query(any()) } returns emptyList()
            every {
                repository.findSimilarWithScores(capture(requestSlot), any<PropositionQuery>())
            } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withEagerSearchAbout("hobbies", 7)

            memory.contribution()

            assertEquals(7, requestSlot.captured.topK)
        }

        @Test
        fun `eager search about respects context id`() {
            val querySlot = slot<PropositionQuery>()
            every { repository.query(any()) } returns emptyList()
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), capture(querySlot))
            } returns emptyList()

            val memory = Memory.forContext("my-context")
                .withRepository(repository)
                .withEagerSearchAbout("hobbies", 10)

            memory.contribution()

            assertEquals(ContextId("my-context"), querySlot.captured.contextId)
        }

        @Test
        fun `eager search about respects narrowedBy`() {
            val querySlot = slot<PropositionQuery>()
            every { repository.query(any()) } returns emptyList()
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), capture(querySlot))
            } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .narrowedBy { it.withEntityId("alice-123") }
                .withEagerSearchAbout("hobbies", 10)

            memory.contribution()

            assertEquals("alice-123", querySlot.captured.entityId)
        }

        @Test
        fun `eager search about deduplicates from subsequent tool calls`() {
            val eagerProp = createProposition("User plays guitar")
            val otherProp = createProposition("User likes jazz")
            every { repository.query(any()) } returns listOf(eagerProp, otherProp)
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>())
            } returns listOf(SimilarityResult(eagerProp, 0.9))

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withEagerSearchAbout("music", 10)

            // Trigger eager loading via contribution
            memory.contribution()

            // listAll should exclude the eagerly loaded proposition
            val result = memory.call("{}")
            val text = (result as Tool.Result.Text).content
            assertFalse(text.contains("User plays guitar"), "Eager prop should be deduped: $text")
            assertTrue(text.contains("User likes jazz"), text)
        }

        @Test
        fun `eager search about combines with eager query`() {
            val aboutProp = createProposition("User plays guitar")
            val queryProp = createProposition("User works at Acme")
            every { repository.query(any()) } returns listOf(aboutProp, queryProp)
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>())
            } returns listOf(SimilarityResult(aboutProp, 0.9))

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withEagerSearchAbout("music", 10)
                .withEagerQuery { it.orderedByEffectiveConfidence().withLimit(5) }

            val contribution = memory.contribution()
            assertTrue(contribution.contains("User plays guitar"), contribution)
            assertTrue(contribution.contains("User works at Acme"), contribution)
        }

        @Test
        fun `rejects invalid limit`() {
            assertThrows(IllegalArgumentException::class.java) {
                Memory.forContext(contextId)
                    .withRepository(repository)
                    .withEagerSearchAbout("hobbies", 0)
            }
        }

        @Test
        fun `omits eager section when no memories match`() {
            every { repository.query(any()) } returns emptyList()
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>())
            } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withEagerSearchAbout("nonexistent topic", 10)

            val contribution = memory.contribution()
            assertFalse(contribution.contains("Key memories"), contribution)
        }
    }

    @Nested
    inner class LlmReferenceTests {

        @Test
        fun `Memory implements LlmReference`() {
            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            assertTrue(memory is LlmReference)
        }

        @Test
        fun `tools returns this memory as a tool`() {
            every { repository.query(any()) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val tools = memory.tools()
            assertEquals(1, tools.size)
            assertSame(memory, tools[0])
        }

        @Test
        fun `notes contains use when guidance`() {
            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withUseWhen("asking about preferences")

            assertTrue(memory.notes().contains("asking about preferences"))
        }

        @Test
        fun `contribution includes reference name and description`() {
            every { repository.query(any()) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val contribution = memory.contribution()
            assertTrue(contribution.contains("Reference: memory"), contribution)
            assertTrue(contribution.contains("Description:"), contribution)
        }
    }

    @Nested
    inner class NarrowedByMultiEntityTests {

        @Test
        fun `narrowedBy with anyEntityIds scopes hybrid search`() {
            val querySlot = slot<PropositionQuery>()
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), capture(querySlot))
            } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .narrowedBy { it.withAnyEntity("alice", "bob") }

            memory.call("""{"query": "jazz"}""")

            assertEquals(listOf("alice", "bob"), querySlot.captured.anyEntityIds)
        }

        @Test
        fun `narrowedBy with allEntityIds scopes listAll`() {
            val queries = mutableListOf<PropositionQuery>()
            every { repository.query(capture(queries)) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .narrowedBy { it.withAllEntities("alice", "bob") }

            memory.call("{}")

            val listAllQuery = queries.first { it.orderBy == PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC }
            assertEquals(listOf("alice", "bob"), listAllQuery.allEntityIds)
        }
    }

}

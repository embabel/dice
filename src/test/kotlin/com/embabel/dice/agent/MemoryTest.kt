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
import com.embabel.dice.projection.memory.MemoryProjection
import com.embabel.dice.projection.memory.MemoryProjector
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
    inner class SearchByTopicTests {

        @Test
        fun `returns formatted results`() {
            val props = listOf(
                createProposition("User likes jazz music"),
                createProposition("User prefers acoustic instruments"),
            )
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>())
            } returns props.map { SimilarityResult(it, 0.9) }

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val result = memory.call("""{"topic": "jazz"}""")
            val text = (result as com.embabel.agent.api.tool.Tool.Result.Text).content

            assertTrue(text.contains("jazz"))
            assertTrue(text.contains("User likes jazz music"))
            assertTrue(text.contains("User prefers acoustic instruments"))
        }

        @Test
        fun `returns empty message when no results`() {
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>())
            } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val result = memory.call("""{"topic": "unknown topic"}""")
            val text = (result as com.embabel.agent.api.tool.Tool.Result.Text).content

            assertTrue(text.contains("No memories found"))
        }

        @Test
        fun `filters by type when specified`() {
            val props = listOf(
                createProposition("User likes jazz music"),
                createProposition("User met Bob yesterday"),
                createProposition("User prefers morning meetings"),
            )
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>())
            } returns props.map { SimilarityResult(it, 0.9) }

            every { projector.project(any()) } returns MemoryProjection(
                procedural = listOf(props[0], props[2]),
                episodic = listOf(props[1]),
            )

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withProjector(projector)

            val result = memory.call("""{"topic": "user", "type": "procedural"}""")
            val text = (result as com.embabel.agent.api.tool.Tool.Result.Text).content

            assertTrue(text.contains("procedural"))
            assertTrue(text.contains("User likes jazz music"))
            assertTrue(text.contains("User prefers morning meetings"))
            assertFalse(text.contains("User met Bob yesterday"))
        }

        @Test
        fun `passes correct parameters to repository`() {
            val requestSlot = slot<TextSimilaritySearchRequest>()
            val querySlot = slot<PropositionQuery>()

            every {
                repository.findSimilarWithScores(capture(requestSlot), capture(querySlot))
            } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withMinConfidence(0.7)

            memory.call("""{"topic": "jazz", "limit": 5}""")

            assertEquals("jazz", requestSlot.captured.query)
            assertEquals(5, requestSlot.captured.topK)
            assertEquals(contextId, querySlot.captured.contextId)
            assertEquals(0.7, querySlot.captured.minEffectiveConfidence)
        }
    }

    @Nested
    inner class SearchByTypeTests {

        @Test
        fun `returns facts for semantic type`() {
            val props = listOf(
                createProposition("Alice works at Acme", confidence = 0.9, decay = 0.1),
                createProposition("User met Bob", confidence = 0.8, decay = 0.6),
                createProposition("Bob is a developer", confidence = 0.85, decay = 0.1),
            )
            every { repository.query(any()) } returns props

            every { projector.project(any()) } returns MemoryProjection(
                semantic = listOf(props[0], props[2]),
                episodic = listOf(props[1]),
            )

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withProjector(projector)

            val result = memory.call("""{"type": "semantic"}""")
            val text = (result as com.embabel.agent.api.tool.Tool.Result.Text).content

            assertTrue(text.contains("Facts"))
            assertTrue(text.contains("Alice works at Acme"))
            assertTrue(text.contains("Bob is a developer"))
            assertFalse(text.contains("User met Bob"))
        }

        @Test
        fun `returns preferences for procedural type`() {
            val props = listOf(
                createProposition("User prefers morning meetings"),
                createProposition("User likes tea"),
            )
            every { repository.query(any()) } returns props

            every { projector.project(any()) } returns MemoryProjection(
                procedural = props,
            )

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withProjector(projector)

            val result = memory.call("""{"type": "procedural"}""")
            val text = (result as com.embabel.agent.api.tool.Tool.Result.Text).content

            assertTrue(text.contains("Preferences"))
            assertTrue(text.contains("User prefers morning meetings"))
            assertTrue(text.contains("User likes tea"))
        }

        @Test
        fun `returns error for invalid type`() {
            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val result = memory.call("""{"type": "invalid"}""")

            assertTrue(result is com.embabel.agent.api.tool.Tool.Result.Error)
        }

        @Test
        fun `returns empty message when no matching type`() {
            every { repository.query(any()) } returns listOf(createProposition("Some fact"))
            every { projector.project(any()) } returns MemoryProjection(
                semantic = listOf(createProposition("Some fact")),
            )

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withProjector(projector)

            val result = memory.call("""{"type": "episodic"}""")
            val text = (result as com.embabel.agent.api.tool.Tool.Result.Text).content

            assertTrue(text.contains("No episodic memories found"))
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
        fun `narrowedBy applies entity filter to searchByTopic`() {
            val querySlot = slot<PropositionQuery>()
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), capture(querySlot))
            } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .narrowedBy { it.withEntityId("alice-123") }

            memory.call("""{"topic": "jazz"}""")

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
        fun `narrowedBy applies entity filter to searchByType`() {
            val queries = mutableListOf<PropositionQuery>()
            every { repository.query(capture(queries)) } returns emptyList()
            every { projector.project(any()) } returns MemoryProjection()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withProjector(projector)
                .narrowedBy { it.withEntityId("alice-123") }

            memory.call("""{"type": "semantic"}""")

            val typeQuery = queries.first { it.orderBy == PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC }
            assertEquals("alice-123", typeQuery.entityId)
            assertEquals(contextId, typeQuery.contextId)
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
            assertEquals(PropositionStatus.ACTIVE, listAllQuery.status)
        }

        @Test
        fun `without narrowedBy queries are unscoped beyond context`() {
            val querySlot = slot<PropositionQuery>()
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), capture(querySlot))
            } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            memory.call("""{"topic": "jazz"}""")

            assertEquals(contextId, querySlot.captured.contextId)
            assertNull(querySlot.captured.entityId)
            assertNull(querySlot.captured.minLevel)
            assertNull(querySlot.captured.status)
        }
    }

    @Nested
    inner class LevelParameterTests {

        @Test
        fun `searchByTopic passes level to query`() {
            val querySlot = slot<PropositionQuery>()
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), capture(querySlot))
            } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            memory.call("""{"topic": "jazz", "level": 1}""")

            assertEquals(1, querySlot.captured.minLevel)
            assertEquals(1, querySlot.captured.maxLevel)
        }

        @Test
        fun `searchByTopic without level leaves levels unset`() {
            val querySlot = slot<PropositionQuery>()
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), capture(querySlot))
            } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            memory.call("""{"topic": "jazz"}""")

            assertNull(querySlot.captured.minLevel)
            assertNull(querySlot.captured.maxLevel)
        }

        @Test
        fun `searchByType passes level to query`() {
            val queries = mutableListOf<PropositionQuery>()
            every { repository.query(capture(queries)) } returns emptyList()
            every { projector.project(any()) } returns MemoryProjection()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withProjector(projector)

            memory.call("""{"type": "semantic", "level": 2}""")

            val typeQuery = queries.first { it.orderBy == PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC }
            assertEquals(2, typeQuery.minLevel)
            assertEquals(2, typeQuery.maxLevel)
        }

        @Test
        fun `level composes with narrowedBy`() {
            val querySlot = slot<PropositionQuery>()
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), capture(querySlot))
            } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .narrowedBy { it.withEntityId("alice") }

            memory.call("""{"topic": "jazz", "level": 1}""")

            assertEquals("alice", querySlot.captured.entityId)
            assertEquals(1, querySlot.captured.minLevel)
            assertEquals(1, querySlot.captured.maxLevel)
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
        fun `narrowedBy with anyEntityIds scopes searchByTopic`() {
            val querySlot = slot<PropositionQuery>()
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), capture(querySlot))
            } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .narrowedBy { it.withAnyEntity("alice", "bob") }

            memory.call("""{"topic": "jazz"}""")

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

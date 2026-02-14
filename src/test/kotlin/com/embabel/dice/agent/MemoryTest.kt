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
import com.embabel.agent.api.tool.progressive.UnfoldingTool
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
    inner class DynamicDescriptionTests {

        @Test
        fun `description shows zero memories when empty`() {
            every { repository.query(any()) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
            assertTrue(memory.description.contains("No memories"), memory.description)
        }

        @Test
        fun `description shows singular for one memory`() {
            every { repository.query(any()) } returns listOf(createProposition("test"))
            val memory = Memory.forContext(contextId)
                .withRepository(repository)
            assertTrue(memory.description.contains("1 stored memory"))
        }

        @Test
        fun `description shows count for multiple memories`() {
            every { repository.query(any()) } returns listOf(
                createProposition("memory 1"),
                createProposition("memory 2"),
                createProposition("memory 3"),
            )

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            assertTrue(memory.description.contains("3 stored memories"))
        }

        @Test
        fun `description includes eager memories when configured`() {
            val eagerMemories = listOf(
                createProposition("User likes jazz music"),
                createProposition("User works at Acme Corp"),
            )
            every { repository.query(any()) } returns eagerMemories

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withEagerQuery { it.orderedByEffectiveConfidence().withLimit(2) }

            val description = memory.description
            assertTrue(description.contains("Key memories:"), description)
            assertTrue(description.contains("1. User likes jazz music"), description)
            assertTrue(description.contains("2. User works at Acme Corp"), description)
        }

        @Test
        fun `description omits eager section when no memories match`() {
            every { repository.query(any()) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withEagerQuery { it.orderedByEffectiveConfidence().withLimit(5) }

            val description = memory.description
            assertFalse(description.contains("Key memories:"), description)
        }

        @Test
        fun `description omits eager section when not configured`() {
            every { repository.query(any()) } returns listOf(createProposition("test"))

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val description = memory.description
            assertFalse(description.contains("Key memories:"), description)
        }
    }

    @Nested
    inner class ToolTests {

        @Test
        fun `returns UnfoldingTool with inner tools`() {
            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val tools = memory.tools()

            assertEquals(1, tools.size)
            assertTrue(tools[0] is UnfoldingTool)

            val unfoldingTool = tools[0] as UnfoldingTool
            assertEquals("memory", unfoldingTool.definition.name)
            assertEquals(4, unfoldingTool.innerTools.size)
            assertTrue(unfoldingTool.innerTools.any { it.definition.name == "searchByTopic" })
            assertTrue(unfoldingTool.innerTools.any { it.definition.name == "searchRecent" })
            assertTrue(unfoldingTool.innerTools.any { it.definition.name == "searchByType" })
            assertTrue(unfoldingTool.innerTools.any { it.definition.name == "drillDown" })
        }

        @Test
        fun `notes contain usage instructions`() {
            val memory = Memory.forContext(contextId)
                .withRepository(repository)
            val notes = memory.notes()
            assertTrue(notes.contains("semantic"))
            assertTrue(notes.contains("procedural"))
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

            val tool = findTool(memory, "searchByTopic")
            val result = tool.call("""{"topic": "jazz"}""")
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

            val tool = findTool(memory, "searchByTopic")
            val result = tool.call("""{"topic": "unknown topic"}""")
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

            val tool = findTool(memory, "searchByTopic")
            val result = tool.call("""{"topic": "user", "type": "procedural"}""")
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

            val tool = findTool(memory, "searchByTopic")
            tool.call("""{"topic": "jazz", "limit": 5}""")

            assertEquals("jazz", requestSlot.captured.query)
            assertEquals(5, requestSlot.captured.topK)
            assertEquals(contextId, querySlot.captured.contextId)
            assertEquals(0.7, querySlot.captured.minEffectiveConfidence)
        }
    }

    @Nested
    inner class SearchRecentTests {

        @Test
        fun `returns formatted results`() {
            val props = listOf(
                createProposition("Just learned about Brahms"),
                createProposition("User mentioned they like symphonies"),
            )
            every { repository.query(any()) } returns props

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val tool = findTool(memory, "searchRecent")
            val result = tool.call("{}")
            val text = (result as com.embabel.agent.api.tool.Tool.Result.Text).content

            assertTrue(text.contains("Recent memories"))
            assertTrue(text.contains("Just learned about Brahms"))
            assertTrue(text.contains("User mentioned they like symphonies"))
        }

        @Test
        fun `returns empty message when no results`() {
            every { repository.query(any()) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val tool = findTool(memory, "searchRecent")
            val result = tool.call("{}")
            val text = (result as com.embabel.agent.api.tool.Tool.Result.Text).content

            assertTrue(text.contains("No recent"))
        }

        @Test
        fun `filters by type when specified`() {
            val props = listOf(
                createProposition("User likes jazz"),
                createProposition("User met Bob"),
                createProposition("User prefers tea"),
            )
            every { repository.query(any()) } returns props

            every { projector.project(any()) } returns MemoryProjection(
                procedural = listOf(props[0], props[2]),
                episodic = listOf(props[1]),
            )

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withProjector(projector)

            val tool = findTool(memory, "searchRecent")
            val result = tool.call("""{"type": "episodic"}""")
            val text = (result as com.embabel.agent.api.tool.Tool.Result.Text).content

            assertTrue(text.contains("episodic"))
            assertTrue(text.contains("User met Bob"))
            assertFalse(text.contains("User likes jazz"))
        }

        @Test
        fun `passes correct query parameters`() {
            val querySlot = slot<PropositionQuery>()
            every { repository.query(capture(querySlot)) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withMinConfidence(0.6)

            val tool = findTool(memory, "searchRecent")
            tool.call("""{"limit": 20}""")

            assertEquals(contextId, querySlot.captured.contextId)
            assertEquals(0.6, querySlot.captured.minEffectiveConfidence)
            assertEquals(PropositionQuery.OrderBy.CREATED_DESC, querySlot.captured.orderBy)
            assertEquals(20, querySlot.captured.limit)
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

            val tool = findTool(memory, "searchByType")
            val result = tool.call("""{"type": "semantic"}""")
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

            val tool = findTool(memory, "searchByType")
            val result = tool.call("""{"type": "procedural"}""")
            val text = (result as com.embabel.agent.api.tool.Tool.Result.Text).content

            assertTrue(text.contains("Preferences"))
            assertTrue(text.contains("User prefers morning meetings"))
            assertTrue(text.contains("User likes tea"))
        }

        @Test
        fun `returns error for missing type`() {
            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val tool = findTool(memory, "searchByType")
            val result = tool.call("{}")

            assertTrue(result is com.embabel.agent.api.tool.Tool.Result.Error)
        }

        @Test
        fun `returns error for invalid type`() {
            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val tool = findTool(memory, "searchByType")
            val result = tool.call("""{"type": "invalid"}""")

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

            val tool = findTool(memory, "searchByType")
            val result = tool.call("""{"type": "episodic"}""")
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

            val tool = findTool(memory, "searchRecent")
            tool.call("{}")

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

            findTool(memory1, "searchRecent").call("{}")
            findTool(memory2, "searchRecent").call("{}")

            // Filter to just the ordered queries (from searchRecent) - description also queries
            val orderedQueries = queries.filter { it.orderBy == PropositionQuery.OrderBy.CREATED_DESC }

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
        fun `definition description matches dynamic description`() {
            every { repository.query(any()) } returns listOf(
                createProposition("memory 1"),
                createProposition("memory 2"),
            )

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            // Both should contain the memory count
            assertTrue(memory.definition.description.contains("2 stored memories"))
            assertTrue(memory.description.contains("2 stored memories"))
        }

        @Test
        fun `call delegates to UnfoldingTool`() {
            every { repository.query(any()) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            // Call with an inner tool
            val result = memory.call("""{"tool": "searchRecent", "input": {}}""")

            // Should route to searchRecent and return a result
            assertTrue(result is Tool.Result.Text)
            val text = (result as Tool.Result.Text).content
            assertTrue(text.contains("searchRecent") || text.contains("No recent"))
        }

        @Test
        fun `tool instance is cached and reused`() {
            every { repository.query(any()) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            // Multiple calls should return the same cached tool
            val tools1 = memory.tools()
            val tools2 = memory.tools()
            val definition1 = memory.definition
            val definition2 = memory.definition

            assertSame(tools1[0], tools2[0])
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

            val tool = findTool(memory, "searchByTopic")
            tool.call("""{"topic": "jazz"}""")

            assertEquals("alice-123", querySlot.captured.entityId)
            assertEquals(contextId, querySlot.captured.contextId)
        }

        @Test
        fun `narrowedBy applies entity filter to searchRecent`() {
            val queries = mutableListOf<PropositionQuery>()
            every { repository.query(capture(queries)) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .narrowedBy { it.withEntityId("alice-123") }

            val tool = findTool(memory, "searchRecent")
            tool.call("{}")

            // Filter to the ordered query from searchRecent (description also queries)
            val recentQuery = queries.first { it.orderBy == PropositionQuery.OrderBy.CREATED_DESC }
            assertEquals("alice-123", recentQuery.entityId)
            assertEquals(contextId, recentQuery.contextId)
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

            val tool = findTool(memory, "searchByType")
            tool.call("""{"type": "semantic"}""")

            val typeQuery = queries.first { it.orderBy == PropositionQuery.OrderBy.EFFECTIVE_CONFIDENCE_DESC }
            assertEquals("alice-123", typeQuery.entityId)
            assertEquals(contextId, typeQuery.contextId)
        }

        @Test
        fun `narrowedBy applies to description count query`() {
            val queries = mutableListOf<PropositionQuery>()
            every { repository.query(capture(queries)) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .narrowedBy { it.withEntityId("alice-123") }

            // Accessing description triggers the count query
            memory.description

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

            // Accessing description triggers eager loading
            memory.description

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

            // Accessing description triggers eager topic search
            memory.description

            assertEquals("alice-123", querySlot.captured.entityId)
        }

        @Test
        fun `narrowedBy supports arbitrary query constraints`() {
            val queries = mutableListOf<PropositionQuery>()
            every { repository.query(capture(queries)) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .narrowedBy { it.withMinLevel(1).withStatus(PropositionStatus.ACTIVE) }

            val tool = findTool(memory, "searchRecent")
            tool.call("{}")

            val recentQuery = queries.first { it.orderBy == PropositionQuery.OrderBy.CREATED_DESC }
            assertEquals(1, recentQuery.minLevel)
            assertEquals(PropositionStatus.ACTIVE, recentQuery.status)
        }

        @Test
        fun `without narrowedBy queries are unscoped beyond context`() {
            val querySlot = slot<PropositionQuery>()
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), capture(querySlot))
            } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val tool = findTool(memory, "searchByTopic")
            tool.call("""{"topic": "jazz"}""")

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

            val tool = findTool(memory, "searchByTopic")
            tool.call("""{"topic": "jazz", "level": 1}""")

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

            val tool = findTool(memory, "searchByTopic")
            tool.call("""{"topic": "jazz"}""")

            assertNull(querySlot.captured.minLevel)
            assertNull(querySlot.captured.maxLevel)
        }

        @Test
        fun `searchRecent passes level to query`() {
            val queries = mutableListOf<PropositionQuery>()
            every { repository.query(capture(queries)) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val tool = findTool(memory, "searchRecent")
            tool.call("""{"level": 0}""")

            val recentQuery = queries.first { it.orderBy == PropositionQuery.OrderBy.CREATED_DESC }
            assertEquals(0, recentQuery.minLevel)
            assertEquals(0, recentQuery.maxLevel)
        }

        @Test
        fun `searchRecent without level leaves levels unset`() {
            val queries = mutableListOf<PropositionQuery>()
            every { repository.query(capture(queries)) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val tool = findTool(memory, "searchRecent")
            tool.call("{}")

            val recentQuery = queries.first { it.orderBy == PropositionQuery.OrderBy.CREATED_DESC }
            assertNull(recentQuery.minLevel)
            assertNull(recentQuery.maxLevel)
        }

        @Test
        fun `searchByType passes level to query`() {
            val queries = mutableListOf<PropositionQuery>()
            every { repository.query(capture(queries)) } returns emptyList()
            every { projector.project(any()) } returns MemoryProjection()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withProjector(projector)

            val tool = findTool(memory, "searchByType")
            tool.call("""{"type": "semantic", "level": 2}""")

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

            val tool = findTool(memory, "searchByTopic")
            tool.call("""{"topic": "jazz", "level": 1}""")

            assertEquals("alice", querySlot.captured.entityId)
            assertEquals(1, querySlot.captured.minLevel)
            assertEquals(1, querySlot.captured.maxLevel)
        }
    }

    @Nested
    inner class DrillDownTests {

        @Test
        fun `drillDown finds sources of an abstraction`() {
            val source1 = createProposition("Alice likes jazz")
            val source2 = createProposition("Alice listens to Coltrane")
            val abstraction = Proposition(
                contextId = contextId,
                text = "Alice is a jazz enthusiast",
                mentions = emptyList(),
                confidence = 0.9,
                level = 1,
                sourceIds = listOf(source1.id, source2.id),
            )

            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>())
            } returns listOf(SimilarityResult(abstraction, 0.95))
            every { repository.findSources(abstraction) } returns listOf(source1, source2)

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val tool = findTool(memory, "drillDown")
            val result = tool.call("""{"memory": "Alice is a jazz enthusiast"}""")
            val text = (result as Tool.Result.Text).content

            assertTrue(text.contains("Alice likes jazz"), text)
            assertTrue(text.contains("Alice listens to Coltrane"), text)
        }

        @Test
        fun `drillDown returns empty message when no abstraction found`() {
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>())
            } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val tool = findTool(memory, "drillDown")
            val result = tool.call("""{"memory": "nonexistent summary"}""")
            val text = (result as Tool.Result.Text).content

            assertTrue(text.contains("No matching abstraction"), text)
        }

        @Test
        fun `drillDown returns empty message for proposition with no sources`() {
            val abstraction = Proposition(
                contextId = contextId,
                text = "A summary",
                mentions = emptyList(),
                confidence = 0.9,
                level = 1,
                sourceIds = listOf("missing-id"),
            )

            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), any<PropositionQuery>())
            } returns listOf(SimilarityResult(abstraction, 0.95))
            every { repository.findSources(abstraction) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val tool = findTool(memory, "drillDown")
            val result = tool.call("""{"memory": "A summary"}""")
            val text = (result as Tool.Result.Text).content

            assertTrue(text.contains("no detailed sources"), text)
        }

        @Test
        fun `drillDown searches with minLevel 1`() {
            val querySlot = slot<PropositionQuery>()
            every {
                repository.findSimilarWithScores(any<TextSimilaritySearchRequest>(), capture(querySlot))
            } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val tool = findTool(memory, "drillDown")
            tool.call("""{"memory": "some summary"}""")

            assertEquals(1, querySlot.captured.minLevel)
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

            val tool = findTool(memory, "searchByTopic")
            tool.call("""{"topic": "jazz"}""")

            assertEquals(listOf("alice", "bob"), querySlot.captured.anyEntityIds)
        }

        @Test
        fun `narrowedBy with allEntityIds scopes searchRecent`() {
            val queries = mutableListOf<PropositionQuery>()
            every { repository.query(capture(queries)) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .narrowedBy { it.withAllEntities("alice", "bob") }

            val tool = findTool(memory, "searchRecent")
            tool.call("{}")

            val recentQuery = queries.first { it.orderBy == PropositionQuery.OrderBy.CREATED_DESC }
            assertEquals(listOf("alice", "bob"), recentQuery.allEntityIds)
        }
    }

    private fun findTool(memory: Memory, name: String): com.embabel.agent.api.tool.Tool {
        val unfoldingTool = memory.tools()[0] as UnfoldingTool
        return unfoldingTool.innerTools.first { it.definition.name == name }
    }
}

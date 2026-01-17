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

import com.embabel.agent.api.tool.MatryoshkaTool
import com.embabel.agent.core.ContextId
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.common.KnowledgeType
import com.embabel.dice.projection.memory.MemoryProjection
import com.embabel.dice.projection.memory.MemoryProjector
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
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

    private fun proposition(text: String, confidence: Double = 0.9, decay: Double = 0.1): Proposition {
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
            assertEquals("Memory", memory.name)
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
    }

    @Nested
    inner class DynamicDescriptionTests {

        @Test
        fun `description shows zero memories when empty`() {
            every { repository.query(any()) } returns emptyList()

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            assertTrue(memory.description.contains("No memories stored yet"))
        }

        @Test
        fun `description shows singular for one memory`() {
            every { repository.query(any()) } returns listOf(proposition("test"))

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            assertTrue(memory.description.contains("1 stored memory"))
        }

        @Test
        fun `description shows count for multiple memories`() {
            every { repository.query(any()) } returns listOf(
                proposition("memory 1"),
                proposition("memory 2"),
                proposition("memory 3"),
            )

            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            assertTrue(memory.description.contains("3 stored memories"))
        }
    }

    @Nested
    inner class ToolTests {

        @Test
        fun `returns MatryoshkaTool with inner tools`() {
            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val tools = memory.tools()

            assertEquals(1, tools.size)
            assertTrue(tools[0] is MatryoshkaTool)

            val matryoshka = tools[0] as MatryoshkaTool
            assertEquals("memory", matryoshka.definition.name)
            assertEquals(3, matryoshka.innerTools.size)
            assertTrue(matryoshka.innerTools.any { it.definition.name == "searchByTopic" })
            assertTrue(matryoshka.innerTools.any { it.definition.name == "searchRecent" })
            assertTrue(matryoshka.innerTools.any { it.definition.name == "searchByType" })
        }

        @Test
        fun `notes contain usage instructions`() {
            val memory = Memory.forContext(contextId)
                .withRepository(repository)

            val notes = memory.notes()

            assertTrue(notes.contains("searchByTopic"))
            assertTrue(notes.contains("searchRecent"))
            assertTrue(notes.contains("searchByType"))
            assertTrue(notes.contains("semantic"))
            assertTrue(notes.contains("procedural"))
        }
    }

    @Nested
    inner class SearchByTopicTests {

        @Test
        fun `returns formatted results`() {
            val props = listOf(
                proposition("User likes jazz music"),
                proposition("User prefers acoustic instruments"),
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
                proposition("User likes jazz music"),
                proposition("User met Bob yesterday"),
                proposition("User prefers morning meetings"),
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
                proposition("Just learned about Brahms"),
                proposition("User mentioned they like symphonies"),
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
                proposition("User likes jazz"),
                proposition("User met Bob"),
                proposition("User prefers tea"),
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
                proposition("Alice works at Acme", confidence = 0.9, decay = 0.1),
                proposition("User met Bob", confidence = 0.8, decay = 0.6),
                proposition("Bob is a developer", confidence = 0.85, decay = 0.1),
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
                proposition("User prefers morning meetings"),
                proposition("User likes tea"),
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
            every { repository.query(any()) } returns listOf(proposition("Some fact"))
            every { projector.project(any()) } returns MemoryProjection(
                semantic = listOf(proposition("Some fact")),
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

    private fun findTool(memory: Memory, name: String): com.embabel.agent.api.tool.Tool {
        val matryoshka = memory.tools()[0] as MatryoshkaTool
        return matryoshka.innerTools.first { it.definition.name == name }
    }
}

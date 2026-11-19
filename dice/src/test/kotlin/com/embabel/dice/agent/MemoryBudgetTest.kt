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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.ContextId
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.projection.memory.support.DefaultMemoryProjector
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Memory with a context budget, against a real in-memory repository. Every fact costs one token
 * per character of its text, so the numbers in each test can be checked by counting.
 */
class MemoryBudgetTest {

    private val contextId = ContextId("budget-context")
    private val longAgo: Instant = Instant.parse("2020-01-01T00:00:00Z")
    private lateinit var repository: InMemoryPropositionRepository

    // Text lengths: lisbon 20, biologist 32, cats 37, tea 28, penicillin 30.
    private val lisbon = fact("lisbon", "User lives in Lisbon", 0.95)
    private val biologist = fact("biologist", "User works as a marine biologist", 0.90)
    private val cats = fact("cats", "User has two cats named Miso and Tofu", 0.85)
    private val tea = fact("tea", "User prefers tea over coffee", 0.80)
    private val penicillin = fact("penicillin", "User is allergic to penicillin", 0.70, pinned = true)

    private fun fact(id: String, text: String, confidence: Double, pinned: Boolean = false) = Proposition(
        id = id,
        contextId = contextId,
        text = text,
        mentions = emptyList(),
        confidence = confidence,
        decay = 0.0,
        pinned = pinned,
        contentRevised = longAgo,
        lastAccessed = longAgo,
    )

    private fun tokens(n: Int): ContextBudget = ContextBudget.ofTokens(n).withEstimator { it.length }

    private fun store(vararg facts: Proposition) = facts.forEach { repository.save(it) }

    /** Memory whose eager block is every fact by confidence. */
    private fun eagerMemory(): Memory = Memory.forContext(contextId)
        .withRepository(repository)
        .withEagerQuery { it.orderedByEffectiveConfidence() }

    private fun callTool(memory: Memory, input: String): String = (memory.call(input) as Tool.Result.Text).content

    private fun lastAccessed(fact: Proposition): Instant = repository.findById(fact.id)!!.lastAccessed

    private fun captureMemoryWarnings(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(Memory::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        return appender.list.filter { it.level == Level.WARN }
    }

    @BeforeEach
    fun setup() {
        repository = InMemoryPropositionRepository()
    }

    @Nested
    inner class NoBudget {

        private val expected = """
            Reference: memory
            Description: Memories about the user & context. 4 memories available.

            Key memories about the user & context:
            1. User lives in Lisbon
            2. User works as a marine biologist
            3. User has two cats named Miso and Tofu
            [1 more retrievable via the memory tool]

            Notes: Use when: whenever you need to recall information about the user & context
        """.trimIndent()

        private fun topThree(): Memory = Memory.forContext(contextId)
            .withRepository(repository)
            .withEagerQuery { it.orderedByEffectiveConfidence().withLimit(3) }

        @Test
        fun `no budget leaves the contribution byte for byte as it was`() {
            store(lisbon, biologist, cats, tea)

            assertEquals(expected, topThree().contribution())
        }

        @Test
        fun `a budget far larger than the facts with no pinned facts gives the same contribution`() {
            store(lisbon, biologist, cats, tea)

            assertEquals(expected, topThree().withContextBudget(tokens(1_000_000)).contribution())
        }

        @Test
        fun `with no budget the selection is the merged list in merge order with no pinned first reordering`() {
            store(lisbon, biologist, penicillin)

            val selection = eagerMemory().eagerSelection()

            assertEquals(listOf(lisbon.id, biologist.id, penicillin.id), selection.selected.map { it.id })
            assertTrue(selection.dropped.isEmpty())
            assertFalse(selection.isOverBudget)
            assertTrue(eagerMemory().contribution().contains("3. User is allergic to penicillin"))
        }
    }

    @Nested
    inner class EagerBlock {

        @Test
        fun `the eager block lists only the selected facts and the tail counts the rest as retrievable`() {
            store(lisbon, biologist, cats, tea)

            val contribution = eagerMemory().withContextBudget(tokens(60)).contribution()

            assertTrue(contribution.contains("1. User lives in Lisbon"), contribution)
            assertTrue(contribution.contains("2. User works as a marine biologist"), contribution)
            assertFalse(contribution.contains(cats.text), contribution)
            assertFalse(contribution.contains(tea.text), contribution)
            assertTrue(contribution.contains("[2 more retrievable via the memory tool]"), contribution)
        }

        @Test
        fun `the eager fill keeps merge order and stops at the first fact that does not fit`() {
            store(lisbon, biologist, cats, tea)

            // lisbon + biologist = 52; cats would make 89, so it stops there, even though tea (80) would fit.
            val selection = eagerMemory().withContextBudget(tokens(85)).eagerSelection()

            assertEquals(listOf(lisbon.id, biologist.id), selection.selected.map { it.id })
            assertEquals(listOf(cats.id, tea.id), selection.dropped.map { it.id })
        }

        @Test
        fun `the cost counts fact text only so the block header does not use the budget`() {
            store(lisbon, biologist)

            val memory = eagerMemory().withContextBudget(tokens(52))
            val contribution = memory.contribution()

            assertTrue(contribution.contains("2. User works as a marine biologist"), contribution)
            assertFalse(contribution.contains("more retrievable"), contribution)
            assertEquals(52, memory.eagerSelection().estimatedTokens)
        }

        @Test
        fun `with a budget pinned facts move to the front`() {
            store(lisbon, biologist, penicillin)

            val contribution = eagerMemory().withContextBudget(tokens(60)).contribution()

            assertTrue(contribution.contains("1. User is allergic to penicillin"), contribution)
            assertTrue(contribution.contains("2. User lives in Lisbon"), contribution)
            assertFalse(contribution.contains(biologist.text), contribution)
        }

        @Test
        fun `a pinned fact over budget is still shown and a warning is logged`() {
            store(lisbon, penicillin)

            lateinit var contribution: String
            val warnings = captureMemoryWarnings {
                contribution = eagerMemory().withContextBudget(tokens(10)).contribution()
            }

            assertTrue(contribution.contains("1. User is allergic to penicillin"), contribution)
            assertFalse(contribution.contains(lisbon.text), contribution)
            assertEquals(1, warnings.size, warnings.toString())
            assertTrue(warnings.single().formattedMessage.contains("context budget"), warnings.toString())
        }

        @Test
        fun `a budget that drops every fact still prints the retrievable line without the key memories list`() {
            store(lisbon, biologist, cats, tea)

            val contribution = eagerMemory().withContextBudget(tokens(10)).contribution()

            assertEquals(
                """
                Reference: memory
                Description: Memories about the user & context. 4 memories available.

                [4 more retrievable via the memory tool]

                Notes: Use when: whenever you need to recall information about the user & context
                """.trimIndent(),
                contribution,
            )
        }
    }

    @Nested
    inner class Touching {

        @Test
        fun `touchAccessed touches only the selected facts`() {
            store(lisbon, biologist, cats, tea)

            eagerMemory().withContextBudget(tokens(60)).contribution()

            assertNotEquals(longAgo, lastAccessed(lisbon))
            assertNotEquals(longAgo, lastAccessed(biologist))
            assertEquals(longAgo, lastAccessed(cats))
            assertEquals(longAgo, lastAccessed(tea))
        }

        @Test
        fun `eagerSelection reports selected dropped and isOverBudget and never touches lastAccessed`() {
            store(lisbon, biologist, cats, tea, penicillin)

            val fits = eagerMemory().withContextBudget(tokens(60)).eagerSelection()
            val over = eagerMemory().withContextBudget(tokens(10)).eagerSelection()

            assertEquals(listOf(penicillin.id, lisbon.id), fits.selected.map { it.id })
            assertEquals(listOf(biologist.id, cats.id, tea.id), fits.dropped.map { it.id })
            assertEquals(50, fits.estimatedTokens)
            assertEquals(60, fits.budgetTokens)
            assertFalse(fits.isOverBudget)

            assertEquals(listOf(penicillin.id), over.selected.map { it.id })
            assertTrue(over.isOverBudget)

            listOf(lisbon, biologist, cats, tea, penicillin).forEach {
                assertEquals(longAgo, lastAccessed(it), it.id)
            }
        }
    }

    @Nested
    inner class ToolResults {

        @Test
        fun `a fact dropped by budget comes back from the memory tool`() {
            store(lisbon, biologist, cats, tea)

            val result = callTool(eagerMemory().withContextBudget(tokens(60)), "{}")

            assertTrue(result.contains(cats.text), result)
            assertFalse(result.contains(lisbon.text), result)
            assertFalse(result.contains(biologist.text), result)
        }

        @Test
        fun `the tool dedupe set is the first selection and stays cached`() {
            store(lisbon, biologist, cats, tea)
            val memory = eagerMemory().withContextBudget(tokens(60))
            memory.call("{}")

            // A new top fact would lead the next selection, but the cached dedupe set is still lisbon and biologist.
            store(fact("oslo", "User grew up in Oslo", 0.99))
            val result = callTool(memory, "{}")

            assertTrue(result.contains("User grew up in Oslo"), result)
            assertFalse(result.contains(lisbon.text), result)
        }

        @Test
        fun `a tool search over budget is trimmed and ends with the left out line`() {
            store(lisbon, biologist, cats, tea)
            val memory = Memory.forContext(contextId).withRepository(repository).withContextBudget(tokens(60))

            val result = callTool(memory, """{"query": "user"}""")

            assertEquals(
                """
                Memories about 'user' (2):
                - [keyword] User lives in Lisbon
                - [keyword] User works as a marine biologist
                [2 more matches left out to stay within the context budget]
                """.trimIndent(),
                result,
            )
        }

        @Test
        fun `a tool list over budget is trimmed and ends with the left out line`() {
            store(lisbon, biologist, cats, tea)
            val memory = Memory.forContext(contextId).withRepository(repository).withContextBudget(tokens(60))

            val result = callTool(memory, "{}")

            assertEquals(
                """
                All memories (2):
                - User lives in Lisbon
                - User works as a marine biologist
                [2 more matches left out to stay within the context budget]
                """.trimIndent(),
                result,
            )
        }

        @Test
        fun `tool lines carry source suffixes the cost does not count`() {
            store(lisbon, biologist)
            val memory = Memory.forContext(contextId)
                .withRepository(repository)
                .withProvenance { ids -> ids.associateWith { listOf("a rather long meeting title about many things") } }
                .withContextBudget(tokens(52))

            val result = callTool(memory, "{}")

            assertTrue(result.contains("User works as a marine biologist — source:"), result)
            assertFalse(result.contains("left out"), result)
        }

        @Test
        fun `with no budget the tool output is not trimmed`() {
            store(lisbon, biologist, cats, tea)

            val result = callTool(Memory.forContext(contextId).withRepository(repository), "{}")

            assertTrue(result.startsWith("All memories (4):"), result)
            assertFalse(result.contains("left out"), result)
        }
    }

    @Nested
    inner class Carrying {

        private val budgeted get() = eagerMemory().withContextBudget(tokens(60))

        @TestFactory
        fun `every with method keeps the budget`(): List<DynamicTest> = listOf<Pair<String, (Memory) -> Memory>>(
            "withTopic" to { it.withTopic("the user") },
            "withUseWhen" to { it.withUseWhen("always") },
            "withProvenance" to { it.withProvenance { ids -> ids.associateWith { emptyList() } } },
            "narrowedBy" to { it.narrowedBy { q -> q } },
            "withProjector" to { it.withProjector(DefaultMemoryProjector.DEFAULT) },
            "withMinConfidence" to { it.withMinConfidence(0.5) },
            "withDefaultLimit" to { it.withDefaultLimit(5) },
            "withEagerQuery" to { it.withEagerQuery { q -> q.orderedByEffectiveConfidence() } },
            "withEagerTopicSearch" to { it.withEagerTopicSearch(3) },
            "withEagerSearchAbout" to {
                it.withEagerSearchAbout(TextSimilaritySearchRequest(query = "x", similarityThreshold = 0.0, topK = 3))
            },
        ).map { (name, apply) ->
            DynamicTest.dynamicTest(name) {
                store(lisbon, biologist, cats, tea)

                val selection = apply(budgeted).eagerSelection()

                assertEquals(60, selection.budgetTokens)
                assertEquals(listOf(lisbon.id, biologist.id), selection.selected.map { it.id })
            }
        }

        @Test
        fun `the budget is written only on a fresh copy and the original is left without one`() {
            store(lisbon, biologist, cats, tea)
            val original = eagerMemory()

            val budgeted = original.withContextBudget(tokens(60))

            assertNotSame(original, budgeted)
            assertEquals(Int.MAX_VALUE, original.eagerSelection().budgetTokens)
            assertEquals(4, original.eagerSelection().selected.size)
            assertEquals(2, budgeted.eagerSelection().selected.size)
        }

        @Test
        fun `equals and a direct copy ignore the budget`() {
            val plain = eagerMemory()
            val budgeted = plain.withContextBudget(tokens(60))

            assertEquals(plain, budgeted)
            assertEquals(Int.MAX_VALUE, budgeted.copy().eagerSelection().budgetTokens)
        }
    }
}

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
import com.embabel.common.ai.model.TokenCountEstimator
import com.embabel.dice.proposition.Proposition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ContextBudgetTest {

    private val contextId = ContextId("budget-test")

    // One token per character keeps every cost in these tests exact.
    private val perChar = TokenCountEstimator<String> { it.length }

    private fun fact(text: String, pinned: Boolean = false): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = emptyList(),
            confidence = 0.9,
            pinned = pinned,
        )

    private fun budget(tokens: Int): ContextBudget = ContextBudget.ofTokens(tokens).withEstimator(perChar)

    @Test
    fun `fills in input order and stops at the first fact that does not fit`() {
        val a = fact("aaaa")       // 4
        val b = fact("bbbbbbbb")   // 8, does not fit after a
        val c = fact("cc")         // 2, would fit but comes after the miss
        val selection = budget(10).select(listOf(a, b, c))

        assertEquals(listOf(a), selection.selected)
        assertEquals(listOf(b, c), selection.dropped)
        assertEquals(4, selection.estimatedTokens)
        assertEquals(10, selection.budgetTokens)
        assertFalse(selection.isOverBudget)
    }

    @Test
    fun `a fact that exactly fills the budget is kept`() {
        val a = fact("aaaa")
        val b = fact("bbbbbb")
        val selection = budget(10).select(listOf(a, b))

        assertEquals(listOf(a, b), selection.selected)
        assertTrue(selection.dropped.isEmpty())
        assertEquals(10, selection.estimatedTokens)
        assertFalse(selection.isOverBudget)
    }

    @Test
    fun `pinned facts come first and are never dropped`() {
        val loose1 = fact("xxx")
        val pinned1 = fact("pppp", pinned = true)
        val loose2 = fact("yyyy")
        val pinned2 = fact("qq", pinned = true)
        val selection = budget(10).select(listOf(loose1, pinned1, loose2, pinned2))

        // Pinned cost 6, loose1 brings it to 9, loose2 would make 13.
        assertEquals(listOf(pinned1, pinned2, loose1), selection.selected)
        assertEquals(listOf(loose2), selection.dropped)
        assertEquals(9, selection.estimatedTokens)
        assertFalse(selection.isOverBudget)
    }

    @Test
    fun `pinned facts alone over budget are all kept and every unpinned fact is dropped`() {
        val pinned1 = fact("pppppp", pinned = true)
        val loose = fact("x")
        val pinned2 = fact("qqqqqq", pinned = true)
        val selection = budget(10).select(listOf(pinned1, loose, pinned2))

        assertEquals(listOf(pinned1, pinned2), selection.selected)
        assertEquals(listOf(loose), selection.dropped)
        assertEquals(12, selection.estimatedTokens)
        assertTrue(selection.isOverBudget)
    }

    @Test
    fun `ranking reorders the unpinned facts only`() {
        val pinnedLong = fact("pppppp", pinned = true)
        val pinnedShort = fact("p", pinned = true)
        val long = fact("lllll")
        val short = fact("s")
        val mid = fact("mmm")
        val shortestFirst = compareBy<Proposition> { it.text.length }
        val selection = budget(11).withRanking(shortestFirst)
            .select(listOf(pinnedLong, long, pinnedShort, mid, short))

        // Pinned stay in input order (7 tokens); then s (8), mmm (11); lllll is dropped.
        assertEquals(listOf(pinnedLong, pinnedShort, short, mid), selection.selected)
        assertEquals(listOf(long), selection.dropped)
        assertEquals(11, selection.estimatedTokens)
    }

    @Test
    fun `empty input selects nothing`() {
        val selection = budget(10).select(emptyList())

        assertTrue(selection.selected.isEmpty())
        assertTrue(selection.dropped.isEmpty())
        assertEquals(0, selection.estimatedTokens)
        assertEquals(10, selection.budgetTokens)
        assertFalse(selection.isOverBudget)
    }

    @Test
    fun `ofTokens rejects a budget of zero or less`() {
        assertThrows<IllegalArgumentException> { ContextBudget.ofTokens(0) }
        assertThrows<IllegalArgumentException> { ContextBudget.ofTokens(-5) }
    }

    @Test
    fun `ofTokens uses the character heuristic and no ranking`() {
        val budget = ContextBudget.ofTokens(3)

        assertEquals(3, budget.tokens)
        assertEquals(null, budget.ranking)
        // About four characters per token: 12 characters cost 3 and fit; 16 do not.
        assertEquals(listOf("twelve chars"), budget.select(listOf(fact("twelve chars"))).selected.map { it.text })
        assertTrue(budget.select(listOf(fact("sixteen chars!!!"))).selected.isEmpty())
    }

    @Test
    fun `a custom estimator is used for every cost`() {
        val seen = mutableListOf<String>()
        val flat = TokenCountEstimator<String> { seen += it; 5 }
        val a = fact("a")
        val b = fact("b", pinned = true)
        val c = fact("c")
        val selection = ContextBudget.ofTokens(10).withEstimator(flat).select(listOf(a, b, c))

        assertEquals(listOf(b, a), selection.selected)
        assertEquals(listOf(c), selection.dropped)
        assertEquals(10, selection.estimatedTokens)
        assertTrue(seen.containsAll(listOf("a", "b", "c")))
    }

    @Test
    fun `with methods return a new budget and leave the original unchanged`() {
        val original = ContextBudget.ofTokens(20)
        val ranking = compareBy<Proposition> { it.text }
        val estimated = original.withEstimator(perChar)
        val ranked = estimated.withRanking(ranking)

        assertSame(perChar, estimated.estimator)
        assertEquals(20, estimated.tokens)
        assertEquals(null, estimated.ranking)
        assertSame(ranking, ranked.ranking)
        assertSame(perChar, ranked.estimator)
        assertEquals(null, original.ranking)
        assertFalse(original.estimator === perChar)
    }
}

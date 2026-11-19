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
package com.embabel.dice;

import com.embabel.dice.agent.BudgetedSelection;
import com.embabel.dice.agent.ContextBudget;
import com.embabel.dice.proposition.Proposition;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that a context budget can be built and used from Java: a lambda counts tokens, a plain
 * Java comparator ranks the unpinned facts, and the selection getters read back what was kept.
 *
 * Contract:
 * E1: ofTokens(50).withEstimator(text -> text.length()) counts one token per character, keeps
 *     facts in input order and stops at the first one that does not fit.
 * E2: a Java Comparator passed to withRanking reorders the unpinned facts; pinned facts stay first.
 * E3: pinned facts that alone exceed the budget are all kept and isOverBudget() is true.
 * E4: ofTokens(0) throws IllegalArgumentException.
 */
class ContextBudgetJavaTest {

    private static Proposition fact(String text, boolean pinned) {
        return Proposition.draft("acme", text, 0.9).withPinned(pinned).toProposition();
    }

    private static final String TWENTY = "a".repeat(20);
    private static final String THIRTY = "b".repeat(30);
    private static final String FIVE = "c".repeat(5);

    private final ContextBudget budget = ContextBudget.ofTokens(50).withEstimator(text -> text.length());

    // E1: twenty and thirty fill the budget exactly, so five is dropped even though it is small.
    @Test
    void lambdaEstimatorFillsInInputOrder() {
        Proposition twenty = fact(TWENTY, false);
        Proposition thirty = fact(THIRTY, false);
        Proposition five = fact(FIVE, false);

        BudgetedSelection selection = budget.select(List.of(twenty, thirty, five));

        assertEquals(List.of(twenty, thirty), selection.getSelected());
        assertEquals(List.of(five), selection.getDropped());
        assertEquals(50, selection.getEstimatedTokens());
        assertEquals(50, selection.getBudgetTokens());
        assertFalse(selection.isOverBudget());
    }

    // E2: shortest-first puts five ahead of thirty; the pinned fact still leads.
    @Test
    void javaComparatorRanksUnpinnedFacts() {
        Proposition pinned = fact(TWENTY, true);
        Proposition thirty = fact(THIRTY, false);
        Proposition five = fact(FIVE, false);
        Comparator<Proposition> shortestFirst = Comparator.comparingInt(p -> p.getText().length());

        BudgetedSelection selection = budget.withRanking(shortestFirst).select(List.of(thirty, five, pinned));

        assertEquals(List.of(pinned, five), selection.getSelected());
        assertEquals(List.of(thirty), selection.getDropped());
        assertEquals(25, selection.getEstimatedTokens());
        assertSame(shortestFirst, budget.withRanking(shortestFirst).getRanking());
        assertNull(budget.getRanking());
    }

    // E3: two pinned facts cost 60 against a budget of 50; both stay and the selection says so.
    @Test
    void pinnedFactsOverBudgetAreKept() {
        Proposition pinned1 = fact(THIRTY, true);
        Proposition pinned2 = fact(THIRTY, true);
        Proposition loose = fact(FIVE, false);

        BudgetedSelection selection = budget.select(List.of(loose, pinned1, pinned2));

        assertEquals(List.of(pinned1, pinned2), selection.getSelected());
        assertEquals(List.of(loose), selection.getDropped());
        assertEquals(60, selection.getEstimatedTokens());
        assertTrue(selection.isOverBudget());
    }

    // E4: a budget must be positive.
    @Test
    void zeroTokensIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ContextBudget.ofTokens(0));
    }
}

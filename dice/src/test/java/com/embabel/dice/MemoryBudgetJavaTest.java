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
import com.embabel.dice.agent.Memory;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that a Memory can be given a context budget from Java and that its eager selection reads
 * back cleanly through the plain Java getters.
 *
 * Contract:
 * E1: withContextBudget takes a ContextBudget built in Java, and a later with... call keeps it.
 * E2: eagerSelection() reports the selected and dropped facts and isOverBudget().
 * E3: pinned facts over the budget are still selected and isOverBudget() is true.
 */
class MemoryBudgetJavaTest {

    private static Proposition fact(String id, String text, double confidence, boolean pinned) {
        return Proposition.draft("acme", text, confidence)
            .withId(id)
            .withDecay(0.0)
            .withPinned(pinned)
            .toProposition();
    }

    private final Proposition twenty = fact("twenty", "a".repeat(20), 0.9, false);
    private final Proposition thirty = fact("thirty", "b".repeat(30), 0.8, false);
    private final Proposition forty = fact("forty", "c".repeat(40), 0.7, false);

    private Memory memoryOver(InMemoryPropositionRepository repository, int tokens) {
        return Memory.forContext("acme")
            .withRepository(repository)
            .withEagerQuery(query -> query.orderedByEffectiveConfidence())
            .withContextBudget(ContextBudget.ofTokens(tokens).withEstimator(text -> text.length()));
    }

    // E1, E2: twenty and thirty fill the 50-token budget; forty is dropped. withTopic keeps the budget.
    @Test
    void budgetConfiguredFromJavaSelectsWhatFits() {
        InMemoryPropositionRepository repository = new InMemoryPropositionRepository();
        List.of(twenty, thirty, forty).forEach(repository::save);

        BudgetedSelection selection = memoryOver(repository, 50).withTopic("the user").eagerSelection();

        assertEquals(List.of("twenty", "thirty"), selection.getSelected().stream().map(Proposition::getId).toList());
        assertEquals(List.of("forty"), selection.getDropped().stream().map(Proposition::getId).toList());
        assertEquals(50, selection.getEstimatedTokens());
        assertEquals(50, selection.getBudgetTokens());
        assertFalse(selection.isOverBudget());
    }

    // E3: a pinned fact alone is over a 5-token budget; it stays, the others go.
    @Test
    void pinnedFactOverBudgetIsKeptAndReported() {
        InMemoryPropositionRepository repository = new InMemoryPropositionRepository();
        Proposition pinned = fact("pinned", "d".repeat(10), 0.6, true);
        List.of(twenty, pinned).forEach(repository::save);

        BudgetedSelection selection = memoryOver(repository, 5).eagerSelection();

        assertEquals(List.of("pinned"), selection.getSelected().stream().map(Proposition::getId).toList());
        assertTrue(selection.isOverBudget());
    }
}

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

import com.embabel.dice.store.ContextReadScope;
import com.embabel.dice.store.ReadBudget;
import com.embabel.dice.store.ReadBudgetExceededException;
import com.embabel.dice.store.ReadBudgetResource;
import com.embabel.dice.store.ReadRefusedException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The read scope and the read budget used from Java: a host builds a scope and a budget, a store
 * makes the budget checks, and a caller catches a refusal.
 */
class ReadScopeValuesJavaInteropTest {

    private static final ContextReadScope SCOPE = ContextReadScope.ofValues("alice-session", "team-handbook");

    @Test
    void aScopeIsBuiltAndReadFromJava() {
        assertEquals(List.of("alice-session", "team-handbook"), List.copyOf(SCOPE.getContextIdValues()));
        assertEquals(SCOPE, ContextReadScope.ofValues(List.of("team-handbook", "alice-session")));
        assertTrue(SCOPE.containsValue("alice-session"));
        assertFalse(SCOPE.containsValue("bob-session"));
        assertThrows(IllegalArgumentException.class, () -> ContextReadScope.ofValues(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ContextReadScope.ofValues(Arrays.asList("alice-session", null)));
        assertThrows(NullPointerException.class, () -> ContextReadScope.ofValues((String) null, "team-handbook"));
        assertThrows(IllegalArgumentException.class,
                () -> ContextReadScope.ofValues("alice-session", (String) null));
    }

    @Test
    void aBudgetIsBuiltWithAChainAndRefusesMoreThanItAllows() {
        ReadBudget budget = ReadBudget.ofRows(200).withMaxVectorCandidates(5_000);

        assertEquals(200, budget.getMaxRows());
        assertEquals(5_000, budget.getMaxVectorCandidates());
        assertTrue(budget.isVectorSearchAllowed());
        budget.requireRows(200);
        budget.requireVectorSearch();
        budget.requireVectorCandidates(5_000);

        ReadBudgetExceededException refusal =
                assertThrows(ReadBudgetExceededException.class, () -> budget.requireRows(201));
        assertEquals(ReadBudgetResource.ROWS, refusal.getResource());
        assertEquals(200, refusal.getAllowed());
        assertEquals(201L, refusal.getRequested());
        assertThrows(IllegalArgumentException.class, () -> ReadBudget.ofRows(Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE - 1, ReadBudget.MAX_CAP);
    }

    @Test
    void aStorePassesTheCountItsDatabaseReturnedAsALong() {
        ReadBudget budget = ReadBudget.ofRows(200).withMaxVectorCandidates(5_000);
        long counted = 4_294_967_301L;

        ReadBudgetExceededException rows =
                assertThrows(ReadBudgetExceededException.class, () -> budget.requireRows(counted));
        ReadBudgetExceededException candidates =
                assertThrows(ReadBudgetExceededException.class, () -> budget.requireVectorCandidates(counted));

        assertEquals(counted, rows.getRequested());
        assertEquals(ReadBudgetResource.VECTOR_CANDIDATES, candidates.getResource());
        assertEquals(counted, candidates.getRequested());
        assertThrows(IllegalArgumentException.class, () -> budget.requireRows(-1L));
    }

    @Test
    void aBudgetRefusalIsCaughtByItsBaseTypeAndIsNotAnArgumentError() {
        ReadRefusedException refusal = assertThrows(ReadRefusedException.class,
                () -> ReadBudget.ofRows(200).requireRows(201));

        assertTrue(refusal instanceof ReadBudgetExceededException);
        assertFalse(IllegalArgumentException.class.isInstance(refusal));
        assertFalse(UnsupportedOperationException.class.isInstance(refusal));
    }

    @Test
    void aBudgetWithNoCandidateCapSaysSimilaritySearchIsNotEnabled() {
        ReadBudget rowsOnly = ReadBudget.ofRows(200);

        assertFalse(rowsOnly.isVectorSearchAllowed());
        ReadBudgetExceededException refusal =
                assertThrows(ReadBudgetExceededException.class, rowsOnly::requireVectorSearch);
        assertEquals(ReadBudgetResource.VECTOR_SEARCH, refusal.getResource());
        assertEquals(0, refusal.getAllowed());
        assertEquals(0L, refusal.getRequested());
        assertTrue(refusal.getMessage().contains("Similarity search is not enabled on this budget"));
        ReadBudgetExceededException counted =
                assertThrows(ReadBudgetExceededException.class, () -> rowsOnly.requireVectorCandidates(0));
        assertEquals(ReadBudgetResource.VECTOR_SEARCH, counted.getResource());
    }
}

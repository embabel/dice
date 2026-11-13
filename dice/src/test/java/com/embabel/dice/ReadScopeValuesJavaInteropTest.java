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
import com.embabel.dice.store.ReadScopeViolationException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The read scope and the read budget used from Java: a host builds both, and a store makes the
 * budget checks and reads the two failures.
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
    void aScopeViolationIsCaughtByTypeAndNamesTheContext() {
        ReadScopeViolationException refusal = assertThrows(ReadScopeViolationException.class, () -> {
            throw new ReadScopeViolationException(SCOPE.getContextIdValues(), "bob-session");
        });

        assertEquals("bob-session", refusal.getContextIdValue());
        assertEquals(SCOPE.getContextIdValues(), refusal.getAllowedContextIdValues());
        assertTrue(refusal instanceof IllegalArgumentException);
    }

    @Test
    void aScopeViolationSurvivesJavaSerialization() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(new ReadScopeViolationException(SCOPE.getContextIdValues(), "bob-session"));
        }

        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            ReadScopeViolationException copy = (ReadScopeViolationException) in.readObject();
            assertEquals("bob-session", copy.getContextIdValue());
            assertEquals(SCOPE.getContextIdValues(), copy.getAllowedContextIdValues());
        }
    }

    @Test
    void aBudgetIsBuiltWithAChainAndRefusesMoreThanItAllows() {
        ReadBudget budget = ReadBudget.ofRows(200).withMaxVectorCandidates(5_000);

        assertEquals(200, budget.getMaxRows());
        assertEquals(5_000, budget.getMaxVectorCandidates());
        budget.requireRows(200);
        budget.requireVectorCandidates(5_000);

        ReadBudgetExceededException refusal =
                assertThrows(ReadBudgetExceededException.class, () -> budget.requireRows(201));
        assertEquals(ReadBudgetResource.ROWS, refusal.getResource());
        assertEquals(200, refusal.getAllowed());
        assertEquals(201, refusal.getRequested());

        ReadBudgetExceededException noSearch = assertThrows(ReadBudgetExceededException.class,
                () -> ReadBudget.ofRows(200).requireVectorCandidates(0));
        assertEquals(ReadBudgetResource.VECTOR_CANDIDATES, noSearch.getResource());
        assertEquals(0, noSearch.getAllowed());
        assertThrows(IllegalArgumentException.class, () -> ReadBudget.ofRows(Integer.MAX_VALUE));
        assertEquals(Integer.MAX_VALUE - 1, ReadBudget.MAX_CAP);
    }
}

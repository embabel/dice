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

import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that the String-id maintenance entry points on MemoryMaintenanceOrchestrator work
 * from Java. They hand the id on to the real maintain call, so a recording fake shows what arrived.
 *
 * Contract:
 * R1: a blank context id is rejected with an IllegalArgumentException before maintain runs.
 * E1: maintainValue(id) calls maintain with that id and an empty list, and returns its result.
 * E2: maintainValue(id, props) passes the given propositions through unchanged.
 */
class MaintenanceStringContextJavaInteropTest {

    private static Proposition proposition(String text) {
        Instant now = Instant.now();
        return Proposition.create(
                "id-" + text,
                "acme",
                text,
                List.of(),
                0.8,
                0.0,
                0.5,
                null,
                List.of(),
                now,
                now,
                now,
                PropositionStatus.ACTIVE
        );
    }

    // E1: the one-argument form forwards the id and an empty list, and returns what maintain returned.
    @Test
    void maintainValueForwardsIdWithEmptyListAndReturnsResult() {
        RecordingMemoryMaintenanceOrchestrator orchestrator = new RecordingMemoryMaintenanceOrchestrator();

        var result = orchestrator.maintainValue("acme");

        assertSame(orchestrator.getResult(), result);
        assertEquals(1, orchestrator.getCalls().size());
        assertEquals("acme", orchestrator.getCalls().get(0).getFirst());
        assertTrue(orchestrator.getCalls().get(0).getSecond().isEmpty());
    }

    // E2: the two-argument form passes the same proposition through to maintain.
    @Test
    void maintainValueWithPropositionsPassesThemThrough() {
        RecordingMemoryMaintenanceOrchestrator orchestrator = new RecordingMemoryMaintenanceOrchestrator();
        Proposition p = proposition("Alice likes tea");

        var result = orchestrator.maintainValue("acme", List.of(p));

        assertSame(orchestrator.getResult(), result);
        assertEquals("acme", orchestrator.getCalls().get(0).getFirst());
        assertEquals(1, orchestrator.getCalls().get(0).getSecond().size());
        assertSame(p, orchestrator.getCalls().get(0).getSecond().get(0));
    }

    // R1: an empty id is refused before maintain is reached, so nothing is recorded.
    @Test
    void maintainValueRejectsBlankContextIdBeforeMaintainRuns() {
        RecordingMemoryMaintenanceOrchestrator orchestrator = new RecordingMemoryMaintenanceOrchestrator();

        assertThrows(IllegalArgumentException.class, () -> orchestrator.maintainValue(""));
        assertTrue(orchestrator.getCalls().isEmpty());
    }
}

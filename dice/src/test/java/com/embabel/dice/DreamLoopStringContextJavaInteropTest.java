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

import com.embabel.dice.projection.memory.DefaultDreamLoopOrchestrator;
import com.embabel.dice.projection.memory.DreamLoopReport;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that the dream loop can be driven from Java with a plain String context id,
 * without building a ContextId by hand.
 *
 * Contract:
 * R1: a blank context id is rejected with an IllegalArgumentException.
 * E1: consolidateNowValue(id) always returns a report for that id, even on an empty store.
 * E2: consolidateValue(id) returns null when the change threshold is not met.
 * I1: an empty store stays empty after a cycle runs.
 */
class DreamLoopStringContextJavaInteropTest {

    private final DefaultDreamLoopOrchestrator orchestrator =
            DefaultDreamLoopOrchestrator.withRepository(new InMemoryPropositionRepository());

    // E1: a forced cycle on an empty store still returns a report for the given id.
    @Test
    void consolidateNowReturnsReportForStringContextId() {
        DreamLoopReport report = orchestrator.consolidateNowValue("acme");

        assertEquals("acme", report.getContextIdValue());
        assertEquals(0, report.getTotalExamined());
    }

    // E2: the threshold-gated trigger skips an empty store and returns null.
    @Test
    void consolidateReturnsNullWhenThresholdNotMet() {
        assertNull(orchestrator.consolidateValue("acme"));
    }

    // R1: a blank id is refused before any cycle runs.
    @Test
    void consolidateNowRejectsBlankStringContextId() {
        assertThrows(IllegalArgumentException.class, () -> orchestrator.consolidateNowValue(" "));
    }

    // R1: the threshold-gated trigger refuses a blank id too.
    @Test
    void consolidateRejectsBlankStringContextId() {
        assertThrows(IllegalArgumentException.class, () -> orchestrator.consolidateValue(""));
    }

    // I1: running a cycle on an empty store leaves it empty.
    @Test
    void consolidateNowLeavesEmptyStoreEmpty() {
        InMemoryPropositionRepository repository = new InMemoryPropositionRepository();
        DefaultDreamLoopOrchestrator fresh = DefaultDreamLoopOrchestrator.withRepository(repository);

        fresh.consolidateNowValue("acme");

        assertTrue(repository.findAll().isEmpty());
    }
}

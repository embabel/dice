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

import com.embabel.dice.projection.memory.CollectorRunResult;
import com.embabel.dice.projection.memory.CollectorRunner;
import com.embabel.dice.projection.memory.DecayCollectorStrategy;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that a Java developer can drive the collector runner with a plain String context id.
 *
 * Contract:
 * R1: a blank context id is rejected with an IllegalArgumentException.
 * E1: runValue(id, true) returns a dry-run result.
 * E2: runValue(id) returns a result that is not a dry run.
 * E3: collectValue(id) returns a result with a blank run id, because nothing is persisted.
 * I1: an empty store stays empty after a run.
 */
class CollectorRunnerStringContextJavaInteropTest {

    private final InMemoryPropositionRepository repository = new InMemoryPropositionRepository();

    private final CollectorRunner runner = CollectorRunner.withRepository(repository)
            .withStrategy(new DecayCollectorStrategy(0.3))
            .build();

    // E1: a dry run with the String id reports that it was a dry run.
    @Test
    void runValueWithDryRunReturnsDryRunResult() {
        CollectorRunResult result = runner.runValue("acme", true);

        assertTrue(result.getDryRun());
    }

    // E2: a run without the flag is a real run, not a dry run.
    @Test
    void runValueWithoutFlagIsNotDryRun() {
        CollectorRunResult result = runner.runValue("acme");

        assertFalse(result.getDryRun());
    }

    // E3: collect with the String id gives a blank run id.
    @Test
    void collectValueReturnsBlankRunId() {
        CollectorRunResult result = runner.collectValue("acme");

        assertTrue(result.getRunId().isBlank());
    }

    // R1: a blank id is refused before any run starts.
    @Test
    void runValueRejectsBlankId() {
        assertThrows(IllegalArgumentException.class, () -> runner.runValue(""));
    }

    // I1: a run on an empty store leaves the store empty.
    @Test
    void runValueLeavesEmptyStoreEmpty() {
        runner.runValue("acme", false);

        assertTrue(repository.findAll().isEmpty());
    }
}

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
import com.embabel.dice.proposition.revision.RevisionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that Java reads whether a revision result is a replay.
 *
 * E1: a merge or reinforcement whose revised proposition equals its original is a replay.
 * E2: a merge that added evidence, and a new proposition, are not replays.
 */
class RevisionReplayJavaInteropTest {

    private final Proposition stored = Proposition.draft("ctx", "Alice knows Bob", 0.9)
            .withId("p-1")
            .withGrounding(List.of("chunk-1"))
            .toProposition();

    @Test
    void e1_aResultThatLeftTheStoredPropositionAsItWasIsAReplay() {
        assertTrue(new RevisionResult.Merged(stored, stored).isReplay());
        assertTrue(new RevisionResult.Reinforced(stored, stored).isReplay());
    }

    @Test
    void e2_aResultThatChangedTheStoreIsNotAReplay() {
        Proposition withMoreEvidence = stored.withGrounding(List.of("chunk-1", "chunk-2"));

        assertFalse(new RevisionResult.Merged(stored, withMoreEvidence).isReplay());
        assertFalse(new RevisionResult.New(stored).isReplay());
    }
}

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

import com.embabel.agent.api.common.Ai;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.revision.LlmPropositionReviser;
import com.embabel.dice.proposition.revision.RevisionResult;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;

/**
 * Revises a proposition from Java against a stored one with the same text.
 *
 * The clauses these tests check:
 * E1: a restatement from a new source returns Merged, adds one to the reinforce count and keeps both grounding ids.
 * E2: a restatement never lowers the stored confidence.
 * I1: a replay of the stored source returns the stored proposition unchanged.
 * I2: a replay of the stored source with a trust scorer returns the stored proposition unchanged.
 */
class PropositionReviserJavaInteropTest {

    private static final String CONTEXT = "acme";
    private static final String TEXT = "Alice lives in Paris";

    private static LlmPropositionReviser reviser() {
        return LlmPropositionReviser.withLlm(mock(LlmOptions.class)).withAi(mock(Ai.class));
    }

    private static Proposition stored(double confidence, String chunkId) {
        return Proposition.draft(CONTEXT, TEXT, confidence).withGrounding(List.of(chunkId)).toProposition();
    }

    private static Proposition incoming(double confidence, String chunkId) {
        return Proposition.draft(CONTEXT, TEXT, confidence).withGrounding(List.of(chunkId)).toProposition();
    }

    @Test
    void restatementFromANewSourceCountsOnce() {
        InMemoryPropositionRepository repository = new InMemoryPropositionRepository();
        repository.save(stored(0.8, "chunk-1"));

        RevisionResult result = reviser().revise(incoming(0.8, "chunk-2"), repository);

        RevisionResult.Merged merged = assertInstanceOf(RevisionResult.Merged.class, result);
        assertEquals(merged.getOriginal().getReinforceCount() + 1, merged.getRevised().getReinforceCount());
        assertEquals(Set.of("chunk-1", "chunk-2"), Set.copyOf(merged.getRevised().getGrounding()));
    }

    @Test
    void restatementNeverLowersConfidence() {
        InMemoryPropositionRepository repository = new InMemoryPropositionRepository();
        repository.save(stored(1.0, "chunk-1"));

        RevisionResult result = reviser().revise(incoming(0.5, "chunk-2"), repository);

        RevisionResult.Merged merged = assertInstanceOf(RevisionResult.Merged.class, result);
        assertEquals(1.0, merged.getRevised().getConfidence(), 0.0);
    }

    @Test
    void replayOfTheSameSourceChangesNothing() {
        InMemoryPropositionRepository repository = new InMemoryPropositionRepository();
        repository.save(stored(0.8, "chunk-1"));

        RevisionResult result = reviser().revise(incoming(0.8, "chunk-1"), repository);

        RevisionResult.Merged merged = assertInstanceOf(RevisionResult.Merged.class, result);
        assertEquals(merged.getOriginal(), merged.getRevised());
    }

    @Test
    void replayWithATrustScorerChangesNothing() {
        InMemoryPropositionRepository repository = new InMemoryPropositionRepository();
        repository.save(stored(0.8, "chunk-1"));

        LlmPropositionReviser scored = reviser().withTrustScorer((proposition, tier, conflict) -> 0.42);
        RevisionResult result = scored.revise(incoming(0.8, "chunk-1"), repository);

        RevisionResult.Merged merged = assertInstanceOf(RevisionResult.Merged.class, result);
        assertEquals(merged.getOriginal(), merged.getRevised());
    }
}

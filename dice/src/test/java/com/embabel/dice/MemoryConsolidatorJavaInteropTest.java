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

import com.embabel.dice.proposition.EntityMention;
import com.embabel.dice.proposition.MentionRole;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.projection.memory.ConsolidationResult;
import com.embabel.dice.projection.memory.DefaultMemoryConsolidator;
import com.embabel.dice.provenance.ProvenanceEntry;
import com.embabel.dice.provenance.UriLocator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Builds and runs the default memory consolidator from Java.
 */
class MemoryConsolidatorJavaInteropTest {

    // The clauses these tests check:
    // 1. R1: a reinforcement boost below 0.0, above 1.0, NaN or infinite is rejected with a message
    //    that names the setting and the value. 0.0 and 1.0 are accepted.
    // 2. R2: a negative or NaN promotion or similarity threshold is rejected. 0.0 is accepted.
    // 3. R3: a threshold above 1.0 is accepted. It means the matching or promotion never happens.
    // 4. E1: a session proposition that matches a stored one is folded into it. Confidence rises by
    //    the boost, the reinforce count goes up by one, and the grounding and provenance are merged.
    // 5. E2: a session proposition below the promotion threshold with no stored match is discarded.

    private static final EntityMention ALICE =
            new EntityMention("Alice", "Person", "alice", MentionRole.SUBJECT, Map.of());
    private static final ProvenanceEntry FIRST_SOURCE =
            new ProvenanceEntry(new UriLocator("https://example.com/first"));
    private static final ProvenanceEntry SECOND_SOURCE =
            new ProvenanceEntry(new UriLocator("https://example.com/second"));

    @Test
    void boostOutsideZeroToOneIsRejected() {
        for (double boost : new double[]{-0.1, 1.5, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new DefaultMemoryConsolidator(0.6, 0.7, boost),
                    "boost " + boost + " should be rejected");
        }
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new DefaultMemoryConsolidator(0.6, 0.7, -0.1));
        assertTrue(ex.getMessage().contains("reinforcementBoost"), ex.getMessage());
        assertTrue(ex.getMessage().contains("-0.1"), ex.getMessage());
    }

    @Test
    void negativeOrNanThresholdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DefaultMemoryConsolidator(-0.1, 0.7, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new DefaultMemoryConsolidator(Double.NaN, 0.7, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new DefaultMemoryConsolidator(0.6, -0.1, 0.1));
        assertThrows(IllegalArgumentException.class, () -> new DefaultMemoryConsolidator(0.6, Double.NaN, 0.1));
    }

    @Test
    void thresholdAboveOneIsAccepted() {
        assertDoesNotThrow(() -> { new DefaultMemoryConsolidator(1.1, 1.1, 0.1); });

        DefaultMemoryConsolidator consolidator = new DefaultMemoryConsolidator(1.1, 1.1, 0.1);
        Proposition session = Proposition.draft("ctx", "Bob likes tea", 1.0).toProposition();

        ConsolidationResult result = consolidator.consolidate(List.of(session), List.of());

        assertTrue(result.getPromoted().isEmpty());
        assertEquals(1, result.getDiscarded().size());
    }

    @Test
    void defaultsAndBoundaryValuesAreAccepted() {
        assertDoesNotThrow(() -> { new DefaultMemoryConsolidator(); });
        assertDoesNotThrow(() -> { new DefaultMemoryConsolidator(0.0, 0.0, 0.0); });
        assertDoesNotThrow(() -> { new DefaultMemoryConsolidator(1.0, 1.0, 1.0); });
    }

    @Test
    void aMatchingSessionPropositionAddsItsEvidenceAndCountsOnce() {
        Proposition stored = Proposition.draft("ctx", "Alice works at Acme", 0.6)
                .withMentions(List.of(ALICE))
                .withGrounding(List.of("g1"))
                .withProvenance(List.of(FIRST_SOURCE))
                .withReinforceCount(2)
                .toProposition();
        Proposition session = Proposition.draft("ctx", "Alice works at Acme", 0.6)
                .withMentions(List.of(ALICE))
                .withGrounding(List.of("g2"))
                .withProvenance(List.of(SECOND_SOURCE))
                .toProposition();

        DefaultMemoryConsolidator consolidator = new DefaultMemoryConsolidator();
        ConsolidationResult result = consolidator.consolidate(List.of(session), List.of(stored));

        assertEquals(1, result.getReinforced().size());
        Proposition reinforced = result.getReinforced().get(0);
        assertTrue(reinforced.getGrounding().containsAll(List.of("g1", "g2")), reinforced.getGrounding().toString());
        assertEquals(2, reinforced.getGrounding().size());
        assertEquals(2, reinforced.getProvenanceEntries().size());
        assertEquals(3, reinforced.getReinforceCount());
        assertEquals(0.7, reinforced.getConfidence(), 1e-9);
    }
}

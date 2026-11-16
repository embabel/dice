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
import com.embabel.dice.proposition.TemporalQueryCapable;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that Java callers can run the temporal queries on TemporalQueryCapable by passing
 * Proposition.DEFAULT_DECAY_K explicitly, the way a Java developer would.
 *
 * Contract:
 * R1: the store is a plain lambda, so TemporalQueryCapable must stay a single-method interface.
 * E1: the ordered query called with DEFAULT_DECAY_K puts the fresh proposition first.
 * E2: the threshold query called with a threshold and DEFAULT_DECAY_K leaves the decayed proposition out.
 * E3: the window query called with (start, end, end, DEFAULT_DECAY_K) returns both propositions, fresh first.
 * I1: a query never changes the propositions the store returns.
 */
class TemporalQueryJavaInteropTest {

    private static final Instant NOW = Instant.now();
    private static final Instant LAST_YEAR = NOW.minus(365, ChronoUnit.DAYS);

    // Fresh has no decay, so it keeps full confidence. Old has heavy decay and is a year old.
    private final Proposition fresh = Proposition.draft("acme", "Alice lives in Paris", 0.9)
            .withCreated(NOW)
            .withContentRevised(NOW)
            .withMetadataRevised(NOW)
            .withLastAccessed(NOW)
            .toProposition();

    private final Proposition old = Proposition.draft("acme", "Bob lived in Lyon", 0.9)
            .withDecay(0.5)
            .withCreated(LAST_YEAR)
            .withContentRevised(LAST_YEAR)
            .withMetadataRevised(LAST_YEAR)
            .withLastAccessed(LAST_YEAR)
            .toProposition();

    private final TemporalQueryCapable store = () -> List.of(fresh, old);

    // E1: ordered query called with the default decay rate puts the fresh proposition first.
    @Test
    void findAllOrderedByEffectiveConfidenceTakesDefaultDecayRate() {
        List<Proposition> result = store.findAllOrderedByEffectiveConfidence(Proposition.DEFAULT_DECAY_K);

        assertSame(fresh, result.get(0));
        assertEquals(List.of(fresh, old), result);
    }

    // E2: threshold query called with the default decay rate leaves out the decayed proposition.
    @Test
    void findByEffectiveConfidenceAboveTakesDefaultDecayRate() {
        List<Proposition> result = store.findByEffectiveConfidenceAbove(0.5, Proposition.DEFAULT_DECAY_K);

        assertEquals(List.of(fresh), result);
    }

    // E3: window query with end as asOf and the default decay rate returns both propositions, fresh first.
    @Test
    void findByCreatedBetweenOrderedByEffectiveConfidenceTakesAsOfAndDecayRate() {
        Instant start = NOW.minus(400, ChronoUnit.DAYS);
        Instant end = NOW.plus(1, ChronoUnit.DAYS);

        List<Proposition> result = store.findByCreatedBetweenOrderedByEffectiveConfidence(start, end, end, Proposition.DEFAULT_DECAY_K);

        assertEquals(List.of(fresh, old), result);
    }

    // R1 and I1: the store is still a lambda and queries leave the propositions untouched.
    @Test
    void queriesDoNotChangeTheStoredPropositions() {
        store.findAllOrderedByEffectiveConfidence(Proposition.DEFAULT_DECAY_K);
        store.findByEffectiveConfidenceAbove(0.5, Proposition.DEFAULT_DECAY_K);

        assertEquals(0.9, fresh.getConfidence(), 1e-12);
        assertEquals(0.5, old.getDecay(), 1e-12);
    }
}

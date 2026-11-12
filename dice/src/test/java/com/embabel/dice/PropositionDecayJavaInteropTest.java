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
 * Checks that Java callers can use the decay functions on Proposition without passing the
 * decay-rate multiplier k.
 */
class PropositionDecayJavaInteropTest {

    @Test
    void defaultDecayRateIsPublished() {
        assertEquals(2.0, Proposition.DEFAULT_DECAY_K);
    }

    @Test
    void effectiveConfidenceAtUsesTheDefaultRate() {
        Proposition fact = oldFact();
        Instant asOf = Instant.parse("2020-01-03T00:00:00Z");

        assertEquals(fact.effectiveConfidenceAt(asOf, Proposition.DEFAULT_DECAY_K), fact.effectiveConfidenceAt(asOf), 1e-12);
        assertEquals(0.8 * Math.exp(-0.5 * 2.0 * 2), fact.effectiveConfidenceAt(asOf), 1e-9);
    }

    @Test
    void effectiveConfidenceNeedsNoArgument() {
        assertTrue(oldFact().effectiveConfidence() < 0.001);
    }

    @Test
    void withDecayAppliedNeedsNoArgument() {
        Proposition decayed = oldFact().withDecayApplied();

        assertTrue(decayed.getConfidence() < 0.001);
        assertEquals("p1", decayed.getId());
    }

    private static Proposition oldFact() {
        Instant day = Instant.parse("2020-01-01T00:00:00Z");
        return Proposition.create(
                "p1",
                "acme",
                "Alice lives in Paris",
                List.of(),
                0.8,
                0.5,
                null,
                List.of(),
                day,
                day,
                PropositionStatus.ACTIVE);
    }
}

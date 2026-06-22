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

import com.embabel.dice.spi.AuthorityTier;
import com.embabel.dice.spi.NeutralTrustScorer;
import com.embabel.dice.spi.TrustScorer;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Demonstrates that the TrustScorer convenience overloads are reachable as instance
 * methods from Java callers, not only via the 3-argument abstract method.
 */
class TrustScorerJavaInteropTest {

    private Proposition proposition() {
        Instant now = Instant.now();
        return Proposition.create(
                "java-trust-test-id",
                "java-trust-test",
                "Alice is a software engineer",
                List.of(),
                0.8,
                0.0,
                0.5,
                null,
                List.of(),
                now,
                now,
                Instant.now(),
                PropositionStatus.ACTIVE
        );
    }

    @Test
    void convenienceOverloadsAreReachableAsInstanceMethods() {
        TrustScorer scorer = NeutralTrustScorer.INSTANCE;
        Proposition prop = proposition();

        // 1-arg convenience overload, callable directly on the instance from Java
        assertEquals(1.0, scorer.score(prop), 0.0);
        // 2-arg convenience overload, callable directly on the instance from Java
        assertEquals(1.0, scorer.score(prop, AuthorityTier.PRIMARY), 0.0);
        // 3-arg abstract method
        assertEquals(1.0, scorer.score(prop, AuthorityTier.PRIMARY, null), 0.0);
    }
}

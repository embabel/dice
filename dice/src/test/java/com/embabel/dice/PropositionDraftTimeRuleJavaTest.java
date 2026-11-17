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
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Checks how a draft fills in the times it was not given, from Java.
 *
 * E1: a draft that sets only contentRevised, as an import does, builds a proposition whose four
 *     times all equal it, so the fact is neither created after it nor looks freshly touched.
 * E2: a draft that sets only created keeps it, and contentRevised takes the build moment, as the
 *     constructor does; metadataRevised and lastAccessed follow contentRevised.
 * E3: created is never after contentRevised, and metadataRevised is never before it, when unset.
 * E4: a draft with no times set builds four equal times at the build moment.
 */
class PropositionDraftTimeRuleJavaTest {

    private static final Instant IMPORTED = Instant.parse("2024-06-01T00:00:00Z");
    private static final Instant CREATED = Instant.parse("2024-01-01T00:00:00Z");

    @Test
    void e1_onlyContentRevisedSetsEveryTimeToIt() {
        Proposition p = Proposition.draft("acme", "Alice lives in Paris", 0.9)
                .withContentRevised(IMPORTED)
                .toProposition();

        assertEquals(IMPORTED, p.getCreated());
        assertEquals(IMPORTED, p.getContentRevised());
        assertEquals(IMPORTED, p.getMetadataRevised());
        assertEquals(IMPORTED, p.getLastAccessed());
    }

    @Test
    void e2_onlyCreatedKeepsItAndRevisesAtTheBuildMoment() {
        Instant before = Instant.now();

        Proposition p = Proposition.draft("acme", "Alice lives in Paris", 0.9)
                .withCreated(CREATED)
                .toProposition();

        assertEquals(CREATED, p.getCreated());
        assertFalse(p.getContentRevised().isBefore(before));
        assertEquals(p.getContentRevised(), p.getMetadataRevised());
        assertEquals(p.getContentRevised(), p.getLastAccessed());
    }

    @Test
    void e3_unsetTimesKeepCreatedFirstAndMetadataLast() {
        Proposition onlyMetadata = Proposition.draft("acme", "t", 0.9)
                .withMetadataRevised(IMPORTED)
                .toProposition();
        assertEquals(IMPORTED, onlyMetadata.getCreated());
        assertFalse(onlyMetadata.getCreated().isAfter(onlyMetadata.getContentRevised()));

        Proposition contentAndAccess = Proposition.draft("acme", "t", 0.9)
                .withContentRevised(IMPORTED)
                .withLastAccessed(CREATED)
                .toProposition();
        assertEquals(CREATED, contentAndAccess.getCreated());
        assertEquals(IMPORTED, contentAndAccess.getMetadataRevised());
    }

    @Test
    void e4_noTimesSetGivesFourEqualTimesAtTheBuildMoment() {
        Instant before = Instant.now();

        Proposition p = Proposition.draft("acme", "t", 0.9).toProposition();

        assertFalse(p.getCreated().isBefore(before));
        assertEquals(p.getCreated(), p.getContentRevised());
        assertEquals(p.getCreated(), p.getMetadataRevised());
        assertEquals(p.getCreated(), p.getLastAccessed());
    }
}

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

import com.embabel.dice.common.PropositionContradicted;
import com.embabel.dice.common.UngroundedMentionsFound;
import com.embabel.dice.pipeline.UngroundedMentionHandling;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks that Java can build the two events that carry a context through their {@code of}
 * factories, the same way a Java developer would call them.
 *
 * Contract, as the tests read it:
 * R1: a blank contextIdValue (empty or only whitespace) is refused with IllegalArgumentException.
 * R2: a non-blank contextIdValue is accepted, including one with surrounding spaces around a word.
 * E1: the context read back through getContextIdValue() is the one passed in.
 * E2: the overload with a timestamp keeps exactly the instant it was given.
 * E3: the overload without a timestamp stamps the event with the time of the call, so it is not
 *     before an instant taken just before it.
 */
class EventFactoriesJavaInteropTest {

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

    @Test
    void contradictionFactoryReadsBackTheContextItWasGiven() {
        // E1
        PropositionContradicted event = PropositionContradicted.of("acme", proposition("a"), proposition("b"));

        assertEquals("acme", event.getContextIdValue());
    }

    @Test
    void contradictionFactoryKeepsTheInstantItWasGiven() {
        // E2
        Instant when = Instant.parse("2026-01-02T03:04:05Z");
        Proposition original = proposition("a");
        Proposition newer = proposition("b");

        PropositionContradicted event = PropositionContradicted.of("acme", original, newer, when);

        assertEquals(when, event.getTimestamp());
        assertEquals(original, event.getOriginal());
        assertEquals(newer, event.getNew());
    }

    @Test
    void contradictionFactoryWithoutATimestampIsNotBeforeTheCall() {
        // E3
        Instant before = Instant.now();

        PropositionContradicted event = PropositionContradicted.of("acme", proposition("a"), proposition("b"));

        assertFalse(event.getTimestamp().isBefore(before));
    }

    @Test
    void contradictionFactoryRefusesABlankContext() {
        // R1
        Proposition original = proposition("a");
        Proposition newer = proposition("b");

        assertThrows(IllegalArgumentException.class,
                () -> PropositionContradicted.of("", original, newer));
        assertThrows(IllegalArgumentException.class,
                () -> PropositionContradicted.of("   ", original, newer, Instant.EPOCH));
    }

    @Test
    void ungroundedFactoryReadsBackTheContextAndChunk() {
        // E1
        UngroundedMentionsFound event = UngroundedMentionsFound.of(
                "acme", "chunk-1", List.of(), UngroundedMentionHandling.DROP);

        assertEquals("acme", event.getContextIdValue());
        assertEquals("chunk-1", event.getChunkId());
        assertEquals(UngroundedMentionHandling.DROP, event.getHandling());
    }

    @Test
    void ungroundedFactoryKeepsTheInstantItWasGiven() {
        // E2
        Instant when = Instant.parse("2026-02-03T04:05:06Z");

        UngroundedMentionsFound event = UngroundedMentionsFound.of(
                "acme", "chunk-1", List.of(), UngroundedMentionHandling.KEEP, when);

        assertEquals(when, event.getTimestamp());
    }

    @Test
    void ungroundedFactoryWithoutATimestampIsNotBeforeTheCall() {
        // E3
        Instant before = Instant.now();

        UngroundedMentionsFound event = UngroundedMentionsFound.of(
                "acme", "chunk-1", List.of(), UngroundedMentionHandling.KEEP);

        assertFalse(event.getTimestamp().isBefore(before));
    }

    @Test
    void ungroundedFactoryRefusesABlankContext() {
        // R1
        assertThrows(IllegalArgumentException.class,
                () -> UngroundedMentionsFound.of("", "chunk-1", List.of(), UngroundedMentionHandling.KEEP));
        assertThrows(IllegalArgumentException.class,
                () -> UngroundedMentionsFound.of(" ", "chunk-1", List.of(), UngroundedMentionHandling.KEEP, Instant.EPOCH));
    }

    @Test
    void ungroundedFactoryAcceptsAContextWithSpacesAroundAWord() {
        // R2: a context with padding is still a non-blank context, so it is not refused.
        UngroundedMentionsFound event = UngroundedMentionsFound.of(
                " acme ", "chunk-1", List.of(), UngroundedMentionHandling.KEEP);

        assertFalse(event.getContextIdValue().isBlank());
    }
}

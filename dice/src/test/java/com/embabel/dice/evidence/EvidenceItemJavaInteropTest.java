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
package com.embabel.dice.evidence;

import com.embabel.dice.temporal.TemporalMetadata;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shows how a Java caller sets and reads an item's reinforce count, temporal metadata and loaded source claims.
 */
class EvidenceItemJavaInteropTest {

    private final EvidenceItem item =
            EvidenceItem.of("Ada wrote the first program", Citation.of("p-17", "support-team"), 0.9);

    private final EvidenceItem source1 =
            EvidenceItem.of("Ada worked with Babbage", Citation.of("p-1", "support-team"), 0.8);

    private final EvidenceItem source2 =
            EvidenceItem.of("Ada wrote notes on the engine", Citation.of("p-2", "support-team"), 0.8);

    @Test
    void itemReadsBackItsReinforceCountAndTemporalMetadata() {
        TemporalMetadata temporal = new TemporalMetadata(
                Instant.parse("2024-01-01T00:00:00Z"), null, null, null, List.of("p-3"), List.of("p-4"));

        EvidenceItem reinforced = item.withReinforceCount(3).withTemporal(temporal);

        assertEquals(3, reinforced.getReinforceCount());
        assertEquals(temporal, reinforced.getTemporal());
        assertEquals(0, item.getReinforceCount());
        assertNull(item.getTemporal());
    }

    @Test
    void negativeReinforceCountIsRejected() {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> item.withReinforceCount(-1));

        assertTrue(thrown.getMessage().contains("-1"), thrown.getMessage());
    }

    @Test
    void itemKeepsItsOwnCopyOfTheTemporalLists() {
        List<String> supersedes = new ArrayList<>(List.of("p-3"));
        TemporalMetadata temporal = new TemporalMetadata(null, null, null, null, supersedes, List.of());

        EvidenceItem withTemporal = item.withTemporal(temporal);
        supersedes.add("p-5");

        assertEquals(List.of("p-3"), withTemporal.getTemporal().getSupersedes());
        assertThrows(UnsupportedOperationException.class,
                () -> withTemporal.getTemporal().getSupersedes().add("p-6"));
    }

    @Test
    void itemsThatDifferOnlyInReinforceCountAreNotEqual() {
        assertNotEquals(item.withReinforceCount(1), item.withReinforceCount(2));
    }

    @Test
    void loadedClaimsSetTheIdsOfAnItemWithNone() {
        EvidenceItem abstraction = item.withDerivedFrom(List.of(source1, source2));

        assertEquals(List.of("p-1", "p-2"), abstraction.getDerivedFromIds());
        assertEquals(List.of(source1, source2), abstraction.getDerivedFrom());
        assertTrue(abstraction.isDerived());
    }

    @Test
    void loadedClaimsMayBeASubsetOfTheIds() {
        EvidenceItem abstraction = item.withDerivedFromIds(List.of("p-1", "p-2", "p-3")).withDerivedFrom(List.of(source2));

        assertEquals(List.of("p-1", "p-2", "p-3"), abstraction.getDerivedFromIds());
        assertEquals(List.of(source2), abstraction.getDerivedFrom());
    }

    @Test
    void loadedClaimOutsideTheIdsIsRejected() {
        EvidenceItem abstraction = item.withDerivedFromIds(List.of("p-2"));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> abstraction.withDerivedFrom(List.of(source1)));

        assertTrue(thrown.getMessage().contains("derivedFrom[0]"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("p-1"), thrown.getMessage());
    }

    @Test
    void oneIdLoadedTwiceInTwoContextsIsRejected() {
        EvidenceItem elsewhere = EvidenceItem.of("Ada worked with Babbage", Citation.of("p-1", "archive"), 0.8);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> item.withDerivedFromIds(List.of("p-1")).withDerivedFrom(List.of(source1, elsewhere)));

        assertTrue(thrown.getMessage().contains("derivedFrom[0] and derivedFrom[1]"), thrown.getMessage());
    }

    @Test
    void idsThatLeaveALoadedClaimUnnamedAreRejected() {
        EvidenceItem abstraction = item.withDerivedFrom(List.of(source1, source2));

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> abstraction.withDerivedFromIds(List.of("p-1")));

        assertTrue(thrown.getMessage().contains("derivedFrom[1]"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("p-2"), thrown.getMessage());
    }

    @Test
    void itemsThatDifferOnlyInLoadedClaimsAreNotEqual() {
        EvidenceItem abstraction = item.withDerivedFromIds(List.of("p-1", "p-2"));

        assertNotEquals(abstraction.withDerivedFrom(List.of(source1)), abstraction.withDerivedFrom(List.of(source2)));
    }

    @Test
    void itemsWhoseLoadedClaimsShareAncestorsHashAndCompareQuickly() {
        EvidenceItem first = chainOfAbstractions(40, "Ada was born in 1815");
        EvidenceItem second = chainOfAbstractions(40, "Ada was born in 1815");

        int hash = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> first.hashCode());
        boolean equal = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> first.equals(second));

        assertTrue(equal);
        assertEquals(hash, second.hashCode());
    }

    @Test
    void itemsThatDifferOnlyInADeepAncestorAreNotEqual() {
        EvidenceItem first = chainOfAbstractions(40, "Ada was born in 1815");
        EvidenceItem second = chainOfAbstractions(40, "Ada was born in 1816");

        assertNotEquals(first, second);
    }

    @Test
    void deepChainsBuiltSeparatelyAreEqual() {
        EvidenceItem first = chainOfSingleSources(20_000, "Ada was born in 1815", "Ada wrote the first program");
        EvidenceItem second = chainOfSingleSources(20_000, "Ada was born in 1815", "Ada wrote the first program");

        assertTrue(first.equals(second));
        assertTrue(second.equals(first));
        assertEquals(first.hashCode(), second.hashCode());
    }

    @Test
    void deepChainsThatDifferAtTheTopOrTheBottomAreNotEqual() {
        EvidenceItem chain = chainOfSingleSources(20_000, "Ada was born in 1815", "Ada wrote the first program");
        EvidenceItem otherTop = chainOfSingleSources(20_000, "Ada was born in 1815", "Ada wrote the second program");
        EvidenceItem otherLeaf = chainOfSingleSources(20_000, "Ada was born in 1816", "Ada wrote the first program");

        assertNotEquals(chain, otherTop);
        assertNotEquals(chain, otherLeaf);
        assertNotEquals(otherLeaf, chain);
    }

    @Test
    void deepChainsWithEqualHashesThatDifferAtTheLeafAreNotEqual() {
        // "Aa" and "BB" have the same String hash, so every level hashes alike and only the leaf text differs.
        EvidenceItem first = chainOfSingleSources(20_000, "Aa", "Ada wrote the first program");
        EvidenceItem second = chainOfSingleSources(20_000, "BB", "Ada wrote the first program");

        assertEquals(first.hashCode(), second.hashCode());
        assertFalse(first.equals(second));
        assertFalse(second.equals(first));
    }

    /**
     * Builds a chain where c-0 is an observation and each later claim c-n is abstracted from c-(n-1) alone.
     *
     * @param length the number of claims in the chain
     * @param leafText the text of c-0
     * @param topText the text of the last claim
     * @return the last claim in the chain
     */
    private static EvidenceItem chainOfSingleSources(int length, String leafText, String topText) {
        EvidenceItem previous = EvidenceItem.of(leafText, Citation.of("c-0", "support-team"), 0.8);
        for (int n = 1; n < length; n++) {
            String text = n == length - 1 ? topText : "Ada claim " + n;
            previous = EvidenceItem.of(text, Citation.of("c-" + n, "support-team"), 0.7)
                    .withDerivedFrom(List.of(previous));
        }
        return previous;
    }

    /**
     * Builds a chain where p-0 and p-1 are observations and each later claim p-n is abstracted from the
     * two before it, p-(n-1) and p-(n-2).
     *
     * @param last the number of the last claim in the chain
     * @param firstText the text of p-0
     * @return the claim p-last
     */
    private static EvidenceItem chainOfAbstractions(int last, String firstText) {
        EvidenceItem older = EvidenceItem.of(firstText, Citation.of("p-0", "support-team"), 0.8);
        EvidenceItem newer = EvidenceItem.of("Ada met Babbage", Citation.of("p-1", "support-team"), 0.8);
        for (int n = 2; n <= last; n++) {
            EvidenceItem next = EvidenceItem.of("Ada claim " + n, Citation.of("p-" + n, "support-team"), 0.7)
                    .withDerivedFrom(List.of(newer, older));
            older = newer;
            newer = next;
        }
        return newer;
    }
}

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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shows how a Java caller sets and reads an item's reinforce count and temporal metadata.
 */
class EvidenceItemJavaInteropTest {

    private final EvidenceItem item =
            EvidenceItem.of("Ada wrote the first program", Citation.of("p-17", "support-team"), 0.9);

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
}

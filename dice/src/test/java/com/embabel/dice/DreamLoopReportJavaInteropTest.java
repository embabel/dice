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

import com.embabel.dice.projection.memory.DreamLoopReport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks that Java can build a {@link DreamLoopReport} through its static {@code of} factory.
 *
 * Contract:
 * R1 a blank context id throws.
 * E1 {@code of} returns a report holding the values it was given.
 * L1 two reports built from the same values are equal.
 */
class DreamLoopReportJavaInteropTest {

    private static final Instant STARTED = Instant.EPOCH;
    private static final Instant COMPLETED = Instant.EPOCH.plusSeconds(5);

    @Test
    void ofBuildsTheSameReportAsTheKotlinFixture() {
        DreamLoopReport built = DreamLoopReport.of(
                "acme", STARTED, STARTED, List.of(), 0, 0, 0, true);

        assertEquals(ContextIdFixtures.dreamLoopReport("acme"), built);
    }

    @Test
    void ofKeepsEveryValueItWasGiven() {
        DreamLoopReport report = DreamLoopReport.of(
                "acme", STARTED, COMPLETED, List.of(), 7, 3, 2, true);

        assertEquals("acme", report.getContextIdValue());
        assertEquals(STARTED, report.getCycleStarted());
        assertEquals(COMPLETED, report.getCycleCompleted());
        assertEquals(List.of(), report.getPassResults());
        assertEquals(7, report.getTotalExamined());
        assertEquals(3, report.getTotalTransitioned());
        assertEquals(2, report.getTotalNewPropositions());
        assertEquals(true, report.getTriggered());
    }

    @Test
    void ofRejectsABlankContextId() {
        assertThrows(IllegalArgumentException.class, () -> DreamLoopReport.of(
                " ", STARTED, STARTED, List.of(), 0, 0, 0, true));
    }
}

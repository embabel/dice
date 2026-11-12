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
package com.embabel.dice.metamodel;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/*
 * Contract for InMemoryDriftReportStore, as a Java caller sees it.
 *
 * R1: driftReports and globalDriftReports require a positive limit and throw
 *     IllegalArgumentException otherwise.
 * E1: a read returns the matching reports newest first by capturedAt.
 */
class InMemoryDriftReportStoreJavaInteropTest {

    private final InMemoryDriftReportStore store = new InMemoryDriftReportStore();

    @Test
    void savedReportReadsBackNewestFirst() {
        DriftReport older = new DriftReport(
                "support", "hash-1", Set.of("Ghost"), Set.of(), Instant.parse("2026-01-01T00:00:00Z"));
        DriftReport newer = new DriftReport(
                "support", "hash-1", Set.of("Ghost"), Set.of(), Instant.parse("2026-01-02T00:00:00Z"));

        store.saveDriftReport(older);
        store.saveDriftReport(newer);

        assertEquals(List.of(newer, older), store.driftReports("support", 10));
    }

    @Test
    void nonPositiveLimitIsRejected() {
        // R1, the positive side: a limit of one is accepted and returns the one report there is.
        DriftReport only = new DriftReport(
                "support", "hash-1", Set.of("Ghost"), Set.of(), Instant.parse("2026-01-01T00:00:00Z"));
        store.saveDriftReport(only);
        assertEquals(List.of(only), store.globalDriftReports("support", 1, null));

        // R1, the boundary: zero is rejected.
        assertThrows(IllegalArgumentException.class, () -> store.globalDriftReports("support", 0));
    }
}

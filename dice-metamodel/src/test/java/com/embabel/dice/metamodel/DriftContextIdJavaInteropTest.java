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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DriftContextIdJavaInteropTest {

    @Test
    void sixArgumentFactoryTakesTheContextAsAPlainString() {
        DriftReport report = DriftReport.of(
                "support",
                "hash",
                Set.of("Ticket"),
                Set.of("RAISED"),
                Instant.EPOCH,
                "acme");

        assertEquals("acme", report.getContextIdValue());
        assertEquals("support", report.getSchemaName());
        assertEquals("hash", report.getVersionHash());
        assertEquals(Set.of("Ticket"), report.getDriftedEntityTypes());
        assertEquals(Set.of("RAISED"), report.getDriftedRelationshipTypes());
        assertEquals(Instant.EPOCH, report.getCapturedAt());
        assertNull(report.getDeclaredDiff());
    }

    @Test
    void sevenArgumentFactoryWithNullContextKeepsTheDeclaredDiff() {
        MetamodelVersion version = new MetamodelVersion("support", List.of(), Map.of(), Map.of(), List.of());
        MetamodelDiff declaredDiff = new MetamodelDiff(version, version, List.of());

        DriftReport report = DriftReport.of(
                "support",
                "hash",
                Set.of(),
                Set.of(),
                Instant.EPOCH,
                null,
                declaredDiff);

        assertNull(report.getContextIdValue());
        assertSame(declaredDiff, report.getDeclaredDiff());
    }

    @Test
    void sixArgumentFactoryRejectsABlankContext() {
        assertThrows(IllegalArgumentException.class, () -> DriftReport.of(
                "support", "hash", Set.of(), Set.of(), Instant.EPOCH, "  "));
    }

    @Test
    void sevenArgumentFactoryRejectsABlankContext() {
        assertThrows(IllegalArgumentException.class, () -> DriftReport.of(
                "support", "hash", Set.of(), Set.of(), Instant.EPOCH, "  ", null));
    }

    @Test
    void scopedReportExposesItsContext() {
        assertEquals("acme", DriftContextIdFixtures.scopedReport("acme").getContextIdValue());
    }

    @Test
    void scopedResultExposesItsContext() {
        assertEquals("acme", DriftContextIdFixtures.scopedResult("acme").getContextIdValue());
    }

    @Test
    void wholeGraphReportHasNoContext() {
        DriftReport report = new DriftReport("support", "hash", Set.of(), Set.of(), Instant.EPOCH);

        assertNull(report.getContextIdValue());
    }
}

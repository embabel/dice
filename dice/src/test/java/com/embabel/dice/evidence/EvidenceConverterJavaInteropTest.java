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

import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.revision.RevisionResult;
import com.embabel.dice.spi.ConflictType;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shows how a Java caller converts a contradiction and reads the kind of disagreement it becomes.
 */
class EvidenceConverterJavaInteropTest {

    private static final Instant RECORDED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private final EvidenceConverter converter =
            EvidenceConverter.create().withClock(Clock.fixed(RECORDED_AT, ZoneOffset.UTC));

    private final Proposition original = claim("p-1", "Ada lives in Marylebone", PropositionStatus.CONTRADICTED);

    private final Proposition replacement = claim("p-2", "Ada lives in London", PropositionStatus.ACTIVE);

    @Test
    void contradictionConvertsToConflictingWithTheOriginalFirst() {
        Evidence.Disagreement disagreement = converter.fromContradiction(
                new RevisionResult.Contradicted(original, replacement, ConflictType.Contradiction.INSTANCE));

        Evidence.Conflicting conflicting = assertInstanceOf(Evidence.Conflicting.class, disagreement);
        assertEquals("p-1", conflicting.getItems().get(0).getCitation().getPropositionId());
        assertEquals("p-2", conflicting.getItems().get(1).getCitation().getPropositionId());
        assertEquals(ConflictType.Contradiction.INSTANCE, conflicting.getConflictType());
    }

    @Test
    void customConflictConvertsToConflicting() {
        ConflictType custom = new ConflictType.Custom("policy-change");

        Evidence.Disagreement disagreement =
                converter.fromContradiction(new RevisionResult.Contradicted(original, replacement, custom));

        Evidence.Conflicting conflicting = assertInstanceOf(Evidence.Conflicting.class, disagreement);
        assertEquals("p-1", conflicting.getItems().get(0).getCitation().getPropositionId());
        assertEquals("p-2", conflicting.getItems().get(1).getCitation().getPropositionId());
        assertEquals(custom, disagreement.getConflictType());
    }

    @Test
    void revisionConvertsToSupersededWithTheOriginalAsPrevious() {
        Evidence.Disagreement disagreement = converter.fromContradiction(
                new RevisionResult.Contradicted(original, replacement, ConflictType.Revision.INSTANCE));

        Evidence.Superseded superseded = assertInstanceOf(Evidence.Superseded.class, disagreement);
        assertEquals("p-2", superseded.getCurrent().getCitation().getPropositionId());
        assertEquals(1, superseded.getPrevious().size());
        assertEquals("p-1", superseded.getPrevious().get(0).getCitation().getPropositionId());
        assertEquals(PropositionStatus.CONTRADICTED, superseded.getPrevious().get(0).getStatus());
        assertEquals(ConflictType.Revision.INSTANCE, superseded.getConflictType());
    }

    @Test
    void worldChangeConvertsToSupersededWithTheOriginalAsPrevious() {
        Evidence.Disagreement disagreement = converter.fromContradiction(
                new RevisionResult.Contradicted(original, replacement, ConflictType.WorldProgression.INSTANCE));

        Evidence.Superseded superseded = assertInstanceOf(Evidence.Superseded.class, disagreement);
        assertEquals("p-2", superseded.getCurrent().getCitation().getPropositionId());
        assertEquals("p-1", superseded.getPrevious().get(0).getCitation().getPropositionId());
        assertEquals(ConflictType.WorldProgression.INSTANCE, superseded.getConflictType());
    }

    @Test
    void pinnedOriginalUnderAWorldChangeKeepsItsActiveStatus() {
        Proposition pinned = claim("p-1", "Ada lives in Marylebone", PropositionStatus.ACTIVE).withPinned(true);

        Evidence.Disagreement disagreement = converter.fromContradiction(
                new RevisionResult.Contradicted(pinned, replacement, ConflictType.WorldProgression.INSTANCE));

        Evidence.Superseded superseded = assertInstanceOf(Evidence.Superseded.class, disagreement);
        assertEquals(PropositionStatus.ACTIVE, superseded.getPrevious().get(0).getStatus());
        assertEquals(PropositionStatus.ACTIVE, superseded.getCurrent().getStatus());
    }

    /**
     * Creates a claim in the `support-team` context with fixed timestamps and no decay.
     *
     * @param id the claim id
     * @param text the claim text
     * @param status the lifecycle status
     * @return the claim
     */
    private static Proposition claim(String id, String text, PropositionStatus status) {
        return Proposition.create(
                id, "support-team", text, List.of(), 0.8, 0.0, null, List.of(), RECORDED_AT, RECORDED_AT, status);
    }
}

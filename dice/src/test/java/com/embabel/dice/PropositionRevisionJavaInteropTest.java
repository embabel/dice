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

import com.embabel.dice.common.DiceMetadataKeys;
import com.embabel.dice.proposition.EntityMention;
import com.embabel.dice.proposition.MentionRole;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shows how Java code reads when a proposition's content was last revised.
 */
class PropositionRevisionJavaInteropTest {

    private static final Instant CREATED = Instant.parse("2025-01-01T00:00:00Z");

    private static Proposition drafted() {
        return Proposition.draft("acme", "Alice lives in Paris", 0.9)
                .withCreated(CREATED)
                .toProposition();
    }

    @Test
    void aDraftedClaimHasNoContentRevision() {
        assertNull(drafted().getLastContentRevision());
    }

    @Test
    void withTextRecordsTheRevisionTimeAndTheMetadataEntry() {
        Proposition revised = drafted().withText("Alice lives in Lyon");

        assertNotNull(revised.getLastContentRevision());
        assertEquals(revised.getContentRevised(), revised.getLastContentRevision());
        assertEquals(Boolean.TRUE, revised.getMetadata().get(DiceMetadataKeys.CONTENT_REVISED));
    }

    @Test
    void withConfidenceAndWithResolvedMentionsRecordTheRevision() {
        Proposition confidence = drafted().withConfidence(0.4);
        Proposition mentions = drafted().withResolvedMentions(
                List.of(new EntityMention("Alice", "Person", "alice-1", MentionRole.SUBJECT, Map.of())));

        assertEquals(confidence.getContentRevised(), confidence.getLastContentRevision());
        assertEquals(mentions.getContentRevised(), mentions.getLastContentRevision());
    }

    @Test
    void administrativeChangesDoNotRecordARevision() {
        Proposition p = drafted();

        assertNull(p.withStatus(PropositionStatus.STALE).getLastContentRevision());
        assertNull(p.withPinned(true).getLastContentRevision());
        assertNull(p.withGrounding(List.of("chunk-1")).getLastContentRevision());
        assertNull(p.withMetadataValue("source", "wiki").getLastContentRevision());
    }

    @Test
    void aDraftCopyKeepsTheRevisionUnlessItsMetadataIsReplaced() {
        Proposition revised = drafted().withText("Alice lives in Lyon");

        assertEquals(revised.getContentRevised(), revised.toDraft().toProposition().getLastContentRevision());
        assertNull(revised.toDraft().withMetadata(Map.of()).toProposition().getLastContentRevision());
    }

    @Test
    void differentCreatedAndContentRevisedTimesAloneRecordNoRevision() {
        Proposition p = Proposition.create(
                "p-1",
                "acme",
                "Alice lives in Paris",
                List.of(),
                0.9,
                0.0,
                null,
                List.of(),
                CREATED,
                CREATED.plus(Duration.ofDays(10)),
                PropositionStatus.ACTIVE);

        assertEquals(CREATED.plus(Duration.ofDays(10)), p.getContentRevised());
        assertNull(p.getLastContentRevision());
    }
}

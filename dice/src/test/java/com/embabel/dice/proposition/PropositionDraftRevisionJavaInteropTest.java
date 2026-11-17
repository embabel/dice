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
package com.embabel.dice.proposition;

import com.embabel.dice.common.DiceMetadataKeys;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shows which content revision a proposition built from a draft carries, from Java.
 */
class PropositionDraftRevisionJavaInteropTest {

    private static final Instant CREATED = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant REVISED_AT = Instant.parse("2025-02-01T00:00:00Z");
    private static final Instant LATER = Instant.parse("2025-03-01T00:00:00Z");

    private static Proposition stored() {
        return Proposition.draft("acme", "Alice lives in Paris", 0.9)
                .withId("p-1")
                .withCreated(CREATED)
                .toProposition();
    }

    private static Proposition revised() {
        return stored().withMetadataValue(DiceMetadataKeys.CONTENT_REVISED, REVISED_AT.toString());
    }

    @Test
    void anUnchangedCopyKeepsTheRecord() {
        Proposition copy = revised().toDraft().toProposition();

        assertEquals(REVISED_AT, copy.getLastContentRevision());
        assertEquals(REVISED_AT.toString(), copy.getMetadata().get(DiceMetadataKeys.CONTENT_REVISED));
        assertNull(stored().toDraft().toProposition().getLastContentRevision());
    }

    @Test
    void changedTextRecordsARevisionAtTheBuildMoment() {
        Proposition fromRevised = revised().toDraft().withText("Alice lives in Lyon").toProposition();
        Proposition fromStored = stored().toDraft().withText("Alice lives in Lyon").toProposition();

        assertEquals(fromRevised.getContentRevised(), fromRevised.getLastContentRevision());
        assertTrue(fromRevised.getLastContentRevision().isAfter(REVISED_AT));
        assertEquals(fromStored.getContentRevised(), fromStored.getLastContentRevision());
        assertTrue(fromStored.getLastContentRevision().isAfter(CREATED));
    }

    @Test
    void changedConfidenceOrMentionsRecordARevision() {
        Proposition confidence = stored().toDraft().withConfidence(0.4).toProposition();
        Proposition mentions = stored().toDraft()
                .withMentions(List.of(new EntityMention("Alice", "Person", "alice-1", MentionRole.SUBJECT, Map.of())))
                .toProposition();

        assertEquals(confidence.getContentRevised(), confidence.getLastContentRevision());
        assertEquals(mentions.getContentRevised(), mentions.getLastContentRevision());
    }

    @Test
    void theSameValuesRecordNoRevision() {
        Proposition p = stored();

        assertEquals(REVISED_AT, revised().toDraft().withText(p.getText()).toProposition().getLastContentRevision());
        assertNull(p.toDraft().withText(p.getText()).toProposition().getLastContentRevision());
        assertNull(p.toDraft().withConfidence(p.getConfidence()).toProposition().getLastContentRevision());
        assertNull(p.toDraft().withMentions(p.getMentions()).toProposition().getLastContentRevision());
    }

    @Test
    void aContentRevisedTimeTheCallerSetIsTheRevisionTime() {
        Proposition p = revised().toDraft()
                .withContentRevised(LATER)
                .withText("Alice lives in Lyon")
                .toProposition();

        assertEquals(LATER, p.getContentRevised());
        assertEquals(LATER, p.getLastContentRevision());
    }

    @Test
    void theCallersMetadataNeverSetsTheRecord() {
        Map<String, Object> claimed = Map.of(DiceMetadataKeys.CONTENT_REVISED, LATER.toString());

        assertEquals(REVISED_AT, revised().toDraft().withMetadata(Map.of()).toProposition().getLastContentRevision());
        assertEquals(REVISED_AT, revised().toDraft().withMetadata(claimed).toProposition().getLastContentRevision());
        assertNull(stored().toDraft().withMetadata(claimed).toProposition().getLastContentRevision());
    }

    @Test
    void aNewClaimHasNoRecord() {
        Map<String, Object> claimed = Map.of(DiceMetadataKeys.CONTENT_REVISED, LATER.toString());

        Proposition newId = revised().toDraft().withId("p-2").toProposition();
        Proposition generatedId = revised().toDraft().withId(null).toProposition();
        Proposition drafted = Proposition.draft("acme", "Alice lives in Paris", 0.9)
                .withMetadata(claimed)
                .toProposition();

        assertNull(newId.getLastContentRevision());
        assertFalse(newId.getMetadata().containsKey(DiceMetadataKeys.CONTENT_REVISED));
        assertNull(generatedId.getLastContentRevision());
        assertNull(drafted.getLastContentRevision());
        assertFalse(drafted.getMetadata().containsKey(DiceMetadataKeys.CONTENT_REVISED));
    }
}

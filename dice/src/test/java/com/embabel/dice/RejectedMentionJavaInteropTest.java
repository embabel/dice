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

import com.embabel.dice.common.Resolutions;
import com.embabel.dice.common.SuggestedEntityResolution;
import com.embabel.dice.pipeline.ChunkPropositionResult;
import com.embabel.dice.pipeline.PropositionResults;
import com.embabel.dice.pipeline.RejectedMention;
import com.embabel.dice.pipeline.RejectedMentionHandling;
import com.embabel.dice.proposition.SuggestedMention;
import com.embabel.dice.proposition.SuggestedPropositions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that a chunk result's rejected mentions can be built and read from Java.
 */
class RejectedMentionJavaInteropTest {

    private final SuggestedPropositions suggested = new SuggestedPropositions("c", List.of());
    private final Resolutions<SuggestedEntityResolution> resolutions = new Resolutions<>(Set.of("c"), List.of());
    private final RejectedMention rejected = new RejectedMention(
            "c",
            0,
            "Alice met Bob",
            new SuggestedMention("Carol", "Person", null, "OTHER"),
            "SPAN_NOT_IN_PROPOSITION",
            RejectedMention.Check.GROUNDING,
            RejectedMentionHandling.DROP);

    @Test
    void successKeepsItsConstructors() {
        ChunkPropositionResult.Success four = new ChunkPropositionResult.Success("c", suggested, resolutions, List.of());
        ChunkPropositionResult.Success five = new ChunkPropositionResult.Success("c", suggested, resolutions, List.of(), List.of());

        assertTrue(four.getRejectedMentions().isEmpty());
        assertTrue(five.getRejectedMentions().isEmpty());
    }

    @Test
    void successCarriesRejectedMentions() {
        ChunkPropositionResult.Success success = new ChunkPropositionResult.Success(
                "c", suggested, resolutions, List.of(), List.of(), List.of(rejected));

        RejectedMention first = success.getRejectedMentions().get(0);
        assertEquals("c", first.getChunkId());
        assertEquals(0, first.getPropositionIndex());
        assertEquals("Alice met Bob", first.getPropositionText());
        assertEquals("Carol", first.getMention().getSpan());
        assertEquals("SPAN_NOT_IN_PROPOSITION", first.getReason());
        assertEquals(RejectedMention.Check.GROUNDING, first.getCheck());
        assertEquals(RejectedMentionHandling.DROP, first.getHandling());

        ChunkPropositionResult.Success copied = success.copy("c", suggested, resolutions, List.of(), List.of());
        assertEquals(List.of(rejected), copied.getRejectedMentions());
    }

    @Test
    void everyResultHasTheList() {
        ChunkPropositionResult failed = ChunkPropositionResult.failed("c", new IllegalStateException("boom"));
        assertTrue(failed.getRejectedMentions().isEmpty());

        ChunkPropositionResult.Success success = new ChunkPropositionResult.Success(
                "c", suggested, resolutions, List.of(), List.of(), List.of(rejected));
        assertEquals(1, new PropositionResults(List.of(success), List.of()).getRejectedMentions().size());
    }
}

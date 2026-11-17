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

import com.embabel.agent.rag.model.Chunk;
import com.embabel.dice.common.SourceAnalysisContext;
import com.embabel.dice.common.filter.GroundedMentionFilter;
import com.embabel.dice.common.filter.MentionFilter;
import com.embabel.dice.common.filter.MentionFixtures;
import com.embabel.dice.pipeline.ChunkPropositionResult;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.pipeline.RejectedMention;
import com.embabel.dice.pipeline.RejectedMentionHandling;
import com.embabel.dice.pipeline.SuggestingPropositionExtractor;
import com.embabel.dice.proposition.SuggestedMention;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that a Java caller can give the proposition pipeline a mention filter with a handling,
 * replace its grounding check, and read the rejected mentions from the results.
 */
class PropositionPipelineScreeningJavaInteropTest {

    private final SuggestingPropositionExtractor extractor = SuggestingPropositionExtractor.of(
            MentionFixtures.proposition(
                    "Alice met Carol",
                    new SuggestedMention("Alice", "Person", null, "SUBJECT"),
                    new SuggestedMention("Carol", "Person", null, "OBJECT")));
    private final SourceAnalysisContext context = MentionFixtures.context();
    private final Chunk chunk = Chunk.create("any", "doc-1");

    @Test
    void lambdaFilterWithDropHandlingRemovesTheMention() {
        MentionFilter noCarols = (mention, text) -> !mention.getSpan().equals("Carol");

        ChunkPropositionResult result = PropositionPipeline.withExtractor(extractor)
                .withMentionFilter(noCarols, RejectedMentionHandling.DROP)
                .processChunk(chunk, context);

        assertEquals(1, result.getPropositions().get(0).getMentions().size());
        List<RejectedMention> rejected = result.getRejectedMentions();
        assertEquals(1, rejected.size());
        RejectedMention rejection = rejected.get(0);
        assertEquals(RejectedMention.Check.FILTER, rejection.getCheck());
        assertEquals(RejectedMentionHandling.DROP, rejection.getHandling());
        assertEquals(chunk.getId(), rejection.getChunkId());
        assertEquals(0, rejection.getPropositionIndex());
    }

    @Test
    void groundingCheckCanBeReplaced() {
        ChunkPropositionResult result = PropositionPipeline.withExtractor(extractor)
                .withMentionGrounding(new GroundedMentionFilter())
                .processChunk(chunk, context);

        assertTrue(result.getRejectedMentions().isEmpty());
    }

    @Test
    void resultsOfARunListRejectedMentions() {
        PropositionPipeline pipeline = PropositionPipeline.withExtractor(extractor)
                .withMentionFilter((mention, text) -> !mention.getSpan().equals("Carol"));

        assertEquals(1, pipeline.process(List.of(chunk), context).getRejectedMentions().size());
    }
}

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

import com.embabel.agent.core.DataDictionary;
import com.embabel.dice.common.SourceAnalysisContext;
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver;
import com.embabel.dice.pipeline.ChunkPropositionResult;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.pipeline.RecordingPropositionExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that the one-text call on the proposition pipeline works from Java and
 * returns a result that is never null.
 */
class PropositionPipelineProcessTextJavaInteropTest {

    private final RecordingPropositionExtractor extractor = new RecordingPropositionExtractor();
    private final PropositionPipeline pipeline = PropositionPipeline.withExtractor(extractor);
    private final SourceAnalysisContext context = SourceAnalysisContext
            .withContextId("acme")
            .withEntityResolver(AlwaysCreateEntityResolver.INSTANCE)
            .withSchema(DataDictionary.fromClasses("test"));

    @Test
    void processTextGroundsPropositionInSourceId() {
        ChunkPropositionResult result = pipeline.processText("Alice lives in Paris", "doc-1", context);

        assertEquals(1, result.getPropositions().size());
        assertEquals(List.of("doc-1"), result.getPropositions().get(0).getGrounding());
        assertEquals(1, extractor.getSeenChunks().size());
        assertEquals("doc-1", extractor.getSeenChunks().get(0).getId());
        assertEquals("Alice lives in Paris", extractor.getSeenChunks().get(0).getText());
    }

    @Test
    void processTextAddsAdditionalGroundingToPropositions() {
        ChunkPropositionResult result = pipeline.processText(
                "Alice lives in Paris", "doc-1", context, List.of("email:7"));

        assertEquals(1, result.getPropositions().size());
        assertEquals(List.of("doc-1", "email:7"), result.getPropositions().get(0).getGrounding());
    }

    @Test
    void processTextSendsSameChunkAsProcessOnce() {
        pipeline.processText("Alice lives in Paris", "doc-1", context);
        pipeline.processOnce("Alice lives in Paris", "doc-1", context);

        assertEquals(2, extractor.getSeenChunks().size());
        assertEquals(extractor.getSeenChunks().get(0).getId(), extractor.getSeenChunks().get(1).getId());
        assertEquals(extractor.getSeenChunks().get(0).getText(), extractor.getSeenChunks().get(1).getText());
        assertEquals(extractor.getSeenChunks().get(0).getParentId(), extractor.getSeenChunks().get(1).getParentId());
    }
}

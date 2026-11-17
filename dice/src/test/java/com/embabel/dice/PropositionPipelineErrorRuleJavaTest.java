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
import com.embabel.agent.rag.model.Chunk;
import com.embabel.dice.common.SourceAnalysisContext;
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver;
import com.embabel.dice.pipeline.ChunkPropositionResult;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.pipeline.PropositionResults;
import com.embabel.dice.proposition.PropositionExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Checks how the proposition pipeline treats errors thrown by the extractor, as seen from Java.
 * An Exception only costs the chunk it came from, while an Error stops the whole run.
 *
 * Contract, as the tests below read it:
 * - E1: an Exception thrown while extracting one chunk gives a Failed result for that chunk.
 * - E2: an Error thrown while extracting is not caught and reaches the caller of process.
 */
class PropositionPipelineErrorRuleJavaTest {

    private final SourceAnalysisContext context = SourceAnalysisContext
            .withContextId("acme")
            .withEntityResolver(AlwaysCreateEntityResolver.INSTANCE)
            .withSchema(DataDictionary.fromClasses("test"));

    private final List<Chunk> chunks = List.of(
            Chunk.create("Alice lives in Paris", "doc-1"));

    @Test
    void errorFromExtractorEndsTheRun() {
        PropositionExtractor extractor = mock(PropositionExtractor.class);
        when(extractor.extract(any(Chunk.class), any(SourceAnalysisContext.class)))
                .thenThrow(new AssertionError("extractor broke badly"));
        PropositionPipeline pipeline = PropositionPipeline.withExtractor(extractor);

        // E2: the Error is not swallowed, so the caller sees it.
        assertThrows(AssertionError.class, () -> pipeline.process(chunks, context));
    }

    @Test
    void exceptionFromExtractorGivesFailedResultForThatChunk() {
        PropositionExtractor extractor = mock(PropositionExtractor.class);
        when(extractor.extract(any(Chunk.class), any(SourceAnalysisContext.class)))
                .thenThrow(new IllegalStateException("extractor refused"));
        PropositionPipeline pipeline = PropositionPipeline.withExtractor(extractor);

        PropositionResults result = pipeline.process(chunks, context);

        // E1: one chunk in, one Failed result out, carrying that chunk's id.
        assertEquals(1, result.getChunkResults().size());
        ChunkPropositionResult chunkResult = result.getChunkResults().get(0);
        ChunkPropositionResult.Failed failed =
                assertInstanceOf(ChunkPropositionResult.Failed.class, chunkResult);
        assertEquals(chunks.get(0).getId(), failed.getChunkId());
        assertInstanceOf(IllegalStateException.class, failed.getCause());
    }
}

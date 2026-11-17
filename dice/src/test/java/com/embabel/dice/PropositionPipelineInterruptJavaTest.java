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
import com.embabel.dice.common.Resolutions;
import com.embabel.dice.common.SourceAnalysisContext;
import com.embabel.dice.common.SuggestedEntities;
import com.embabel.dice.common.SuggestedEntityResolution;
import com.embabel.dice.common.filter.MentionFilter;
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver;
import com.embabel.dice.pipeline.BatchedExtractionStrategy;
import com.embabel.dice.pipeline.ChunkPropositionResult;
import com.embabel.dice.pipeline.ExtractionExecutionStrategy;
import com.embabel.dice.pipeline.ParallelExtractionStrategy;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.pipeline.PropositionResults;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionExtractor;
import com.embabel.dice.proposition.SuggestedPropositions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that an interrupt raised while one chunk is extracted on a pool thread does not fail the
 * resolution of the other chunks, and that the caller still sees it when process returns.
 *
 * E1: with either concurrent strategy, the interrupted chunk is Failed, the healthy chunk is
 *     resolved to Success even though resolution sleeps, and the caller's flag is set afterwards.
 */
class PropositionPipelineInterruptJavaTest {

    private final SourceAnalysisContext context = SourceAnalysisContext
            .withContextId("acme")
            .withEntityResolver(AlwaysCreateEntityResolver.INSTANCE)
            .withSchema(DataDictionary.fromClasses("test"));

    private final List<Chunk> chunks = List.of(
            Chunk.create("one", "doc-1"),
            Chunk.create("two", "doc-1"));

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        Thread.interrupted();
        executor.shutdownNow();
    }

    /** Interrupted while extracting the chunk "one". Resolution sleeps, so it fails if interrupted. */
    static final class InterruptingExtractor implements PropositionExtractor {

        @Override
        public SuggestedPropositions extract(Chunk chunk, SourceAnalysisContext context) {
            if (chunk.getText().equals("one")) {
                Thread.currentThread().interrupt();
                sleep();
            }
            return new SuggestedPropositions(chunk.getId(), List.of());
        }

        @Override
        public SuggestedEntities toSuggestedEntities(SuggestedPropositions suggestedPropositions,
                                                     SourceAnalysisContext context,
                                                     String sourceText,
                                                     MentionFilter mentionFilter) {
            return new SuggestedEntities(List.of(), sourceText);
        }

        @Override
        public List<Proposition> resolvePropositions(SuggestedPropositions suggestedPropositions,
                                                     Resolutions<SuggestedEntityResolution> resolutions,
                                                     SourceAnalysisContext context) {
            sleep();
            return List.of();
        }

        private static void sleep() {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                throw PropositionPipelineInterruptJavaTest.sneaky(e);
            }
        }
    }

    @Test
    void e1_anInterruptedExtractionDoesNotFailTheOtherChunksResolution() {
        for (ExtractionExecutionStrategy strategy : List.of(
                new ParallelExtractionStrategy(executor),
                new BatchedExtractionStrategy(2, executor))) {
            PropositionPipeline pipeline = PropositionPipeline
                    .withExtractor(new InterruptingExtractor())
                    .withExecutionStrategy(strategy);

            PropositionResults result = pipeline.process(chunks, context);

            assertTrue(Thread.interrupted(), "the caller should see the interrupt when process returns");
            assertInstanceOf(ChunkPropositionResult.Failed.class, result.getChunkResults().get(0));
            assertInstanceOf(ChunkPropositionResult.Success.class, result.getChunkResults().get(1));
        }
    }

    @SuppressWarnings("unchecked")
    static <E extends Throwable> RuntimeException sneaky(Throwable t) throws E {
        throw (E) t;
    }
}

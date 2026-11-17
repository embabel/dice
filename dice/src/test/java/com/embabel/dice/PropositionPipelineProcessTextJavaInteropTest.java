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
import com.embabel.dice.common.support.Sha256ContentHasher;
import com.embabel.dice.incremental.HashKey;
import com.embabel.dice.incremental.InMemoryChunkHistoryStore;
import com.embabel.dice.pipeline.ChunkPropositionResult;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.pipeline.RecordingPropositionExtractor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the text calls on the proposition pipeline from Java: processText always gives a
 * result, and processOnce gives a result the first time a store sees the text and null after that.
 *
 * Contract, as the tests below read it:
 * - E1: processText returns a result for every call and records nothing in any store.
 * - E2: processOnce with no store returns a result and records nothing.
 * - E3: processOnce with a store returns a result the first time and null for the same text after.
 * - E4: after a successful processOnce, the store reports the text's hash as processed.
 * - E5: every path sends the extractor a chunk whose id is the source id.
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

    @Test
    void processOnceWithStoreReturnsResultThenNullForSameText() {
        InMemoryChunkHistoryStore store = new InMemoryChunkHistoryStore();

        ChunkPropositionResult first = pipeline.processOnce("Alice lives in Paris", "doc-1", context, store);
        ChunkPropositionResult second = pipeline.processOnce("Alice lives in Paris", "doc-1", context, store);

        assertNotNull(first);
        assertNull(second);
        assertEquals(1, extractor.getSeenChunks().size());
    }

    @Test
    void processOnceWithStoreRecordsTextHashAfterSuccess() {
        InMemoryChunkHistoryStore store = new InMemoryChunkHistoryStore();
        String text = "Alice lives in Paris";

        assertFalse(store.isProcessed(HashKey.of("acme", Sha256ContentHasher.INSTANCE.hash(text))));
        pipeline.processOnce(text, "doc-1", context, store);

        assertTrue(store.isProcessed(HashKey.of("acme", Sha256ContentHasher.INSTANCE.hash(text))));
    }

    @Test
    void processTextTwiceReturnsResultsAndLeavesFreshStoreEmpty() {
        InMemoryChunkHistoryStore store = new InMemoryChunkHistoryStore();
        String text = "Alice lives in Paris";

        ChunkPropositionResult first = pipeline.processText(text, "doc-1", context);
        ChunkPropositionResult second = pipeline.processText(text, "doc-1", context);

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(2, extractor.getSeenChunks().size());
        assertFalse(store.isProcessed(HashKey.of("acme", Sha256ContentHasher.INSTANCE.hash(text))));
    }

    @Test
    void everyTextPathSendsChunkWhoseIdIsTheSourceId() {
        InMemoryChunkHistoryStore store = new InMemoryChunkHistoryStore();

        pipeline.processText("Alice lives in Paris", "doc-1", context);
        pipeline.processOnce("Alice lives in Paris", "doc-1", context);
        pipeline.processOnce("Bob lives in Rome", "doc-1", context, store);

        assertEquals(3, extractor.getSeenChunks().size());
        for (var chunk : extractor.getSeenChunks()) {
            assertEquals("doc-1", chunk.getId());
        }
    }
}

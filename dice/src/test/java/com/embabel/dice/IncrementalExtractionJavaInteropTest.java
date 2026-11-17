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
import com.embabel.agent.rag.model.NamedEntity;
import com.embabel.agent.rag.model.SimpleNamedEntityData;
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository;
import com.embabel.chat.Message;
import com.embabel.chat.UserMessage;
import com.embabel.dice.common.Relations;
import com.embabel.dice.common.SourceAnalysisRequestEvent;
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver;
import com.embabel.dice.incremental.InMemoryChunkHistoryStore;
import com.embabel.dice.incremental.IncrementalSource;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.pipeline.RecordingPropositionExtractor;
import com.embabel.dice.projection.graph.GraphProjectionService;
import com.embabel.dice.projection.graph.NamedEntityDataRepositoryGraphRelationshipPersister;
import com.embabel.dice.projection.graph.RelationBasedGraphProjector;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.proposition.extraction.IncrementalPropositionExtraction;
import com.embabel.dice.proposition.extraction.PersistOutcome;
import com.embabel.dice.proposition.extraction.PersistStep;
import com.embabel.dice.proposition.extraction.PersistUnitFailedException;
import com.embabel.dice.proposition.extraction.PersistUnitWriter;
import com.embabel.dice.proposition.extraction.PropositionExtractionProperties;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Calls the extractor from Java and catches what a failed store throws.
 */
class IncrementalExtractionJavaInteropTest {

    private final DataDictionary schema = DataDictionary.fromClasses("java-extraction");

    private final InMemoryNamedEntityDataRepository entities = new InMemoryNamedEntityDataRepository(schema);

    private final NamedEntity user = new SimpleNamedEntityData(
            "java-extraction-tenant", null, "Alice", "A test user", Set.of("Person"), Map.of(), Map.of(), null);

    /**
     * Builds an extractor over a pipeline that returns one claim for any text.
     *
     * @param claims the proposition repository the extractor writes to
     * @return an extractor with nothing bound
     */
    private IncrementalPropositionExtraction extraction(PropositionRepository claims) {
        return new IncrementalPropositionExtraction(
                PropositionPipeline.withExtractor(new RecordingPropositionExtractor()),
                new InMemoryChunkHistoryStore(),
                schema,
                Relations.empty(),
                claims,
                entities,
                AlwaysCreateEntityResolver.INSTANCE,
                GraphProjectionService.create(
                        new RelationBasedGraphProjector(),
                        new NamedEntityDataRepositoryGraphRelationshipPersister(entities),
                        schema),
                new PropositionExtractionProperties());
    }

    @Test
    void aCheckedStoreFailureReachesAJavaCallerUnchecked() {
        IOException cause = new IOException("claims store unreachable");
        PropositionRepository claims = Mockito.mock(PropositionRepository.class);
        Mockito.doAnswer(invocation -> {
            throw cause;
        }).when(claims).saveAllReturningCanonical(Mockito.any());

        try {
            extraction(claims).rememberText("Alice lives in Paris", "doc-1", user);
            fail("Expected rememberText to throw");
        } catch (PersistUnitFailedException e) {
            assertEquals(PersistStep.PROPOSITIONS, e.getStep());
            assertSame(cause, e.getCause());
        }
    }

    @Test
    void aChunkWhoseWriteStoredNothingIsAnalysedAgain() {
        InMemoryPropositionRepository claims = new InMemoryPropositionRepository(null);
        PersistUnitWriter inMemory = PersistUnitWriter.inMemory(claims, entities);
        AtomicInteger writes = new AtomicInteger();
        IncrementalPropositionExtraction extraction = extraction(claims).withPersistUnitWriter(unit ->
                writes.incrementAndGet() == 1
                        ? PersistOutcome.failed(
                                PersistStep.PROPOSITIONS, new IllegalStateException("claims store down"), Set.of())
                        : inMemory.write(unit));
        SourceAnalysisRequestEvent event = new SixMessages(this, user);

        extraction.extractPropositions(event);
        assertTrue(claims.findAll().isEmpty());

        extraction.extractPropositions(event);
        assertEquals(1, claims.findAll().size());

        extraction.extractPropositions(event);
        assertEquals(2, writes.get());
    }

    /** An event over a source that holds the same six messages every time. */
    private static final class SixMessages extends SourceAnalysisRequestEvent {

        private final List<Message> messages = Collections.nCopies(6, new UserMessage("Alice lives in Paris"));

        SixMessages(Object source, NamedEntity user) {
            super(source, user);
        }

        @Override
        public IncrementalSource<Message> incrementalSource() {
            return new IncrementalSource<>() {
                @Override
                public String getId() {
                    return "java-conversation";
                }

                @Override
                public int getSize() {
                    return messages.size();
                }

                @Override
                public List<Message> getItems(int start, int end) {
                    return messages.subList(start, end);
                }
            };
        }
    }
}

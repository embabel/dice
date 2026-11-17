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
import com.embabel.dice.common.Relations;
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver;
import com.embabel.dice.incremental.InMemoryChunkHistoryStore;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.pipeline.RecordingPropositionExtractor;
import com.embabel.dice.projection.graph.GraphProjectionService;
import com.embabel.dice.projection.graph.NamedEntityDataRepositoryGraphRelationshipPersister;
import com.embabel.dice.projection.graph.RelationBasedGraphProjector;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.proposition.extraction.IncrementalPropositionExtraction;
import com.embabel.dice.proposition.extraction.PersistStep;
import com.embabel.dice.proposition.extraction.PersistUnitFailedException;
import com.embabel.dice.proposition.extraction.PropositionExtractionProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

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
}

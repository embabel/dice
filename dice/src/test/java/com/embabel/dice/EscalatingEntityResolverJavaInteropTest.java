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
import com.embabel.agent.rag.model.NamedEntityData;
import com.embabel.agent.rag.model.SimpleNamedEntityData;
import com.embabel.agent.rag.service.NamedEntityDataRepository;
import com.embabel.common.core.types.SimilarityResult;
import com.embabel.dice.common.EntityResolver;
import com.embabel.dice.common.NewEntity;
import com.embabel.dice.common.Resolutions;
import com.embabel.dice.common.SuggestedEntities;
import com.embabel.dice.common.SuggestedEntity;
import com.embabel.dice.common.SuggestedEntityResolution;
import com.embabel.dice.common.resolver.CandidateBakeoff;
import com.embabel.dice.common.resolver.EscalatingEntityResolver;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/*
 * Contract for the Java-facing factories on EscalatingEntityResolver.
 *
 * E1  create(repository) resolves a name nobody knows to one new entity, and it searches the vector index.
 * E2  withoutVector(repository) resolves a name nobody knows to one new entity, and it never searches the vector index.
 * E3  With two candidates and a bakeoff, create(repository, bakeoff) and withoutVector(repository, bakeoff) ask the bakeoff to pick.
 * E4  With two candidates and no bakeoff, both one-argument factories mint a new entity.
 */
class EscalatingEntityResolverJavaInteropTest {

    private final NamedEntityDataRepository repository = Mockito.mock(NamedEntityDataRepository.class);
    private final DataDictionary schema = DataDictionary.fromClasses("test");

    private final NamedEntityData brahms = new SimpleNamedEntityData(
            "brahms-123", null, "Johannes Brahms", "German composer",
            Set.of("Composer", "Person"), Map.of(), Map.of(), null);

    private final NamedEntityData wagner = new SimpleNamedEntityData(
            "wagner-456", null, "Richard Wagner", "German composer of operas",
            Set.of("Composer", "Person"), Map.of(), Map.of(), null);

    @Test
    void e1_createMintsANewEntityAndSearchesTheVectorIndex() {
        stubSearchesToFindNothing();
        EntityResolver resolver = EscalatingEntityResolver.create(repository);

        Resolutions<SuggestedEntityResolution> result = resolver.resolve(oneComposer("Gustav Mahler"), schema);

        assertOneNewEntity(result);
        verify(repository, atLeastOnce()).vectorSearch(any(), any(), any());
    }

    @Test
    void e2_withoutVectorMintsANewEntityAndNeverSearchesTheVectorIndex() {
        stubSearchesToFindNothing();
        EntityResolver resolver = EscalatingEntityResolver.withoutVector(repository);

        Resolutions<SuggestedEntityResolution> result = resolver.resolve(oneComposer("Gustav Mahler"), schema);

        assertOneNewEntity(result);
        verify(repository, never()).vectorSearch(any(), any(), any());
    }

    @Test
    void e3_createWithABakeoffLetsItPickFromTwoCandidates() {
        stubTwoCandidates();
        CandidateBakeoff bakeoff = Mockito.mock(CandidateBakeoff.class);
        EntityResolver resolver = EscalatingEntityResolver.create(repository, bakeoff);

        resolver.resolve(oneComposer("Gustav Mahler"), schema);

        verify(bakeoff).selectBestMatch(any(), any(), any());
    }

    @Test
    void e3_withoutVectorWithABakeoffLetsItPickFromTwoCandidates() {
        stubTwoCandidates();
        CandidateBakeoff bakeoff = Mockito.mock(CandidateBakeoff.class);
        EntityResolver resolver = EscalatingEntityResolver.withoutVector(repository, bakeoff);

        resolver.resolve(oneComposer("Gustav Mahler"), schema);

        verify(bakeoff).selectBestMatch(any(), any(), any());
    }

    @Test
    void e4_createWithoutABakeoffMintsANewEntityForTwoCandidates() {
        stubTwoCandidates();
        EntityResolver resolver = EscalatingEntityResolver.create(repository);

        Resolutions<SuggestedEntityResolution> result = resolver.resolve(oneComposer("Gustav Mahler"), schema);

        assertOneNewEntity(result);
    }

    @Test
    void e4_withoutVectorWithoutABakeoffMintsANewEntityForTwoCandidates() {
        stubTwoCandidates();
        EntityResolver resolver = EscalatingEntityResolver.withoutVector(repository);

        Resolutions<SuggestedEntityResolution> result = resolver.resolve(oneComposer("Gustav Mahler"), schema);

        assertOneNewEntity(result);
    }

    private void stubSearchesToFindNothing() {
        when(repository.textSearch(any(), any(), any())).thenReturn(List.of());
        when(repository.vectorSearch(any(), any(), any())).thenReturn(List.of());
    }

    private void stubTwoCandidates() {
        List<SimilarityResult<NamedEntityData>> hits = List.of(
                SimilarityResult.create(brahms, 0.8),
                SimilarityResult.create(wagner, 0.8));
        when(repository.textSearch(any(), any(), any())).thenReturn(hits);
        when(repository.vectorSearch(any(), any(), any())).thenReturn(List.of());
    }

    private static SuggestedEntities oneComposer(String name) {
        SuggestedEntity suggested = new SuggestedEntity(
                List.of("Composer"), name, "", "chunk-1", null, Map.of(), 1.0, 0.0, 0.5, "source_analyzer");
        return new SuggestedEntities(List.of(suggested), "Some conversation about composers");
    }

    private static void assertOneNewEntity(Resolutions<SuggestedEntityResolution> result) {
        List<SuggestedEntityResolution> resolutions = result.getResolutions();
        assertEquals(1, resolutions.size());
        assertInstanceOf(NewEntity.class, resolutions.get(0));
    }
}

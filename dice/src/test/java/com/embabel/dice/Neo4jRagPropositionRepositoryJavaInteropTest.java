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
import com.embabel.agent.rag.service.NamedEntityDataRepository;
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.PropositionStore;
import com.embabel.dice.proposition.VectorSearchCapable;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import com.embabel.dice.proposition.store.Neo4jRagPropositionRepository;
import com.embabel.dice.proposition.store.RagAdapterPropositionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Shows that Java code can build and use the store under both names: the current one, and the old
 * one, which is now a deprecated subclass of RagAdapterPropositionRepository.
 */
@SuppressWarnings("deprecation")
class Neo4jRagPropositionRepositoryJavaInteropTest {

    @Test
    void newNameBuildsAWorkingStoreFromJava() {
        NamedEntityDataRepository entityRepo = entityRepository();
        RagAdapterPropositionRepository store =
                new RagAdapterPropositionRepository(new InMemoryPropositionRepository(), entityRepo);

        assertSavesAndFinds(store);
        assertInstanceOf(VectorSearchCapable.class, store);
        assertSame(entityRepo, store.getEntityRepository());
    }

    @Test
    void oldNameIsVisibleToJavaAndBehavesLikeTheNewOne() {
        NamedEntityDataRepository entityRepo = entityRepository();
        Neo4jRagPropositionRepository store =
                new Neo4jRagPropositionRepository(new InMemoryPropositionRepository(), entityRepo);

        RagAdapterPropositionRepository asNew = store;

        assertSavesAndFinds(store);
        assertInstanceOf(VectorSearchCapable.class, store);
        assertSame(entityRepo, asNew.getEntityRepository());
        assertSame(entityRepo, store.getEntityRepository());
    }

    private static NamedEntityDataRepository entityRepository() {
        return new InMemoryNamedEntityDataRepository(DataDictionary.fromClasses("java-interop"), null);
    }

    private static void assertSavesAndFinds(PropositionStore store) {
        Instant now = Instant.now();
        Proposition saved = store.save(Proposition.create(
                "java-interop-id",
                "java-interop",
                "Alice is a software engineer",
                List.of(),
                0.8,
                0.0,
                0.5,
                null,
                List.of(),
                now,
                now,
                now,
                PropositionStatus.ACTIVE
        ));

        Proposition found = store.findById(saved.getId());
        assertNotNull(found);
        assertEquals("Alice is a software engineer", found.getText());
    }
}

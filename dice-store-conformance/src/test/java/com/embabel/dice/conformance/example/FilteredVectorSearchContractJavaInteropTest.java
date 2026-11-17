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
package com.embabel.dice.conformance.example;

import com.embabel.dice.conformance.AbstractFilteredVectorSearchContractTest;
import com.embabel.dice.conformance.ConformanceEmbeddingService;
import com.embabel.dice.proposition.PropositionStore;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;
import com.embabel.dice.store.VectorFiltering;

/**
 * A backend's test for similarity search, written in Java: the store embeds with the kit's
 * embedding service, and the test names the vector filtering mode the store declares.
 */
class FilteredVectorSearchContractJavaInteropTest extends AbstractFilteredVectorSearchContractTest {

    @Override
    protected PropositionStore store() {
        return new InMemoryPropositionRepository(new ConformanceEmbeddingService());
    }

    @Override
    protected VectorFiltering expectedVectorFiltering() {
        return VectorFiltering.UNDECLARED;
    }
}

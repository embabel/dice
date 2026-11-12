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

import com.embabel.dice.conformance.AbstractPropositionRunLinkStoreContractTest;
import com.embabel.dice.proposition.extraction.InMemoryExtractionRunStore;
import com.embabel.dice.proposition.extraction.InMemoryPropositionRunLinkStore;
import com.embabel.dice.proposition.extraction.PropositionRunLinkStore;
import com.embabel.dice.proposition.store.InMemoryPropositionRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * A backend's test for {@code PropositionRunLinkStore}, written in Java. The store seeds the same
 * runs and propositions the Kotlin reference does, then hands them to the link store.
 */
class PropositionRunLinkStoreContractJavaInteropTest extends AbstractPropositionRunLinkStoreContractTest {

    private InMemoryExtractionRunStore runs;
    private InMemoryPropositionRepository propositions;

    @Override
    protected PropositionRunLinkStore store() {
        runs = new InMemoryExtractionRunStore();
        propositions = new InMemoryPropositionRepository();
        List.of(getTenantValue(), getNeighbourValue()).forEach(context ->
                getFixtureRunIds().forEach(runId -> runs.save(runIn(runId, context))));
        List<String> tenantPropositionIds = new ArrayList<>(getFixturePropositionIds());
        tenantPropositionIds.add(getDisposablePropositionId());
        tenantPropositionIds.forEach(id -> propositions.save(propositionIn(id, getTenantValue())));
        getNeighbourPropositionIds().forEach(id -> propositions.save(propositionIn(id, getNeighbourValue())));
        return new InMemoryPropositionRunLinkStore(runs, propositions);
    }

    @Override
    protected void deleteProposition(String id) {
        propositions.delete(id);
    }
}

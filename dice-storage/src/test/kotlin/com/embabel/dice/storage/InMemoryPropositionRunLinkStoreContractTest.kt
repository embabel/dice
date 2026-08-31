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
package com.embabel.dice.storage

import com.embabel.dice.proposition.extraction.InMemoryExtractionRunStore
import com.embabel.dice.proposition.extraction.InMemoryPropositionRunLinkStore
import com.embabel.dice.proposition.extraction.PropositionRunLinkStore
import com.embabel.dice.proposition.store.InMemoryPropositionRepository

/**
 * The reference implementation against the cross-backend contract. It is the executable statement
 * of what the Drivine store is held to, so it runs the same suite from the same fixtures.
 */
class InMemoryPropositionRunLinkStoreContractTest : AbstractPropositionRunLinkStoreContractTest() {

    private lateinit var propositions: InMemoryPropositionRepository

    override fun store(): PropositionRunLinkStore {
        val runs = InMemoryExtractionRunStore()
        propositions = InMemoryPropositionRepository()
        listOf(tenant, neighbour).forEach { context ->
            fixtureRunIds.forEach { runs.save(run(it, context)) }
        }
        (fixturePropositionIds + disposablePropositionId).forEach {
            propositions.save(proposition(it, tenant))
        }
        neighbourPropositionIds.forEach { propositions.save(proposition(it, neighbour)) }
        return InMemoryPropositionRunLinkStore(runs, propositions)
    }

    override fun deleteProposition(id: String) {
        propositions.delete(id)
    }
}

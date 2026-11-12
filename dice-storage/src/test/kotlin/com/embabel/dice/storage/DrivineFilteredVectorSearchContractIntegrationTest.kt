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

import com.embabel.dice.conformance.AbstractFilteredVectorSearchContractTest
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.store.VectorFiltering
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Runs the filtered vector search suite against the Neo4j-backed [DrivinePropositionRepository]
 * (testcontainer). The graph store publishes no capability descriptor, so the suite checks that it
 * declares [VectorFiltering.UNDECLARED] and skips the cases that need a filtering mode.
 */
@SpringBootTest(classes = [TestApplication::class])
class DrivineFilteredVectorSearchContractIntegrationTest : AbstractFilteredVectorSearchContractTest() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }

    @Autowired
    private lateinit var repository: DrivinePropositionRepository

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    override fun store(): PropositionStore = repository

    override fun expectedVectorFiltering(): VectorFiltering = VectorFiltering.UNDECLARED

    @AfterEach
    fun cleanUp() {
        repository.clearAll()
        persistenceManager.execute(QuerySpecification.withStatement("MATCH (s:Source) DETACH DELETE s"))
    }
}

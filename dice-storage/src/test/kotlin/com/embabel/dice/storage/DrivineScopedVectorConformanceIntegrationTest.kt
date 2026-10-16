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

import com.embabel.dice.proposition.ContextSetVectorSearchCapable
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.store.AbstractScopedVectorConformanceTest
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager

/**
 * Runs the shared scoped vector suite against Neo4j. Rows are saved through the configured store, then
 * given the suite's vectors directly, and searched through a store whose embedder knows the query.
 */
@SpringBootTest(classes = [TestApplication::class])
class DrivineScopedVectorConformanceIntegrationTest : AbstractScopedVectorConformanceTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }

    @Autowired private lateinit var repository: DrivinePropositionRepository
    @Autowired private lateinit var manager: PersistenceManager
    @Autowired private lateinit var graph: StatelessGraphObjectManager
    @Autowired private lateinit var registry: DatabaseRegistry
    @Autowired private lateinit var sources: DataSourceMap
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    override fun store(rows: List<Pair<Proposition, FloatArray>>, query: FloatArray): ContextSetVectorSearchCapable {
        repository.clearAll()
        rows.forEach { (row, vector) ->
            repository.save(row)
            manager.execute(
                QuerySpecification.withStatement("MATCH (p:Proposition {id: \$id}) SET p.embedding = \$vector")
                    .bind(mapOf("id" to row.id, "vector" to vector.toList())),
            )
        }
        return DrivinePropositionRepository(graph, manager, embedder(rows, query), transactionManager, "retained-test-index")
            .withNativeReadExecutor(Neo4jScopedReadExecutor.from(manager, registry, sources))
    }

    @AfterEach
    fun clean() {
        repository.clearAll()
    }
}

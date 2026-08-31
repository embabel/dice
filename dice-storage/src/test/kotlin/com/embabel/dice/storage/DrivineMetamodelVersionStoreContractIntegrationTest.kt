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

import com.embabel.dice.metamodel.MetamodelVersionStore
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Runs the [AbstractMetamodelVersionStoreContractTest] suite against the Neo4j-backed
 * [DrivineMetamodelVersionStore] (testcontainer). This is the half that catches the graph backend
 * disagreeing with the in-memory reference on the provenance rules, which is easy to do: they live
 * in a Cypher `coalesce` there and in Kotlin here.
 *
 * Uses the shared [Neo4jTestContainer]; see that class for why Drivine's built-in testcontainer
 * is bypassed.
 */
@SpringBootTest(classes = [TestApplication::class])
class DrivineMetamodelVersionStoreContractIntegrationTest : AbstractMetamodelVersionStoreContractTest() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }

    @Autowired
    private lateinit var graphStore: DrivineMetamodelVersionStore

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    override fun store(): MetamodelVersionStore = graphStore

    @AfterEach
    fun cleanUp() {
        listOf("MetamodelVersion", "MetamodelSchemaCounter").forEach { label ->
            persistenceManager.execute(QuerySpecification.withStatement("MATCH (n:$label) DETACH DELETE n"))
        }
    }
}

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

import com.embabel.dice.proposition.extraction.ExtractionRunStore
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Runs the [AbstractExtractionRunStoreContractTest] suite against the Neo4j-backed
 * [DrivineExtractionRunStore] (testcontainer). This is the half that catches the graph backend
 * disagreeing with the in-memory reference, which is easy to do here: the state machine lives in a
 * Cypher `FOREACH` over a conditional list there and in Kotlin `if`s in the reference, and the
 * idempotency rule is a stored string on one side and a map entry on the other.
 *
 * One store bean serves every call, wiped between cases, which is the arrangement
 * [DrivinePropositionStoreContractIntegrationTest] uses. The suite calls [store] more than once
 * inside a few cases and expects a store holding nothing for its tenants each time; it gets that
 * because those cases mint a distinct run id per iteration and only ever read by key.
 */
@SpringBootTest(classes = [TestApplication::class])
class DrivineExtractionRunStoreContractIntegrationTest : AbstractExtractionRunStoreContractTest() {

    @Autowired
    private lateinit var graphStore: DrivineExtractionRunStore

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    override fun store(): ExtractionRunStore = graphStore

    @AfterEach
    fun cleanUp() {
        ExtractionRunSchema.LABELS.forEach { label ->
            persistenceManager.execute(QuerySpecification.withStatement("MATCH (n:$label) DETACH DELETE n"))
        }
    }
}

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

import com.embabel.dice.proposition.ContextSetReadCapable
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.store.AbstractScopedReadConformanceTest
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** Runs the shared scoped read suite against Neo4j, through the configured Drivine store. */
@SpringBootTest(classes = [TestApplication::class])
class DrivineScopedReadConformanceIntegrationTest : AbstractScopedReadConformanceTest() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }

    @Autowired private lateinit var repository: DrivinePropositionRepository

    override fun store(rows: List<Proposition>): ContextSetReadCapable {
        repository.clearAll()
        rows.forEach { repository.save(it) }
        return repository
    }

    @AfterEach
    fun clean() {
        repository.clearAll()
    }
}

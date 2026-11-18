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
package com.embabel.dice.storage.sqlite

import com.embabel.dice.storage.StoreErrors
import com.embabel.dice.storage.TestApplication
import com.embabel.dice.storage.cypherQuery
import org.drivine.manager.PersistenceManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** A write SQLite turns away for a uniqueness constraint is told apart from every other failure. */
@SpringBootTest(classes = [TestApplication::class])
class SqliteUniquenessViolationIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun sqliteProperties(registry: DynamicPropertyRegistry) = SqliteTestStore.registerProperties(registry)
    }

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    @AfterEach
    fun cleanUp() {
        persistenceManager.execute(cypherQuery("MATCH (p:Proposition) DETACH DELETE p"))
    }

    @Test
    fun `a second proposition with the same context and text is a uniqueness violation`() {
        val create = "CREATE (:Proposition {id: ${'$'}id, contextId: 'unique-ctx', text: 'The ghost sings'})"
        persistenceManager.execute(cypherQuery(create).bind(mapOf("id" to "p-1")))

        val failure = assertThrows<RuntimeException> {
            persistenceManager.execute(cypherQuery(create).bind(mapOf("id" to "p-2")))
        }

        assertTrue(StoreErrors.isUniquenessViolation(failure), "not recognised: $failure")
    }
}

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

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.storage.DrivineObservedSchemaSource
import com.embabel.dice.storage.DrivinePropositionRepository
import com.embabel.dice.storage.TestApplication
import com.embabel.dice.storage.cypherQuery
import org.drivine.manager.PersistenceManager
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** Observes the whole graph, the observation with no context, on Drivine's SQLite backend. */
@SpringBootTest(classes = [TestApplication::class])
class SqliteObservedSchemaSourceIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun sqliteProperties(registry: DynamicPropertyRegistry) = SqliteTestStore.registerProperties(registry)
    }

    @Autowired
    private lateinit var source: DrivineObservedSchemaSource

    @Autowired
    private lateinit var repository: DrivinePropositionRepository

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    @AfterEach
    fun cleanUp() {
        persistenceManager.execute(cypherQuery("MATCH (n) DETACH DELETE n"))
    }

    @Test
    fun `whole-graph observation reports the domain's labels and relationship types`() {
        persistenceManager.execute(
            cypherQuery("CREATE (:Haunt {id: 'h1'})-[:HAUNTED_BY {sourcePropositions: ['p-a']}]->(:Spirit {id: 's1'})"),
        )

        val observed = source.observe()

        assertTrue(observed.entityTypeNames.containsAll(setOf("Haunt", "Spirit")), "got ${observed.entityTypeNames}")
        assertTrue(observed.relationshipTypeNames.contains("HAUNTED_BY"), "got ${observed.relationshipTypeNames}")
    }

    @Test
    fun `whole-graph observation leaves out dice's own labels`() {
        repository.save(
            Proposition(
                contextId = ContextId("observe-ctx"),
                text = "The ghost walks the hall",
                mentions = listOf(EntityMention(span = "the ghost", type = "Ghost", role = MentionRole.SUBJECT)),
                confidence = 0.9,
            ),
        )

        val observed = source.observe()

        assertFalse(observed.entityTypeNames.contains("Proposition"), "got ${observed.entityTypeNames}")
        assertTrue(observed.mentionTypeNames.contains("Ghost"), "got ${observed.mentionTypeNames}")
    }
}

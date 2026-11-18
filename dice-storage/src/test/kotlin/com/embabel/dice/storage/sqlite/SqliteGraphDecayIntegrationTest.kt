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
import com.embabel.dice.storage.DrivinePropositionRepository
import com.embabel.dice.storage.GraphDecayManager
import com.embabel.dice.storage.TestApplication
import com.embabel.dice.storage.cypherQuery
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.concurrent.atomic.AtomicInteger

/**
 * The decay sweep on Drivine's SQLite backend. SQLite has one writer at a time, so a sweep written
 * as one statement would keep every save waiting until it finished. It commits a batch at a time.
 */
@SpringBootTest(classes = [TestApplication::class])
class SqliteGraphDecayIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun sqliteProperties(registry: DynamicPropertyRegistry) = SqliteTestStore.registerProperties(registry)
    }

    @Autowired
    private lateinit var repository: DrivinePropositionRepository

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    @AfterEach
    fun cleanUp() {
        repository.clearAll()
    }

    @Test
    fun `a sweep refreshes every proposition, a batch at a time`() {
        val context = ContextId("decay-ctx")
        repeat(5) { i ->
            repository.save(
                Proposition(
                    contextId = context,
                    text = "The ghost was seen in room $i",
                    mentions = listOf(EntityMention(span = "the ghost", type = "Ghost", role = MentionRole.SUBJECT)),
                    confidence = 0.9,
                ),
            )
        }
        val counting = CountingPersistenceManager(persistenceManager)

        GraphDecayManager(repository, counting, batchRows = 2).materialize(context)

        val refreshed = persistenceManager.query(
            cypherQuery("MATCH (p:Proposition) WHERE p.decayUpdatedAt IS NOT NULL RETURN p.id").transform(String::class.java),
        )
        assertEquals(5, refreshed.size)
        assertTrue(counting.writes.get() >= 3, "expected one write per batch of two, saw ${counting.writes.get()}")
    }
}

/** Passes every call on and counts the statements written with [execute]. */
private class CountingPersistenceManager(private val delegate: PersistenceManager) : PersistenceManager by delegate {
    val writes = AtomicInteger()

    override fun execute(spec: QuerySpecification<*>) {
        writes.incrementAndGet()
        delegate.execute(spec)
    }
}

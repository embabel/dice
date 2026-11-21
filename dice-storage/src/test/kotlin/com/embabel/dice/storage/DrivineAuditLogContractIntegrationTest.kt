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

import com.embabel.agent.core.ContextId
import com.embabel.dice.audit.AuditCoverageException
import com.embabel.dice.audit.AuditEntry
import com.embabel.dice.audit.AuditKind
import com.embabel.dice.audit.AuditedProposition
import com.embabel.dice.audit.AuditingPropositionRepository
import com.embabel.dice.conformance.AbstractAuditLogContractTest
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.storage.sqlite.SqliteTestStore
import org.drivine.manager.PersistenceManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@TestConfiguration(proxyBeanMethods = false)
class AuditTestConfiguration {
    @Bean
    fun auditSchema(): DiceStorageSchema = AuditLogSchema
}

abstract class DrivineAuditLogContractCases : AbstractAuditLogContractTest() {

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var propositions: DrivinePropositionRepository

    private val recordedAt = Instant.parse("2026-01-02T00:00:00.000000001Z")
    private val auditClock = Clock.fixed(recordedAt, ZoneOffset.UTC)

    override fun log() = DrivineAuditLogStore(persistenceManager, transactionManager, auditClock)

    @Test
    fun `a missing committed create cannot produce a partial belief set`() {
        val store = log()
        val contextId = UUID.randomUUID().toString()
        val live = Proposition(id = UUID.randomUUID().toString(), contextId = ContextId(contextId),
            text = "Missing durable event", mentions = emptyList(), confidence = 0.8)
        store.initializeBaseline(contextId, recordedAt, emptyList())
        store.append(contextId, AuditEntry(AuditKind.CREATED, live.id, after = AuditedProposition.from(live)))
        persistenceManager.execute(
            cypherQuery("MATCH (n:DiceAuditRecord {contextId: ${'$'}contextId}) DELETE n")
                .bind(mapOf("contextId" to contextId)),
        )
        assertThrows(AuditCoverageException::class.java) { store.beliefSetAsOf(contextId, recordedAt) }
    }

    @Test
    fun `a joining sink rolls the proposition and audit back together and a new instance sees committed history`() {
        val store = log()
        val contextId = UUID.randomUUID().toString()
        val live = Proposition(id = UUID.randomUUID().toString(), contextId = ContextId(contextId),
            text = "Durable proposition", mentions = emptyList(), confidence = 0.8)
        val repo = AuditingPropositionRepository.wrapping(propositions, store)
        assertThrows(IllegalStateException::class.java) {
            store.duringWrite(contextId) {
                store.initializeBaseline(contextId, recordedAt, emptyList())
                repo.save(live)
                throw IllegalStateException("rollback")
            }
        }
        assertNull(repo.findById(live.id))
        assertEquals(emptyList<Any>(), store.query(contextId, 0, 10))
        assertThrows(AuditCoverageException::class.java) { store.beliefSetAsOf(contextId, recordedAt) }

        store.initializeBaseline(contextId, recordedAt, emptyList())
        repo.save(live)
        assertEquals(live.text, log().asOf(contextId, live.id, recordedAt)?.text)
        assertEquals(live.id, repo.save(live.copy(id = UUID.randomUUID().toString())).id)
        assertEquals(1, log().history(contextId, live.id).size)
    }
}

@SpringBootTest(classes = [TestApplication::class, AuditTestConfiguration::class])
class DrivineAuditLogContractIntegrationTest : DrivineAuditLogContractCases() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }
}

@SpringBootTest(classes = [TestApplication::class, AuditTestConfiguration::class])
class SqliteAuditLogContractIntegrationTest : DrivineAuditLogContractCases() {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun sqliteProperties(registry: DynamicPropertyRegistry) = SqliteTestStore.registerProperties(registry)
    }
}

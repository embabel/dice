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
import com.embabel.dice.common.ExtractionRunTransitioned
import com.embabel.dice.common.PropositionRejected
import com.embabel.dice.audit.AuditCoverageException
import com.embabel.dice.audit.AuditContextProvider
import com.embabel.dice.audit.AuditAttribution
import com.embabel.dice.audit.InMemoryAuditLog
import com.embabel.dice.audit.AuditEntry
import com.embabel.dice.audit.AuditEventListener
import com.embabel.dice.audit.AuditFailurePolicy
import com.embabel.dice.audit.AuditRunKind
import com.embabel.dice.audit.AuditRunRef
import com.embabel.dice.audit.AuditKind
import com.embabel.dice.audit.AuditedProposition
import com.embabel.dice.audit.AuditingPropositionRepository
import com.embabel.dice.conformance.AbstractAuditLogContractTest
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.extraction.ExtractionRun
import com.embabel.dice.proposition.extraction.ExtractionRunLineage
import com.embabel.dice.proposition.extraction.ExtractionRunRef
import com.embabel.dice.proposition.extraction.ExtractionRunStatus
import com.embabel.dice.storage.sqlite.SqliteTestStore
import com.embabel.dice.temporal.TemporalMetadata
import org.drivine.manager.PersistenceManager
import org.drivine.schema.SchemaCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestConfiguration(proxyBeanMethods = false)
class AuditTestConfiguration {
    @Bean
    fun auditSchema(): DiceStorageSchema = AuditLogSchema.forRegistration()
}

abstract class DrivineAuditLogContractCases : AbstractAuditLogContractTest() {

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var propositions: DrivinePropositionRepository

    @Autowired
    private lateinit var registeredSchemas: List<DiceStorageSchema>

    @Autowired
    private lateinit var registeredCatalogs: List<SchemaCatalog>

    @Test
    fun `explicit registration contributes audit constraints to schema and ownership`() {
        val auditSpecs = AuditLogSchema.forRegistration().specs()
        assertEquals(true, registeredSchemas.flatMap { it.specs() }.containsAll(auditSpecs))
        assertEquals(true, registeredCatalogs.flatMap { it.items }.containsAll(auditSpecs))
    }

    private val recordedAt = Instant.parse("2026-01-02T00:00:00.000000001Z")
    private val auditClock = Clock.fixed(recordedAt, ZoneOffset.UTC)

    override fun log() = DrivineAuditLogStore(persistenceManager, transactionManager, auditClock)

    @Test
    fun `a terminal event emitted after host commit is recorded in its own transaction`() {
        val store = log()
        val contextId = UUID.randomUUID().toString()
        val key = ExtractionRunRef("run-terminal-audit")
        val run = ExtractionRun(
            ContextId(contextId), ExtractionRunLineage.root(key), ExtractionRunStatus.COMPLETED,
            recordedAt.minusSeconds(1), finishedAt = recordedAt,
        )
        val listener = AuditEventListener(store)
        TransactionTemplate(transactionManager).executeWithoutResult {
            store.initializeBaseline(contextId, recordedAt, emptyList())
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() {
                    listener.onEvent(ExtractionRunTransitioned(run))
                }
            })
            assertEquals(emptyList<Any>(), store.query(contextId, 0, 10))
        }
        val event = store.query(contextId, 0, 10).single()
        assertEquals(AuditKind.EXTRACTION_RUN_ENDED, event.kind)
        assertEquals(AuditRunRef(AuditRunKind.EXTRACTION, key.runId), event.attribution.run)
    }

    @Test
    fun `a caught strict verdict provider failure prevents a host commit`() {
        val store = log()
        val contextId = UUID.randomUUID().toString()
        val live = Proposition(id = UUID.randomUUID().toString(), contextId = ContextId(contextId),
            text = "Must roll back", mentions = emptyList(), confidence = 0.8)
        val repo = AuditingPropositionRepository.wrapping(
            propositions, store, AuditContextProvider.NONE, AuditFailurePolicy.FAIL_WRITE, propositions.auditCanonicalLookup(),
        )
        val listener = AuditEventListener(store, AuditContextProvider { error("attribution unavailable") })
        store.initializeBaseline(contextId, recordedAt, emptyList())

        assertThrows(IllegalStateException::class.java) {
            TransactionTemplate(transactionManager).executeWithoutResult {
                repo.save(live)
                assertThrows(IllegalStateException::class.java) {
                    listener.onEvent(PropositionRejected(live.copy(id = "rejected"), "evidence_floor"))
                }
            }
        }
        assertNull(repo.findById(live.id))
        assertEquals(emptyList<Any>(), store.query(contextId, 0, 10))
    }

    @Test
    fun `an outer transaction cannot commit an unaudited change after catching a strict audit failure`() {
        val store = log()
        val contextId = UUID.randomUUID().toString()
        val live = Proposition(id = UUID.randomUUID().toString(), contextId = ContextId(contextId),
            text = "Audit failure", mentions = emptyList(), confidence = 0.8)
        val repo = AuditingPropositionRepository.wrapping(propositions, store,
            AuditContextProvider { error("attribution unavailable") })
        store.initializeBaseline(contextId, recordedAt, emptyList())

        assertThrows(IllegalStateException::class.java) {
            TransactionTemplate(transactionManager).executeWithoutResult {
                assertThrows(IllegalStateException::class.java) { repo.save(live) }
            }
        }
        assertNull(repo.findById(live.id))
        assertEquals(emptyList<Any>(), store.query(contextId, 0, 10))
        assertNull(store.asOf(contextId, live.id, recordedAt))
    }

    @Test
    fun `an in-memory sink captures durable canonical deduplication without a context scan`() {
        val store = InMemoryAuditLog(auditClock)
        val contextId = UUID.randomUUID().toString()
        val live = Proposition(id = UUID.randomUUID().toString(), contextId = ContextId(contextId),
            text = "Durable duplicate", mentions = emptyList(), confidence = 0.8)
        val repo = AuditingPropositionRepository.wrapping(
            propositions, store, AuditContextProvider.NONE, AuditFailurePolicy.FAIL_WRITE, propositions.auditCanonicalLookup(),
        )
        store.initializeBaseline(contextId, recordedAt, emptyList())
        repo.save(live)

        assertEquals(live.id, repo.save(live.copy(id = UUID.randomUUID().toString())).id)
        assertEquals(listOf(AuditKind.CREATED), store.history(contextId, live.id).map { it.kind })
        assertEquals(live.id, store.asOf(contextId, live.id, recordedAt)?.id)
    }

    @Test
    fun `audit tracks authoritative temporal postimages when Drivine normalizes a save`() {
        val store = log()
        val contextId = UUID.randomUUID().toString()
        val emptyTemporal = Proposition(id = UUID.randomUUID().toString(), contextId = ContextId(contextId),
            text = "Empty temporal metadata", mentions = emptyList(), confidence = 0.8, temporal = TemporalMetadata())
        val dated = emptyTemporal.copy(id = UUID.randomUUID().toString(), text = "Observed fact",
            temporal = TemporalMetadata(observedAt = recordedAt))
        val repo = AuditingPropositionRepository.wrapping(
            propositions, store, AuditContextProvider.NONE, AuditFailurePolicy.FAIL_WRITE, propositions.auditCanonicalLookup(),
        )
        store.initializeBaseline(contextId, recordedAt, emptyList())
        repo.saveAll(listOf(emptyTemporal, dated))
        assertNull(repo.findById(emptyTemporal.id)?.temporal)
        assertNull(store.asOf(contextId, emptyTemporal.id, recordedAt)?.temporal)
        assertEquals(dated.temporal, store.asOf(contextId, dated.id, recordedAt)?.temporal)

        repo.saveAll(listOf(emptyTemporal, dated))
        repo.pin(emptyTemporal.id)
        assertEquals(true, store.asOf(contextId, emptyTemporal.id, recordedAt)?.pinned)
        assertEquals(listOf(AuditKind.CREATED, AuditKind.PINNED), store.history(contextId, emptyTemporal.id).map { it.kind })
        assertEquals(listOf(AuditKind.CREATED), store.history(contextId, dated.id).map { it.kind })
    }

    @Test
    fun `rolling back a context erase restores its live rows and audit content`() {
        val store = log()
        val contextId = UUID.randomUUID().toString()
        val live = Proposition(id = UUID.randomUUID().toString(), contextId = ContextId(contextId),
            text = "Erasure rollback", mentions = emptyList(), confidence = 0.8)
        val repo = AuditingPropositionRepository.wrapping(
            propositions, store, AuditContextProvider.NONE, AuditFailurePolicy.FAIL_WRITE, propositions.auditCanonicalLookup(),
        )
        store.initializeBaseline(contextId, recordedAt, emptyList())
        repo.save(live)
        val original = store.history(contextId, live.id)

        assertThrows(IllegalStateException::class.java) {
            TransactionTemplate(transactionManager).executeWithoutResult {
                assertEquals(1, repo.clearByContext(contextId))
                assertEquals(AuditKind.ERASURE, store.query(contextId, 0, 10).single().kind)
                throw IllegalStateException("rollback erasure")
            }
        }

        assertEquals(live.id, repo.findById(live.id)?.id)
        assertEquals(original, store.query(contextId, 0, 10))
        assertEquals(live.id, store.asOf(contextId, live.id, recordedAt)?.id)
    }

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
        val repo = AuditingPropositionRepository.wrapping(
            propositions, store, AuditContextProvider.NONE, AuditFailurePolicy.FAIL_WRITE, propositions.auditCanonicalLookup(),
        )
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
    @Autowired
    private lateinit var graphPropositions: DrivinePropositionRepository

    @Test
    fun `two graph writers cannot assign a new proposition id to different contexts`() {
        val firstContext = UUID.randomUUID().toString()
        val secondContext = UUID.randomUUID().toString()
        val claim = Proposition(id = UUID.randomUUID().toString(), contextId = ContextId(firstContext),
            text = "Initial owner", mentions = emptyList(), confidence = 0.8)
        val firstWritePending = CountDownLatch(1)
        val allowCommit = CountDownLatch(1)
        val contenderInitialRead = CountDownLatch(1)
        val contenderRecheck = CountDownLatch(1)
        val contenderReads = ThreadLocal<Int>()
        val delegate = object : PropositionRepository by graphPropositions {
            override fun findById(id: String): Proposition? {
                val value = graphPropositions.findById(id)
                val read = contenderReads.get() ?: return value
                contenderReads.set(read + 1)
                if (read == 0) {
                    assertNull(value)
                    contenderInitialRead.countDown()
                } else if (read == 1) contenderRecheck.countDown()
                return value
            }
        }
        val firstSink = log()
        val otherSink = log()
        val firstRepo = AuditingPropositionRepository.wrapping(
            delegate, firstSink,
            AuditContextProvider {
                firstWritePending.countDown()
                check(allowCommit.await(10, TimeUnit.SECONDS))
                AuditAttribution()
            },
            AuditFailurePolicy.FAIL_WRITE, graphPropositions.auditCanonicalLookup(),
        )
        val otherRepo = AuditingPropositionRepository.wrapping(
            delegate, otherSink, AuditContextProvider.NONE, AuditFailurePolicy.FAIL_WRITE,
            graphPropositions.auditCanonicalLookup(),
        )
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit(Callable { firstRepo.save(claim) })
            check(firstWritePending.await(10, TimeUnit.SECONDS))
            val second = pool.submit(Callable {
                contenderReads.set(0)
                runCatching { otherRepo.save(claim.copy(contextId = ContextId(secondContext), text = "Competing owner")) }
            })
            check(contenderInitialRead.await(10, TimeUnit.SECONDS))
            assertFalse(contenderRecheck.await(300, TimeUnit.MILLISECONDS))
            allowCommit.countDown()

            assertEquals(claim.id, first.get(10, TimeUnit.SECONDS).id)
            assertEquals(true, second.get(10, TimeUnit.SECONDS).exceptionOrNull() is IllegalArgumentException)
            assertEquals(firstContext, graphPropositions.findById(claim.id)?.contextId?.value)
            assertEquals(1, firstSink.history(firstContext, claim.id).size)
            assertEquals(emptyList<Any>(), otherSink.history(secondContext, claim.id))
        } finally {
            allowCommit.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `batches with opposite stripe orders both preserve their caller order`() {
        val firstContext = UUID.randomUUID().toString()
        val secondContext = UUID.randomUUID().toString()
        val prefix = UUID.randomUUID().toString()
        fun claims(contextId: String, ids: List<String>) = ids.map { id ->
            Proposition(id = prefix + id, contextId = ContextId(contextId),
                text = "Claim $id", mentions = emptyList(), confidence = 0.8)
        }

        // Aa hashes as BB and Ab hashes as BC.
        val first = claims(firstContext, listOf("Aa", "Ab"))
        val second = claims(secondContext, listOf("BC", "BB"))
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val writes = listOf(first, second).map { batch ->
                pool.submit(Callable {
                    val repo = AuditingPropositionRepository.wrapping(graphPropositions, log(),
                        AuditContextProvider.NONE, AuditFailurePolicy.FAIL_WRITE, graphPropositions.auditCanonicalLookup())
                    barrier.await(10, TimeUnit.SECONDS)
                    repo.saveAllReturningCanonical(batch)
                })
            }
            assertEquals(first.map { it.id }, writes[0].get(15, TimeUnit.SECONDS).canonicalPropositions.map { it.id })
            assertEquals(second.map { it.id }, writes[1].get(15, TimeUnit.SECONDS).canonicalPropositions.map { it.id })
        } finally {
            pool.shutdownNow()
        }
    }

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

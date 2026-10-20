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
package com.embabel.dice.storage.autoconfigure

import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.ContextReadScope
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.ReadClock
import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.ScopedReadFailure
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.storage.DrivinePropositionRepository
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.drivine.connection.ConnectionProperties
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.neo4j.driver.Driver
import org.neo4j.driver.Record
import org.neo4j.driver.Result
import org.neo4j.driver.Session
import org.neo4j.driver.SessionConfig
import org.neo4j.driver.Transaction
import org.neo4j.driver.TransactionConfig
import org.neo4j.driver.Values
import org.springframework.aop.support.AopUtils
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration
import org.springframework.transaction.PlatformTransactionManager
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@ExtendWith(OutputCaptureExtension::class)
class GraphRepositoryConfigurationTest {
    private val graph = mock<StatelessGraphObjectManager>()
    private val manager = mock<PersistenceManager> {
        whenever(it.database).thenReturn("store-alias")
        whenever(it.type).thenReturn(DatabaseType.NEO4J)
    }
    private val transactionManager = mock<PlatformTransactionManager>()
    private val embedding = mock<EmbeddingService> {
        whenever(it.embed(any<String>())).thenReturn(floatArrayOf(1f, 0f))
    }
    private val ai = mock<Ai>()
    private val driver = mock<Driver>()
    private val provider = mock<Neo4jConnectionProvider> {
        whenever(it.nativeDriver).thenReturn(driver)
    }
    private val registry = mock<DatabaseRegistry> {
        whenever(it.connectionProvider("store-alias")).thenReturn(provider)
        whenever(it.resolveDatabaseName("store-alias")).thenReturn("store-alias")
    }
    private val source = mock<ConnectionProperties> {
        whenever(it.databaseName).thenReturn("physical-dice")
    }
    private val sources = DataSourceMap(mapOf("store-alias" to source))
    private val scope = ContextReadScope.ofValues(listOf("A", "B"))
    private val spec = ScopedReadSpec.of(scope)
    private val clock = ReadClock(Instant.parse("2026-10-15T12:00:00Z"), 2.0)
    private val request = TextSimilaritySearchRequest(query = "query", topK = 1, similarityThreshold = 0.0)
    private val runner = baseRunner(manager)

    private fun baseRunner(manager: PersistenceManager) = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DiceStorageAutoConfiguration::class.java))
        .withPropertyValues(
            "embabel.dice.store.type=graph",
            "embabel.dice.store.vector-index.enabled=false",
        )
        .withBean(StatelessGraphObjectManager::class.java, { graph })
        .withBean(PersistenceManager::class.java, { manager })
        .withBean(PlatformTransactionManager::class.java, { transactionManager })
        .withBean(Ai::class.java, { ai })
        .withBean(EmbeddingService::class.java, { embedding })

    private fun runnerWith(manager: PersistenceManager, registry: DatabaseRegistry, sources: DataSourceMap = this.sources) =
        baseRunner(manager)
            .withBean(DatabaseRegistry::class.java, { registry })
            .withBean(DataSourceMap::class.java, { sources })

    private fun nativeRunner() = runnerWith(manager, registry)

    private fun overflowTransaction(): Pair<Session, Transaction> {
        val transaction = mock<Transaction>()
        val session = mock<Session>()
        val result = mock<Result>()
        val row = mock<Record>()
        whenever(driver.session(any<SessionConfig>())).thenReturn(session)
        whenever(session.beginTransaction(any<TransactionConfig>())).thenReturn(transaction)
        whenever(transaction.run(any<String>(), any<Map<String, Any?>>())).thenReturn(result)
        whenever(result.single()).thenReturn(row)
        whenever(row["overflow"]).thenReturn(Values.value(true))
        return session to transaction
    }

    private fun assertLegacyBudget(repository: PropositionRepository, cap: Int, timeout: Duration) {
        val (session, transaction) = overflowTransaction()
        val exhausted = assertFailsWith<ScopedReadException> {
            repository.findSimilarWithScores(request, PropositionQuery.againstContext("A"))
        }
        assertThat(exhausted.failure).isEqualTo(ScopedReadFailure.EXACT_VECTOR_BUDGET_EXCEEDED)
        val parameters = argumentCaptor<Map<String, Any?>>()
        verify(transaction).run(any<String>(), parameters.capture())
        assertThat(parameters.firstValue["candidateCap"]).isEqualTo(cap)
        assertThat(parameters.firstValue["admissionLimit"]).isEqualTo(cap + 1L)
        val config = argumentCaptor<SessionConfig>()
        verify(driver).session(config.capture())
        assertThat(config.firstValue.database().orElseThrow()).isEqualTo("physical-dice")
        val txConfig = argumentCaptor<TransactionConfig>()
        verify(session).beginTransaction(txConfig.capture())
        val configuredTimeout = txConfig.firstValue.timeout()
        assertTrue(configuredTimeout != null && configuredTimeout > Duration.ZERO && configuredTimeout <= timeout)
        verify(transaction).close()
    }

    @Test
    fun `default graph wiring enables native reads with finite legacy vector defaults without connecting`() {
        nativeRunner().run { context ->
            assertThat(context).hasNotFailed().hasSingleBean(PropositionRepository::class.java)
            val repository = context.getBean(DrivinePropositionRepository::class.java)
            assertThat(repository.supportsScopedReads).isTrue()
            assertThat(repository.supportsScopedVectors).isTrue()
            verifyNoInteractions(driver)
            verify(embedding, never()).embed(any<String>())
            assertLegacyBudget(repository, 10_000, Duration.ofSeconds(5))
        }
    }

    @Test
    fun `bound properties control candidate cap and native transaction timeout`() {
        nativeRunner().withPropertyValues(
            "embabel.dice.store.exact-vector.max-candidates=17",
            "embabel.dice.store.exact-vector.timeout=PT2S",
            "embabel.dice.store.decay.enabled=false",
            "embabel.dice.store.decay.interval-ms=1234",
            "embabel.dice.store.decay.k=1.25",
            "embabel.dice.store.decay.prune-stale=true",
        ).run { context ->
            assertThat(context).hasNotFailed()
            val properties = context.getBean(DiceStoreProperties::class.java)
            assertThat(properties.type).isEqualTo("graph")
            assertThat(properties.decay.enabled).isFalse()
            assertThat(properties.decay.intervalMs).isEqualTo(1234L)
            assertThat(properties.decay.k).isEqualTo(1.25)
            assertThat(properties.decay.pruneStale).isTrue()
            assertThat(properties.vectorIndex.enabled).isFalse()
            assertThat(properties.exactVector.maxCandidates).isEqualTo(17)
            assertThat(properties.exactVector.timeout).isEqualTo(Duration.ofSeconds(2))
            assertLegacyBudget(context.getBean(PropositionRepository::class.java), 17, Duration.ofSeconds(2))
        }
    }

    @Test
    fun `copying the properties keeps the exact vector settings`() {
        val properties = DiceStoreProperties(type = "graph")
        properties.exactVector.maxCandidates = 17
        val copied = properties.copy(type = "in-memory")
        assertThat(copied.exactVector.maxCandidates).isEqualTo(17)
        assertThat(copied).isNotEqualTo(DiceStoreProperties(type = "in-memory"))
    }

    @Test
    fun `invalid candidate caps fail graph construction explicitly`() {
        for (cap in listOf("0", "-1")) {
            nativeRunner().withPropertyValues("embabel.dice.store.exact-vector.max-candidates=$cap").run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseInstanceOf(IllegalArgumentException::class.java)
                    .hasStackTraceContaining("maxCandidates must be positive")
            }
        }
        verifyNoInteractions(driver)
    }

    @Test
    fun `invalid timeouts fail graph construction explicitly`() {
        for (timeout in listOf("PT0S", "PT-1S")) {
            nativeRunner().withPropertyValues("embabel.dice.store.exact-vector.timeout=$timeout").run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseInstanceOf(IllegalArgumentException::class.java)
                    .hasStackTraceContaining("timeout must be positive")
            }
        }
        verifyNoInteractions(driver)
    }

    @Test
    fun `the scoped read timeout property reaches the proxied repository`() {
        nativeRunner()
            .withConfiguration(AutoConfigurations.of(TransactionAutoConfiguration::class.java))
            .withPropertyValues("embabel.dice.store.scoped-read.timeout=7s")
            .run { context ->
                assertThat(context).hasNotFailed()
                val repository = context.getBean(DrivinePropositionRepository::class.java)
                assertTrue(AopUtils.isAopProxy(repository), "expected a transactional proxy, got ${repository::class.java}")
                // The timeout only shows where a scoped read opens its transaction, so run one.
                val transaction = mock<Transaction>()
                val session = mock<Session>()
                val result = mock<Result>()
                val row = mock<Record>()
                whenever(driver.session(any<SessionConfig>())).thenReturn(session)
                whenever(session.beginTransaction(any<TransactionConfig>())).thenReturn(transaction)
                whenever(transaction.run(any<String>(), any<Map<String, Any?>>())).thenReturn(result)
                whenever(result.single()).thenReturn(row)
                whenever(row["count"]).thenReturn(Values.value(3L))
                assertThat(repository.count(spec, clock)).isEqualTo(3L)
                val txConfig = argumentCaptor<TransactionConfig>()
                verify(session).beginTransaction(txConfig.capture())
                assertThat(txConfig.firstValue.timeout()).isEqualTo(Duration.ofSeconds(7))
            }
    }

    @Test
    fun `a scoped read timeout that is not positive stops the store from starting`() {
        for (timeout in listOf("0s", "-1s")) {
            nativeRunner().withPropertyValues("embabel.dice.store.scoped-read.timeout=$timeout").run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseInstanceOf(IllegalArgumentException::class.java)
                    .hasStackTraceContaining("Scoped read timeout must be positive")
            }
        }
        verifyNoInteractions(driver)
    }

    @Test
    fun `either missing native dependency keeps reads honestly unsupported`() {
        val variants = listOf(
            runner,
            runner.withBean(DatabaseRegistry::class.java, { registry }),
            runner.withBean(DataSourceMap::class.java, { sources }),
        )
        for (variant in variants) {
            variant.run { context ->
                assertThat(context).hasNotFailed()
                val repository = context.getBean(DrivinePropositionRepository::class.java)
                assertThat(repository.supportsScopedReads).isFalse()
                assertThat(repository.supportsScopedVectors).isFalse()
                assertThat(assertFailsWith<ScopedReadException> { repository.count(spec, clock) }.failure)
                    .isEqualTo(ScopedReadFailure.SCOPED_READ_UNSUPPORTED)
            }
        }
        verifyNoInteractions(driver)
        verify(embedding, never()).embed(any<String>())
    }

    @Test
    fun `application repository overrides the graph bean`() {
        val host = mock<PropositionRepository>()
        nativeRunner().withBean(PropositionRepository::class.java, { host }).run { context ->
            assertThat(context).hasNotFailed().hasSingleBean(PropositionRepository::class.java)
            assertThat(context.getBean(PropositionRepository::class.java)).isSameAs(host)
            verifyNoInteractions(registry)
            verifyNoInteractions(driver)
        }
    }

    @Test
    fun `a database other than Neo4j starts with native reads off`(output: CapturedOutput) {
        val falkor = mock<PersistenceManager> {
            whenever(it.database).thenReturn("falkor-alias")
            whenever(it.type).thenReturn(DatabaseType.FALKORDB)
        }
        val falkorRegistry = mock<DatabaseRegistry> {
            whenever(it.connectionProvider("falkor-alias")).thenReturn(mock<ConnectionProvider>())
        }
        val memgraph = mock<PersistenceManager> {
            whenever(it.database).thenReturn("store-alias")
            whenever(it.type).thenReturn(DatabaseType.MEMGRAPH)
        }
        for ((other, otherRegistry) in listOf(falkor to falkorRegistry, memgraph to registry)) {
            runnerWith(other, otherRegistry).run { context ->
                assertThat(context).hasNotFailed()
                val repository = context.getBean(DrivinePropositionRepository::class.java)
                assertThat(repository.supportsScopedReads).isFalse()
                assertThat(repository.supportsScopedVectors).isFalse()
                assertThat(repository.supportsStrictPolicyReads).isFalse()
            }
        }
        assertThat(output).contains("Scoped and policy reads are off")
        verifyNoInteractions(driver)
    }

    @Test
    fun `a Neo4j database with no matching data source starts with native reads off`(output: CapturedOutput) {
        // The provider is there, but the data source map only knows some other name, as it does when
        // the provider was registered in code or came from a ConnectionProvider bean.
        val elsewhere = DataSourceMap(mapOf("some-other-store" to source))
        runnerWith(manager, registry, elsewhere).run { context ->
            assertThat(context).hasNotFailed()
            val repository = context.getBean(DrivinePropositionRepository::class.java)
            assertThat(repository.supportsScopedReads).isFalse()
            assertThat(repository.supportsScopedVectors).isFalse()
            assertThat(repository.supportsStrictPolicyReads).isFalse()
            assertThat(assertFailsWith<ScopedReadException> { repository.count(spec, clock) }.failure)
                .isEqualTo(ScopedReadFailure.SCOPED_READ_UNSUPPORTED)
        }
        assertThat(output).contains("Scoped and policy reads are off").contains("no Drivine data source named 'store-alias'")
        verifyNoInteractions(driver)
    }

    @Test
    fun `the default database name resolves to the registered data source and turns native reads on`() {
        val zeroConfig = mock<PersistenceManager> {
            whenever(it.database).thenReturn("default")
            whenever(it.type).thenReturn(DatabaseType.NEO4J)
        }
        // Drivine answers "default" with its first registered provider, so the name it resolves to is
        // the one the data source map is keyed by.
        val aliasing = mock<DatabaseRegistry> {
            whenever(it.connectionProvider("default")).thenReturn(provider)
            whenever(it.connectionProvider("store-alias")).thenReturn(provider)
            whenever(it.resolveDatabaseName("default")).thenReturn("store-alias")
        }
        runnerWith(zeroConfig, aliasing).run { context ->
            assertThat(context).hasNotFailed()
            val repository = context.getBean(DrivinePropositionRepository::class.java)
            assertThat(repository.supportsScopedReads).isTrue()
            assertThat(repository.supportsScopedVectors).isTrue()
            assertThat(repository.supportsStrictPolicyReads).isTrue()
            verifyNoInteractions(driver)
            // A read lands on the physical database behind the resolved name.
            assertLegacyBudget(repository, 10_000, Duration.ofSeconds(5))
        }
    }
}

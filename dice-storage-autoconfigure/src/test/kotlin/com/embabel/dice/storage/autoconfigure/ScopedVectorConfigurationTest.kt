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
import com.embabel.dice.proposition.ExactVectorBudget
import com.embabel.dice.proposition.ExactVectorBudgetExceededException
import com.embabel.dice.proposition.FilteredVectorUnsupportedException
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.ScopedReadSpec
import com.embabel.dice.storage.DrivinePropositionRepository
import com.embabel.dice.storage.Neo4jScopedReadExecutor
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.drivine.connection.ConnectionProperties
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.Neo4jConnectionProvider
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.junit.jupiter.api.Test
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
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.transaction.PlatformTransactionManager
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScopedVectorConfigurationTest {
    private val graph = mock<StatelessGraphObjectManager>()
    private val manager = mock<PersistenceManager> {
        whenever(it.database).thenReturn("store-alias")
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
    }
    private val source = mock<ConnectionProperties> {
        whenever(it.databaseName).thenReturn("physical-dice")
    }
    private val sources = DataSourceMap(mapOf("store-alias" to source))
    private val scope = ContextReadScope.ofValues(listOf("A", "B"))
    private val spec = ScopedReadSpec.at(Instant.parse("2026-10-15T12:00:00Z"), 2.0)
    private val request = TextSimilaritySearchRequest(query = "query", topK = 1, similarityThreshold = 0.0)
    private val runner = ApplicationContextRunner()
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

    private fun nativeRunner() = runner
        .withBean(DatabaseRegistry::class.java, { registry })
        .withBean(DataSourceMap::class.java, { sources })

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
        assertFailsWith<ExactVectorBudgetExceededException> {
            repository.findSimilarWithScores(request, PropositionQuery.againstContext("A"))
        }
        val parameters = argumentCaptor<Map<String, Any?>>()
        verify(transaction).run(any<String>(), parameters.capture())
        assertThat(parameters.firstValue["candidateCap"]).isEqualTo(cap)
        assertThat(parameters.firstValue["admissionLimit"]).isEqualTo(cap + 1)
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
    fun `invalid candidate caps fail graph construction explicitly`() {
        for (cap in listOf("0", "-1", Int.MAX_VALUE.toString())) {
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
        for (timeout in listOf("PT0S", "PT-1S", "P1000000D")) {
            nativeRunner().withPropertyValues("embabel.dice.store.exact-vector.timeout=$timeout").run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseInstanceOf(IllegalArgumentException::class.java)
                    .hasStackTraceContaining("timeout must be a positive finite nanosecond interval")
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
                assertThat(assertFailsWith<ScopedReadException> { repository.count(scope, spec) }.code)
                    .isEqualTo("SCOPED_READ_UNSUPPORTED")
                assertFailsWith<FilteredVectorUnsupportedException> {
                    repository.findSimilarWithScores(request, PropositionQuery.againstContext("A"))
                }
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
    fun `repository withers preserve both settings in executor then budget order`() {
        val original = DrivinePropositionRepository(graph, manager, embedding, transactionManager)
        val native = original.withNativeReadExecutor(Neo4jScopedReadExecutor.from(manager, registry, sources))
        val configured = native.withExactVectorBudget(ExactVectorBudget(23, Duration.ofSeconds(3)))
        assertThat(original.supportsScopedReads).isFalse()
        assertThat(configured).isNotSameAs(native)
        assertThat(configured.supportsScopedReads).isTrue()
        assertLegacyBudget(configured, 23, Duration.ofSeconds(3))
    }

    @Test
    fun `repository withers preserve both settings in budget then executor order`() {
        val original = DrivinePropositionRepository(graph, manager, embedding, transactionManager)
        val bounded = original.withExactVectorBudget(ExactVectorBudget(29, Duration.ofSeconds(4)))
        val configured = bounded.withNativeReadExecutor(Neo4jScopedReadExecutor.from(manager, registry, sources))
        assertThat(original.supportsScopedReads).isFalse()
        assertThat(bounded.supportsScopedReads).isFalse()
        assertThat(configured).isNotSameAs(bounded)
        assertLegacyBudget(configured, 29, Duration.ofSeconds(4))
    }

    @Test
    fun `legacy no-arg configuration and bean invocation remain callable`() {
        val beans = DefaultListableBeanFactory()
        beans.registerSingleton("embedding", embedding)
        val configuration = DiceStorageAutoConfiguration()
        val repository = configuration.drivinePropositionRepository(
            graph, manager, ai, transactionManager, beans.getBeanProvider(EmbeddingService::class.java),
        )
        assertThat((repository as DrivinePropositionRepository).supportsScopedReads).isFalse()
        assertThat(repository.supportsScopedVectors).isFalse()
        assertThat(DiceStorageAutoConfiguration::class.java.getMethod(
            "drivinePropositionRepository", StatelessGraphObjectManager::class.java,
            PersistenceManager::class.java, Ai::class.java, PlatformTransactionManager::class.java,
            ObjectProvider::class.java,
        ).returnType).isEqualTo(PropositionRepository::class.java)
        verifyNoInteractions(driver)
    }

    @Test
    fun `properties retain their original primary constructor copy and components`() {
        val properties = DiceStoreProperties("graph", DiceStoreProperties.Decay(), DiceStoreProperties.VectorIndex())
        assertThat(properties.copy(type = "in-memory").type).isEqualTo("in-memory")
        assertThat(properties.component1()).isEqualTo("graph")
        assertThat(DiceStoreProperties::class.java.getConstructor(
            String::class.java, DiceStoreProperties.Decay::class.java, DiceStoreProperties.VectorIndex::class.java,
        )).isNotNull()
        assertThat(DiceStoreProperties::class.java.getMethod(
            "copy", String::class.java, DiceStoreProperties.Decay::class.java, DiceStoreProperties.VectorIndex::class.java,
        ).returnType).isEqualTo(DiceStoreProperties::class.java)
        assertThat(DiceStoreProperties::class.java.getConstructor(
            String::class.java, DiceStoreProperties.Decay::class.java, DiceStoreProperties.VectorIndex::class.java,
            Integer.TYPE, Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"),
        )).isNotNull()
        assertThat(DiceStoreProperties::class.java.getMethod(
            "copy\$default", DiceStoreProperties::class.java, String::class.java,
            DiceStoreProperties.Decay::class.java, DiceStoreProperties.VectorIndex::class.java,
            Integer.TYPE, Any::class.java,
        ).returnType).isEqualTo(DiceStoreProperties::class.java)
    }
}

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
package com.embabel.dice.storage.falkordb.autoconfigure

import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.incremental.InMemoryChunkHistoryStore
import com.embabel.dice.projection.lineage.ProjectionRecordStore
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.storage.DrivineChunkHistoryStore
import com.embabel.dice.storage.autoconfigure.DiceStorageAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.drivine.connection.ConnectionProperties
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbTransactionMode
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.transaction.TransactionContextHolder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.mock.env.MockEnvironment
import org.springframework.transaction.PlatformTransactionManager

class FalkorDbStorageAutoConfigurationTest {
    private val runner = ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
        DiceStorageAutoConfiguration::class.java, FalkorDbStorageAutoConfiguration::class.java,
    ))

    @Test
    fun `auto-configuration is discoverable through Spring Boot imports`() {
        val imports = org.springframework.boot.context.annotation.ImportCandidates.load(
            org.springframework.boot.autoconfigure.AutoConfiguration::class.java, javaClass.classLoader,
        )
        assertThat(imports.toList()).contains(FalkorDbStorageAutoConfiguration::class.java.name)
    }

    @Test
    fun `application stores win without requiring a datasource`() {
        val repository = InMemoryPropositionRepository()
        val chunks = InMemoryChunkHistoryStore()
        runner.withPropertyValues("embabel.dice.store.type=falkordb")
            .withBean(PropositionRepository::class.java, { repository })
            .withBean(ChunkHistoryStore::class.java, { chunks })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(PropositionRepository::class.java)).isSameAs(repository)
                assertThat(context.getBean(ChunkHistoryStore::class.java)).isSameAs(chunks)
            }
    }

    @Test
    fun `the datasource name binds through FalkorDbStoreProperties`() {
        runner.withPropertyValues("embabel.dice.store.type=falkordb", "embabel.dice.store.falkordb.datasource=knowledge")
            .withBean(PropositionRepository::class.java, { InMemoryPropositionRepository() })
            .withBean(ChunkHistoryStore::class.java, { InMemoryChunkHistoryStore() })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(FalkorDbStoreProperties::class.java).datasource).isEqualTo("knowledge")
            }
    }

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `startup warns about the stores that stay in memory`(output: CapturedOutput) {
        runner.withPropertyValues("embabel.dice.store.type=falkordb")
            .withBean(PropositionRepository::class.java, { InMemoryPropositionRepository() })
            .withBean(ChunkHistoryStore::class.java, { InMemoryChunkHistoryStore() })
            .withBean(ProjectionRecordStore::class.java, { mock<ProjectionRecordStore>() })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(output).contains("lose them on restart: collector audit records")
                assertThat(output).doesNotContain("projection records")
            }
    }

    @Test
    fun `default selector retains in-memory stores`() {
        val embedding = mock<EmbeddingService>()
        whenever(embedding.name).thenReturn("synthetic")
        whenever(embedding.provider).thenReturn("test")
        runner.withBean(Ai::class.java, { mock<Ai>() })
            .withBean(EmbeddingService::class.java, { embedding })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(PropositionRepository::class.java)).isInstanceOf(InMemoryPropositionRepository::class.java)
                assertThat(context.getBean(ChunkHistoryStore::class.java)).isInstanceOf(InMemoryChunkHistoryStore::class.java)
            }
    }

    @Test
    fun `graph selector retains Neo4j chunk implementation`() {
        runner.withPropertyValues("embabel.dice.store.type=graph")
            .withBean(StatelessGraphObjectManager::class.java, { mock<StatelessGraphObjectManager>() })
            .withBean(PersistenceManager::class.java, { mock<PersistenceManager>() })
            .withBean(PlatformTransactionManager::class.java, { mock<PlatformTransactionManager>() })
            .withBean(PropositionRepository::class.java, { InMemoryPropositionRepository() })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(ChunkHistoryStore::class.java)).isInstanceOf(DrivineChunkHistoryStore::class.java)
                assertThat(context).doesNotHaveBean("falkorDbChunkHistoryStore")
            }
    }

    @Test
    fun `unknown named datasource produces actionable startup failure`() {
        invalidRunner(DatabaseRegistry(DataSourceMap()), DataSourceMap())
            .withPropertyValues("embabel.dice.store.falkordb.datasource=missing")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("FalkorDB datasource 'missing' is not registered")
            }
    }

    @Test
    fun `declared Neo4j datasource is rejected before provider use`() {
        val registry = mock<DatabaseRegistry>()
        whenever(registry.resolveDatabaseName("knowledge")).thenReturn("knowledge")
        invalidRunner(registry, DataSourceMap(mapOf("knowledge" to ConnectionProperties(type = DatabaseType.NEO4J))))
            .withPropertyValues("embabel.dice.store.falkordb.datasource=knowledge")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("database.datasources.knowledge.type=FALKORDB")
            }
    }

    @Test
    fun `declared Falkor datasource still requires actual Falkor provider`() {
        val registry = mock<DatabaseRegistry>()
        whenever(registry.resolveDatabaseName("knowledge")).thenReturn("knowledge")
        whenever(registry.connectionProvider("knowledge")).thenReturn(mock<ConnectionProvider>())
        invalidRunner(registry, DataSourceMap(mapOf("knowledge" to ConnectionProperties(type = DatabaseType.FALKORDB))))
            .withPropertyValues("embabel.dice.store.falkordb.datasource=knowledge")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("requires a Drivine FalkorDbConnectionProvider")
            }
    }

    @Test
    fun `the stores' datasource defaults to STRICT and an explicit mode is kept`() {
        val strict = FalkorDbStorageAutoConfiguration.falkorDbStrictTransactions(
            MockEnvironment().withProperty("embabel.dice.store.falkordb.datasource", "knowledge"),
        )
        val sources = DataSourceMap(mapOf(
            "other" to ConnectionProperties(type = DatabaseType.FALKORDB),
            "knowledge" to ConnectionProperties(type = DatabaseType.FALKORDB),
            "chosen" to ConnectionProperties(type = DatabaseType.FALKORDB, falkorDbTransactionMode = FalkorDbTransactionMode.WARN),
        ))
        val processed = strict.postProcessAfterInitialization(sources, "dataSourceMap") as DataSourceMap
        assertThat(processed.dataSources.getValue("knowledge").falkorDbTransactionMode).isEqualTo(FalkorDbTransactionMode.STRICT)
        assertThat(processed.dataSources.getValue("other").falkorDbTransactionMode).isNull()
        val explicit = FalkorDbStorageAutoConfiguration.falkorDbStrictTransactions(
            MockEnvironment().withProperty("embabel.dice.store.falkordb.datasource", "chosen"),
        ).postProcessAfterInitialization(sources, "dataSourceMap") as DataSourceMap
        assertThat(explicit.dataSources.getValue("chosen").falkorDbTransactionMode).isEqualTo(FalkorDbTransactionMode.WARN)
        val byDefault = FalkorDbStorageAutoConfiguration.falkorDbStrictTransactions(MockEnvironment())
            .postProcessAfterInitialization(sources, "dataSourceMap") as DataSourceMap
        assertThat(byDefault.dataSources.getValue("other").falkorDbTransactionMode).isEqualTo(FalkorDbTransactionMode.STRICT)
    }

    private fun invalidRunner(registry: DatabaseRegistry, sources: DataSourceMap) = runner
        .withPropertyValues("embabel.dice.store.type=falkordb")
        .withBean(DatabaseRegistry::class.java, { registry })
        .withBean(DataSourceMap::class.java, { sources })
        .withBean(TransactionContextHolder::class.java, { TransactionContextHolder(registry) })
}

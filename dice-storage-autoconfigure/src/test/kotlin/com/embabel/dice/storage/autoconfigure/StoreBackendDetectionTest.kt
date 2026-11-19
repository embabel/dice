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
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.incremental.InMemoryChunkHistoryStore
import com.embabel.dice.metamodel.DriftReportStore
import com.embabel.dice.metamodel.InMemoryMetamodelVersionStore
import com.embabel.dice.metamodel.MetamodelVersionStore
import com.embabel.dice.metamodel.ObservedSchemaSource
import com.embabel.dice.projection.lineage.CollectorRecordStore
import com.embabel.dice.projection.lineage.InMemoryCollectorRecordStore
import com.embabel.dice.projection.lineage.InMemoryProjectionRecordStore
import com.embabel.dice.projection.lineage.ProjectionRecordStore
import com.embabel.dice.proposition.DecayManager
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.store.InMemoryDecayManager
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.spi.CollectorTraceStore
import com.embabel.dice.spi.InMemoryCollectorTraceStore
import com.embabel.dice.storage.DrivineChunkHistoryStore
import com.embabel.dice.storage.DrivineCollectorRecordStore
import com.embabel.dice.storage.DrivineCollectorTraceStore
import com.embabel.dice.storage.DrivineDriftReportStore
import com.embabel.dice.storage.DrivineMetamodelVersionStore
import com.embabel.dice.storage.DrivineObservedSchemaSource
import com.embabel.dice.storage.DrivineProjectionRecordStore
import com.embabel.dice.storage.DrivinePropositionRepository
import com.embabel.dice.storage.GraphDecayManager
import org.assertj.core.api.Assertions.assertThat
import org.drivine.autoconfigure.DrivineAutoConfiguration
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.schema.SchemaCatalog
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.Configurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/**
 * DICE picks its stores from what the application has configured, with no property needed.
 *
 * If Drivine is set up (its `PersistenceManager` bean exists), the Drivine stores are wired.
 * If it isn't, the in-memory stores are. `embabel.dice.store.type` is deprecated but still
 * respected for now: `in-memory` forces the in-memory stores, `graph` behaves like detection
 * except that it refuses to start without Drivine, and anything else refuses to start.
 */
@ExtendWith(OutputCaptureExtension::class)
class StoreBackendDetectionTest {

    private val storage = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DiceStorageAutoConfiguration::class.java))
        .withUserConfiguration(StubAi::class.java)

    @Nested
    inner class `with Drivine configured and the property unset` {

        @Test
        fun `every store the graph backend makes is the Drivine one`() {
            storage.withUserConfiguration(StubDrivine::class.java).run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertDrivineStores(ctx)
            }
        }

        @Test
        fun `a host's own PropositionRepository still wins`() {
            storage
                .withUserConfiguration(StubDrivine::class.java, HostRepository::class.java)
                .run { ctx ->
                    assertThat(ctx).hasNotFailed()
                    assertThat(ctx).hasSingleBean(PropositionRepository::class.java)
                    assertThat(ctx.getBean<PropositionRepository>()).isSameAs(HostRepository.INSTANCE)
                }
        }

        @Test
        fun `nothing about the deprecated property is logged`(output: CapturedOutput) {
            storage.withUserConfiguration(StubDrivine::class.java).run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(output.out).doesNotContain(DEPRECATION_MARKER)
            }
        }
    }

    @Test
    fun `without Drivine and with the property unset the stores are in-memory`() {
        storage.run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertInMemoryStores(ctx)
            assertThat(ctx.getBeanProvider(SchemaCatalog::class.java).orderedStream().toList()).isEmpty()
        }
    }

    @Test
    fun `type=in-memory forces the in-memory stores even with Drivine configured`() {
        storage
            .withUserConfiguration(StubDrivine::class.java)
            .withPropertyValues("embabel.dice.store.type=in-memory")
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertInMemoryStores(ctx)
                assertThat(ctx.getBeanProvider(SchemaCatalog::class.java).orderedStream().toList()).isEmpty()
            }
    }

    @Test
    fun `type=graph with Drivine configured wires the Drivine stores`() {
        storage
            .withUserConfiguration(StubDrivine::class.java)
            .withPropertyValues("embabel.dice.store.type=graph")
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertDrivineStores(ctx)
            }
    }

    @Test
    fun `type=graph without Drivine refuses to start and says Drivine is missing`() {
        storage
            .withPropertyValues("embabel.dice.store.type=graph")
            .run { ctx ->
                assertThat(ctx).hasFailed()
                assertThat(ctx).getFailure().rootCause()
                    .hasMessageContaining("embabel.dice.store.type=graph")
                    .hasMessageContaining("Drivine")
                    .hasMessageContaining("PersistenceManager")
            }
    }

    @Test
    fun `an unknown type refuses to start and names the allowed values`() {
        storage
            .withUserConfiguration(StubDrivine::class.java)
            .withPropertyValues("embabel.dice.store.type=grpah")
            .run { ctx ->
                assertThat(ctx).hasFailed()
                assertThat(ctx).getFailure().rootCause()
                    .hasMessageContaining("grpah")
                    .hasMessageContaining("graph")
                    .hasMessageContaining("in-memory")
                    .hasMessageContaining("deprecated")
            }
    }

    @Test
    fun `setting the property logs one deprecation warning`(output: CapturedOutput) {
        storage
            .withUserConfiguration(StubDrivine::class.java)
            .withPropertyValues("embabel.dice.store.type=in-memory")
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(output.out.split(DEPRECATION_MARKER).size - 1).isEqualTo(1)
            }
    }

    @Test
    fun `DICE's auto-configurations run after Drivine's`() {
        val ordered = Configurations.getClasses(
            AutoConfigurations.of(
                MetamodelAutoConfiguration::class.java,
                CollectorAutoConfiguration::class.java,
                DiceStorageAutoConfiguration::class.java,
                DrivineAutoConfiguration::class.java,
            ),
        )
        val drivine = ordered.indexOf(DrivineAutoConfiguration::class.java)
        listOf(
            DiceStorageAutoConfiguration::class.java,
            CollectorAutoConfiguration::class.java,
            MetamodelAutoConfiguration::class.java,
        ).forEach { dice ->
            assertThat(ordered.indexOf(dice)).describedAs("%s after Drivine", dice.simpleName).isGreaterThan(drivine)
        }
    }

    @Nested
    inner class `the collector trace store follows the same rule` {

        private val collector = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    DiceStorageAutoConfiguration::class.java,
                    CollectorAutoConfiguration::class.java,
                ),
            )
            .withUserConfiguration(StubAi::class.java)

        @Test
        fun `Drivine configured, property unset, gives the Drivine trace store`() {
            collector.withUserConfiguration(StubDrivine::class.java).run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx.getBean<CollectorTraceStore>()).isInstanceOf(DrivineCollectorTraceStore::class.java)
                assertThat(ctx).hasBean("collectorTraceSchema")
            }
        }

        @Test
        fun `no Drivine gives the in-memory trace store`() {
            collector.run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx.getBean<CollectorTraceStore>()).isInstanceOf(InMemoryCollectorTraceStore::class.java)
                assertThat(ctx).doesNotHaveBean("collectorTraceSchema")
            }
        }

        @Test
        fun `type=in-memory forces the in-memory trace store`() {
            collector
                .withUserConfiguration(StubDrivine::class.java)
                .withPropertyValues("embabel.dice.store.type=in-memory")
                .run { ctx ->
                    assertThat(ctx).hasNotFailed()
                    assertThat(ctx.getBean<CollectorTraceStore>()).isInstanceOf(InMemoryCollectorTraceStore::class.java)
                    assertThat(ctx).doesNotHaveBean("collectorTraceSchema")
                }
        }
    }

    @Nested
    inner class `the metamodel stores follow the same rule` {

        // On its own, as the other metamodel tests run it: the fixtures stub only the
        // PersistenceManager, which is all the governance stores need.
        private val metamodel = ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MetamodelAutoConfiguration::class.java))

        @Test
        fun `Drivine configured, property unset, gives the Drivine governance stores`() {
            metamodel.withUserConfiguration(DeclaredSchemaConfig::class.java).run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx.getBean<MetamodelVersionStore>()).isInstanceOf(DrivineMetamodelVersionStore::class.java)
                assertThat(ctx.getBean<DriftReportStore>()).isInstanceOf(DrivineDriftReportStore::class.java)
                assertThat(ctx.getBean<ObservedSchemaSource>()).isInstanceOf(DrivineObservedSchemaSource::class.java)
                assertThat(ctx).hasBean("metamodelSchema")
            }
        }

        @Test
        fun `no Drivine gives the in-memory version store and no graph beans`() {
            metamodel.withUserConfiguration(InMemoryBackendConfig::class.java).run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx.getBean<MetamodelVersionStore>()).isInstanceOf(InMemoryMetamodelVersionStore::class.java)
                assertThat(ctx).doesNotHaveBean(DriftReportStore::class.java)
                assertThat(ctx).doesNotHaveBean(ObservedSchemaSource::class.java)
                assertThat(ctx).doesNotHaveBean("metamodelSchema")
            }
        }

        @Test
        fun `type=in-memory forces the in-memory version store`() {
            metamodel
                .withUserConfiguration(DeclaredSchemaConfig::class.java)
                .withPropertyValues("embabel.dice.store.type=in-memory")
                .run { ctx ->
                    assertThat(ctx).hasNotFailed()
                    assertThat(ctx.getBean<MetamodelVersionStore>())
                        .isInstanceOf(InMemoryMetamodelVersionStore::class.java)
                    assertThat(ctx).doesNotHaveBean(DriftReportStore::class.java)
                    assertThat(ctx).doesNotHaveBean("metamodelSchema")
                }
        }
    }

    private fun assertDrivineStores(ctx: AssertableApplicationContext) {
        assertThat(ctx.getBean<PropositionRepository>()).isInstanceOf(DrivinePropositionRepository::class.java)
        assertThat(ctx.getBean<ChunkHistoryStore>()).isInstanceOf(DrivineChunkHistoryStore::class.java)
        assertThat(ctx.getBean<ProjectionRecordStore>()).isInstanceOf(DrivineProjectionRecordStore::class.java)
        assertThat(ctx.getBean<CollectorRecordStore>()).isInstanceOf(DrivineCollectorRecordStore::class.java)
        assertThat(ctx.getBean<DecayManager>()).isInstanceOf(GraphDecayManager::class.java)
    }

    private fun assertInMemoryStores(ctx: AssertableApplicationContext) {
        assertThat(ctx.getBean<PropositionRepository>()).isInstanceOf(InMemoryPropositionRepository::class.java)
        assertThat(ctx.getBean<ChunkHistoryStore>()).isInstanceOf(InMemoryChunkHistoryStore::class.java)
        assertThat(ctx.getBean<ProjectionRecordStore>()).isInstanceOf(InMemoryProjectionRecordStore::class.java)
        assertThat(ctx.getBean<CollectorRecordStore>()).isInstanceOf(InMemoryCollectorRecordStore::class.java)
        assertThat(ctx.getBean<DecayManager>()).isInstanceOf(InMemoryDecayManager::class.java)
    }

    /** What Drivine's starter registers in a real application, as mocks nobody calls. */
    @Configuration(proxyBeanMethods = false)
    open class StubDrivine {

        @Bean
        open fun persistenceManager(): PersistenceManager = mock()

        @Bean
        open fun graphObjectManager(): StatelessGraphObjectManager = mock()

        @Bean
        open fun transactionManager(): PlatformTransactionManager = mock()
    }

    /** The embabel-agent `Ai` handle the proposition stores need for embedding. */
    @Configuration(proxyBeanMethods = false)
    open class StubAi {

        @Bean
        open fun ai(): Ai {
            // A real name and dimension, because the graph backend sizes and versions its vector
            // index from them.
            val embeddings = mock<EmbeddingService>().also {
                whenever(it.name).thenReturn("stub-embeddings")
                whenever(it.dimensions).thenReturn(8)
            }
            return mock<Ai>().also { whenever(it.withDefaultEmbeddingService()).thenReturn(embeddings) }
        }
    }

    @Configuration(proxyBeanMethods = false)
    open class HostRepository {

        @Bean
        open fun hostPropositionRepository(): PropositionRepository = INSTANCE

        companion object {
            val INSTANCE: PropositionRepository = InMemoryPropositionRepository(mock<EmbeddingService>())
        }
    }

    private companion object {
        /** A fragment of the deprecation warning, distinctive enough to count. */
        const val DEPRECATION_MARKER = "embabel.dice.store.type is deprecated"
    }
}

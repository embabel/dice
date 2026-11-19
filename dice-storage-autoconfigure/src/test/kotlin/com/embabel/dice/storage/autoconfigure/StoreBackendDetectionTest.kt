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
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.Configurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.net.URL
import java.nio.file.Files
import java.util.Collections
import java.util.Enumeration

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

    // Detection is for apps that added dice-starter-neo4j, so these runners carry its marker.
    private val storage = ApplicationContextRunner()
        .withClassLoader(StarterOnClasspath())
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

    /**
     * A host whose own auto-configuration supplies Drivine, ordered after DICE's. DICE's conditions
     * can't see that `PersistenceManager` when they run, so the outcome has to come from the property
     * or from the startup check instead.
     */
    @Nested
    inner class `with Drivine registered after DICE's auto-configuration` {

        private val late = ApplicationContextRunner()
            .withClassLoader(StarterOnClasspath())
            .withConfiguration(
                AutoConfigurations.of(DiceStorageAutoConfiguration::class.java, LateDrivine::class.java),
            )
            .withUserConfiguration(StubAi::class.java)

        @Test
        fun `type=graph still wires the Drivine stores`() {
            late.withPropertyValues("embabel.dice.store.type=graph").run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertDrivineStores(ctx)
            }
        }

        @Test
        fun `with the property unset startup fails and says how to fix it`() {
            late.run { ctx ->
                assertThat(ctx).hasFailed()
                assertThat(ctx).getFailure().rootCause()
                    .hasMessageContaining("PersistenceManager")
                    .hasMessageContaining("after DICE's auto-configuration")
                    .hasMessageContaining("DiceStorageAutoConfiguration")
                    .hasMessageContaining("embabel.dice.store.type=graph")
            }
        }

        @Test
        fun `type=in-memory gives the in-memory stores without failing`() {
            late.withPropertyValues("embabel.dice.store.type=in-memory").run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertInMemoryStores(ctx)
            }
        }

        @Test
        fun `a host's own PropositionRepository still fails, naming DICE's other in-memory stores`() {
            late.withUserConfiguration(HostRepository::class.java).run { ctx ->
                assertThat(ctx).hasFailed()
                assertThat(ctx).getFailure().rootCause().message()
                    .contains("after DICE's auto-configuration")
                    .contains(ChunkHistoryStore::class.java.simpleName)
                    .contains(ProjectionRecordStore::class.java.simpleName)
                    .contains(CollectorRecordStore::class.java.simpleName)
                    .doesNotContain(PropositionRepository::class.java.simpleName)
                    .doesNotContain(DecayManager::class.java.simpleName)
            }
        }

        /** The decay manager DICE builds over the host's repository follows that repository, so it doesn't count. */
        @Test
        fun `with every store DICE would fall back on the host's own, nothing fails`() {
            late
                .withUserConfiguration(
                    HostRepository::class.java,
                    HostLineageStores::class.java,
                    HostCollectorRecordStore::class.java,
                )
                .run { ctx ->
                    assertThat(ctx).hasNotFailed()
                    assertThat(ctx.getBean<PropositionRepository>()).isSameAs(HostRepository.INSTANCE)
                    assertThat(ctx.getBean<ChunkHistoryStore>()).isSameAs(HostLineageStores.CHUNKS)
                    assertThat(ctx.getBean<ProjectionRecordStore>()).isSameAs(HostLineageStores.PROJECTIONS)
                    assertThat(ctx.getBean<DecayManager>()).isInstanceOf(InMemoryDecayManager::class.java)
                }
        }

        @Test
        fun `the check still runs when every bean is lazy`() {
            late
                .withInitializer { it.addBeanFactoryPostProcessor(LazyInitializationBeanFactoryPostProcessor()) }
                .run { ctx ->
                    assertThat(ctx).hasFailed()
                    assertThat(ctx).getFailure().rootCause()
                        .hasMessageContaining("after DICE's auto-configuration")
                }
        }

        /** No `Ai` bean, so DICE makes no proposition store at all, only the stores that don't embed. */
        private val lateWithoutAi = ApplicationContextRunner()
            .withClassLoader(StarterOnClasspath())
            .withConfiguration(
                AutoConfigurations.of(DiceStorageAutoConfiguration::class.java, LateDrivine::class.java),
            )

        @Test
        fun `without an Ai bean startup still fails and names the in-memory stores`() {
            lateWithoutAi.run { ctx ->
                assertThat(ctx).hasFailed()
                assertThat(ctx).getFailure().rootCause()
                    .hasMessageContaining("after DICE's auto-configuration")
                    .hasMessageContaining("embabel.dice.store.type=graph")
                    .hasMessageContaining(ChunkHistoryStore::class.java.simpleName)
                    .hasMessageContaining(ProjectionRecordStore::class.java.simpleName)
                    .hasMessageContaining(CollectorRecordStore::class.java.simpleName)
            }
        }

        @Test
        fun `without an Ai bean type=in-memory gives the in-memory stores without failing`() {
            lateWithoutAi.withPropertyValues("embabel.dice.store.type=in-memory").run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx.getBean<ChunkHistoryStore>()).isInstanceOf(InMemoryChunkHistoryStore::class.java)
                assertThat(ctx.getBean<ProjectionRecordStore>()).isInstanceOf(InMemoryProjectionRecordStore::class.java)
            }
        }

        @Test
        fun `host stores are never counted, but a remaining DICE in-memory store still fails`() {
            lateWithoutAi.withUserConfiguration(HostLineageStores::class.java).run { ctx ->
                assertThat(ctx).hasFailed()
                assertThat(ctx).getFailure().rootCause().message()
                    .contains(CollectorRecordStore::class.java.simpleName)
                    .doesNotContain(ChunkHistoryStore::class.java.simpleName)
                    .doesNotContain(ProjectionRecordStore::class.java.simpleName)
            }
        }

        @Test
        fun `with every store the host's own nothing fails`() {
            lateWithoutAi
                .withUserConfiguration(HostLineageStores::class.java, HostCollectorRecordStore::class.java)
                .run { ctx ->
                    assertThat(ctx).hasNotFailed()
                    assertThat(ctx.getBean<ChunkHistoryStore>()).isSameAs(HostLineageStores.CHUNKS)
                    assertThat(ctx.getBean<ProjectionRecordStore>()).isSameAs(HostLineageStores.PROJECTIONS)
                }
        }

        @Test
        fun `the collector trace and metamodel version stores are counted too`() {
            ApplicationContextRunner()
                .withClassLoader(StarterOnClasspath())
                .withConfiguration(
                    AutoConfigurations.of(
                        DiceStorageAutoConfiguration::class.java,
                        CollectorAutoConfiguration::class.java,
                        MetamodelAutoConfiguration::class.java,
                        LateDrivine::class.java,
                    ),
                )
                .withUserConfiguration(
                    InMemoryBackendConfig::class.java,
                    HostLineageStores::class.java,
                    HostCollectorRecordStore::class.java,
                )
                .run { ctx ->
                    assertThat(ctx).hasFailed()
                    assertThat(ctx).getFailure().rootCause()
                        .hasMessageContaining(CollectorTraceStore::class.java.simpleName)
                        .hasMessageContaining(MetamodelVersionStore::class.java.simpleName)
                }
        }
    }

    @Test
    fun `with trace recording turned off the in-memory trace store is not mistaken for lost storage`() {
        storage
            .withConfiguration(AutoConfigurations.of(CollectorAutoConfiguration::class.java))
            .withUserConfiguration(StubDrivine::class.java)
            .withPropertyValues("embabel.dice.collector.trace.enabled=false")
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx.getBean<CollectorTraceStore>()).isInstanceOf(InMemoryCollectorTraceStore::class.java)
            }
    }

    @Nested
    inner class `the collector trace store follows the same rule` {

        private val collector = ApplicationContextRunner()
            .withClassLoader(StarterOnClasspath())
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
            .withClassLoader(StarterOnClasspath())
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

    /**
     * An app that never added dice-starter-neo4j gets what it got before detection existed: the
     * in-memory stores unless it sets `embabel.dice.store.type=graph`, whatever Drivine beans it has
     * for its own use. The test classpath has no marker, so these runners leave it out.
     */
    @Nested
    inner class `without dice-starter-neo4j on the classpath` {

        private val plain = ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(DiceStorageAutoConfiguration::class.java, CollectorAutoConfiguration::class.java),
            )
            .withUserConfiguration(StubAi::class.java)

        @Test
        fun `the marker really is missing from the test classpath`() {
            assertThat(javaClass.classLoader.getResource(StarterOnClasspath.MARKER_PATH)).isNull()
            assertThat(StarterOnClasspath().getResource(StarterOnClasspath.MARKER_PATH)).isNotNull()
        }

        @Test
        fun `Drivine configured and the property unset gives the in-memory stores and no DICE schema`(
            output: CapturedOutput,
        ) {
            plain.withUserConfiguration(StubDrivine::class.java).run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertInMemoryStores(ctx)
                assertThat(ctx.getBean<CollectorTraceStore>()).isInstanceOf(InMemoryCollectorTraceStore::class.java)
                assertThat(ctx.getBeanProvider(SchemaCatalog::class.java).orderedStream().toList()).isEmpty()
                assertThat(output.out).doesNotContain(DEPRECATION_MARKER)
            }
        }

        @Test
        fun `Drivine configured and the property unset keeps the metamodel stores in memory`() {
            ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MetamodelAutoConfiguration::class.java))
                .withUserConfiguration(InMemoryBackendConfig::class.java, StubDrivine::class.java)
                .run { ctx ->
                    assertThat(ctx).hasNotFailed()
                    assertThat(ctx.getBean<MetamodelVersionStore>())
                        .isInstanceOf(InMemoryMetamodelVersionStore::class.java)
                    assertThat(ctx).doesNotHaveBean(DriftReportStore::class.java)
                    assertThat(ctx).doesNotHaveBean("metamodelSchema")
                }
        }

        @Test
        fun `Drivine registered after DICE's auto-configuration doesn't fail startup`() {
            ApplicationContextRunner()
                .withConfiguration(
                    AutoConfigurations.of(DiceStorageAutoConfiguration::class.java, LateDrivine::class.java),
                )
                .withUserConfiguration(StubAi::class.java)
                .run { ctx ->
                    assertThat(ctx).hasNotFailed()
                    assertInMemoryStores(ctx)
                }
        }

        @Test
        fun `type=graph wires the Drivine stores, as it always has`() {
            plain
                .withUserConfiguration(StubDrivine::class.java)
                .withPropertyValues("embabel.dice.store.type=graph")
                .run { ctx ->
                    assertThat(ctx).hasNotFailed()
                    assertDrivineStores(ctx)
                    assertThat(ctx.getBean<CollectorTraceStore>()).isInstanceOf(DrivineCollectorTraceStore::class.java)
                }
        }

        @Test
        fun `type=in-memory gives the in-memory stores`() {
            plain
                .withUserConfiguration(StubDrivine::class.java)
                .withPropertyValues("embabel.dice.store.type=in-memory")
                .run { ctx ->
                    assertThat(ctx).hasNotFailed()
                    assertInMemoryStores(ctx)
                }
        }

        @Test
        fun `a blank property counts as unset`() {
            plain
                .withUserConfiguration(StubDrivine::class.java)
                .withPropertyValues("embabel.dice.store.type= ")
                .run { ctx ->
                    assertThat(ctx).hasNotFailed()
                    assertInMemoryStores(ctx)
                }
        }

        @Test
        fun `an unknown type still refuses to start`() {
            plain
                .withUserConfiguration(StubDrivine::class.java)
                .withPropertyValues("embabel.dice.store.type=grpah")
                .run { ctx ->
                    assertThat(ctx).hasFailed()
                    assertThat(ctx).getFailure().rootCause().hasMessageContaining("grpah")
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

    /** Drivine supplied by a host auto-configuration that runs after DICE's. */
    @AutoConfiguration(after = [DiceStorageAutoConfiguration::class])
    open class LateDrivine : StubDrivine()

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

    /** A host that keeps its own chunk history and projection records. */
    @Configuration(proxyBeanMethods = false)
    open class HostLineageStores {

        @Bean
        open fun hostChunkHistoryStore(): ChunkHistoryStore = CHUNKS

        @Bean
        open fun hostProjectionRecordStore(): ProjectionRecordStore = PROJECTIONS

        companion object {
            val CHUNKS: ChunkHistoryStore = InMemoryChunkHistoryStore()
            val PROJECTIONS: ProjectionRecordStore = InMemoryProjectionRecordStore()
        }
    }

    @Configuration(proxyBeanMethods = false)
    open class HostCollectorRecordStore {

        @Bean
        open fun hostCollectorRecordStore(): CollectorRecordStore = InMemoryCollectorRecordStore()
    }

    private companion object {
        /** A fragment of the deprecation warning, distinctive enough to count. */
        const val DEPRECATION_MARKER = "embabel.dice.store.type is deprecated"
    }
}

/**
 * A class loader that can also find the marker dice-starter-neo4j ships, as if the starter were on the
 * classpath. Everything else comes from the test classpath, which doesn't have the marker.
 */
internal class StarterOnClasspath(
    parent: ClassLoader = StoreBackendDetectionTest::class.java.classLoader,
) : ClassLoader(parent) {

    override fun findResource(name: String): URL? = if (name == MARKER_PATH) MARKER else null

    override fun findResources(name: String): Enumeration<URL> =
        Collections.enumeration(listOfNotNull(findResource(name)))

    companion object {
        /** Where dice-starter-neo4j puts its marker; spelled out here so the test notices if it moves. */
        const val MARKER_PATH = "META-INF/dice/drivine-stores.properties"

        private val MARKER: URL = Files.createTempFile("drivine-stores", ".properties").also {
            it.toFile().deleteOnExit()
            Files.writeString(it, "# Stands in for the marker dice-starter-neo4j ships.\n")
        }.toUri().toURL()
    }
}

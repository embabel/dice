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
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.storage.DrivineChunkHistoryStore
import com.embabel.dice.storage.DrivinePropositionRepository
import org.assertj.core.api.Assertions.assertThat
import org.drivine.autoconfigure.DrivineAutoConfiguration
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.manager.StatelessGraphObjectManager
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.Configurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * A host with Drivine's starter and a datasource gets DICE's Drivine stores without writing any beans.
 *
 * Drivine's own auto-configuration builds the factories but stops short of a `PersistenceManager`
 * and a `StatelessGraphObjectManager`, which DICE's stores need. DICE makes those two from the
 * factories, for the one datasource there is or the one `embabel.dice.store.datasource` names.
 *
 * The datasources point at a Neo4j nobody is running. That's fine: Drivine doesn't connect until
 * something runs a query, and nothing here does.
 */
class DiceDrivineAutoConfigurationTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DrivineAutoConfiguration::class.java,
                DiceDrivineAutoConfiguration::class.java,
                DiceStorageAutoConfiguration::class.java,
            ),
        )
        .withUserConfiguration(StubAi::class.java)

    @Test
    fun `with one datasource and no host beans DICE makes both beans for it and wires its Drivine stores`() {
        runner.withPropertyValues(*datasource("neo")).run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(PersistenceManager::class.java)
            assertThat(ctx).hasSingleBean(StatelessGraphObjectManager::class.java)
            assertThat(ctx.getBean<PersistenceManager>().database).isEqualTo("neo")
            assertThat(ctx.getBean<StatelessGraphObjectManager>())
                .isSameAs(ctx.getBean<GraphObjectManagerFactory>().stateless("neo"))
            assertThat(ctx.getBean<PropositionRepository>()).isInstanceOf(DrivinePropositionRepository::class.java)
            assertThat(ctx.getBean<ChunkHistoryStore>()).isInstanceOf(DrivineChunkHistoryStore::class.java)
        }
    }

    @Test
    fun `with two datasources the one named by embabel dice store datasource is used`() {
        runner
            .withPropertyValues(*datasource("neo"), *datasource("other"), "embabel.dice.store.datasource=other")
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx.getBean<PersistenceManager>().database).isEqualTo("other")
                assertThat(ctx.getBean<StatelessGraphObjectManager>())
                    .isSameAs(ctx.getBean<GraphObjectManagerFactory>().stateless("other"))
                assertThat(ctx.getBean<PropositionRepository>()).isInstanceOf(DrivinePropositionRepository::class.java)
            }
    }

    @Test
    fun `with two datasources and none named startup fails naming both`() {
        runner.withPropertyValues(*datasource("neo"), *datasource("other")).run { ctx ->
            assertThat(ctx).hasFailed()
            assertThat(ctx).getFailure().rootCause()
                .hasMessageContaining("neo")
                .hasMessageContaining("other")
                .hasMessageContaining("embabel.dice.store.datasource")
        }
    }

    @Test
    fun `a named datasource that doesn't exist fails startup naming the configured ones`() {
        runner
            .withPropertyValues(*datasource("neo"), *datasource("other"), "embabel.dice.store.datasource=missing")
            .run { ctx ->
                assertThat(ctx).hasFailed()
                assertThat(ctx).getFailure().rootCause()
                    .hasMessageContaining("'missing'")
                    .hasMessageContaining("neo")
                    .hasMessageContaining("other")
                    .hasMessageContaining("embabel.dice.store.datasource")
            }
    }

    @Test
    fun `a host's own PersistenceManager and StatelessGraphObjectManager win`() {
        // Two datasources and none named: if DICE tried to choose, startup would fail.
        runner
            .withPropertyValues(*datasource("neo"), *datasource("other"))
            .withUserConfiguration(HostDrivineBeans::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).hasSingleBean(PersistenceManager::class.java)
                assertThat(ctx).hasSingleBean(StatelessGraphObjectManager::class.java)
                assertThat(ctx.getBean<PersistenceManager>()).isSameAs(HostDrivineBeans.PERSISTENCE_MANAGER)
                assertThat(ctx.getBean<StatelessGraphObjectManager>()).isSameAs(HostDrivineBeans.GRAPH_OBJECT_MANAGER)
            }
    }

    @Test
    fun `a host PersistenceManager alone gets a StatelessGraphObjectManager on its own datasource`() {
        // Two datasources and none named: the host's manager settles which one DICE uses.
        runner
            .withPropertyValues(*datasource("neo"), *datasource("other"), "host.datasource=other")
            .withUserConfiguration(HostPersistenceManager::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).hasSingleBean(PersistenceManager::class.java)
                assertThat(ctx.getBean<PersistenceManager>().database).isEqualTo("other")
                assertThat(ctx.getBean<StatelessGraphObjectManager>())
                    .isSameAs(ctx.getBean<GraphObjectManagerFactory>().stateless("other"))
            }
    }

    @Test
    fun `a host PersistenceManager on the default alias gets a StatelessGraphObjectManager on the first datasource`() {
        runner
            .withPropertyValues(*datasource("neo"), *datasource("other"), "host.datasource=default")
            .withUserConfiguration(HostPersistenceManager::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx.getBean<StatelessGraphObjectManager>())
                    .isSameAs(ctx.getBean<GraphObjectManagerFactory>().stateless("neo"))
            }
    }

    @Test
    fun `a host PersistenceManager on one datasource and the property naming another fails startup naming both`() {
        runner
            .withPropertyValues(
                *datasource("neo"),
                *datasource("other"),
                "host.datasource=neo",
                "embabel.dice.store.datasource=other",
            )
            .withUserConfiguration(HostPersistenceManager::class.java)
            .run { ctx ->
                assertThat(ctx).hasFailed()
                assertThat(ctx).getFailure().rootCause()
                    .hasMessageContaining("'neo'")
                    .hasMessageContaining("'other'")
                    .hasMessageContaining("embabel.dice.store.datasource")
            }
    }

    @Test
    fun `a host PersistenceManager and the property naming the same datasource start`() {
        runner
            .withPropertyValues(
                *datasource("neo"),
                *datasource("other"),
                "host.datasource=other",
                "embabel.dice.store.datasource=other",
            )
            .withUserConfiguration(HostPersistenceManager::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx.getBean<StatelessGraphObjectManager>())
                    .isSameAs(ctx.getBean<GraphObjectManagerFactory>().stateless("other"))
            }
    }

    @Test
    fun `a host StatelessGraphObjectManager alone with several datasources fails startup asking for both beans`() {
        // Drivine doesn't say which database a StatelessGraphObjectManager uses, so the property can't
        // be trusted to match it.
        runner
            .withPropertyValues(
                *datasource("neo"),
                *datasource("other"),
                "host.datasource=neo",
                "embabel.dice.store.datasource=other",
            )
            .withUserConfiguration(HostGraphObjectManager::class.java)
            .run { ctx ->
                assertThat(ctx).hasFailed()
                assertThat(ctx).getFailure().rootCause()
                    .hasMessageContaining("StatelessGraphObjectManager")
                    .hasMessageContaining("PersistenceManager")
                    .hasMessageContaining("both")
            }
    }

    @Test
    fun `a host StatelessGraphObjectManager alone with one datasource gets a PersistenceManager for it`() {
        runner
            .withPropertyValues(*datasource("neo"), "host.datasource=neo")
            .withUserConfiguration(HostGraphObjectManager::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx.getBean<PersistenceManager>().database).isEqualTo("neo")
                assertThat(ctx.getBean<StatelessGraphObjectManager>())
                    .isSameAs(ctx.getBean<GraphObjectManagerFactory>().stateless("neo"))
            }
    }

    @Test
    fun `forcing in-memory with several datasources starts with no Drivine beans from DICE`() {
        runner
            .withPropertyValues(*datasource("neo"), *datasource("other"), "embabel.dice.store.type=in-memory")
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).doesNotHaveBean(PersistenceManager::class.java)
                assertThat(ctx).doesNotHaveBean(StatelessGraphObjectManager::class.java)
                assertThat(ctx.getBean<PropositionRepository>()).isInstanceOf(InMemoryPropositionRepository::class.java)
                assertThat(ctx.getBean<ChunkHistoryStore>()).isInstanceOf(InMemoryChunkHistoryStore::class.java)
            }
    }

    @Test
    fun `without a Drivine datasource nothing is created and the stores stay in-memory`() {
        runner.run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).doesNotHaveBean(PersistenceManagerFactory::class.java)
            assertThat(ctx).doesNotHaveBean(PersistenceManager::class.java)
            assertThat(ctx).doesNotHaveBean(StatelessGraphObjectManager::class.java)
            assertThat(ctx.getBean<PropositionRepository>()).isInstanceOf(InMemoryPropositionRepository::class.java)
            assertThat(ctx.getBean<ChunkHistoryStore>()).isInstanceOf(InMemoryChunkHistoryStore::class.java)
        }
    }

    @Test
    fun `it runs after Drivine's auto-configuration and before every DICE one that looks for Drivine`() {
        val ordered = Configurations.getClasses(
            AutoConfigurations.of(
                MetamodelAutoConfiguration::class.java,
                CollectorAutoConfiguration::class.java,
                DiceStorageAutoConfiguration::class.java,
                DiceDrivineAutoConfiguration::class.java,
                DrivineAutoConfiguration::class.java,
            ),
        )
        val drivineBeans = ordered.indexOf(DiceDrivineAutoConfiguration::class.java)
        assertThat(drivineBeans).isGreaterThan(ordered.indexOf(DrivineAutoConfiguration::class.java))
        listOf(
            DiceStorageAutoConfiguration::class.java,
            CollectorAutoConfiguration::class.java,
            MetamodelAutoConfiguration::class.java,
        ).forEach { dice ->
            assertThat(ordered.indexOf(dice)).describedAs("%s after DiceDrivineAutoConfiguration", dice.simpleName)
                .isGreaterThan(drivineBeans)
        }
    }

    @Test
    fun `it is registered as an auto-configuration`() {
        val registered = ImportCandidates.load(AutoConfiguration::class.java, javaClass.classLoader).candidates
        assertThat(registered).contains(DiceDrivineAutoConfiguration::class.java.name)
    }

    /** A Neo4j datasource under [name], enough for Drivine to register it without connecting. */
    private fun datasource(name: String): Array<String> = arrayOf(
        "database.datasources.$name.type=NEO4J",
        "database.datasources.$name.host=localhost",
        "database.datasources.$name.username=neo4j",
        "database.datasources.$name.password=unused",
    )

    /** The embabel-agent `Ai` handle the proposition store needs for embedding. */
    @Configuration(proxyBeanMethods = false)
    open class StubAi {

        @Bean
        open fun ai(): Ai {
            val embeddings = mock<EmbeddingService>().also {
                whenever(it.name).thenReturn("stub-embeddings")
                whenever(it.dimensions).thenReturn(8)
            }
            return mock<Ai>().also { whenever(it.withDefaultEmbeddingService()).thenReturn(embeddings) }
        }
    }

    /** A host that writes only the PersistenceManager, for the datasource named by `host.datasource`. */
    @Configuration(proxyBeanMethods = false)
    open class HostPersistenceManager {

        @Bean
        open fun hostPersistenceManager(
            factory: PersistenceManagerFactory,
            @Value("\${host.datasource}") datasource: String,
        ): PersistenceManager = factory.get(datasource)
    }

    /** A host that writes only the StatelessGraphObjectManager, for the datasource named by `host.datasource`. */
    @Configuration(proxyBeanMethods = false)
    open class HostGraphObjectManager {

        @Bean
        open fun hostGraphObjectManager(
            factory: GraphObjectManagerFactory,
            @Value("\${host.datasource}") datasource: String,
        ): StatelessGraphObjectManager = factory.stateless(datasource)
    }

    /** A host that still writes the two beans itself, as hosts had to before. */
    @Configuration(proxyBeanMethods = false)
    open class HostDrivineBeans {

        @Bean
        open fun hostPersistenceManager(): PersistenceManager = PERSISTENCE_MANAGER

        @Bean
        open fun hostGraphObjectManager(): StatelessGraphObjectManager = GRAPH_OBJECT_MANAGER

        companion object {
            val PERSISTENCE_MANAGER: PersistenceManager = mock()
            val GRAPH_OBJECT_MANAGER: StatelessGraphObjectManager = mock()
        }
    }
}

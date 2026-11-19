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
import com.embabel.dice.metamodel.DeclaredSchemaSource
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.storage.DrivineChunkHistoryStore
import com.embabel.dice.storage.DrivinePropositionRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.drivine.autoconfigure.DrivineAutoConfiguration
import org.drivine.connection.DatabaseRegistry
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.mapper.SubtypeRegistry
import org.drivine.schema.DatabaseTarget
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaCatalog
import org.drivine.schema.SchemaTargets
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.annotation.Configurations
import org.springframework.boot.context.annotation.ImportCandidates
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

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
    fun `a host PersistenceManager alone fails startup asking for both managers or neither`() {
        runner
            .withPropertyValues(*datasource("neo"), "host.datasource=neo")
            .withUserConfiguration(HostPersistenceManager::class.java)
            .run { ctx -> assertAskedForBothOrNeither(ctx, "'hostPersistenceManager'") }
    }

    @Test
    fun `a host StatelessGraphObjectManager alone fails startup asking for both managers or neither`() {
        runner
            .withPropertyValues(*datasource("neo"), "host.datasource=neo")
            .withUserConfiguration(HostGraphObjectManager::class.java)
            .run { ctx -> assertAskedForBothOrNeither(ctx, "'hostGraphObjectManager'") }
    }

    @Test
    fun `a host StatelessGraphObjectManager on another registry fails startup instead of getting a PersistenceManager beside it`() {
        // A companion PersistenceManager on "neo" would save nodes there while the host's manager on
        // "external" ran dedup and provenance somewhere else.
        runner
            .withPropertyValues(*datasource("neo"))
            .withUserConfiguration(ExternalHostGraphObjectManager::class.java)
            .run { ctx -> assertAskedForBothOrNeither(ctx, "'externalGraphObjectManager'") }
    }

    @Test
    fun `a host StatelessGraphObjectManager built on the PersistenceManager bean still counts as only one of the two`() {
        runner
            .withPropertyValues(*datasource("neo"))
            .withUserConfiguration(HostGraphObjectManagerOnPersistenceManager::class.java)
            .run { ctx -> assertAskedForBothOrNeither(ctx, "'hostGraphObjectManager'") }
    }

    @Test
    fun `several host StatelessGraphObjectManagers and no PersistenceManager fail startup naming them`() {
        // A primary among them doesn't help: DICE no longer builds a manager to sit beside the host's.
        runner
            .withPropertyValues(*datasource("neo"), "host.primary=default", "host.secondary=neo")
            .withUserConfiguration(PrimaryAmongHostGraphObjectManagers::class.java)
            .run { ctx ->
                assertAskedForBothOrNeither(ctx, "'primaryGraphObjectManager'", "'otherGraphObjectManager'")
            }
    }

    @Test
    fun `several host PersistenceManagers and no StatelessGraphObjectManager fail startup naming them`() {
        runner
            .withPropertyValues(*datasource("neo"), "host.primary=default", "host.secondary=neo")
            .withUserConfiguration(UnrankedHostPersistenceManagers::class.java)
            .run { ctx ->
                assertAskedForBothOrNeither(ctx, "'firstPersistenceManager'", "'secondPersistenceManager'")
            }
    }

    @Test
    fun `a host that defines both managers on the default alias gets nothing from DICE`() {
        // Two datasources and none named, which would stop DICE if it tried to choose.
        runner
            .withPropertyValues(*datasource("neo"), *datasource("other"), "host.datasource=default")
            .withUserConfiguration(HostDrivinePair::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).hasSingleBean(PersistenceManager::class.java)
                assertThat(ctx).hasSingleBean(StatelessGraphObjectManager::class.java)
                assertThat(ctx.containsBeanDefinition("persistenceManager")).isFalse()
                assertThat(ctx.containsBeanDefinition("statelessGraphObjectManager")).isFalse()
                assertThat(ctx.getBean<PersistenceManager>().database).isEqualTo("default")
                assertThat(ctx.getBean<StatelessGraphObjectManager>().database).isEqualTo("default")
            }
    }

    @Test
    fun `with several datasources DICE's schema goes only to the one its stores use`() {
        runnerWithCollector
            .withPropertyValues(*datasource("neo"), *datasource("other"), "embabel.dice.store.datasource=other")
            .withUserConfiguration(HostSchema::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                val dice = diceCatalogs(ctx)
                assertThat(dice).containsKeys("propositionConstraintSchema", "lineageRecordSchema", "collectorTraceSchema")
                dice.forEach { (name, catalog) ->
                    assertThat(catalog.target).describedAs(name).isEqualTo(DatabaseTarget.Named(setOf("other")))
                }
                assertThat(SchemaTargets.resolve(dice.values.toList(), ctx.getBean<DatabaseRegistry>()).databases)
                    .containsExactly("other")
                assertThat(ctx.getBean<SchemaCatalog>("hostSchema").target).isEqualTo(DatabaseTarget.All)
            }
    }

    @Test
    fun `with several datasources and a host pair on the default alias DICE's schema goes to the first`() {
        runnerWithCollector
            .withPropertyValues(*datasource("neo"), *datasource("other"), "host.datasource=default")
            .withUserConfiguration(HostDrivinePair::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                val dice = diceCatalogs(ctx)
                assertThat(dice).isNotEmpty()
                dice.forEach { (name, catalog) ->
                    assertThat(catalog.target).describedAs(name).isEqualTo(DatabaseTarget.Named(setOf("neo")))
                }
                assertThat(SchemaTargets.resolve(dice.values.toList(), ctx.getBean<DatabaseRegistry>()).databases)
                    .containsExactly("neo")
            }
    }

    @Test
    fun `with one datasource DICE's schema targets stay as they were`() {
        runnerWithCollector.withPropertyValues(*datasource("neo")).run { ctx ->
            assertThat(ctx).hasNotFailed()
            val dice = diceCatalogs(ctx)
            assertThat(dice).isNotEmpty()
            dice.forEach { (name, catalog) -> assertThat(catalog.target).describedAs(name).isEqualTo(DatabaseTarget.All) }
        }
    }

    @Test
    fun `with several datasources the collector trace schema goes only to the named datasource`() {
        runnerWithCollector
            .withPropertyValues(
                *datasource("neo"),
                *datasource("other"),
                "embabel.dice.store.datasource=other",
                "embabel.dice.collector.enabled=true",
            )
            .run { ctx -> assertCatalogOnlyOn(ctx, "collectorTraceSchema", "other") }
    }

    @Test
    fun `with one datasource the collector trace schema keeps its original target`() {
        runnerWithCollector
            .withPropertyValues(*datasource("neo"), "embabel.dice.collector.enabled=true")
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx.getBean<SchemaCatalog>("collectorTraceSchema").target).isEqualTo(DatabaseTarget.All)
            }
    }

    @Test
    fun `with several datasources the metamodel schema goes only to the named datasource`() {
        runnerWithMetamodel
            .withPropertyValues(
                *datasource("neo"),
                *datasource("other"),
                "embabel.dice.store.datasource=other",
                "embabel.dice.metamodel.enabled=true",
            )
            .run { ctx -> assertCatalogOnlyOn(ctx, "metamodelSchema", "other") }
    }

    @Test
    fun `with one datasource the metamodel schema keeps its original target`() {
        runnerWithMetamodel
            .withPropertyValues(*datasource("neo"), "embabel.dice.metamodel.enabled=true")
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx.getBean<SchemaCatalog>("metamodelSchema").target).isEqualTo(DatabaseTarget.All)
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
    fun `a StatelessGraphObjectManager alone in a parent context fails startup asking for both managers or neither`() {
        parentWith(HostGraphObjectManager::class.java, *datasource("neo"), "host.datasource=default").run { parent ->
            childOf(parent).run { ctx -> assertAskedForBothOrNeither(ctx, "'hostGraphObjectManager'") }
        }
    }

    @Test
    fun `a PersistenceManager alone in a parent context fails startup asking for both managers or neither`() {
        parentWith(HostPersistenceManager::class.java, *datasource("neo"), "host.datasource=default").run { parent ->
            childOf(parent).run { ctx -> assertAskedForBothOrNeither(ctx, "'hostPersistenceManager'") }
        }
    }

    @Test
    fun `a pair in a parent context means DICE builds nothing, and its schema follows the parent's PersistenceManager`() {
        parentWith(
            HostDrivinePair::class.java,
            *datasource("neo"),
            *datasource("other"),
            "host.datasource=other",
        ).run { parent ->
            childOf(parent).run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx.containsBeanDefinition("persistenceManager")).isFalse()
                assertThat(ctx.containsBeanDefinition("statelessGraphObjectManager")).isFalse()
                assertThat(ctx.getBean<PersistenceManager>()).isSameAs(parent.getBean<PersistenceManager>())
                assertThat(ctx.getBean<StatelessGraphObjectManager>())
                    .isSameAs(parent.getBean<StatelessGraphObjectManager>())
                val dice = diceCatalogs(ctx)
                assertThat(dice).isNotEmpty()
                dice.forEach { (name, catalog) ->
                    assertThat(catalog.target).describedAs(name).isEqualTo(DatabaseTarget.Named(setOf("other")))
                }
            }
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

    private val runnerWithCollector = runner.withConfiguration(
        AutoConfigurations.of(CollectorAutoConfiguration::class.java),
    )

    // Metamodel governance only switches on when the host declares a schema.
    private val runnerWithMetamodel = runner
        .withConfiguration(AutoConfigurations.of(MetamodelAutoConfiguration::class.java))
        .withUserConfiguration(HostDeclaredSchema::class.java)

    /** A parent context with Drivine's factories and the host managers in [hostBeans], and nothing of DICE's. */
    private fun parentWith(hostBeans: Class<*>, vararg properties: String) = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DrivineAutoConfiguration::class.java))
        .withUserConfiguration(hostBeans)
        .withPropertyValues(*properties)

    /** A child of [parent] with DICE's auto-configurations, which inherits Drivine and the parent's managers. */
    private fun childOf(parent: ApplicationContext) = ApplicationContextRunner()
        .withParent(parent)
        .withConfiguration(
            AutoConfigurations.of(
                DiceDrivineAutoConfiguration::class.java,
                DiceStorageAutoConfiguration::class.java,
                CollectorAutoConfiguration::class.java,
            ),
        )
        .withUserConfiguration(StubAi::class.java)

    /** Checks startup stopped because the host defined one Drivine manager, naming the [beans] it found. */
    private fun assertAskedForBothOrNeither(ctx: AssertableApplicationContext, vararg beans: String) {
        assertThat(ctx).hasFailed()
        val message = assertThat(ctx).getFailure().rootCause()
        beans.forEach { message.hasMessageContaining(it) }
        message
            .hasMessageContaining("PersistenceManager")
            .hasMessageContaining("StatelessGraphObjectManager")
            .hasMessageContaining("both")
            .hasMessageContaining("neither")
            .hasMessageContaining("database.datasources")
    }

    /** Checks the catalog bean [name] targets [datasource] alone, and that Drivine would apply it nowhere else. */
    private fun assertCatalogOnlyOn(ctx: AssertableApplicationContext, name: String, datasource: String) {
        assertThat(ctx).hasNotFailed()
        val catalog = ctx.getBean<SchemaCatalog>(name)
        assertThat(catalog.target).isEqualTo(DatabaseTarget.Named(setOf(datasource)))
        assertThat(SchemaTargets.resolve(listOf(catalog), ctx.getBean<DatabaseRegistry>()).databases)
            .containsExactly(datasource)
    }

    /** The schema catalogs DICE's own auto-configurations declared, by bean name. */
    private fun diceCatalogs(ctx: ApplicationContext): Map<String, SchemaCatalog> =
        ctx.getBeansOfType(SchemaCatalog::class.java).filterKeys { it != "hostSchema" }

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

    /** A host with two StatelessGraphObjectManagers, one of them primary, on `host.primary` and `host.secondary`. */
    @Configuration(proxyBeanMethods = false)
    open class PrimaryAmongHostGraphObjectManagers {

        @Bean
        @Primary
        open fun primaryGraphObjectManager(
            factory: GraphObjectManagerFactory,
            @Value("\${host.primary}") datasource: String,
        ): StatelessGraphObjectManager = factory.stateless(datasource)

        @Bean
        open fun otherGraphObjectManager(
            factory: GraphObjectManagerFactory,
            @Value("\${host.secondary}") datasource: String,
        ): StatelessGraphObjectManager = factory.stateless(datasource)
    }

    /** A host with two PersistenceManagers and neither primary. */
    @Configuration(proxyBeanMethods = false)
    open class UnrankedHostPersistenceManagers {

        @Bean
        open fun firstPersistenceManager(
            factory: PersistenceManagerFactory,
            @Value("\${host.primary}") datasource: String,
        ): PersistenceManager = factory.get(datasource)

        @Bean
        open fun secondPersistenceManager(
            factory: PersistenceManagerFactory,
            @Value("\${host.secondary}") datasource: String,
        ): PersistenceManager = factory.get(datasource)
    }

    /** A host that writes both managers itself, for the datasource named by `host.datasource`. */
    @Configuration(proxyBeanMethods = false)
    open class HostDrivinePair {

        @Bean
        open fun hostPersistenceManager(
            factory: PersistenceManagerFactory,
            @Value("\${host.datasource}") datasource: String,
        ): PersistenceManager = factory.get(datasource)

        @Bean
        open fun hostGraphObjectManager(
            factory: GraphObjectManagerFactory,
            @Value("\${host.datasource}") datasource: String,
        ): StatelessGraphObjectManager = factory.stateless(datasource)
    }

    /** A host StatelessGraphObjectManager from some other registry, on a database Drivine here doesn't know. */
    @Configuration(proxyBeanMethods = false)
    open class ExternalHostGraphObjectManager {

        @Bean
        open fun externalGraphObjectManager(): StatelessGraphObjectManager =
            mock<StatelessGraphObjectManager>().also { whenever(it.database).thenReturn("external") }
    }

    /** A host that builds its StatelessGraphObjectManager on whatever PersistenceManager the context has. */
    @Configuration(proxyBeanMethods = false)
    open class HostGraphObjectManagerOnPersistenceManager {

        @Bean
        open fun hostGraphObjectManager(
            persistenceManager: PersistenceManager,
            subtypeRegistry: SubtypeRegistry,
        ): StatelessGraphObjectManager = StatelessGraphObjectManager(persistenceManager, ObjectMapper(), subtypeRegistry)
    }

    /** A host with schema of its own, which DICE leaves where the host put it. */
    @Configuration(proxyBeanMethods = false)
    open class HostSchema {

        @Bean
        open fun hostSchema(): SchemaCatalog = SchemaCatalog.of(RangeIndexSpec("HostThing", "id"))
    }

    /** A host that declares a schema, which is what turns DICE's metamodel governance on. */
    @Configuration(proxyBeanMethods = false)
    open class HostDeclaredSchema {

        @Bean
        open fun declaredSchemaSource(): DeclaredSchemaSource = FixedDeclaredSchemaSource()
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

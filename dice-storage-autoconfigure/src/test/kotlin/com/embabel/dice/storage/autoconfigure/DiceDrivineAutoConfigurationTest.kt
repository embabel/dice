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
import com.embabel.dice.storage.DiceOwnedSchema
import com.embabel.dice.storage.DrivineAuditLogStore
import com.embabel.dice.storage.DrivineChunkHistoryStore
import com.embabel.dice.storage.DrivinePropositionRepository
import com.embabel.dice.storage.SqliteWorldProgression
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
import org.drivine.schema.SchemaManager
import org.drivine.schema.SchemaTargets
import org.drivine.schema.UniquenessConstraintSpec
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.NoSuchBeanDefinitionException
import org.springframework.beans.factory.support.BeanDefinitionOverrideException
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
import org.springframework.transaction.PlatformTransactionManager
import java.nio.file.Path

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

    // All of this is for apps that added dice-starter-neo4j, so the runner carries its marker.
    private val runner = ApplicationContextRunner()
        .withClassLoader(StarterOnClasspath())
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
    fun `world progression opts SQLite out of text uniqueness while keeping proposition ids unique`(@TempDir dir: Path) {
        val sqlite = runner.withPropertyValues(
            "database.datasources.sqlite.type=SQLITE",
            "database.datasources.sqlite.database-name=${dir.resolve("test.db")}",
        )
        val id = UniquenessConstraintSpec("Proposition", "id")
        val text = UniquenessConstraintSpec("Proposition", listOf("contextId", "text"))
        sqlite.run { ctx ->
            ctx.getBean<SchemaManager>().enforce()
            assertThat(ctx.getBean<PersistenceManager>().constraints.find(text)).isNotNull()
        }
        val enabled = sqlite.withPropertyValues("embabel.dice.store.world-progression.enabled=yes")
        enabled.run { ctx ->
            assertThat(ctx).hasNotFailed()
            ctx.getBean<SchemaManager>().enforce()
            val manager = ctx.getBean<PersistenceManager>()
            SqliteWorldProgression.enable(
                ctx.getBean<PropositionRepository>() as DrivinePropositionRepository,
                DrivineAuditLogStore(manager, ctx.getBean<PlatformTransactionManager>()),
            )
            assertThat(manager.constraints.find(text)).isNull()
        }
        enabled.run { ctx ->
            ctx.getBean<SchemaManager>().enforce()
            val constraints = ctx.getBean<PersistenceManager>().constraints
            assertThat(constraints.find(text)).isNull()
            assertThat(constraints.find(id)).isNotNull()
        }
        sqlite.withPropertyValues("embabel.dice.store.world-progression.enabled=typo").run { ctx ->
            assertThat(ctx.startupFailure).hasRootCauseMessage("Invalid boolean value 'typo'")
        }
        runner.withPropertyValues(*datasource("neo"), "embabel.dice.store.world-progression.enabled=true").run { ctx ->
            assertThat(ctx.startupFailure).hasRootCauseMessage("World progression requires SQLite")
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
    fun `with two host PersistenceManagers and no primary DICE's schema follows the one named like the stores' parameter`() {
        // Spring falls back to the bean named after the parameter, so the stores get `persistenceManager`.
        runnerWithCollector
            .withPropertyValues(*datasource("neo"), *datasource("other"), "host.named=other", "host.unnamed=neo")
            .withUserConfiguration(HostManagersNamedLikeParameters::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                val repository = ctx.getBean<PropositionRepository>()
                assertThat(repository).extracting("persistenceManager").isSameAs(ctx.getBean("persistenceManager"))
                val dice = diceCatalogs(ctx)
                assertThat(dice).isNotEmpty()
                dice.forEach { (name, catalog) ->
                    assertThat(catalog.target).describedAs(name).isEqualTo(DatabaseTarget.Named(setOf("other")))
                }
                assertThat(SchemaTargets.resolve(dice.values.toList(), ctx.getBean<DatabaseRegistry>()).databases)
                    .containsExactly("other")
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
    fun `the host audit catalog targets the repository datasource and exposes its ownership shapes`() {
        runnerWithMetamodel
            .withUserConfiguration(DiceStorageSchemaRegistrationTest.AuditSchemaConfig::class.java)
            .withPropertyValues(
                *datasource("neo"),
                *datasource("audit-data"),
                "embabel.dice.store.datasource=audit-data",
                "host.audit.datasource=audit-data",
            )
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                val repository = ctx.getBean<PropositionRepository>()
                val manager = ctx.getBean<PersistenceManager>()
                assertThat(repository).extracting("persistenceManager").isSameAs(manager)
                assertThat(manager.database).isEqualTo("audit-data")
                assertCatalogOnlyOn(ctx, "auditSchemaCatalog", manager.database)

                assertThat(ctx.getBean<DiceOwnedSchema>().nodeShapes)
                    .containsEntry("DiceAuditContext", listOf("contextId"))
                    .containsEntry("DiceAuditRecord", listOf("contextId", "sequence"))
                    .containsEntry("DiceAuditIdentityFence", listOf("stripe"))
            }
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
    fun `forcing in-memory leaves out the step that decides about DICE's managers`() {
        runner
            .withPropertyValues(*datasource("neo"), "embabel.dice.store.type=in-memory")
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx.containsBeanDefinition(DiceDrivineManagerRegistrar.BEAN_NAME)).isFalse()
                assertThat(ctx).doesNotHaveBean(PersistenceManager::class.java)
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
    fun `a host auto-configuration with both managers ordered only before DICE's storage keeps its own`() {
        // Sorted by name, the host lands after DICE's Drivine auto-configuration, so DICE is parsed first
        // and can't see the host's managers yet. Its bean names are the ones hosts on main use.
        assertParsedAfterDiceDrivine(HostManagersBeforeStorage::class.java)
        runner
            .withConfiguration(AutoConfigurations.of(HostManagersBeforeStorage::class.java))
            .withPropertyValues(*datasource("neo"), "host.datasource=neo")
            .run { ctx -> assertUsesHostManagers(ctx, HostManagersBeforeStorage::class.java) }
    }

    @Test
    fun `a host auto-configuration with both managers ordered after DICE's Drivine one keeps its own`() {
        assertParsedAfterDiceDrivine(HostManagersAfterDiceDrivine::class.java)
        runner
            .withConfiguration(AutoConfigurations.of(HostManagersAfterDiceDrivine::class.java))
            .withPropertyValues(*datasource("neo"), "host.datasource=neo")
            .run { ctx -> assertUsesHostManagers(ctx, HostManagersAfterDiceDrivine::class.java) }
    }

    @Test
    fun `a host auto-configuration with only a PersistenceManager fails startup asking for both managers or neither`() {
        runner
            .withConfiguration(AutoConfigurations.of(HostPersistenceManagerBeforeStorage::class.java))
            .withPropertyValues(*datasource("neo"), "host.datasource=neo")
            .run { ctx ->
                assertAskedForBothOrNeither(ctx, "'persistenceManager'")
                var cause: Throwable? = ctx.startupFailure
                while (cause != null) {
                    assertThat(cause).isNotInstanceOf(BeanDefinitionOverrideException::class.java)
                    cause = cause.cause
                }
            }
    }

    @Test
    fun `with no host managers anywhere DICE builds both and its Drivine stores get them`() {
        runner.withPropertyValues(*datasource("neo")).run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx.containsBeanDefinition("persistenceManager")).isTrue()
            assertThat(ctx.containsBeanDefinition("statelessGraphObjectManager")).isTrue()
            val repository = ctx.getBean<PropositionRepository>()
            assertThat(repository).isInstanceOf(DrivinePropositionRepository::class.java)
            assertThat(repository).extracting("persistenceManager").isSameAs(ctx.getBean<PersistenceManager>())
            assertThat(repository).extracting("graphObjectManager").isSameAs(ctx.getBean<StatelessGraphObjectManager>())
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

    @Test
    fun `type=graph with only a host PersistenceManager doesn't ask for both or neither`() {
        // On main this app starts, because it replaced every store that takes the other manager.
        runner
            .withPropertyValues(*datasource("neo"), "host.datasource=neo", "embabel.dice.store.type=graph")
            .withUserConfiguration(HostPersistenceManager::class.java, HostStoresOnGraphObjectManager::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx.containsBeanDefinition("persistenceManager")).isFalse()
                assertThat(ctx.containsBeanDefinition("statelessGraphObjectManager")).isFalse()
                assertThat(ctx).doesNotHaveBean(StatelessGraphObjectManager::class.java)
            }
    }

    @Test
    fun `without the starter, Drivine datasources and the host's own pair leave DICE in memory and out of the database`() {
        withoutStarter
            .withPropertyValues(*datasource("neo"), "host.datasource=neo")
            .withUserConfiguration(HostDrivinePair::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx.getBean<PropositionRepository>()).isInstanceOf(InMemoryPropositionRepository::class.java)
                assertThat(ctx.getBean<ChunkHistoryStore>()).isInstanceOf(InMemoryChunkHistoryStore::class.java)
                assertThat(ctx).hasSingleBean(PersistenceManager::class.java)
                assertThat(ctx).hasSingleBean(StatelessGraphObjectManager::class.java)
                assertNothingFromDiceDrivine(ctx)
                assertThat(ctx.getBeansOfType(SchemaCatalog::class.java)).isEmpty()
            }
    }

    @Test
    fun `without the starter, Drivine datasources and no managers leave DICE in memory with no PersistenceManager`() {
        // Two datasources and none named, which would stop startup if DICE tried to build its managers.
        withoutStarter.withPropertyValues(*datasource("neo"), *datasource("other")).run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx.getBean<PropositionRepository>()).isInstanceOf(InMemoryPropositionRepository::class.java)
            assertThat(ctx.getBean<ChunkHistoryStore>()).isInstanceOf(InMemoryChunkHistoryStore::class.java)
            assertThat(ctx).doesNotHaveBean(PersistenceManager::class.java)
            assertThat(ctx).doesNotHaveBean(StatelessGraphObjectManager::class.java)
            assertNothingFromDiceDrivine(ctx)
            assertThat(ctx.getBeansOfType(SchemaCatalog::class.java)).isEmpty()
        }
    }

    @Test
    fun `without the starter, type=graph with the host's own pair wires DICE's Drivine stores on them`() {
        withoutStarter
            .withPropertyValues(*datasource("neo"), "host.datasource=neo", "embabel.dice.store.type=graph")
            .withUserConfiguration(HostDrivinePair::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                val repository = ctx.getBean<PropositionRepository>()
                assertThat(repository).isInstanceOf(DrivinePropositionRepository::class.java)
                assertThat(repository).extracting("persistenceManager").isSameAs(ctx.getBean("hostPersistenceManager"))
                assertThat(repository).extracting("graphObjectManager").isSameAs(ctx.getBean("hostGraphObjectManager"))
                assertThat(ctx.getBean<ChunkHistoryStore>()).isInstanceOf(DrivineChunkHistoryStore::class.java)
                assertNothingFromDiceDrivine(ctx)
            }
    }

    @Test
    fun `without the starter, type=graph with only a host PersistenceManager starts as on main when its stores don't need the other`() {
        withoutStarter
            .withPropertyValues(*datasource("neo"), "host.datasource=neo", "embabel.dice.store.type=graph")
            .withUserConfiguration(HostPersistenceManager::class.java, HostStoresOnGraphObjectManager::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertNothingFromDiceDrivine(ctx)
            }
    }

    @Test
    fun `without the starter, type=graph with only a host PersistenceManager fails as on main, on the missing bean`() {
        withoutStarter
            .withPropertyValues(*datasource("neo"), "host.datasource=neo", "embabel.dice.store.type=graph")
            .withUserConfiguration(HostPersistenceManager::class.java)
            .run { ctx ->
                assertThat(ctx).hasFailed()
                assertThat(ctx).getFailure().rootCause()
                    .isInstanceOf(NoSuchBeanDefinitionException::class.java)
                    .hasMessageContaining("StatelessGraphObjectManager")
                    .hasMessageNotContaining("neither")
            }
    }

    /** DICE's auto-configurations with no dice-starter-neo4j marker, which the test classpath doesn't have. */
    private val withoutStarter = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DrivineAutoConfiguration::class.java,
                DiceDrivineAutoConfiguration::class.java,
                DiceStorageAutoConfiguration::class.java,
                CollectorAutoConfiguration::class.java,
            ),
        )
        .withUserConfiguration(StubAi::class.java)

    /** Checks DICE's Drivine auto-configuration added nothing: no managers, no registrar, no schema targeting. */
    private fun assertNothingFromDiceDrivine(ctx: AssertableApplicationContext) {
        assertThat(ctx).doesNotHaveBean(DiceDrivineAutoConfiguration::class.java)
        assertThat(ctx.containsBeanDefinition(DiceDrivineManagerRegistrar.BEAN_NAME)).isFalse()
        assertThat(ctx.containsBeanDefinition("persistenceManager")).isFalse()
        assertThat(ctx.containsBeanDefinition("statelessGraphObjectManager")).isFalse()
        assertThat(ctx).doesNotHaveBean(DiceSchemaTargeting::class.java)
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
        .withClassLoader(StarterOnClasspath())
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

    /** Checks [host] really is parsed after DICE's Drivine auto-configuration, which is what makes it hard. */
    private fun assertParsedAfterDiceDrivine(host: Class<*>) {
        val ordered = Configurations.getClasses(
            AutoConfigurations.of(
                DrivineAutoConfiguration::class.java,
                DiceDrivineAutoConfiguration::class.java,
                DiceStorageAutoConfiguration::class.java,
                host,
            ),
        )
        assertThat(ordered.indexOf(host)).isGreaterThan(ordered.indexOf(DiceDrivineAutoConfiguration::class.java))
        assertThat(ordered.indexOf(host)).isLessThan(ordered.indexOf(DiceStorageAutoConfiguration::class.java))
    }

    /** Checks the context started on [host]'s two managers alone and DICE's Drivine stores were given them. */
    private fun assertUsesHostManagers(ctx: AssertableApplicationContext, host: Class<*>) {
        assertThat(ctx).hasNotFailed()
        assertThat(ctx).hasSingleBean(PersistenceManager::class.java)
        assertThat(ctx).hasSingleBean(StatelessGraphObjectManager::class.java)
        val factory = ctx.sourceApplicationContext.beanFactory
        listOf("persistenceManager", "graphObjectManager").forEach { name ->
            assertThat(factory.getType(factory.getBeanDefinition(name).factoryBeanName!!))
                .describedAs("%s comes from the host", name)
                .isEqualTo(host)
        }
        assertThat(ctx.containsBeanDefinition("statelessGraphObjectManager")).isFalse()
        val repository = ctx.getBean<PropositionRepository>()
        assertThat(repository).isInstanceOf(DrivinePropositionRepository::class.java)
        assertThat(repository).extracting("persistenceManager").isSameAs(ctx.getBean("persistenceManager"))
        assertThat(repository).extracting("graphObjectManager").isSameAs(ctx.getBean("graphObjectManager"))
        assertThat(ctx.getBean<ChunkHistoryStore>()).isInstanceOf(DrivineChunkHistoryStore::class.java)
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

    /**
     * A host with two of each manager and neither primary. One of each is named like DICE's store parameters,
     * on `host.named`; the others sit on `host.unnamed`.
     */
    @Configuration(proxyBeanMethods = false)
    open class HostManagersNamedLikeParameters {

        @Bean
        open fun persistenceManager(
            factory: PersistenceManagerFactory,
            @Value("\${host.named}") datasource: String,
        ): PersistenceManager = factory.get(datasource)

        @Bean
        open fun otherPersistenceManager(
            factory: PersistenceManagerFactory,
            @Value("\${host.unnamed}") datasource: String,
        ): PersistenceManager = factory.get(datasource)

        @Bean
        open fun graphObjectManager(
            factory: GraphObjectManagerFactory,
            @Value("\${host.named}") datasource: String,
        ): StatelessGraphObjectManager = factory.stateless(datasource)

        @Bean
        open fun otherGraphObjectManager(
            factory: GraphObjectManagerFactory,
            @Value("\${host.unnamed}") datasource: String,
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

    /**
     * A host's own auto-configuration with both managers, ordered before DICE's storage the way
     * `StoreBackendCheck` tells hosts to, and with no order against DICE's Drivine auto-configuration.
     */
    @AutoConfiguration(before = [DiceStorageAutoConfiguration::class])
    open class HostManagersBeforeStorage {

        @Bean
        open fun persistenceManager(
            factory: PersistenceManagerFactory,
            @Value("\${host.datasource}") datasource: String,
        ): PersistenceManager = factory.get(datasource)

        @Bean
        open fun graphObjectManager(
            factory: GraphObjectManagerFactory,
            @Value("\${host.datasource}") datasource: String,
        ): StatelessGraphObjectManager = factory.stateless(datasource)
    }

    /** The same host auto-configuration, ordered explicitly after DICE's Drivine one. */
    @AutoConfiguration(after = [DiceDrivineAutoConfiguration::class], before = [DiceStorageAutoConfiguration::class])
    open class HostManagersAfterDiceDrivine {

        @Bean
        open fun persistenceManager(
            factory: PersistenceManagerFactory,
            @Value("\${host.datasource}") datasource: String,
        ): PersistenceManager = factory.get(datasource)

        @Bean
        open fun graphObjectManager(
            factory: GraphObjectManagerFactory,
            @Value("\${host.datasource}") datasource: String,
        ): StatelessGraphObjectManager = factory.stateless(datasource)
    }

    /** A host auto-configuration that defines only the PersistenceManager. */
    @AutoConfiguration(before = [DiceStorageAutoConfiguration::class])
    open class HostPersistenceManagerBeforeStorage {

        @Bean
        open fun persistenceManager(
            factory: PersistenceManagerFactory,
            @Value("\${host.datasource}") datasource: String,
        ): PersistenceManager = factory.get(datasource)
    }

    /** A host's own versions of the two stores that take a StatelessGraphObjectManager, so it needs only a PersistenceManager. */
    @Configuration(proxyBeanMethods = false)
    open class HostStoresOnGraphObjectManager {

        @Bean
        open fun hostPropositionRepository(): PropositionRepository = InMemoryPropositionRepository(mock<EmbeddingService>())

        @Bean
        open fun hostChunkHistoryStore(): ChunkHistoryStore = InMemoryChunkHistoryStore()
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

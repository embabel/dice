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
import com.embabel.agent.core.ContextId
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.storage.DiceStorageTransactions
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.drivine.connection.ConnectionProvider
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.connection.JdbcConnectionProvider
import org.drivine.manager.DelegatingPersistenceManager
import org.drivine.manager.PersistenceManager
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.transaction.DrivineTransactionManager
import org.drivine.transaction.TransactionContextHolder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.aop.framework.Advised
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.ConnectionHandle
import org.springframework.jdbc.datasource.SimpleConnectionHandle
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.orm.jpa.DefaultJpaDialect
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.TransactionTemplate
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

/**
 * Proves that a real consumer of `dice-storage-autoconfigure` gets working `@Transactional` advice
 * on the graph-backed [PropositionRepository] bean, going beyond confirming the bean merely exists.
 *
 * A `PlatformTransactionManager` bean alone is not enough: Spring only wires the interceptor that
 * reads `@Transactional` when transaction management is actually enabled somewhere. Boot 4 moved
 * that wiring into the separate `spring-boot-transaction` module. Both tests below run the actual
 * bean through the actual autoconfiguration stack and inspect the resulting object's proxy
 * advisors, so a regression here (e.g. dropping the `spring-boot-transaction` dependency again)
 * fails a concrete assertion, catching it even if the annotation itself silently stays inert.
 */
@ExtendWith(OutputCaptureExtension::class)
class DiceStorageTransactionAutoConfigurationTest {

    private val baseRunner = ApplicationContextRunner()
        .withUserConfiguration(GraphBackendStubConfig::class.java)
        .withPropertyValues("embabel.dice.store.type=graph")

    @Test
    fun `with spring-boot-transaction autoconfiguration, the proposition store bean is a real transactional proxy`() {
        baseRunner
            .withConfiguration(
                AutoConfigurations.of(
                    DiceStorageAutoConfiguration::class.java,
                    TransactionAutoConfiguration::class.java,
                )
            )
            .run { ctx ->
                val repository = ctx.getBean<PropositionRepository>()

                assertThat(AopUtils.isAopProxy(repository))
                    .withFailMessage("expected the proposition store bean to be an AOP proxy once transaction " +
                        "management is enabled, but it was a plain %s", repository::class.java)
                    .isTrue()

                val advisors = (repository as Advised).advisors.toList()
                assertThat(advisors.any { it.advice is TransactionInterceptor })
                    .withFailMessage("expected a TransactionInterceptor advisor on the proxy, found: %s", advisors)
                    .isTrue()
            }
    }

    /**
     * Same wiring with [TransactionAutoConfiguration] left out, standing in for what every consumer
     * got before `spring-boot-transaction` was added as a dependency: a plain, unproxied bean. This
     * is what pinned P1-b down in the first place — the `PlatformTransactionManager` bean existing
     * was never sufficient on its own.
     */
    @Test
    fun `without spring-boot-transaction autoconfiguration, the proposition store bean is not proxied`() {
        baseRunner
            .withConfiguration(AutoConfigurations.of(DiceStorageAutoConfiguration::class.java))
            .run { ctx ->
                val repository = ctx.getBean<PropositionRepository>()
                assertThat(AopUtils.isAopProxy(repository)).isFalse()
            }
    }

    /**
     * A host that brings a transaction manager of its own (for its own database) turns Drivine's
     * away, since Drivine registers one only when there is none. The proposition store must still
     * run its saves in Drivine transactions, or each statement would commit on its own.
     */
    @Test
    fun `with a host's own transaction manager, the proposition store still saves in Drivine transactions`() {
        baseRunner
            .withConfiguration(AutoConfigurations.of(DiceStorageAutoConfiguration::class.java))
            .withBean(TransactionContextHolder::class.java, { mock<TransactionContextHolder>() })
            .run { ctx ->
                val repository = ctx.getBean<PropositionRepository>()
                val saves = ReflectionTestUtils.getField(repository, "txTemplate") as TransactionTemplate

                assertThat(saves.transactionManager).isInstanceOf(DrivineTransactionManager::class.java)
            }
    }

    /**
     * A store method that only carries `@Transactional`, with no template of its own, runs in a
     * Drivine transaction too when the host brings a manager of its own, so its statements commit
     * together. The host's own `@Transactional` code still gets the host's manager.
     */
    @Test
    fun `with a host's own transaction manager, an annotated store method runs in a Drivine transaction`() {
        val holder = TransactionContextHolder(mock<DatabaseRegistry>())
        val seen = mutableListOf<Any?>()
        baseRunner
            .withConfiguration(
                AutoConfigurations.of(DiceStorageAutoConfiguration::class.java, TransactionAutoConfiguration::class.java),
            )
            .withBean(TransactionContextHolder::class.java, { holder })
            .run { ctx ->
                val hostManager = ctx.getBean<PlatformTransactionManager>()
                doAnswer { seen += holder.currentTransaction; null }
                    .whenever(ctx.getBean<PersistenceManager>()).execute(any())

                ctx.getBean<ChunkHistoryStore>().clearByContext(ContextId("annotated"))

                assertThat(seen).hasSize(1)
                assertThat(seen.single()).isNotNull()
                assertThat(hostManager).isSameAs(ctx.getBean("platformTransactionManager"))
            }
    }

    /**
     * The one wiring DICE supports where a host's JDBC manager and Drivine share a SQLite file:
     * Drivine reads it through a transaction-aware proxy over the host's data source. Drivine's
     * statements then run on the host's connection in the host's transaction, and a Drivine
     * transaction of the store's own would commit the host's work with it. The store keeps the
     * host's manager.
     */
    @Test
    fun `with a host's JDBC manager and Drivine behind a transaction-aware proxy, the proposition store keeps it`() {
        val target = drivinePool(Files.createTempFile("dice-host-jdbc-", ".db"))
        val hostManager = DataSourceTransactionManager(target)

        sharedFileRunner(TransactionAwareDataSourceProxy(target), hostManager).run { ctx ->
            val repository = ctx.getBean<PropositionRepository>()
            val saves = ReflectionTestUtils.getField(repository, "txTemplate") as TransactionTemplate

            assertThat(saves.transactionManager).isSameAs(hostManager)
        }
    }

    /**
     * A pool the host builds itself, read by Drivine through the proxy. Its transactions start
     * IMMEDIATE, but its connections lack the SQL functions Drivine registers only on its own pool,
     * so Drivine's first statement on it would fail. Refused at startup, naming the pool to use.
     */
    @Test
    fun `a host-built SQLite pool behind the proxy lacks Drivine's functions and is refused at startup`() {
        val target = sqliteDataSource(Files.createTempFile("dice-host-built-", ".db"))

        sharedFileRunner(TransactionAwareDataSourceProxy(target), DataSourceTransactionManager(target)).run { ctx ->
            assertThat(ctx).hasFailed()
            assertThat(ctx.startupFailure).rootCause().hasMessageContaining("JdbcConnectionProvider.sqlite")
        }
    }

    /**
     * The host's default manager serves another database, and a second JDBC manager owns Drivine's
     * pool, which Drivine reads through the proxy. The stores take the second manager, so their
     * writes run in its transactions, where a Drivine transaction of their own would commit them.
     */
    @Test
    fun `a second JDBC manager that owns Drivine's pool behind the proxy is the stores' manager`() {
        val target = drivinePool(Files.createTempFile("dice-host-second-", ".db"))
        val owner = DataSourceTransactionManager(target)

        sharedFileRunner(TransactionAwareDataSourceProxy(target), mock())
            .withBean("graphManager", PlatformTransactionManager::class.java, { owner }, { it.isPrimary = false })
            .withBean(
                "otherDatabaseManager",
                PlatformTransactionManager::class.java,
                { DataSourceTransactionManager(sqliteDataSource(Files.createTempFile("dice-host-other-", ".db"))) },
                { it.isPrimary = true },
            )
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                val saves = ReflectionTestUtils.getField(ctx.getBean<PropositionRepository>(), "txTemplate") as TransactionTemplate

                assertThat(saves.transactionManager).isSameAs(owner)
                assertThat(ctx.getBean(DiceStorageTransactions.MANAGER)).isSameAs(owner)
            }
    }

    /** Two managers over Drivine's pool behind the proxy: which transaction a store write is in cannot be told. Refused. */
    @Test
    fun `two managers that own Drivine's pool behind the proxy are refused at startup`() {
        val target = drivinePool(Files.createTempFile("dice-host-two-owners-", ".db"))

        sharedFileRunner(TransactionAwareDataSourceProxy(target), mock())
            .withBean("firstOwner", PlatformTransactionManager::class.java, { DataSourceTransactionManager(target) }, { it.isPrimary = true })
            .withBean("secondOwner", PlatformTransactionManager::class.java, { DataSourceTransactionManager(target) }, { it.isPrimary = false })
            .run { ctx ->
                assertThat(ctx).hasFailed()
                assertThat(ctx.startupFailure).rootCause().hasMessageContaining("each manage")
            }
    }

    /**
     * With no proposition or run store in the context, the graph's other stores still use the
     * SQLite file, so a pool without Drivine's functions is refused at startup all the same.
     */
    @Test
    fun `a host-built SQLite pool is refused at startup when only the graph's other stores are configured`() {
        val provider = mock<JdbcConnectionProvider>()
        whenever(provider.nativeDriver).thenReturn(sqliteDataSource(Files.createTempFile("dice-host-chunks-", ".db")))
        val registry = mock<DatabaseRegistry>()
        whenever(registry.connectionProvider("neo")).thenReturn(provider)
        val persistenceManager = mock<PersistenceManager>()
        whenever(persistenceManager.type).thenReturn(DatabaseType.SQLITE)
        whenever(persistenceManager.database).thenReturn("neo")

        ApplicationContextRunner()
            .withPropertyValues("embabel.dice.store.type=graph")
            .withConfiguration(AutoConfigurations.of(DiceStorageAutoConfiguration::class.java))
            .withBean(StatelessGraphObjectManager::class.java, { mock<StatelessGraphObjectManager>() })
            .withBean(TransactionContextHolder::class.java, { mock<TransactionContextHolder>() })
            .withBean(DatabaseRegistry::class.java, { registry })
            .withBean(PersistenceManager::class.java, { persistenceManager })
            .withBean(PlatformTransactionManager::class.java, { mock<PlatformTransactionManager>() })
            .run { ctx ->
                assertThat(ctx).hasFailed()
                assertThat(ctx.startupFailure).rootCause().hasMessageContaining("JdbcConnectionProvider.sqlite")
            }
    }

    /**
     * The host's manager and Drivine on one raw pool: each takes its own connection, so a write
     * the store makes in the host's transaction would wait for the host's own write lock. Refused.
     */
    @Test
    fun `a host's JDBC manager sharing a raw pool with Drivine is refused at startup`() {
        val target = sqliteDataSource(Files.createTempFile("dice-host-raw-", ".db"))

        sharedFileRunner(target, DataSourceTransactionManager(target)).run { ctx ->
            assertThat(ctx).hasFailed()
            assertThat(ctx.startupFailure).rootCause()
                .hasMessageContaining("TransactionAwareDataSourceProxy")
        }
    }

    /** The same file through two pools of its own is the same lock and the same refusal. */
    @Test
    fun `a host's JDBC manager on another pool over Drivine's SQLite file is refused at startup`() {
        val file = Files.createTempFile("dice-host-two-pools-", ".db")

        sharedFileRunner(sqliteDataSource(file), DataSourceTransactionManager(sqliteDataSource(file))).run { ctx ->
            assertThat(ctx).hasFailed()
            assertThat(ctx.startupFailure).rootCause()
                .hasMessageContaining("TransactionAwareDataSourceProxy")
        }
    }

    /** A SQLite store whose transaction mode cannot be read could be DEFERRED. Refused. */
    @Test
    fun `a SQLite store whose transaction mode cannot be read is refused at startup`() {
        val registry = mock<DatabaseRegistry>()
        whenever(registry.connectionProvider("neo")).thenReturn(mock<ConnectionProvider>())

        sqliteRunner(registry, mock()).run { ctx ->
            assertThat(ctx).hasFailed()
            assertThat(ctx.startupFailure).rootCause().hasMessageContaining("transaction mode")
        }
    }

    /**
     * A host that builds Drivine by hand publishes no transaction state as a bean, but Drivine's
     * persistence manager carries its own. The stores use Drivine's manager over it.
     */
    @Test
    fun `without Drivine's transaction state as a bean, the store finds it on the persistence manager`(output: CapturedOutput) {
        val delegating = mock<DelegatingPersistenceManager>()
        whenever(delegating.contextHolder).thenReturn(mock<TransactionContextHolder>())

        ApplicationContextRunner()
            .withUserConfiguration(SharedDataSourceStubConfig::class.java)
            .withPropertyValues("embabel.dice.store.type=graph")
            .withConfiguration(AutoConfigurations.of(DiceStorageAutoConfiguration::class.java))
            .withBean(PersistenceManager::class.java, { delegating })
            .withBean(PlatformTransactionManager::class.java, { mock<PlatformTransactionManager>() })
            .run { ctx ->
                val repository = ctx.getBean<PropositionRepository>()
                val saves = ReflectionTestUtils.getField(repository, "txTemplate") as TransactionTemplate

                assertThat(saves.transactionManager).isInstanceOf(DrivineTransactionManager::class.java)
                assertThat(output.out).doesNotContain("not Drivine's")
            }
    }

    /**
     * A lazy proxy between the transaction-aware proxy and the pool the manager binds: the outer
     * proxy looks the host's connection up under the lazy one, finds none, and takes a second
     * connection to the file. Refused.
     */
    @Test
    fun `a transaction-aware proxy over another wrapper of the host's pool is refused at startup`() {
        val target = sqliteDataSource(Files.createTempFile("dice-host-lazy-", ".db"))

        sharedFileRunner(
            TransactionAwareDataSourceProxy(LazyConnectionDataSourceProxy(target)),
            DataSourceTransactionManager(target),
        ).run { ctx ->
            assertThat(ctx).hasFailed()
            assertThat(ctx.startupFailure).rootCause().hasMessageContaining("TransactionAwareDataSourceProxy")
        }
    }

    /** One file named once as a path and once as a `file:` URI with an escaped space is still one file. */
    @Test
    fun `a host's pool naming Drivine's SQLite file as an escaped file URI is refused at startup`() {
        val dir = Files.createTempDirectory("dice host spaced")
        val file = dir.resolve("store.db")
        val uri = "jdbc:sqlite:file:" + file.toString().replace(" ", "%20")

        sharedFileRunner(sqliteDataSource(file), DataSourceTransactionManager(sqliteDataSource(uri))).run { ctx ->
            assertThat(ctx).hasFailed()
            assertThat(ctx.startupFailure).rootCause().hasMessageContaining("TransactionAwareDataSourceProxy")
        }
    }

    /**
     * A host that builds Drivine by hand publishes no registry bean. The SQLite store's transaction
     * mode is read through the registry Drivine's own persistence manager carries, and it starts.
     */
    @Test
    fun `without a registry bean, the SQLite store reads its data source from Drivine's persistence manager`() {
        val provider = mock<JdbcConnectionProvider>()
        whenever(provider.nativeDriver).thenReturn(drivinePool(Files.createTempFile("dice-hand-built-", ".db")))
        val registry = mock<DatabaseRegistry>()
        whenever(registry.connectionProvider("neo")).thenReturn(provider)
        val holder = mock<TransactionContextHolder>()
        whenever(holder.databaseRegistry).thenReturn(registry)
        val delegating = mock<DelegatingPersistenceManager>()
        whenever(delegating.contextHolder).thenReturn(holder)
        whenever(delegating.type).thenReturn(DatabaseType.SQLITE)
        whenever(delegating.database).thenReturn("neo")

        ApplicationContextRunner()
            .withUserConfiguration(SharedDataSourceStubConfig::class.java)
            .withPropertyValues("embabel.dice.store.type=graph")
            .withConfiguration(AutoConfigurations.of(DiceStorageAutoConfiguration::class.java))
            .withBean(PersistenceManager::class.java, { delegating })
            .withBean(PlatformTransactionManager::class.java, { mock<PlatformTransactionManager>() })
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).hasSingleBean(PropositionRepository::class.java)
            }
    }

    /**
     * A JPA manager names its entity manager factory as its resource, and its data source through
     * `getDataSource()`. When its dialect binds the JDBC connection, as Hibernate's does, the store
     * keeps the JPA manager behind a transaction-aware proxy over that data source, so a store write
     * runs in the host's transaction instead of committing it.
     */
    @Test
    fun `with a host's JPA manager and Drivine behind a transaction-aware proxy, the proposition store keeps it`() {
        val target = drivinePool(Files.createTempFile("dice-host-jpa-", ".db"))
        val jpa = JpaTransactionManager(mock<EntityManagerFactory>()).apply {
            dataSource = target
            setJpaDialect(JdbcExposingDialect(target))
        }

        sharedFileRunner(TransactionAwareDataSourceProxy(target), jpa).run { ctx ->
            val repository = ctx.getBean<PropositionRepository>()
            val saves = ReflectionTestUtils.getField(repository, "txTemplate") as TransactionTemplate

            assertThat(saves.transactionManager).isSameAs(jpa)
        }
    }

    /**
     * Spring's default JPA dialect binds no JDBC connection, so the proxy would hand Drivine a
     * connection of its own outside the host's transaction. Refused.
     */
    @Test
    fun `a host's JPA manager whose dialect binds no JDBC connection is refused behind the proxy`() {
        val target = sqliteDataSource(Files.createTempFile("dice-host-jpa-plain-", ".db"))
        val jpa = JpaTransactionManager(mock<EntityManagerFactory>()).apply { dataSource = target }

        sharedFileRunner(TransactionAwareDataSourceProxy(target), jpa).run { ctx ->
            assertThat(ctx).hasFailed()
            assertThat(ctx.startupFailure).rootCause().hasMessageContaining("binds no JDBC connection")
        }
    }

    /**
     * With Drivine's transaction state both as a bean and on its persistence manager, the store's
     * manager is made over the persistence manager's: that is the state its statements consult.
     */
    @Test
    fun `the persistence manager's own transaction state wins over the bean`() {
        val own = mock<TransactionContextHolder>()
        val delegating = mock<DelegatingPersistenceManager>()
        whenever(delegating.contextHolder).thenReturn(own)

        ApplicationContextRunner()
            .withUserConfiguration(SharedDataSourceStubConfig::class.java)
            .withPropertyValues("embabel.dice.store.type=graph")
            .withConfiguration(AutoConfigurations.of(DiceStorageAutoConfiguration::class.java))
            .withBean(TransactionContextHolder::class.java, { mock<TransactionContextHolder>() })
            .withBean(PersistenceManager::class.java, { delegating })
            .withBean(PlatformTransactionManager::class.java, { mock<PlatformTransactionManager>() })
            .run { ctx ->
                val saves = ReflectionTestUtils.getField(ctx.getBean<PropositionRepository>(), "txTemplate") as TransactionTemplate

                assertThat(ReflectionTestUtils.getField(saves.transactionManager!!, "contextHolder")).isSameAs(own)
            }
    }

    /**
     * A dialect that overrides the connection hook but returns no handle cannot be told from one
     * that returns a real handle until a transaction runs, so startup accepts it. The KDoc on the
     * check says a custom dialect has to return a real handle; this pins what startup can see.
     */
    @Test
    fun `a JPA dialect that returns no handle cannot be told apart at startup`() {
        val target = drivinePool(Files.createTempFile("dice-host-jpa-null-", ".db"))
        val jpa = JpaTransactionManager(mock<EntityManagerFactory>()).apply {
            dataSource = target
            setJpaDialect(NullHandleDialect())
        }

        sharedFileRunner(TransactionAwareDataSourceProxy(target), jpa).run { ctx ->
            assertThat(ctx).hasNotFailed()
        }
    }

    /** A JPA manager and Drivine on one raw pool is the same second connection, and the same refusal. */
    @Test
    fun `a host's JPA manager sharing a raw pool with Drivine is refused at startup`() {
        val target = sqliteDataSource(Files.createTempFile("dice-host-jpa-raw-", ".db"))
        val jpa = JpaTransactionManager(mock<EntityManagerFactory>()).apply { dataSource = target }

        sharedFileRunner(target, jpa).run { ctx ->
            assertThat(ctx).hasFailed()
            assertThat(ctx.startupFailure).rootCause().hasMessageContaining("TransactionAwareDataSourceProxy")
        }
    }

    /** Two hard links to one SQLite file are one file, and one write lock. */
    @Test
    fun `a host's pool over a hard link to Drivine's SQLite file is refused at startup`() {
        val file = Files.createTempFile("dice-host-linked-", ".db")
        sqliteDataSource(file).connection.use { it.createStatement().execute("CREATE TABLE IF NOT EXISTS t (x INTEGER)") }
        val link = file.resolveSibling(file.fileName.toString() + ".link")
        Files.createLink(link, file)

        sharedFileRunner(sqliteDataSource(file), DataSourceTransactionManager(sqliteDataSource(link))).run { ctx ->
            assertThat(ctx).hasFailed()
            assertThat(ctx.startupFailure).rootCause().hasMessageContaining("TransactionAwareDataSourceProxy")
        }
    }

    private fun sqliteDataSource(url: String): DataSource =
        SQLiteDataSource(SQLiteConfig().apply { setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE) })
            .apply { this.url = url }

    /** A pool a host builds itself over [file]: IMMEDIATE, as DICE asks, but without Drivine's functions. */
    private fun sqliteDataSource(file: Path): DataSource =
        SQLiteDataSource(SQLiteConfig().apply { setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE) })
            .apply { url = "jdbc:sqlite:$file" }

    /** Drivine's own SQLite pool over [file], whose connections carry the functions its statements call. */
    private fun drivinePool(file: Path): DataSource =
        JdbcConnectionProvider.sqlite(name = "pool-${file.fileName}", path = file.toString()).nativeDriver

    /** The graph store on SQLite, Drivine reading [drivineDataSource], the host's manager [hostManager]. */
    private fun sharedFileRunner(drivineDataSource: DataSource, hostManager: PlatformTransactionManager): ApplicationContextRunner {
        val provider = mock<JdbcConnectionProvider>()
        whenever(provider.nativeDriver).thenReturn(drivineDataSource)
        val registry = mock<DatabaseRegistry>()
        whenever(registry.connectionProvider("neo")).thenReturn(provider)
        return sqliteRunner(registry, hostManager)
    }

    private fun sqliteRunner(registry: DatabaseRegistry, hostManager: PlatformTransactionManager): ApplicationContextRunner {
        val persistenceManager = mock<PersistenceManager>()
        whenever(persistenceManager.type).thenReturn(DatabaseType.SQLITE)
        whenever(persistenceManager.database).thenReturn("neo")
        return ApplicationContextRunner()
            .withUserConfiguration(SharedDataSourceStubConfig::class.java)
            .withPropertyValues("embabel.dice.store.type=graph")
            .withConfiguration(AutoConfigurations.of(DiceStorageAutoConfiguration::class.java))
            .withBean(TransactionContextHolder::class.java, { mock<TransactionContextHolder>() })
            .withBean(DatabaseRegistry::class.java, { registry })
            .withBean(PersistenceManager::class.java, { persistenceManager })
            .withBean(PlatformTransactionManager::class.java, { hostManager })
    }

    /**
     * A host that builds Drivine by hand and publishes no transaction state leaves nothing to make
     * Drivine's manager from. The stores fall back to the host's, and the log says so.
     */
    @Test
    fun `without Drivine's transaction state, falling back to the host's manager is logged`(output: CapturedOutput) {
        baseRunner
            .withConfiguration(AutoConfigurations.of(DiceStorageAutoConfiguration::class.java))
            .run { ctx ->
                ctx.getBean<PropositionRepository>()

                assertThat(output.out).contains("not Drivine's")
            }
    }

    /** What [GraphBackendStubConfig] gives, less the persistence and transaction managers. */
    @Configuration(proxyBeanMethods = false)
    class SharedDataSourceStubConfig {
        @Bean
        fun ai(): Ai = GraphBackendStubConfig().ai()

        @Bean
        fun graphObjectManager(): StatelessGraphObjectManager = mock()
    }

    @Configuration(proxyBeanMethods = false)
    class GraphBackendStubConfig {
        @Bean
        fun ai(): Ai {
            val ai = mock<Ai>()
            val embeddingService = mock<EmbeddingService>()
            whenever(embeddingService.dimensions).thenReturn(1536)
            whenever(embeddingService.name).thenReturn("stub-embedding-model")
            whenever(ai.withDefaultEmbeddingService()).thenReturn(embeddingService)
            return ai
        }

        @Bean
        fun graphObjectManager(): StatelessGraphObjectManager = mock()

        @Bean
        fun persistenceManager(): PersistenceManager = mock()

        @Bean
        fun platformTransactionManager(): PlatformTransactionManager = mock()
    }
}

/** A JPA dialect that hands out the JDBC connection, as Hibernate's does. */
private class JdbcExposingDialect(private val dataSource: DataSource) : DefaultJpaDialect() {
    override fun getJdbcConnection(entityManager: EntityManager, readOnly: Boolean): ConnectionHandle =
        SimpleConnectionHandle(dataSource.connection)
}

/** A JPA dialect that overrides the connection hook and still hands out nothing. */
private class NullHandleDialect : DefaultJpaDialect() {
    override fun getJdbcConnection(entityManager: EntityManager, readOnly: Boolean): ConnectionHandle? = null
}

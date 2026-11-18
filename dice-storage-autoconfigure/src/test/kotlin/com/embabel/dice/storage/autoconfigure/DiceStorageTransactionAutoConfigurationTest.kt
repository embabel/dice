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
import com.embabel.dice.proposition.PropositionRepository
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
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
     * The one wiring DICE supports where a host's JDBC manager and Drivine share a SQLite file:
     * Drivine reads it through a transaction-aware proxy over the host's data source. Drivine's
     * statements then run on the host's connection in the host's transaction, and a Drivine
     * transaction of the store's own would commit the host's work with it. The store keeps the
     * host's manager.
     */
    @Test
    fun `with a host's JDBC manager and Drivine behind a transaction-aware proxy, the proposition store keeps it`() {
        val target = sqliteDataSource(Files.createTempFile("dice-host-jdbc-", ".db"))
        val hostManager = DataSourceTransactionManager(target)

        sharedFileRunner(TransactionAwareDataSourceProxy(target), hostManager).run { ctx ->
            val repository = ctx.getBean<PropositionRepository>()
            val saves = ReflectionTestUtils.getField(repository, "txTemplate") as TransactionTemplate

            assertThat(saves.transactionManager).isSameAs(hostManager)
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

    private fun sqliteDataSource(file: Path): DataSource =
        SQLiteDataSource(SQLiteConfig().apply { setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE) })
            .apply { url = "jdbc:sqlite:$file" }

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

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
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.transaction.DrivineTransactionManager
import org.drivine.transaction.TransactionContextHolder
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.aop.framework.Advised
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.TransactionTemplate

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

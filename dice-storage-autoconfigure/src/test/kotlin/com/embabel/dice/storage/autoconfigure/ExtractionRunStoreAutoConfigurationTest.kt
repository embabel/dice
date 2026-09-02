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

import com.embabel.dice.proposition.extraction.ExtractionRunStore
import com.embabel.dice.proposition.extraction.PropositionRunLinkStore
import com.embabel.dice.storage.DrivineExtractionRunStore
import com.embabel.dice.storage.DrivinePropositionRunLinkStore
import org.assertj.core.api.Assertions.assertThat
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/**
 * Wiring for the extraction-run store and the lineage link store.
 *
 * These two used to be declared only by `dice-storage`'s own `TestApplication`, which meant the
 * suite exercised them and no host could get them without writing the beans by hand. They are
 * registered here on exactly the terms the stores around them use: the graph backend selects them,
 * anything else leaves them out entirely, and a host that declares its own wins.
 *
 * **Registering them changes no behaviour.** Both are inert until a caller names a run on an
 * `ExtractionRequest`, and lineage additionally has to be bound onto the extractor with
 * `withRunLineage`. A host that upgrades and passes no runs cannot tell these beans from their
 * absence, which is what makes adding them safe in a patch.
 */
class ExtractionRunStoreAutoConfigurationTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DiceStorageAutoConfiguration::class.java))
        .withUserConfiguration(StubGraphInfrastructure::class.java)

    @Test
    fun `the graph backend registers both stores and their schema`() {
        runner
            .withPropertyValues("embabel.dice.store.type=graph")
            .run { ctx ->
                assertThat(ctx).hasSingleBean(ExtractionRunStore::class.java)
                assertThat(ctx).hasSingleBean(PropositionRunLinkStore::class.java)
                assertThat(ctx.getBean(ExtractionRunStore::class.java))
                    .isInstanceOf(DrivineExtractionRunStore::class.java)
                assertThat(ctx.getBean(PropositionRunLinkStore::class.java))
                    .isInstanceOf(DrivinePropositionRunLinkStore::class.java)

                // The constraints the run key and the terminal write depend on. Without them the
                // store's MERGE and CREATE upserts are not race-free, so the schema travels with the
                // beans as part of the wiring, and a host does not opt into it separately.
                assertThat(ctx).hasBean("extractionRunSchema")
            }
    }

    @Test
    fun `without the graph backend neither store is registered`() {
        // The default. A host on the in-memory backend gets no run store, no link store and no
        // schema bootstrap, exactly as before this wiring existed.
        runner.run { ctx ->
            assertThat(ctx).doesNotHaveBean(ExtractionRunStore::class.java)
            assertThat(ctx).doesNotHaveBean(PropositionRunLinkStore::class.java)
            assertThat(ctx).doesNotHaveBean("extractionRunSchema")
        }
    }

    @Test
    fun `an explicitly configured store type other than graph registers neither`() {
        runner
            .withPropertyValues("embabel.dice.store.type=in-memory")
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(ExtractionRunStore::class.java)
                assertThat(ctx).doesNotHaveBean(PropositionRunLinkStore::class.java)
            }
    }

    @Test
    fun `a host that declares its own stores keeps them`() {
        // ConditionalOnMissingBean, from the host's side: someone with their own backend, or a
        // recording decorator around ours, must not end up with two.
        runner
            .withPropertyValues("embabel.dice.store.type=graph")
            .withUserConfiguration(HostSuppliedStores::class.java)
            .run { ctx ->
                assertThat(ctx).hasSingleBean(ExtractionRunStore::class.java)
                assertThat(ctx).hasSingleBean(PropositionRunLinkStore::class.java)
                assertThat(ctx.getBean(ExtractionRunStore::class.java))
                    .isNotInstanceOf(DrivineExtractionRunStore::class.java)
                assertThat(ctx.getBean(PropositionRunLinkStore::class.java))
                    .isNotInstanceOf(DrivinePropositionRunLinkStore::class.java)
            }
    }

    /** What Drivine would supply in a real application, as mocks. Nothing here is called. */
    @Configuration(proxyBeanMethods = false)
    open class StubGraphInfrastructure {

        @Bean
        open fun persistenceManager(): PersistenceManager = mock()

        @Bean
        open fun graphObjectManager(): GraphObjectManager = mock()

        @Bean
        open fun transactionManager(): PlatformTransactionManager = mock()
    }

    @Configuration(proxyBeanMethods = false)
    open class HostSuppliedStores {

        @Bean
        open fun hostRunStore(): ExtractionRunStore = mock()

        @Bean
        open fun hostLinkStore(): PropositionRunLinkStore = mock()
    }
}

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

import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.proposition.extraction.ExtractionRunStore
import com.embabel.dice.proposition.extraction.InMemoryExtractionRunStore
import com.embabel.dice.proposition.extraction.InMemoryPropositionRunLinkStore
import com.embabel.dice.proposition.extraction.PropositionRunLinkStore
import com.embabel.dice.storage.DrivineExtractionRunStore
import com.embabel.dice.storage.DrivinePropositionRunLinkStore
import com.embabel.dice.storage.ExtractionRunSchema
import org.assertj.core.api.Assertions.assertThat
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.schema.SchemaCatalog
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.assertj.AssertableApplicationContext
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager

/**
 * Wiring for the extraction-run store, the lineage link store, and the run schema catalog.
 *
 * These used to be declared only by `dice-storage`'s own `TestApplication`, which meant the suite
 * exercised them and no host could get them without writing the beans by hand. They are registered
 * here behind two conditions: the graph backend has to be selected, and
 * `embabel.dice.extraction.runs.enabled` has to be `true`. A host that declares its own stores still
 * wins.
 *
 * **Why the flag.** The schema catalog is the part with an effect a host can see. Drivine's schema
 * manager ensures every [SchemaCatalog] bean on startup, so registering [ExtractionRunSchema] writes
 * three constraints and five indexes into the host's database. That is a decision a host makes on
 * purpose, so the default is off and the tests below pin both halves: nothing lands with the flag
 * absent, and everything lands with it on.
 */
class ExtractionRunStoreAutoConfigurationTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DiceStorageAutoConfiguration::class.java))
        .withUserConfiguration(StubGraphInfrastructure::class.java)

    @Test
    fun `the graph backend with extraction runs enabled registers both stores and their schema`() {
        runner
            .withPropertyValues(
                "embabel.dice.store.type=graph",
                "embabel.dice.extraction.runs.enabled=true",
            )
            .run { ctx ->
                assertThat(ctx).hasSingleBean(ExtractionRunStore::class.java)
                assertThat(ctx).hasSingleBean(PropositionRunLinkStore::class.java)
                assertThat(ctx.getBean(ExtractionRunStore::class.java))
                    .isInstanceOf(DrivineExtractionRunStore::class.java)
                assertThat(ctx.getBean(PropositionRunLinkStore::class.java))
                    .isInstanceOf(DrivinePropositionRunLinkStore::class.java)

                // The constraints the run key and the terminal write depend on. Without them the
                // store's MERGE and CREATE upserts are not race-free, so the schema travels with the
                // beans once the flag is on, and a host does not opt into it separately.
                assertThat(ctx).hasBean("extractionRunSchema")
                assertThat(runSchemaLabelsReachingTheSchemaManager(ctx))
                    .containsExactlyInAnyOrderElementsOf(ExtractionRunSchema.LABELS)
            }
    }

    @Test
    fun `the default context on the graph backend registers none of the three beans`() {
        // The property is absent, which is the default a host upgrades into. None of the three
        // beans exist, and the schema manager is handed no ExtractionRun specs at all, so nothing
        // is written to the host's database on startup.
        runner
            .withPropertyValues("embabel.dice.store.type=graph")
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(ExtractionRunStore::class.java)
                assertThat(ctx).doesNotHaveBean(PropositionRunLinkStore::class.java)
                assertThat(ctx).doesNotHaveBean("extractionRunSchema")
                assertThat(runSchemaSpecsReachingTheSchemaManager(ctx)).isEmpty()
            }
    }

    @Test
    fun `the flag set to false registers none of the three beans`() {
        runner
            .withPropertyValues(
                "embabel.dice.store.type=graph",
                "embabel.dice.extraction.runs.enabled=false",
            )
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(ExtractionRunStore::class.java)
                assertThat(ctx).doesNotHaveBean(PropositionRunLinkStore::class.java)
                assertThat(ctx).doesNotHaveBean("extractionRunSchema")
                assertThat(runSchemaSpecsReachingTheSchemaManager(ctx)).isEmpty()
            }
    }

    @Test
    fun `without the graph backend neither store is registered`() {
        // Both conditions are required, so the flag alone buys a non-graph host nothing.
        runner
            .withPropertyValues("embabel.dice.extraction.runs.enabled=true")
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(ExtractionRunStore::class.java)
                assertThat(ctx).doesNotHaveBean(PropositionRunLinkStore::class.java)
                assertThat(ctx).doesNotHaveBean("extractionRunSchema")
            }
    }

    @Test
    fun `an explicitly configured store type other than graph registers neither`() {
        runner
            .withPropertyValues(
                "embabel.dice.store.type=in-memory",
                "embabel.dice.extraction.runs.enabled=true",
            )
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
            .withPropertyValues(
                "embabel.dice.store.type=graph",
                "embabel.dice.extraction.runs.enabled=true",
            )
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

    @Test
    fun `a host running the in-memory run store keeps it with the flag off`() {
        // The in-memory path is the host's own construction and owes nothing to this wiring. With
        // the flag absent its stores are still there and still usable, and the flag's only effect
        // is that no graph beans and no run schema join them.
        runner
            .withPropertyValues("embabel.dice.store.type=graph")
            .withUserConfiguration(HostSuppliedInMemoryStores::class.java)
            .run { ctx ->
                assertThat(ctx).hasSingleBean(ExtractionRunStore::class.java)
                assertThat(ctx).hasSingleBean(PropositionRunLinkStore::class.java)
                assertThat(ctx.getBean(ExtractionRunStore::class.java))
                    .isInstanceOf(InMemoryExtractionRunStore::class.java)
                assertThat(ctx.getBean(PropositionRunLinkStore::class.java))
                    .isInstanceOf(InMemoryPropositionRunLinkStore::class.java)
                assertThat(ctx).doesNotHaveBean("extractionRunSchema")
                assertThat(runSchemaSpecsReachingTheSchemaManager(ctx)).isEmpty()
            }
    }

    /**
     * Every `ExtractionRun` schema item any [SchemaCatalog] bean in the context carries.
     *
     * Drivine's schema manager collects the catalog beans and ensures what they hold, so this is
     * what would actually be written to a host's database on startup.
     */
    private fun runSchemaSpecsReachingTheSchemaManager(ctx: AssertableApplicationContext): List<String> =
        ctx.getBeansOfType(SchemaCatalog::class.java).values
            .flatMap { catalog -> catalog.items }
            .filter { spec -> spec.label in ExtractionRunSchema.LABELS }
            .map { spec -> "${spec.kind} ${spec.label}${spec.properties}" }

    /** The distinct run labels those items name. */
    private fun runSchemaLabelsReachingTheSchemaManager(ctx: AssertableApplicationContext): List<String> =
        ctx.getBeansOfType(SchemaCatalog::class.java).values
            .flatMap { catalog -> catalog.items }
            .map { spec -> spec.label }
            .filter { label -> label in ExtractionRunSchema.LABELS }
            .distinct()

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

    @Configuration(proxyBeanMethods = false)
    open class HostSuppliedInMemoryStores {

        @Bean
        open fun hostRunStore(): ExtractionRunStore = InMemoryExtractionRunStore()

        @Bean
        open fun hostLinkStore(runStore: ExtractionRunStore): PropositionRunLinkStore =
            InMemoryPropositionRunLinkStore(runStore, mock<PropositionStore>())
    }
}

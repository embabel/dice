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

import com.embabel.agent.core.ContextId
import com.embabel.dice.metamodel.DeclaredObservedDiff
import com.embabel.dice.metamodel.DeclaredObservedDiffer
import com.embabel.dice.metamodel.DeclaredSchema
import com.embabel.dice.metamodel.DeclaredSchemaSource
import com.embabel.dice.metamodel.DriftCheckResult
import com.embabel.dice.metamodel.DriftCheckRunner
import com.embabel.dice.metamodel.DriftQuarantinePolicy
import com.embabel.dice.metamodel.DriftReportStore
import com.embabel.dice.metamodel.MetamodelDiff
import com.embabel.dice.metamodel.MetamodelDiffer
import com.embabel.dice.metamodel.MetamodelVersionStore
import com.embabel.dice.metamodel.ObservedSchema
import com.embabel.dice.metamodel.ObservedSchemaSource
import com.embabel.dice.metamodel.QuarantineResult
import com.embabel.dice.metamodel.support.DefaultDriftCheckRunner
import com.embabel.dice.metamodel.support.MentionTypeDriftQuarantinePolicy
import com.embabel.dice.metamodel.support.StructuralMetamodelDiffer
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.storage.DrivineDriftReportStore
import com.embabel.dice.storage.DrivineMetamodelVersionStore
import com.embabel.dice.storage.DrivineObservedSchemaSource
import org.assertj.core.api.Assertions.assertThat
import org.drivine.manager.PersistenceManager
import org.drivine.schema.SchemaCatalog
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * Wiring tests for [MetamodelAutoConfiguration]. No Spring Boot app and no database — just the
 * auto-configuration, a stub `PersistenceManager`, and whichever governance beans a given test
 * wants the application to have supplied.
 *
 * The questions here are the ones the wiring can get wrong on its own: does governance stay
 * completely absent until somebody declares a schema, does a consumer's bean win regardless of
 * declaration order, and does each drift tier produce a runner that actually behaves like that
 * tier.
 */
class MetamodelAutoConfigurationTest {

    private val autoConfiguration = AutoConfigurations.of(MetamodelAutoConfiguration::class.java)

    /** The application declared a schema and has a graph connection: the normal case. */
    private val runner = ApplicationContextRunner()
        .withConfiguration(autoConfiguration)
        .withUserConfiguration(DeclaredSchemaConfig::class.java)

    // ---- The opt-in gate ----

    @Test
    fun `no DeclaredSchemaSource bean means no metamodel beans at all`() {
        ApplicationContextRunner()
            .withConfiguration(autoConfiguration)
            .withUserConfiguration(NoDeclaredSchemaConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).doesNotHaveBean(MetamodelAutoConfiguration::class.java)
                assertThat(ctx).doesNotHaveBean(MetamodelProperties::class.java)
                assertThat(ctx).doesNotHaveBean(MetamodelVersionStore::class.java)
                assertThat(ctx).doesNotHaveBean(DriftReportStore::class.java)
                assertThat(ctx).doesNotHaveBean(ObservedSchemaSource::class.java)
                assertThat(ctx).doesNotHaveBean(DeclaredObservedDiffer::class.java)
                assertThat(ctx).doesNotHaveBean(DriftQuarantinePolicy::class.java)
                assertThat(ctx).doesNotHaveBean(DriftCheckRunner::class.java)
                assertThat(ctx).doesNotHaveBean(SchemaCatalog::class.java)
            }
    }

    @Test
    fun `a DeclaredSchemaSource bean wires the whole loop with Drivine-backed defaults`() {
        runner.run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(MetamodelAutoConfiguration::class.java)
            assertThat(ctx.getBean<MetamodelVersionStore>()).isInstanceOf(DrivineMetamodelVersionStore::class.java)
            assertThat(ctx.getBean<DriftReportStore>()).isInstanceOf(DrivineDriftReportStore::class.java)
            assertThat(ctx.getBean<ObservedSchemaSource>()).isInstanceOf(DrivineObservedSchemaSource::class.java)
            assertThat(ctx.getBean<DriftQuarantinePolicy>())
                .isInstanceOf(MentionTypeDriftQuarantinePolicy::class.java)
            assertThat(ctx).hasSingleBean(DriftCheckRunner::class.java)
            assertThat(ctx).hasSingleBean(SchemaCatalog::class.java)
        }
    }

    @Test
    fun `the metamodel SchemaCatalog carries the uniqueness constraints the stores need`() {
        runner.run { ctx ->
            val labels = ctx.getBean<SchemaCatalog>().items.map { it.label }
            assertThat(labels).contains(
                "MetamodelVersion",
                "MetamodelSchemaCounter",
                "MetamodelDriftReport",
                "MetamodelDriftReportCounter",
            )
        }
    }

    @Test
    fun `enabled=false kills every metamodel bean even with a declared schema`() {
        runner
            .withPropertyValues("embabel.dice.metamodel.enabled=false")
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).doesNotHaveBean(MetamodelAutoConfiguration::class.java)
                assertThat(ctx).doesNotHaveBean(MetamodelVersionStore::class.java)
                assertThat(ctx).doesNotHaveBean(DriftCheckRunner::class.java)
                // The DeclaredSchemaSource the application supplied is untouched — only ours go.
                assertThat(ctx).hasSingleBean(DeclaredSchemaSource::class.java)
            }
    }

    // ---- The differ, resolvable as both of the questions it answers ----

    @Test
    fun `the default differ resolves as both MetamodelDiffer and DeclaredObservedDiffer`() {
        runner.run { ctx ->
            val asStructural = ctx.getBean<StructuralMetamodelDiffer>()
            assertThat(ctx.getBean<MetamodelDiffer>()).isSameAs(asStructural)
            assertThat(ctx.getBean<DeclaredObservedDiffer>()).isSameAs(asStructural)
        }
    }

    // ---- Consumer beans win, in either declaration order ----

    @Test
    fun `a consumer differ wins whether it is registered before or after the auto-configuration`() {
        bothOrders(CustomDifferConfig::class.java) { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx.getBean<DeclaredObservedDiffer>()).isInstanceOf(CustomDiffer::class.java)
            assertThat(ctx).doesNotHaveBean(StructuralMetamodelDiffer::class.java)
        }
    }

    @Test
    fun `a consumer quarantine policy wins whether it is registered before or after`() {
        bothOrders(CustomPolicyConfig::class.java) { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(DriftQuarantinePolicy::class.java)
            assertThat(ctx.getBean<DriftQuarantinePolicy>()).isInstanceOf(CustomPolicy::class.java)
        }
    }

    @Test
    fun `a consumer drift-check runner wins whether it is registered before or after`() {
        bothOrders(CustomRunnerConfig::class.java) { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(DriftCheckRunner::class.java)
            assertThat(ctx.getBean<DriftCheckRunner>()).isInstanceOf(CustomRunner::class.java)
        }
    }

    @Test
    fun `consumer stores win, and then no Drivine connection is needed at all`() {
        ApplicationContextRunner()
            .withConfiguration(autoConfiguration)
            .withUserConfiguration(InMemoryGovernanceConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).doesNotHaveBean(PersistenceManager::class.java)
                assertThat(ctx.getBean<MetamodelVersionStore>())
                    .isInstanceOf(RecordingMetamodelVersionStore::class.java)
                assertThat(ctx.getBean<DriftReportStore>()).isInstanceOf(RecordingDriftReportStore::class.java)
                assertThat(ctx.getBean<ObservedSchemaSource>()).isInstanceOf(FixedObservedSchemaSource::class.java)
            }
    }

    // ---- The narrow proposition port ----

    @Test
    fun `the runner wires against a bare PropositionStore, with no PropositionRepository in sight`() {
        ApplicationContextRunner()
            .withConfiguration(autoConfiguration)
            .withUserConfiguration(InMemoryGovernanceConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).doesNotHaveBean(PropositionRepository::class.java)
                assertThat(ctx).hasSingleBean(DriftCheckRunner::class.java)
            }
    }

    // ---- The drift tiers ----

    @Test
    fun `mode off leaves the stores wired and no runner`() {
        ApplicationContextRunner()
            .withConfiguration(autoConfiguration)
            .withUserConfiguration(InMemoryGovernanceConfig::class.java)
            .withPropertyValues("embabel.dice.metamodel.drift.mode=off")
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx.getBean<MetamodelProperties>().drift.mode).isEqualTo(DriftMode.OFF)
                assertThat(ctx).hasSingleBean(MetamodelVersionStore::class.java)
                assertThat(ctx).hasSingleBean(DriftReportStore::class.java)
                assertThat(ctx).doesNotHaveBean(DriftCheckRunner::class.java)
            }
    }

    @Test
    fun `observe is the default tier and downgrades a live run to a dry one`() {
        ApplicationContextRunner()
            .withConfiguration(autoConfiguration)
            .withUserConfiguration(InMemoryGovernanceConfig::class.java)
            .run { ctx ->
                assertThat(ctx.getBean<MetamodelProperties>().drift.mode).isEqualTo(DriftMode.OBSERVE)
                assertThat(ctx.getBean<DriftCheckRunner>()).isInstanceOf(ObserveOnlyDriftCheckRunner::class.java)

                // Ask for a live run. Drift is real -- 'Ghost' is observed and never declared -- so
                // the quarantine tier would act here. Observe must not.
                val result = ctx.getBean<DriftCheckRunner>().run(dryRun = false, contextId = null)

                assertThat(result.dryRun).isTrue()
                assertThat(result.hasDrift).isTrue()
                assertThat(result.driftedEntityTypes).containsExactly("Ghost")
                assertThat(result.quarantinedCount).isZero()

                val store = ctx.getBean<RecordingDriftReportStore>()
                assertThat(store.saved).hasSize(1)
                assertThat(store.saved.single().driftedEntityTypes).containsExactly("Ghost")

                val propositions = ctx.getBean<MapPropositionStore>().findAll()
                assertThat(propositions).allMatch { it.status == PropositionStatus.ACTIVE }
            }
    }

    @Test
    fun `explicit observe mode wires the same observe-only runner`() {
        ApplicationContextRunner()
            .withConfiguration(autoConfiguration)
            .withUserConfiguration(InMemoryGovernanceConfig::class.java)
            .withPropertyValues("embabel.dice.metamodel.drift.mode=observe")
            .run { ctx ->
                assertThat(ctx.getBean<DriftCheckRunner>()).isInstanceOf(ObserveOnlyDriftCheckRunner::class.java)
            }
    }

    @Test
    fun `quarantine mode wires the real runner and a live run really quarantines`() {
        ApplicationContextRunner()
            .withConfiguration(autoConfiguration)
            .withUserConfiguration(InMemoryGovernanceConfig::class.java)
            .withPropertyValues("embabel.dice.metamodel.drift.mode=quarantine")
            .run { ctx ->
                assertThat(ctx.getBean<MetamodelProperties>().drift.mode).isEqualTo(DriftMode.QUARANTINE)
                assertThat(ctx.getBean<DriftCheckRunner>()).isInstanceOf(DefaultDriftCheckRunner::class.java)

                val result = ctx.getBean<DriftCheckRunner>().run(dryRun = false, contextId = null)

                assertThat(result.dryRun).isFalse()
                assertThat(result.driftedEntityTypes).containsExactly("Ghost")
                assertThat(result.quarantinedCount).isEqualTo(1)

                val store = ctx.getBean<MapPropositionStore>()
                assertThat(store.findByStatus(PropositionStatus.STALE)).hasSize(1)
                assertThat(store.findByStatus(PropositionStatus.ACTIVE)).hasSize(1)
            }
    }

    @Test
    fun `quarantine mode still leaves a dry run harmless`() {
        ApplicationContextRunner()
            .withConfiguration(autoConfiguration)
            .withUserConfiguration(InMemoryGovernanceConfig::class.java)
            .withPropertyValues("embabel.dice.metamodel.drift.mode=quarantine")
            .run { ctx ->
                val result = ctx.getBean<DriftCheckRunner>().run()

                assertThat(result.dryRun).isTrue()
                assertThat(result.quarantinedCount).isZero()
                assertThat(ctx.getBean<MapPropositionStore>().findByStatus(PropositionStatus.STALE)).isEmpty()
            }
    }

    @Test
    fun `every run stamps the declared version before the report is written`() {
        ApplicationContextRunner()
            .withConfiguration(autoConfiguration)
            .withUserConfiguration(InMemoryGovernanceConfig::class.java)
            .run { ctx ->
                ctx.getBean<DriftCheckRunner>().run()

                val versions = ctx.getBean<RecordingMetamodelVersionStore>().saved
                val reports = ctx.getBean<RecordingDriftReportStore>().saved
                assertThat(versions).hasSize(1)
                assertThat(reports.single().versionHash).isEqualTo(versions.single().contentHash)
            }
    }

    // ---- Registration ----

    @Test
    fun `the class is registered as a real auto-configuration, not a scanned Configuration`() {
        val declared = PathMatchingResourcePatternResolver()
            .getResources("classpath*:META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
            .flatMap { it.inputStream.bufferedReader().readLines() }
            .map { it.trim() }

        assertThat(declared).contains(MetamodelAutoConfiguration::class.java.name)
    }

    /**
     * Runs [assertions] twice: once with [userConfiguration] registered before the
     * auto-configuration and once after. Order-independence is the whole point of shipping this as
     * a real `@AutoConfiguration` — a plain `@Configuration` with the same
     * `@ConditionalOnMissingBean` annotations would win or lose depending on which got processed
     * first.
     */
    private fun bothOrders(
        userConfiguration: Class<*>,
        assertions: (org.springframework.boot.test.context.assertj.AssertableApplicationContext) -> Unit,
    ) {
        ApplicationContextRunner()
            .withUserConfiguration(DeclaredSchemaConfig::class.java, userConfiguration)
            .withConfiguration(autoConfiguration)
            .run(assertions)

        ApplicationContextRunner()
            .withConfiguration(autoConfiguration)
            .withUserConfiguration(DeclaredSchemaConfig::class.java, userConfiguration)
            .run(assertions)
    }
}

/** A graph-connected application that has declared a schema: the Drivine defaults apply. */
@Configuration(proxyBeanMethods = false)
internal open class DeclaredSchemaConfig {

    @Bean
    open fun persistenceManager(): PersistenceManager = mock()

    @Bean
    open fun declaredSchemaSource(): DeclaredSchemaSource = FixedDeclaredSchemaSource()

    @Bean
    open fun propositionStore(): MapPropositionStore = MapPropositionStore()
}

/** A graph-connected application that has declared nothing. Governance must stay away. */
@Configuration(proxyBeanMethods = false)
internal open class NoDeclaredSchemaConfig {

    @Bean
    open fun persistenceManager(): PersistenceManager = mock()

    @Bean
    open fun propositionStore(): MapPropositionStore = MapPropositionStore()
}

/**
 * An application that brought its own governance stores, so no Drivine connection is needed and the
 * whole loop can be driven in memory. The observed schema holds a `Ghost` type the declaration never
 * mentions, and one of the two propositions mentions it — so a live run has exactly one thing to
 * quarantine and one thing to leave alone.
 */
@Configuration(proxyBeanMethods = false)
internal open class InMemoryGovernanceConfig {

    @Bean
    open fun declaredSchemaSource(): DeclaredSchemaSource = FixedDeclaredSchemaSource()

    @Bean
    open fun versionStore(): RecordingMetamodelVersionStore = RecordingMetamodelVersionStore()

    @Bean
    open fun driftReportStore(): RecordingDriftReportStore = RecordingDriftReportStore()

    @Bean
    open fun observedSchemaSource(): FixedObservedSchemaSource = FixedObservedSchemaSource()

    @Bean
    open fun propositionStore(): MapPropositionStore = MapPropositionStore(
        listOf(
            MetamodelTestFixtures.proposition("Ada haunts the archive", mentionType = "Ghost"),
            MetamodelTestFixtures.proposition("Ada wrote the notes", mentionType = "Person"),
        ),
    )
}

internal class CustomDiffer : DeclaredObservedDiffer {
    override fun diffAgainstObserved(declared: DeclaredSchema, observed: ObservedSchema): DeclaredObservedDiff =
        DeclaredObservedDiff(
            declared = declared,
            observedSchema = observed,
            driftedEntityTypes = emptySet(),
            driftedRelationshipTypes = emptySet(),
            unobservedEntityTypes = emptySet(),
            unobservedRelationshipTypes = emptySet(),
        )
}

@Configuration(proxyBeanMethods = false)
internal open class CustomDifferConfig {

    @Bean
    open fun customDiffer(): DeclaredObservedDiffer = CustomDiffer()
}

internal class CustomPolicy : DriftQuarantinePolicy {
    override fun evaluate(diff: MetamodelDiff, propositions: Iterable<Proposition>): QuarantineResult =
        QuarantineResult(conforming = emptyList(), quarantined = emptyList())
}

@Configuration(proxyBeanMethods = false)
internal open class CustomPolicyConfig {

    @Bean
    open fun customPolicy(): DriftQuarantinePolicy = CustomPolicy()
}

internal class CustomRunner : DriftCheckRunner {
    override fun run(dryRun: Boolean, contextId: ContextId?): DriftCheckResult =
        throw UnsupportedOperationException("never called; this test only asks which bean won")
}

@Configuration(proxyBeanMethods = false)
internal open class CustomRunnerConfig {

    @Bean
    open fun customRunner(): DriftCheckRunner = CustomRunner()
}

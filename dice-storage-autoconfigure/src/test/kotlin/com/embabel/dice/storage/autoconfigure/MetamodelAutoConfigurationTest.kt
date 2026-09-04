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
import com.embabel.dice.spi.DriftQuarantinePolicy
import com.embabel.dice.metamodel.DriftReportStore
import com.embabel.dice.spi.DriftSweepCapable
import com.embabel.dice.metamodel.InMemoryMetamodelVersionStore
import com.embabel.dice.metamodel.MetamodelChange
import com.embabel.dice.metamodel.MetamodelDiff
import com.embabel.dice.metamodel.MetamodelDiffer
import com.embabel.dice.metamodel.MetamodelVersion
import com.embabel.dice.metamodel.MetamodelVersionStore
import com.embabel.dice.metamodel.ObservedSchema
import com.embabel.dice.metamodel.ObservedSchemaSource
import com.embabel.dice.spi.QuarantineResult
import com.embabel.dice.metamodel.support.DefaultDriftCheckRunner
import com.embabel.dice.spi.MentionTypeDriftQuarantinePolicy
import com.embabel.dice.spi.PropositionStoreDriftSweep
import com.embabel.dice.metamodel.support.StructuralMetamodelDiffer
import com.embabel.dice.projection.lineage.InMemoryProjectionRecordStore
import com.embabel.dice.projection.lineage.ProjectionLifecycle
import com.embabel.dice.projection.lineage.ProjectionLineageStaleCascade
import com.embabel.dice.projection.lineage.ProjectionRecord
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
 * Wiring tests for [MetamodelAutoConfiguration]. No Spring Boot app and no database: the
 * auto-configuration, a stub `PersistenceManager` where the graph backend is selected, and whichever
 * governance beans a given test wants the application to have supplied.
 *
 * These cover what the wiring alone can get wrong: whether governance stays absent until somebody
 * declares a schema, whether a consumer's bean wins whichever declaration order it arrives in,
 * whether a host with no graph still starts, and whether a quarantine is heard by the listeners the
 * application registered.
 */
class MetamodelAutoConfigurationTest {

    private val autoConfiguration = AutoConfigurations.of(MetamodelAutoConfiguration::class.java)

    /** The application declared a schema and selected the graph backend: the normal case. */
    private val runner = ApplicationContextRunner()
        .withConfiguration(autoConfiguration)
        .withPropertyValues(GRAPH_BACKEND)
        .withUserConfiguration(DeclaredSchemaConfig::class.java)

    // ---- The opt-in gate ----

    @Test
    fun `no DeclaredSchemaSource bean means no metamodel beans at all`() {
        ApplicationContextRunner()
            .withConfiguration(autoConfiguration)
            .withPropertyValues(GRAPH_BACKEND)
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
                assertThat(ctx).doesNotHaveBean(DriftSweepCapable::class.java)
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
            assertThat(ctx.getBean<DriftSweepCapable>()).isInstanceOf(PropositionStoreDriftSweep::class.java)
            assertThat(ctx.getBean<DriftCheckRunner>()).isInstanceOf(DefaultDriftCheckRunner::class.java)
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
                assertThat(ctx).doesNotHaveBean(MetamodelProperties::class.java)
                assertThat(ctx).doesNotHaveBean(MetamodelVersionStore::class.java)
                assertThat(ctx).doesNotHaveBean(DriftReportStore::class.java)
                assertThat(ctx).doesNotHaveBean(ObservedSchemaSource::class.java)
                assertThat(ctx).doesNotHaveBean(DeclaredObservedDiffer::class.java)
                assertThat(ctx).doesNotHaveBean(MetamodelDiffer::class.java)
                assertThat(ctx).doesNotHaveBean(DriftQuarantinePolicy::class.java)
                assertThat(ctx).doesNotHaveBean(DriftSweepCapable::class.java)
                assertThat(ctx).doesNotHaveBean(DriftCheckRunner::class.java)
                assertThat(ctx).doesNotHaveBean(SchemaCatalog::class.java)
                // The DeclaredSchemaSource the application supplied stays; only the metamodel
                // beans are removed.
                assertThat(ctx).hasSingleBean(DeclaredSchemaSource::class.java)
            }
    }

    // ---- Backend selection ----

    @Test
    fun `a declared schema starts under the default in-memory backend, with no PersistenceManager`() {
        ApplicationContextRunner()
            .withConfiguration(autoConfiguration)
            .withUserConfiguration(InMemoryBackendConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).doesNotHaveBean(PersistenceManager::class.java)

                // What a host with no graph gets: schema history, the comparisons, the policy, and
                // the sweep it can call once it has a diff.
                assertThat(ctx.getBean<MetamodelVersionStore>())
                    .isInstanceOf(InMemoryMetamodelVersionStore::class.java)
                assertThat(ctx).hasSingleBean(StructuralMetamodelDiffer::class.java)
                assertThat(ctx).hasSingleBean(DriftQuarantinePolicy::class.java)
                assertThat(ctx.getBean<DriftSweepCapable>()).isInstanceOf(PropositionStoreDriftSweep::class.java)

                // What it does not get, and why: there is no live graph to observe, so there is
                // nothing for a check to ask about and no drift log to write the answer to.
                assertThat(ctx).doesNotHaveBean(ObservedSchemaSource::class.java)
                assertThat(ctx).doesNotHaveBean(DriftReportStore::class.java)
                assertThat(ctx).doesNotHaveBean(DriftCheckRunner::class.java)
                // The Neo4j constraints are DDL for the Drivine stores, which are not here either.
                assertThat(ctx).doesNotHaveBean(SchemaCatalog::class.java)
            }
    }

    // ---- Status transitions reach the application's listeners ----

    @Test
    fun `a quarantine through the wired sweep drives the projection lineage cascade`() {
        ApplicationContextRunner()
            .withConfiguration(autoConfiguration)
            .withUserConfiguration(CascadeListeningConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                val propositions = ctx.getBean<MapPropositionStore>()
                val ghost = propositions.findAll().single { proposition ->
                    proposition.mentions.any { it.type == "Ghost" }
                }
                val records = ctx.getBean<InMemoryProjectionRecordStore>()
                records.record(
                    ProjectionRecord(
                        propositionId = ghost.id,
                        target = "graph",
                        lifecycle = ProjectionLifecycle.PROJECTED,
                        runId = "run-1",
                    ),
                )

                // A host acting on a schema change that dropped `Ghost`. Nothing in DICE called this.
                val result = ctx.getBean<DriftSweepCapable>().sweep(
                    diff = MetamodelTestFixtures.diffRemoving("Ghost"),
                    policy = ctx.getBean<DriftQuarantinePolicy>(),
                    contextId = MetamodelTestFixtures.CONTEXT_ID,
                )

                assertThat(result.quarantined).hasSize(1)
                assertThat(propositions.findById(ghost.id)!!.status).isEqualTo(PropositionStatus.QUARANTINED)
                // The point of the test: the cascade heard the transition, so the projection record
                // derived from that proposition is stale too.
                assertThat(records.findByProposition(ghost.id).single().lifecycle)
                    .isEqualTo(ProjectionLifecycle.STALE)
            }
    }

    @Test
    fun `the sweep still works when the application registered no listener at all`() {
        ApplicationContextRunner()
            .withConfiguration(autoConfiguration)
            .withUserConfiguration(InMemoryBackendConfig::class.java)
            .run { ctx ->
                val propositions = ctx.getBean<MapPropositionStore>()
                val result = ctx.getBean<DriftSweepCapable>().sweep(
                    diff = MetamodelTestFixtures.diffRemoving("Ghost"),
                    policy = ctx.getBean<DriftQuarantinePolicy>(),
                    contextId = MetamodelTestFixtures.CONTEXT_ID,
                )

                assertThat(result.quarantined).hasSize(1)
                assertThat(propositions.findByStatus(PropositionStatus.QUARANTINED)).hasSize(1)
            }
    }

    // ---- The two differ roles ----

    @Test
    fun `the default differ resolves as both MetamodelDiffer and DeclaredObservedDiffer`() {
        runner.run { ctx ->
            val asStructural = ctx.getBean<StructuralMetamodelDiffer>()
            assertThat(ctx.getBean<MetamodelDiffer>()).isSameAs(asStructural)
            assertThat(ctx.getBean<DeclaredObservedDiffer>()).isSameAs(asStructural)
        }
    }

    @Test
    fun `a distinct MetamodelDiffer bean and DeclaredObservedDiffer bean are both used`() {
        ApplicationContextRunner()
            .withConfiguration(autoConfiguration)
            .withUserConfiguration(TwoDifferConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                // The declared-against-baseline comparison only runs when a sweep has completed, so
                // give it a baseline to compare against.
                ctx.getBean<InMemoryMetamodelVersionStore>()
                    .markSwept(MetamodelTestFixtures.declaredSchema("Person", "Retired").version)

                val result = ctx.getBean<DriftCheckRunner>().run()

                val declaredObserved = ctx.getBean<RecordingDeclaredObservedDiffer>()
                val metamodel = ctx.getBean<RecordingMetamodelDiffer>()
                assertThat(declaredObserved.calls).isEqualTo(1)
                assertThat(metamodel.calls).isEqualTo(1)
                // Each answer reaches the report under its own heading, so neither differ can be
                // standing in for the other.
                assertThat(result.driftedEntityTypes).containsExactly(RecordingDeclaredObservedDiffer.MARKER)
                assertThat(result.declaredDiff!!.removedEntityTypes)
                    .containsExactly(RecordingMetamodelDiffer.MARKER)
            }
    }

    @Test
    fun `a consumer MetamodelDiffer alone still leaves the declared-against-graph comparison working`() {
        ApplicationContextRunner()
            .withConfiguration(autoConfiguration)
            .withUserConfiguration(MetamodelDifferOnlyConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                ctx.getBean<InMemoryMetamodelVersionStore>()
                    .markSwept(MetamodelTestFixtures.declaredSchema("Person", "Retired").version)

                val result = ctx.getBean<DriftCheckRunner>().run()

                assertThat(ctx.getBean<RecordingMetamodelDiffer>().calls).isEqualTo(1)
                assertThat(result.declaredDiff!!.removedEntityTypes)
                    .containsExactly(RecordingMetamodelDiffer.MARKER)
                // The shipped structural differ filled the role nobody supplied a bean for.
                assertThat(result.driftedEntityTypes).containsExactly("Ghost")
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
    fun `a consumer sweep wins whether it is registered before or after`() {
        bothOrders(CustomSweepConfig::class.java) { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(DriftSweepCapable::class.java)
            assertThat(ctx.getBean<DriftSweepCapable>()).isInstanceOf(CustomSweep::class.java)
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
                assertThat(ctx).hasSingleBean(DriftCheckRunner::class.java)
            }
    }

    // ---- The narrow proposition port ----

    @Test
    fun `the sweep wires against a bare PropositionStore, with no PropositionRepository in sight`() {
        ApplicationContextRunner()
            .withConfiguration(autoConfiguration)
            .withUserConfiguration(InMemoryGovernanceConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).doesNotHaveBean(PropositionRepository::class.java)
                assertThat(ctx).hasSingleBean(DriftSweepCapable::class.java)
            }
    }

    // ---- A check reports and changes nothing ----

    @Test
    fun `observe is the default mode and a check leaves every proposition alone`() {
        ApplicationContextRunner()
            .withConfiguration(autoConfiguration)
            .withUserConfiguration(InMemoryGovernanceConfig::class.java)
            .run { ctx ->
                assertThat(ctx.getBean<MetamodelProperties>().drift.mode).isEqualTo(DriftMode.OBSERVE)
                assertThat(ctx.getBean<DriftCheckRunner>()).isInstanceOf(DefaultDriftCheckRunner::class.java)

                // 'Ghost' is observed and undeclared, so there is real drift to report.
                val result = ctx.getBean<DriftCheckRunner>().run()

                assertThat(result.hasDrift).isTrue()
                assertThat(result.driftedEntityTypes).containsExactly("Ghost")

                val store = ctx.getBean<RecordingDriftReportStore>()
                assertThat(store.saved).hasSize(1)
                assertThat(store.saved.single().driftedEntityTypes).containsExactly("Ghost")

                val propositions = ctx.getBean<MapPropositionStore>().findAll()
                assertThat(propositions).allMatch { it.status == PropositionStatus.ACTIVE }
            }
    }

    @Test
    fun `explicit observe mode wires the same runner`() {
        ApplicationContextRunner()
            .withConfiguration(autoConfiguration)
            .withUserConfiguration(InMemoryGovernanceConfig::class.java)
            .withPropertyValues("embabel.dice.metamodel.drift.mode=observe")
            .run { ctx ->
                assertThat(ctx.getBean<DriftCheckRunner>()).isInstanceOf(DefaultDriftCheckRunner::class.java)
            }
    }

    @Test
    fun `mode off leaves the stores and the sweep wired, and no runner`() {
        ApplicationContextRunner()
            .withConfiguration(autoConfiguration)
            .withUserConfiguration(InMemoryGovernanceConfig::class.java)
            .withPropertyValues("embabel.dice.metamodel.drift.mode=off")
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx.getBean<MetamodelProperties>().drift.mode).isEqualTo(DriftMode.OFF)
                assertThat(ctx).hasSingleBean(MetamodelVersionStore::class.java)
                assertThat(ctx).hasSingleBean(DriftReportStore::class.java)
                assertThat(ctx).hasSingleBean(DriftSweepCapable::class.java)
                assertThat(ctx).doesNotHaveBean(DriftCheckRunner::class.java)
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
     * auto-configuration and once after. Shipping this as a real `@AutoConfiguration` is what makes
     * the result order-independent. A plain `@Configuration` with the same
     * `@ConditionalOnMissingBean` annotations would win or lose depending on which was processed
     * first.
     */
    private fun bothOrders(
        userConfiguration: Class<*>,
        assertions: (org.springframework.boot.test.context.assertj.AssertableApplicationContext) -> Unit,
    ) {
        ApplicationContextRunner()
            .withPropertyValues(GRAPH_BACKEND)
            .withUserConfiguration(DeclaredSchemaConfig::class.java, userConfiguration)
            .withConfiguration(autoConfiguration)
            .run(assertions)

        ApplicationContextRunner()
            .withPropertyValues(GRAPH_BACKEND)
            .withConfiguration(autoConfiguration)
            .withUserConfiguration(DeclaredSchemaConfig::class.java, userConfiguration)
            .run(assertions)
    }

    private companion object {

        /** Selects the Drivine/Neo4j backend, the same switch the proposition store reads. */
        const val GRAPH_BACKEND = "embabel.dice.store.type=graph"
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

/** A graph-connected application that has declared nothing, so no governance beans appear. */
@Configuration(proxyBeanMethods = false)
internal open class NoDeclaredSchemaConfig {

    @Bean
    open fun persistenceManager(): PersistenceManager = mock()

    @Bean
    open fun propositionStore(): MapPropositionStore = MapPropositionStore()
}

/**
 * An application on the default in-memory backend that declared a schema and nothing else. No
 * `PersistenceManager` anywhere, which is the whole point: declaring a schema must not drag a graph
 * connection in behind it.
 */
@Configuration(proxyBeanMethods = false)
internal open class InMemoryBackendConfig {

    @Bean
    open fun declaredSchemaSource(): DeclaredSchemaSource = FixedDeclaredSchemaSource()

    @Bean
    open fun propositionStore(): MapPropositionStore = MapPropositionStore(
        listOf(
            MetamodelTestFixtures.proposition("Ada haunts the archive", mentionType = "Ghost"),
            MetamodelTestFixtures.proposition("Ada wrote the notes", mentionType = "Person"),
        ),
    )
}

/**
 * The same host, plus the lineage cascade registered as an ordinary `DiceEventListener` bean. This
 * is what a real application does when it wants derived projection records to follow their
 * proposition.
 */
@Configuration(proxyBeanMethods = false)
internal open class CascadeListeningConfig {

    @Bean
    open fun declaredSchemaSource(): DeclaredSchemaSource = FixedDeclaredSchemaSource()

    @Bean
    open fun propositionStore(): MapPropositionStore = MapPropositionStore(
        listOf(
            MetamodelTestFixtures.proposition("Ada haunts the archive", mentionType = "Ghost"),
            MetamodelTestFixtures.proposition("Ada wrote the notes", mentionType = "Person"),
        ),
    )

    @Bean
    open fun projectionRecordStore(): InMemoryProjectionRecordStore = InMemoryProjectionRecordStore()

    @Bean
    open fun projectionLineageStaleCascade(
        recordStore: InMemoryProjectionRecordStore,
    ): ProjectionLineageStaleCascade = ProjectionLineageStaleCascade(recordStore)
}

/**
 * An application that brought its own governance stores, so no Drivine connection is needed and the
 * whole loop runs in memory. The observed schema holds a `Ghost` type the declaration never
 * mentions, so a check has real drift to report.
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

/** Records that it was asked, and answers with a name no other collaborator could have produced. */
internal class RecordingDeclaredObservedDiffer : DeclaredObservedDiffer {

    var calls = 0
        private set

    override fun diffAgainstObserved(declared: DeclaredSchema, observed: ObservedSchema): DeclaredObservedDiff {
        calls++
        return DeclaredObservedDiff(
            declared = declared,
            observedSchema = observed,
            driftedEntityTypes = setOf(MARKER),
            driftedRelationshipTypes = emptySet(),
            unobservedEntityTypes = emptySet(),
            unobservedRelationshipTypes = emptySet(),
        )
    }

    companion object {

        const val MARKER = "AnsweredByTheConsumerDeclaredObservedDiffer"
    }
}

/** The same trick for the other role: its answer carries a name only it can produce. */
internal class RecordingMetamodelDiffer : MetamodelDiffer {

    var calls = 0
        private set

    override fun diff(from: MetamodelVersion, to: MetamodelVersion): MetamodelDiff {
        calls++
        return MetamodelDiff(
            fromVersion = from,
            toVersion = to,
            changes = listOf(MetamodelChange.EntityTypeRemoved(MARKER)),
        )
    }

    companion object {

        const val MARKER = "AnsweredByTheConsumerMetamodelDiffer"
    }
}

/** An application supplying a distinct bean for each of the two differ roles. */
@Configuration(proxyBeanMethods = false)
internal open class TwoDifferConfig {

    @Bean
    open fun declaredSchemaSource(): DeclaredSchemaSource = FixedDeclaredSchemaSource()

    @Bean
    open fun versionStore(): InMemoryMetamodelVersionStore = InMemoryMetamodelVersionStore()

    @Bean
    open fun driftReportStore(): RecordingDriftReportStore = RecordingDriftReportStore()

    @Bean
    open fun observedSchemaSource(): FixedObservedSchemaSource = FixedObservedSchemaSource()

    @Bean
    open fun consumerDeclaredObservedDiffer(): RecordingDeclaredObservedDiffer = RecordingDeclaredObservedDiffer()

    @Bean
    open fun consumerMetamodelDiffer(): RecordingMetamodelDiffer = RecordingMetamodelDiffer()
}

/** An application that only wanted to replace the declaration-against-baseline comparison. */
@Configuration(proxyBeanMethods = false)
internal open class MetamodelDifferOnlyConfig {

    @Bean
    open fun declaredSchemaSource(): DeclaredSchemaSource = FixedDeclaredSchemaSource()

    @Bean
    open fun versionStore(): InMemoryMetamodelVersionStore = InMemoryMetamodelVersionStore()

    @Bean
    open fun driftReportStore(): RecordingDriftReportStore = RecordingDriftReportStore()

    @Bean
    open fun observedSchemaSource(): FixedObservedSchemaSource = FixedObservedSchemaSource()

    @Bean
    open fun consumerMetamodelDiffer(): RecordingMetamodelDiffer = RecordingMetamodelDiffer()
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

    override fun candidateMentionTypes(diff: MetamodelDiff): Set<String> = emptySet()

    override fun evaluate(diff: MetamodelDiff, propositions: Iterable<Proposition>): QuarantineResult =
        QuarantineResult(conforming = emptyList(), quarantined = emptyList())
}

@Configuration(proxyBeanMethods = false)
internal open class CustomPolicyConfig {

    @Bean
    open fun customPolicy(): DriftQuarantinePolicy = CustomPolicy()
}

internal class CustomRunner : DriftCheckRunner {
    override fun run(contextId: ContextId?): DriftCheckResult =
        throw UnsupportedOperationException("never called; this test only asks which bean won")
}

@Configuration(proxyBeanMethods = false)
internal open class CustomRunnerConfig {

    @Bean
    open fun customRunner(): DriftCheckRunner = CustomRunner()
}

/** A store-side sweep of the kind a durable backend implements once it can push the query down. */
internal class CustomSweep : DriftSweepCapable {

    override fun quarantineCandidates(
        contextId: ContextId,
        mentionTypes: Set<String>,
        limit: Int,
        afterId: String?,
    ): List<Proposition> = emptyList()

    override fun applyQuarantine(decision: com.embabel.dice.spi.QuarantineDecision.Quarantined): Proposition =
        decision.proposition

    override fun releaseFromQuarantine(propositionId: String): Proposition? = null
}

@Configuration(proxyBeanMethods = false)
internal open class CustomSweepConfig {

    @Bean
    open fun customSweep(): DriftSweepCapable = CustomSweep()
}

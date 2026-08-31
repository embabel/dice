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

import com.embabel.dice.metamodel.DeclaredObservedDiffer
import com.embabel.dice.metamodel.DeclaredSchemaSource
import com.embabel.dice.metamodel.DriftCheckRunner
import com.embabel.dice.metamodel.DriftQuarantinePolicy
import com.embabel.dice.metamodel.DriftReportStore
import com.embabel.dice.metamodel.MetamodelVersionStore
import com.embabel.dice.metamodel.ObservedSchemaSource
import com.embabel.dice.metamodel.support.DefaultDriftCheckRunner
import com.embabel.dice.metamodel.support.MentionTypeDriftQuarantinePolicy
import com.embabel.dice.metamodel.support.StructuralMetamodelDiffer
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.storage.DrivineDriftReportStore
import com.embabel.dice.storage.DrivineMetamodelVersionStore
import com.embabel.dice.storage.DrivineObservedSchemaSource
import com.embabel.dice.storage.MetamodelSchema
import org.drivine.manager.PersistenceManager
import org.drivine.schema.SchemaCatalog
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Wires the schema-governance loop: version stamps, a drift log, a snapshot of what the live graph
 * holds, the comparison between the two, and the runner that sequences them.
 *
 * ## The opt-in is a declared schema
 *
 * Governance activates when the application supplies a `DeclaredSchemaSource` bean, and only then.
 * With no declared schema there are no metamodel beans at all: no stores, no differ, no runner. It
 * is the same arrangement Spring Boot has with JPA and a `DataSource`.
 *
 * There is no sensible default declared schema. A schema states what an application governs, and
 * guessing one would either govern everything, turning every exploratory type an LLM extracted into
 * reported drift, or govern nothing, leaving the loop a no-op that still logs as if it were working.
 * Requiring the bean puts the opt-in in the consumer's own code, where a reader can see it.
 *
 * `embabel.dice.metamodel.enabled=false` is a kill switch on top of that, for switching governance
 * off in one environment while the bean stays in place. It is consulted only once the bean exists,
 * so it changes nothing for an application that never declared a schema.
 *
 * ## Drift tiers
 *
 * `embabel.dice.metamodel.drift.mode` sets how far a check may go:
 *
 * - `off` — stores and stamps only. Nothing compares a declaration against a graph.
 * - `observe` (**default**) — checks run and reports are written; no proposition is touched. The
 *   runner is an [ObserveOnlyDriftCheckRunner], so the guarantee holds against a caller who passes
 *   `dryRun = false`.
 * - `quarantine` — the real [DefaultDriftCheckRunner]. `run(dryRun = false)` moves stranded
 *   propositions to `STALE` with a reason.
 *
 * The default is `observe`. Reporting is safe to leave running indefinitely; changing proposition
 * state is a decision somebody makes on purpose. A mistyped type name in a declared schema costs
 * five minutes under `observe` and marks every proposition mentioning that type stale under
 * `quarantine`, so the escalation is opt-in in the direction where mistakes are recoverable.
 *
 * Nothing here schedules anything. This wires the capability to run a check; when a check runs is
 * the application's call, from a cron, an admin endpoint, or a startup hook.
 *
 * ## Defaults, and replacing them
 *
 * Every default is `@ConditionalOnMissingBean`, and this is a real `@AutoConfiguration` registered
 * through `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. That
 * pairing is what makes "your bean wins" a guarantee: Spring Boot registers every application bean
 * definition before it processes a single auto-configuration, so a consumer's bean is already there
 * when these conditions are evaluated, whichever order the two were declared in.
 *
 * The defaults are Drivine/Neo4j-backed, so this needs the same `PersistenceManager` the rest of
 * `dice-storage` uses. An application that declared a schema without a graph connection fails at
 * startup with the missing bean named. Wiring nothing instead would leave someone believing
 * governance was running when it wasn't.
 *
 * To replace the differ, supply a `DeclaredObservedDiffer` bean, which is the one the runner needs.
 * The shipped [StructuralMetamodelDiffer] answers both questions (declaration vs declaration, and
 * declaration vs live graph), so it is registered under its concrete type and resolves as either
 * interface.
 *
 * The metamodel constraints ride along as a [SchemaCatalog] bean, the same way the proposition and
 * lineage constraints do; Drivine's `SchemaManager` applies them idempotently on startup. The
 * stores' MERGEs are only race-free under them, so they are required.
 */
@AutoConfiguration(after = [DiceStorageAutoConfiguration::class])
@ConditionalOnBean(DeclaredSchemaSource::class)
@ConditionalOnProperty(
    prefix = "embabel.dice.metamodel",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(MetamodelProperties::class)
class MetamodelAutoConfiguration {

    private val logger = LoggerFactory.getLogger(MetamodelAutoConfiguration::class.java)

    /**
     * The uniqueness constraints the governance stores need. Declared as data in `dice-storage` so
     * the constraint list and the label list the observed-schema source excludes stay one edit
     * apart.
     *
     * No `@ConditionalOnMissingBean`, matching the other schema beans in this module. Catalogs
     * accumulate, so a consumer adding their own gets both, and these are still required.
     */
    @Bean
    fun metamodelSchema(): SchemaCatalog = SchemaCatalog.of(MetamodelSchema.specs())

    @Bean
    @ConditionalOnMissingBean(MetamodelVersionStore::class)
    fun metamodelVersionStore(persistenceManager: PersistenceManager): MetamodelVersionStore {
        logger.debug("Wiring default MetamodelVersionStore: DrivineMetamodelVersionStore")
        return DrivineMetamodelVersionStore(persistenceManager)
    }

    @Bean
    @ConditionalOnMissingBean(DriftReportStore::class)
    fun driftReportStore(persistenceManager: PersistenceManager): DriftReportStore {
        logger.debug("Wiring default DriftReportStore: DrivineDriftReportStore")
        return DrivineDriftReportStore(persistenceManager)
    }

    @Bean
    @ConditionalOnMissingBean(ObservedSchemaSource::class)
    fun observedSchemaSource(persistenceManager: PersistenceManager): ObservedSchemaSource {
        logger.debug("Wiring default ObservedSchemaSource: DrivineObservedSchemaSource")
        return DrivineObservedSchemaSource(persistenceManager)
    }

    /**
     * The shipped differ, registered under its concrete type so it resolves as both a
     * `MetamodelDiffer` (two declarations compared) and a [DeclaredObservedDiffer] (a declaration
     * compared against a live graph).
     *
     * Backing off keys on [DeclaredObservedDiffer] alone, because that is the collaborator the
     * runner needs. A consumer who supplies their own drift differ takes this one's place entirely.
     * A consumer who supplies only a `MetamodelDiffer`, which is a legitimate thing to want on its
     * own, still gets a working drift check.
     */
    @Bean
    @ConditionalOnMissingBean(DeclaredObservedDiffer::class)
    fun structuralMetamodelDiffer(): StructuralMetamodelDiffer {
        logger.debug("Wiring default differ: StructuralMetamodelDiffer")
        return StructuralMetamodelDiffer()
    }

    @Bean
    @ConditionalOnMissingBean(DriftQuarantinePolicy::class)
    fun driftQuarantinePolicy(): DriftQuarantinePolicy {
        logger.debug("Wiring default DriftQuarantinePolicy: MentionTypeDriftQuarantinePolicy")
        return MentionTypeDriftQuarantinePolicy()
    }

    /**
     * The `quarantine` tier: the real runner, which honours `run(dryRun = false)`.
     *
     * Mutually exclusive with [observeOnlyDriftCheckRunner] by property value, so which one you get
     * doesn't depend on the order the two are listed in.
     */
    @Bean
    @ConditionalOnMissingBean(DriftCheckRunner::class)
    @ConditionalOnProperty(
        prefix = "embabel.dice.metamodel.drift",
        name = ["mode"],
        havingValue = "quarantine",
    )
    fun quarantiningDriftCheckRunner(
        declaredSchemaSource: DeclaredSchemaSource,
        versionStore: MetamodelVersionStore,
        observedSchemaSource: ObservedSchemaSource,
        differ: DeclaredObservedDiffer,
        driftReportStore: DriftReportStore,
        quarantinePolicy: DriftQuarantinePolicy,
        propositionStore: PropositionStore,
    ): DriftCheckRunner {
        logger.info(
            "Metamodel drift checking wired in QUARANTINE mode: run(dryRun = false) will move " +
                "stranded propositions to STALE. Nothing runs on a schedule; a caller decides when.",
        )
        return defaultRunner(
            declaredSchemaSource, versionStore, observedSchemaSource, differ,
            driftReportStore, quarantinePolicy, propositionStore,
        )
    }

    /**
     * The default `observe` tier: the real runner behind an [ObserveOnlyDriftCheckRunner], so
     * turning this context's checks live takes a property change.
     */
    @Bean
    @ConditionalOnMissingBean(DriftCheckRunner::class)
    @ConditionalOnProperty(
        prefix = "embabel.dice.metamodel.drift",
        name = ["mode"],
        havingValue = "observe",
        matchIfMissing = true,
    )
    fun observeOnlyDriftCheckRunner(
        declaredSchemaSource: DeclaredSchemaSource,
        versionStore: MetamodelVersionStore,
        observedSchemaSource: ObservedSchemaSource,
        differ: DeclaredObservedDiffer,
        driftReportStore: DriftReportStore,
        quarantinePolicy: DriftQuarantinePolicy,
        propositionStore: PropositionStore,
    ): DriftCheckRunner {
        logger.info("Metamodel drift checking wired in OBSERVE mode: checks report, nothing is quarantined")
        return ObserveOnlyDriftCheckRunner(
            defaultRunner(
                declaredSchemaSource, versionStore, observedSchemaSource, differ,
                driftReportStore, quarantinePolicy, propositionStore,
            ),
        )
    }

    /**
     * Builds the shipped runner. A plain function rather than a shared `@Bean`, because
     * `@AutoConfiguration` runs with `proxyBeanMethods = false` — a `@Bean` method called from
     * another one would build a second, unmanaged instance. Only one of the two runner beans is
     * ever registered, so there is nothing to share anyway.
     *
     * [propositionStore] is the base `PropositionStore` port. A drift check reads propositions by
     * context or in bulk and saves them back, and uses none of the vector search, graph traversal
     * or temporal query that `PropositionRepository` adds. Asking for the wider interface here
     * would shut a plain store-and-retrieve backend out of governance over capabilities it is
     * never asked to use, and a `PropositionRepository` satisfies this parameter anyway.
     */
    private fun defaultRunner(
        declaredSchemaSource: DeclaredSchemaSource,
        versionStore: MetamodelVersionStore,
        observedSchemaSource: ObservedSchemaSource,
        differ: DeclaredObservedDiffer,
        driftReportStore: DriftReportStore,
        quarantinePolicy: DriftQuarantinePolicy,
        propositionStore: PropositionStore,
    ): DriftCheckRunner = DefaultDriftCheckRunner(
        declaredSchemaSource = declaredSchemaSource,
        versionStore = versionStore,
        observedSchemaSource = observedSchemaSource,
        differ = differ,
        driftReportStore = driftReportStore,
        quarantinePolicy = quarantinePolicy,
        propositionStore = propositionStore,
    )
}

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
 * ## It only switches on if you declared a schema
 *
 * Governance activates when — and only when — the application supplies a `DeclaredSchemaSource`
 * bean. No declared schema, no metamodel beans at all: not the stores, not the differ, not the
 * runner, nothing on the classpath doing work in the background. It is the same bargain Spring Boot
 * makes with JPA and a `DataSource`. Bring the one thing only you can decide, and the rest of the
 * plumbing appears; bring nothing and you don't pay for anything.
 *
 * That seam is deliberate rather than convenient. There is no sensible default declared schema —
 * a schema is a statement about what an application governs, and guessing one would either govern
 * everything (turning every exploratory type an LLM extracted into reported drift) or govern nothing
 * (making the whole loop a no-op that still logs as if it were working). Requiring the bean makes
 * the opt-in explicit and legible in the consumer's own code.
 *
 * `embabel.dice.metamodel.enabled=false` is the kill switch on top of that — the way to turn
 * governance off in one environment without deleting the bean. It is only consulted once the bean
 * exists, so it changes nothing for an application that never declared a schema.
 *
 * ## Tiers, and why quarantine is never the default
 *
 * `embabel.dice.metamodel.drift.mode` picks how far a check may go:
 *
 * - `off` — stores and stamps only. Nothing compares a declaration against a graph.
 * - `observe` (**default**) — checks run and reports are written; no proposition is ever touched.
 *   The runner is an [ObserveOnlyDriftCheckRunner], so the guarantee holds even against a caller who
 *   passes `dryRun = false`.
 * - `quarantine` — the real [DefaultDriftCheckRunner]. `run(dryRun = false)` now moves stranded
 *   propositions to `STALE` with a reason.
 *
 * Reporting is safe to leave running forever; changing proposition state is a decision somebody has
 * to make on purpose. A mistyped type name in a declared schema is a cheap mistake under `observe`
 * and an expensive one under `quarantine`, so the escalation is opt-in in exactly the direction that
 * mistakes are recoverable.
 *
 * Nothing here schedules anything. This wires the *capability* to run a check; when a check runs is
 * the application's call — a cron, an admin endpoint, a startup hook.
 *
 * ## Defaults, and replacing them
 *
 * Every default is `@ConditionalOnMissingBean`, and this is a real `@AutoConfiguration` registered
 * through `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. That
 * pairing is what makes "your bean wins" a guarantee rather than a coincidence: Spring Boot
 * registers every application bean definition before it processes a single auto-configuration, so a
 * consumer's bean is already there when these conditions are evaluated — no matter which order the
 * two were declared in.
 *
 * The defaults are Drivine/Neo4j-backed, so this needs the same `PersistenceManager` the rest of
 * `dice-storage` uses. An application that declared a schema without a graph connection fails at
 * startup with the missing bean named, which is the right kind of loud: silently wiring nothing
 * would leave someone believing governance was running when it wasn't.
 *
 * To replace the differ, supply a `DeclaredObservedDiffer` bean — that's the one the runner needs.
 * The shipped [StructuralMetamodelDiffer] answers both questions (declaration vs declaration, and
 * declaration vs live graph), so it is registered under its concrete type and resolves as either
 * interface.
 *
 * The metamodel constraints ride along as a [SchemaCatalog] bean, the same way the proposition and
 * lineage constraints do; Drivine's `SchemaManager` applies them idempotently on startup. They are
 * not optional — the stores' MERGEs are only race-free under them.
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
     * No `@ConditionalOnMissingBean`, matching the other schema beans in this module: catalogs
     * accumulate rather than compete, and a consumer adding their own doesn't mean these stop being
     * required.
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
     * compared against a live graph). One object, two questions.
     *
     * Backing off keys on [DeclaredObservedDiffer] alone, because that is the collaborator the
     * runner needs. A consumer who supplies their own drift differ takes this one's place entirely;
     * a consumer who supplies only a `MetamodelDiffer` — a different question, and a legitimate
     * thing to want on its own — still gets a working drift check rather than a context that
     * refuses to start.
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
     * Mutually exclusive with [observeOnlyDriftCheckRunner] by property value rather than by
     * declaration order, so which one you get never depends on how the two happened to be listed.
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
     * The default `observe` tier: the real runner behind an [ObserveOnlyDriftCheckRunner], so no
     * caller can turn this context's checks live without changing the property.
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
     * [propositionStore] is the base `PropositionStore` port, not `PropositionRepository`. A drift
     * check reads propositions by context or in bulk and saves them back; it never does vector
     * search, graph traversal, or temporal query. Asking for the wider interface here would shut a
     * plain store-and-retrieve backend out of governance over capabilities it is never asked to
     * use — and a `PropositionRepository` satisfies this parameter anyway, so nothing is lost.
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

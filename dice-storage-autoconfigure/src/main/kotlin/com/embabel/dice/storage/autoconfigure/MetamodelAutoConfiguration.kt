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

import com.embabel.dice.common.CompositeDiceEventListener
import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.governance.GovernanceOperationsService
import com.embabel.dice.metamodel.DeclaredObservedDiffer
import com.embabel.dice.metamodel.DeclaredSchemaSource
import com.embabel.dice.metamodel.DriftCheckRunner
import com.embabel.dice.metamodel.DriftReportStore
import com.embabel.dice.metamodel.InMemoryMetamodelVersionStore
import com.embabel.dice.metamodel.MetamodelDiffer
import com.embabel.dice.metamodel.MetamodelVersionStore
import com.embabel.dice.metamodel.ObservedSchemaSource
import com.embabel.dice.metamodel.SweptBaselineStore
import com.embabel.dice.metamodel.support.DefaultDriftCheckRunner
import com.embabel.dice.metamodel.support.StructuralMetamodelDiffer
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.spi.DriftQuarantinePolicy
import com.embabel.dice.spi.DriftSweepCapable
import com.embabel.dice.spi.MentionTypeDriftQuarantinePolicy
import com.embabel.dice.spi.PropositionStoreDriftSweep
import com.embabel.dice.storage.DrivineDriftReportStore
import com.embabel.dice.storage.DrivineMetamodelVersionStore
import com.embabel.dice.storage.DiceOwnedSchema
import com.embabel.dice.storage.DiceStorageSchema
import com.embabel.dice.storage.DrivineObservedSchemaSource
import com.embabel.dice.storage.MetamodelSchema
import org.drivine.manager.PersistenceManager
import org.drivine.schema.SchemaCatalog
import org.jetbrains.annotations.ApiStatus
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Wires the schema-governance loop: version stamps, a drift log, a snapshot of what the live graph
 * holds, the comparisons between them, the runner that sequences a check, and the sweep a host calls
 * when it decides to act on one.
 *
 * ## The opt-in is a declared schema
 *
 * Governance activates when the application supplies a `DeclaredSchemaSource` bean, and only then.
 * With no declared schema there are no metamodel beans at all: no stores, no differ, no runner, no
 * sweep. It is the same arrangement Spring Boot has with JPA and a `DataSource`.
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
 * ## Nothing here runs, and nothing here quarantines
 *
 * Every bean below is a capability sitting still. There is no scheduler and no startup hook: a check
 * happens when the application calls [DriftCheckRunner.run], from a cron, an admin endpoint or a
 * migration script. A check reads, compares and writes a report, and no path through it moves a
 * proposition.
 *
 * Quarantine is a second, deliberate step. The [DriftSweepCapable] bean below is what performs it,
 * and it performs it when a host calls [DriftSweepCapable.sweep] with a diff it decided to act on.
 * Nothing in this class calls that method.
 *
 * `embabel.dice.metamodel.drift.mode` picks whether the runner bean is registered at all: `off` for
 * stamps and history alone, `observe` (the default) for the check as well. See [DriftMode].
 *
 * ## Status transitions reach the application's listeners
 *
 * A sweep moves a proposition to `QUARANTINED`, and things downstream need to hear about it —
 * `ProjectionLineageStaleCascade` marks the projection records derived from that proposition stale,
 * and it can only do so if the transition is announced. So the sweep is built with every
 * `DiceEventListener` bean the application registered, fanned out through a
 * [CompositeDiceEventListener], which makes each delivery exception-safe. An application with no
 * listener bean gets [DiceEventListener.DEV_NULL] and the same behaviour otherwise.
 *
 * ## Backend selection follows the store
 *
 * The Drivine/Neo4j governance beans register under `embabel.dice.store.type=graph`, the same switch
 * [DiceStorageAutoConfiguration] uses for the proposition store, and they are declared before their
 * in-memory counterparts so the fallback resolves by registration order.
 *
 * Under the default in-memory backend a host that declares a schema still starts. It gets the
 * in-memory version store, the differ and the quarantine policy, and it gets the sweep when a
 * `PropositionStore` is on the context. It gets no drift log and no observed-schema source, because
 * both of those read a graph and there is no graph here, and therefore no drift-check runner: with
 * nothing to observe, a check has no question to ask. Such a host can stamp schemas, compare
 * declarations and sweep on a diff it built itself.
 *
 * ## Defaults, and replacing them
 *
 * Every default is `@ConditionalOnMissingBean`, and this is a real `@AutoConfiguration` registered
 * through `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. That
 * pairing is what makes "your bean wins" a guarantee: Spring Boot registers every application bean
 * definition before it processes a single auto-configuration, so a consumer's bean is already there
 * when these conditions are evaluated, whichever order the two were declared in.
 *
 * The metamodel constraints ride along as a [SchemaCatalog] bean, the same way the proposition and
 * lineage constraints do; Drivine's `SchemaManager` applies them idempotently on startup. The
 * stores' MERGEs are only race-free under them, so they are required wherever the graph stores are.
 */
@ApiStatus.Experimental
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

    // ---- Graph backend (embabel.dice.store.type=graph) ----

    /**
     * The uniqueness constraints the governance stores need. Declared as data in `dice-storage` so
     * the constraint list and the label list the observed-schema source excludes stay one edit
     * apart.
     *
     * Graph-only, since it is DDL for the Drivine stores below. No `@ConditionalOnMissingBean`,
     * matching the other schema beans in this module. Catalogs accumulate, so a consumer adding
     * their own gets both, and these are still required.
     */
    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    fun metamodelSchema(): SchemaCatalog = SchemaCatalog.of(MetamodelSchema.specs())

    /**
     * The metamodel store schema, registered so ownership derivation sees it: the drift
     * observation excludes what the registered [DiceStorageSchema] beans declare, and without
     * this bean governance would observe its own report bookkeeping as drift.
     */
    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    fun metamodelStorageSchema(): DiceStorageSchema = MetamodelSchema

    /**
     * The version store, returned as a [SweptBaselineStore] on purpose.
     *
     * Both shipped stores track the reconciled baseline, and a host needs that capability by name:
     * after it has swept every context it meant to reconcile it calls
     * [SweptBaselineStore.markSwept] itself. Spring predicts a bean's type from the factory method's
     * return type, so declaring the narrower [MetamodelVersionStore] here would hide the baseline
     * capability at injection time and leave a `SweptBaselineStore` injection point unresolvable.
     */
    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnMissingBean(MetamodelVersionStore::class)
    fun drivineMetamodelVersionStore(persistenceManager: PersistenceManager): SweptBaselineStore {
        logger.debug("Wiring graph MetamodelVersionStore: DrivineMetamodelVersionStore")
        return DrivineMetamodelVersionStore(persistenceManager)
    }

    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnMissingBean(DriftReportStore::class)
    fun drivineDriftReportStore(persistenceManager: PersistenceManager): DriftReportStore {
        logger.debug("Wiring graph DriftReportStore: DrivineDriftReportStore")
        return DrivineDriftReportStore(persistenceManager)
    }

    /** What dice owns in this application, derived from the registered storage schemas. */
    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnMissingBean(DiceOwnedSchema::class)
    fun diceOwnedSchema(schemas: List<DiceStorageSchema>): DiceOwnedSchema =
        DiceOwnedSchema.of(schemas)

    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnMissingBean(ObservedSchemaSource::class)
    fun drivineObservedSchemaSource(
        persistenceManager: PersistenceManager,
        ownedSchema: DiceOwnedSchema,
    ): ObservedSchemaSource {
        logger.debug("Wiring graph ObservedSchemaSource: DrivineObservedSchemaSource")
        return DrivineObservedSchemaSource(persistenceManager, ownedSchema)
    }

    // ---- In-memory backend (default) ----

    /**
     * Schema history for a host with no graph. It keeps stamps and the swept baseline in the JVM, so
     * a declaration can be stamped and compared against its predecessor before there is a database.
     *
     * There is no in-memory counterpart to the other two graph beans, and that is the honest answer
     * for both. A drift log is a durable record an operator reads days later, and a heap map is not
     * one. An observed-schema source reports what a live graph holds, and a host with no graph has
     * no graph to report on.
     */
    @Bean
    @ConditionalOnMissingBean(MetamodelVersionStore::class)
    fun inMemoryMetamodelVersionStore(): SweptBaselineStore {
        logger.debug("Wiring in-memory MetamodelVersionStore: InMemoryMetamodelVersionStore")
        return InMemoryMetamodelVersionStore()
    }

    // ---- Pure-JVM collaborators, both backends ----

    /**
     * The shipped differ, registered under its concrete type so it resolves as both a
     * [MetamodelDiffer] (two declarations compared) and a [DeclaredObservedDiffer] (a declaration
     * compared against a live graph).
     *
     * It backs off as soon as the application supplies either one. That looks strict for a host that
     * supplied only a [MetamodelDiffer], since the declaration-against-graph question is then
     * unanswered by any bean — but [driftCheckRunner] fills that role with its own
     * [StructuralMetamodelDiffer] and both differs are honoured. Registering this bean anyway would
     * put two [MetamodelDiffer] candidates on the context, and the consumer's bean would lose to an
     * ambiguity nobody asked for.
     */
    @Bean
    @ConditionalOnMissingBean(value = [DeclaredObservedDiffer::class, MetamodelDiffer::class])
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

    // ---- The sweep, for a host that decides to act on a check ----

    /**
     * The reference sweep over whatever [PropositionStore] the application has, wired with the
     * application's own event listeners so a quarantine it performs is heard downstream.
     *
     * Backs off when a bean already implements [DriftSweepCapable], which is how a durable store
     * that can push the candidate query down into its backend takes over: it implements the
     * interface itself and this default steps aside.
     *
     * [propositionStore] is the base persistence port. A sweep reads one context and saves, and uses
     * none of the vector search, graph traversal or temporal query `PropositionRepository` adds, so
     * asking for the wider interface would shut a plain store-and-retrieve backend out of governance
     * over capabilities it never touches. A `PropositionRepository` satisfies this parameter anyway.
     *
     * Registering this bean sweeps nothing. It is the object a host calls when it has read a check
     * and decided to act.
     *
     * @param listeners Every `DiceEventListener` bean the application registered. Each status
     *   transition the sweep performs is announced to all of them, which is what lets
     *   `ProjectionLineageStaleCascade` mark the projection records derived from a quarantined
     *   proposition stale.
     */
    @Bean
    @ConditionalOnBean(PropositionStore::class)
    @ConditionalOnMissingBean(DriftSweepCapable::class)
    fun propositionStoreDriftSweep(
        propositionStore: PropositionStore,
        listeners: ObjectProvider<DiceEventListener>,
    ): DriftSweepCapable {
        val listener = compositeListener(listeners)
        logger.debug("Wiring default DriftSweepCapable: PropositionStoreDriftSweep")
        return PropositionStoreDriftSweep(propositionStore, listener)
    }

    // ---- The check ----

    /**
     * The shipped runner. It stamps the declaration, snapshots the graph, runs both comparisons and
     * writes a report, and it touches no proposition on any path.
     *
     * Registered only when there is something to observe and somewhere to write the answer, which is
     * why it is conditional on both [ObservedSchemaSource] and [DriftReportStore]. Under the default
     * in-memory backend neither exists, and the context starts with the rest of the loop wired.
     *
     * ## Two differs, resolved independently
     *
     * A check asks two different questions and each has its own collaborator: [DeclaredObservedDiffer]
     * compares the declaration against the live graph, and [MetamodelDiffer] compares the declaration
     * against the baseline a sweep last reconciled. One object commonly answers both — the shipped
     * [StructuralMetamodelDiffer] does — so the two are looked up separately and the same bean is
     * free to satisfy each. An application that supplies a distinct bean for either role gets that
     * bean used for that role, and whichever role no bean fills falls back to a
     * [StructuralMetamodelDiffer] built here. Looking up one interface and testing the result for
     * the other is what let a consumer's [MetamodelDiffer] be silently ignored.
     *
     * @param declaredObservedDiffers Candidates for the declaration-against-graph comparison.
     * @param metamodelDiffers Candidates for the declaration-against-baseline comparison.
     */
    @Bean
    @ConditionalOnMissingBean(DriftCheckRunner::class)
    @ConditionalOnBean(value = [ObservedSchemaSource::class, DriftReportStore::class])
    @ConditionalOnProperty(
        prefix = "embabel.dice.metamodel.drift",
        name = ["mode"],
        havingValue = "observe",
        matchIfMissing = true,
    )
    fun driftCheckRunner(
        declaredSchemaSource: DeclaredSchemaSource,
        versionStore: MetamodelVersionStore,
        observedSchemaSource: ObservedSchemaSource,
        driftReportStore: DriftReportStore,
        declaredObservedDiffers: ObjectProvider<DeclaredObservedDiffer>,
        metamodelDiffers: ObjectProvider<MetamodelDiffer>,
    ): DriftCheckRunner {
        // One fallback object, shared by both roles and built only if a role needs it.
        val fallback = lazy { StructuralMetamodelDiffer() }
        val differ = declaredObservedDiffers.getIfUnique() ?: fallback.value
        val metamodelDiffer = metamodelDiffers.getIfUnique() ?: fallback.value

        logger.info(
            "Metamodel drift checking wired: a check reports and quarantines nothing. Nothing runs " +
                "on a schedule; the application decides when to call it.",
        )
        return DefaultDriftCheckRunner(
            declaredSchemaSource = declaredSchemaSource,
            versionStore = versionStore,
            observedSchemaSource = observedSchemaSource,
            differ = differ,
            metamodelDiffer = metamodelDiffer,
            driftReportStore = driftReportStore,
        )
    }

    // ---- The operator surface ----

    /**
     * The one object a person or an agent reaches the loop through: read the drift log and the
     * current declaration, run a check, release a quarantined proposition.
     *
     * Declared last on purpose. `@ConditionalOnBean` is evaluated in declaration order within a
     * configuration class, so the runner and the sweep above are already registered when this
     * condition is tested.
     *
     * The conditions are the loop's own: with no drift log there are no reports to read, with no
     * runner there is no check to run, and with no sweep there is no hold to lift. Under the default
     * in-memory backend, and under `drift.mode=off`, none of those exist and neither does this bean.
     *
     * Registering it runs nothing. It is the object a host calls, or hands to `GovernanceController`
     * and `GovernanceTools`.
     *
     * It is also the only governance bean either front end needs. `GovernanceController` arrives
     * with `DiceRestConfiguration`, the one import that opens any DICE REST surface, and switches
     * itself on when this bean exists. `GovernanceTools` is constructed by the host, the way every
     * DICE tool object is; nothing here registers one.
     */
    @Bean
    @ConditionalOnBean(
        value = [
            DriftReportStore::class,
            DriftCheckRunner::class,
            DriftSweepCapable::class,
            PropositionStore::class,
        ],
    )
    @ConditionalOnMissingBean(GovernanceOperationsService::class)
    fun governanceOperationsService(
        declaredSchemaSource: DeclaredSchemaSource,
        versionStore: MetamodelVersionStore,
        driftReportStore: DriftReportStore,
        driftCheckRunner: DriftCheckRunner,
        driftSweep: DriftSweepCapable,
        propositionStore: PropositionStore,
    ): GovernanceOperationsService {
        logger.info(
            "Metamodel operator surface wired: drift reports and the declared version are readable, " +
                "a check can be run, and a quarantined proposition can be released. Nothing here runs " +
                "on its own.",
        )
        return GovernanceOperationsService(
            declaredSchemaSource = declaredSchemaSource,
            versionStore = versionStore,
            driftReportStore = driftReportStore,
            driftCheckRunner = driftCheckRunner,
            driftSweep = driftSweep,
            propositions = propositionStore,
        )
    }

    /**
     * Every application listener as one listener. [CompositeDiceEventListener] wraps each delivery so
     * a throwing listener can never abort the sweep that emitted the event, and an application with
     * no listener bean gets the no-op.
     */
    private fun compositeListener(listeners: ObjectProvider<DiceEventListener>): DiceEventListener {
        val all = listeners.orderedStream().toList()
        if (all.isEmpty()) {
            logger.debug("No DiceEventListener bean on the context; drift quarantine will announce to nobody")
            return DiceEventListener.DEV_NULL
        }
        logger.debug("Drift quarantine will announce status transitions to {} listener(s)", all.size)
        return CompositeDiceEventListener(all)
    }
}

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
import com.embabel.dice.spi.DecayStatusPolicy
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.incremental.InMemoryChunkHistoryStore
import com.embabel.dice.metamodel.MetamodelVersionStore
import com.embabel.dice.projection.lineage.CollectorRecordStore
import com.embabel.dice.projection.lineage.InMemoryCollectorRecordStore
import com.embabel.dice.projection.lineage.InMemoryProjectionRecordStore
import com.embabel.dice.projection.lineage.ProjectionRecordStore
import com.embabel.dice.proposition.DecayManager
import com.embabel.dice.proposition.DecaySweepConfig
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.extraction.ExtractionRunStore
import com.embabel.dice.proposition.extraction.PropositionRunLinkStore
import com.embabel.dice.proposition.store.InMemoryDecayManager
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.spi.CollectorTraceStore
import com.embabel.dice.storage.DiceStorageSchema
import com.embabel.dice.storage.DrivineChunkHistoryStore
import com.embabel.dice.storage.DrivineCollectorRecordStore
import com.embabel.dice.storage.DrivineExtractionRunStore
import com.embabel.dice.storage.DrivinePropositionRepository
import com.embabel.dice.storage.DrivinePropositionRunLinkStore
import com.embabel.dice.storage.DrivineProjectionRecordStore
import com.embabel.dice.storage.ExtractionRunSchema
import com.embabel.dice.storage.GraphDecayManager
import com.embabel.dice.storage.LineageSchema
import com.embabel.dice.storage.diceStorageCatalog
import org.drivine.autoconfigure.DrivineAutoConfiguration
import org.drivine.manager.StatelessGraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaCatalog
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.BeanFactoryUtils
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionMessage
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.ConfigurationCondition
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Lazy
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.PlatformTransactionManager

/**
 * Auto-configures the Dice proposition store and its lineage record stores (projection records and
 * the collector audit trail).
 *
 * The backend follows from what the application has set up. If Drivine is configured (its
 * `PersistenceManager` bean exists), the Drivine/Neo4j stores are wired; otherwise the in-memory
 * stores are. No property is needed. The old `embabel.dice.store.type` switch is deprecated but still
 * respected: `in-memory` forces the in-memory stores, and `graph` insists on Drivine (see
 * [StoreBackendCheck]). Every bean is `@ConditionalOnMissingBean`, so an application's own bean always
 * wins. Graph beans are declared before their in-memory counterparts so the fallback resolves by
 * registration order.
 *
 * This runs after Drivine's own auto-configuration and DICE's Drivine one, so a `PersistenceManager` is
 * already registered, or DICE has said it will build one, when the backend is decided.
 *
 * Schema (indexes/constraints) is declared as [SchemaCatalog] beans; Drivine's `SchemaManager`
 * (registered by the starter) applies them idempotently on startup — no runner here.
 */
@AutoConfiguration(after = [DrivineAutoConfiguration::class])
@EnableConfigurationProperties(DiceStoreProperties::class)
@Import(StoreBackendCheck::class)
class DiceStorageAutoConfiguration {

    private val logger = LoggerFactory.getLogger(DiceStorageAutoConfiguration::class.java)

    // ---- Graph backend (when Drivine is configured) ----

    @Bean
    @ConditionalOnBean(Ai::class)
    @ConditionalOnDrivineStores
    @ConditionalOnMissingBean(PropositionRepository::class)
    fun drivinePropositionRepository(
        graphObjectManager: StatelessGraphObjectManager,
        persistenceManager: PersistenceManager,
        ai: Ai,
        transactionManager: PlatformTransactionManager,
        embeddingServices: ObjectProvider<EmbeddingService>,
    ): PropositionRepository {
        logger.info(
            "Wiring graph proposition store (Drivine/Neo4j), vector index '{}'",
            DrivinePropositionRepository.VECTOR_INDEX,
        )
        return DrivinePropositionRepository(
            graphObjectManager, persistenceManager, storeEmbeddingService("graph", ai, embeddingServices), transactionManager,
        )
    }

    /**
     * The application's own [EmbeddingService] bean where there is an unambiguous one
     * (a `@Primary` bean counts), otherwise the platform default.
     *
     * Preferring the bean matters for a host that can start with NO embedding model
     * configured — one whose provider key arrives at first run rather than at boot.
     * Such a host registers an embedding service that reports its own absence and can be
     * switched on later, whereas `ai.withDefaultEmbeddingService()` resolves the default
     * eagerly and throws when no model is registered, taking the context down with it.
     * [DrivinePropositionRepository] only touches the service when it actually embeds, so
     * an absent-tolerant one is safe to hold.
     *
     * Several beans and no `@Primary` is the ORDINARY case, not an ambiguity to fail on: the platform
     * registers one bean per model it can reach (an OpenAI key alone registers three), and choosing
     * among those is exactly what the configured default embedding model is for. A host that wants
     * its own service used marks it `@Primary`.
     */
    private fun embeddingService(ai: Ai, embeddingServices: ObjectProvider<EmbeddingService>): EmbeddingService =
        embeddingServices.getIfUnique() ?: ai.withDefaultEmbeddingService()

    /**
     * [embeddingService] for a proposition store, saying which one it got and why. Both stores go
     * through here, so a host's embedding service reaches whichever backend it runs, and the one
     * line an operator needs — is memory embedded with the model I configured? — is at startup
     * rather than inferred from a vector-width mismatch later.
     */
    private fun storeEmbeddingService(
        store: String,
        ai: Ai,
        embeddingServices: ObjectProvider<EmbeddingService>,
    ): EmbeddingService {
        val hostService = embeddingServices.getIfUnique()
        val service = hostService ?: ai.withDefaultEmbeddingService()
        logger.info(
            "{} proposition store embeds with '{}' ({}): {}",
            store,
            service.name,
            service.provider,
            if (hostService != null) "the application's EmbeddingService bean" else "the platform default embedding service",
        )
        return service
    }

    @Bean
    @ConditionalOnDrivineStores
    @ConditionalOnMissingBean(ChunkHistoryStore::class)
    fun drivineChunkHistoryStore(
        graphObjectManager: StatelessGraphObjectManager,
        persistenceManager: PersistenceManager,
    ): ChunkHistoryStore = DrivineChunkHistoryStore(graphObjectManager, persistenceManager)

    @Bean
    @ConditionalOnBean(PropositionRepository::class)
    @ConditionalOnDrivineStores
    @ConditionalOnMissingBean(DecayManager::class)
    fun graphDecayManager(
        repository: PropositionRepository,
        persistenceManager: PersistenceManager,
    ): DecayManager = GraphDecayManager(repository, persistenceManager)

    @Bean
    @ConditionalOnDrivineStores
    @ConditionalOnMissingBean(ProjectionRecordStore::class)
    fun drivineProjectionRecordStore(
        persistenceManager: PersistenceManager,
    ): ProjectionRecordStore = DrivineProjectionRecordStore(persistenceManager)

    @Bean
    @ConditionalOnDrivineStores
    @ConditionalOnMissingBean(CollectorRecordStore::class)
    fun drivineCollectorRecordStore(
        persistenceManager: PersistenceManager,
    ): CollectorRecordStore = DrivineCollectorRecordStore(persistenceManager)

    /**
     * The durable extraction-run header store.
     *
     * Two conditions, both required. The graph backend has to be in use (Drivine is
     * configured), and extraction runs have to be turned on with
     * `embabel.dice.extraction.runs.enabled=true`. The property defaults to off, so an upgrading
     * host gets none of this until it asks.
     *
     * The property is what carries the decision, because registering these beans is what puts the
     * run schema in front of Drivine's schema manager, and that writes constraints and indexes to
     * the host's database on startup. [extractionRunSchema] lists exactly what lands. A host that
     * turns the flag on has consented to those writes; one that leaves it alone keeps a database
     * with no run labels in it.
     */
    @Bean
    @ConditionalOnDrivineStores
    @ConditionalOnProperty(
        prefix = "embabel.dice.extraction.runs",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = false,
    )
    @ConditionalOnMissingBean(ExtractionRunStore::class)
    fun drivineExtractionRunStore(
        persistenceManager: PersistenceManager,
        transactionManager: PlatformTransactionManager,
    ): ExtractionRunStore = DrivineExtractionRunStore(persistenceManager, transactionManager)

    /**
     * Where `(proposition, run)` links go.
     *
     * Registered on the same two conditions as the run store: the graph backend, and
     * `embabel.dice.extraction.runs.enabled=true`. It is reached only by an extraction that carries
     * a run, and binding it is still the host's move: `IncrementalPropositionExtraction` takes it
     * through `withRunLineage`, so having the bean in the context does not by itself make anything
     * record lineage.
     */
    @Bean
    @ConditionalOnDrivineStores
    @ConditionalOnProperty(
        prefix = "embabel.dice.extraction.runs",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = false,
    )
    @ConditionalOnMissingBean(PropositionRunLinkStore::class)
    fun drivinePropositionRunLinkStore(
        persistenceManager: PersistenceManager,
    ): PropositionRunLinkStore = DrivinePropositionRunLinkStore(persistenceManager)

    /**
     * The constraints and indexes the run store and the lineage relation need.
     *
     * Separate from [lineageRecordSchema] because that one is the projection and collector audit
     * trail, which has its own labels and its own lifecycle. Both are ensured on startup by
     * Drivine's schema manager.
     *
     * Behind the same `embabel.dice.extraction.runs.enabled` flag as the two stores, and for the
     * reason the flag exists: this catalog is the part that writes to the host's database. Turning
     * the flag on ensures three uniqueness constraints — on `ExtractionRun(contextId, runId)`,
     * `ExtractionRunInvocation(contextId, runId, invocationIndex, attempt)` and
     * `ExtractionRunTerminalWrite(contextId, runId)` — and five range indexes, four on
     * `ExtractionRun` (`contextId`; `contextId, rootRunId`; `contextId, parentRunId`;
     * `contextId, startedAtEpochSecond`) and one on `ExtractionRunInvocation(contextId, runId)`.
     * Leaving it off keeps every one of them out of the catalog the schema manager sees.
     */
    @Bean
    @ConditionalOnDrivineStores
    @ConditionalOnProperty(
        prefix = "embabel.dice.extraction.runs",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = false,
    )
    fun extractionRunSchema(): SchemaCatalog = SchemaCatalog.of(ExtractionRunSchema.specs())

    /**
     * The lineage stores' schema, registered as a [DiceStorageSchema] so one bean answers both
     * questions about it: Drivine ensures its constraints and indexes, and dice's drift observation
     * reads the same object to recognise `(:ProjectionRecord)` and `(:CollectorRecord)` nodes as its
     * own. Registering the DDL by hand here, as this used to, left the second half to a separate
     * list that could fall behind.
     *
     * Natural keys back the MERGE upserts, so a replayed record updates in place and never
     * duplicates; the range indexes back the lineage lookups. Both live in [LineageSchema].
     */
    @Bean
    @ConditionalOnDrivineStores
    fun lineageStorageSchema(): DiceStorageSchema = LineageSchema

    @Bean
    @ConditionalOnDrivineStores
    fun lineageRecordSchema(): SchemaCatalog = diceStorageCatalog(listOf(LineageSchema))

    @Bean
    @ConditionalOnBean(Ai::class)
    @ConditionalOnDrivineStores
    fun propositionConstraintSchema(): SchemaCatalog = SchemaCatalog.of(
        UniquenessConstraintSpec(label = "Proposition", property = "id"),
        UniquenessConstraintSpec(label = "Mention", property = "id"),
        UniquenessConstraintSpec(label = "ProcessedChunk", property = "id"),
        UniquenessConstraintSpec(label = "Source", property = "key"),
        // Cross-instance dedup backstop: the same fact minted by parallel writers as distinct ids
        // collapses to one node; save() catches the violation and reuses the existing node.
        UniquenessConstraintSpec(label = "Proposition", properties = listOf("contextId", "text")),
        RangeIndexSpec("Proposition", "contextId"),
        RangeIndexSpec("Proposition", "status"),
        RangeIndexSpec("Proposition", "level"),
        RangeIndexSpec("Proposition", "effectiveConfidence"),
        RangeIndexSpec("Mention", "resolvedId"),
        RangeIndexSpec("ProcessedChunk", "sourceId"),
        RangeIndexSpec("Source", "kind"),
    )

    @Bean
    @ConditionalOnBean(Ai::class)
    @ConditionalOnDrivineStores
    @ConditionalOnProperty(
        prefix = "embabel.dice.store.vector-index",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun propositionVectorIndexSchema(
        ai: Ai,
        embeddingServices: ObjectProvider<EmbeddingService>,
    ): SchemaCatalog {
        // A vector index is created AT the embedding model's dimension, so with no model
        // there is no dimension to create it at. Register nothing rather than guess: an
        // index at the wrong dimension is worse than none, because writes to it succeed.
        // The catalog is rebuilt on the next boot, by which time a model configured at
        // first run is registered.
        val embeddingService = runCatching { embeddingService(ai, embeddingServices) }
            .getOrElse {
                logger.warn("Skipping proposition vector index schema: no embedding model ({})", it.message)
                return SchemaCatalog.of()
            }
        val dimensions = runCatching { embeddingService.dimensions }
            .getOrElse {
                logger.warn("Skipping proposition vector index schema: no embedding model ({})", it.message)
                return SchemaCatalog.of()
            }
        val spec = propositionVectorIndexSpec(dimensions)
        logger.info("Registering proposition vector index schema: {} (model={})", spec, embeddingService.name)
        return SchemaCatalog.of(spec).withVersion(embeddingService.name)
    }

    /**
     * The schema (DDL) for the proposition vector index. Its label, property, name, and similarity
     * come straight from [DrivinePropositionRepository]'s canonical constants — the same identity the
     * `@VectorIndex` annotation gives `loadNearest` and the `findClusters` Cypher — so all three paths
     * target one index. Only [dimensions] varies, since it comes from the embedding model at runtime.
     */
    /**
     * The same index, ensured on demand instead of at startup.
     *
     * The catalog above can only register what it can see WHEN IT IS BUILT, and a host that starts
     * without an embedding model has nothing to register. Its own note said the catalog would be
     * rebuilt on the next boot — true while such a host restarted after a model was configured,
     * and no longer true of one that applies a provider key without restarting. The index was then
     * never made: the model came alive, was used, and every read failed with "There is no such
     * vector schema index".
     *
     * A catalog cannot answer it either. `SchemaCatalog` holds materialised specs and
     * `SchemaManager` snapshots the catalog beans when it is built, so a later `enforce()` applies
     * the same empty catalog. This goes through `PersistenceManager.indexes`, which is the public
     * way to converge one index without a catalog at all.
     *
     * The host decides when — see [PropositionVectorIndexConvergence].
     */
    @Bean
    @ConditionalOnBean(Ai::class)
    @ConditionalOnDrivineStores
    @ConditionalOnProperty(
        prefix = "embabel.dice.store.vector-index",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun propositionVectorIndexConvergence(
        persistenceManager: PersistenceManager,
        ai: Ai,
        embeddingServices: ObjectProvider<EmbeddingService>,
    ): PropositionVectorIndexConvergence = PropositionVectorIndexConvergence(
        persistenceManager = persistenceManager,
        // Resolved per call, not captured: the model that matters here is one that arrived after
        // this bean was built.
        dimensionsOf = PropositionVectorIndexConvergence.dimensionsFrom { embeddingService(ai, embeddingServices) },
        spec = ::propositionVectorIndexSpec,
    )

    /**
     * Delegated to the repository, which owns the identity constants and now needs the spec at
     * runtime as well — [DrivinePropositionRepository.reembedAll] reconciles the index itself. Two
     * builders would be two chances for the startup DDL and the re-embed to describe different
     * indexes.
     */
    internal fun propositionVectorIndexSpec(dimensions: Int): VectorIndexSpec =
        DrivinePropositionRepository.vectorIndexSpec(dimensions)

    // ---- In-memory backend (default) ----

    @Bean
    @ConditionalOnBean(Ai::class)
    @ConditionalOnMissingBean(PropositionRepository::class)
    fun inMemoryPropositionRepository(
        ai: Ai,
        embeddingServices: ObjectProvider<EmbeddingService>,
    ): PropositionRepository {
        logger.info("Wiring in-memory proposition store")
        return InMemoryPropositionRepository(storeEmbeddingService("in-memory", ai, embeddingServices))
    }

    @Bean
    @ConditionalOnMissingBean(ChunkHistoryStore::class)
    fun inMemoryChunkHistoryStore(): ChunkHistoryStore = InMemoryChunkHistoryStore()

    @Bean
    @ConditionalOnMissingBean(ProjectionRecordStore::class)
    fun inMemoryProjectionRecordStore(): ProjectionRecordStore = InMemoryProjectionRecordStore()

    @Bean
    @ConditionalOnMissingBean(CollectorRecordStore::class)
    fun inMemoryCollectorRecordStore(): CollectorRecordStore = InMemoryCollectorRecordStore()

    @Bean
    @ConditionalOnBean(PropositionRepository::class)
    @ConditionalOnMissingBean(DecayManager::class)
    fun inMemoryDecayManager(repository: PropositionRepository): DecayManager =
        InMemoryDecayManager(repository)
}

/**
 * Schedules the decay tick (materialise cached confidence, then apply lifecycle transitions). Split
 * out so `@EnableScheduling` is only switched on when decay is enabled. Resolves the [DecayManager]
 * lazily via [ObjectProvider] so it's robust to the backend that registered it.
 */
@AutoConfiguration(after = [DiceStorageAutoConfiguration::class])
@ConditionalOnProperty(
    prefix = "embabel.dice.store.decay",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(DiceStoreProperties::class)
@EnableScheduling
class DiceDecaySchedulingConfiguration(
    private val decayManager: ObjectProvider<DecayManager>,
    private val properties: DiceStoreProperties,
) {
    private val logger = LoggerFactory.getLogger(DiceDecaySchedulingConfiguration::class.java)

    @Scheduled(fixedDelayString = "\${embabel.dice.store.decay.interval-ms:3600000}")
    fun tick() {
        val manager = decayManager.ifAvailable ?: return
        val result = manager.tick(
            DecaySweepConfig(
                policy = DecayStatusPolicy(kMultiplier = properties.decay.k),
                pruneStale = properties.decay.pruneStale,
            )
        )
        logger.debug("Decay tick result: {}", result)
    }
}

/**
 * How DICE decides between its Drivine stores and its in-memory ones.
 *
 * Drivine counts as configured when its `PersistenceManager` bean exists, which is exactly what the
 * Drivine stores need to be built. The deprecated `embabel.dice.store.type` property can still force
 * the in-memory stores; any other value only adds checks (see [StoreBackendCheck]).
 */
internal object StoreBackend {
    const val TYPE_PROPERTY = "${DicePropertyPrefixes.STORE}.type"
    const val GRAPH = "graph"
    const val IN_MEMORY = "in-memory"

    /** The property's value, trimmed and lower-cased, or null when it isn't set or is blank. */
    fun requested(environment: Environment): String? =
        environment.getProperty(TYPE_PROPERTY)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    fun drivineConfigured(beanFactory: ListableBeanFactory): Boolean =
        BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
            beanFactory, PersistenceManager::class.java, true, false,
        ).isNotEmpty()
}

/**
 * Registers a bean only when DICE should use its Drivine stores: the deprecated property says `graph`,
 * or it is unset and Drivine is configured. Every Drivine-backed bean in this module carries it, so
 * all of them switch together.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Conditional(OnDrivineStores::class)
internal annotation class ConditionalOnDrivineStores

/**
 * The check behind [ConditionalOnDrivineStores].
 *
 * `graph` is taken at its word without looking for Drivine. The lookup runs while beans are being
 * registered, so it can't see a `PersistenceManager` that a host's own auto-configuration registers
 * after DICE's; an explicit request must not be lost to that. Whether Drivine really turned up is
 * left to [StoreBackendCheck], which runs once everything is registered.
 *
 * With the property unset, the lookup sees Drivine's own auto-configuration (which runs before DICE's)
 * and anything the application declares itself. It also matches when DICE's own Drivine auto-configuration
 * is in, though the managers it may build aren't registered yet: it decides about them once every bean is
 * registered, and either a PersistenceManager is there by the time beans are created or startup stops
 * asking for both managers or neither.
 */
internal class OnDrivineStores : SpringBootCondition(), ConfigurationCondition {

    override fun getConfigurationPhase(): ConfigurationCondition.ConfigurationPhase =
        ConfigurationCondition.ConfigurationPhase.REGISTER_BEAN

    override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
        val message = ConditionMessage.forCondition(ConditionalOnDrivineStores::class.java)
        when (StoreBackend.requested(context.environment)) {
            StoreBackend.IN_MEMORY -> return ConditionOutcome.noMatch(
                message.because("${StoreBackend.TYPE_PROPERTY}=${StoreBackend.IN_MEMORY} forces the in-memory stores"),
            )
            StoreBackend.GRAPH -> return ConditionOutcome.match(
                message.because("${StoreBackend.TYPE_PROPERTY}=${StoreBackend.GRAPH} asks for the Drivine stores"),
            )
        }
        val beanFactory = context.beanFactory
            ?: return ConditionOutcome.noMatch(message.because("there is no bean factory to look in"))
        return when {
            StoreBackend.drivineConfigured(beanFactory) ->
                ConditionOutcome.match(message.found("Drivine bean").items(PersistenceManager::class.java.simpleName))
            DiceDrivineManagerRegistrar.declaredIn(beanFactory) ->
                ConditionOutcome.match(message.because("DICE's Drivine auto-configuration provides the Drivine managers"))
            else ->
                ConditionOutcome.noMatch(message.didNotFind("Drivine bean").items(PersistenceManager::class.java.simpleName))
        }
    }
}

/**
 * Checks once, at startup, that DICE ended up on the stores the application meant it to use.
 *
 * It runs after every bean has been registered, so unlike the conditions it sees a Drivine registered
 * by a host auto-configuration that came after DICE's. It is never lazy: with
 * `spring.main.lazy-initialization=true` nothing asks for this bean, so a lazy one would simply never
 * run and the check with it.
 *
 * With the deprecated `embabel.dice.store.type` property unset, it refuses to start when Drivine is
 * configured but DICE wired any of its own in-memory stores that have a Drivine version: Drivine arrived
 * too late for the conditions to see it, and durable storage would otherwise be quietly lost. That
 * covers every such store, not just the proposition store, because a host without an `Ai` bean gets no
 * proposition store at all yet still gets the others. A host can swap any one store by defining its own
 * bean, and that store is left alone while the rest must still follow Drivine. The decay manager DICE
 * builds over a host's own proposition store is left alone too.
 * With the property set, it logs one deprecation warning, then refuses to start if the value is
 * unknown, or if it is `graph` and Drivine isn't configured. `in-memory` never fails.
 */
@Lazy(false)
internal class StoreBackendCheck(environment: Environment, beanFactory: ConfigurableListableBeanFactory) {

    init {
        val requested = StoreBackend.requested(environment)
        if (requested == null) {
            val lost = inMemoryStoresDespiteDrivine(environment, beanFactory)
            check(lost.isEmpty()) {
                "Drivine is configured (there is a Drivine PersistenceManager bean), but it was registered after " +
                    "DICE's auto-configuration had already chosen its stores, so DICE wired its in-memory " +
                    "stores (${lost.joinToString()}) and nothing would be saved to the database. Fix it in one " +
                    "of these ways: order the " +
                    "auto-configuration that sets up Drivine before " +
                    "${DiceStorageAutoConfiguration::class.java.name} (the 'before' attribute of its @AutoConfiguration), " +
                    "set " +
                    "${StoreBackend.TYPE_PROPERTY}=${StoreBackend.GRAPH}, or define the store beans yourself."
            }
        } else {
            LoggerFactory.getLogger(StoreBackendCheck::class.java).warn(
                "{} is deprecated and will be removed. DICE now uses its Drivine stores when Drivine is " +
                    "configured and its in-memory stores otherwise, so the property can go. Keep " +
                    "'{}' only to force the in-memory stores while Drivine is present.",
                StoreBackend.TYPE_PROPERTY,
                StoreBackend.IN_MEMORY,
            )
            when (requested) {
                StoreBackend.IN_MEMORY -> Unit
                StoreBackend.GRAPH -> check(StoreBackend.drivineConfigured(beanFactory)) {
                    "${StoreBackend.TYPE_PROPERTY}=${StoreBackend.GRAPH} asks for DICE's Drivine stores, but " +
                        "Drivine isn't configured: there is no Drivine PersistenceManager bean. Add Drivine's " +
                        "Spring Boot starter and its data source settings, or remove the property (it is " +
                        "deprecated) and DICE will use its in-memory stores."
                }
                else -> throw IllegalStateException(
                    "${StoreBackend.TYPE_PROPERTY}=${environment.getProperty(StoreBackend.TYPE_PROPERTY)} isn't " +
                        "a value DICE knows. The allowed values are '${StoreBackend.GRAPH}' and " +
                        "'${StoreBackend.IN_MEMORY}', and the property is deprecated: remove it and DICE picks " +
                        "its stores from whether Drivine is configured.",
                )
            }
        }
    }

    /**
     * The stores DICE wired in memory although Drivine is configured, by interface name; empty when
     * there are none or Drivine isn't configured. Only bean definitions are read, so no bean is
     * created just to answer this.
     */
    private fun inMemoryStoresDespiteDrivine(
        environment: Environment,
        beanFactory: ConfigurableListableBeanFactory,
    ): List<String> {
        if (!StoreBackend.drivineConfigured(beanFactory)) return emptyList()
        return beanFactory.beanDefinitionNames
            .mapNotNull { name -> dicesInMemoryFallback(beanFactory, name) }
            .filter { it.counts(environment, beanFactory) }
            .map { it.store.simpleName }
            .distinct()
    }

    /** The fallback [name] was made by, when it is one of DICE's own in-memory store methods. */
    private fun dicesInMemoryFallback(beanFactory: ConfigurableListableBeanFactory, name: String): InMemoryFallback? {
        val definition = runCatching { beanFactory.getMergedBeanDefinition(name) }.getOrNull() ?: return null
        val candidates = IN_MEMORY_FALLBACKS.filter { it.method == definition.factoryMethodName }
        if (candidates.isEmpty()) return null
        val factoryBean = definition.factoryBeanName ?: return null
        val factoryType = beanFactory.getType(factoryBean, false) ?: return null
        return candidates.firstOrNull { it.configuration.isAssignableFrom(factoryType) }
    }

    /**
     * One of DICE's in-memory store methods that has a Drivine version, and when its being wired means
     * durable storage was lost. Most always mean that, with two exceptions. The collector trace store
     * doesn't when trace recording is off: its Drivine version is skipped then while the in-memory one
     * is wired anyway, Drivine or not. And the decay manager works on whatever proposition store is
     * there, so when that store is the host's own, the decay manager DICE builds over it is the host's
     * choice too.
     */
    private class InMemoryFallback(
        val configuration: Class<*>,
        val method: String,
        val store: Class<*>,
        val counts: (Environment, ConfigurableListableBeanFactory) -> Boolean = { _, _ -> true },
    )

    private companion object {
        const val COLLECTOR_TRACE_ENABLED = "${DicePropertyPrefixes.DICE}.collector.trace.enabled"

        /**
         * Whether the host defined its own proposition store, meaning a [PropositionRepository] bean
         * that none of DICE's own configuration made. Reads definitions only.
         */
        fun hostPropositionRepository(beanFactory: ConfigurableListableBeanFactory): Boolean =
            BeanFactoryUtils.beanNamesForTypeIncludingAncestors(
                beanFactory, PropositionRepository::class.java, true, false,
            ).any { name -> !madeByDiceStorage(beanFactory, name) }

        private fun madeByDiceStorage(beanFactory: ConfigurableListableBeanFactory, name: String): Boolean {
            val definition = runCatching { beanFactory.getMergedBeanDefinition(name) }.getOrNull() ?: return false
            val factoryBean = definition.factoryBeanName ?: return false
            val factoryType = beanFactory.getType(factoryBean, false) ?: return false
            return DiceStorageAutoConfiguration::class.java.isAssignableFrom(factoryType)
        }

        val IN_MEMORY_FALLBACKS = listOf(
            InMemoryFallback(
                DiceStorageAutoConfiguration::class.java,
                DiceStorageAutoConfiguration::inMemoryPropositionRepository.name,
                PropositionRepository::class.java,
            ),
            InMemoryFallback(
                DiceStorageAutoConfiguration::class.java,
                DiceStorageAutoConfiguration::inMemoryChunkHistoryStore.name,
                ChunkHistoryStore::class.java,
            ),
            InMemoryFallback(
                DiceStorageAutoConfiguration::class.java,
                DiceStorageAutoConfiguration::inMemoryProjectionRecordStore.name,
                ProjectionRecordStore::class.java,
            ),
            InMemoryFallback(
                DiceStorageAutoConfiguration::class.java,
                DiceStorageAutoConfiguration::inMemoryCollectorRecordStore.name,
                CollectorRecordStore::class.java,
            ),
            InMemoryFallback(
                DiceStorageAutoConfiguration::class.java,
                DiceStorageAutoConfiguration::inMemoryDecayManager.name,
                DecayManager::class.java,
            ) { _, beanFactory -> !hostPropositionRepository(beanFactory) },
            InMemoryFallback(
                CollectorAutoConfiguration::class.java,
                CollectorAutoConfiguration::inMemoryCollectorTraceStore.name,
                CollectorTraceStore::class.java,
            ) { environment, _ ->
                // Matches @ConditionalOnProperty with no havingValue: anything but "false" is on.
                !environment.getProperty(COLLECTOR_TRACE_ENABLED, "").trim().equals("false", ignoreCase = true)
            },
            InMemoryFallback(
                MetamodelAutoConfiguration::class.java,
                MetamodelAutoConfiguration::inMemoryMetamodelVersionStore.name,
                MetamodelVersionStore::class.java,
            ),
        )
    }
}

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
import com.embabel.dice.storage.DrivineChunkHistoryStore
import com.embabel.dice.storage.DrivineCollectorRecordStore
import com.embabel.dice.storage.DrivineExtractionRunStore
import com.embabel.dice.storage.DrivinePropositionRepository
import com.embabel.dice.storage.DrivinePropositionRunLinkStore
import com.embabel.dice.storage.DrivineProjectionRecordStore
import com.embabel.dice.storage.ExtractionRunSchema
import com.embabel.dice.storage.GraphDecayManager
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.PersistenceManager
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaCatalog
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.PlatformTransactionManager

/**
 * Auto-configures the Dice proposition store and its lineage record stores (projection records and
 * the collector audit trail).
 *
 * `embabel.dice.store.type=graph` selects the Drivine/Neo4j backend; anything else (default) uses the
 * in-memory backend. Every bean is `@ConditionalOnMissingBean`, so an application's own bean always
 * wins. Graph beans are declared before their in-memory counterparts so the flip resolves by
 * registration order.
 *
 * Schema (indexes/constraints) is declared as [SchemaCatalog] beans; Drivine's `SchemaManager`
 * (registered by the starter) applies them idempotently on startup — no runner here.
 */
@AutoConfiguration
@EnableConfigurationProperties(DiceStoreProperties::class)
class DiceStorageAutoConfiguration {

    private val logger = LoggerFactory.getLogger(DiceStorageAutoConfiguration::class.java)

    // ---- Graph backend (embabel.dice.store.type=graph) ----

    @Bean
    @ConditionalOnBean(Ai::class)
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnMissingBean(PropositionRepository::class)
    fun drivinePropositionRepository(
        graphObjectManager: GraphObjectManager,
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
            graphObjectManager, persistenceManager, embeddingService(ai, embeddingServices), transactionManager,
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
     */
    private fun embeddingService(ai: Ai, embeddingServices: ObjectProvider<EmbeddingService>): EmbeddingService =
        embeddingServices.getIfUnique() ?: ai.withDefaultEmbeddingService()

    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnMissingBean(ChunkHistoryStore::class)
    fun drivineChunkHistoryStore(
        graphObjectManager: GraphObjectManager,
        persistenceManager: PersistenceManager,
    ): ChunkHistoryStore = DrivineChunkHistoryStore(graphObjectManager, persistenceManager)

    @Bean
    @ConditionalOnBean(PropositionRepository::class)
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnMissingBean(DecayManager::class)
    fun graphDecayManager(
        repository: PropositionRepository,
        persistenceManager: PersistenceManager,
    ): DecayManager = GraphDecayManager(repository, persistenceManager)

    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnMissingBean(ProjectionRecordStore::class)
    fun drivineProjectionRecordStore(
        persistenceManager: PersistenceManager,
    ): ProjectionRecordStore = DrivineProjectionRecordStore(persistenceManager)

    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnMissingBean(CollectorRecordStore::class)
    fun drivineCollectorRecordStore(
        persistenceManager: PersistenceManager,
    ): CollectorRecordStore = DrivineCollectorRecordStore(persistenceManager)

    /**
     * The durable extraction-run header store.
     *
     * Two conditions, both required. The graph backend has to be selected
     * (`embabel.dice.store.type=graph`), and extraction runs have to be turned on with
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
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
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
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
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
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnProperty(
        prefix = "embabel.dice.extraction.runs",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = false,
    )
    fun extractionRunSchema(): SchemaCatalog = SchemaCatalog.of(ExtractionRunSchema.specs())

    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    fun lineageRecordSchema(): SchemaCatalog = SchemaCatalog.of(
        // Natural keys back the MERGE upserts: a replayed record updates in place, not duplicates.
        UniquenessConstraintSpec(label = "ProjectionRecord", properties = listOf("propositionId", "runId", "target")),
        UniquenessConstraintSpec(label = "CollectorRecord", properties = listOf("propositionId", "runId")),
        UniquenessConstraintSpec(label = "CollectorRun", property = "runId"),
        RangeIndexSpec("ProjectionRecord", "propositionId"),
        RangeIndexSpec("ProjectionRecord", "lifecycle"),
        RangeIndexSpec("CollectorRecord", "propositionId"),
    )

    @Bean
    @ConditionalOnBean(Ai::class)
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
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
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
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
    internal fun propositionVectorIndexSpec(dimensions: Int): VectorIndexSpec = VectorIndexSpec(
        label = DrivinePropositionRepository.VECTOR_INDEX_LABEL,
        property = DrivinePropositionRepository.VECTOR_INDEX_PROPERTY,
        dimensions = dimensions,
        similarity = SimilarityFunction.COSINE,
        name = DrivinePropositionRepository.VECTOR_INDEX,
    )

    // ---- In-memory backend (default) ----

    @Bean
    @ConditionalOnBean(Ai::class)
    @ConditionalOnMissingBean(PropositionRepository::class)
    fun inMemoryPropositionRepository(ai: Ai): PropositionRepository {
        logger.info("Wiring in-memory proposition store")
        return InMemoryPropositionRepository(ai.withDefaultEmbeddingService())
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

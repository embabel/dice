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
import com.embabel.dice.proposition.store.InMemoryDecayManager
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.storage.DrivineChunkHistoryStore
import com.embabel.dice.storage.DrivineCollectorRecordStore
import com.embabel.dice.storage.DrivinePropositionRepository
import com.embabel.dice.storage.DrivineProjectionRecordStore
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
open class DiceStorageAutoConfiguration {

    private val logger = LoggerFactory.getLogger(DiceStorageAutoConfiguration::class.java)

    // ---- Graph backend (embabel.dice.store.type=graph) ----

    @Bean
    @ConditionalOnBean(Ai::class)
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnMissingBean(PropositionRepository::class)
    open fun drivinePropositionRepository(
        graphObjectManager: GraphObjectManager,
        persistenceManager: PersistenceManager,
        ai: Ai,
        transactionManager: PlatformTransactionManager,
        properties: DiceStoreProperties,
    ): PropositionRepository {
        val vi = properties.vectorIndex
        // Mirror Drivine's derived index name when none is configured, so findClusters' index lookup
        // stays in sync with whatever name the schema registered.
        val indexName = vi.name.ifBlank { "${vi.label}_${vi.property}_vector" }
        return DrivinePropositionRepository(
            graphObjectManager, persistenceManager, ai.withDefaultEmbeddingService(), transactionManager,
            vectorIndexName = indexName,
        )
    }

    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnMissingBean(ChunkHistoryStore::class)
    open fun drivineChunkHistoryStore(
        graphObjectManager: GraphObjectManager,
        persistenceManager: PersistenceManager,
    ): ChunkHistoryStore = DrivineChunkHistoryStore(graphObjectManager, persistenceManager)

    @Bean
    @ConditionalOnBean(PropositionRepository::class)
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnMissingBean(DecayManager::class)
    open fun graphDecayManager(
        repository: PropositionRepository,
        persistenceManager: PersistenceManager,
    ): DecayManager = GraphDecayManager(repository, persistenceManager)

    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnMissingBean(ProjectionRecordStore::class)
    open fun drivineProjectionRecordStore(
        persistenceManager: PersistenceManager,
    ): ProjectionRecordStore = DrivineProjectionRecordStore(persistenceManager)

    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    @ConditionalOnMissingBean(CollectorRecordStore::class)
    open fun drivineCollectorRecordStore(
        persistenceManager: PersistenceManager,
    ): CollectorRecordStore = DrivineCollectorRecordStore(persistenceManager)

    @Bean
    @ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "graph")
    open fun lineageRecordSchema(): SchemaCatalog = SchemaCatalog.of(
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
    open fun propositionConstraintSchema(): SchemaCatalog = SchemaCatalog.of(
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
    open fun propositionVectorIndexSchema(ai: Ai, properties: DiceStoreProperties): SchemaCatalog {
        val embeddingService = ai.withDefaultEmbeddingService()
        val vi = properties.vectorIndex
        val spec = VectorIndexSpec(
            label = vi.label,
            property = vi.property,
            dimensions = embeddingService.dimensions,
            similarity = SimilarityFunction.valueOf(vi.similarityFunction.uppercase()),
            name = vi.name,
        )
        logger.info("Registering proposition vector index schema: {} (model={})", spec, embeddingService.name)
        return SchemaCatalog.of(spec).withVersion(embeddingService.name)
    }

    // ---- In-memory backend (default) ----

    @Bean
    @ConditionalOnBean(Ai::class)
    @ConditionalOnMissingBean(PropositionRepository::class)
    open fun inMemoryPropositionRepository(ai: Ai): PropositionRepository =
        InMemoryPropositionRepository(ai.withDefaultEmbeddingService())

    @Bean
    @ConditionalOnMissingBean(ChunkHistoryStore::class)
    open fun inMemoryChunkHistoryStore(): ChunkHistoryStore = InMemoryChunkHistoryStore()

    @Bean
    @ConditionalOnMissingBean(ProjectionRecordStore::class)
    open fun inMemoryProjectionRecordStore(): ProjectionRecordStore = InMemoryProjectionRecordStore()

    @Bean
    @ConditionalOnMissingBean(CollectorRecordStore::class)
    open fun inMemoryCollectorRecordStore(): CollectorRecordStore = InMemoryCollectorRecordStore()

    @Bean
    @ConditionalOnBean(PropositionRepository::class)
    @ConditionalOnMissingBean(DecayManager::class)
    open fun inMemoryDecayManager(repository: PropositionRepository): DecayManager =
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
open class DiceDecaySchedulingConfiguration(
    private val decayManager: ObjectProvider<DecayManager>,
    private val properties: DiceStoreProperties,
) {
    private val logger = LoggerFactory.getLogger(DiceDecaySchedulingConfiguration::class.java)

    @Scheduled(fixedDelayString = "\${embabel.dice.store.decay.interval-ms:3600000}")
    open fun tick() {
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

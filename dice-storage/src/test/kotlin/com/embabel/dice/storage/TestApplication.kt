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
package com.embabel.dice.storage

import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.PricingModel
import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.manager.GraphObjectManager
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.schema.SchemaCatalog
import org.drivine.schema.SimilarityFunction
import org.drivine.schema.UniquenessConstraintSpec
import org.drivine.schema.VectorIndexSpec
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.transaction.PlatformTransactionManager
import kotlin.random.Random

/**
 * Deterministic [EmbeddingService] for tests: identical text → identical vector (cosine 1.0),
 * distinct text → distinct vector. Enough to exercise vector search, clustering, and ranking
 * mechanics without a real model.
 */
class FakeEmbeddingService(override val dimensions: Int = 16) : EmbeddingService {
    override val name: String = "fake-embedding"
    override val provider: String = "test"
    override val pricingModel: PricingModel? = null

    override fun embed(text: String): FloatArray =
        FloatArray(dimensions) { i -> Random(text.hashCode().toLong() * 1_000_003L + i).nextDouble(-1.0, 1.0).toFloat() }

    override fun embed(texts: List<String>): List<FloatArray> = texts.map(::embed)
}

/**
 * Test wiring: Drivine's test support spins a Neo4j testcontainer and transaction management;
 * we add the graph stores and a fake embedding service. [SchemaCatalog] beans are ensured on
 * startup by Drivine's SchemaManager, so the vector index exists for similarity/clustering.
 */
@Configuration
@EnableDrivine
@EnableDrivineTestConfig
@EnableAspectJAutoProxy(proxyTargetClass = true)
open class TestApplication {

    @Bean
    open fun persistenceManager(factory: PersistenceManagerFactory): PersistenceManager = factory.get("neo")

    @Bean
    open fun graphObjectManager(factory: GraphObjectManagerFactory): GraphObjectManager = factory.get("neo")

    @Bean
    open fun embeddingService(): EmbeddingService = FakeEmbeddingService()

    @Bean
    open fun propositionSchema(embeddingService: EmbeddingService): SchemaCatalog = SchemaCatalog.of(
        VectorIndexSpec("Proposition", "embedding", embeddingService.dimensions, SimilarityFunction.COSINE),
        UniquenessConstraintSpec(label = "Proposition", property = "id"),
        UniquenessConstraintSpec(label = "Mention", property = "id"),
        UniquenessConstraintSpec(label = "ProcessedChunk", property = "id"),
        UniquenessConstraintSpec(label = "Source", property = "key"),
        // Cross-instance dedup backstop: the same fact minted by parallel writers as distinct ids
        // collapses to one node; save() catches the violation and reuses the existing node.
        UniquenessConstraintSpec(label = "Proposition", properties = listOf("contextId", "text")),
    )

    @Bean
    open fun propositionRepository(
        graphObjectManager: GraphObjectManager,
        persistenceManager: PersistenceManager,
        embeddingService: EmbeddingService,
        transactionManager: PlatformTransactionManager,
    ): DrivinePropositionRepository =
        DrivinePropositionRepository(graphObjectManager, persistenceManager, embeddingService, transactionManager)

    @Bean
    open fun chunkHistoryStore(
        graphObjectManager: GraphObjectManager,
        persistenceManager: PersistenceManager,
    ): DrivineChunkHistoryStore = DrivineChunkHistoryStore(graphObjectManager, persistenceManager)

    @Bean
    open fun lineageSchema(): SchemaCatalog = SchemaCatalog.of(
        UniquenessConstraintSpec(label = "ProjectionRecord", properties = listOf("propositionId", "runId", "target")),
        UniquenessConstraintSpec(label = "CollectorRecord", properties = listOf("propositionId", "runId")),
        UniquenessConstraintSpec(label = "CollectorRun", property = "runId"),
    )

    @Bean
    open fun projectionRecordStore(
        persistenceManager: PersistenceManager,
    ): DrivineProjectionRecordStore = DrivineProjectionRecordStore(persistenceManager)

    @Bean
    open fun collectorRecordStore(
        persistenceManager: PersistenceManager,
    ): DrivineCollectorRecordStore = DrivineCollectorRecordStore(persistenceManager)

    @Bean
    open fun decayManager(
        repository: DrivinePropositionRepository,
        persistenceManager: PersistenceManager,
    ): GraphDecayManager = GraphDecayManager(repository, persistenceManager)
}

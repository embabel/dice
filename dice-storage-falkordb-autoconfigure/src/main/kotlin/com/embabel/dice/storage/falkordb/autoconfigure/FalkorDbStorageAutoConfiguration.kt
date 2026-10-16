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
package com.embabel.dice.storage.falkordb.autoconfigure

import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.metamodel.InMemoryMetamodelVersionStore
import com.embabel.dice.metamodel.MetamodelVersionStore
import com.embabel.dice.projection.lineage.CollectorRecordStore
import com.embabel.dice.projection.lineage.InMemoryCollectorRecordStore
import com.embabel.dice.projection.lineage.InMemoryProjectionRecordStore
import com.embabel.dice.projection.lineage.ProjectionRecordStore
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.spi.CollectorTraceStore
import com.embabel.dice.spi.InMemoryCollectorTraceStore
import com.embabel.dice.storage.autoconfigure.DiceStorageAutoConfiguration
import com.embabel.dice.storage.autoconfigure.DiceStoreProperties
import com.embabel.dice.storage.autoconfigure.storeEmbeddingService
import com.embabel.dice.storage.falkordb.FalkorDbChunkHistoryStore
import com.embabel.dice.storage.falkordb.FalkorDbPropositionRepository
import com.embabel.dice.storage.falkordb.FalkorDbSchema
import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivinePropertiesConfig
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.connection.FalkorDbTransactionMode
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.schema.SchemaCatalog
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

/**
 * Wires the FalkorDB proposition and chunk-history stores when `embabel.dice.store.type=falkordb`,
 * ahead of the in-memory fallbacks. An application's own store beans win.
 *
 * The stores use the Drivine datasource named by `embabel.dice.store.falkordb.datasource`. Their
 * schema is registered as a catalog for that datasource, which Drivine's schema manager ensures at
 * startup. Unless the datasource says otherwise, its transaction mode is set to STRICT, so Drivine
 * refuses a caller transaction or a batch FalkorDB could only half apply.
 *
 * `embabel.dice.store.exact-vector.max-candidates` caps the proposition store's filtered vector
 * searches. Its `timeout` has no effect here, since FalkorDB's server `TIMEOUT` is the only deadline.
 */
@AutoConfiguration(before = [DiceStorageAutoConfiguration::class])
@ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "falkordb")
@EnableConfigurationProperties(FalkorDbStoreProperties::class, DiceStoreProperties::class)
// Drivine's starter has no auto-configuration of its own, so the stores' managers come from enabling
// it here. An application that enables Drivine too gets each configuration once, since Spring
// imports a class only once.
@EnableDrivinePropertiesConfig
@EnableDrivine
class FalkorDbStorageAutoConfiguration {

    private val logger = LoggerFactory.getLogger(FalkorDbStorageAutoConfiguration::class.java)

    @Bean
    @ConditionalOnMissingBean(PropositionRepository::class)
    fun falkorDbPropositionRepository(
        registry: DatabaseRegistry,
        sources: DataSourceMap,
        objects: GraphObjectManagerFactory,
        persistence: PersistenceManagerFactory,
        ai: ObjectProvider<Ai>,
        embeddingServices: ObjectProvider<EmbeddingService>,
        properties: FalkorDbStoreProperties,
        store: DiceStoreProperties,
    ): PropositionRepository {
        val name = datasource(registry, sources, properties.datasource)
        return FalkorDbPropositionRepository(
            objects.stateless(name), persistence.get(name), embeddingService(ai, embeddingServices), store.exactVector.toBudget(),
        )
    }

    /** The base stores' rule when the platform's `Ai` is there; without it only an application bean can turn vectors on. */
    private fun embeddingService(ai: ObjectProvider<Ai>, embeddingServices: ObjectProvider<EmbeddingService>): EmbeddingService? {
        ai.ifAvailable?.let { return storeEmbeddingService("FalkorDB", it, embeddingServices) }
        return embeddingServices.ifUnique.also {
            if (it == null) logger.warn("FalkorDB proposition store has no embedding service, so vector search and clustering return nothing")
        }
    }

    @Bean
    @ConditionalOnMissingBean(ChunkHistoryStore::class)
    fun falkorDbChunkHistoryStore(
        registry: DatabaseRegistry,
        sources: DataSourceMap,
        objects: GraphObjectManagerFactory,
        properties: FalkorDbStoreProperties,
    ): ChunkHistoryStore = FalkorDbChunkHistoryStore(objects.stateless(datasource(registry, sources, properties.datasource)))

    /**
     * The stores' constraints and indexes, aimed at their datasource. An application that brings
     * both stores itself may have no FalkorDB datasource at all, and then this is empty; a store this
     * configuration creates reports a bad datasource itself.
     */
    @Bean
    fun falkorDbSchema(
        registry: DatabaseRegistry,
        sources: DataSourceMap,
        properties: FalkorDbStoreProperties,
    ): SchemaCatalog = runCatching { datasource(registry, sources, properties.datasource) }
        .map(FalkorDbSchema::catalog)
        .getOrElse { SchemaCatalog.of(emptyList()) }

    /** Warns at startup about the DICE stores that still keep their records in memory with this backend. */
    @Bean
    fun falkorDbInMemoryStoreWarning(beans: ListableBeanFactory): SmartInitializingSingleton = SmartInitializingSingleton {
        val inMemory = listOfNotNull(
            "projection records".takeIf { beans.getBeansOfType(ProjectionRecordStore::class.java).values.any { it is InMemoryProjectionRecordStore } },
            "collector audit records".takeIf { beans.getBeansOfType(CollectorRecordStore::class.java).values.any { it is InMemoryCollectorRecordStore } },
            "collector traces".takeIf { beans.getBeansOfType(CollectorTraceStore::class.java).values.any { it is InMemoryCollectorTraceStore } },
            "metamodel versions".takeIf { beans.getBeansOfType(MetamodelVersionStore::class.java).values.any { it is InMemoryMetamodelVersionStore } },
        )
        if (inMemory.isNotEmpty()) {
            logger.warn(
                "With embabel.dice.store.type=falkordb these stores keep their records in memory and lose them on restart: {}. Register your own beans to keep them.",
                inMemory.joinToString(),
            )
        }
    }

    private fun datasource(registry: DatabaseRegistry, sources: DataSourceMap, datasource: String): String {
        val name = requireNotNull(registry.resolveDatabaseName(datasource)) {
            "FalkorDB datasource '$datasource' is not registered; configure database.datasources and $DATASOURCE_PROPERTY"
        }
        require(sources.dataSources[name]?.type == DatabaseType.FALKORDB) {
            "FalkorDB datasource '$name' must declare database.datasources.$name.type=FALKORDB"
        }
        require(registry.connectionProvider(name) is FalkorDbConnectionProvider) {
            "FalkorDB datasource '$name' requires a Drivine FalkorDbConnectionProvider"
        }
        return name
    }

    companion object {
        private const val DATASOURCE_PROPERTY = "${FalkorDbStoreProperties.PREFIX}.datasource"

        /**
         * Sets STRICT on the stores' datasource before Drivine builds its connection, unless the
         * application chose a mode. FalkorDB cannot roll back, so a write the stores make is always one
         * statement, and STRICT makes Drivine refuse anything that would quietly not be.
         */
        @Bean
        @JvmStatic
        fun falkorDbStrictTransactions(environment: Environment): BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
                if (bean !is DataSourceMap) return bean
                val configured = Binder.get(environment)
                    .bindOrCreate(FalkorDbStoreProperties.PREFIX, FalkorDbStoreProperties::class.java).datasource
                // "default" is Drivine's name for the first datasource it registers.
                val name = if (configured == "default") bean.dataSources.keys.firstOrNull() else configured
                val properties = name?.let { bean.dataSources[it] } ?: return bean
                if (properties.type != DatabaseType.FALKORDB || properties.falkorDbTransactionMode != null) return bean
                return DataSourceMap(bean.dataSources + (name to properties.copy(falkorDbTransactionMode = FalkorDbTransactionMode.STRICT)))
            }
        }
    }
}

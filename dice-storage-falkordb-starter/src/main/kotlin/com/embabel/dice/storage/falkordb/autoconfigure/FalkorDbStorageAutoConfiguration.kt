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

import com.embabel.common.ai.model.EmbeddingService
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.storage.autoconfigure.DiceStorageAutoConfiguration
import com.embabel.dice.storage.falkordb.FalkorDbChunkHistoryStore
import com.embabel.dice.storage.falkordb.FalkorDbPropositionRepository
import org.drivine.autoconfigure.DrivineConfiguration
import org.drivine.autoconfigure.EnableDrivinePropertiesConfig
import org.drivine.connection.DataSourceMap
import org.drivine.connection.DatabaseRegistry
import org.drivine.connection.DatabaseType
import org.drivine.connection.FalkorDbConnectionProvider
import org.drivine.transaction.TransactionContextHolder
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/**
 * Selects FalkorDB before the portable store fallbacks. Each adapter establishes its fixed
 * identity constraints before its bean is published; no Neo4j schema catalog is registered.
 * The application retains ownership of Drivine providers. Application store beans take precedence.
 */
@AutoConfiguration(before = [DiceStorageAutoConfiguration::class])
@ConditionalOnProperty(prefix = "embabel.dice.store", name = ["type"], havingValue = "falkordb")
@EnableDrivinePropertiesConfig
@Import(DrivineConfiguration::class)
class FalkorDbStorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(PropositionRepository::class)
    fun falkorDbPropositionRepository(
        registry: DatabaseRegistry,
        sources: DataSourceMap,
        transactionContextHolder: TransactionContextHolder,
        embeddingServices: ObjectProvider<EmbeddingService>,
        @Value("\${embabel.dice.store.falkordb.datasource:default}") datasource: String,
    ): PropositionRepository = FalkorDbPropositionRepository(
        provider(registry, sources, datasource), transactionContextHolder, embeddingServices.ifUnique,
    )

    @Bean
    @ConditionalOnMissingBean(ChunkHistoryStore::class)
    fun falkorDbChunkHistoryStore(
        registry: DatabaseRegistry,
        sources: DataSourceMap,
        transactionContextHolder: TransactionContextHolder,
        @Value("\${embabel.dice.store.falkordb.datasource:default}") datasource: String,
    ): ChunkHistoryStore = FalkorDbChunkHistoryStore(provider(registry, sources, datasource), transactionContextHolder)

    private fun provider(registry: DatabaseRegistry, sources: DataSourceMap, datasource: String): FalkorDbConnectionProvider {
        val name = requireNotNull(registry.resolveDatabaseName(datasource)) {
            "FalkorDB datasource '$datasource' is not registered; configure database.datasources and embabel.dice.store.falkordb.datasource"
        }
        require(sources.dataSources[name]?.type == DatabaseType.FALKORDB) {
            "FalkorDB datasource '$name' must declare database.datasources.$name.type=FALKORDB"
        }
        return requireNotNull(registry.connectionProvider(name) as? FalkorDbConnectionProvider) {
            "FalkorDB datasource '$name' requires a Drivine FalkorDbConnectionProvider"
        }
    }
}

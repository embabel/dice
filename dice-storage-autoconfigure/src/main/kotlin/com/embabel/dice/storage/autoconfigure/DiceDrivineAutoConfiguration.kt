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

import org.drivine.autoconfigure.DrivineAutoConfiguration
import org.drivine.connection.DatabaseRegistry
import org.drivine.manager.GraphObjectManagerFactory
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.manager.StatelessGraphObjectManager
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Makes the two Drivine beans DICE's stores need, so a host with Drivine's starter and a datasource
 * doesn't have to write them.
 *
 * Drivine's own auto-configuration builds the factories from `database.datasources` but leaves the
 * `PersistenceManager` and `StatelessGraphObjectManager` to the application. This builds both from
 * those factories, for the only datasource when there is one, or for the one named by
 * `embabel.dice.store.datasource` when there are several. Several with none named, or a name that
 * isn't there, stops startup and lists the ones that are.
 *
 * It runs after Drivine's auto-configuration, so the factories are already registered when it looks
 * for them, and before DICE's storage auto-configuration, which picks the Drivine stores when it
 * finds a `PersistenceManager`. A host that defines either bean itself keeps its own.
 */
@AutoConfiguration(after = [DrivineAutoConfiguration::class], before = [DiceStorageAutoConfiguration::class])
@ConditionalOnClass(PersistenceManagerFactory::class)
@ConditionalOnBean(PersistenceManagerFactory::class, DatabaseRegistry::class)
@EnableConfigurationProperties(DiceDrivineProperties::class)
class DiceDrivineAutoConfiguration {

    private val logger = LoggerFactory.getLogger(DiceDrivineAutoConfiguration::class.java)

    @Bean
    @ConditionalOnMissingBean
    fun persistenceManager(
        factory: PersistenceManagerFactory,
        registry: DatabaseRegistry,
        properties: DiceDrivineProperties,
    ): PersistenceManager {
        val datasource = DrivineDatasource.choose(registry, properties)
        logger.info("DICE's Drivine stores use datasource '{}'", datasource)
        return factory.get(datasource)
    }

    @Bean
    @ConditionalOnBean(GraphObjectManagerFactory::class)
    @ConditionalOnMissingBean
    fun statelessGraphObjectManager(
        factory: GraphObjectManagerFactory,
        registry: DatabaseRegistry,
        properties: DiceDrivineProperties,
    ): StatelessGraphObjectManager = factory.stateless(DrivineDatasource.choose(registry, properties))
}

/** Picks the datasource DICE's Drivine beans are built for, out of the ones Drivine has registered. */
internal object DrivineDatasource {

    const val PROPERTY = "${DicePropertyPrefixes.STORE}.datasource"

    fun choose(registry: DatabaseRegistry, properties: DiceDrivineProperties): String {
        val configured = registry.databaseNames
        val named = properties.datasource?.trim()?.takeIf { it.isNotEmpty() }
        if (named != null) {
            // Drivine's own "default" alias, meaning the first datasource, resolves too.
            return registry.resolveDatabaseName(named) ?: error(
                "$PROPERTY names '$named', but Drivine has no datasource by that name. " +
                    "Configured datasources: ${describe(configured)}.",
            )
        }
        return when (configured.size) {
            1 -> configured.single()
            0 -> error(
                "Drivine has no datasource registered, so DICE can't build its PersistenceManager and " +
                    "StatelessGraphObjectManager. Add one under database.datasources, or define those two beans yourself.",
            )
            else -> error(
                "Drivine has several datasources (${describe(configured)}) and DICE can't tell which one to keep " +
                    "its stores in. Set $PROPERTY to one of them, or define your own PersistenceManager and " +
                    "StatelessGraphObjectManager beans.",
            )
        }
    }

    private fun describe(names: Set<String>): String =
        if (names.isEmpty()) "none" else names.joinToString { "'$it'" }
}

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
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionMessage
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.type.AnnotatedTypeMetadata

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
 * The two always share a database: DICE saves through one and deduplicates, deletes and decays
 * through the other, so on two databases its reads and writes would drift apart. A host that defines only its own
 * `PersistenceManager` gets a `StatelessGraphObjectManager` on that manager's datasource, and the
 * property can't move it elsewhere: naming a different one stops startup. A host that defines only
 * a `StatelessGraphObjectManager` gets a `PersistenceManager` when there is just one datasource; with
 * several, Drivine doesn't say which one that manager uses, so startup stops and asks for both.
 *
 * It runs after Drivine's auto-configuration, so the factories are already registered when it looks
 * for them, and before DICE's storage auto-configuration, which picks the Drivine stores when it
 * finds a `PersistenceManager`. A host that defines either bean itself keeps its own. With
 * `embabel.dice.store.type=in-memory` it does nothing, since DICE won't use Drivine at all.
 */
@AutoConfiguration(after = [DrivineAutoConfiguration::class], before = [DiceStorageAutoConfiguration::class])
@ConditionalOnClass(PersistenceManagerFactory::class)
@ConditionalOnBean(PersistenceManagerFactory::class, DatabaseRegistry::class)
@Conditional(NotForcedInMemory::class)
@EnableConfigurationProperties(DiceDrivineProperties::class)
class DiceDrivineAutoConfiguration {

    private val logger = LoggerFactory.getLogger(DiceDrivineAutoConfiguration::class.java)

    // A field rather than a bean-method parameter, so the published method signatures stay as they are.
    @Autowired
    private lateinit var beanFactory: ConfigurableListableBeanFactory

    @Bean
    @ConditionalOnMissingBean
    fun persistenceManager(
        factory: PersistenceManagerFactory,
        registry: DatabaseRegistry,
        properties: DiceDrivineProperties,
    ): PersistenceManager {
        val hostGraphObjectManagers = hostGraphObjectManagers()
        check(hostGraphObjectManagers.isEmpty() || registry.databaseNames.size <= 1) {
            "Your StatelessGraphObjectManager (${describe(hostGraphObjectManagers.toSet())}) has no PersistenceManager " +
                "beside it, and with several Drivine datasources (${describe(registry.databaseNames)}) DICE can't tell " +
                "which one it uses. DICE needs both on the same database, so define both beans yourself."
        }
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
    ): StatelessGraphObjectManager {
        // Whichever PersistenceManager the stores get, the host's or the one above, decides the database.
        val manager = beanFactory.getBeanProvider(PersistenceManager::class.java).getIfUnique()
            ?: error(
                "There are several PersistenceManager beans and none is primary, so DICE can't tell which database " +
                    "to build its StatelessGraphObjectManager for. Define that bean yourself, on the same database.",
            )
        val datasource = registry.resolveDatabaseName(manager.database) ?: error(
            "Your PersistenceManager uses database '${manager.database}', which isn't one of Drivine's datasources " +
                "(${describe(registry.databaseNames)}), so DICE can't build a StatelessGraphObjectManager beside it. " +
                "Define both beans yourself, on the same database.",
        )
        val named = DrivineDatasource.named(properties)
        if (named != null) {
            check(registry.resolveDatabaseName(named) == datasource) {
                "${DrivineDatasource.PROPERTY} names '$named', but your PersistenceManager uses '$datasource'. DICE keeps " +
                    "its stores on one database and won't move them off your PersistenceManager's: remove the property " +
                    "or make the two agree."
            }
        }
        return factory.stateless(datasource)
    }

    /** The application's own StatelessGraphObjectManager beans, leaving out the one this class may register. */
    private fun hostGraphObjectManagers(): List<String> =
        beanFactory.getBeanNamesForType(StatelessGraphObjectManager::class.java, true, false).filterNot { name ->
            beanFactory.containsBeanDefinition(name) &&
                (beanFactory.getBeanDefinition(name) as? AnnotatedBeanDefinition)
                    ?.factoryMethodMetadata?.declaringClassName == DiceDrivineAutoConfiguration::class.java.name
        }
}

/**
 * Keeps DICE's Drivine beans out when `embabel.dice.store.type=in-memory` forces the in-memory stores,
 * so several datasources with none named don't stop a host that isn't using them.
 */
internal class NotForcedInMemory : SpringBootCondition() {

    override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
        val message = ConditionMessage.forCondition("DICE Drivine beans")
        return if (StoreBackend.requested(context.environment) == StoreBackend.IN_MEMORY) {
            ConditionOutcome.noMatch(
                message.because("${StoreBackend.TYPE_PROPERTY}=${StoreBackend.IN_MEMORY} forces the in-memory stores"),
            )
        } else {
            ConditionOutcome.match(message.because("the in-memory stores aren't forced"))
        }
    }
}

/** Picks the datasource DICE's Drivine beans are built for, out of the ones Drivine has registered. */
internal object DrivineDatasource {

    const val PROPERTY = "${DicePropertyPrefixes.STORE}.datasource"

    fun choose(registry: DatabaseRegistry, properties: DiceDrivineProperties): String {
        val configured = registry.databaseNames
        val named = named(properties)
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

    /** The datasource the property names, or null when it is unset or blank. */
    fun named(properties: DiceDrivineProperties): String? = properties.datasource?.trim()?.takeIf { it.isNotEmpty() }
}

private fun describe(names: Set<String>): String =
    if (names.isEmpty()) "none" else names.joinToString { "'$it'" }

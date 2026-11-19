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
import org.drivine.schema.SchemaCatalog
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.BeanCreationException
import org.springframework.beans.factory.BeanCurrentlyInCreationException
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.config.BeanPostProcessor
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
import org.springframework.context.annotation.Import
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
 * The two always share a database, under the same name: DICE saves through one and deduplicates,
 * deletes and decays through the other, and Drivine gives a transaction one connection per name it is
 * asked for, so "default" and the datasource it stands for would be two connections that neither see
 * each other's writes nor commit together. A host that defines only its own `PersistenceManager` gets a
 * `StatelessGraphObjectManager` under exactly the name the host used, and the property can't move it
 * elsewhere: naming a different datasource stops startup. A host that defines only a
 * `StatelessGraphObjectManager` gets a `PersistenceManager` under that manager's name when there is just
 * one datasource; with several, startup stops and asks for both. When the host has several of either
 * manager, the primary one is the one that counts, since that is the one Spring injects; several with
 * none primary stops startup and names them.
 *
 * With several datasources, DICE's schema (its constraints and indexes) goes only to the one its
 * stores use, rather than to every datasource Drivine knows. With one, it is left as declared.
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
@Import(DiceSchemaTargeting::class)
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
        // The host StatelessGraphObjectManager Spring injects, when it is on this datasource, decides the
        // name, so both join one connection.
        val key = injectedHostGraphObjectManagerKey(hostGraphObjectManagers)
            ?.takeIf { registry.resolveDatabaseName(it) == datasource }
            ?: datasource
        logger.info("DICE's Drivine stores use datasource '{}'", key)
        return factory.get(key)
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
        // The one Spring injects: the only one, or the primary among several.
        val manager = beanFactory.getBeanProvider(PersistenceManager::class.java).getIfUnique()
            ?: error(
                "There are several PersistenceManager beans " +
                    "(${describe(beanFactory.getBeanNamesForType(PersistenceManager::class.java, true, false).toSet())}) " +
                    "and none is primary, so DICE can't tell which database to build its StatelessGraphObjectManager " +
                    "for. Mark one @Primary, or define both beans yourself, on the same database.",
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
        // The name exactly as the PersistenceManager was given it, not the datasource it resolves to:
        // Drivine opens a connection per name within a transaction.
        return factory.stateless(manager.database)
    }

    /**
     * The name the host StatelessGraphObjectManager Spring injects was built for: the only one, or the
     * primary among several. Null when the host has none, or when that manager is itself built on the
     * PersistenceManager being made here, in which case the two already share the name. Several with
     * none primary stops startup, since DICE's stores would get one DICE can't predict.
     */
    private fun injectedHostGraphObjectManagerKey(hostBeanNames: List<String>): String? {
        if (hostBeanNames.isEmpty()) return null
        // Spring's own choice, which only creates the manager it picks.
        val injected = try {
            beanFactory.getBeanProvider(StatelessGraphObjectManager::class.java).getIfUnique()
        } catch (e: BeanCreationException) {
            if (e.contains(BeanCurrentlyInCreationException::class.java)) return null else throw e
        }
        checkNotNull(injected) {
            "There are several StatelessGraphObjectManager beans (${describe(hostBeanNames.toSet())}) and none is " +
                "primary, so DICE can't tell which one its PersistenceManager must share a database name with. " +
                "Mark one @Primary, or define both beans yourself, on the same database."
        }
        return injected.database
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
 * Points DICE's schema catalogs at the one datasource its stores use, when Drivine has several.
 *
 * A catalog goes to every datasource unless told otherwise, so with a second database configured,
 * DICE's constraints and indexes would land there too. This narrows each catalog one of DICE's
 * auto-configurations declares to the datasource behind the context's PersistenceManager, the one
 * DICE's stores are built on. The host's own catalogs are left alone, and so is everything when there
 * is just one datasource.
 */
internal class DiceSchemaTargeting : BeanPostProcessor, BeanFactoryAware {

    private val logger = LoggerFactory.getLogger(DiceSchemaTargeting::class.java)

    private lateinit var beanFactory: ConfigurableListableBeanFactory

    override fun setBeanFactory(beanFactory: BeanFactory) {
        this.beanFactory = beanFactory as ConfigurableListableBeanFactory
    }

    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        if (bean !is SchemaCatalog || !declaredByDice(beanName)) return bean
        val registry = beanFactory.getBeanProvider(DatabaseRegistry::class.java).getIfAvailable() ?: return bean
        if (registry.databaseNames.size <= 1) return bean
        val datasource = diceDatasource(registry)
        if (datasource == null) {
            logger.warn(
                "Can't tell which of Drivine's datasources ({}) DICE's stores use, so schema catalog '{}' still goes to all of them",
                describe(registry.databaseNames), beanName,
            )
            return bean
        }
        // The resolved name, not "default": Drivine matches a catalog's target against registered names.
        return bean.forDatabase(datasource)
    }

    private fun declaredByDice(beanName: String): Boolean =
        beanFactory.containsBeanDefinition(beanName) &&
            (beanFactory.getBeanDefinition(beanName) as? AnnotatedBeanDefinition)
                ?.factoryMethodMetadata?.declaringClassName in DICE_SCHEMA_SOURCES

    private companion object {
        /** The auto-configurations whose SchemaCatalog beans are DICE's own schema. */
        val DICE_SCHEMA_SOURCES = setOf(
            DiceStorageAutoConfiguration::class.java.name,
            CollectorAutoConfiguration::class.java.name,
            MetamodelAutoConfiguration::class.java.name,
        )
    }

    /** The registered datasource behind the PersistenceManager DICE's stores get, or null when that can't be told. */
    private fun diceDatasource(registry: DatabaseRegistry): String? {
        val manager = beanFactory.getBeanProvider(PersistenceManager::class.java).getIfUnique() ?: return null
        // A PersistenceManager that isn't Drivine's own may not have a name at all.
        val key: String? = manager.database
        return key?.let(registry::resolveDatabaseName)
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

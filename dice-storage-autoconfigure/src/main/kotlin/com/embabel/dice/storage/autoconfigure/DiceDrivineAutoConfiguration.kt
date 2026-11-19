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
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.BeanFactoryUtils
import org.springframework.beans.factory.NoUniqueBeanDefinitionException
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.config.DependencyDescriptor
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionMessage
import org.springframework.boot.autoconfigure.condition.ConditionOutcome
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.SpringBootCondition
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
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
 * DICE builds the two only as a pair, and only when the host defines neither, parent contexts included.
 * DICE saves through one and deduplicates, deletes and decays through the other, so they have to share a
 * database and a transaction, and a manager built to sit beside the host's can always end up somewhere
 * the host's isn't. So a host that defines both keeps them and DICE leaves them alone, and a host that
 * defines just one stops startup with a message asking for both or neither. This is the same way Spring
 * Boot steps aside from a whole persistence setup when the application brings its own.
 *
 * With several datasources, DICE's schema (its constraints and indexes) goes only to the one its
 * stores use, rather than to every datasource Drivine knows. With one, it is left as declared.
 *
 * It runs after Drivine's auto-configuration, so the factories are already registered when it looks
 * for them, and before DICE's storage auto-configuration, which picks the Drivine stores when it
 * finds a `PersistenceManager`. With `embabel.dice.store.type=in-memory` it does nothing, since DICE
 * won't use Drivine at all.
 */
@AutoConfiguration(after = [DrivineAutoConfiguration::class], before = [DiceStorageAutoConfiguration::class])
@ConditionalOnClass(PersistenceManagerFactory::class)
@ConditionalOnBean(PersistenceManagerFactory::class, GraphObjectManagerFactory::class, DatabaseRegistry::class)
@Conditional(NotForcedInMemory::class)
@EnableConfigurationProperties(DiceDrivineProperties::class)
@Import(DiceSchemaTargeting::class)
class DiceDrivineAutoConfiguration {

    private val logger = LoggerFactory.getLogger(DiceDrivineAutoConfiguration::class.java)

    @Bean
    @Conditional(HostDefinesNeitherManager::class)
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
    @Conditional(HostDefinesNeitherManager::class)
    fun statelessGraphObjectManager(
        factory: GraphObjectManagerFactory,
        registry: DatabaseRegistry,
        properties: DiceDrivineProperties,
    ): StatelessGraphObjectManager =
        // The same resolved name as the PersistenceManager above: Drivine opens one connection per name in a
        // transaction, so the two only share a transaction when they were asked for exactly the same name.
        factory.stateless(DrivineDatasource.choose(registry, properties))
}

/**
 * Lets DICE build its Drivine managers only when the host defines neither of them, and stops startup when
 * the host defines just one.
 *
 * Both present means the host has its own setup, so DICE builds nothing and doesn't look at them. Just one
 * would leave DICE building the other beside it, which is how saves and dedup end up on two databases, so
 * that is a startup error. Beans in parent contexts count, as they do for injection; DICE's own two
 * managers, declared in this context, don't.
 */
internal class HostDefinesNeitherManager : SpringBootCondition() {

    override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
        val message = ConditionMessage.forCondition("DICE Drivine managers")
        val beanFactory = context.beanFactory
            ?: return ConditionOutcome.noMatch(message.because("there is no bean factory to look in"))
        val persistenceManagers = hostBeans(beanFactory, PersistenceManager::class.java)
        val graphObjectManagers = hostBeans(beanFactory, StatelessGraphObjectManager::class.java)
        return when {
            persistenceManagers.isEmpty() && graphObjectManagers.isEmpty() ->
                ConditionOutcome.match(message.because("the application defines neither manager"))
            persistenceManagers.isNotEmpty() && graphObjectManagers.isNotEmpty() ->
                ConditionOutcome.noMatch(message.because("the application defines both managers"))
            else -> error(onlyOne(persistenceManagers, graphObjectManagers))
        }
    }

    private fun onlyOne(persistenceManagers: Set<String>, graphObjectManagers: Set<String>): String {
        val (defined, missing, names) = if (persistenceManagers.isNotEmpty()) {
            Triple("PersistenceManager", "StatelessGraphObjectManager", persistenceManagers)
        } else {
            Triple("StatelessGraphObjectManager", "PersistenceManager", graphObjectManagers)
        }
        return "Your application defines a $defined (${describe(names)}) but no $missing. DICE builds these two " +
            "Drivine managers only as a pair, so its stores never save through one database and deduplicate " +
            "through another. Define both beans yourself, on the same database key, or neither and let DICE " +
            "build them from database.datasources."
    }

    /** The application's beans of [type], parent contexts included, leaving out the ones DICE declares here. */
    private fun hostBeans(beanFactory: ConfigurableListableBeanFactory, type: Class<*>): Set<String> =
        beanNamesIncludingAncestors(beanFactory, type).filterNot { name ->
            beanFactory.containsBeanDefinition(name) &&
                (beanFactory.getBeanDefinition(name) as? AnnotatedBeanDefinition)
                    ?.factoryMethodMetadata?.declaringClassName == DiceDrivineAutoConfiguration::class.java.name
        }.toSet()
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
        val manager = injectedBean(beanFactory, PersistenceManager::class.java) ?: return null
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

/**
 * The bean of [type] Spring would inject: the only one, or the primary among several, counting beans in
 * parent contexts the way injection does. Null when there is none, or several and Spring can't pick one.
 *
 * Spring's own `getBeanProvider(type).getIfUnique()` looks at this context first and only asks the parent
 * when this one has none, so a plain local bean would hide a primary one in the parent that injection picks.
 * This asks the injection machinery instead, which also creates only the bean it picks.
 */
private fun <T : Any> injectedBean(beanFactory: ConfigurableListableBeanFactory, type: Class<T>): T? {
    val point = ManagerInjectionPoints::class.java.declaredFields.single { it.type == type }
    // Optional and not eager, so nothing is created just to learn its type. No name either, so a bean that
    // happens to share the stand-in field's name isn't preferred, as a named injection point would be.
    val descriptor = object : DependencyDescriptor(point, false, false) {
        override fun getDependencyName(): String? = null
    }
    return try {
        type.cast(beanFactory.resolveDependency(descriptor, null))
    } catch (e: NoUniqueBeanDefinitionException) {
        null
    }
}

/** Every bean name of [type] in this context and its parents, without creating anything to find them. */
private fun beanNamesIncludingAncestors(beanFactory: ConfigurableListableBeanFactory, type: Class<*>): List<String> =
    BeanFactoryUtils.beanNamesForTypeIncludingAncestors(beanFactory, type, true, false).toList()

/** Stand-in injection points, so DICE can ask Spring which manager it would inject without injecting one. */
@Suppress("unused")
private class ManagerInjectionPoints {
    @JvmField var persistenceManager: PersistenceManager? = null
}

private fun describe(names: Set<String>): String =
    if (names.isEmpty()) "none" else names.joinToString { "'$it'" }

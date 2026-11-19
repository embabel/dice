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
import org.springframework.beans.factory.BeanDefinitionStoreException
import org.springframework.beans.factory.BeanFactoryUtils
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.beans.factory.NoUniqueBeanDefinitionException
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.config.DependencyDescriptor
import org.springframework.beans.factory.support.AbstractBeanDefinition
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.beans.factory.support.RootBeanDefinition
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
import org.springframework.core.env.Environment
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
 * That decision waits until every bean is registered, so it sees managers from a host auto-configuration
 * that happens to be read after this one, and that never collides with them.
 *
 * With several datasources, DICE's schema (its constraints and indexes) goes only to the one its
 * stores use, rather than to every datasource Drivine knows. With one, it is left as declared.
 *
 * It runs after Drivine's auto-configuration, so the factories are already registered when it looks
 * for them, and before DICE's storage auto-configuration, which picks the Drivine stores when it
 * finds a `PersistenceManager` or sees that DICE is going to decide about one.
 *
 * It only runs for apps that added `dice-starter-neo4j`. An app that set up Drivine for its own use and
 * never added the starter gets nothing from here (no managers, no schema targeting), whatever
 * `embabel.dice.store.type` says. With `embabel.dice.store.type=in-memory` it does nothing either, since
 * DICE won't use Drivine at all.
 */
@AutoConfiguration(after = [DrivineAutoConfiguration::class], before = [DiceStorageAutoConfiguration::class])
@ConditionalOnClass(PersistenceManagerFactory::class)
@ConditionalOnBean(PersistenceManagerFactory::class, GraphObjectManagerFactory::class, DatabaseRegistry::class)
@Conditional(OptedIntoDiceDrivine::class)
@EnableConfigurationProperties(DiceDrivineProperties::class)
@Import(DiceSchemaTargeting::class)
class DiceDrivineAutoConfiguration {

    private val logger = LoggerFactory.getLogger(DiceDrivineAutoConfiguration::class.java)

    /**
     * DICE's PersistenceManager, for the chosen datasource. It becomes a bean only when the host defines
     * neither manager; [DiceDrivineManagerRegistrar] decides that and registers this method as its factory.
     */
    fun persistenceManager(
        factory: PersistenceManagerFactory,
        registry: DatabaseRegistry,
        properties: DiceDrivineProperties,
    ): PersistenceManager {
        val datasource = DrivineDatasource.choose(registry, properties)
        logger.info("DICE's Drivine stores use datasource '{}'", datasource)
        return factory.get(datasource)
    }

    /** DICE's StatelessGraphObjectManager, made only alongside [persistenceManager]. */
    fun statelessGraphObjectManager(
        factory: GraphObjectManagerFactory,
        registry: DatabaseRegistry,
        properties: DiceDrivineProperties,
    ): StatelessGraphObjectManager =
        // The same resolved name as the PersistenceManager above: Drivine opens one connection per name in a
        // transaction, so the two only share a transaction when they were asked for exactly the same name.
        factory.stateless(DrivineDatasource.choose(registry, properties))

    private companion object {

        // Static, so Spring can run it before anything else here is created, which a registry post-processor needs.
        @JvmStatic
        @Bean(DiceDrivineManagerRegistrar.BEAN_NAME)
        private fun diceDrivineManagerRegistrar(environment: Environment): DiceDrivineManagerRegistrar =
            DiceDrivineManagerRegistrar(environment)
    }
}

/**
 * Registers DICE's two Drivine managers when the host defines neither of them, and stops startup when the
 * host defines just one.
 *
 * It runs once every bean definition is in, auto-configurations read after DICE's included, so a host's
 * managers are seen whatever order their auto-configuration has. Both present means the host has its own
 * setup, so DICE registers nothing and doesn't look at them. Just one would leave DICE building the other
 * beside it, which is how saves and dedup end up on two databases, so that is a startup error. Beans in
 * parent contexts count, as they do for injection. Only definitions are read; nothing is created to answer.
 *
 * The one exception is an explicit `embabel.dice.store.type=graph`. Such an app may have started with one
 * manager before DICE built any, by replacing the stores that need the other, so DICE builds nothing and
 * lets injection succeed or fail exactly as it used to.
 */
internal class DiceDrivineManagerRegistrar(private val environment: Environment) : BeanDefinitionRegistryPostProcessor {

    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        val beanFactory = registry as? ListableBeanFactory
            ?: error("DICE can't look for Drivine managers in a ${registry.javaClass.name}, which can't list its beans.")
        val persistenceManagers = beanNamesIncludingAncestors(beanFactory, PersistenceManager::class.java).toSet()
        val graphObjectManagers = beanNamesIncludingAncestors(beanFactory, StatelessGraphObjectManager::class.java).toSet()
        when {
            persistenceManagers.isEmpty() && graphObjectManagers.isEmpty() -> registerDiceManagers(registry, beanFactory)
            persistenceManagers.isNotEmpty() && graphObjectManagers.isNotEmpty() -> Unit
            StoreBackend.requested(environment) == StoreBackend.GRAPH -> Unit
            // Wrapped like any other failure Spring reports at startup, with the reason as the root cause.
            else -> throw BeanDefinitionStoreException(
                "DICE can't decide about its Drivine managers",
                IllegalStateException(onlyOne(persistenceManagers, graphObjectManagers)),
            )
        }
    }

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) = Unit

    /** Registers the two managers as DiceDrivineAutoConfiguration's methods, as if they were declared `@Bean`. */
    private fun registerDiceManagers(registry: BeanDefinitionRegistry, beanFactory: ListableBeanFactory) {
        val configuration = beanFactory
            .getBeanNamesForType(DiceDrivineAutoConfiguration::class.java, true, false)
            .singleOrNull()
            ?: error("DICE's Drivine auto-configuration isn't registered exactly once, so DICE can't build its managers.")
        registry.registerBeanDefinition(
            PERSISTENCE_MANAGER,
            managerDefinition(configuration, PERSISTENCE_MANAGER, PersistenceManager::class.java),
        )
        registry.registerBeanDefinition(
            GRAPH_OBJECT_MANAGER,
            managerDefinition(configuration, GRAPH_OBJECT_MANAGER, StatelessGraphObjectManager::class.java),
        )
    }

    private fun managerDefinition(configuration: String, method: String, type: Class<*>) =
        RootBeanDefinition().apply {
            factoryBeanName = configuration
            setUniqueFactoryMethodName(method)
            // The method's parameters are injected, as they are for any @Bean method.
            autowireMode = AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR
            setTargetType(type)
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

    companion object {
        /** The registrar's bean name, which DICE's storage conditions look for. */
        const val BEAN_NAME = "diceDrivineManagerRegistrar"

        // The names DICE's managers had when they were @Bean methods, which hosts may refer to.
        private const val PERSISTENCE_MANAGER = "persistenceManager"
        private const val GRAPH_OBJECT_MANAGER = "statelessGraphObjectManager"

        /**
         * Whether DICE will decide about its Drivine managers in [beanFactory] once every bean is registered.
         * When it will, a PersistenceManager exists by the time beans are created, or startup has stopped.
         */
        fun declaredIn(beanFactory: ConfigurableListableBeanFactory): Boolean =
            beanFactory.containsBeanDefinition(BEAN_NAME)
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
        val manager = injectedBean(beanFactory, PersistenceManager::class.java) ?: return null
        // A PersistenceManager that isn't Drivine's own may not have a name at all.
        val key: String? = manager.database
        return key?.let(registry::resolveDatabaseName)
    }
}

/**
 * Lets DICE's Drivine beans in only for apps that added `dice-starter-neo4j` and haven't forced the
 * in-memory stores. Without the starter, an app's own Drivine setup is left exactly as it was; with
 * `embabel.dice.store.type=in-memory`, several datasources with none named don't stop a host that isn't
 * using them.
 */
internal class OptedIntoDiceDrivine : SpringBootCondition() {

    override fun getMatchOutcome(context: ConditionContext, metadata: AnnotatedTypeMetadata): ConditionOutcome {
        val message = ConditionMessage.forCondition("DICE Drivine beans")
        return when {
            StoreBackend.requested(context.environment) == StoreBackend.IN_MEMORY -> ConditionOutcome.noMatch(
                message.because("${StoreBackend.TYPE_PROPERTY}=${StoreBackend.IN_MEMORY} forces the in-memory stores"),
            )
            !StoreBackend.optedIn(context.classLoader) -> ConditionOutcome.noMatch(
                message.didNotFind("dice-starter-neo4j marker").items(StoreBackend.STARTER_MARKER),
            )
            else -> ConditionOutcome.match(
                message.found("dice-starter-neo4j marker").items(StoreBackend.STARTER_MARKER),
            )
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
 * The bean of [type] Spring would inject into DICE's stores: the only one, the primary among several, or
 * the one named like the stores' parameter, counting beans in parent contexts the way injection does. Null when there is none, or several and Spring can't pick one.
 *
 * Spring's own `getBeanProvider(type).getIfUnique()` looks at this context first and only asks the parent
 * when this one has none, so a plain local bean would hide a primary one in the parent that injection picks.
 * This asks the injection machinery instead, which also creates only the bean it picks.
 */
private fun <T : Any> injectedBean(beanFactory: ConfigurableListableBeanFactory, type: Class<T>): T? {
    val point = ManagerInjectionPoints::class.java.declaredFields.single { it.type == type }
    // Optional and not eager, so nothing is created just to learn its type. The stand-in field is named like
    // the stores' own parameters on purpose: with several beans and no primary, Spring falls back to the one
    // whose name matches, and this has to pick the same bean the stores get.
    val descriptor = DependencyDescriptor(point, false, false)
    return try {
        type.cast(beanFactory.resolveDependency(descriptor, null))
    } catch (e: NoUniqueBeanDefinitionException) {
        null
    }
}

/** Every bean name of [type] in this context and its parents, without creating anything to find them. */
private fun beanNamesIncludingAncestors(beanFactory: ListableBeanFactory, type: Class<*>): List<String> =
    BeanFactoryUtils.beanNamesForTypeIncludingAncestors(beanFactory, type, true, false).toList()

/**
 * Stand-in injection points, so DICE can ask Spring which manager it would inject without injecting one.
 * Each field is named exactly like the parameter DICE's stores declare; keep the two names in step.
 */
@Suppress("unused")
private class ManagerInjectionPoints {
    @JvmField var persistenceManager: PersistenceManager? = null
}

private fun describe(names: Set<String>): String =
    if (names.isEmpty()) "none" else names.joinToString { "'$it'" }

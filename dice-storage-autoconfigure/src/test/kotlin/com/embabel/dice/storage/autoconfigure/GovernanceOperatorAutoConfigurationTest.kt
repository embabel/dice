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

import com.embabel.dice.agent.GovernanceTools
import com.embabel.dice.governance.GovernanceOperationsService
import com.embabel.dice.metamodel.DeclaredSchemaSource
import com.embabel.dice.metamodel.DriftCheckRunner
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.web.rest.DiceRestConfiguration
import com.embabel.dice.web.rest.GovernanceController
import org.assertj.core.api.Assertions.assertThat
import org.drivine.manager.PersistenceManager
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * Wiring tests for the governance operator surface: the service from [MetamodelAutoConfiguration],
 * and the REST controller that arrives with a host's `@Import(DiceRestConfiguration)`.
 *
 * Two host decisions have to line up before an operator can reach governance over HTTP — import the
 * DICE REST surface, and wire the governance loop — and these tests pin all four combinations. The
 * agent tools are here too, for the opposite reason: no context may hold a `GovernanceTools` bean,
 * because a host builds those itself.
 */
class GovernanceOperatorAutoConfigurationTest {

    private val autoConfigurations = AutoConfigurations.of(MetamodelAutoConfiguration::class.java)

    /** A host with the whole loop in memory and no REST import at all. */
    private val runner = ApplicationContextRunner()
        .withConfiguration(autoConfigurations)
        .withUserConfiguration(InMemoryGovernanceConfig::class.java)

    /** The same loop in a servlet web application that imported the DICE REST surface. */
    private val webRunner = WebApplicationContextRunner()
        .withConfiguration(autoConfigurations)
        .withUserConfiguration(RestImportingGovernanceConfig::class.java, EndpointMappingConfig::class.java)

    // ---- Import plus a wired loop ----

    /**
     * The happy path, pinned as URLs. A host that imported [DiceRestConfiguration] and declared a
     * schema gets exactly these six routes, which is the surface an operator is offered and the
     * surface a consumer's endpoint snapshot records.
     */
    @Test
    fun `importing DICE REST with the loop wired opens all six routes`() {
        webRunner.run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(GovernanceOperationsService::class.java)
            assertThat(ctx).hasSingleBean(GovernanceController::class.java)
            assertThat(metamodelEndpoints(ctx)).containsExactlyInAnyOrder(*ALL_SIX_ROUTES)
        }
    }

    /**
     * The condition is asked late enough to see a bean the auto-configuration contributed.
     *
     * `@ConditionalOnBean` on an imported class is answered while the importing configuration class
     * is read, and Spring Boot registers auto-configuration bean definitions after that. So a plain
     * entry in `DiceRestConfiguration`'s `@Import` list would answer "no service" for every host
     * whose loop came from `MetamodelAutoConfiguration` — which is every host that follows the
     * documented wiring. `GovernanceControllerImport` defers the import to the end of the round,
     * and this test is what would catch that deferral being dropped: the service below exists only
     * because the auto-configuration built it.
     */
    @Test
    fun `the controller sees a service the auto-configuration contributed`() {
        webRunner.run { ctx ->
            assertThat(ctx.getBeanDefinitionNames()).contains("governanceOperationsService")
            assertThat(ctx).hasSingleBean(GovernanceController::class.java)
        }
    }

    /**
     * The service is useful to a host with no HTTP at all, and the tools run anywhere, so only the
     * controller depends on a servlet web application and on the REST import.
     */
    @Test
    fun `outside a web application there is a service and no controller`() {
        runner.run { ctx ->
            assertThat(ctx).hasSingleBean(GovernanceOperationsService::class.java)
            assertThat(ctx).doesNotHaveBean(GovernanceController::class.java)
        }
    }

    // ---- Import without a wired loop ----

    /**
     * A host that imported the DICE REST surface and declared no schema. It must resolve zero
     * `/api/v1/metamodel` URLs and start cleanly — the other DICE controllers it asked for are
     * unaffected.
     *
     * The endpoint assertion is the load-bearing one. A `doesNotHaveBean(GovernanceController)`
     * check answers a narrower question — whether one bean type is on the context — and stays green
     * while a route reaches a handler by some path this test never named.
     */
    @Test
    fun `importing DICE REST with no declared schema opens no route and starts cleanly`() {
        WebApplicationContextRunner()
            .withConfiguration(autoConfigurations)
            .withPropertyValues(GRAPH_BACKEND)
            .withUserConfiguration(RestImportingNoSchemaConfig::class.java, EndpointMappingConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(metamodelEndpoints(ctx)).isEmpty()
                assertThat(ctx).doesNotHaveBean(GovernanceOperationsService::class.java)
                assertThat(ctx).doesNotHaveBean(GovernanceController::class.java)
            }
    }

    /**
     * A declared schema on its own is short of a service: the in-memory backend has no drift log and
     * nothing to observe, so there is no runner and no operator service. The import is in place and
     * the routes still have to stay shut, with a context that starts.
     *
     * This is the degradation that matters. The controller takes a `GovernanceOperationsService` in
     * its constructor, so a condition that let it register here would fail the context outright.
     */
    @Test
    fun `a declared schema with no service behind it opens no route and starts cleanly`() {
        WebApplicationContextRunner()
            .withConfiguration(autoConfigurations)
            .withUserConfiguration(RestImportingNoGraphConfig::class.java, EndpointMappingConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).hasSingleBean(DeclaredSchemaSource::class.java)
                assertThat(ctx).doesNotHaveBean(GovernanceOperationsService::class.java)
                assertThat(metamodelEndpoints(ctx)).isEmpty()
                assertThat(ctx).doesNotHaveBean(GovernanceController::class.java)
            }
    }

    @Test
    fun `enabled=false shuts the routes with the rest of the loop`() {
        webRunner
            .withPropertyValues("embabel.dice.metamodel.enabled=false")
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(metamodelEndpoints(ctx)).isEmpty()
                assertThat(ctx).doesNotHaveBean(GovernanceOperationsService::class.java)
                assertThat(ctx).doesNotHaveBean(GovernanceController::class.java)
                // The application's own DeclaredSchemaSource stays; only the metamodel beans go.
                assertThat(ctx).hasSingleBean(DeclaredSchemaSource::class.java)
            }
    }

    /**
     * With no runner there is no check to run, so there is no operator service either. Reading the
     * log through a surface that could not answer half its own routes would be worse than not
     * offering it.
     */
    @Test
    fun `drift mode off leaves no runner and therefore no routes`() {
        webRunner
            .withPropertyValues("embabel.dice.metamodel.drift.mode=off")
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).doesNotHaveBean(DriftCheckRunner::class.java)
                assertThat(ctx).doesNotHaveBean(GovernanceOperationsService::class.java)
                assertThat(metamodelEndpoints(ctx)).isEmpty()
                assertThat(ctx).doesNotHaveBean(GovernanceController::class.java)
            }
    }

    // ---- A wired loop without the import ----

    /**
     * The whole governance loop, in a servlet web application, with no `@Import` of the DICE REST
     * surface. The service is there for the host's own code and zero URLs are published.
     *
     * This is the test the operator comment is about: HTTP used to switch itself on as soon as a
     * schema existed, and now it takes the same explicit import every other DICE controller takes.
     */
    @Test
    fun `a wired loop publishes no route until the host imports DICE REST`() {
        WebApplicationContextRunner()
            .withConfiguration(autoConfigurations)
            .withUserConfiguration(InMemoryGovernanceConfig::class.java, EndpointMappingConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).hasSingleBean(GovernanceOperationsService::class.java)
                assertThat(metamodelEndpoints(ctx)).isEmpty()
                assertThat(ctx).doesNotHaveBean(GovernanceController::class.java)
            }
    }

    /**
     * The same with the graph backend selected and no schema declared: still no import, still no
     * routes. The import is the only thing that publishes them.
     */
    @Test
    fun `no import means no route whatever the host declared`() {
        WebApplicationContextRunner()
            .withConfiguration(autoConfigurations)
            .withPropertyValues(GRAPH_BACKEND)
            .withUserConfiguration(NoDeclaredSchemaConfig::class.java, EndpointMappingConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(metamodelEndpoints(ctx)).isEmpty()
                assertThat(ctx).doesNotHaveBean(GovernanceController::class.java)
            }
    }

    /**
     * No entry in the auto-configuration imports file may register the controller. That file is the
     * list Spring Boot applies to every application on the classpath, so a name in it is the one way
     * governance HTTP could come back without a host asking for it.
     */
    @Test
    fun `no auto-configuration is registered for the governance HTTP surface`() {
        val declared = PathMatchingResourcePatternResolver()
            .getResources("classpath*:META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
            .flatMap { it.inputStream.bufferedReader().readLines() }
            .map { it.trim() }
            .filter { it.startsWith("com.embabel.dice") }

        assertThat(declared).contains(MetamodelAutoConfiguration::class.java.name)
        assertThat(declared).noneMatch { "Governance" in it }
    }

    // ---- The agent tools are the host's to build ----

    /**
     * No autoconfigured context holds a `GovernanceTools` bean, in any of the shapes above. DICE
     * registers no tool object as a bean — `DiscoveryTools`, `GraphQueryTools` and `Memory` are all
     * constructed by the application that wants them, and governance follows that.
     */
    @Test
    fun `no autoconfigured context holds a GovernanceTools bean`() {
        runner.run { ctx ->
            assertThat(ctx).hasSingleBean(GovernanceOperationsService::class.java)
            assertThat(ctx).doesNotHaveBean(GovernanceTools::class.java)
        }
        webRunner.run { ctx ->
            assertThat(ctx).hasSingleBean(GovernanceController::class.java)
            assertThat(ctx).doesNotHaveBean(GovernanceTools::class.java)
        }
        WebApplicationContextRunner()
            .withConfiguration(autoConfigurations)
            .withPropertyValues(GRAPH_BACKEND)
            .withUserConfiguration(NoDeclaredSchemaConfig::class.java)
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(GovernanceTools::class.java)
            }
    }

    /**
     * And the host can build them in one line from the service the wiring did supply, which is what
     * makes the missing bean a design choice a consumer can live with. `GovernanceToolsTest` pins
     * which five tools come back.
     */
    @Test
    fun `a host builds the agent tools from the wired service`() {
        runner.run { ctx ->
            val tools = GovernanceTools.asTools(ctx.getBean<GovernanceOperationsService>())
            assertThat(tools).hasSize(5)
        }
    }

    // ---- A consumer's own bean wins ----

    @Test
    fun `a consumer operator service wins whether it is registered before or after`() {
        WebApplicationContextRunner()
            .withUserConfiguration(InMemoryGovernanceConfig::class.java, CustomOperationsConfig::class.java)
            .withConfiguration(autoConfigurations)
            .run(::assertConsumerServiceWon)

        WebApplicationContextRunner()
            .withConfiguration(autoConfigurations)
            .withUserConfiguration(InMemoryGovernanceConfig::class.java, CustomOperationsConfig::class.java)
            .run(::assertConsumerServiceWon)
    }

    /**
     * A host that wants these operations on a different path, or behind extra authorization, brings
     * its own controller bean and still imports [DiceRestConfiguration] for the other DICE
     * controllers. The shipped one backs off, so the two never compete for the same URLs.
     */
    @Test
    fun `a consumer controller wins and the shipped one backs off`() {
        WebApplicationContextRunner()
            .withConfiguration(autoConfigurations)
            .withUserConfiguration(
                RestImportingGovernanceConfig::class.java,
                CustomControllerConfig::class.java,
                EndpointMappingConfig::class.java,
            )
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).hasSingleBean(GovernanceController::class.java)
                assertThat(ctx.getBean<GovernanceController>())
                    .isSameAs(ctx.getBean(CustomControllerConfig::class.java).singleton)
            }
    }

    // ---- Inertness ----

    /**
     * Registering the operator surface runs nothing. A context that stamped a version or wrote a
     * report while it started would turn every application restart into a drift check, and an
     * operator reading the log would find entries nobody asked for.
     */
    @Test
    fun `building the context runs no check and writes to no store`() {
        webRunner.run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(GovernanceController::class.java)

            assertThat(ctx.getBean<RecordingDriftReportStore>().saved).isEmpty()
            assertThat(ctx.getBean<RecordingMetamodelVersionStore>().saved).isEmpty()
            assertThat(ctx.getBean<InMemoryPropositionRepository>().findAll())
                .isNotEmpty
                .allMatch { it.status == PropositionStatus.ACTIVE }

            // And the surface answers the moment it is asked. That is what makes the silence above
            // inertness: the wiring works, and it simply did nothing.
            assertThat(ctx.getBean<GovernanceOperationsService>().currentDeclaredVersion().schemaName)
                .isEqualTo(MetamodelTestFixtures.SCHEMA_NAME)
            assertThat(ctx.getBean<RecordingDriftReportStore>().saved).isEmpty()
        }
    }

    @Test
    fun `a check happens when, and only when, the operator asks for one`() {
        webRunner.run { ctx ->
            val reports = ctx.getBean<RecordingDriftReportStore>()
            assertThat(reports.saved).isEmpty()

            val check = ctx.getBean<GovernanceOperationsService>().runCheck()

            assertThat(check.driftedEntityTypes).containsExactly("Ghost")
            assertThat(reports.saved).hasSize(1)
            // A check reports and moves nothing.
            assertThat(ctx.getBean<InMemoryPropositionRepository>().findAll())
                .allMatch { it.status == PropositionStatus.ACTIVE }
        }
    }

    private fun assertConsumerServiceWon(
        ctx: org.springframework.boot.test.context.assertj.AssertableWebApplicationContext,
    ) {
        assertThat(ctx).hasNotFailed()
        assertThat(ctx).hasSingleBean(GovernanceOperationsService::class.java)
        assertThat(ctx.getBean<GovernanceOperationsService>())
            .isSameAs(ctx.getBean(CustomOperationsConfig::class.java).singleton)
    }

    /**
     * The `/api/v1/metamodel` URLs this context actually resolves, as `METHOD /path`.
     *
     * Read off a live [RequestMappingHandlerMapping], which is the object Spring MVC dispatches
     * through, so an empty answer means a client calling those paths gets a 404.
     */
    private fun metamodelEndpoints(ctx: ApplicationContext): List<String> =
        ctx.getBean(RequestMappingHandlerMapping::class.java).handlerMethods.keys
            .flatMap { info ->
                val verbs = info.methodsCondition.methods.map { it.name }.ifEmpty { listOf("ANY") }
                info.pathPatternsCondition?.patternValues.orEmpty()
                    .flatMap { path -> verbs.map { verb -> "$verb $path" } }
            }
            .filter { METAMODEL_PREFIX in it }
            .sorted()

    private companion object {

        const val GRAPH_BACKEND = "embabel.dice.store.type=graph"

        const val METAMODEL_PREFIX = "/api/v1/metamodel"

        /** Every route the operator surface exposes. Changing this list changes a public API. */
        val ALL_SIX_ROUTES = arrayOf(
            "GET $METAMODEL_PREFIX/declared-version",
            "GET $METAMODEL_PREFIX/drift-reports",
            "GET $METAMODEL_PREFIX/contexts/{contextId}/drift-reports",
            "POST $METAMODEL_PREFIX/drift-checks",
            "POST $METAMODEL_PREFIX/contexts/{contextId}/drift-checks",
            "POST $METAMODEL_PREFIX/contexts/{contextId}/quarantine/{propositionId}/release",
        )
    }
}

/**
 * A live Spring MVC handler mapping, so a test can ask which URLs a context resolves.
 *
 * It builds its table from the controller bean definitions the context holds, and it reads their
 * types without creating them, so a context carrying one stays as inert as it was.
 */
@Configuration(proxyBeanMethods = false)
internal open class EndpointMappingConfig {

    @Bean
    open fun requestMappingHandlerMapping(): RequestMappingHandlerMapping = RequestMappingHandlerMapping()
}

/**
 * A host that imported the DICE REST surface and wired the whole governance loop in memory. The
 * observed schema holds a `Ghost` type the declaration never mentions, so a check has real drift to
 * report.
 *
 * Its proposition store is an `InMemoryPropositionRepository`, which is what a host importing
 * [DiceRestConfiguration] already has: `MemoryController` comes with that import and needs a
 * `PropositionRepository` on the context.
 */
@Configuration(proxyBeanMethods = false)
@Import(DiceRestConfiguration::class)
internal open class RestImportingGovernanceConfig {

    @Bean
    open fun declaredSchemaSource(): DeclaredSchemaSource = FixedDeclaredSchemaSource()

    @Bean
    open fun versionStore(): RecordingMetamodelVersionStore = RecordingMetamodelVersionStore()

    @Bean
    open fun driftReportStore(): RecordingDriftReportStore = RecordingDriftReportStore()

    @Bean
    open fun observedSchemaSource(): FixedObservedSchemaSource = FixedObservedSchemaSource()

    @Bean
    open fun propositionStore(): InMemoryPropositionRepository = seededRepository()
}

/** The same host with its declaration taken away, so governance never wires. */
@Configuration(proxyBeanMethods = false)
@Import(DiceRestConfiguration::class)
internal open class RestImportingNoSchemaConfig {

    @Bean
    open fun persistenceManager(): PersistenceManager = mock()

    @Bean
    open fun propositionStore(): InMemoryPropositionRepository = seededRepository()
}

/**
 * A host that imported the DICE REST surface and declared a schema on the default in-memory backend.
 * Governance wires as far as it can, and stops short of a drift log, an observed schema, a runner
 * and therefore an operator service.
 */
@Configuration(proxyBeanMethods = false)
@Import(DiceRestConfiguration::class)
internal open class RestImportingNoGraphConfig {

    @Bean
    open fun declaredSchemaSource(): DeclaredSchemaSource = FixedDeclaredSchemaSource()

    @Bean
    open fun propositionStore(): InMemoryPropositionRepository = seededRepository()
}

/** An application that brought its own operator service. */
@Configuration(proxyBeanMethods = false)
internal open class CustomOperationsConfig {

    val singleton: GovernanceOperationsService = GovernanceOperationsService(
        declaredSchemaSource = FixedDeclaredSchemaSource(),
        versionStore = RecordingMetamodelVersionStore(),
        driftReportStore = RecordingDriftReportStore(),
        driftCheckRunner = ThrowingDriftCheckRunner,
        driftSweep = com.embabel.dice.spi.PropositionStoreDriftSweep(MapPropositionStore()),
        propositions = MapPropositionStore(),
    )

    @Bean
    open fun consumerGovernanceOperations(): GovernanceOperationsService = singleton
}

/** An application that brought its own controller, to put the routes somewhere else. */
@Configuration(proxyBeanMethods = false)
internal open class CustomControllerConfig {

    val singleton: GovernanceController = GovernanceController(CustomOperationsConfig().singleton)

    @Bean
    open fun consumerGovernanceController(): GovernanceController = singleton
}

/** Never asked in these tests; supplied so a consumer's service can be built without a graph. */
internal object ThrowingDriftCheckRunner : DriftCheckRunner {
    override fun run(contextId: com.embabel.agent.core.ContextId?) =
        throw UnsupportedOperationException("never called; these tests only ask which bean won")
}

/** Two propositions, one of them mentioning the `Ghost` type the declaration never governs. */
internal fun seededRepository(): InMemoryPropositionRepository =
    InMemoryPropositionRepository().apply {
        save(MetamodelTestFixtures.proposition("Ada haunts the archive", mentionType = "Ghost"))
        save(MetamodelTestFixtures.proposition("Ada wrote the notes", mentionType = "Person"))
    }

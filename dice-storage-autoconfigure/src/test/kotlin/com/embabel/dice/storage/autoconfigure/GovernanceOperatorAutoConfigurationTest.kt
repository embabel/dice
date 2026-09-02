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
import com.embabel.dice.web.rest.GovernanceController
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * Wiring tests for the governance operator surface: the service and the agent tools from
 * [MetamodelAutoConfiguration], and the REST controller from [GovernanceHttpAutoConfiguration].
 *
 * What the wiring alone can get wrong is whether the surface appears when there is something behind
 * it, disappears with the rest of the loop, backs off for a consumer's own bean, and stays inert —
 * registering these beans must never run a check or write to a store.
 */
class GovernanceOperatorAutoConfigurationTest {

    private val autoConfigurations = AutoConfigurations.of(
        MetamodelAutoConfiguration::class.java,
        GovernanceHttpAutoConfiguration::class.java,
    )

    /** A host with the whole loop in memory: stores, an observed schema, propositions. */
    private val runner = ApplicationContextRunner()
        .withConfiguration(autoConfigurations)
        .withUserConfiguration(InMemoryGovernanceConfig::class.java)

    private val webRunner = WebApplicationContextRunner()
        .withConfiguration(autoConfigurations)
        .withUserConfiguration(InMemoryGovernanceConfig::class.java)

    // ---- Present when the loop is ----

    @Test
    fun `the governance loop brings the operator service and the agent tools with it`() {
        runner.run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(GovernanceOperationsService::class.java)
            assertThat(ctx).hasSingleBean(GovernanceTools::class.java)
        }
    }

    @Test
    fun `the REST controller appears in a servlet web application`() {
        webRunner.run { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(GovernanceOperationsService::class.java)
            assertThat(ctx).hasSingleBean(GovernanceController::class.java)
        }
    }

    /**
     * The service and the tools are useful to a host with no HTTP at all, so only the controller is
     * gated on a servlet web application.
     */
    @Test
    fun `outside a web application there is a service and no controller`() {
        runner.run { ctx ->
            assertThat(ctx).hasSingleBean(GovernanceOperationsService::class.java)
            assertThat(ctx).doesNotHaveBean(GovernanceController::class.java)
        }
    }

    // ---- Absent when the loop is ----

    @Test
    fun `no DeclaredSchemaSource means no operator surface at all`() {
        WebApplicationContextRunner()
            .withConfiguration(autoConfigurations)
            .withPropertyValues(GRAPH_BACKEND)
            .withUserConfiguration(NoDeclaredSchemaConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).doesNotHaveBean(GovernanceOperationsService::class.java)
                assertThat(ctx).doesNotHaveBean(GovernanceTools::class.java)
                assertThat(ctx).doesNotHaveBean(GovernanceController::class.java)
            }
    }

    @Test
    fun `enabled=false removes the operator surface with the rest of the loop`() {
        webRunner
            .withPropertyValues("embabel.dice.metamodel.enabled=false")
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).doesNotHaveBean(GovernanceOperationsService::class.java)
                assertThat(ctx).doesNotHaveBean(GovernanceTools::class.java)
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
    fun `drift mode off leaves no runner and therefore no operator surface`() {
        webRunner
            .withPropertyValues("embabel.dice.metamodel.drift.mode=off")
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).doesNotHaveBean(DriftCheckRunner::class.java)
                assertThat(ctx).doesNotHaveBean(GovernanceOperationsService::class.java)
                assertThat(ctx).doesNotHaveBean(GovernanceController::class.java)
            }
    }

    /**
     * The default in-memory backend has no drift log and no observed-schema source, so no runner and
     * no operator surface. The rest of the loop still starts.
     */
    @Test
    fun `a host with no graph gets the loop without the operator surface`() {
        WebApplicationContextRunner()
            .withConfiguration(autoConfigurations)
            .withUserConfiguration(InMemoryBackendConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).doesNotHaveBean(GovernanceOperationsService::class.java)
                assertThat(ctx).doesNotHaveBean(GovernanceController::class.java)
            }
    }

    // ---- A consumer's own bean wins ----

    @Test
    fun `a consumer operator service wins whether it is registered before or after`() {
        bothOrders(CustomOperationsConfig::class.java) { ctx ->
            assertThat(ctx).hasNotFailed()
            assertThat(ctx).hasSingleBean(GovernanceOperationsService::class.java)
            assertThat(ctx.getBean<GovernanceOperationsService>())
                .isSameAs(ctx.getBean(CustomOperationsConfig::class.java).singleton)
        }
    }

    @Test
    fun `a consumer controller wins whether it is registered before or after`() {
        bothOrders(CustomControllerConfig::class.java) { ctx ->
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
            assertThat(ctx.getBean<MapPropositionStore>().findAll())
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
            assertThat(ctx.getBean<MapPropositionStore>().findAll())
                .allMatch { it.status == PropositionStatus.ACTIVE }
        }
    }

    // ---- Registration ----

    /**
     * The HTTP surface is registered as an auto-configuration of its own. That entry is what makes
     * `spring.autoconfigure.exclude: com.embabel.dice.storage.autoconfigure.GovernanceHttpAutoConfiguration`
     * a knob at all: Spring Boot's exclusion filter works on the names in this file, so a controller
     * declared inside [MetamodelAutoConfiguration] could not have been switched off on its own.
     */
    @Test
    fun `the HTTP surface is its own auto-configuration, so a host can exclude it by name`() {
        val declared = PathMatchingResourcePatternResolver()
            .getResources("classpath*:META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
            .flatMap { it.inputStream.bufferedReader().readLines() }
            .map { it.trim() }

        assertThat(declared).contains(GovernanceHttpAutoConfiguration::class.java.name)
        assertThat(declared).contains(MetamodelAutoConfiguration::class.java.name)
    }

    /**
     * What that exclusion leaves behind: the governance loop, the operator service and the agent
     * tools, all in a servlet web application, with no controller. This is the same context an
     * excluded [GovernanceHttpAutoConfiguration] produces, built by registering the other
     * auto-configuration alone.
     */
    @Test
    fun `without the HTTP auto-configuration the service and the tools still stand`() {
        WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MetamodelAutoConfiguration::class.java))
            .withUserConfiguration(InMemoryGovernanceConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).hasSingleBean(GovernanceOperationsService::class.java)
                assertThat(ctx).hasSingleBean(GovernanceTools::class.java)
                assertThat(ctx).doesNotHaveBean(GovernanceController::class.java)
            }
    }

    /** Runs [assertions] with [userConfiguration] registered before and after the auto-configurations. */
    private fun bothOrders(
        userConfiguration: Class<*>,
        assertions: (org.springframework.boot.test.context.assertj.AssertableWebApplicationContext) -> Unit,
    ) {
        WebApplicationContextRunner()
            .withUserConfiguration(InMemoryGovernanceConfig::class.java, userConfiguration)
            .withConfiguration(autoConfigurations)
            .run(assertions)

        WebApplicationContextRunner()
            .withConfiguration(autoConfigurations)
            .withUserConfiguration(InMemoryGovernanceConfig::class.java, userConfiguration)
            .run(assertions)
    }

    private companion object {

        const val GRAPH_BACKEND = "embabel.dice.store.type=graph"
    }
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

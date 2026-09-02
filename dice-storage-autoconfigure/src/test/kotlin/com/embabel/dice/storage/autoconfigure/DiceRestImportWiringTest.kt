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

import com.embabel.agent.api.common.Ai
import com.embabel.agent.rag.ingestion.HierarchicalContentReader
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.SchemaRegistry
import com.embabel.dice.pipeline.PropositionPipeline
import com.embabel.dice.projection.memory.CollectorRunner
import com.embabel.dice.web.rest.DiceRestConfiguration
import com.embabel.dice.web.rest.DiscoveryController
import com.embabel.dice.web.rest.MemoryController
import com.embabel.dice.web.rest.PropositionPipelineController
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * The controllers behind a host's `@Import(DiceRestConfiguration)` must see the store beans the
 * auto-configurations contribute. `@ConditionalOnBean` on a plainly imported class is answered
 * while the importing configuration is read, before Spring Boot registers any auto-configuration
 * bean definition, so a condition naming an autoconfigured store answers "no" in exactly the
 * wiring the docs recommend. These tests pin the fix: the conditional controllers arrive through
 * a deferred import that is processed after the auto-configuration selector.
 *
 * The host shape throughout is the documented one — stores from `dice-storage-autoconfigure`,
 * runner and pipeline hand-built.
 */
class DiceRestImportWiringTest {

    private val autoConfigurations = AutoConfigurations.of(
        DiceStorageAutoConfiguration::class.java,
        CollectorAutoConfiguration::class.java,
    )

    private val importingHost = WebApplicationContextRunner()
        .withConfiguration(autoConfigurations)
        .withUserConfiguration(ImportingHostConfig::class.java, EndpointMappingConfig::class.java)

    @Test
    fun `discovery routes exist for a host on autoconfigured stores`() {
        importingHost.run { ctx ->
            assertThat(ctx).hasSingleBean(DiscoveryController::class.java)
            assertThat(endpoints(ctx)).anyMatch { "/discovery" in it }
        }
    }

    @Test
    fun `pipeline routes exist for a host on autoconfigured stores`() {
        importingHost.run { ctx ->
            assertThat(ctx).hasSingleBean(PropositionPipelineController::class.java)
        }
    }

    @Test
    fun `memory routes ride the same import`() {
        importingHost.run { ctx ->
            assertThat(ctx).hasSingleBean(MemoryController::class.java)
            assertThat(endpoints(ctx)).anyMatch { "/memory" in it }
        }
    }

    @Test
    fun `no import means no dice routes whatever the autoconfigurations wired`() {
        WebApplicationContextRunner()
            .withConfiguration(autoConfigurations)
            .withUserConfiguration(NonImportingHostConfig::class.java, EndpointMappingConfig::class.java)
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(DiscoveryController::class.java)
                assertThat(ctx).doesNotHaveBean(PropositionPipelineController::class.java)
                assertThat(ctx).doesNotHaveBean(MemoryController::class.java)
                assertThat(endpoints(ctx)).isEmpty()
            }
    }

    @Test
    fun `a missing collector runner leaves discovery off and the context clean`() {
        WebApplicationContextRunner()
            .withConfiguration(autoConfigurations)
            .withUserConfiguration(NoRunnerHostConfig::class.java, EndpointMappingConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx).doesNotHaveBean(DiscoveryController::class.java)
                assertThat(ctx).hasSingleBean(MemoryController::class.java)
            }
    }

    /** The URLs this context actually resolves, as `METHOD /path`, read off a live handler mapping. */
    private fun endpoints(ctx: ApplicationContext): List<String> =
        ctx.getBean(RequestMappingHandlerMapping::class.java).handlerMethods.keys
            .flatMap { info ->
                val verbs = info.methodsCondition.methods.map { it.name }.ifEmpty { listOf("ANY") }
                info.pathPatternsCondition?.patternValues.orEmpty()
                    .flatMap { path -> verbs.map { verb -> "$verb $path" } }
            }
            .sorted()

    /**
     * A live Spring MVC handler mapping. It builds its table from the controller bean definitions
     * the context holds and reads their types without creating them.
     */
    @Configuration(proxyBeanMethods = false)
    open class EndpointMappingConfig {
        @Bean
        open fun requestMappingHandlerMapping(): RequestMappingHandlerMapping = RequestMappingHandlerMapping()
    }

    /** Everything a host hand-builds today: the AI stub the in-memory store needs, runner, pipeline. */
    @Configuration(proxyBeanMethods = false)
    open class HandBuiltBeansConfig {
        @Bean
        open fun ai(): Ai {
            val ai = mock<Ai>()
            whenever(ai.withDefaultEmbeddingService()).thenReturn(mock<EmbeddingService>())
            return ai
        }

        @Bean
        open fun collectorRunner(): CollectorRunner = mock<CollectorRunner>()

        @Bean
        open fun propositionPipeline(): PropositionPipeline = mock<PropositionPipeline>()

        @Bean
        open fun entityResolver(): EntityResolver = mock<EntityResolver>()

        @Bean
        open fun schemaRegistry(): SchemaRegistry = mock<SchemaRegistry>()

        /**
         * The pipeline controller's default content reader is the Tika one, and Tika's rag-ingestion
         * module is optional and absent here; a supplied bean keeps the default from evaluating.
         */
        @Bean
        open fun contentReader(): HierarchicalContentReader = mock<HierarchicalContentReader>()
    }

    @Configuration(proxyBeanMethods = false)
    @Import(DiceRestConfiguration::class, HandBuiltBeansConfig::class)
    open class ImportingHostConfig

    @Configuration(proxyBeanMethods = false)
    @Import(HandBuiltBeansConfig::class)
    open class NonImportingHostConfig

    /** Imports DICE REST and wires the pipeline, with no collector runner anywhere. */
    @Configuration(proxyBeanMethods = false)
    @Import(DiceRestConfiguration::class)
    open class NoRunnerHostConfig {
        @Bean
        open fun ai(): Ai {
            val ai = mock<Ai>()
            whenever(ai.withDefaultEmbeddingService()).thenReturn(mock<EmbeddingService>())
            return ai
        }

        @Bean
        open fun propositionPipeline(): PropositionPipeline = mock<PropositionPipeline>()

        @Bean
        open fun entityResolver(): EntityResolver = mock<EntityResolver>()

        @Bean
        open fun schemaRegistry(): SchemaRegistry = mock<SchemaRegistry>()

        /**
         * The pipeline controller's default content reader is the Tika one, and Tika's rag-ingestion
         * module is optional and absent here; a supplied bean keeps the default from evaluating.
         */
        @Bean
        open fun contentReader(): HierarchicalContentReader = mock<HierarchicalContentReader>()
    }
}

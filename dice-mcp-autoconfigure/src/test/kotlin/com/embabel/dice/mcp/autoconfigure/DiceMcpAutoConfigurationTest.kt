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
package com.embabel.dice.mcp.autoconfigure

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.tool.ToolObject
import com.embabel.agent.mcpserver.McpToolExport
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.dice.mcp.DiceMcpTools
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.storage.autoconfigure.DiceStorageAutoConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.getBean
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.FilteredClassLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * `ApplicationContextRunner` wiring tests for [DiceMcpAutoConfiguration]: no MCP server process,
 * no Neo4j — the autoconfiguration plus a stub [PropositionRepository] and `embabel.dice.mcp.*`.
 */
class DiceMcpAutoConfigurationTest {

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(DiceMcpAutoConfiguration::class.java))

    @Test
    fun `disabled by default so no tools or export beans`() {
        runner
            .withUserConfiguration(StubPropositionRepositoryConfig::class.java)
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(DiceMcpAutoConfiguration::class.java)
                assertThat(ctx).doesNotHaveBean(DiceMcpTools::class.java)
                assertThat(ctx).doesNotHaveBean(McpToolExport::class.java)
            }
    }

    @Test
    fun `master switch off means no beans`() {
        runner
            .withUserConfiguration(StubPropositionRepositoryConfig::class.java)
            .withPropertyValues("embabel.dice.mcp.enabled=false")
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(DiceMcpTools::class.java)
                assertThat(ctx).doesNotHaveBean(McpToolExport::class.java)
            }
    }

    @Test
    fun `enabled without a PropositionRepository exports nothing`() {
        runner
            .withPropertyValues("embabel.dice.mcp.enabled=true")
            .run { ctx ->
                assertThat(ctx).hasSingleBean(DiceMcpAutoConfiguration::class.java)
                assertThat(ctx).doesNotHaveBean(DiceMcpTools::class.java)
                assertThat(ctx).doesNotHaveBean("diceMcpToolExport")
            }
    }

    @Test
    fun `enabled with a repository wires tools and exports the four names`() {
        runner
            .withUserConfiguration(StubPropositionRepositoryConfig::class.java)
            .withPropertyValues("embabel.dice.mcp.enabled=true")
            .run { ctx ->
                assertThat(ctx).hasSingleBean(DiceMcpTools::class.java)
                assertThat(ctx).hasBean("diceMcpToolExport")

                val export = ctx.getBean<McpToolExport>("diceMcpToolExport")
                val names = export.toolCallbacks.map { it.toolDefinition.name() }.toSet()
                assertThat(names).isEqualTo(DiceMcpTools.TOOL_NAMES)
            }
    }

    @Test
    fun `minConfidence and defaultLimit bind from properties`() {
        runner
            .withUserConfiguration(StubPropositionRepositoryConfig::class.java)
            .withPropertyValues(
                "embabel.dice.mcp.enabled=true",
                "embabel.dice.mcp.min-confidence=0.7",
                "embabel.dice.mcp.default-limit=3",
            )
            .run { ctx ->
                val props = ctx.getBean<DiceMcpProperties>()
                assertThat(props.minConfidence).isEqualTo(0.7)
                assertThat(props.defaultLimit).isEqualTo(3)
            }
    }

    @Test
    fun `invalid minConfidence fails context startup`() {
        runner
            .withUserConfiguration(StubPropositionRepositoryConfig::class.java)
            .withPropertyValues(
                "embabel.dice.mcp.enabled=true",
                "embabel.dice.mcp.min-confidence=1.5",
            )
            .run { ctx ->
                assertThat(ctx).hasFailed()
                assertThat(ctx.startupFailure)
                    .hasStackTraceContaining("embabel.dice.mcp.min-confidence")
            }
    }

    @Test
    fun `invalid defaultLimit fails context startup`() {
        runner
            .withUserConfiguration(StubPropositionRepositoryConfig::class.java)
            .withPropertyValues(
                "embabel.dice.mcp.enabled=true",
                "embabel.dice.mcp.default-limit=0",
            )
            .run { ctx ->
                assertThat(ctx).hasFailed()
                assertThat(ctx.startupFailure)
                    .hasStackTraceContaining("embabel.dice.mcp.default-limit")
            }
    }

    /**
     * The tools clamp `limit` to [DiceMcpTools.MAX_LIMIT], so a configured default above it
     * would bind cleanly and then be silently truncated on every call. Fail at startup instead.
     */
    @Test
    fun `defaultLimit above the clamp fails context startup`() {
        runner
            .withUserConfiguration(StubPropositionRepositoryConfig::class.java)
            .withPropertyValues(
                "embabel.dice.mcp.enabled=true",
                "embabel.dice.mcp.default-limit=${DiceMcpTools.MAX_LIMIT + 1}",
            )
            .run { ctx ->
                assertThat(ctx).hasFailed()
                assertThat(ctx.startupFailure)
                    .hasStackTraceContaining("embabel.dice.mcp.default-limit")
            }
    }

    @Test
    fun `defaultLimit at the clamp binds`() {
        runner
            .withUserConfiguration(StubPropositionRepositoryConfig::class.java)
            .withPropertyValues(
                "embabel.dice.mcp.enabled=true",
                "embabel.dice.mcp.default-limit=${DiceMcpTools.MAX_LIMIT}",
            )
            .run { ctx ->
                assertThat(ctx).hasNotFailed()
                assertThat(ctx.getBean<DiceMcpProperties>().defaultLimit)
                    .isEqualTo(DiceMcpTools.MAX_LIMIT)
            }
    }

    @Test
    fun `afterName names DiceStorageAutoConfiguration so there is no compile dep`() {
        val afterName = DiceMcpAutoConfiguration::class.java
            .getAnnotation(AutoConfiguration::class.java)
            .afterName
        assertThat(afterName).containsExactly(
            "com.embabel.dice.storage.autoconfigure.DiceStorageAutoConfiguration",
        )
    }

    /**
     * Runs [DiceStorageAutoConfiguration] and [DiceMcpAutoConfiguration] together, not the
     * isolated stub the other tests use, to prove `afterName` on [DiceMcpAutoConfiguration]
     * actually resolves `@ConditionalOnBean(PropositionRepository::class)`. MCP is listed
     * first so a broken `afterName` cannot hide behind declaration order — without the wait,
     * the store bean is not there yet and export silently drops out (the #102 failure).
     */
    @Test
    fun `combining storage and MCP autoconfig wires tools from the store bean`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    DiceMcpAutoConfiguration::class.java,
                    DiceStorageAutoConfiguration::class.java,
                )
            )
            .withUserConfiguration(StubAiConfig::class.java)
            .withPropertyValues("embabel.dice.mcp.enabled=true")
            .run { ctx ->
                assertThat(ctx).hasSingleBean(PropositionRepository::class.java)
                assertThat(ctx).hasSingleBean(DiceMcpTools::class.java)
                assertThat(ctx).hasBean("diceMcpToolExport")

                val export = ctx.getBean<McpToolExport>("diceMcpToolExport")
                val names = export.toolCallbacks.map { it.toolDefinition.name() }.toSet()
                assertThat(names).isEqualTo(DiceMcpTools.TOOL_NAMES)
            }
    }

    @Test
    fun `combining storage and MCP autoconfig stays dark when the switch is off`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    DiceMcpAutoConfiguration::class.java,
                    DiceStorageAutoConfiguration::class.java,
                )
            )
            .withUserConfiguration(StubAiConfig::class.java)
            .run { ctx ->
                assertThat(ctx).hasSingleBean(PropositionRepository::class.java)
                assertThat(ctx).doesNotHaveBean(DiceMcpAutoConfiguration::class.java)
                assertThat(ctx).doesNotHaveBean(DiceMcpTools::class.java)
                assertThat(ctx).doesNotHaveBean(McpToolExport::class.java)
            }
    }

    @Test
    fun `combining storage and MCP autoconfig exports nothing when storage has no Ai`() {
        ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(
                    DiceMcpAutoConfiguration::class.java,
                    DiceStorageAutoConfiguration::class.java,
                )
            )
            .withPropertyValues("embabel.dice.mcp.enabled=true")
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(PropositionRepository::class.java)
                assertThat(ctx).hasSingleBean(DiceMcpAutoConfiguration::class.java)
                assertThat(ctx).doesNotHaveBean(DiceMcpTools::class.java)
                assertThat(ctx).doesNotHaveBean("diceMcpToolExport")
            }
    }

    @Test
    fun `bound minConfidence is applied to the tools bean`() {
        runner
            .withUserConfiguration(StubPropositionRepositoryConfig::class.java)
            .withPropertyValues(
                "embabel.dice.mcp.enabled=true",
                "embabel.dice.mcp.min-confidence=0.7",
            )
            .run { ctx ->
                val tools = ctx.getBean<DiceMcpTools>()
                tools.storeMemory("session-1", "Weak guess", confidence = 0.6)
                tools.storeMemory("session-1", "Strong fact", confidence = 0.9)
                val listed = tools.listMemories("session-1", limit = 10)
                assertThat(listed).contains("Strong fact")
                assertThat(listed).doesNotContain("Weak guess")
            }
    }

    @Test
    fun `bound defaultLimit is applied when list is called without a limit`() {
        runner
            .withUserConfiguration(StubPropositionRepositoryConfig::class.java)
            .withPropertyValues(
                "embabel.dice.mcp.enabled=true",
                "embabel.dice.mcp.min-confidence=0.0",
                "embabel.dice.mcp.default-limit=2",
            )
            .run { ctx ->
                val tools = ctx.getBean<DiceMcpTools>()
                tools.storeMemory("session-1", "Fact one", confidence = 0.9)
                tools.storeMemory("session-1", "Fact two", confidence = 0.8)
                tools.storeMemory("session-1", "Fact three", confidence = 0.7)
                val listed = tools.listMemories("session-1")
                val numbered = listed.lines().count { it.matches(Regex("^\\d+\\..*")) }
                assertThat(numbered).isEqualTo(2)
            }
    }

    @Test
    fun `no McpToolExport on the classpath means no auto-config`() {
        runner
            .withClassLoader(FilteredClassLoader(McpToolExport::class.java))
            .withUserConfiguration(StubPropositionRepositoryConfig::class.java)
            .withPropertyValues("embabel.dice.mcp.enabled=true")
            .run { ctx ->
                assertThat(ctx).doesNotHaveBean(DiceMcpAutoConfiguration::class.java)
                assertThat(ctx).doesNotHaveBean(DiceMcpTools::class.java)
            }
    }

    @Test
    fun `a custom DiceMcpTools bean wins over the default`() {
        runner
            .withUserConfiguration(StubPropositionRepositoryConfig::class.java, CustomToolsConfig::class.java)
            .withPropertyValues("embabel.dice.mcp.enabled=true")
            .run { ctx ->
                assertThat(ctx).hasSingleBean(DiceMcpTools::class.java)
                assertThat(ctx.getBean<DiceMcpTools>()).isSameAs(CustomToolsConfig.INSTANCE)
                assertThat(ctx).hasBean("diceMcpToolExport")
            }
    }

    @Test
    fun `a custom diceMcpToolExport bean wins over the default`() {
        runner
            .withUserConfiguration(StubPropositionRepositoryConfig::class.java, CustomExportConfig::class.java)
            .withPropertyValues("embabel.dice.mcp.enabled=true")
            .run { ctx ->
                assertThat(ctx).hasBean("diceMcpToolExport")
                assertThat(ctx.getBean<McpToolExport>("diceMcpToolExport"))
                    .isSameAs(CustomExportConfig.INSTANCE)
            }
    }

    @Configuration(proxyBeanMethods = false)
    private class StubPropositionRepositoryConfig {
        @Bean
        fun propositionRepository(): PropositionRepository = InMemoryPropositionRepository()
    }

    @Configuration(proxyBeanMethods = false)
    private class CustomToolsConfig {
        @Bean
        fun diceMcpTools(): DiceMcpTools = INSTANCE

        companion object {
            val INSTANCE = DiceMcpTools(InMemoryPropositionRepository())
        }
    }

    @Configuration(proxyBeanMethods = false)
    private class CustomExportConfig {
        @Bean(name = ["diceMcpToolExport"])
        fun diceMcpToolExport(): McpToolExport = INSTANCE

        companion object {
            val INSTANCE: McpToolExport = McpToolExport.fromToolObject(
                ToolObject(objects = listOf(DiceMcpTools(InMemoryPropositionRepository()))),
            )
        }
    }

    /**
     * Satisfies [DiceStorageAutoConfiguration]'s `@ConditionalOnBean(Ai::class)` gate on
     * `inMemoryPropositionRepository`, so the real in-memory store bean is registered and
     * the cross-auto-configuration ordering path is exercised.
     */
    @Configuration(proxyBeanMethods = false)
    private class StubAiConfig {
        @Bean
        fun ai(): Ai {
            val ai = mock<Ai>()
            whenever(ai.withDefaultEmbeddingService()).thenReturn(mock<EmbeddingService>())
            return ai
        }
    }
}

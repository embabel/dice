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

import com.embabel.agent.api.tool.ToolObject
import com.embabel.agent.mcpserver.McpToolExport
import com.embabel.dice.mcp.DiceMcpTools
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.getBean
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
}

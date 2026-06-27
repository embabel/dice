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
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Registers [DiceMcpTools] and exports them as MCP tools when embabel-agent's MCP server is present.
 *
 * Typical application dependencies:
 * ```xml
 * <dependency>
 *     <groupId>com.embabel.dice</groupId>
 *     <artifactId>dice-mcp-autoconfigure</artifactId>
 * </dependency>
 * <dependency>
 *     <groupId>com.embabel.agent</groupId>
 *     <artifactId>embabel-agent-starter-mcpserver</artifactId>
 * </dependency>
 * ```
 *
 * ```yaml
 * embabel:
 *   dice:
 *     mcp:
 *       enabled: true
 * ```
 *
 * `afterName` waits for `dice-storage-autoconfigure` when that module is on the classpath, so
 * `@ConditionalOnBean(PropositionRepository)` sees the store bean. If storage autoconfig is absent,
 * the named class is ignored and the host supplies its own repository.
 */
@AutoConfiguration(afterName = ["com.embabel.dice.storage.autoconfigure.DiceStorageAutoConfiguration"])
@ConditionalOnClass(McpToolExport::class)
@ConditionalOnProperty(prefix = "embabel.dice.mcp", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(DiceMcpProperties::class)
class DiceMcpAutoConfiguration {

    private val logger = LoggerFactory.getLogger(DiceMcpAutoConfiguration::class.java)

    @Bean
    @ConditionalOnBean(PropositionRepository::class)
    @ConditionalOnMissingBean(DiceMcpTools::class)
    fun diceMcpTools(
        repository: PropositionRepository,
        properties: DiceMcpProperties,
    ): DiceMcpTools = DiceMcpTools(
        repository = repository,
        minConfidence = properties.minConfidence,
        defaultLimit = properties.defaultLimit,
    )

    @Bean("diceMcpToolExport")
    @ConditionalOnBean(DiceMcpTools::class)
    @ConditionalOnMissingBean(name = ["diceMcpToolExport"])
    fun diceMcpToolExport(tools: DiceMcpTools): McpToolExport {
        logger.info("Exporting DICE MCP tools: {}", DiceMcpTools.TOOL_NAMES.sorted())
        return McpToolExport.fromToolObject(ToolObject(objects = listOf(tools)))
    }
}

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
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.SchemaRegistry
import com.embabel.dice.entity.EntityResolutionService
import com.embabel.dice.mcp.DiceMcpTools
import com.embabel.dice.pipeline.PropositionPipeline
import com.embabel.dice.proposition.PropositionRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
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
 *       profile: core   # or extended
 * ```
 */
@AutoConfiguration
@ConditionalOnClass(McpToolExport::class)
@ConditionalOnProperty(prefix = "embabel.dice.mcp", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(DiceMcpProperties::class)
open class DiceMcpAutoConfiguration {

    private val logger = LoggerFactory.getLogger(DiceMcpAutoConfiguration::class.java)

    @Bean
    @ConditionalOnBean(PropositionRepository::class)
    @ConditionalOnMissingBean(DiceMcpTools::class)
    open fun diceMcpTools(
        repository: PropositionRepository,
        pipeline: ObjectProvider<PropositionPipeline>,
        entityResolver: ObjectProvider<EntityResolver>,
        schemaRegistry: ObjectProvider<SchemaRegistry>,
        entityResolutionService: ObjectProvider<EntityResolutionService>,
        properties: DiceMcpProperties,
    ): DiceMcpTools = DiceMcpTools(
        repository = repository,
        pipeline = pipeline.ifAvailable,
        entityResolver = entityResolver.ifAvailable,
        schemaRegistry = schemaRegistry.ifAvailable,
        entityResolutionService = entityResolutionService.ifAvailable,
        minConfidence = properties.minConfidence,
        defaultLimit = properties.defaultLimit,
    )

    @Bean("diceMcpToolExport")
    @ConditionalOnBean(DiceMcpTools::class)
    @ConditionalOnMissingBean(name = ["diceMcpToolExport"])
    open fun diceMcpToolExport(
        tools: DiceMcpTools,
        properties: DiceMcpProperties,
    ): McpToolExport {
        val allowed = properties.profile.toolNames(
            pipelineAvailable = tools.pipelineAvailable(),
            entityResolutionAvailable = tools.entityResolutionAvailable(),
        )
        logger.info(
            "Exporting DICE MCP tools (profile={}, count={}): {}",
            properties.profile,
            allowed.size,
            allowed.sorted(),
        )
        return McpToolExport.fromToolObject(
            ToolObject(
                objects = listOf(tools),
                filter = { name -> name in allowed },
            ),
        )
    }
}

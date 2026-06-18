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
package com.embabel.dice.entity

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.tool.Tool

/**
 * LLM-invocable tools for asserting entities and relationships into the knowledge graph.
 *
 * Wraps [EntityResolutionService] as `@LlmTool` methods so an LLM agent can
 * resolve, create, and relate entities directly.
 *
 * Usage:
 * ```kotlin
 * val tools: List<Tool> = EntityResolutionTools.asTools(entityResolutionService)
 * // Add to agent's tool set
 * ```
 *
 * @param service The entity resolution service to delegate to
 */
class EntityResolutionTools(
    private val service: EntityResolutionService,
) {

    /**
     * Assert entities into the knowledge graph with automatic resolution.
     *
     * Each entity is resolved against existing graph data using the full escalation
     * chain (exact name, fuzzy match, vector similarity, LLM). New entities are created;
     * existing entities are updated with merged labels and properties.
     *
     * @param entities The entities to assert
     * @return Resolution results for each entity
     */
    @LlmTool(
        name = "assert_entities",
        description = "Assert entities into the knowledge graph. Each entity is resolved against existing " +
            "data — matching entities are updated, new ones are created. " +
            "Provide a list of entities with name, labels (types like Person, Company), " +
            "optional description, and optional properties.",
    )
    fun assertEntities(
        @LlmTool.Param(description = "List of entities to assert. Each needs a 'name' and optionally 'labels', 'description', and 'properties'.")
        entities: List<EntityAssertion>,
    ): EntityAssertionResult {
        return service.resolve(EntityAssertionRequest(entities = entities))
    }

    /**
     * Assert entities and relationships into the knowledge graph.
     *
     * Entities are resolved first, then relationships are created between the
     * resolved entities. Relationships referencing vetoed entities are skipped.
     *
     * @param entities The entities to assert
     * @param relationships The relationships to create between entities
     * @return Resolution and relationship results
     */
    @LlmTool(
        name = "assert_entities_and_relationships",
        description = "Assert entities and relationships into the knowledge graph. " +
            "Entities are resolved first (matched or created), then relationships are created between them. " +
            "Relationship source and target must match entity names from the entities list.",
    )
    fun assertEntitiesAndRelationships(
        @LlmTool.Param(description = "List of entities to assert. Each needs a 'name' and optionally 'labels', 'description', and 'properties'.")
        entities: List<EntityAssertion>,
        @LlmTool.Param(description = "List of relationships. Each needs 'source' (entity name), 'target' (entity name), 'type' (e.g. WORKS_AT), and optionally 'description' and 'properties'.")
        relationships: List<RelationshipAssertion>,
    ): EntityAssertionResult {
        return service.resolve(EntityAssertionRequest(
            entities = entities,
            relationships = relationships,
        ))
    }

    companion object {
        /**
         * Create [Tool] instances from an [EntityResolutionService].
         *
         * ```kotlin
         * val tools = EntityResolutionTools.asTools(entityResolutionService)
         * ```
         */
        fun asTools(service: EntityResolutionService): List<Tool> {
            return Tool.fromInstance(EntityResolutionTools(service))
        }
    }
}

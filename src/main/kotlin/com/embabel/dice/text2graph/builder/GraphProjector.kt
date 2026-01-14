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
package com.embabel.dice.text2graph.builder

import com.embabel.agent.core.DataDictionary
import com.embabel.dice.text2graph.KnowledgeGraphDelta

/**
 * Projects a KnowledgeGraphDelta (entities and relationships) into an object graph
 * where entities are instantiated as their corresponding domain objects (e.g., Person, Animal)
 * and relationships are represented as object references.
 *
 * Ensures that the same entity instance is shared across multiple references.
 * @param E type of projected or stored entity
 */
interface GraphProjector<E : Any> {

    /**
     * Projects entities from the delta into domain objects.
     * Returns a list of all instantiated domain objects.
     *
     * Entities are instantiated based on their labels, resolved against the schema's domain types.
     * Entities that cannot be resolved will generate warnings but will not cause errors.
     *
     * @param delta The knowledge graph delta containing entities and relationships
     * @return List of all domain objects with relationships resolved
     */
    fun project(schema: DataDictionary, delta: KnowledgeGraphDelta?): List<E>
}

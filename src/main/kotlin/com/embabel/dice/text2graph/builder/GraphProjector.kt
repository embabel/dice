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


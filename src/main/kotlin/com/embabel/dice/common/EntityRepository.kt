package com.embabel.dice.common

import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.Retrievable
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.agent.rag.service.CoreSearchOperations
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.util.loggerFor

/**
 * Storage interface for named entities.
 * Extends [CoreSearchOperations] for compatibility with [CoreSearchOperationsEntityResolver],
 * and adds write operations for entity persistence.
 *
 * Search operations are inherited from [CoreSearchOperations]:
 * - [findById] - exact ID lookup
 * - [vectorSearch] - semantic similarity search
 * - [textSearch] - text/Lucene search
 *
 * Implementations may use different backends (in-memory, database, vector store, graph database).
 */
interface EntityRepository : CoreSearchOperations {

    // ===========================================
    // Write Operations
    // ===========================================

    /**
     * Save an entity. If an entity with the same ID exists, it will be replaced.
     * @return The saved entity (may have updated fields like timestamps)
     */
    fun save(entity: NamedEntityData): NamedEntityData

    /**
     * Save multiple entities.
     * @return The saved entities
     */
    fun saveAll(entities: Collection<NamedEntityData>): List<NamedEntityData> =
        entities.map { save(it) }

    /**
     * Update an existing entity. Fails if entity doesn't exist.
     * Use this when you want to ensure you're updating, not creating.
     * @return The updated entity
     * @throws NoSuchElementException if entity with given ID doesn't exist
     */
    fun update(entity: NamedEntityData): NamedEntityData {
        findEntityById(entity.id) ?: throw NoSuchElementException("Entity not found: ${entity.id}")
        return save(entity)
    }

    /**
     * Delete an entity by ID.
     * @return true if the entity was deleted, false if it didn't exist
     */
    fun delete(id: String): Boolean

    /**
     * Delete multiple entities by ID.
     * @return Number of entities actually deleted
     */
    fun deleteAll(ids: Collection<String>): Int =
        ids.count { delete(it) }

    // ===========================================
    // Entity-Specific Read Operations
    // ===========================================

    /**
     * Find an entity by its ID.
     */
    fun findEntityById(id: String): NamedEntityData?

    /**
     * Find all entities with a specific label.
     * @param label The label to search for (e.g., "Person", "Organization")
     */
    fun findByLabel(label: String): List<NamedEntityData>

    // ===========================================
    // CoreSearchOperations Implementation
    // ===========================================

    override fun supportsType(type: String): Boolean =
        type == NamedEntityData::class.java.simpleName ||
            type == SimpleNamedEntityData::class.java.simpleName ||
            type == "Entity"

    @Suppress("UNCHECKED_CAST")
    override fun <T> findById(id: String, clazz: Class<T>): T? =
        findEntityById(id) as T?

    @Suppress("UNCHECKED_CAST")
    override fun <T : Retrievable> findById(id: String, type: String): T? {
        if (!supportsType(type)) {
            loggerFor<EntityRepository>().warn(
                "EntityRepository only supports NamedEntityData, not {}",
                type
            )
            return null
        }
        return findEntityById(id) as T?
    }
}

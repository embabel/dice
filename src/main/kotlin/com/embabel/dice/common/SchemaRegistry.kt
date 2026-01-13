package com.embabel.dice.common

import com.embabel.agent.core.DataDictionary

/**
 * Registry for named schemas (DataDictionary instances).
 * Allows API clients to specify which schema to use for proposition extraction.
 */
interface SchemaRegistry {

    /**
     * Get a schema by name.
     * @param name The schema name
     * @return The schema, or null if not found
     */
    fun get(name: String): DataDictionary?

    /**
     * Get the default schema.
     * @return The default schema
     * @throws IllegalStateException if no default schema is configured
     */
    fun getDefault(): DataDictionary

    /**
     * Get a schema by name, falling back to default if not found.
     * @param name The schema name, or null to use the default
     * @return The schema
     */
    fun getOrDefault(name: String?): DataDictionary =
        name?.let { get(it) } ?: getDefault()

    /**
     * Register a schema with a name.
     * @param name The schema name
     * @param schema The schema to register
     */
    fun register(name: String, schema: DataDictionary)

    /**
     * List all registered schema names.
     */
    fun names(): Set<String>
}


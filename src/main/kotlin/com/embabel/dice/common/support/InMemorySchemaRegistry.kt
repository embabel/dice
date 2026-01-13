package com.embabel.dice.common.support

import com.embabel.agent.core.DataDictionary
import com.embabel.dice.common.SchemaRegistry

/**
 * In-memory implementation of SchemaRegistry.
 * Register schemas at startup and look them up by name at runtime.
 *
 * @param defaultSchema The default schema to use when no name is specified
 * @param namedSchemas Optional map of named schemas
 */
class InMemorySchemaRegistry @JvmOverloads constructor(
    private val defaultSchema: DataDictionary,
    namedSchemas: Map<String, DataDictionary> = emptyMap(),
) : SchemaRegistry {

    private val schemas = namedSchemas.toMutableMap()

    override fun get(name: String): DataDictionary? = schemas[name]

    override fun getDefault(): DataDictionary = defaultSchema

    override fun register(name: String, schema: DataDictionary) {
        schemas[name] = schema
    }

    override fun names(): Set<String> = schemas.keys.toSet()
}
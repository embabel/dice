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
package com.embabel.dice.common.support

import com.embabel.agent.core.DataDictionary
import com.embabel.dice.common.SchemaRegistry

/**
 * In-memory implementation of SchemaRegistry.
 * Register schemas at startup and look them up by name at runtime.
 *
 * Schemas are automatically keyed by their [DataDictionary.name] property.
 *
 * @param defaultSchema The default schema to use when no name is specified
 * @param additionalSchemas Optional additional schemas to register
 */
class InMemorySchemaRegistry @JvmOverloads constructor(
    private val defaultSchema: DataDictionary,
    additionalSchemas: Collection<DataDictionary> = emptyList(),
) : SchemaRegistry {

    private val schemas = mutableMapOf<String, DataDictionary>()

    init {
        // Register default schema by its name
        schemas[defaultSchema.name] = defaultSchema
        // Register additional schemas
        additionalSchemas.forEach { register(it) }
    }

    override fun get(name: String): DataDictionary? = schemas[name]

    override fun getDefault(): DataDictionary = defaultSchema

    override fun register(schema: DataDictionary) {
        schemas[schema.name] = schema
    }

    override fun names(): Set<String> = schemas.keys.toSet()

    override fun all(): Collection<DataDictionary> = schemas.values
}

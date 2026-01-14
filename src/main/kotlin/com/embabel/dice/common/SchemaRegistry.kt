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

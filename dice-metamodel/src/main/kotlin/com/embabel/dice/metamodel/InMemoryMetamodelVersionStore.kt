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
package com.embabel.dice.metamodel

/**
 * Reference [MetamodelVersionStore] that keeps stamps in a list.
 *
 * It is the executable statement of what the contract means, so a durable backend can be held to
 * the same suite of tests. It also lets a host stamp and compare schemas before it has a database,
 * which is most of what the first tier of versioning is for.
 *
 * Nothing here survives the JVM, and two instances know nothing about each other.
 */
class InMemoryMetamodelVersionStore : MetamodelVersionStore {

    private val saved = mutableListOf<MetamodelVersion>()

    /**
     * Upsert on `(schemaName, contentHash)`. A stamp that is already there keeps its place in the
     * write order, so re-saving an old version doesn't make it the latest; the incoming stamp
     * replaces it in place.
     *
     * Everything runs under the list's own lock, so two threads saving the same version can't
     * interleave the search for an existing stamp with the write that lands the new one.
     */
    override fun saveVersion(version: MetamodelVersion) {
        synchronized(saved) {
            val existingIndex = saved.indexOfFirst {
                it.schemaName == version.schemaName && it.contentHash == version.contentHash
            }
            if (existingIndex < 0) saved += version else saved[existingIndex] = version
        }
    }

    override fun latestVersion(schemaName: String): MetamodelVersion? =
        synchronized(saved) { saved.lastOrNull { it.schemaName == schemaName } }

    override fun versionHistory(schemaName: String): List<MetamodelVersion> =
        synchronized(saved) { saved.filter { it.schemaName == schemaName }.reversed() }
}

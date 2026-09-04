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

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.core.DynamicType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Covers the one piece of behaviour the contract itself ships: the default [findVersion], which a
 * backend is free to override with a keyed lookup. A store implementation gets its own tests
 * wherever it lives.
 */
class MetamodelVersionStoreTest {

    /** Minimal store honouring the contract: upsert on (schemaName, contentHash), newest first. */
    private class InMemoryVersionStore : MetamodelVersionStore {

        private val saved = mutableListOf<MetamodelVersion>()

        override fun saveVersion(version: MetamodelVersion) {
            // Idempotent: re-saving an existing version keeps its original position in write order.
            if (saved.none { it.schemaName == version.schemaName && it.contentHash == version.contentHash }) {
                saved.add(version)
            }
        }

        override fun latestVersion(schemaName: String): MetamodelVersion? =
            versionHistory(schemaName).firstOrNull()

        override fun versionHistory(schemaName: String): List<MetamodelVersion> =
            saved.filter { it.schemaName == schemaName }.reversed()
    }

    private fun version(schemaName: String, vararg typeNames: String): MetamodelVersion =
        MetamodelVersion.from(
            DataDictionary.fromDomainTypes(schemaName, typeNames.map { DynamicType(name = it) }),
        )

    @Test
    fun `findVersion returns the stamp with that hash`() {
        val store = InMemoryVersionStore()
        val first = version("app", "Person")
        val second = version("app", "Person", "Company")
        store.saveVersion(first)
        store.saveVersion(second)

        assertEquals(first, store.findVersion("app", first.contentHash))
        assertEquals(second, store.findVersion("app", second.contentHash))
    }

    @Test
    fun `findVersion is scoped to the schema name`() {
        // Two schemas can hold structurally identical versions, because the hash excludes the
        // name, so the lookup has to match on both halves of the key.
        val store = InMemoryVersionStore()
        val mine = version("mine", "Person")
        store.saveVersion(mine)

        assertEquals(mine, store.findVersion("mine", mine.contentHash))
        assertNull(store.findVersion("yours", mine.contentHash))
    }

    @Test
    fun `findVersion returns null for an unknown hash`() {
        val store = InMemoryVersionStore()
        store.saveVersion(version("app", "Person"))

        assertNull(store.findVersion("app", "not-a-hash"))
    }

    @Test
    fun `re-saving a version leaves one record, not two`() {
        val store = InMemoryVersionStore()
        val v = version("app", "Person")
        store.saveVersion(v)
        store.saveVersion(v)

        assertEquals(listOf(v), store.versionHistory("app"))
        assertEquals(v, store.latestVersion("app"))
    }

    @Test
    fun `an empty store has no latest version and an empty history`() {
        val store = InMemoryVersionStore()

        assertNull(store.latestVersion("app"))
        assertEquals(emptyList<MetamodelVersion>(), store.versionHistory("app"))
        assertNull(store.findVersion("app", "anything"))
    }
}

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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Covers behaviour the contract itself ships, independent of any specific backend: the default
 * [findVersion], which a backend is free to override with a keyed lookup, and
 * [InMemoryMetamodelVersionStore]'s reading of the [SweptBaselineStore.sweptVersion] /
 * [SweptBaselineStore.markSwept] pointer. A store implementation gets its own tests wherever it
 * lives.
 *
 * The upsert rules [MetamodelVersionStore.saveVersion] states are checked by
 * `AbstractMetamodelVersionStoreContractTest`, which runs the same suite against
 * [InMemoryMetamodelVersionStore] and the graph-backed store.
 */
class MetamodelVersionStoreTest {

    private fun version(schemaName: String, vararg typeNames: String): MetamodelVersion =
        MetamodelVersion.from(
            DataDictionary.fromDomainTypes(schemaName, typeNames.map { DynamicType(name = it) }),
        )

    @Test
    fun `findVersion returns the stamp with that hash`() {
        val store = InMemoryMetamodelVersionStore()
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
        val store = InMemoryMetamodelVersionStore()
        val mine = version("mine", "Person")
        store.saveVersion(mine)

        assertEquals(mine, store.findVersion("mine", mine.contentHash))
        assertNull(store.findVersion("yours", mine.contentHash))
    }

    @Test
    fun `findVersion returns null for an unknown hash`() {
        val store = InMemoryMetamodelVersionStore()
        store.saveVersion(version("app", "Person"))

        assertNull(store.findVersion("app", "not-a-hash"))
    }

    @Test
    fun `re-saving a version leaves one record, not two`() {
        val store = InMemoryMetamodelVersionStore()
        val v = version("app", "Person")
        store.saveVersion(v)
        store.saveVersion(v)

        assertEquals(listOf(v), store.versionHistory("app"))
        assertEquals(v, store.latestVersion("app"))
    }

    @Test
    fun `an empty store has no latest version and an empty history`() {
        val store = InMemoryMetamodelVersionStore()

        assertNull(store.latestVersion("app"))
        assertEquals(emptyList<MetamodelVersion>(), store.versionHistory("app"))
        assertNull(store.findVersion("app", "anything"))
    }

    /**
     * [SweptBaselineStore.sweptVersion] tracks a different fact than
     * [MetamodelVersionStore.latestVersion]: which declaration the last completed sweep actually
     * reconciled against, where `latestVersion` answers which stamp arrived most recently in write
     * order.
     */
    @Nested
    inner class SweptVersion {

        @Test
        fun `nothing has ever been swept for a schema no one has marked`() {
            val store = InMemoryMetamodelVersionStore()
            store.saveVersion(version("app", "Person"))

            assertNull(store.sweptVersion("app"), "a plain stamp save is not a completed sweep")
        }

        @Test
        fun `markSwept records the version sweptVersion then answers`() {
            val store = InMemoryMetamodelVersionStore()
            val v = version("app", "Person")

            store.markSwept(v)

            assertEquals(v, store.sweptVersion("app"))
        }

        @Test
        fun `markSwept also saves the version into ordinary history`() {
            val store = InMemoryMetamodelVersionStore()
            val v = version("app", "Person")

            store.markSwept(v)

            assertEquals(v, store.latestVersion("app"), "a swept version is a real stamp too")
            assertEquals(listOf(v), store.versionHistory("app"))
        }

        @Test
        fun `sweptVersion is scoped to its schema name`() {
            val store = InMemoryMetamodelVersionStore()
            store.markSwept(version("mine", "Person"))

            assertNotNull(store.sweptVersion("mine"))
            assertNull(store.sweptVersion("yours"))
        }

        @Test
        fun `sweptVersion tracks the reconciled pointer, not write order, across a reverted declaration`() {
            // A -> B (swept) -> A again (only re-saved, never re-swept) -> A swept for real.
            // latestVersion answers B the whole way through, because re-saving A keeps its
            // original write-order position; sweptVersion must answer A once markSwept says so.
            val store = InMemoryMetamodelVersionStore()
            val a = version("app", "Person")
            val b = version("app", "Person", "Company")
            store.markSwept(a)
            store.markSwept(b)
            store.saveVersion(a)

            assertEquals(b, store.latestVersion("app"), "sanity: re-saving A doesn't move latestVersion")
            assertEquals(b, store.sweptVersion("app"), "sanity: B is still the reconciled baseline")

            store.markSwept(a)

            assertEquals(a, store.sweptVersion("app"), "the pointer moved to what was actually swept")
            assertEquals(b, store.latestVersion("app"), "latestVersion is unaffected -- a different question")
        }
    }

    /**
     * A plain [MetamodelVersionStore] has no swept baseline at all, and there is no forwarding
     * default that would give it one. That absence is the fix for a real hazard: a default answering
     * [MetamodelVersionStore.latestVersion] moved on every ordinary stamp, so a store that never
     * asked for baseline tracking still handed one out, and a dry, scoped or interrupted write
     * looked to the next check like a finished sweep.
     */
    @Nested
    inner class NoBaselineWithoutTheCapability {

        @Test
        fun `a plain version store is no SweptBaselineStore`() {
            val store: MetamodelVersionStore = MinimalStore()
            store.saveVersion(version("app", "Person"))

            assertFalse(
                store is SweptBaselineStore,
                "stamping must never be enough to make a store look like it tracks a baseline",
            )
        }

        @Test
        fun `the in-memory store does declare the capability`() {
            val store: MetamodelVersionStore = InMemoryMetamodelVersionStore()

            assertTrue(store is SweptBaselineStore)
        }

        @Test
        fun `saving a stamp many times leaves the baseline where it was`() {
            val store = InMemoryMetamodelVersionStore()
            val swept = version("app", "Person")
            store.markSwept(swept)

            repeat(5) { store.saveVersion(version("app", "Person", "Company")) }

            assertEquals(
                swept,
                store.sweptVersion("app"),
                "a completed sweep is the only thing that may move the baseline",
            )
        }
    }

    /** Implements only the four original members; everything else comes from the interface. */
    private class MinimalStore : MetamodelVersionStore {
        private val versions = mutableListOf<MetamodelVersion>()

        override fun saveVersion(version: MetamodelVersion) {
            versions.removeIf { it.schemaName == version.schemaName && it.contentHash == version.contentHash }
            versions.add(0, version)
        }

        override fun latestVersion(schemaName: String): MetamodelVersion? =
            versions.firstOrNull { it.schemaName == schemaName }

        override fun versionHistory(schemaName: String): List<MetamodelVersion> =
            versions.filter { it.schemaName == schemaName }
    }
}

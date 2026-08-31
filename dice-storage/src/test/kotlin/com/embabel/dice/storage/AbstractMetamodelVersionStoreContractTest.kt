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
package com.embabel.dice.storage

import com.embabel.dice.metamodel.MetamodelVersion
import com.embabel.dice.metamodel.MetamodelVersionStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Cross-backend contract for [MetamodelVersionStore.saveVersion]'s upsert. Each subclass supplies a
 * store and inherits the whole suite, so a backend that disagrees with the in-memory reference fails
 * at authoring time.
 *
 * The rules here matter because the drift check re-stamps its schema on every pass. A store that
 * treated each of those re-stamps as a new record would fill the history with copies of one version,
 * and one that moved a re-stamped version to the front would report the wrong stamp as the latest.
 */
abstract class AbstractMetamodelVersionStoreContractTest {

    /** A store holding nothing for the schema names below. */
    protected abstract fun store(): MetamodelVersionStore

    /** A stamp of one entity type. */
    private fun version(
        schemaName: String,
        typeName: String = "Person",
    ): MetamodelVersion = MetamodelVersion(
        schemaName = schemaName,
        entityTypeNames = listOf(typeName),
        entityTypeLabels = emptyMap(),
        entityTypeProperties = emptyMap(),
        relationshipNames = emptyList(),
        entityTypeAliases = emptyMap(),
    )

    // ---- the upsert ----

    @Test
    fun `re-saving a version leaves one record`() {
        val store = store()
        val schemaName = "contract-idempotent"
        val stamp = version(schemaName)

        store.saveVersion(stamp)
        store.saveVersion(stamp)

        assertEquals(listOf(stamp), store.versionHistory(schemaName))
        assertEquals(stamp, store.latestVersion(schemaName))
    }

    @Test
    fun `a re-save leaves the stamp where it was in the history`() {
        // The write lands on an existing key, so it has to behave like any other re-save: content
        // refreshed, position in the write order untouched.
        val store = store()
        val schemaName = "contract-order"
        val first = version(schemaName, "First")
        val second = version(schemaName, "Second")
        store.saveVersion(first)
        store.saveVersion(second)

        store.saveVersion(version(schemaName, "First"))

        assertEquals(
            listOf("Second", "First"),
            store.versionHistory(schemaName).map { it.entityTypeNames.single() },
            "a re-save must not make an old version the latest",
        )
        assertEquals(first, store.findVersion(schemaName, first.contentHash))
    }
}

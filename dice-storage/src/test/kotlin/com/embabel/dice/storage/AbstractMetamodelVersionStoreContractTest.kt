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
import com.embabel.dice.metamodel.StampProvenance
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Cross-backend contract for [MetamodelVersionStore.saveVersion]'s upsert, and for the two
 * provenance rules it states. Each subclass supplies a store and inherits the whole suite, so a
 * backend that disagrees with the in-memory reference fails at authoring time.
 *
 * The provenance rules exist because the drift check re-stamps its schema on every pass and carries
 * no provenance when it does. A store that overwrote both fields on every save would blank the
 * recorded cause within a deploy cycle, and one that stamped its own identity into `lastStamped`
 * would launder it, so both halves get a test here.
 */
abstract class AbstractMetamodelVersionStoreContractTest {

    /** A store holding nothing for the schema names below. */
    protected abstract fun store(): MetamodelVersionStore

    /**
     * A stamp of one entity type. Provenance is never hashed, so two calls differing only in
     * [origin] or [lastStamped] land on the same natural key, which is what a re-stamp is.
     */
    private fun version(
        schemaName: String,
        typeName: String = "Person",
        origin: StampProvenance? = null,
        lastStamped: StampProvenance? = null,
    ): MetamodelVersion = MetamodelVersion(
        schemaName = schemaName,
        entityTypeNames = listOf(typeName),
        entityTypeLabels = emptyMap(),
        entityTypeProperties = emptyMap(),
        relationshipNames = emptyList(),
        entityTypeAliases = emptyMap(),
        origin = origin,
        lastStamped = lastStamped,
    )

    // ---- the upsert the provenance rules sit on ----

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

    // ---- provenance is written and read back ----

    @Test
    fun `a first save keeps the provenance it was given`() {
        val store = store()
        val schemaName = "contract-provenance-first"
        val cause = StampProvenance("deploy-pipeline", "release-42")

        store.saveVersion(version(schemaName, origin = cause, lastStamped = cause))

        val reloaded = store.latestVersion(schemaName)!!
        assertEquals(cause, reloaded.origin)
        assertEquals(cause, reloaded.lastStamped)
    }

    @Test
    fun `a provenance field the host left unset stays unset`() {
        val store = store()
        val schemaName = "contract-provenance-partial"

        store.saveVersion(version(schemaName, origin = StampProvenance(actor = "operator")))

        val reloaded = store.latestVersion(schemaName)!!
        assertEquals("operator", reloaded.origin!!.actor)
        assertNull(reloaded.origin!!.trigger)
        assertNull(reloaded.lastStamped)
    }

    @Test
    fun `a provenance with neither field set is still a provenance`() {
        // StampProvenance() says "the host recorded a cause and named nothing in it", which is not
        // the same as recording no cause at all. A store that flattened the two would answer the
        // question "was this stamp taken by something that reports provenance" wrongly.
        val store = store()
        val schemaName = "contract-provenance-empty"

        store.saveVersion(version(schemaName, origin = StampProvenance()))

        val reloaded = store.latestVersion(schemaName)!!
        assertNotNull(reloaded.origin, "an empty provenance must not read back as no provenance")
        assertNull(reloaded.origin!!.actor)
        assertNull(reloaded.origin!!.trigger)
    }

    // ---- the two rules ----

    @Test
    fun `a re-stamp carrying no provenance leaves both fields alone`() {
        // Every routine drift-check re-stamp arrives like this.
        val store = store()
        val schemaName = "contract-provenance-null-restamp"
        val cause = StampProvenance("operator", "first-stamp")
        store.saveVersion(version(schemaName, origin = cause, lastStamped = cause))

        store.saveVersion(version(schemaName))

        val reloaded = store.latestVersion(schemaName)!!
        assertEquals(cause, reloaded.origin, "a null re-stamp must not erase the original cause")
        assertEquals(cause, reloaded.lastStamped, "nor the most recent one")
    }

    @Test
    fun `a re-stamp carrying provenance keeps origin and moves lastStamped`() {
        val store = store()
        val schemaName = "contract-provenance-restamp"
        val first = StampProvenance("bootstrap", "first-boot")
        val second = StampProvenance("operator", "manual-restamp")
        store.saveVersion(version(schemaName, origin = first, lastStamped = first))

        store.saveVersion(version(schemaName, origin = second, lastStamped = second))

        val reloaded = store.latestVersion(schemaName)!!
        assertEquals(first, reloaded.origin, "origin is first-write-wins")
        assertEquals(second, reloaded.lastStamped, "lastStamped follows the newest save that names one")
    }

    @Test
    fun `origin is taken by the first save that carries one and not moved after`() {
        val store = store()
        val schemaName = "contract-provenance-first-write-wins"

        store.saveVersion(version(schemaName))
        assertNull(store.latestVersion(schemaName)!!.origin, "nothing was supplied, so nothing is recorded")

        val backfilled = StampProvenance("operator", "backfill")
        store.saveVersion(version(schemaName, origin = backfilled))
        assertEquals(backfilled, store.latestVersion(schemaName)!!.origin, "a stamp with no origin takes one")

        store.saveVersion(version(schemaName, origin = StampProvenance("someone-else", "later")))
        assertEquals(backfilled, store.latestVersion(schemaName)!!.origin, "and never gives it up again")
    }

    @Test
    fun `lastStamped moves without an origin ever being supplied`() {
        val store = store()
        val schemaName = "contract-provenance-last-only"
        store.saveVersion(version(schemaName, lastStamped = StampProvenance("first", "run-1")))

        store.saveVersion(version(schemaName, lastStamped = StampProvenance("second", "run-2")))

        val reloaded = store.latestVersion(schemaName)!!
        assertNull(reloaded.origin)
        assertEquals(StampProvenance("second", "run-2"), reloaded.lastStamped)
    }

    // ---- provenance belongs to the stamp ----

    @Test
    fun `each stamp of a schema carries its own provenance`() {
        val store = store()
        val schemaName = "contract-provenance-per-stamp"
        val first = version(schemaName, "First", origin = StampProvenance("first-cause"))
        val second = version(schemaName, "Second", origin = StampProvenance("second-cause"))

        store.saveVersion(first)
        store.saveVersion(second)

        assertEquals(StampProvenance("first-cause"), store.findVersion(schemaName, first.contentHash)!!.origin)
        assertEquals(StampProvenance("second-cause"), store.findVersion(schemaName, second.contentHash)!!.origin)
    }

    @Test
    fun `a re-stamp for provenance leaves the stamp where it was in the history`() {
        // Provenance is not hashed, so this write lands on an existing key. It has to behave like
        // any other re-save: content refreshed, position in the write order untouched.
        val store = store()
        val schemaName = "contract-provenance-order"
        val first = version(schemaName, "First", origin = StampProvenance("original"))
        val second = version(schemaName, "Second")
        store.saveVersion(first)
        store.saveVersion(second)

        store.saveVersion(version(schemaName, "First", lastStamped = StampProvenance("re-stamped")))

        assertEquals(
            listOf("Second", "First"),
            store.versionHistory(schemaName).map { it.entityTypeNames.single() },
            "a provenance re-stamp must not make an old version the latest",
        )
        val reloadedFirst = store.findVersion(schemaName, first.contentHash)!!
        assertEquals(StampProvenance("original"), reloadedFirst.origin)
        assertEquals(StampProvenance("re-stamped"), reloadedFirst.lastStamped)
    }
}

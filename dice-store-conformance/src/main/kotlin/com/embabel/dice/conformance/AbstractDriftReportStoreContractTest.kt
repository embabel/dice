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
package com.embabel.dice.conformance

import com.embabel.agent.core.ContextId
import com.embabel.dice.metamodel.DriftReport
import com.embabel.dice.metamodel.DriftReportStore
import com.embabel.dice.metamodel.MetamodelChange
import com.embabel.dice.metamodel.MetamodelDiff
import com.embabel.dice.metamodel.MetamodelVersion
import org.jetbrains.annotations.ApiStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Cross-backend contract for [DriftReportStore]. It checks what the interface documents: that a
 * report reads back whole, that reads come back newest first and stay bounded, that each read
 * sees only its own scope, and that saving the same observation again replaces it. Each subclass
 * supplies an empty store and inherits the whole suite.
 *
 * Every report in one test has its own capture instant, in whole minutes. The interface does not
 * order reports that share an instant, so the suite never relies on that order.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
abstract class AbstractDriftReportStoreContractTest {

    /** A store with no reports. */
    protected abstract fun store(): DriftReportStore

    private val schemaName = "test-schema"
    private val contextA = ContextId("ctx-a")
    private val contextB = ContextId("ctx-b")
    private val epoch = Instant.parse("2026-01-01T00:00:00Z")

    /** A report captured [minute] minutes after the epoch, optionally scoped to a context. */
    private fun save(
        store: DriftReportStore,
        minute: Long,
        contextId: ContextId? = null,
        schema: String = schemaName,
    ): DriftReport {
        val report = DriftReport(
            schemaName = schema,
            versionHash = "hash-$minute",
            driftedEntityTypes = setOf("Ghost$minute"),
            driftedRelationshipTypes = emptySet(),
            capturedAt = epoch.plusSeconds(minute * 60),
            contextId = contextId,
        )
        store.saveDriftReport(report)
        return report
    }

    private fun version(schema: String, typeName: String): MetamodelVersion = MetamodelVersion(
        schemaName = schema,
        entityTypeNames = listOf(typeName),
        entityTypeLabels = emptyMap(),
        entityTypeProperties = emptyMap(),
        relationshipNames = emptyList(),
        entityTypeAliases = emptyMap(),
    )

    @Test
    @DisplayName("a global report reads back with every field")
    open fun aGlobalReportReadsBackWithEveryField() {
        val store = store()
        val report = DriftReport(
            schemaName = schemaName,
            versionHash = "hash-global",
            driftedEntityTypes = setOf("GhostA", "GhostB"),
            driftedRelationshipTypes = setOf("HAUNTS"),
            capturedAt = epoch.plusSeconds(60),
        )

        store.saveDriftReport(report)

        assertEquals(listOf(report), store.driftReports(schemaName, limit = 10))
    }

    @Test
    @DisplayName("a context report reads back with its context")
    open fun aContextReportReadsBackWithItsContext() {
        val store = store()
        val report = DriftReport(
            schemaName = schemaName,
            versionHash = "hash-context",
            driftedEntityTypes = setOf("GhostA"),
            driftedRelationshipTypes = emptySet(),
            capturedAt = epoch.plusSeconds(60),
            contextId = contextA,
        )

        store.saveDriftReport(report)

        assertEquals(listOf(report), store.driftReportsInContext(schemaName, contextA, limit = 10))
    }

    @Test
    @DisplayName("a report reads back with its declared diff")
    open fun aReportReadsBackWithItsDeclaredDiff() {
        val store = store()
        val declaredDiff = MetamodelDiff(
            version(schemaName, "Person"),
            version(schemaName, "Customer"),
            listOf(MetamodelChange.EntityTypeRemoved("Person")),
        )
        val report = DriftReport(
            schemaName = schemaName,
            versionHash = "hash-diff",
            driftedEntityTypes = emptySet(),
            driftedRelationshipTypes = emptySet(),
            capturedAt = epoch.plusSeconds(60),
            declaredDiff = declaredDiff,
        )

        store.saveDriftReport(report)

        assertEquals(declaredDiff, store.driftReports(schemaName, limit = 10).single().declaredDiff)
    }

    @Test
    @DisplayName("reads come back newest first")
    open fun readsComeBackNewestFirst() {
        val store = store()
        save(store, 1)
        save(store, 3)
        save(store, 2)

        val reports = store.driftReports(schemaName, limit = 10)

        assertEquals(listOf("hash-3", "hash-2", "hash-1"), reports.map { it.versionHash })
    }

    @Test
    @DisplayName("a limit returns the newest reports")
    open fun aLimitReturnsTheNewestReports() {
        val store = store()
        (1L..5L).forEach { save(store, it) }

        val reports = store.driftReports(schemaName, limit = 2)

        assertEquals(2, reports.size)
        assertEquals(listOf("hash-5", "hash-4"), reports.map { it.versionHash })
    }

    @Test
    @DisplayName("since bounds the window from below and includes its own instant")
    open fun sinceBoundsTheWindowFromBelowAndIncludesItsOwnInstant() {
        val store = store()
        (1L..4L).forEach { save(store, it) }

        val reports = store.driftReports(schemaName, limit = 10, since = epoch.plusSeconds(2 * 60))

        assertEquals(listOf("hash-4", "hash-3", "hash-2"), reports.map { it.versionHash })
    }

    @Test
    @DisplayName("a limit that is not positive is rejected")
    open fun aLimitThatIsNotPositiveIsRejected() {
        val store = store()
        save(store, 1, contextA)

        assertThrows(IllegalArgumentException::class.java) { store.driftReports(schemaName, limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { store.driftReports(schemaName, limit = -1) }
        assertThrows(IllegalArgumentException::class.java) { store.globalDriftReports(schemaName, limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { store.globalDriftReports(schemaName, limit = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            store.driftReportsInContext(schemaName, contextA, limit = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.driftReportsInContext(schemaName, contextA, limit = -1)
        }
    }

    @Test
    @DisplayName("each read sees only its own scope")
    open fun eachReadSeesOnlyItsOwnScope() {
        val store = store()
        val global = save(store, 1)
        val inA = save(store, 2, contextA)
        val inB = save(store, 3, contextB)

        assertEquals(
            listOf(inB, inA, global),
            store.driftReports(schemaName, limit = 10),
            "the unscoped read sees everything",
        )
        assertEquals(listOf(global), store.globalDriftReports(schemaName, limit = 10))
        assertEquals(listOf(inA), store.driftReportsInContext(schemaName, contextA, limit = 10))
        assertEquals(listOf(inB), store.driftReportsInContext(schemaName, contextB, limit = 10))
    }

    @Test
    @DisplayName("the scope is applied before the limit")
    open fun theScopeIsAppliedBeforeTheLimit() {
        // A store that reads a limited page first and filters it afterwards would find no global
        // report here. The newest three reports are all context-scoped, so the one global report
        // would be cut off before the filter runs.
        val store = store()
        val global = save(store, 1)
        save(store, 2, contextA)
        save(store, 3, contextA)
        save(store, 4, contextA)

        val globals = store.globalDriftReports(schemaName, limit = 3)

        assertEquals(listOf(global), globals, "a scoped read must push its scope into the query")
    }

    @Test
    @DisplayName("reports for another schema are never returned")
    open fun reportsForAnotherSchemaAreNeverReturned() {
        val store = store()
        save(store, 1, schema = "other-schema")

        assertTrue(store.driftReports(schemaName, limit = 10).isEmpty())
    }

    @Test
    @DisplayName("saving the same observation again replaces it")
    open fun savingTheSameObservationAgainReplacesIt() {
        val store = store()
        val first = save(store, 1)
        val corrected = DriftReport(
            schemaName = first.schemaName,
            versionHash = first.versionHash,
            driftedEntityTypes = setOf("GhostA", "GhostB"),
            driftedRelationshipTypes = setOf("HAUNTS"),
            capturedAt = first.capturedAt,
            contextId = first.contextId,
        )

        store.saveDriftReport(corrected)

        val reports = store.driftReports(schemaName, limit = 10)
        assertEquals(1, reports.size, "the same natural key holds one record")
        assertEquals(setOf("GhostA", "GhostB"), reports.single().driftedEntityTypes)
    }

    @Test
    @DisplayName("checks of one schema at different instants are separate reports")
    open fun checksOfOneSchemaAtDifferentInstantsAreSeparateReports() {
        val store = store()
        save(store, 1)
        save(store, 2)

        assertEquals(2, store.driftReports(schemaName, limit = 10).size)
    }
}

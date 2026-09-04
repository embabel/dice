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

import com.embabel.agent.core.ContextId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Pins the [DriftReportStore] contract against [InMemoryDriftReportStore], the reference reading of
 * it. A Drivine-backed store lands in a later slice and has to answer these same questions the same
 * way, in Cypher rather than in memory.
 */
class DriftReportStoreTest {

    private val schemaName = "test-schema"
    private val contextA = ContextId("ctx-a")
    private val contextB = ContextId("ctx-b")
    private val epoch = Instant.parse("2026-01-01T00:00:00Z")

    private lateinit var store: DriftReportStore

    @BeforeEach
    fun setUp() {
        store = InMemoryDriftReportStore()
    }

    /** A report captured [minute] minutes after the epoch, optionally scoped to a context. */
    private fun save(minute: Long, contextId: ContextId? = null, schema: String = schemaName): DriftReport {
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

    @Test
    fun `reads come back newest first`() {
        save(1)
        save(3)
        save(2)

        val reports = store.driftReports(schemaName, limit = 10)

        assertEquals(listOf("hash-3", "hash-2", "hash-1"), reports.map { it.versionHash })
    }

    @Test
    fun `a limit returns the newest page, not an arbitrary one`() {
        (1L..5L).forEach { save(it) }

        val reports = store.driftReports(schemaName, limit = 2)

        assertEquals(2, reports.size)
        assertEquals(listOf("hash-5", "hash-4"), reports.map { it.versionHash })
    }

    @Test
    fun `since bounds the window from below, inclusively`() {
        (1L..4L).forEach { save(it) }

        val reports = store.driftReports(schemaName, limit = 10, since = epoch.plusSeconds(2 * 60))

        assertEquals(listOf("hash-4", "hash-3", "hash-2"), reports.map { it.versionHash })
    }

    @Test
    fun `a non-positive limit is rejected rather than quietly meaning everything`() {
        save(1)

        assertThrows(IllegalArgumentException::class.java) { store.driftReports(schemaName, limit = 0) }
        assertThrows(IllegalArgumentException::class.java) { store.driftReports(schemaName, limit = -1) }
    }

    @Test
    fun `each read sees only its own scope`() {
        val global = save(1)
        val inA = save(2, contextA)
        val inB = save(3, contextB)

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
    fun `scoping happens before limiting, not after`() {
        // Guards against a store that reads a limited page and then filters it. Such a store would
        // answer "no global drift" here, because the newest three reports are all context-scoped and
        // the one global report never survives to the filter.
        val global = save(1)
        save(2, contextA)
        save(3, contextA)
        save(4, contextA)

        val globals = store.globalDriftReports(schemaName, limit = 3)

        assertEquals(listOf(global), globals, "a scoped read must push its scope into the query")
    }

    @Test
    fun `reports for another schema are never returned`() {
        save(1, schema = "other-schema")

        assertTrue(store.driftReports(schemaName, limit = 10).isEmpty())
    }

    @Test
    fun `re-saving the same observation updates it in place`() {
        val first = save(1)
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
        assertEquals(1, reports.size, "same natural key means the same record, not a second one")
        assertEquals(setOf("GhostA", "GhostB"), reports.single().driftedEntityTypes)
    }

    @Test
    fun `checks of the same schema at different instants are separate records`() {
        save(1)
        save(2)

        assertEquals(2, store.driftReports(schemaName, limit = 10).size)
    }
}

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

import com.embabel.dice.projection.lineage.CollectorOutcome
import com.embabel.dice.projection.lineage.CollectorRecord
import com.embabel.dice.projection.lineage.CollectorRecordStore
import com.embabel.dice.projection.lineage.CollectorRun
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.spi.MarkReason
import org.jetbrains.annotations.ApiStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Cross-backend contract for [CollectorRecordStore]. It checks what the interface documents: records
 * and run headers read back with the values they were saved with, finders return only the matching
 * rows, and a replayed record keeps its undo stamp. Each subclass supplies an empty store and
 * inherits the whole suite.
 *
 * The suite compares records as sets. It never counts duplicates after a replay, and it never checks
 * the order of records or of finder results. It does check the order of [CollectorRecordStore.runs],
 * since the interface documents insertion order for it.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
abstract class AbstractCollectorRecordStoreContractTest {

    /** A store with no records and no runs. */
    protected abstract fun store(): CollectorRecordStore

    private val t0 = Instant.parse("2026-01-01T00:00:00Z")

    /**
     * Returns the instant a number of seconds after the fixture start.
     *
     * @param seconds How many seconds after the fixture start.
     * @return The instant.
     */
    private fun at(seconds: Long): Instant = t0.plusSeconds(seconds)

    /**
     * Builds a stale-reason record marked by the `stale` strategy.
     *
     * @param propositionId The proposition the record marks.
     * @param runId The run that wrote the record.
     * @param at When the record was written. Defaults to the fixture start.
     * @param undoneAt When the record was undone, or null if it still stands.
     * @return The record.
     */
    private fun record(
        propositionId: String,
        runId: String,
        at: Instant = t0,
        undoneAt: Instant? = null,
    ): CollectorRecord = CollectorRecord(
        propositionId = propositionId,
        reason = MarkReason.Stale,
        outcome = CollectorOutcome.MARKED,
        strategyName = "stale",
        runId = runId,
        at = at,
        undoneAt = undoneAt,
    )

    @Test
    @DisplayName("a new store holds no records and no runs")
    open fun aNewStoreHoldsNoRecordsAndNoRuns() {
        val store = store()

        assertTrue(store.all().isEmpty())
        assertTrue(store.runs().isEmpty())
    }

    @Test
    @DisplayName("a record reads back with every field")
    open fun aRecordReadsBackWithEveryField() {
        val store = store()
        val record = CollectorRecord(
            propositionId = "p-1",
            reason = MarkReason.Duplicate("p-survivor"),
            outcome = CollectorOutcome.TRANSITIONED,
            strategyName = "duplicate",
            runId = "run-1",
            at = at(1),
            previousStatus = PropositionStatus.ACTIVE,
            newStatus = PropositionStatus.SUPERSEDED,
            mergedIntoId = "p-survivor",
            undoneAt = null,
        )

        store.record(record)

        assertEquals(listOf(record), store.all())
    }

    @Test
    @DisplayName("findByProposition returns only the records of that proposition")
    open fun findByPropositionReturnsOnlyThatPropositionsRecords() {
        val store = store()
        val first = record("p-1", "run-1", at(1))
        val second = record("p-2", "run-1", at(2))
        val third = record("p-1", "run-2", at(3))
        store.record(first)
        store.record(second)
        store.record(third)

        assertEquals(setOf(first, third), store.findByProposition("p-1").toSet())
    }

    @Test
    @DisplayName("findByRun returns only the records of that run")
    open fun findByRunReturnsOnlyThatRunsRecords() {
        val store = store()
        val first = record("p-1", "run-1", at(1))
        val second = record("p-2", "run-1", at(2))
        val third = record("p-1", "run-2", at(3))
        store.record(first)
        store.record(second)
        store.record(third)

        assertEquals(setOf(first, second), store.findByRun("run-1").toSet())
    }

    @Test
    @DisplayName("an unknown proposition or run has no records")
    open fun anUnknownPropositionOrRunHasNoRecords() {
        val store = store()
        store.record(record("p-1", "run-1", at(1)))

        assertTrue(store.findByProposition("p-unknown").isEmpty())
        assertTrue(store.findByRun("run-unknown").isEmpty())
    }

    @Test
    @DisplayName("a run header reads back by its id")
    open fun aRunHeaderReadsBackByItsId() {
        val store = store()
        val run = CollectorRun(
            runId = "run-1",
            startedAt = at(1),
            finishedAt = at(5),
            dryRun = true,
        )

        store.recordRun(run)

        assertEquals(run, store.findRun("run-1"))
    }

    @Test
    @DisplayName("an unknown run id has no header")
    open fun anUnknownRunIdHasNoHeader() {
        val store = store()
        store.recordRun(CollectorRun(runId = "run-1", startedAt = at(1)))

        assertNull(store.findRun("run-unknown"))
    }

    @Test
    @DisplayName("runs come back in the order they were recorded")
    open fun runsComeBackInTheOrderTheyWereRecorded() {
        val store = store()
        store.recordRun(CollectorRun(runId = "run-1", startedAt = at(2)))
        store.recordRun(CollectorRun(runId = "run-2", startedAt = at(3)))
        store.recordRun(CollectorRun(runId = "run-3", startedAt = at(1)))

        assertEquals(listOf("run-1", "run-2", "run-3"), store.runs().map { it.runId })
    }

    @Test
    @DisplayName("a run with no records keeps its header")
    open fun aRunWithNoRecordsKeepsItsHeader() {
        val store = store()
        val run = CollectorRun(runId = "run-1", startedAt = at(1), finishedAt = at(2))

        store.recordRun(run)

        assertEquals(run, store.findRun("run-1"))
        assertTrue(store.findByRun("run-1").isEmpty())
    }

    @Test
    @DisplayName("replaying a record keeps its undo stamp")
    open fun replayingARecordKeepsItsUndoStamp() {
        val store = store()
        val stamped = record("p-1", "run-1", at(1), undoneAt = at(4))
        val replayed = stamped.copy(undoneAt = null)

        store.record(stamped)
        store.record(replayed)

        assertTrue(store.findByProposition("p-1").any { it.undoneAt == at(4) })
        assertTrue(store.findByRun("run-1").any { it.undoneAt == at(4) })
    }
}

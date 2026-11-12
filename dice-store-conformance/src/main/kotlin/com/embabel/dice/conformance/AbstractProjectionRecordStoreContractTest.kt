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

import com.embabel.dice.projection.lineage.ProjectionLifecycle
import com.embabel.dice.projection.lineage.ProjectionRecord
import com.embabel.dice.projection.lineage.ProjectionRecordStore
import org.jetbrains.annotations.ApiStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Cross-backend contract for [ProjectionRecordStore]. It checks the behaviour the interface documents:
 * which records each finder returns, what a record keeps when it is read back, and how records are
 * marked stale. Each subclass supplies an empty store and inherits the whole suite.
 *
 * Contract:
 * - E1 A record reads back with every field it was saved with.
 * - E2 Each finder returns exactly the records whose key matches, whatever their lifecycle.
 * - E3 A key that no record carries finds no records.
 * - E4 findStale returns exactly the records in the STALE lifecycle.
 * - E5 markStaleByProposition marks every record of the proposition STALE and returns how many changed.
 * - E6 markStaleByProposition returns 0 when no record changes.
 *
 * A store that keeps the interface default for [ProjectionRecordStore.markStaleByProposition] fails
 * the case that marks records. The default exists for older implementations, so a current store
 * should override it.
 *
 * The suite never records the same proposition, run and target twice. It does not check the order
 * of a finder's results, because the interface does not define one.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
abstract class AbstractProjectionRecordStoreContractTest {

    /** A fresh, empty store for the test about to run. */
    protected abstract fun store(): ProjectionRecordStore

    private val t0 = Instant.parse("2026-01-01T00:00:00Z")

    private fun record(
        propositionId: String,
        target: String,
        runId: String,
        lifecycle: ProjectionLifecycle = ProjectionLifecycle.PROJECTED,
        targetRef: String? = null,
        contextId: String = "",
    ): ProjectionRecord = ProjectionRecord(
        propositionId = propositionId,
        target = target,
        targetRef = targetRef,
        lifecycle = lifecycle,
        runId = runId,
        at = t0,
        contextId = contextId,
    )

    @Test
    @DisplayName("a new store holds no records")
    open fun aNewStoreHoldsNoRecords() {
        val store = store()

        assertTrue(store.all().isEmpty())
        assertTrue(store.findStale().isEmpty())
    }

    @Test
    @DisplayName("a record reads back with every field")
    open fun aRecordReadsBackWithEveryField() {
        val store = store()
        val saved = ProjectionRecord(
            propositionId = "p1",
            target = "graph",
            targetRef = "node-42",
            lifecycle = ProjectionLifecycle.ADOPTED,
            runId = "run-1",
            at = t0,
            reason = "matched existing entity",
            contextId = "projection-ctx-a",
        )
        store.record(saved)

        assertEquals(saved, store.all().single())
    }

    @Test
    @DisplayName("findByProposition returns only the records of that proposition")
    open fun findByPropositionReturnsOnlyThatPropositionsRecords() {
        val store = store()
        val p1Graph = record("p-1", "graph", "run-1")
        val p1Prolog = record("p-1", "prolog", "run-1")
        store.record(p1Graph)
        store.record(p1Prolog)
        store.record(record("p-2", "graph", "run-1"))

        assertEquals(setOf(p1Graph, p1Prolog), store.findByProposition("p-1").toSet())
    }

    @Test
    @DisplayName("findByTarget returns only the records of that target")
    open fun findByTargetReturnsOnlyThatTargetsRecords() {
        val store = store()
        val p1Graph = record("p-1", "graph", "run-1")
        val p2Graph = record("p-2", "graph", "run-1")
        store.record(p1Graph)
        store.record(p2Graph)
        store.record(record("p-1", "prolog", "run-1"))

        assertEquals(setOf(p1Graph, p2Graph), store.findByTarget("graph").toSet())
    }

    @Test
    @DisplayName("findByContext returns only the records of that context")
    open fun findByContextReturnsOnlyThatContextsRecords() {
        val store = store()
        val ctxA1 = record("p-1", "graph", "run-1", contextId = "projection-ctx-a")
        val ctxA2 = record("p-3", "prolog", "run-1", contextId = "projection-ctx-a")
        store.record(ctxA1)
        store.record(ctxA2)
        store.record(record("p-2", "graph", "run-1", contextId = "projection-ctx-b"))

        assertEquals(setOf(ctxA1, ctxA2), store.findByContext("projection-ctx-a").toSet())
    }

    @Test
    @DisplayName("findByRun returns only the records of that run")
    open fun findByRunReturnsOnlyThatRunsRecords() {
        val store = store()
        val run1Graph = record("p-1", "graph", "run-1")
        val run1Other = record("p-2", "graph", "run-1")
        store.record(run1Graph)
        store.record(run1Other)
        store.record(record("p-1", "prolog", "run-2"))

        assertEquals(setOf(run1Graph, run1Other), store.findByRun("run-1").toSet())
    }

    @Test
    @DisplayName("findByTargetRef returns the records that point at that artifact")
    open fun findByTargetRefReturnsTheRecordsThatPointAtThatArtifact() {
        val store = store()
        val adopted = record("p-1", "graph", "run-1", ProjectionLifecycle.ADOPTED, targetRef = "node-42")
        val projected = record("p-2", "graph", "run-1", ProjectionLifecycle.PROJECTED, targetRef = "node-42")
        store.record(adopted)
        store.record(projected)
        store.record(record("p-3", "graph", "run-1", ProjectionLifecycle.PROJECTED, targetRef = "node-7"))

        assertEquals(setOf(adopted, projected), store.findByTargetRef("node-42").toSet())
    }

    @Test
    @DisplayName("an unknown key has no records")
    open fun anUnknownKeyHasNoRecords() {
        val store = store()
        store.record(record("p-1", "graph", "run-1", targetRef = "node-1", contextId = "projection-ctx-a"))

        assertTrue(store.findByProposition("unknown").isEmpty())
        assertTrue(store.findByTarget("unknown").isEmpty())
        assertTrue(store.findByContext("unknown").isEmpty())
        assertTrue(store.findByRun("unknown").isEmpty())
        assertTrue(store.findByTargetRef("unknown").isEmpty())
    }

    @Test
    @DisplayName("findStale returns only stale records")
    open fun findStaleReturnsOnlyStaleRecords() {
        val store = store()
        val stale = record("p-1", "graph", "run-1", ProjectionLifecycle.STALE)
        store.record(stale)
        store.record(record("p-2", "graph", "run-1", ProjectionLifecycle.PROJECTED))

        assertEquals(setOf(stale), store.findStale().toSet())
    }

    @Test
    @DisplayName("markStaleByProposition marks every record of the proposition and reports how many changed")
    open fun markStaleByPropositionMarksEveryRecordOfTheProposition() {
        val store = store()
        store.record(record("p-1", "graph", "run-1"))
        store.record(record("p-1", "prolog", "run-1", ProjectionLifecycle.ADOPTED))
        val untouched = record("p-2", "graph", "run-1")
        store.record(untouched)

        assertEquals(2, store.markStaleByProposition("p-1"))

        assertEquals(
            setOf(
                record("p-1", "graph", "run-1", ProjectionLifecycle.STALE),
                record("p-1", "prolog", "run-1", ProjectionLifecycle.STALE),
            ),
            store.findByProposition("p-1").toSet(),
        )
        assertEquals(setOf(untouched), store.findByProposition("p-2").toSet())
    }

    @Test
    @DisplayName("markStaleByProposition reports zero when nothing changes")
    open fun markStaleByPropositionReportsZeroWhenNothingChanges() {
        val store = store()
        store.record(record("p-1", "graph", "run-1"))
        store.markStaleByProposition("p-1")

        assertEquals(0, store.markStaleByProposition("p-1"))
        assertEquals(0, store.markStaleByProposition("unknown"))
    }
}

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
package com.embabel.dice.projection.lineage

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InMemoryProjectionRecordStoreTest {

    private lateinit var store: InMemoryProjectionRecordStore

    @BeforeEach
    fun setUp() {
        store = InMemoryProjectionRecordStore()
    }

    private fun record(
        propositionId: String,
        target: String,
        runId: String,
        lifecycle: ProjectionLifecycle,
    ) = ProjectionRecord(
        propositionId = propositionId,
        target = target,
        runId = runId,
        lifecycle = lifecycle,
    )

    @Test
    fun `record and findByProposition`() {
        store.record(record("p1", "graph", "run-1", ProjectionLifecycle.PROJECTED))
        store.record(record("p1", "prolog", "run-1", ProjectionLifecycle.PROJECTED))
        store.record(record("p2", "graph", "run-1", ProjectionLifecycle.SKIPPED))

        val p1 = store.findByProposition("p1")
        assertEquals(2, p1.size)
        assertTrue(p1.all { it.propositionId == "p1" })
        assertTrue(store.findByProposition("missing").isEmpty())
    }

    @Test
    fun findByTarget() {
        store.record(record("p1", "graph", "run-1", ProjectionLifecycle.PROJECTED))
        store.record(record("p2", "graph", "run-1", ProjectionLifecycle.ADOPTED))
        store.record(record("p3", "prolog", "run-1", ProjectionLifecycle.PROJECTED))

        assertEquals(2, store.findByTarget("graph").size)
        assertEquals(1, store.findByTarget("prolog").size)
    }

    @Test
    fun findByRun() {
        store.record(record("p1", "graph", "run-1", ProjectionLifecycle.PROJECTED))
        store.record(record("p2", "graph", "run-2", ProjectionLifecycle.PROJECTED))

        assertEquals(1, store.findByRun("run-1").size)
        assertEquals(1, store.findByRun("run-2").size)
        assertTrue(store.findByRun("run-3").isEmpty())
    }

    @Test
    fun `findStale returns only stale`() {
        store.record(record("p1", "graph", "run-1", ProjectionLifecycle.PROJECTED))
        store.record(record("p2", "graph", "run-1", ProjectionLifecycle.STALE))
        store.record(record("p3", "prolog", "run-1", ProjectionLifecycle.FAILED))
        store.record(record("p4", "report", "run-1", ProjectionLifecycle.STALE))

        val stale = store.findStale()
        assertEquals(2, stale.size)
        assertTrue(stale.all { it.lifecycle == ProjectionLifecycle.STALE })
        assertEquals(setOf("p2", "p4"), stale.map { it.propositionId }.toSet())
    }

    @Test
    fun `all returns insertion order`() {
        store.record(record("p1", "graph", "run-1", ProjectionLifecycle.PROJECTED))
        store.record(record("p2", "graph", "run-1", ProjectionLifecycle.PROJECTED))

        val all = store.all()
        assertEquals(2, all.size)
        assertEquals("p1", all[0].propositionId)
        assertEquals("p2", all[1].propositionId)
    }
}

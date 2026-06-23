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

import com.embabel.dice.spi.MarkReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class InMemoryCollectorRecordStoreTest {

    private fun record(
        propositionId: String,
        runId: String,
        reason: MarkReason = MarkReason.Stale,
        outcome: CollectorOutcome = CollectorOutcome.MARKED,
    ) = CollectorRecord(
        propositionId = propositionId,
        reason = reason,
        outcome = outcome,
        strategyName = "decay",
        runId = runId,
    )

    @Test
    fun `store starts empty`() {
        val store = InMemoryCollectorRecordStore()
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `record appends and all returns records in insertion order`() {
        val store = InMemoryCollectorRecordStore()
        val first = record("p1", "r1")
        val second = record("p2", "r1")

        store.record(first)
        store.record(second)

        assertEquals(listOf(first, second), store.all())
    }

    @Test
    fun `findByProposition returns only records with that propositionId`() {
        val store = InMemoryCollectorRecordStore()
        val p1a = record("p1", "r1")
        val p2 = record("p2", "r1")
        val p1b = record("p1", "r2")
        store.record(p1a)
        store.record(p2)
        store.record(p1b)

        assertEquals(listOf(p1a, p1b), store.findByProposition("p1"))
    }

    @Test
    fun `findByRun returns only records with that runId`() {
        val store = InMemoryCollectorRecordStore()
        val r1a = record("p1", "r1")
        val r2 = record("p2", "r2")
        val r1b = record("p3", "r1")
        store.record(r1a)
        store.record(r2)
        store.record(r1b)

        assertEquals(listOf(r1a, r1b), store.findByRun("r1"))
    }

    @Test
    fun `findByProposition returns empty for unknown id`() {
        val store = InMemoryCollectorRecordStore()
        store.record(record("p1", "r1"))

        assertTrue(store.findByProposition("nope").isEmpty())
    }

    @Test
    fun `findRun returns the recorded run header and null for an unknown id`() {
        val store = InMemoryCollectorRecordStore()
        store.recordRun(CollectorRun(runId = "r1", startedAt = Instant.now(), dryRun = true))

        val found = store.findRun("r1")
        assertEquals("r1", found?.runId)
        assertTrue(found?.dryRun == true)
        assertNull(store.findRun("nope"))
    }
}

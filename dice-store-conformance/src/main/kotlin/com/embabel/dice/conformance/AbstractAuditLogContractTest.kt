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
import com.embabel.dice.audit.AbstractAuditLog
import com.embabel.dice.audit.AuditActorRef
import com.embabel.dice.audit.AuditCoverageException
import com.embabel.dice.audit.AuditEntry
import com.embabel.dice.audit.AuditKind
import com.embabel.dice.audit.AuditedProposition
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.temporal.TemporalMetadata
import org.jetbrains.annotations.ApiStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** An opt-in audit backend contract independent of repository or database implementation. */
@ApiStatus.Experimental
abstract class AbstractAuditLogContractTest {

    protected abstract fun log(): AbstractAuditLog

    private val cutover = Instant.parse("2026-01-02T00:00:00Z")
    private val recordedAt = cutover.plusNanos(1)
    private val scope = UUID.randomUUID().toString()
    private val other = UUID.randomUUID().toString()
    private val actor = AuditActorRef("opaque-eraser")

    private fun claim(contextId: String, text: String) = Proposition(
        id = "shared-proposition-id",
        contextId = ContextId(contextId),
        text = text,
        mentions = emptyList(),
        confidence = 0.8,
        temporal = TemporalMetadata(observedAt = cutover, supersedes = listOf("reference")),
    )

    private fun created(claim: Proposition) =
        AuditEntry(AuditKind.CREATED, claim.id, after = AuditedProposition.from(claim))

    @Test
    fun `complete baselines reject foreign and duplicate rows without leaving partial coverage`() {
        val store = log()
        val live = claim(scope, "Previously known")
        val foreign = claim(other, "Foreign")
        assertThrows(IllegalArgumentException::class.java) {
            store.initializeBaseline(scope, cutover, listOf(live, foreign))
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.initializeBaseline(scope, cutover, listOf(live, live))
        }
        assertThrows(AuditCoverageException::class.java) { store.beliefSetAsOf(scope, cutover) }
        store.initializeBaseline(scope, cutover, listOf(live))
        assertThrows(IllegalStateException::class.java) {
            store.initializeBaseline(scope, cutover, emptyList())
        }
        assertEquals(listOf(AuditedProposition.from(live)), store.beliefSetAsOf(scope, cutover))
        assertThrows(AuditCoverageException::class.java) { store.beliefSetAsOf(scope, cutover.minusNanos(1)) }
        assertThrows(AuditCoverageException::class.java) { store.beliefSetAsOf(scope, recordedAt.plusNanos(1)) }
        assertThrows(AuditCoverageException::class.java) { store.beliefSetAsOf(other, cutover) }
    }

    @Test
    fun `updates and deletions replay while erasure removes content and keeps a single scoped tombstone`() {
        val store = log()
        val live = claim(scope, "Previously known")
        val updated = live.copy(text = "Updated")
        val foreign = claim(other, "Foreign")
        store.initializeBaseline(scope, cutover, listOf(live))
        store.initializeBaseline(other, cutover, emptyList())
        assertThrows(IllegalArgumentException::class.java) { store.append(scope, created(foreign)) }
        store.append(other, created(foreign))
        val first = store.append(scope, AuditEntry(AuditKind.CHANGED, live.id, AuditedProposition.from(live), AuditedProposition.from(updated)))
        val deleted = store.append(scope, AuditEntry(AuditKind.DELETED, live.id, AuditedProposition.from(updated)))
        assertEquals(AuditedProposition.from(live), store.asOf(scope, live.id, cutover))
        assertNull(store.asOf(scope, live.id, recordedAt))
        assertEquals(listOf(first, deleted), store.history(scope, live.id))
        assertEquals(listOf(deleted), store.query(scope, first.sequence, 1))

        val erasure = store.eraseContext(scope, actor)
        assertEquals(AuditKind.ERASURE, erasure.kind)
        assertEquals(2L, erasure.erasedRecordCount)
        assertEquals(1L, erasure.erasedBaselineCount)
        assertEquals(actor, erasure.attribution.actor)
        assertEquals(emptyList<Any>(), erasure.changes)
        assertNull(erasure.propositionId)
        assertNull(erasure.reason)
        assertEquals(erasure, store.eraseContext(scope, actor))
        assertEquals(listOf(erasure), store.query(scope, 0, 10))
        assertEquals(emptyList<Any>(), store.history(scope, live.id))
        assertThrows(AuditCoverageException::class.java) { store.beliefSetAsOf(scope, recordedAt) }
        assertEquals(AuditedProposition.from(foreign), store.asOf(other, foreign.id, recordedAt))
    }

    @Test
    fun `concurrent appends allocate one position per record even at the same instant`() {
        val store = log()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val records = pool.invokeAll((1..12).map { i ->
                Callable { store.append(scope, created(claim(scope, "Claim $i").copy(id = "claim-$i"))) }
            }).map { it.get() }
            assertEquals((1L..12L).toSet(), records.map { it.sequence }.toSet())
            assertEquals(listOf(recordedAt), records.map { it.recordedAt }.distinct())
            assertEquals(records.sortedBy { it.sequence }, store.query(scope, 0, 25))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `known gaps and inconsistent before images fail closed`() {
        val store = log()
        val live = claim(scope, "Known state")
        val wrong = live.copy(text = "Missing write")
        store.initializeBaseline(scope, cutover, listOf(live))
        store.append(scope, AuditEntry(AuditKind.DELETED, live.id, before = AuditedProposition.from(wrong)))
        assertEquals(AuditedProposition.from(live), store.asOf(scope, live.id, cutover))
        assertThrows(AuditCoverageException::class.java) { store.asOf(scope, live.id, recordedAt) }
        store.initializeBaseline(other, cutover, emptyList())
        store.invalidateCoverage(other)
        assertThrows(AuditCoverageException::class.java) { store.beliefSetAsOf(other, recordedAt) }
    }
}

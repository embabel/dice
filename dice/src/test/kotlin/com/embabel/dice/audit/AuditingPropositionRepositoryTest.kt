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
package com.embabel.dice.audit

import com.embabel.agent.core.ContextId
import com.embabel.dice.common.PropositionRejected
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AuditingPropositionRepositoryTest {

    @Test
    fun `a run is scoped to its context and restored after failure`() {
        val log = InMemoryAuditLog()
        val repo = AuditingPropositionRepository.wrapping(InMemoryPropositionRepository(), log)
        val claim = Proposition(id = "first", contextId = ContextId("scope"), text = "First", mentions = emptyList(), confidence = 0.8)
        val run = AuditRunRef(AuditRunKind.EXTRACTION, "opaque-run")
        assertThrows(IllegalStateException::class.java) {
            AuditRunContext.withRun("scope", run) {
                repo.save(claim)
                repo.save(claim.copy(id = "foreign", contextId = ContextId("other")))
                throw IllegalStateException("run ended")
            }
        }
        repo.save(claim.copy(id = "standalone"))
        assertEquals(run, log.history("scope", "first").single().attribution.run)
        assertNull(log.history("other", "foreign").single().attribution.run)
        assertNull(log.history("scope", "standalone").single().attribution.run)
    }

    @Test
    fun `writes are sequenced with their saved values and gate decisions do not change the belief set`() {
        val cutover = Instant.parse("2026-01-02T00:00:00Z")
        val recordedAt = cutover.plusSeconds(1)
        val log = InMemoryAuditLog(Clock.fixed(recordedAt, ZoneOffset.UTC))
        val run = AuditRunRef(AuditRunKind.COLLECTOR, "collector-run")
        val provider = AuditContextProvider { AuditAttribution(AuditActorRef("opaque-actor"), run) }
        val repo = AuditingPropositionRepository.wrapping(InMemoryPropositionRepository(), log, provider)
        val listener = AuditEventListener(log, provider)
        val proposition = Proposition(id = "id", contextId = ContextId("scope"), text = "First", mentions = emptyList(), confidence = 0.8)
        log.initializeBaseline("scope", cutover, emptyList())
        repo.save(proposition)
        repo.save(proposition.withStatus(PropositionStatus.STALE))
        repo.pin("id")
        repo.pin("id")
        listener.onEvent(PropositionRejected(proposition.copy(id = "rejected"), "evidence_floor"))
        assertEquals(true, repo.delete("id"))
        assertEquals(false, repo.delete("id"))

        val records = log.query("scope", 0, 10)
        assertEquals(listOf(AuditKind.CREATED, AuditKind.CHANGED, AuditKind.PINNED, AuditKind.GATE_REJECTED, AuditKind.DELETED), records.map { it.kind })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), records.map { it.sequence })
        assertEquals(setOf(recordedAt), records.map { it.recordedAt }.toSet())
        assertEquals(listOf(AuditFieldChange(AuditField.STATUS, PropositionStatus.ACTIVE, PropositionStatus.STALE)), records[1].changes)
        assertEquals(run, records[1].attribution.run)
        assertEquals("evidence_floor", records[3].reason)
        assertNull(log.asOf("scope", "id", recordedAt))
        assertNull(log.asOf("scope", "rejected", recordedAt))
        assertEquals(records.filter { it.propositionId == "id" }, log.history("scope", "id"))
        assertEquals(emptyList<AuditRecord>(), log.history("other", "id"))
        assertThrows(AuditCoverageException::class.java) { log.asOf("other", "id", recordedAt) }
    }
}

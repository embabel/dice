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

import com.embabel.dice.proposition.Proposition
import org.jetbrains.annotations.ApiStatus
import java.time.Clock
import java.time.Instant
import java.util.function.Supplier

/** Process-lifetime audit log. Use a persistent sink when history must survive a restart. */
@ApiStatus.Experimental
class InMemoryAuditLog @JvmOverloads constructor(private val clock: Clock = Clock.systemUTC()) : AbstractAuditLog() {
    override val transactionParticipation = AuditTransactionParticipation.BEST_EFFORT
    private val contexts = mutableMapOf<String, Context>()

    @Synchronized
    override fun initializeBaseline(contextId: String, cutover: Instant, completeSnapshot: Collection<Proposition>) {
        val baseline = baselineOf(contextId, completeSnapshot)
        require(cutover <= clock.instant()) { "Audit cutover cannot be in the future" }
        val context = contexts.getOrPut(contextId, ::Context)
        check(context.cutover == null) { "Audit baseline is already initialized" }
        check(context.lastRecordedAt == null || cutover >= context.lastRecordedAt) {
            "Audit cutover cannot precede existing records"
        }
        context.baseline = baseline
        context.baselineSequence = context.sequence
        context.cutover = cutover
        context.invalidFrom = null
    }

    @Synchronized
    override fun append(contextId: String, entry: AuditEntry): AuditRecord {
        requireAppend(contextId, entry)
        return contexts.getOrPut(contextId, ::Context).append(
            contextId, entry.copy(before = entry.before?.snapshot(), after = entry.after?.snapshot()),
        )
    }

    @Synchronized
    override fun eraseContext(contextId: String, actor: AuditActorRef?): AuditRecord {
        requireContext(contextId)
        val context = contexts.getOrPut(contextId, ::Context)
        val previous = context.records.singleOrNull()
        if (context.cutover == null && previous?.kind == AuditKind.ERASURE) return previous
        val tombstone = AuditEntry(
            AuditKind.ERASURE,
            attribution = AuditAttribution(actor),
            erasedRecordCount = context.records.count { it.kind != AuditKind.ERASURE }.toLong(),
            erasedBaselineCount = context.baseline.size.toLong(),
        )
        context.records.clear()
        context.cutover = null
        context.baseline = emptyList()
        context.invalidFrom = null
        return context.append(contextId, tombstone)
    }

    @Synchronized
    override fun <T> duringWrite(contextId: String, action: Supplier<T>): T {
        requireContext(contextId)
        return action.get()
    }

    @Synchronized
    override fun invalidateCoverage(contextId: String) {
        requireContext(contextId)
        val context = contexts[contextId] ?: return
        context.invalidFrom = context.cutover
    }

    @Synchronized
    override fun history(contextId: String, propositionId: String): List<AuditRecord> {
        requireContext(contextId)
        require(propositionId.isNotBlank())
        return contexts[contextId]?.records.orEmpty().filter { it.propositionId == propositionId }
    }

    @Synchronized
    override fun query(contextId: String, afterSequence: Long, limit: Int): List<AuditRecord> {
        requirePage(contextId, afterSequence, limit)
        return contexts[contextId]?.records.orEmpty().asSequence().filter { it.sequence > afterSequence }.take(limit).toList()
    }

    @Synchronized
    override fun timeline(contextId: String, at: Instant): AuditTimeline {
        val context = contexts[contextId] ?: return AuditTimeline(null, null, emptyList(), emptyList())
        return AuditTimeline(
            context.cutover, context.invalidFrom, context.baseline,
            context.records.filter { it.sequence > context.baselineSequence && it.recordedAt <= at },
        )
    }

    private inner class Context {
        var cutover: Instant? = null
        var invalidFrom: Instant? = null
        var lastRecordedAt: Instant? = null
        var baseline: List<AuditedProposition> = emptyList()
        var baselineSequence = 0L
        var sequence = 0L
        val records = mutableListOf<AuditRecord>()

        fun nextRecordedAt(): Instant = maxOf(clock.instant(), lastRecordedAt ?: Instant.MIN, cutover ?: Instant.MIN)

        fun append(contextId: String, entry: AuditEntry): AuditRecord {
            val at = nextRecordedAt()
            lastRecordedAt = at
            val record = AuditRecord(contextId, ++sequence, at, entry)
            records.add(record)
            return record
        }
    }
}

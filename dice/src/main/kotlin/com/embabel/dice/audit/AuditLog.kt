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
import java.time.Instant
import java.util.function.Supplier

@ApiStatus.Experimental
enum class AuditTransactionParticipation { BEST_EFFORT, JOINS_HOST_TRANSACTION }

@ApiStatus.Experimental
class AuditCoverageException : IllegalStateException("Complete audit coverage is unavailable for the requested instant")

/** The host authorizes the exact context before calling any operation. Context IDs are not credentials. */
@ApiStatus.Experimental
interface AuditSink {
    val transactionParticipation: AuditTransactionParticipation

    /**
     * The host fences all writers while capturing the full context and calling this method.
     * An empty snapshot asserts the context is empty at [cutover]. The baseline is installed
     * atomically. To preserve history, an existing baseline cannot be silently replaced.
     */
    fun initializeBaseline(contextId: String, cutover: Instant, completeSnapshot: Collection<Proposition>)

    /** Appends one entry. ERASURE entries can only be created by [eraseContext]. */
    fun append(contextId: String, entry: AuditEntry): AuditRecord

    /**
     * Remove historical content and invalidate the baseline in the same operation as writing
     * the content-free tombstone. The host also erases its live data under the same write fence.
     */
    fun eraseContext(contextId: String, actor: AuditActorRef?): AuditRecord

    /**
     * Serialize read-before-write work with audit appends, cutover, and erasure for the same context.
     * A joining sink executes [action] in the host transaction (or starts one if none exists).
     * The repository and sink must use the same transaction manager when atomicity is required.
     */
    fun <T> duringWrite(contextId: String, action: Supplier<T>): T

    /** Fail historical reconstruction when a repository write may have escaped recording. */
    fun invalidateCoverage(contextId: String)
}

/** Scope is mandatory even when proposition IDs happen to be globally unique. */
@ApiStatus.Experimental
interface AuditLogQuery {
    fun history(contextId: String, propositionId: String): List<AuditRecord>

    /** Sequence-ordered page, with an exclusive cursor. Pass zero to start at the first entry. */
    fun query(contextId: String, afterSequence: Long, limit: Int): List<AuditRecord>

    /** Null means the proposition was absent in a covered context. Missing coverage throws. */
    fun asOf(contextId: String, propositionId: String, at: Instant): AuditedProposition?

    /** Rebuild all stored propositions, including non-active statuses, at transaction time [at]. */
    fun beliefSetAsOf(contextId: String, at: Instant): List<AuditedProposition>
}

/** Common validation and replay for the supplied backends. */
@ApiStatus.Experimental
abstract class AbstractAuditLog : AuditSink, AuditLogQuery {
    /** Returns one coherent view of the baseline and all records between the baseline and [at]. */
    protected abstract fun timeline(contextId: String, at: Instant): AuditTimeline

    final override fun asOf(contextId: String, propositionId: String, at: Instant): AuditedProposition? {
        require(propositionId.isNotBlank())
        return beliefSetAsOf(contextId, at).firstOrNull { it.id == propositionId }
    }

    final override fun beliefSetAsOf(contextId: String, at: Instant): List<AuditedProposition> {
        requireContext(contextId)
        val timeline = timeline(contextId, at)
        if (timeline.cutover == null || at < timeline.cutover ||
            (timeline.invalidFrom != null && at >= timeline.invalidFrom)
        ) {
            throw AuditCoverageException()
        }
        val claims = timeline.baseline.associateByTo(linkedMapOf()) {
            check(it.contextId == contextId) { "Audit baseline scope mismatch" }
            it.id
        }
        check(claims.size == timeline.baseline.size) { "Duplicate audit baseline identifiers" }
        var lastSequence = 0L
        for (record in timeline.records) {
            check(record.contextId == contextId && record.sequence > lastSequence &&
                record.recordedAt >= timeline.cutover && record.recordedAt <= at)
            lastSequence = record.sequence
            if (record.before == null && record.after == null) continue
            val id = checkNotNull(record.propositionId)
            if (claims[id] != record.before) throw AuditCoverageException()
            val next = record.after
            if (next == null) claims.remove(id) else {
                check(next.contextId == contextId) { "Audit record scope mismatch" }
                claims[id] = next
            }
        }
        return claims.values.sortedBy { it.id }
    }

    protected fun requireContext(contextId: String) {
        require(contextId.isNotBlank()) { "An exact audit context is required" }
    }

    protected fun requirePage(contextId: String, afterSequence: Long, limit: Int) {
        requireContext(contextId)
        require(afterSequence >= 0)
        require(limit > 0)
    }

    protected fun baselineOf(contextId: String, snapshot: Collection<Proposition>): List<AuditedProposition> {
        requireContext(contextId)
        return snapshot.map {
            require(it.contextId.value == contextId) { "Audit baseline must be confined to one context" }
            AuditedProposition.from(it)
        }.also { claims ->
            require(claims.map { it.id }.toSet().size == claims.size) { "Duplicate audit baseline identifiers" }
        }
    }

    protected fun requireAppend(contextId: String, entry: AuditEntry) {
        requireContext(contextId)
        require(entry.kind != AuditKind.ERASURE) { "Use eraseContext to record an erasure" }
        require(entry.before == null || entry.before.contextId == contextId) { "Audit prior value scope mismatch" }
        require(entry.after == null || entry.after.contextId == contextId) { "Audit saved value scope mismatch" }
    }

    protected data class AuditTimeline(
        val cutover: Instant?,
        val invalidFrom: Instant?,
        val baseline: List<AuditedProposition>,
        val records: List<AuditRecord>,
    )
}

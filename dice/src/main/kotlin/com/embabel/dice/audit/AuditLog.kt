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
     * atomically. A valid baseline cannot be replaced. An invalidated baseline can be replaced
     * by a complete snapshot at a later cutover while preserving the recorded operations.
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

    /**
     * Fence a backend-native access timestamp update. The sink can select a compatible transaction
     * boundary for this metadata-only operation when a host transaction is read-only.
     */
    fun duringAccessTouch(contextId: String, action: Runnable) = duringWrite(contextId) { action.run() }

    /**
     * Fence first assignments of globally keyed proposition IDs across contexts. Called inside
     * [duringWrite]. Acquire all requested fences in a stable order and hold them until the
     * same write boundary completes.
     */
    fun <T> duringPropositionCreations(contextId: String, propositionIds: Collection<String>, action: Supplier<T>): T

    /** Records a terminal event published after its original operation committed. */
    fun appendAfterCommit(contextId: String, entry: AuditEntry): AuditRecord = append(contextId, entry)

    /** Fail historical reconstruction when a repository write may have escaped recording. */
    fun invalidateCoverage(contextId: String)
}

/** A deduplicating repository can find the saved id before a new input is written. */
@ApiStatus.Experimental
fun interface AuditCanonicalLookup {
    /** Returns the existing canonical id for [input], if its save will reuse one. */
    fun findAuditCanonicalId(input: Proposition): String?
}

/**
 * Native access refresh for a repository whose ordinary touch opens an independent transaction.
 * Expose this on the same adapter supplied as [AuditCanonicalLookup] to the audit wrapper.
 */
@ApiStatus.Experimental
fun interface AuditAccessTouch {
    /** Refresh access for existing IDs in this context without leaving the active write transaction. */
    fun touchAccessedInWrite(contextId: String, propositionIds: Collection<String>)
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
    /** The baseline, retained records, and their high watermark must come from one coherent view. */
    protected abstract fun timeline(contextId: String): AuditTimeline

    final override fun asOf(contextId: String, propositionId: String, at: Instant): AuditedProposition? {
        require(propositionId.isNotBlank())
        return beliefSetAsOf(contextId, at).firstOrNull { it.id == propositionId }
    }

    final override fun beliefSetAsOf(contextId: String, at: Instant): List<AuditedProposition> {
        requireContext(contextId)
        val timeline = timeline(contextId)
        if (timeline.cutover == null || at < timeline.cutover || at > timeline.observedThrough ||
            (timeline.invalidFrom != null && at >= timeline.invalidFrom)
        ) {
            throw AuditCoverageException()
        }
        val claims = timeline.baseline.associateByTo(linkedMapOf()) {
            if (it.contextId != contextId) throw AuditCoverageException()
            it.id
        }
        if (claims.size != timeline.baseline.size) throw AuditCoverageException()
        var lastSequence = timeline.baselineSequence
        var lastRecordedAt = timeline.cutover
        for (record in timeline.records) {
            if (record.contextId != contextId || record.sequence != lastSequence + 1 ||
                record.recordedAt < lastRecordedAt || record.recordedAt > timeline.observedThrough
            ) throw AuditCoverageException()
            lastSequence = record.sequence
            lastRecordedAt = record.recordedAt
            if (record.recordedAt > at || (record.before == null && record.after == null)) continue
            val id = checkNotNull(record.propositionId)
            if (claims[id] != record.before) throw AuditCoverageException()
            val next = record.after
            if (next == null) claims.remove(id) else {
                if (next.contextId != contextId) throw AuditCoverageException()
                claims[id] = next
            }
        }
        if (lastSequence != timeline.latestSequence) throw AuditCoverageException()
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
        val observedThrough: Instant,
        val baselineSequence: Long,
        val latestSequence: Long,
        val baseline: List<AuditedProposition>,
        val records: List<AuditRecord>,
    )
}

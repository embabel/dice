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

import com.embabel.dice.proposition.GraphQueryCapable
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionPersistenceResult
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.ProvenanceSubtractionCapable
import com.embabel.dice.proposition.SourceRevisionQueryCapable
import org.jetbrains.annotations.ApiStatus

/**
 * Opt-in audited mutation boundary. Route every tracked write for a covered context through a
 * wrapper with the same audit sink, including application maintenance tasks. Writes made directly
 * to the underlying repository or a backend-specific maintenance API are outside audit coverage.
 */
@ApiStatus.Experimental
open class AuditingPropositionRepository private constructor(
    private val delegate: PropositionRepository,
    private val sink: AuditSink,
    private val contextProvider: AuditContextProvider,
    private val failurePolicy: AuditFailurePolicy,
) : PropositionRepository by delegate {

    override fun save(proposition: Proposition): Proposition {
        val contextId = proposition.contextId.value
        return sink.duringWrite(contextId) {
            val current = delegate.findById(proposition.id)
            require(current == null || current.contextId.value == contextId) {
                "An audited proposition cannot move between contexts"
            }
            // A backend may deduplicate a new input to an existing canonical proposition. Its
            // prior image, rather than the new input or a copy read after save, belongs in history.
            val candidates = if (current == null) {
                delegate.findByContextIdValue(contextId).associateBy { it.id }
            } else {
                emptyMap()
            }
            val saved = mutate(contextId) { delegate.save(proposition) }
            trackedWrite(contextId) {
                check(saved.contextId.value == contextId) { "Audited save returned a different context" }
                check(current == null || current.id == saved.id) { "Audited update changed identity" }
                record(contextId, current ?: candidates[saved.id], saved)
            }
            saved
        }
    }

    override fun saveAll(propositions: Collection<Proposition>) {
        propositions.forEach(::save)
    }

    override fun saveAllReturningCanonical(propositions: Collection<Proposition>): PropositionPersistenceResult {
        val inputs = propositions.toList()
        return PropositionPersistenceResult.of(inputs, inputs.map(::save))
    }

    override fun pin(id: String): Proposition? = changeExisting(id, delegate::pin)
    override fun unpin(id: String): Proposition? = changeExisting(id, delegate::unpin)

    private fun changeExisting(id: String, update: (String) -> Proposition?): Proposition? {
        val contextId = delegate.findById(id)?.contextId?.value ?: return null
        return sink.duringWrite(contextId) {
            val current = currentInContext(id, contextId) ?: return@duringWrite null
            val saved = mutate(contextId) { update(id) } ?: return@duringWrite null
            trackedWrite(contextId) { record(contextId, current, saved) }
            saved
        }
    }

    override fun delete(id: String): Boolean {
        val contextId = delegate.findById(id)?.contextId?.value ?: return false
        return sink.duringWrite(contextId) {
            val current = currentInContext(id, contextId) ?: return@duringWrite false
            val deleted = mutate(contextId) { delegate.delete(id) }
            if (deleted) trackedWrite(contextId) { record(contextId, current, null) }
            deleted
        }
    }

    private fun currentInContext(id: String, contextId: String): Proposition? =
        delegate.findById(id)?.also {
            check(it.contextId.value == contextId) { "Audited proposition changed its context" }
        }

    /** Erase both the context's live propositions and its historical content. */
    override fun clearByContext(contextId: String): Int = sink.duringWrite(contextId) {
        val actor = contextProvider.attributionFor(contextId).actor
        val count = mutate(contextId) { delegate.clearByContext(contextId) }
        writeAudit(sink, AuditFailurePolicy.FAIL_WRITE, contextId, affectsBeliefSet = true) {
            sink.eraseContext(contextId, actor)
        }
        count
    }

    override fun clearAll(): Int = throw UnsupportedOperationException("Use clearByContext for audited erasure")

    override fun clearByContextPrefix(contextIdPrefix: String): Int =
        throw UnsupportedOperationException("Use clearByContext for audited erasure")

    private fun <T> mutate(contextId: String, action: () -> T): T =
        try {
            action()
        } catch (failure: RuntimeException) {
            // A best-effort repository might have written before reporting failure.
            writeAudit(sink, AuditFailurePolicy.FAIL_WRITE, contextId, affectsBeliefSet = true) { throw failure }
            throw failure
        }

    private fun trackedWrite(contextId: String, action: () -> Unit) =
        writeAudit(sink, failurePolicy, contextId, affectsBeliefSet = true, action)

    private fun record(contextId: String, current: Proposition?, saved: Proposition?) {
        val before = current?.let(AuditedProposition::from)
        val after = saved?.let(AuditedProposition::from)
        if (before == after) return
        val kind = when {
            before == null -> AuditKind.CREATED
            after == null -> AuditKind.DELETED
            before.pinned != after.pinned -> if (after.pinned) AuditKind.PINNED else AuditKind.UNPINNED
            else -> AuditKind.CHANGED
        }
        sink.append(contextId, AuditEntry(
            kind, (after ?: checkNotNull(before)).id, before, after,
            contextProvider.attributionFor(contextId),
        ))
    }

    companion object {
        /** Preserve exactly the optional repository interfaces implemented by the delegate. */
        @JvmStatic
        @JvmOverloads
        fun wrapping(
            delegate: PropositionRepository,
            sink: AuditSink,
            contextProvider: AuditContextProvider = AuditContextProvider.NONE,
            failurePolicy: AuditFailurePolicy = AuditFailurePolicy.FAIL_WRITE,
        ): AuditingPropositionRepository = when {
            delegate is GraphQueryCapable && delegate is SourceRevisionQueryCapable && delegate is ProvenanceSubtractionCapable ->
                object : AuditingPropositionRepository(delegate, sink, contextProvider, failurePolicy),
                    GraphQueryCapable by delegate, SourceRevisionQueryCapable by delegate, ProvenanceSubtractionCapable by delegate {}

            delegate is GraphQueryCapable && delegate is SourceRevisionQueryCapable ->
                object : AuditingPropositionRepository(delegate, sink, contextProvider, failurePolicy),
                    GraphQueryCapable by delegate, SourceRevisionQueryCapable by delegate {}

            delegate is GraphQueryCapable && delegate is ProvenanceSubtractionCapable ->
                object : AuditingPropositionRepository(delegate, sink, contextProvider, failurePolicy),
                    GraphQueryCapable by delegate, ProvenanceSubtractionCapable by delegate {}

            delegate is SourceRevisionQueryCapable && delegate is ProvenanceSubtractionCapable ->
                object : AuditingPropositionRepository(delegate, sink, contextProvider, failurePolicy),
                    SourceRevisionQueryCapable by delegate, ProvenanceSubtractionCapable by delegate {}

            delegate is GraphQueryCapable ->
                object : AuditingPropositionRepository(delegate, sink, contextProvider, failurePolicy), GraphQueryCapable by delegate {}

            delegate is SourceRevisionQueryCapable ->
                object : AuditingPropositionRepository(delegate, sink, contextProvider, failurePolicy), SourceRevisionQueryCapable by delegate {}

            delegate is ProvenanceSubtractionCapable ->
                object : AuditingPropositionRepository(delegate, sink, contextProvider, failurePolicy), ProvenanceSubtractionCapable by delegate {}

            else -> AuditingPropositionRepository(delegate, sink, contextProvider, failurePolicy)
        }
    }
}

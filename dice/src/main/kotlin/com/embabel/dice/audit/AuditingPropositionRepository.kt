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
import com.embabel.dice.proposition.GraphQueryCapable
import com.embabel.dice.proposition.PropositionReembedReport
import com.embabel.dice.provenance.ProvenanceEntry
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
    private val canonicalLookup: AuditCanonicalLookup?,
) : PropositionRepository by delegate {

    override fun save(proposition: Proposition): Proposition {
        val contextId = proposition.contextId.value
        return sink.duringWrite(contextId) {
            val current = delegate.findById(proposition.id)
            if (current != null) {
                saveInContext(contextId, proposition, current)
            } else {
                // Check ownership again after a competing context has finished creating this ID.
                sink.duringPropositionCreation(contextId, proposition.id) {
                    saveInContext(contextId, proposition, delegate.findById(proposition.id))
                }
            }
        }
    }

    private fun saveInContext(contextId: String, proposition: Proposition, current: Proposition?): Proposition {
        require(current == null || current.contextId.value == contextId) {
            "An audited proposition cannot move between contexts"
        }
        // A deduplicating save needs the previous canonical proposition, before the save runs.
        val canonical = if (current == null) {
            canonicalLookup?.findAuditCanonicalId(proposition)?.let { currentInContext(it, contextId) }
        } else null
        val saved = mutate(contextId) { delegate.save(proposition) }
        trackedWrite(contextId) {
            if (current == null && saved.id != proposition.id) {
                check(canonical?.id == saved.id) { "Audited save did not capture the canonical preimage" }
            }
            recordStored(contextId, current ?: canonical?.takeIf { it.id == saved.id }, saved)
        }
        return saved
    }

    override fun saveAll(propositions: Collection<Proposition>) {
        saveAllReturningCanonical(propositions)
    }

    override fun saveAllReturningCanonical(propositions: Collection<Proposition>): PropositionPersistenceResult {
        val inputs = propositions.toList()
        val contextId = inputs.firstOrNull()?.contextId?.value
        val persist = { PropositionPersistenceResult.of(inputs, inputs.map(::save)) }
        return if (contextId != null && inputs.all { it.contextId.value == contextId }) {
            sink.duringWrite(contextId, persist)
        } else persist()
    }

    override fun pin(id: String): Proposition? = changeExisting(id, delegate::pin)
    override fun unpin(id: String): Proposition? = changeExisting(id, delegate::unpin)

    override fun addProvenance(propositionId: String, entries: List<ProvenanceEntry>): Proposition? =
        changeExisting(propositionId) { delegate.addProvenance(it, entries) }

    override fun setProvenance(propositionId: String, entries: List<ProvenanceEntry>): Proposition? =
        changeExisting(propositionId) { delegate.setProvenance(it, entries) }

    override fun clearProvenance(propositionId: String): Proposition? =
        changeExisting(propositionId, delegate::clearProvenance)

    override fun touchAccessed(ids: Collection<String>) {
        val byContext = ids.distinct().mapNotNull(delegate::findById).groupBy { it.contextId.value }
        for ((contextId, propositions) in byContext) {
            sink.duringWrite(contextId) {
                val liveIds = propositions.mapNotNull { currentInContext(it.id, contextId)?.id }
                if (liveIds.isNotEmpty()) mutate(contextId) { delegate.touchAccessed(liveIds) }
            }
        }
    }

    fun findPinnedValue(contextIdValue: String): List<Proposition> = findPinned(ContextId(contextIdValue))

    override fun reembedAll(): PropositionReembedReport =
        throw UnsupportedOperationException("Run whole-store re-embedding while audited writers are quiescent")

    internal fun changeExisting(id: String, update: (String) -> Proposition?): Proposition? {
        val contextId = delegate.findById(id)?.contextId?.value ?: return null
        return sink.duringWrite(contextId) {
            val current = currentInContext(id, contextId) ?: return@duringWrite null
            val saved = mutate(contextId) { update(id) } ?: return@duringWrite null
            trackedWrite(contextId) { recordStored(contextId, current, saved) }
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
        val actor = contextProvider.resolveAttribution(contextId).actor
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
        } catch (failure: Exception) {
            // A best-effort repository might have written before reporting failure.
            writeAudit(sink, AuditFailurePolicy.FAIL_WRITE, contextId, affectsBeliefSet = true) { throw failure }
            throw failure
        }

    private fun trackedWrite(contextId: String, action: () -> Unit) =
        writeAudit(sink, failurePolicy, contextId, affectsBeliefSet = true, action)

    private fun recordStored(contextId: String, current: Proposition?, saved: Proposition) {
        check(saved.contextId.value == contextId) { "Audited save returned a different context" }
        check(current == null || current.id == saved.id) { "Audited update changed identity" }
        // A backend may normalize tracked fields when it persists them and return the input.
        val stored = checkNotNull(currentInContext(saved.id, contextId)) { "Audited save did not persist a proposition" }
        record(contextId, current, stored)
    }

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
            contextProvider.resolveAttribution(contextId),
        ))
    }

    private open class Subtracting(
        delegate: PropositionRepository,
        sink: AuditSink,
        contextProvider: AuditContextProvider,
        failurePolicy: AuditFailurePolicy,
        canonicalLookup: AuditCanonicalLookup?,
        private val subtraction: ProvenanceSubtractionCapable,
    ) : AuditingPropositionRepository(delegate, sink, contextProvider, failurePolicy, canonicalLookup),
        ProvenanceSubtractionCapable {

        override val supportsProvenanceSubtraction: Boolean get() = subtraction.supportsProvenanceSubtraction

        override fun subtractProvenance(propositionId: String, provenanceRefs: List<String>): Proposition? =
            changeExisting(propositionId) { subtraction.subtractProvenance(it, provenanceRefs) }

        override fun subtractFoldedEvidence(
            propositionId: String,
            provenanceRefs: List<String>,
            grounding: Collection<String>,
            sourceIds: Collection<String>,
        ): Proposition? = changeExisting(propositionId) {
            subtraction.subtractFoldedEvidence(it, provenanceRefs, grounding, sourceIds)
        }
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
            canonicalLookup: AuditCanonicalLookup? = delegate as? AuditCanonicalLookup,
        ): AuditingPropositionRepository = when {
            delegate is GraphQueryCapable && delegate is SourceRevisionQueryCapable && delegate is ProvenanceSubtractionCapable ->
                object : Subtracting(delegate, sink, contextProvider, failurePolicy, canonicalLookup, delegate),
                    GraphQueryCapable by delegate, SourceRevisionQueryCapable by delegate {}

            delegate is GraphQueryCapable && delegate is SourceRevisionQueryCapable ->
                object : AuditingPropositionRepository(delegate, sink, contextProvider, failurePolicy, canonicalLookup),
                    GraphQueryCapable by delegate, SourceRevisionQueryCapable by delegate {}

            delegate is GraphQueryCapable && delegate is ProvenanceSubtractionCapable ->
                object : Subtracting(delegate, sink, contextProvider, failurePolicy, canonicalLookup, delegate),
                    GraphQueryCapable by delegate {}

            delegate is SourceRevisionQueryCapable && delegate is ProvenanceSubtractionCapable ->
                object : Subtracting(delegate, sink, contextProvider, failurePolicy, canonicalLookup, delegate),
                    SourceRevisionQueryCapable by delegate {}

            delegate is GraphQueryCapable ->
                object : AuditingPropositionRepository(delegate, sink, contextProvider, failurePolicy, canonicalLookup), GraphQueryCapable by delegate {}

            delegate is SourceRevisionQueryCapable ->
                object : AuditingPropositionRepository(delegate, sink, contextProvider, failurePolicy, canonicalLookup), SourceRevisionQueryCapable by delegate {}

            delegate is ProvenanceSubtractionCapable ->
                Subtracting(delegate, sink, contextProvider, failurePolicy, canonicalLookup, delegate)

            else -> AuditingPropositionRepository(delegate, sink, contextProvider, failurePolicy, canonicalLookup)
        }
    }
}

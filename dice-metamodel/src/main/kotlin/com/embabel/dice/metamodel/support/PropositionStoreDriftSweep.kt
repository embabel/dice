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
package com.embabel.dice.metamodel.support

import com.embabel.agent.core.ContextId
import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.common.PropositionStatusChanged
import com.embabel.dice.metamodel.DriftQuarantineKeys
import com.embabel.dice.metamodel.DriftSweepCapable
import com.embabel.dice.metamodel.QuarantineDecision
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStore
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * The reference [DriftSweepCapable]: a working sweep over any [PropositionStore], and the executable
 * statement of what the contract means.
 *
 * It is correct for every backend, so a host can sweep on day one, and a durable store can be held
 * to the same suite of tests once it implements [DriftSweepCapable] natively. It is honest about the
 * cost: a plain [PropositionStore] can filter by context and nothing else, so this class reads one
 * context's propositions and applies the mention-type filter, the ordering and the page bound in the
 * JVM. The context bound is real — the read never leaves the context, so no other tenant's data is
 * ever materialised — and the rest is the part a backend should push down.
 *
 * Because it does its own paging over a store read, a context whose propositions change underneath a
 * long sweep can shift between pages. That is inherent to paging a live store, and it is safe here:
 * a proposition the sweep misses is caught by the next check, and one it sees twice comes back as
 * already quarantined.
 *
 * @param propositions Where candidates are read from and quarantined copies are saved back to. The
 *   base persistence port: a sweep reads by context and saves, so requiring vector search, graph
 *   traversal and temporal query alongside would shut a plain store-and-retrieve backend out of
 *   drift work over capabilities it never uses.
 * @param listener Told about each real status transition as a [PropositionStatusChanged], so a
 *   consumer like `ProjectionLineageStaleCascade` hears about a quarantine, and about a release,
 *   without depending on whichever concrete [propositions] store happens to be wired in. Defaults to
 *   a no-op: everything else here holds with nobody listening.
 */
class PropositionStoreDriftSweep @JvmOverloads constructor(
    private val propositions: PropositionStore,
    private val listener: DiceEventListener = DiceEventListener.DEV_NULL,
) : DriftSweepCapable {

    private val logger = LoggerFactory.getLogger(PropositionStoreDriftSweep::class.java)

    /**
     * Reads the one context through [PropositionStore.findByContextId], then filters, sorts and
     * pages in the JVM.
     *
     * The context read is the part that matters for safety: a whole-store read would materialise
     * every tenant, and there is no call to one here. A backend implementing [DriftSweepCapable]
     * itself turns the three steps after the read into query clauses.
     */
    override fun quarantineCandidates(
        contextId: ContextId,
        mentionTypes: Set<String>,
        limit: Int,
        afterId: String?,
    ): List<Proposition> {
        require(limit > 0) { "limit must be positive, but was $limit" }
        if (mentionTypes.isEmpty()) return emptyList()

        return propositions.findByContextId(contextId)
            .filter { proposition -> proposition.mentions.any { it.type in mentionTypes } }
            .sortedBy { it.id }
            .filter { afterId == null || it.id > afterId }
            .take(limit)
    }

    override fun applyQuarantine(decision: QuarantineDecision.Quarantined): Proposition {
        val saved = propositions.save(decision.proposition)
        logger.debug("Quarantined proposition (id={}): {}", saved.id, decision.reason)
        // A proposition can arrive already STALE from ordinary decay (no quarantine reason yet, so
        // the policy still treats it as a fresh candidate) and get quarantined without its status
        // actually moving. Announcing a transition then would be a lie the listener has no way to
        // catch, so this only fires when something really changed.
        announce(saved, decision.previousStatus, saved.status, decision.reason)
        return saved
    }

    /**
     * Restores the status recorded under [DriftQuarantineKeys.PREVIOUS_STATUS] and drops both
     * quarantine keys in one save.
     *
     * A proposition with no readable previous status — quarantined by an older policy, or with the
     * key edited away — goes back to [PropositionStatus.ACTIVE]. Releasing says "let this back into
     * use", and `ACTIVE` is what that means when the record of where it came from is gone.
     */
    override fun releaseFromQuarantine(propositionId: String): Proposition? {
        val quarantined = propositions.findById(propositionId) ?: return null
        if (!quarantined.metadata.containsKey(DiceMetadataKeys.QUARANTINE_REASON)) {
            logger.debug("Proposition (id={}) carries no quarantine reason; nothing to release", propositionId)
            return null
        }

        val restoredStatus = previousStatusOf(quarantined)
        val released = propositions.save(
            quarantined.copy(
                status = restoredStatus,
                metadata = quarantined.metadata -
                    DiceMetadataKeys.QUARANTINE_REASON -
                    DriftQuarantineKeys.PREVIOUS_STATUS,
                metadataRevised = Instant.now(),
            ),
        )
        logger.debug("Released proposition (id={}) back to {}", released.id, restoredStatus)
        announce(released, quarantined.status, restoredStatus, RELEASE_REASON)
        return released
    }

    /**
     * The status this proposition carried before it was quarantined, or [PropositionStatus.ACTIVE]
     * when nothing readable was recorded.
     */
    private fun previousStatusOf(proposition: Proposition): PropositionStatus {
        val recorded = proposition.metadata[DriftQuarantineKeys.PREVIOUS_STATUS] as? String
            ?: return PropositionStatus.ACTIVE
        return runCatching { PropositionStatus.valueOf(recorded) }.getOrDefault(PropositionStatus.ACTIVE)
    }

    /** Tell the listener, and only when the status genuinely moved. */
    private fun announce(
        proposition: Proposition,
        previousStatus: PropositionStatus,
        newStatus: PropositionStatus,
        reason: String,
    ) {
        if (previousStatus == newStatus) return
        listener.onEvent(
            PropositionStatusChanged(
                proposition = proposition,
                previousStatus = previousStatus,
                newStatus = newStatus,
                reason = reason,
            ),
        )
    }

    private companion object {

        /** What a release event says, since a release clears the reason the quarantine carried. */
        const val RELEASE_REASON = "Released from schema drift quarantine"
    }
}

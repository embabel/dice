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
package com.embabel.dice.proposition

import com.embabel.agent.rag.service.Cluster
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.core.types.ZeroToOne
import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.common.PropositionPersisted
import com.embabel.dice.common.PropositionStatusChanged
import org.slf4j.LoggerFactory

/**
 * Decorator that emits lifecycle events at the persistence boundary.
 *
 * Wraps any [PropositionRepository] via `by delegate`. All read methods forward untouched.
 * Only the write path is instrumented:
 *
 * - [save] persists via the delegate first, then emits exactly one event carrying the saved
 *   instance. The event type depends on whether a status transition occurred:
 *     - [PropositionStatusChanged] when a prior entry exists with a different status (e.g.
 *       ACTIVE → CONTRADICTED), so consumers can distinguish transitions from plain inserts.
 *     - [PropositionPersisted] for fresh inserts and non-status updates.
 * - [saveAll] is explicitly overridden to call this decorator's own [save] per proposition,
 *   so one event fires per proposition. The default interface `saveAll` would bypass the
 *   decorator's [save] and emit nothing.
 *
 * To keep fresh ACTIVE inserts read-free, the prior-status lookup is skipped when the incoming
 * status is already ACTIVE. The tradeoff isn't only performance: it loses a real signal. A revival
 * STALE → ACTIVE (reinforcement — arguably the most interesting lifecycle transition for a downstream
 * index) is reported as a plain [PropositionPersisted], not a [PropositionStatusChanged]. A consumer
 * that needs the reinforcement signal should observe it at its origin — the reviser, which knows it is
 * reinforcing — rather than infer it here on the hot path.
 *
 * Throw isolation is the listener's responsibility — wrap the listener in `SafeDiceEventListener`
 * if you need graceful degradation.
 *
 * Example usage:
 * ```kotlin
 * val repo = EventEmittingPropositionRepository(
 *     delegate = inMemoryRepository,
 *     listener = SafeDiceEventListener(myListener),
 * )
 * ```
 *
 * @property delegate The underlying repository. All non-write methods forward here.
 * @property listener Notified after each persist. Defaults to [DiceEventListener.DEV_NULL] (no-op).
 */
class EventEmittingPropositionRepository(
    private val delegate: PropositionRepository,
    private val listener: DiceEventListener = DiceEventListener.DEV_NULL,
) : PropositionRepository by delegate {

    private val logger = LoggerFactory.getLogger(EventEmittingPropositionRepository::class.java)

    /**
     * Persists via the delegate, then emits one lifecycle event carrying the saved instance.
     *
     * The prior status is read only when the incoming status is non-ACTIVE, keeping fresh ACTIVE
     * inserts read-free. A [PropositionStatusChanged] is emitted when a prior entry existed with
     * a different status; otherwise a [PropositionPersisted] is emitted. The delegate always
     * completes before the event fires.
     *
     * @param proposition The proposition to persist.
     * @return The instance returned by `delegate.save`, also carried in the emitted event.
     */
    override fun save(proposition: Proposition): Proposition {
        // Gate the prior-status read to non-ACTIVE saves: a fresh insert defaults to ACTIVE, so
        // the common hot path never touches the delegate's read path.
        val previousStatus =
            if (proposition.status != PropositionStatus.ACTIVE) {
                delegate.findById(proposition.id)?.status
            } else {
                null
            }
        val saved = delegate.save(proposition)
        if (previousStatus != null && previousStatus != saved.status) {
            logger.debug("Emitting PropositionStatusChanged for {}: {} -> {}", saved.id.take(8), previousStatus, saved.status)
            listener.onEvent(
                PropositionStatusChanged(
                    proposition = saved,
                    previousStatus = previousStatus,
                    newStatus = saved.status,
                    reason = null,
                ),
            )
        } else {
            logger.debug("Emitting PropositionPersisted for {}", saved.id.take(8))
            listener.onEvent(PropositionPersisted(saved))
        }
        return saved
    }

    /**
     * Persists each proposition through this decorator's own [save], emitting one event per
     * proposition. Explicitly overridden — not delegated — because the default interface
     * `saveAll` forwards to `delegate.saveAll`, which bypasses this decorator's [save].
     *
     * @param propositions The propositions to persist.
     */
    override fun saveAll(propositions: Collection<Proposition>) {
        propositions.forEach { save(it) }
    }

    // ========================================================================
    // Explicit vector-capability forwarding
    //
    // These overrides are intentional: they pin the vector members as a tested seam so that
    // any future narrowing of the delegate type surfaces as a compile error here rather than
    // silently changing behavior. The delegate type guarantees the capability is present, so
    // no cast or empty-result fallback is needed.
    // ========================================================================

    override fun findSimilar(textSimilaritySearchRequest: TextSimilaritySearchRequest): List<Proposition> =
        delegate.findSimilar(textSimilaritySearchRequest)

    override fun findSimilarWithScores(
        textSimilaritySearchRequest: TextSimilaritySearchRequest,
    ): List<SimilarityResult<Proposition>> =
        delegate.findSimilarWithScores(textSimilaritySearchRequest)

    override fun findSimilarWithScores(
        textSimilaritySearchRequest: TextSimilaritySearchRequest,
        query: PropositionQuery,
    ): List<SimilarityResult<Proposition>> =
        delegate.findSimilarWithScores(textSimilaritySearchRequest, query)

    override fun findClusters(
        similarityThreshold: ZeroToOne,
        topK: Int,
        query: PropositionQuery,
    ): List<Cluster<Proposition>> =
        delegate.findClusters(similarityThreshold, topK, query)
}

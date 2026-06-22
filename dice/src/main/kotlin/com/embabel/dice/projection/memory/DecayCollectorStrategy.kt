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
package com.embabel.dice.projection.memory

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import org.slf4j.LoggerFactory

/**
 * A [CollectorStrategy] that marks propositions whose decayed confidence has fallen below a
 * staleness threshold.
 *
 * Rather than deleting anything directly, it reports a [MarkReason.Stale] mark and leaves the
 * actual decision to the sweep phase — which by default soft-transitions the proposition to
 * STALE rather than removing it.
 *
 * Stateless and read-only: the same candidates always produce the same marks, and the
 * repository is never consulted (the candidate set comes from the runner).
 *
 * @property retireBelow Effective-confidence threshold below which a candidate is marked stale.
 * @property retireDecayK Decay-rate multiplier for [Proposition.effectiveConfidence].
 */
class DecayCollectorStrategy @JvmOverloads constructor(
    private val retireBelow: Double,
    private val retireDecayK: Double = 2.0,
) : CollectorStrategy {

    private val logger = LoggerFactory.getLogger(DecayCollectorStrategy::class.java)

    override fun mark(
        candidates: List<Proposition>,
        repository: PropositionRepository,
        contextId: ContextId,
    ): List<PropositionMark> {
        val marks = candidates
            .filter { it.effectiveConfidence(retireDecayK) < retireBelow }
            .map { PropositionMark(propositionId = it.id, reason = MarkReason.Stale, strategyName = STRATEGY_NAME) }
        logger.debug("DecayCollectorStrategy: marked {} of {} candidates as stale (threshold={})", marks.size, candidates.size, retireBelow)
        return marks
    }

    companion object {
        private const val STRATEGY_NAME = "decay"
    }
}

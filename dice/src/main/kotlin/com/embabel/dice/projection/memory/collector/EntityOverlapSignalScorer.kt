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
package com.embabel.dice.projection.memory.collector

import com.embabel.agent.core.ContextId
import com.embabel.dice.spi.CandidatePair
import com.embabel.dice.spi.CollectorSignalScore
import com.embabel.dice.spi.CollectorSignalScorer
import kotlin.jvm.JvmOverloads

/**
 * Scores how much two propositions' entity mentions overlap, as Jaccard similarity of their
 * stable mention ids. Ports the `entityJaccard` slice of Me's `PropositionMatchScorer` blend
 * into its own scorer.
 *
 * When either proposition has no entity mentions at all, there's nothing to compare on the
 * entity axis — that's missing evidence, not a mismatch, so this scorer abstains rather than
 * blend in a jaccard of 0 as if the entities disagreed.
 *
 * @param weight contribution weight for this signal in the aggregated blend; defaults to 1.0.
 */
class EntityOverlapSignalScorer @JvmOverloads constructor(private val weight: Double = 1.0) : CollectorSignalScorer {

    override fun score(pair: CandidatePair, contextId: ContextId): CollectorSignalScore? {
        if (pair.anchor.mentions.isEmpty() || pair.member.mentions.isEmpty()) return null
        return CollectorSignalScore(
            signal = SIGNAL_NAME,
            score = jaccard(mentionIds(pair.anchor), mentionIds(pair.member)),
            weight = weight,
        )
    }

    companion object {
        private const val SIGNAL_NAME = "entity-overlap"
    }
}

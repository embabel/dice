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
 * Scores how much two propositions' grounding chunk ids overlap, as Jaccard similarity. Two
 * propositions extracted from the same source chunks are more likely restating the same fact.
 *
 * Abstains when either side has no grounding at all — there's no evidence to compare, so this
 * stays out of the blend rather than counting as a mismatch. Weighted conservatively (0.5):
 * shared grounding is corroborating, not conclusive — two different facts can be grounded in the
 * same chunk.
 *
 * @param weight contribution weight for this signal in the aggregated blend; defaults to 0.5.
 */
class GroundingOverlapSignalScorer @JvmOverloads constructor(private val weight: Double = 0.5) : CollectorSignalScorer {

    override fun score(pair: CandidatePair, contextId: ContextId): CollectorSignalScore? {
        if (pair.anchor.grounding.isEmpty() || pair.member.grounding.isEmpty()) return null
        return CollectorSignalScore(
            signal = SIGNAL_NAME,
            score = jaccard(pair.anchor.grounding.toSet(), pair.member.grounding.toSet()),
            weight = weight,
        )
    }

    companion object {
        private const val SIGNAL_NAME = "grounding-overlap"
    }
}

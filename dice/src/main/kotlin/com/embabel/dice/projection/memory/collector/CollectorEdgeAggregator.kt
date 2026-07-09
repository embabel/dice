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

import com.embabel.dice.spi.CandidatePair
import com.embabel.dice.spi.CollectorCandidateEdge
import com.embabel.dice.spi.CollectorSignalScore

/**
 * Blends every signal's verdict on one candidate pair into a single scored edge. Pure and
 * stateless — no repository, no IO.
 */
internal object CollectorEdgeAggregator {

    /**
     * Combine one pair's non-abstaining signal scores into a [CollectorCandidateEdge].
     *
     * The aggregate score is the weighted mean of every signal's score
     * (`Σ(weight*score) / Σ(weight)`), coerced into 0..1; a pair with no signals at all (every
     * scorer abstained) gets an aggregate score of 0. Any signal with `veto == true` forces
     * [CollectorCandidateEdge.vetoed] regardless of the blended score.
     *
     * @param pair the candidate pair the signals were scored against
     * @param signals every non-null [CollectorSignalScore] returned by a scorer for this pair
     *   (abstaining scorers should already be filtered out before calling this)
     */
    fun aggregate(pair: CandidatePair, signals: List<CollectorSignalScore>): CollectorCandidateEdge {
        val weightSum = signals.sumOf { it.weight }
        val aggregateScore = if (weightSum <= 0.0) {
            0.0
        } else {
            (signals.sumOf { it.weight * it.score } / weightSum).coerceIn(0.0, 1.0)
        }
        return CollectorCandidateEdge(
            anchorId = pair.anchor.id,
            memberId = pair.member.id,
            aggregateScore = aggregateScore,
            vetoed = signals.any { it.veto },
            signals = signals,
        )
    }
}

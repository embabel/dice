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
 * Turns the cosine similarity a [VectorCandidatePairSource] already computed into a signal
 * score, without re-embedding anything.
 *
 * Ports the `vector = cosine.coerceIn(0.0, 1.0)` slice of Me's `PropositionMatchScorer` blend.
 * Only speaks when the pair actually carries a proposal score — a pair proposed by a source
 * that never runs vector similarity has nothing for this scorer to say, so it abstains.
 *
 * @param weight contribution weight for this signal in the aggregated blend; defaults to 1.0.
 */
class VectorSignalScorer @JvmOverloads constructor(private val weight: Double = 1.0) : CollectorSignalScorer {

    override fun score(pair: CandidatePair, contextId: ContextId): CollectorSignalScore? {
        val proposalScore = pair.proposalScore ?: return null
        return CollectorSignalScore(
            signal = SIGNAL_NAME,
            score = proposalScore.coerceIn(0.0, 1.0),
            weight = weight,
        )
    }

    companion object {
        private const val SIGNAL_NAME = "vector"
    }
}

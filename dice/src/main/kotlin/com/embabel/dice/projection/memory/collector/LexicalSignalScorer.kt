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
import org.apache.commons.text.similarity.JaroWinklerSimilarity

/**
 * Scores how similar two propositions' surface text is, using Commons Text's Jaro-Winkler
 * similarity.
 *
 * Every proposition has text, so this scorer never abstains.
 *
 * @param weight contribution weight for this signal in the aggregated blend; defaults to 0.5.
 */
class LexicalSignalScorer @JvmOverloads constructor(private val weight: Double = 0.5) : CollectorSignalScorer {

    private val jaroWinkler = JaroWinklerSimilarity()

    override fun score(pair: CandidatePair, contextId: ContextId): CollectorSignalScore =
        CollectorSignalScore(
            signal = SIGNAL_NAME,
            score = jaroWinkler.apply(normalizedText(pair.anchor), normalizedText(pair.member)).coerceIn(0.0, 1.0),
            weight = weight,
        )

    companion object {
        private const val SIGNAL_NAME = "lexical"
    }
}

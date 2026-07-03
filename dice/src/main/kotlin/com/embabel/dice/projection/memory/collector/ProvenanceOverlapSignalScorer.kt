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
 * Net-new signal (no Me equivalent): scores how much two propositions' rich provenance overlaps,
 * as Jaccard similarity of their [com.embabel.dice.provenance.SourceLocator]s. Locators compare
 * by structural equality, so this catches "same source material" even when grounding chunk ids
 * differ or are absent.
 *
 * Abstains when either side has no provenance entries at all — there's no evidence to compare.
 * Weighted conservatively (0.5), same reasoning as grounding overlap: corroborating, not
 * conclusive on its own.
 *
 * @param weight contribution weight for this signal in the aggregated blend; defaults to 0.5.
 */
class ProvenanceOverlapSignalScorer @JvmOverloads constructor(private val weight: Double = 0.5) : CollectorSignalScorer {

    override fun score(pair: CandidatePair, contextId: ContextId): CollectorSignalScore? {
        if (pair.anchor.provenanceEntries.isEmpty() || pair.member.provenanceEntries.isEmpty()) return null
        val anchorLocators = pair.anchor.provenanceEntries.map { it.locator }.toSet()
        val memberLocators = pair.member.provenanceEntries.map { it.locator }.toSet()
        return CollectorSignalScore(
            signal = SIGNAL_NAME,
            score = jaccard(anchorLocators, memberLocators),
            weight = weight,
        )
    }

    companion object {
        private const val SIGNAL_NAME = "provenance-overlap"
    }
}

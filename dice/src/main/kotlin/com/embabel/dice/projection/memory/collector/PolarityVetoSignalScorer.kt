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

/**
 * Vetoes a merge when two propositions share an entity but assert opposite polarity about it —
 * one states something, the other negates it ("works at Acme" vs "no longer works at Acme" /
 * "left Acme"). Ports Me's `polarityVeto` / `containsNegation` / negation word and phrase lists
 * into its own scorer.
 *
 * This is a veto-only signal: it never contributes a positive score to the blend. When it fires
 * it returns a zero-weight veto so [CollectorEdgeAggregator] flips `vetoed = true` without
 * dragging the blended `aggregateScore` down — a vetoed edge's score should still reflect the
 * real corroborating signals; the edge is excluded from merging by `vetoed`, not by its score.
 * When it doesn't fire, it abstains (there's nothing else for this signal to say).
 *
 * Limits worth knowing:
 *  - It only fires when the mention sets actually overlap; propositions with no mentions never
 *    trigger it, since there's no shared-subject evidence.
 *  - It is a lexical negation-cue scan, not a semantic model — it can misfire on cues used
 *    non-negatively ("left a message") and miss negation phrased without a cue word. It's a
 *    guard rail for opposite-polarity merges, not an NLI classifier.
 */
class PolarityVetoSignalScorer : CollectorSignalScorer {

    override fun score(pair: CandidatePair, contextId: ContextId): CollectorSignalScore? {
        val sharedEntities = mentionIds(pair.anchor).intersect(mentionIds(pair.member)).isNotEmpty()
        if (!sharedEntities) return null
        val anchorNegated = containsNegation(normalizedText(pair.anchor))
        val memberNegated = containsNegation(normalizedText(pair.member))
        if (anchorNegated == memberNegated) return null
        return CollectorSignalScore(
            signal = SIGNAL_NAME,
            score = 0.0,
            weight = VETO_WEIGHT,
            veto = true,
            explanation = "opposite polarity about shared entities",
        )
    }

    /** True when the text carries a negation / cessation cue (a word or a short phrase). */
    internal fun containsNegation(text: String): Boolean {
        if (NEGATION_PHRASES.any { text.contains(it) }) return true
        val tokens = text.split(NON_WORD).filter { it.isNotBlank() }
        return tokens.any { it in NEGATION_WORDS }
    }

    companion object {
        private const val SIGNAL_NAME = "polarity-veto"

        /**
         * Must stay 0.0: this signal only ever contributes via [CollectorSignalScore.veto], never
         * via the weighted blend. A non-zero weight would drag a vetoed edge's aggregateScore
         * toward 0, hiding what the other signals actually thought of the pair.
         */
        private const val VETO_WEIGHT = 0.0

        private val NON_WORD = Regex("[^a-z']+")

        /** Multi-word cues that signal negation or a state that has ended. */
        private val NEGATION_PHRASES = listOf(
            "no longer",
            "used to",
            "no more",
        )

        /**
         * Single-word negation / cessation cues. Kept small and obvious; expanding it trades
         * more contradiction catches for more false vetoes.
         */
        private val NEGATION_WORDS = setOf(
            "not", "no", "never", "none",
            "left", "quit", "resigned", "departed", "former", "formerly",
            "stopped", "ceased", "ended",
            "isn't", "wasn't", "aren't", "doesn't", "didn't", "don't",
            "won't", "can't", "cannot", "without",
        )
    }
}

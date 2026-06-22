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
package com.embabel.dice.common

import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.revision.ConflictType
import org.slf4j.LoggerFactory

/**
 * A [TrustScorer] that scores a proposition purely by the authority of its source: more
 * authoritative tiers get a higher trust score. This is the natural step up from
 * [NeutralTrustScorer] for consumers who want "trust the source" behaviour without writing their
 * own model.
 *
 * The tier-to-score mapping is fully configurable — pass your own [weights] to retune it, or rely
 * on the [DEFAULT_WEIGHTS] below. A tier that is missing from the map (or a `null` tier, e.g. when
 * authority could not be resolved) scores [unknownScore]. Conflict type is ignored by default;
 * subclasses can override [score] to factor it in. Every score is clamped to `[0.0, 1.0]` so a
 * mis-tuned map can never produce an out-of-range value.
 *
 * @property weights trust score per authority tier
 * @property unknownScore score used when the tier is `null` or absent from [weights]
 */
open class AuthorityWeightedTrustScorer @JvmOverloads constructor(
    private val weights: Map<AuthorityTier, Double> = DEFAULT_WEIGHTS,
    private val unknownScore: Double = DEFAULT_UNKNOWN_SCORE,
) : TrustScorer {

    private val logger = LoggerFactory.getLogger(AuthorityWeightedTrustScorer::class.java)

    override fun score(
        proposition: Proposition,
        authorityTier: AuthorityTier?,
        conflictType: ConflictType?,
    ): Double {
        val raw = authorityTier?.let { weights[it] } ?: unknownScore
        val score = raw.coerceIn(0.0, 1.0)
        logger.trace("Trust score {} for proposition {} (tier={})", score, proposition.id.take(8), authorityTier)
        return score
    }

    companion object {

        /** Neutral half-confidence used when the source authority is unknown. */
        const val DEFAULT_UNKNOWN_SCORE: Double = 0.5

        /**
         * Sensible defaults: trust falls as the source gets more indirect, and an unknown source
         * lands at the neutral midpoint.
         */
        val DEFAULT_WEIGHTS: Map<AuthorityTier, Double> = mapOf(
            AuthorityTier.PRIMARY to 0.9,
            AuthorityTier.SECONDARY to 0.75,
            AuthorityTier.DERIVED to 0.6,
            AuthorityTier.UNKNOWN to DEFAULT_UNKNOWN_SCORE,
        )
    }
}

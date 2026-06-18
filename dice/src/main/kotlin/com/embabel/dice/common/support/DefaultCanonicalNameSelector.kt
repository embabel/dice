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
package com.embabel.dice.common.support

import com.embabel.dice.common.CanonicalNameSelector
import com.embabel.dice.common.resolver.searcher.NormalizedNameCandidateSearcher

/**
 * Default [com.embabel.dice.common.CanonicalNameSelector]. Stateless, thread-safe, side-effect
 * free — exposed as an `object` so callers can use it directly without
 * Spring DI when that's the right shape.
 */
object DefaultCanonicalNameSelector : CanonicalNameSelector {

    override fun select(candidates: Collection<String?>): String? {
        val cleaned = candidates
            .asSequence()
            .mapNotNull { it?.let { raw -> NormalizedNameCandidateSearcher.normalizeName(raw) } }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        if (cleaned.isEmpty()) return null
        // Stable ordering: when two candidates score equal, the first
        // one supplied by the caller wins. That gives callers
        // deterministic control over "all else equal, prefer X".
        return cleaned.maxByOrNull(::score)
    }

    /**
     * `internal` so the test in this module can exercise the scoring
     * function directly without going through the selector. The score
     * itself is an opaque integer — callers should never depend on
     * specific values, only on the ordering they imply.
     */
    internal fun score(name: String): Int {
        var s = 0
        if (' ' in name) s += MULTI_WORD_BONUS
        if (name.any { it.isUpperCase() }) s += MIXED_CASE_BONUS
        if (name.isNotEmpty() && name.first().isUpperCase()) s += TITLE_CASED_BONUS
        if (name == name.uppercase() && name.any { it.isLetter() }) s -= ALL_CAPS_PENALTY
        if ('@' in name) s -= LOOKS_LIKE_EMAIL_PENALTY
        if (name.any { it.isDigit() }) s -= HAS_DIGIT_PENALTY
        s += name.length // tiebreaker — longer often = more complete
        return s
    }

    // Scoring weights. Magnitude order matters more than the exact
    // numbers; tweak only with concrete failing cases in mind.
    private const val MULTI_WORD_BONUS = 1000
    private const val MIXED_CASE_BONUS = 500
    private const val TITLE_CASED_BONUS = 100
    private const val ALL_CAPS_PENALTY = 200
    private const val LOOKS_LIKE_EMAIL_PENALTY = 2000
    private const val HAS_DIGIT_PENALTY = 50
}

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
package com.embabel.dice.temporal

import java.time.Instant

/**
 * Optional temporal metadata for a fact. Every field is optional — attach only
 * what is known; a fact for which nothing temporal is tracked has no
 * [TemporalMetadata] at all.
 *
 * - **Observed / source time** ([observedAt]): when the underlying source
 *   material was authored — e.g. the date of the email this fact came from.
 *   Distinct from `Proposition.created` (when DICE ingested it): a fact recorded
 *   today from a 45-day-old email has `created = today` and `observedAt = 45
 *   days ago`. (See embabel/dice#26.)
 * - **Valid time** ([validFrom] / [validTo]): the window during which the fact
 *   holds in the world. A **non-null [validFrom] is what marks a fact as *dated*** —
 *   a known validity window, which does not decay — as opposed to merely
 *   decaying. A null [validTo] means "still valid / open-ended".
 * - **Invalidation** ([invalidatedAt]): when the fact was explicitly retracted,
 *   independent of its valid window.
 *
 * @property observedAt Source / effective date of the fact, or null if unknown
 * @property validFrom When this fact began to be true in the world, or null if unknown / not a dated fact
 * @property validTo When this fact stopped being true in the world, or null if open-ended
 * @property invalidatedAt When this fact was explicitly invalidated, or null if not invalidated
 * @property supersedes IDs of propositions this fact supersedes (replaces with newer truth)
 * @property contradicts IDs of propositions this fact contradicts
 */
data class TemporalMetadata @JvmOverloads constructor(
    val observedAt: Instant? = null,
    val validFrom: Instant? = null,
    val validTo: Instant? = null,
    val invalidatedAt: Instant? = null,
    val supersedes: List<String> = emptyList(),
    val contradicts: List<String> = emptyList(),
) {

    init {
        require(validFrom == null || validTo == null || !validTo.isBefore(validFrom)) {
            "validTo must be null or >= validFrom"
        }
    }

    /**
     * Whether this fact is current as of the given instant.
     *
     * A fact is current when [at] falls within its valid window
     * (`validFrom <= at`, and `at < validTo` when [validTo] is set) and the fact
     * has not been invalidated at or before [at].
     *
     * @param at The instant to evaluate currency against
     * @return true if the fact is valid and not invalidated as of [at]
     */
    fun isCurrentAsOf(at: Instant): Boolean {
        if (invalidatedAt != null && !invalidatedAt.isAfter(at)) return false
        if (validFrom != null && at.isBefore(validFrom)) return false
        if (validTo != null && !at.isBefore(validTo)) return false
        return true
    }

    /**
     * Whether this fact is current as of now.
     *
     * @return true if the fact is valid and not invalidated at the present instant
     */
    fun isCurrent(): Boolean = isCurrentAsOf(Instant.now())
}

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
package com.embabel.dice.proposition

import java.time.Instant

/**
 * Opt-in capability for time-window and effective-confidence queries over propositions.
 *
 * The defaults filter in memory via [findAll]; a temporal-native backend can override to push
 * the filter down to the store.
 */
interface TemporalQueryCapable {

    /**
     * Get all propositions. Used by the time-window defaults; satisfied automatically when
     * a type also implements [PropositionStore].
     */
    fun findAll(): List<Proposition>

    // ========================================================================
    // Temporal queries - default implementations work with any repository
    // ========================================================================

    /**
     * Find propositions created within a time range.
     *
     * @param start Start of range (inclusive)
     * @param end End of range (inclusive)
     * @return Propositions created within the range
     */
    fun findByCreatedBetween(start: Instant, end: Instant): List<Proposition> =
        findAll().filter { it.created in start..end }

    /**
     * Find propositions last touched within a time range. "Touched" means any
     * update — content or administrative — so this anchors on [Proposition.lastTouched]
     * (the later of contentRevised/metadataRevised), not the decay anchor alone.
     *
     * @param start Start of range (inclusive)
     * @param end End of range (inclusive)
     * @return Propositions last touched within the range
     */
    fun findByRevisedBetween(start: Instant, end: Instant): List<Proposition> =
        findAll().filter { it.lastTouched in start..end }

    // ========================================================================
    // Effective confidence queries - apply decay for ranking
    // ========================================================================

    /**
     * Find all propositions ordered by effective confidence (highest first).
     * Applies time-based decay to confidence scores.
     *
     * @param k Decay rate multiplier (default 2.0)
     * @return Propositions ordered by decayed confidence
     */
    fun findAllOrderedByEffectiveConfidence(k: Double = 2.0): List<Proposition> =
        findAll().sortedByDescending { it.effectiveConfidence(k) }

    /**
     * Find propositions with effective confidence above a threshold.
     *
     * @param threshold Minimum effective confidence (after decay)
     * @param k Decay rate multiplier (default 2.0)
     * @return Propositions with effective confidence >= threshold, ordered by confidence
     */
    fun findByEffectiveConfidenceAbove(threshold: Double, k: Double = 2.0): List<Proposition> =
        findAll()
            .filter { it.effectiveConfidence(k) >= threshold }
            .sortedByDescending { it.effectiveConfidence(k) }

    /**
     * Find propositions from a time range, ordered by effective confidence as of a point in time.
     * Useful for temporal analysis: "What was most confidently true during Q1?"
     *
     * @param start Start of creation range
     * @param end End of creation range
     * @param asOf Calculate effective confidence as of this time (defaults to end of range)
     * @param k Decay rate multiplier (default 2.0)
     * @return Propositions from range, ordered by effective confidence at the given time
     */
    fun findByCreatedBetweenOrderedByEffectiveConfidence(
        start: Instant,
        end: Instant,
        asOf: Instant = end,
        k: Double = 2.0,
    ): List<Proposition> =
        findByCreatedBetween(start, end)
            .sortedByDescending { it.effectiveConfidenceAt(asOf, k) }
}

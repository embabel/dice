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
package com.embabel.dice.spi

import com.embabel.dice.proposition.Proposition
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Policy SPI: classifies the [ConflictType] between an incoming proposition and an
 * existing one it appears to contradict.
 *
 * This separates *detecting* that two propositions clash (done elsewhere by the
 * reviser) from *classifying* the clash — is it a genuine contradiction, a revision,
 * or the world simply progressing? Consumers may supply their own implementation;
 * DICE ships a conservative default ([AlwaysContradictionDetector]) and a
 * temporal one ([TemporalConflictDetector]).
 */
fun interface ConflictDetector {

    /**
     * Classify the conflict between an incoming and an existing proposition.
     *
     * @param incoming The newly observed proposition
     * @param existing The pre-existing proposition it clashes with
     * @return the [ConflictType] describing the nature of the clash
     */
    fun detect(incoming: Proposition, existing: Proposition): ConflictType
}

/**
 * Conservative default [ConflictDetector] that classifies every clash as a
 * [ConflictType.Contradiction]. With it installed, conflict classification is a
 * no-op refinement and behaviour matches a reviser with no conflict typing.
 */
object AlwaysContradictionDetector : ConflictDetector {

    override fun detect(incoming: Proposition, existing: Proposition): ConflictType =
        ConflictType.Contradiction
}

/**
 * [ConflictDetector] that distinguishes world progression from genuine contradiction
 * using the proposition predicate and temporal recency.
 *
 * A clash is classified as [ConflictType.WorldProgression] when both hold:
 * - the predicate is in [evolvingPredicates] — i.e. a fact that legitimately changes
 *   over time, such as an employer or residence; and
 * - the [incoming] proposition is *not older* than the [existing] one.
 *
 * The predicate is read from `metadata[`[Proposition.PREDICATE]`]` on the [incoming]
 * proposition, falling back to the [existing] proposition's predicate when the incoming
 * one carries none. In a revision flow the stored/existing proposition is the likelier
 * carrier of enriched predicate metadata, while a freshly extracted incoming one may not
 * yet have a cached predicate — so considering both avoids mis-classifying the very
 * world-progression case this detector exists to catch.
 *
 * Recency uses the temporal anchor when present
 * (`temporal.observedAt`, falling back to `temporal.validFrom`), otherwise the
 * proposition's `contentRevised`.
 *
 * Tie rule: recency is compared with strict supersession. When the two anchors are the
 * *same* `Instant`, neither proposition strictly supersedes the other, so an equal-recency
 * clash on an evolving predicate is treated as [ConflictType.WorldProgression] rather than
 * a [ConflictType.Contradiction] — equal timestamps are deliberately *not* a temporal
 * contradiction. Only an incoming proposition that is strictly *older* falls back to
 * [ConflictType.Contradiction].
 *
 * Every other case — a stable predicate, an absent predicate on both propositions, or a
 * strictly older incoming — is classified conservatively as [ConflictType.Contradiction].
 *
 * No LLM or IO is involved; classification is deterministic and O(1).
 *
 * @property evolvingPredicates Lower-cased predicate names treated as time-evolving;
 *   defaults to [DEFAULT_EVOLVING_PREDICATES]
 */
class TemporalConflictDetector @JvmOverloads constructor(
    private val evolvingPredicates: Set<String> = DEFAULT_EVOLVING_PREDICATES,
) : ConflictDetector {

    private val logger = LoggerFactory.getLogger(TemporalConflictDetector::class.java)

    override fun detect(incoming: Proposition, existing: Proposition): ConflictType {
        val predicate = (
            (incoming.metadata[Proposition.PREDICATE] as? String)
                ?: (existing.metadata[Proposition.PREDICATE] as? String)
            )?.lowercase()
        if (predicate == null || predicate !in evolvingPredicates) {
            logger.debug("Conflict classified as Contradiction: predicate {} is not a tracked evolving predicate", predicate)
            return ConflictType.Contradiction
        }
        val incomingRecency = recencyOf(incoming)
        val existingRecency = recencyOf(existing)
        // Equal timestamps are not a temporal contradiction (neither strictly supersedes),
        // so only a strictly-older incoming falls back to Contradiction.
        val verdict = if (incomingRecency.isBefore(existingRecency)) {
            ConflictType.Contradiction
        } else {
            ConflictType.WorldProgression
        }
        logger.debug(
            "Conflict on evolving predicate '{}' classified as {} (incoming@{} vs existing@{})",
            predicate, verdict, incomingRecency, existingRecency,
        )
        return verdict
    }

    private fun recencyOf(proposition: Proposition): Instant =
        proposition.temporal?.observedAt
            ?: proposition.temporal?.validFrom
            ?: proposition.contentRevised

    companion object {
        /**
         * Default set of predicate names treated as time-evolving facts. Kept small
         * and configurable; consumers can supply their own set.
         *
         * Note: `"status"` here is a free-text *predicate* string (a fact like "status: married"),
         * unrelated to the [com.embabel.dice.proposition.PropositionStatus] enum — the name overlap is
         * coincidental.
         */
        @JvmField
        val DEFAULT_EVOLVING_PREDICATES: Set<String> =
            setOf("employer", "residence", "status", "role", "location", "title")
    }
}

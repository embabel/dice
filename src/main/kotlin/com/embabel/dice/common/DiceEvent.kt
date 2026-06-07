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

import com.embabel.agent.core.ContextId
import com.embabel.common.core.types.Timestamped
import com.embabel.dice.pipeline.PropositionExtractionStats
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.fasterxml.jackson.annotation.JsonTypeInfo
import java.time.Instant

@JsonTypeInfo(
    use = JsonTypeInfo.Id.CLASS,
    include = JsonTypeInfo.As.PROPERTY,
    property = "fqn"
)
interface DiceEvent : Timestamped

/**
 * Legacy empty contradiction signal.
 *
 * @deprecated Carries no payload. Replaced by [PropositionContradicted], which
 * carries the [ContextId] plus both the original and the contradicting proposition.
 * Retained (deprecate-only) this release for backward compatibility; will be removed later.
 *
 * @property timestamp When the event was created.
 */
@Deprecated(
    message = "Use PropositionContradicted, which carries contextId and both propositions.",
    replaceWith = ReplaceWith("PropositionContradicted"),
)
data class ContradictionEvent(
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * Emitted when a brand-new proposition is discovered (mirrors [com.embabel.dice.proposition.revision.RevisionResult.New]).
 *
 * @property proposition The newly discovered proposition.
 * @property timestamp When the event was created.
 */
data class PropositionDiscovered @JvmOverloads constructor(
    val proposition: Proposition,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * Emitted when an incoming proposition is merged into an existing one
 * (mirrors [com.embabel.dice.proposition.revision.RevisionResult.Merged]).
 *
 * @property original The pre-existing proposition.
 * @property revised The merged result.
 * @property timestamp When the event was created.
 */
data class PropositionMerged @JvmOverloads constructor(
    val original: Proposition,
    val revised: Proposition,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * Emitted when an existing proposition is reinforced by a similar new one
 * (mirrors [com.embabel.dice.proposition.revision.RevisionResult.Reinforced]).
 *
 * @property original The pre-existing proposition.
 * @property revised The reinforced result.
 * @property timestamp When the event was created.
 */
data class PropositionReinforced @JvmOverloads constructor(
    val original: Proposition,
    val revised: Proposition,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * Emitted when a new proposition contradicts an existing one
 * (mirrors [com.embabel.dice.proposition.revision.RevisionResult.Contradicted], whose
 * field is `new` rather than `revised`). Carries the [ContextId].
 *
 * @property contextId The context in which the contradiction was detected.
 * @property original The pre-existing proposition.
 * @property new The contradicting proposition.
 * @property timestamp When the event was created.
 */
data class PropositionContradicted @JvmOverloads constructor(
    val contextId: ContextId,
    val original: Proposition,
    val new: Proposition,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * Emitted when a higher-level proposition generalizes a group of source propositions
 * (mirrors [com.embabel.dice.proposition.revision.RevisionResult.Generalized]).
 *
 * @property proposition The generalizing (abstract) proposition.
 * @property generalizes The source propositions it abstracts over.
 * @property timestamp When the event was created.
 */
data class PropositionGeneralized @JvmOverloads constructor(
    val proposition: Proposition,
    val generalizes: List<Proposition>,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * The canonical durable signal: emitted at the persistence boundary once a proposition
 * has been saved. The full proposition snapshot is carried.
 *
 * @property proposition The persisted proposition (post-save state).
 * @property timestamp When the event was created.
 */
data class PropositionPersisted @JvmOverloads constructor(
    val proposition: Proposition,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * Emitted once a projection batch finishes, summarizing per-outcome counts
 * (from [com.embabel.dice.proposition.ProjectionResults]).
 *
 * @property successCount Number of successful projections.
 * @property skipCount Number of skipped projections.
 * @property failureCount Number of failed projections.
 * @property totalCount Total propositions in the batch.
 * @property timestamp When the event was created.
 */
data class ProjectionBatchCompleted @JvmOverloads constructor(
    val successCount: Int,
    val skipCount: Int,
    val failureCount: Int,
    val totalCount: Int,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * Emitted once an extraction batch finishes, carrying the revision [PropositionExtractionStats].
 *
 * @property stats Per-outcome statistics for the extraction batch.
 * @property timestamp When the event was created.
 */
data class ExtractionBatchCompleted @JvmOverloads constructor(
    val stats: PropositionExtractionStats,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * Emitted when a proposition's lifecycle [PropositionStatus] changes (e.g. ACTIVE → STALE
 * during a decay sweep, or STALE → ACTIVE on revival).
 *
 * This event is only *defined* here; emission wiring is handled separately by the
 * persistence-boundary repository decorator. The name is chosen so emission can be wired
 * without renaming.
 *
 * @property proposition The proposition whose status changed (post-transition state).
 * @property previousStatus The status before the transition.
 * @property newStatus The status after the transition.
 * @property reason Optional free-text explanation for the transition.
 */
data class PropositionStatusChanged(
    val proposition: Proposition,
    val previousStatus: PropositionStatus,
    val newStatus: PropositionStatus,
    val reason: String? = null,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * Emitted when a proposition is pinned (marked sweep-exempt).
 *
 * This event is only *defined* here; emission wiring is handled separately by the persistence-boundary decorator.
 *
 * @property proposition The proposition that was pinned.
 */
data class PropositionPinned(
    val proposition: Proposition,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * Emitted when a proposition is unpinned (no longer sweep-exempt).
 *
 * This event is only *defined* here; emission wiring is handled separately by the persistence-boundary decorator.
 *
 * @property proposition The proposition that was unpinned.
 */
data class PropositionUnpinned(
    val proposition: Proposition,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * Emitted when a post-extraction routing decision rejects a proposition (it should be
 * discarded rather than persisted). Carries the full proposition snapshot, matching the
 * convention of the other events here.
 *
 * Note: the proposition (including its text) is carried into consumer-supplied listeners,
 * identical exposure to every other [DiceEvent]; consumers control which listeners receive it.
 *
 * @property proposition The proposition that was rejected.
 * @property reason Developer-authored explanation for the rejection.
 * @property timestamp When the event was created.
 */
data class PropositionRejected @JvmOverloads constructor(
    val proposition: Proposition,
    val reason: String,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * Emitted when a post-extraction routing decision routes a proposition to a review queue
 * rather than persisting it directly. Carries the full proposition snapshot.
 *
 * @property proposition The proposition routed to review.
 * @property reason Developer-authored explanation for the routing.
 * @property timestamp When the event was created.
 */
data class PropositionRoutedToReview @JvmOverloads constructor(
    val proposition: Proposition,
    val reason: String,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * Emitted when a post-extraction routing decision excludes a proposition from projection
 * (it may still be persisted, but is not projected to downstream representations). Carries
 * the full proposition snapshot.
 *
 * @property proposition The proposition excluded from projection.
 * @property reason Developer-authored explanation for skipping projection.
 * @property timestamp When the event was created.
 */
data class PropositionProjectionSkipped @JvmOverloads constructor(
    val proposition: Proposition,
    val reason: String,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

fun interface DiceEventListener {
    fun onEvent(event: DiceEvent)

    companion object {
        val DEV_NULL: DiceEventListener = DevNull
    }
}

private object DevNull : DiceEventListener {
    override fun onEvent(event: DiceEvent) {
        // Do nothing
    }
}

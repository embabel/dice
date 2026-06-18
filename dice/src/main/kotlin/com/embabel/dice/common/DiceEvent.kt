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

/**
 * Something noteworthy that happened to a proposition as it moved through DICE — discovered,
 * merged, contradicted, saved, gone stale, and so on. Each subtype is a specific moment you
 * can listen for; every event knows when it occurred.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.CLASS,
    include = JsonTypeInfo.As.PROPERTY,
    property = "fqn"
)
interface DiceEvent : Timestamped

/**
 * The original contradiction signal, which carried no information about *what* was
 * contradicted.
 *
 * @deprecated Use [PropositionContradicted] instead — it tells you the context and hands
 * you both the original and the contradicting proposition. This empty version stays around
 * for one release so existing listeners keep compiling, and will be removed after that.
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
 * A brand-new proposition was discovered — nothing like it existed before.
 *
 * @property proposition The newly discovered proposition.
 * @property timestamp When the event was created.
 */
data class PropositionDiscovered @JvmOverloads constructor(
    val proposition: Proposition,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * An incoming proposition was folded into one we already had, producing a combined version.
 *
 * @property original The proposition that already existed.
 * @property revised The combined result after the merge.
 * @property timestamp When the event was created.
 */
data class PropositionMerged @JvmOverloads constructor(
    val original: Proposition,
    val revised: Proposition,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * A similar proposition came in and reinforced one we already trusted, strengthening it.
 *
 * @property original The proposition that already existed.
 * @property revised The strengthened result.
 * @property timestamp When the event was created.
 */
data class PropositionReinforced @JvmOverloads constructor(
    val original: Proposition,
    val revised: Proposition,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * A new proposition directly contradicts one we already held. Both are handed to listeners
 * so they can decide how to reconcile them.
 *
 * @property contextId The context in which the contradiction surfaced.
 * @property original The proposition that already existed.
 * @property new The new proposition that contradicts it.
 * @property timestamp When the event was created.
 */
data class PropositionContradicted @JvmOverloads constructor(
    val contextId: ContextId,
    val original: Proposition,
    val new: Proposition,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * A higher-level proposition was formed that generalizes over a group of more specific ones.
 *
 * @property proposition The abstract proposition that was formed.
 * @property generalizes The specific propositions it summarizes.
 * @property timestamp When the event was created.
 */
data class PropositionGeneralized @JvmOverloads constructor(
    val proposition: Proposition,
    val generalizes: List<Proposition>,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * A proposition was saved. This is the signal to rely on when you care that something
 * actually made it to durable storage, not just that it was proposed — it fires once the
 * write is done and carries the saved state.
 *
 * @property proposition The proposition as it was saved.
 * @property timestamp When the event was created.
 */
data class PropositionPersisted @JvmOverloads constructor(
    val proposition: Proposition,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * A batch of propositions finished projecting, with a tally of how each one turned out.
 *
 * @property successCount How many projected successfully.
 * @property skipCount How many were skipped.
 * @property failureCount How many failed.
 * @property totalCount How many propositions were in the batch.
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
 * A batch of text finished extraction, carrying the stats on what came out of it.
 *
 * @property stats How the extraction batch broke down by outcome.
 * @property timestamp When the event was created.
 */
data class ExtractionBatchCompleted @JvmOverloads constructor(
    val stats: PropositionExtractionStats,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * A proposition moved to a different lifecycle [PropositionStatus] — for example going stale
 * during a decay sweep, or coming back to life when it's seen again.
 *
 * @property proposition The proposition, in its state after the change.
 * @property previousStatus Where it was before.
 * @property newStatus Where it is now.
 * @property reason An optional note on why it changed.
 */
data class PropositionStatusChanged(
    val proposition: Proposition,
    val previousStatus: PropositionStatus,
    val newStatus: PropositionStatus,
    val reason: String? = null,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * A proposition was pinned, so decay sweeps will leave it alone.
 *
 * @property proposition The proposition that was pinned.
 */
data class PropositionPinned(
    val proposition: Proposition,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * A proposition was unpinned, so it's back in scope for decay sweeps.
 *
 * @property proposition The proposition that was unpinned.
 */
data class PropositionUnpinned(
    val proposition: Proposition,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * A proposition was turned away after extraction — it should be discarded rather than saved.
 * Like every other event here, it carries the whole proposition (text included) to whichever
 * listeners you've wired up.
 *
 * @property proposition The proposition that was rejected.
 * @property reason Why it was rejected.
 * @property timestamp When the event was created.
 */
data class PropositionRejected @JvmOverloads constructor(
    val proposition: Proposition,
    val reason: String,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * A proposition was set aside for a human (or a later pass) to look at, rather than being
 * saved straight away.
 *
 * @property proposition The proposition sent to review.
 * @property reason Why it needs a second look.
 * @property timestamp When the event was created.
 */
data class PropositionRoutedToReview @JvmOverloads constructor(
    val proposition: Proposition,
    val reason: String,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * A proposition was kept out of projection — it may still be saved, but it won't be pushed
 * out to the downstream representations (graph, Prolog, memory, and so on).
 *
 * @property proposition The proposition that won't be projected.
 * @property reason Why projection was skipped.
 * @property timestamp When the event was created.
 */
data class PropositionProjectionSkipped @JvmOverloads constructor(
    val proposition: Proposition,
    val reason: String,
    override val timestamp: Instant = Instant.now(),
) : DiceEvent

/**
 * Implement this to react to [DiceEvent]s as they happen. Keep in mind handlers run inline
 * on the emitting thread, so do anything slow off to the side.
 */
fun interface DiceEventListener {
    fun onEvent(event: DiceEvent)

    companion object {
        /** A listener that ignores everything — handy as a default when no one's listening. */
        val DEV_NULL: DiceEventListener = DevNull
    }
}

private object DevNull : DiceEventListener {
    override fun onEvent(event: DiceEvent) {
        // Intentionally ignores every event.
    }
}

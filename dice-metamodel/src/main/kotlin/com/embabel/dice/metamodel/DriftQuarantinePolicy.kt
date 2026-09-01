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
package com.embabel.dice.metamodel

import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus

/**
 * Metadata keys the metamodel writes onto a proposition, alongside the shared
 * [com.embabel.dice.common.DiceMetadataKeys.QUARANTINE_REASON].
 *
 * They live here because drift quarantine is the only thing that writes or reads them, and the core
 * proposition model has no business knowing about schema versioning. The naming follows the same
 * `dice.<area>.<name>` convention, so nothing collides with a consumer's own keys.
 */
object DriftQuarantineKeys {

    /**
     * The [PropositionStatus] a proposition carried at the moment it was quarantined, stored as its
     * `name`.
     *
     * Quarantine moves a proposition to `STALE`, and `STALE` is a destination several roads lead to
     * — ordinary decay reaches it as well. Without this key, releasing a quarantine could only guess
     * where to put the proposition back. With it, release is exact:
     * [DriftSweepCapable.releaseFromQuarantine] reads the value, restores that status, and clears
     * both keys.
     */
    const val PREVIOUS_STATUS = "dice.metamodel.quarantine.previousStatus"
}

/**
 * What a policy decided about one [Proposition].
 *
 * Four outcomes. [AlreadyQuarantined] covers a proposition an earlier sweep already quarantined:
 * counting it as conforming would overstate how clean the set is, and it isn't newly quarantined
 * either, because this sweep leaves it alone to preserve its original reason. [Protected] covers a
 * pinned proposition that a lossy change would otherwise have caught: pinning is DICE's
 * cross-cutting "must retain" promise, so this sweep leaves it alone too, and still says what the
 * schema change would have done to it. Each keeps `conforming.size` accurate.
 *
 * Sealed, so a `when` over the outcomes is exhaustive and the compiler speaks up if a fifth
 * ever lands.
 */
sealed interface QuarantineDecision {

    /**
     * Nothing in the schema change touches this proposition; it needs no action.
     *
     * @property proposition The proposition, unchanged.
     */
    data class Conforming(val proposition: Proposition) : QuarantineDecision

    /**
     * An earlier sweep already quarantined this one: it is `STALE` and carries a
     * `DiceMetadataKeys.QUARANTINE_REASON`, so this sweep left it as it found it. Nothing needs
     * persisting for these.
     *
     * To force one back through evaluation, clear its `QUARANTINE_REASON` metadata and pass it in
     * again.
     *
     * @property proposition The proposition, unchanged.
     * @property originalReason The reason the earlier sweep recorded, when it is still readable as
     *   text. `null` when the metadata value is present but isn't a string. The proposition still
     *   counts as already quarantined; only the explanation is unrecoverable.
     */
    data class AlreadyQuarantined(
        val proposition: Proposition,
        val originalReason: String?,
    ) : QuarantineDecision

    /**
     * Schema drift stranded this proposition, and it has been flagged.
     *
     * [proposition] is an immutable copy already moved to `STALE` and annotated with the reason
     * under `DiceMetadataKeys.QUARANTINE_REASON` and the status it came from under
     * [DriftQuarantineKeys.PREVIOUS_STATUS]. The original is never mutated, and nothing is written
     * anywhere; persisting the copy is the caller's job.
     *
     * @property proposition The flagged, `STALE` copy.
     * @property reason A human-readable explanation of why it was quarantined.
     * @property affectedMentionTypes The entity type names that triggered it.
     * @property previousStatus The status the proposition carried before this decision, which
     *   [DriftSweepCapable.releaseFromQuarantine] restores. `STALE` when the proposition was already
     *   stale from ordinary decay, in which case quarantine wrote a reason and moved no status.
     */
    data class Quarantined(
        val proposition: Proposition,
        val reason: String,
        val affectedMentionTypes: Set<String>,
        val previousStatus: PropositionStatus,
    ) : QuarantineDecision

    /**
     * A pinned proposition that a lossy schema change would otherwise have quarantined. Pinning
     * promises cross-cutting immunity from reclamation (see `PropositionStore.pin`), so this sweep
     * leaves it exactly as it was — an unpinned match on the same change gets flipped to `STALE`,
     * this one doesn't — and reports it here so an operator reading the sweep can still see it was
     * affected.
     *
     * [proposition] is the original, completely untouched: no status change, no metadata written.
     * Persisting it is never necessary, unlike [Quarantined]'s copy.
     *
     * @property proposition The pinned proposition, unchanged.
     * @property reason The same explanation an unpinned match would have carried, so an operator
     *   knows what the schema change was.
     * @property affectedMentionTypes The entity type names that would have triggered quarantine.
     */
    data class Protected(
        val proposition: Proposition,
        val reason: String,
        val affectedMentionTypes: Set<String>,
    ) : QuarantineDecision
}

/**
 * What a whole sweep decided, with one decision per proposition it was given.
 *
 * @property conforming Propositions the change doesn't touch.
 * @property quarantined Propositions this sweep flagged, as `STALE` copies waiting to be persisted.
 * @property alreadyQuarantined Propositions an earlier sweep had already flagged, left untouched by
 *   this one. Empty unless the input contained some.
 * @property protected Pinned propositions a lossy change would otherwise have caught, left
 *   untouched because pinning promises immunity. Empty unless the input contained some.
 */
data class QuarantineResult @JvmOverloads constructor(
    val conforming: List<QuarantineDecision.Conforming>,
    val quarantined: List<QuarantineDecision.Quarantined>,
    val alreadyQuarantined: List<QuarantineDecision.AlreadyQuarantined> = emptyList(),
    val protected: List<QuarantineDecision.Protected> = emptyList(),
) {

    /** How many propositions the sweep looked at. */
    val total: Int get() = conforming.size + quarantined.size + alreadyQuarantined.size + protected.size

    /** Every proposition the sweep saw, in one flat list. */
    val allPropositions: List<Proposition>
        get() = conforming.map { it.proposition } +
            quarantined.map { it.proposition } +
            alreadyQuarantined.map { it.proposition } +
            protected.map { it.proposition }
}

/**
 * Decides which propositions a schema change has stranded, and flags them.
 *
 * Quarantining is non-destructive. An affected proposition comes back as an immutable copy moved
 * to [com.embabel.dice.proposition.PropositionStatus.STALE] with a metadata note explaining why;
 * the original is untouched and nothing is written to any store. Persisting the copies is the
 * caller's job, which is what lets a drift check preview a sweep without changing anything.
 *
 * It takes a [MetamodelDiff], a comparison of two declared versions, which is what says exactly
 * which types the schema stopped recognising. A drift check compares a declaration against a live
 * graph and synthesizes the equivalent diff, so quarantine is decided on one kind of input.
 *
 * ```kotlin
 * val diff = result.quarantineDiff
 * val swept = sweepStore.sweep(diff, policy, contextId)
 * ```
 */
interface DriftQuarantinePolicy {

    /**
     * Evaluate every proposition against [diff].
     *
     * Implementations must be idempotent: a proposition already quarantined by a prior sweep
     * (`STALE` with a `QUARANTINE_REASON`) must keep its original reason. Those come back unchanged
     * as [QuarantineDecision.AlreadyQuarantined], not as conforming, which would report them as
     * clean. Clear the metadata key to force one back through evaluation.
     *
     * That classification does not depend on [diff]. Being already quarantined is a fact about the
     * proposition, so an empty or purely additive diff must still sort those into
     * [QuarantineResult.alreadyQuarantined] rather than short-circuiting the whole input into
     * [QuarantineResult.conforming]. Drift checks run on a schedule and most runs find nothing, so
     * short-circuiting would report quarantined records as conforming on those runs.
     *
     * A pinned proposition a lossy change would otherwise catch must never be flipped to `STALE`:
     * implementations report it as [QuarantineResult.protected] instead, leaving the proposition
     * itself untouched. This holds even for one an earlier sweep already quarantined before it was
     * pinned; that one is [QuarantineResult.alreadyQuarantined], since idempotency (above) takes
     * priority over the pin.
     *
     * @param diff What changed between the old and new schema.
     * @param propositions The propositions to evaluate. Any [Iterable] will do: a list, a
     *   repository page, a lazy sequence.
     * @return One decision per input proposition.
     */
    fun evaluate(diff: MetamodelDiff, propositions: Iterable<Proposition>): QuarantineResult

    /**
     * Every entity type name that, appearing as a mention type, could make a proposition a candidate
     * under [diff].
     *
     * This is what lets a sweep ask its store for a narrow, bounded set of propositions
     * ([DriftSweepCapable.quarantineCandidates]) with no policy knowledge of its own. Which names
     * matter is a policy judgement — a removed type's declared former names count, an added type's
     * name doesn't — so the policy is the only thing that can answer it.
     *
     * **The contract that makes bounded selection sound:** if a proposition's mention types are all
     * outside this set, [evaluate] must classify it as [QuarantineDecision.Conforming] or
     * [QuarantineDecision.AlreadyQuarantined]. A sweep never reads such a proposition, so a policy
     * that would have quarantined one anyway silently strands data.
     *
     * Include every spelling a mention can use. A declared type name can be fully qualified where
     * the graph writes the simple label, so a policy matching both must list both here, or a
     * bounded sweep asks for a spelling the store has never seen.
     *
     * An empty result means the diff could strand nothing, and a sweep then reads no propositions at
     * all.
     *
     * @param diff What changed between the old and new schema.
     * @return The mention type names worth reading. Empty when nothing in [diff] can strand data.
     */
    fun candidateMentionTypes(diff: MetamodelDiff): Set<String>
}

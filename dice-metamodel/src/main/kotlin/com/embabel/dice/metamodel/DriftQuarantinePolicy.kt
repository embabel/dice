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

/**
 * What a policy decided about one [Proposition].
 *
 * Three outcomes, not two, and the third is the one that's easy to miss: a proposition an earlier
 * sweep already quarantined. It isn't clean, so calling it conforming would overstate how healthy
 * the set is, and it isn't newly quarantined either, since this sweep deliberately left it alone to
 * preserve its original reason. It gets its own variant so `conforming.size` means what it says.
 *
 * Sealed, so a `when` over the outcomes is exhaustive and the compiler speaks up if a fourth
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
     * An earlier sweep already quarantined this one — it is `STALE` and carries a
     * `DiceMetadataKeys.QUARANTINE_REASON` — so this sweep left it exactly as it found it. Nothing
     * needs persisting for these.
     *
     * To force one back through evaluation, clear its `QUARANTINE_REASON` metadata and pass it in
     * again.
     *
     * @property proposition The proposition, unchanged.
     * @property originalReason The reason the earlier sweep recorded, when it is still readable as
     *   text. `null` when the metadata value is present but isn't a string — the proposition still
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
     * under `DiceMetadataKeys.QUARANTINE_REASON`. The original is never mutated, and nothing is
     * written anywhere — persisting the copy is the caller's job.
     *
     * @property proposition The flagged, `STALE` copy.
     * @property reason A human-readable explanation of why it was quarantined.
     * @property affectedMentionTypes The entity type names that triggered it.
     */
    data class Quarantined(
        val proposition: Proposition,
        val reason: String,
        val affectedMentionTypes: Set<String>,
    ) : QuarantineDecision
}

/**
 * What a whole sweep decided, with one decision per proposition it was given.
 *
 * @property conforming Propositions the change doesn't touch — genuinely clean.
 * @property quarantined Propositions this sweep flagged, as `STALE` copies waiting to be persisted.
 * @property alreadyQuarantined Propositions an earlier sweep had already flagged, left untouched by
 *   this one. Empty unless the input contained some.
 */
data class QuarantineResult @JvmOverloads constructor(
    val conforming: List<QuarantineDecision.Conforming>,
    val quarantined: List<QuarantineDecision.Quarantined>,
    val alreadyQuarantined: List<QuarantineDecision.AlreadyQuarantined> = emptyList(),
) {

    /** How many propositions the sweep looked at. */
    val total: Int get() = conforming.size + quarantined.size + alreadyQuarantined.size

    /** Every proposition the sweep saw, in one flat list. */
    val allPropositions: List<Proposition>
        get() = conforming.map { it.proposition } +
            quarantined.map { it.proposition } +
            alreadyQuarantined.map { it.proposition }
}

/**
 * Decides which propositions a schema change has stranded, and flags them.
 *
 * Quarantining is **non-destructive**. An affected proposition comes back as an immutable copy
 * moved to [com.embabel.dice.proposition.PropositionStatus.STALE] with a metadata note explaining
 * why; the original is untouched and nothing is written to any store. Persisting the copies is
 * deliberately the caller's job — the policy is a decision, not an effect, which is what lets a
 * drift check preview one without changing anything.
 *
 * It takes a [MetamodelDiff] — a comparison of two *declared* versions — because "what did the
 * schema stop recognising?" is the question that matters, and a diff answers it precisely. A drift
 * check, which compares a declaration against a live graph instead, synthesizes the equivalent diff
 * rather than re-deciding quarantine on its own terms.
 *
 * ```kotlin
 * val diff = differ.diff(previousVersion, currentVersion)
 * val result = policy.evaluate(diff, repository.findAll())
 * result.quarantined.forEach { repository.save(it.proposition) }
 * ```
 */
interface DriftQuarantinePolicy {

    /**
     * Evaluate every proposition against [diff].
     *
     * Implementations must be **idempotent**: a proposition already quarantined by a prior sweep
     * (`STALE` with a `QUARANTINE_REASON`) must not have its original reason overwritten. Those come
     * back unchanged as [QuarantineDecision.AlreadyQuarantined] — not as conforming, which would
     * misreport them as clean. Clear the metadata key to force one back through evaluation.
     *
     * That classification does not depend on [diff]. Being already quarantined is a fact about the
     * proposition, so an empty or purely additive diff must still sort those into
     * [QuarantineResult.alreadyQuarantined] rather than short-circuiting the whole input into
     * [QuarantineResult.conforming]. Drift checks run on a schedule and most of them find nothing,
     * so a shortcut there would make quarantined records look healthy nearly every run.
     *
     * @param diff What changed between the old and new schema.
     * @param propositions The propositions to evaluate. Any [Iterable] will do — a list, a
     *   repository page, a lazy sequence.
     * @return One decision per input proposition.
     */
    fun evaluate(diff: MetamodelDiff, propositions: Iterable<Proposition>): QuarantineResult
}

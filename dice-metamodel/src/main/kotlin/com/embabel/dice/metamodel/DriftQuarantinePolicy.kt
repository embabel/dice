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
 * The outcome of evaluating a single [Proposition] against a [MetamodelDiff].
 *
 * Sealed so callers can exhaustively handle every outcome kind.
 */
sealed interface QuarantineDecision {

    /** The proposition conforms to the updated schema and requires no action. */
    data class Conforming(val proposition: Proposition) : QuarantineDecision

    /**
     * The proposition is affected by schema drift and has been flagged as quarantined.
     *
     * The returned [proposition] has already been transitioned to `STALE` and carries
     * the quarantine reason in its metadata under `DiceMetadataKeys.QUARANTINE_REASON`.
     * The original proposition is **not** mutated — this is an immutable copy.
     *
     * @property proposition The flagged, STALE copy of the original proposition.
     * @property reason A human-readable explanation of why this proposition was quarantined.
     * @property affectedMentionTypes The entity type names that triggered quarantine.
     */
    data class Quarantined(
        val proposition: Proposition,
        val reason: String,
        val affectedMentionTypes: Set<String>,
    ) : QuarantineDecision
}

/**
 * The aggregate result of applying a [DriftQuarantinePolicy] to a collection of propositions.
 *
 * @property conforming Propositions that were unaffected by the diff.
 * @property quarantined Quarantine decisions for propositions that were flagged.
 */
data class QuarantineResult(
    val conforming: List<QuarantineDecision.Conforming>,
    val quarantined: List<QuarantineDecision.Quarantined>,
) {

    /** Total number of propositions evaluated. */
    val total: Int get() = conforming.size + quarantined.size

    /** All proposition copies (conforming + quarantined) in a single flat list. */
    val allPropositions: List<Proposition>
        get() = conforming.map { it.proposition } + quarantined.map { it.proposition }
}

/**
 * Decides which propositions are affected by a [MetamodelDiff] and produces quarantine decisions.
 *
 * Quarantining is **non-destructive**: affected propositions are returned as immutable copies
 * transitioned to [com.embabel.dice.proposition.PropositionStatus.STALE] with a metadata
 * annotation explaining the reason. The caller is responsible for persisting the updated copies.
 *
 * Example usage:
 * ```kotlin
 * val diff = differ.diff(oldSchema, newSchema)
 * val result = policy.evaluate(diff, repository.findAll())
 * result.quarantined.forEach { decision ->
 *     repository.save(decision.proposition)
 * }
 * ```
 */
interface DriftQuarantinePolicy {

    /**
     * Evaluate every proposition in [propositions] against [diff] and return a [QuarantineResult]
     * partitioning them into conforming and quarantined groups.
     *
     * Implementations should be **idempotent**: a proposition that is already quarantined (status
     * `STALE` with a `QUARANTINE_REASON` metadata entry) from a prior sweep must not have its
     * original reason overwritten. Such propositions are placed in the conforming group unchanged.
     * To force re-evaluation of a previously quarantined proposition, clear its `QUARANTINE_REASON`
     * metadata before passing it here.
     *
     * @param diff The metamodel diff describing what changed between the old and new schema.
     * @param propositions The propositions to evaluate. May be any [Iterable] (list, repository
     *   page, lazy sequence, etc.).
     * @return An immutable [QuarantineResult] with one decision per input proposition.
     */
    fun evaluate(diff: MetamodelDiff, propositions: Iterable<Proposition>): QuarantineResult
}

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
import com.embabel.dice.proposition.PropositionStatus

/**
 * Policy SPI: determines whether a proposition should transition lifecycle status.
 *
 * Lifecycle state is the *output* of a policy evaluation over proposition signals
 * (effective confidence, importance, reinforcement, pinned). The [PropositionStatus]
 * enum is a label applied after the policy runs; this interface separates "what state
 * should this proposition be in?" (policy) from "what is its current state?" (the label).
 *
 * Implementations are stateless and are invoked per-proposition by a sweep
 * (see [com.embabel.dice.proposition.DecaySweeper]). Return `null` to indicate
 * that no transition is needed.
 *
 * Consumers may supply their own implementations to handle domain-specific staleness
 * signals (e.g. a schema-version policy) without editing DICE core.
 *
 * Example usage:
 * ```kotlin
 * val policy: StatusTransitionPolicy = DecayStatusPolicy()
 * val target = policy.evaluate(proposition)
 * if (target != null) {
 *     // proposition.withStatus(target)
 * }
 * ```
 */
fun interface StatusTransitionPolicy {

    /**
     * Evaluate the target lifecycle status for a proposition.
     *
     * @param proposition The proposition to evaluate
     * @return The [PropositionStatus] the proposition should transition to, or `null`
     *   if no transition is needed.
     */
    fun evaluate(proposition: Proposition): PropositionStatus?
}

/**
 * Default [StatusTransitionPolicy]: transitions ACTIVE → STALE when a proposition's
 * decayed utility drops below [stalenessThreshold], and STALE → ACTIVE when utility
 * recovers above [recoveryThreshold].
 *
 * The two thresholds form a hysteresis band: a proposition whose utility sits between
 * [stalenessThreshold] and [recoveryThreshold] does not transition, which prevents
 * oscillation around a single cut-off. Pinned propositions are sweep-exempt and always
 * return `null`.
 *
 * Utility composite:
 * ```
 * utility = effectiveConfidence(kMultiplier)
 *         * (1 + importanceWeight  * importance)
 *         * (1 + reinforceWeight   * ln1p(reinforceCount))
 * ```
 * With the default weights of 0.0, utility reduces to plain decayed effective confidence.
 *
 * This composite is a sweep-time approximation: it lets a consumer opt into
 * reinforcement/importance weighting at sweep time.
 *
 * Recovery (STALE → ACTIVE) only fires when something re-anchors the proposition. Utility
 * is driven by [Proposition.effectiveConfidence], which decays monotonically against the
 * `contentRevised` anchor, so the recovery branch below cannot fire from the passage of time
 * alone — it becomes reachable when a reviser refreshes `contentRevised` (and/or raises
 * confidence) on reinforcement. The branch is included so the policy contract is complete:
 * revival works as soon as such a re-anchoring path runs.
 *
 * @property stalenessThreshold ACTIVE propositions with utility strictly below this become STALE.
 * @property recoveryThreshold STALE propositions with utility strictly above this become ACTIVE.
 * @property kMultiplier Decay-rate multiplier passed to [Proposition.effectiveConfidence].
 * @property importanceWeight Weight applied to importance in the utility composite (0.0 = ignore).
 * @property reinforceWeight Weight applied to ln1p(reinforceCount) in the utility composite (0.0 = ignore).
 */
class DecayStatusPolicy(
    val stalenessThreshold: Double = 0.1,
    val recoveryThreshold: Double = 0.2,
    val kMultiplier: Double = 2.0,
    val importanceWeight: Double = 0.0,
    val reinforceWeight: Double = 0.0,
) : StatusTransitionPolicy {

    override fun evaluate(proposition: Proposition): PropositionStatus? {
        if (proposition.pinned) return null
        val utility = proposition.effectiveConfidence(kMultiplier) *
            (1 + importanceWeight * proposition.importance) *
            (1 + reinforceWeight * kotlin.math.ln(1.0 + proposition.reinforceCount.toDouble()))
        return when {
            proposition.status == PropositionStatus.ACTIVE && utility < stalenessThreshold ->
                PropositionStatus.STALE
            proposition.status == PropositionStatus.STALE && utility > recoveryThreshold ->
                PropositionStatus.ACTIVE
            else -> null
        }
    }
}

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
package com.embabel.dice.proposition.gate

import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.common.PropositionDemoted
import com.embabel.dice.common.PropositionProjectionSkipped
import com.embabel.dice.common.PropositionRejected
import com.embabel.dice.common.PropositionRoutedToReview
import com.embabel.dice.common.SafeDiceEventListener
import com.embabel.dice.proposition.Proposition
import org.slf4j.LoggerFactory

/**
 * Decorator that adds event emission to any [ExtractionGate].
 *
 * Delegates the evaluation to the wrapped gate and then, depending on the [GateDecision],
 * emits the matching routing event through the supplied [DiceEventListener]. The delegate's
 * [GateEvaluation] is always returned unchanged — the decorator fires events, it never alters
 * the decision.
 *
 * Event mapping:
 * - [GateDecision.Reject] → [PropositionRejected]
 * - [GateDecision.RouteToReview] → [PropositionRoutedToReview]
 * - [GateDecision.SkipProjection] → [PropositionProjectionSkipped]
 * - [GateDecision.Demote] → [PropositionDemoted]
 * - [GateDecision.Persist] → nothing emitted; the persist signal fires at the save boundary
 *   and emitting one here would double-emit it.
 *
 * Each emitted event carries the full [Proposition]. Wrap the listener in [SafeDiceEventListener]
 * so a throwing listener cannot abort the gate run; this decorator does not handle exceptions itself.
 *
 * Example usage:
 * ```kotlin
 * val gate = ObservableGate(confidenceGate, SafeDiceEventListener(myListener))
 * val evaluation = gate.evaluate(proposition, gateContext)
 * ```
 *
 * @property delegate the underlying gate to wrap
 * @property listener the listener that receives routing events; defaults to
 *   [DiceEventListener.DEV_NULL] (no observation)
 */
class ObservableGate(
    private val delegate: ExtractionGate,
    private val listener: DiceEventListener = DiceEventListener.DEV_NULL,
) : ExtractionGate {

    private val logger = LoggerFactory.getLogger(ObservableGate::class.java)

    override fun evaluate(
        proposition: Proposition,
        context: GateContext,
    ): GateEvaluation {
        val evaluation = delegate.evaluate(proposition, context)

        when (val decision = evaluation.decision) {
            is GateDecision.Reject -> {
                logger.debug("Emitting PropositionRejected for {}: {}", proposition.id.take(8), decision.reason)
                listener.onEvent(PropositionRejected(proposition, decision.reason))
            }

            is GateDecision.RouteToReview -> {
                logger.debug("Emitting PropositionRoutedToReview for {}: {}", proposition.id.take(8), decision.reason)
                listener.onEvent(PropositionRoutedToReview(proposition, decision.reason))
            }

            is GateDecision.SkipProjection -> {
                logger.debug("Emitting PropositionProjectionSkipped for {}: {}", proposition.id.take(8), decision.reason)
                listener.onEvent(PropositionProjectionSkipped(proposition, decision.reason))
            }

            is GateDecision.Demote -> {
                logger.debug("Emitting PropositionDemoted for {}: demote to '{}', reason: {}", proposition.id.take(8), decision.toRelation, decision.reason)
                listener.onEvent(PropositionDemoted(proposition, decision.toRelation, decision.reason))
            }

            // The durable persist signal fires at the save boundary; emitting here would double-emit it.
            is GateDecision.Persist -> Unit
        }

        return evaluation
    }
}

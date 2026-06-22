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

import com.embabel.dice.proposition.Proposition
import org.slf4j.LoggerFactory

/**
 * A standalone runner that applies an ordered list of [ExtractionGate]s to propositions and
 * aggregates each gate's [GateEvaluation] into a [GatedPropositionResult].
 *
 * This is a consumer-invoked, pre-persistence stage. The consumer runs the extraction pipeline,
 * then passes the resulting propositions through this runner BEFORE calling `save()` — it is not
 * embedded inside the extraction pipeline, so the proposition remains the canonical source of
 * truth and gates only route or annotate. Insertion point:
 *
 * ```kotlin
 * val results = pipeline.process(chunks, context)
 * val gated = gatePipeline.evaluateAll(results.allPropositions) { proposition ->
 *     // Coerce numerically so a wrong-typed value does not silently read as missing.
 *     GateContext(trustScore = (proposition.metadata["dice.trust.score"] as? Number)?.toDouble())
 * }
 * gated.forEach { result ->
 *     when (result.finalDecision) {
 *         is GateDecision.Persist -> repository.save(result.proposition)
 *         is GateDecision.RouteToReview -> reviewQueue.add(result.proposition)
 *         is GateDecision.Reject -> { /* drop */ }
 *         is GateDecision.SkipProjection -> repository.save(result.proposition) // persist, no projection
 *         is GateDecision.Demote -> {
 *             // Persist as-is; consumer applies the weaker predicate at projection time.
 *             repository.save(result.proposition)
 *             projector.project(result.proposition, demoteLabel = result.demoteTo)
 *         }
 *     }
 * }
 * ```
 *
 * **Convention ordering.** Gates run in the order they are supplied; the runner does not reorder
 * them. The recommended convention is to order from cheapest/most-decisive to most-permissive —
 * confidence, then dedup/merge, then conflict, then trust, then projection-eligibility — so an
 * early hard rejection short-circuits the rest.
 *
 * **First-non-Persist-wins.** The final decision is the first non-[GateDecision.Persist] decision
 * encountered; once set, it is never overwritten. This guarantees a later [GateDecision.SkipProjection]
 * (or any other decision) cannot mask an earlier [GateDecision.Reject].
 *
 * @property gates the gates to run, in convention order
 * @property shortCircuitOnReject when true (the default), gate execution stops as soon as a gate
 *   returns [GateDecision.Reject] (after recording that evaluation); when false, every gate runs and
 *   every evaluation is recorded regardless of decision. Note that short-circuiting can truncate
 *   the recorded evaluation trail even when the [GateDecision.Reject] is not the winning decision —
 *   for example if an earlier gate already set the final decision to [GateDecision.RouteToReview], a
 *   later [GateDecision.Reject] still stops execution, so any gates after it never run and are absent
 *   from [GatedPropositionResult.evaluations]. Set this to false if the consumer needs the complete
 *   trail for audit or observability.
 */
class ExtractionGatePipeline @JvmOverloads constructor(
    gates: List<ExtractionGate>,
    val shortCircuitOnReject: Boolean = true,
) {

    private val logger = LoggerFactory.getLogger(ExtractionGatePipeline::class.java)

    /**
     * The gates to run, in convention order. Snapshotted at construction so that mutating an
     * aliased backing list a caller passed in cannot change the pipeline's iteration afterwards.
     */
    val gates: List<ExtractionGate> = gates.toList()

    /**
     * Run every gate against a single proposition, in declared order, applying first-non-Persist-wins
     * precedence and the configured short-circuit behaviour.
     *
     * @param proposition the proposition to evaluate
     * @param context the gate context for this proposition (trust score, revision result, source context)
     * @return the aggregated result: every recorded evaluation plus the final routing decision
     */
    fun evaluate(
        proposition: Proposition,
        context: GateContext,
    ): GatedPropositionResult {
        val evaluations = mutableListOf<GateEvaluation>()
        var finalDecision: GateDecision = GateDecision.Persist

        for (gate in gates) {
            val evaluation = gate.evaluate(proposition, context)
            evaluations.add(evaluation)

            // First non-Persist decision wins; never overwrite it with a later decision.
            if (finalDecision is GateDecision.Persist && evaluation.decision !is GateDecision.Persist) {
                finalDecision = evaluation.decision
                logger.debug(
                    "Gate '{}' set final decision to {} for proposition {}",
                    evaluation.gateName, finalDecision::class.simpleName, proposition.id.take(8),
                )
            }

            // Short-circuit on a hard reject AFTER recording the evaluation that produced it.
            if (shortCircuitOnReject && evaluation.decision is GateDecision.Reject) {
                logger.debug("Short-circuiting gate pipeline on Reject from '{}' for proposition {}", evaluation.gateName, proposition.id.take(8))
                break
            }
        }

        return GatedPropositionResult(proposition, evaluations, finalDecision)
    }

    /**
     * Evaluate a batch of propositions, producing one [GatedPropositionResult] per proposition.
     *
     * The [contextFor] factory supplies the [GateContext] for each proposition, so the consumer
     * can derive per-proposition context — for example, reading a cached trust score from the
     * proposition's metadata.
     *
     * @param propositions the propositions to gate
     * @param contextFor produces the gate context for each proposition
     * @return one result per input proposition, in input order
     */
    fun evaluateAll(
        propositions: List<Proposition>,
        contextFor: (Proposition) -> GateContext,
    ): List<GatedPropositionResult> =
        propositions.map { evaluate(it, contextFor(it)) }
}

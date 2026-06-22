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

import com.embabel.dice.common.AuthorityResolver
import com.embabel.dice.common.Relations
import com.embabel.dice.common.StructuralAuthorityResolver
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.revision.RevisionResult
import org.slf4j.LoggerFactory

/**
 * Routes a proposition based purely on its own confidence: anything below [minConfidence] is
 * rejected, everything else is persisted.
 *
 * This gate reads the decay-adjusted [Proposition.effectiveConfidence] — it has no dependency on
 * the surrounding context and therefore makes the same decision regardless of revision or trust
 * signals. Using the effective (rather than raw extraction-time) confidence keeps the threshold
 * consistent with the query layer (`PropositionQuery.withMinEffectiveConfidence`) and the decay
 * collector, so an aged proposition is gated on the same value every other read path scores it on.
 *
 * @property minConfidence the inclusive lower bound a proposition must meet to be persisted;
 *   must be in `0.0..1.0`
 * @throws IllegalArgumentException if [minConfidence] is outside `0.0..1.0`
 */
class ConfidenceGate(private val minConfidence: Double) : ExtractionGate {

    private val logger = LoggerFactory.getLogger(ConfidenceGate::class.java)

    init {
        require(minConfidence in 0.0..1.0) { "minConfidence must be in 0.0..1.0, was $minConfidence" }
    }

    override fun evaluate(
        proposition: Proposition,
        context: GateContext,
    ): GateEvaluation {
        val effectiveConfidence = proposition.effectiveConfidence()
        val decision = if (effectiveConfidence >= minConfidence) {
            GateDecision.Persist
        } else {
            GateDecision.Reject("effective confidence $effectiveConfidence < $minConfidence")
        }
        logger.debug("ConfidenceGate: {} for proposition {} (confidence={})", decision::class.simpleName, proposition.id.take(8), effectiveConfidence)
        return GateEvaluation(GATE_NAME, proposition, decision)
    }

    private companion object {
        const val GATE_NAME = "ConfidenceGate"
    }
}

/**
 * Routes propositions that were merged into, or reinforced an existing proposition to review so a
 * consumer can confirm the consolidation. The gate carries no policy: it reads only
 * [GateContext.revisionResult].
 *
 * Fail-open: when no revision result is present (the proposition was not revised against existing
 * knowledge), the gate persists. The reviser owns the classification — this gate only interprets it.
 */
class MergeCandidateGate : ExtractionGate {

    private val logger = LoggerFactory.getLogger(MergeCandidateGate::class.java)

    override fun evaluate(
        proposition: Proposition,
        context: GateContext,
    ): GateEvaluation {
        val decision = when (context.revisionResult) {
            is RevisionResult.Merged ->
                GateDecision.RouteToReview("merged into an existing proposition; confirm consolidation")
            is RevisionResult.Reinforced ->
                GateDecision.RouteToReview("reinforced an existing proposition; confirm consolidation")
            // null (not revised) and all other outcomes persist — fail open.
            else -> GateDecision.Persist
        }
        logger.debug("MergeCandidateGate: {} for proposition {} (revisionResult={})", decision::class.simpleName, proposition.id.take(8), context.revisionResult?.let { it::class.simpleName })
        return GateEvaluation(GATE_NAME, proposition, decision)
    }

    private companion object {
        const val GATE_NAME = "MergeCandidateGate"
    }
}

/**
 * Routes propositions that contradicted existing knowledge to review. The gate carries no policy:
 * it reads only [GateContext.revisionResult].
 *
 * Conservative default: any contradiction routes to review regardless of its conflict type. A
 * consumer that wants finer behaviour (for example treating world progression as benign) can
 * supply its own gate.
 *
 * Fail-open: when no revision result is present, the gate persists.
 */
class ConflictClassificationGate : ExtractionGate {

    private val logger = LoggerFactory.getLogger(ConflictClassificationGate::class.java)

    override fun evaluate(
        proposition: Proposition,
        context: GateContext,
    ): GateEvaluation {
        val decision = when (context.revisionResult) {
            is RevisionResult.Contradicted ->
                GateDecision.RouteToReview("contradicts an existing proposition; review the conflict")
            // null (not revised) and all other outcomes persist — fail open.
            else -> GateDecision.Persist
        }
        logger.debug("ConflictClassificationGate: {} for proposition {}", decision::class.simpleName, proposition.id.take(8))
        return GateEvaluation(GATE_NAME, proposition, decision)
    }

    private companion object {
        const val GATE_NAME = "ConflictClassificationGate"
    }
}

/**
 * Routes propositions whose trust score falls below [minTrustScore]. The score is supplied by the
 * consumer through [GateContext.trustScore]; this gate never computes trust and depends on no
 * scoring component — it only reads the injected value.
 *
 * Fail-open: when the trust score is absent the gate applies [onMissingScore], which defaults to
 * persisting. A consumer that wants a missing score to be treated as untrusted can supply, for
 * example, `GateDecision.Reject(...)`.
 *
 * @property minTrustScore the inclusive lower bound a trust score must meet to be persisted;
 *   must be in `0.0..1.0`
 * @property onMissingScore the decision to apply when [GateContext.trustScore] is null
 * @throws IllegalArgumentException if [minTrustScore] is outside `0.0..1.0`
 */
class TrustGate @JvmOverloads constructor(
    private val minTrustScore: Double,
    private val onMissingScore: GateDecision = GateDecision.Persist,
) : ExtractionGate {

    private val logger = LoggerFactory.getLogger(TrustGate::class.java)

    init {
        require(minTrustScore in 0.0..1.0) { "minTrustScore must be in 0.0..1.0, was $minTrustScore" }
    }

    override fun evaluate(
        proposition: Proposition,
        context: GateContext,
    ): GateEvaluation {
        val trustScore = context.trustScore
        val decision = when {
            trustScore == null -> onMissingScore
            trustScore >= minTrustScore -> GateDecision.Persist
            else -> GateDecision.RouteToReview("trust score $trustScore < $minTrustScore")
        }
        logger.debug("TrustGate: {} for proposition {} (trustScore={})", decision::class.simpleName, proposition.id.take(8), trustScore)
        return GateEvaluation(GATE_NAME, proposition, decision)
    }

    private companion object {
        const val GATE_NAME = "TrustGate"
    }
}

/**
 * Decides whether a proposition should be projected to downstream representations.
 *
 * A proposition is excluded from projection (but may still be persisted) when its
 * decay-adjusted effective confidence is below [minConfidence] or its status is
 * [PropositionStatus.CONTRADICTED]. Using effective rather than raw confidence keeps
 * the threshold consistent with what the query and decay-collector layers see.
 *
 * @property minConfidence the inclusive lower bound below which a proposition is excluded from
 *   projection; must be in `0.0..1.0`. Defaults to [DEFAULT_MIN_PROJECTION_CONFIDENCE].
 * @throws IllegalArgumentException if [minConfidence] is outside `0.0..1.0`
 */
class ProjectionEligibilityGate @JvmOverloads constructor(
    private val minConfidence: Double = DEFAULT_MIN_PROJECTION_CONFIDENCE,
) : ExtractionGate {

    private val logger = LoggerFactory.getLogger(ProjectionEligibilityGate::class.java)

    init {
        require(minConfidence in 0.0..1.0) { "minConfidence must be in 0.0..1.0, was $minConfidence" }
    }

    override fun evaluate(
        proposition: Proposition,
        context: GateContext,
    ): GateEvaluation {
        val effectiveConfidence = proposition.effectiveConfidence()
        val decision = when {
            effectiveConfidence < minConfidence ->
                GateDecision.SkipProjection("effective confidence $effectiveConfidence < $minConfidence")
            proposition.status == PropositionStatus.CONTRADICTED ->
                GateDecision.SkipProjection("status is ${PropositionStatus.CONTRADICTED}")
            else -> GateDecision.Persist
        }
        logger.debug("ProjectionEligibilityGate: {} for proposition {} (confidence={}, status={})", decision::class.simpleName, proposition.id.take(8), effectiveConfidence, proposition.status)
        return GateEvaluation(GATE_NAME, proposition, decision)
    }

    companion object {
        /**
         * Permissive default: only very low-confidence propositions are blocked from projection,
         * so moderately confident ones still reach downstream representations.
         */
        const val DEFAULT_MIN_PROJECTION_CONFIDENCE: Double = 0.3

        private const val GATE_NAME = "ProjectionEligibilityGate"
    }
}

/**
 * Stops a cheap structural signal from being asserted as a strong claim.
 *
 * Matches a proposition to a declared [com.embabel.dice.common.Relation] by checking whether
 * the relation's predicate appears in the proposition text. If that relation declares an
 * [com.embabel.dice.common.EvidenceFloor] and the proposition's decay-adjusted confidence and
 * source authority clear it, the proposition persists. If it falls short, the gate either demotes
 * the assertion to the floor's weaker predicate ([GateDecision.Demote]) or, when the floor names
 * none, holds it for review ([GateDecision.RouteToReview]).
 *
 * The mechanism is generic; the policy is not. DICE only checks the floor a relation declares —
 * what "works for" demotes to, and how high its bar sits, are the consumer's to configure on each
 * relation. The gate never mutates the proposition: it routes, leaving the proposition canonical.
 *
 * Fail-open: a proposition that matches no declared relation, or matches one with no floor, persists.
 *
 * @property relations the declared relations whose floors this gate enforces
 * @property authorityResolver resolves a proposition's source authority; defaults to the
 *   grounding-driven [StructuralAuthorityResolver]
 * @property caseSensitive whether predicate matching is case-sensitive (default false)
 */
class EvidenceFloorGate @JvmOverloads constructor(
    private val relations: Relations,
    private val authorityResolver: AuthorityResolver = StructuralAuthorityResolver(),
    private val caseSensitive: Boolean = false,
) : ExtractionGate {

    private val logger = LoggerFactory.getLogger(EvidenceFloorGate::class.java)

    override fun evaluate(
        proposition: Proposition,
        context: GateContext,
    ): GateEvaluation {
        val relation = matchingRelation(proposition)
        val floor = relation?.evidenceFloor
        val decision = if (relation == null || floor == null) {
            // No declared relation, or no floor on it — nothing to enforce.
            GateDecision.Persist
        } else {
            val confidence = proposition.effectiveConfidence()
            val authority = authorityResolver.resolve(proposition)
            if (floor.isSatisfiedBy(confidence, authority)) {
                GateDecision.Persist
            } else {
                val reason =
                    "evidence floor for '${relation.predicate}' not met " +
                        "(confidence=$confidence, authority=$authority)"
                val demoteTo = floor.demoteTo
                if (demoteTo != null) {
                    GateDecision.Demote(demoteTo, reason)
                } else {
                    GateDecision.RouteToReview(reason)
                }
            }
        }
        logger.debug(
            "EvidenceFloorGate: {} for proposition {} (relation={})",
            decision::class.simpleName, proposition.id.take(8), relation?.predicate,
        )
        return GateEvaluation(GATE_NAME, proposition, decision)
    }

    /**
     * Returns the first relation whose predicate appears as a substring in the proposition text,
     * or null if none match.
     *
     * **Matching is substring-based and order-sensitive.** A short predicate such as `"works"`
     * will match a proposition about "networks with" (because "networks" contains "works"). When
     * multiple relations could match — for example both `"works"` and `"works for"` are declared —
     * whichever appears first in [relations] wins. This means a broader predicate declared before a
     * narrower one will always shadow the narrower one, so the narrower relation's floor is never
     * applied. **Declare more-specific predicates first** to ensure the tightest floor always wins.
     *
     * If word-boundary matching is needed (to avoid the "networks"/"works" false positive), replace
     * `text.contains(predicate)` with a `\b${Regex.escape(predicate)}\b` regex match.
     */
    private fun matchingRelation(proposition: Proposition) =
        relations.firstOrNull { relation ->
            val text = if (caseSensitive) proposition.text else proposition.text.lowercase()
            val predicate = if (caseSensitive) relation.predicate else relation.predicate.lowercase()
            text.contains(predicate)
        }

    private companion object {
        const val GATE_NAME = "EvidenceFloorGate"
    }
}

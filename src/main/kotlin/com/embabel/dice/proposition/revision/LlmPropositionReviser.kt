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
package com.embabel.dice.proposition.revision

import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.core.types.SimilarityCutoff
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.util.trim
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * LLM-based implementation of PropositionReviser.
 * Uses structured output to classify and revise propositions.
 *
 * Example usage:
 * ```kotlin
 * val reviser = LlmPropositionReviser
 *     .withLlm(llmOptions)
 *     .withAi(ai)
 *     .withRetrievalTopK(10)
 *     .withMinSimilarity(0.6)
 * ```
 *
 * @param llmOptions LLM configuration
 * @param ai AI service for LLM calls
 * @param topK Number of similar propositions to retrieve for classification
 * @param similarityThreshold Minimum similarity threshold - skip LLM if no candidates above this
 * @param minSimilarityForReinforce Minimum LLM-reported similarity to accept SIMILAR classification (default 0.7)
 * @param decayK Decay constant for time-based confidence reduction
 */
data class LlmPropositionReviser(
    private val llmOptions: LlmOptions,
    private val ai: Ai,
    override val topK: Int = 5,
    override val similarityThreshold: Double = 0.5,
    private val minSimilarityForReinforce: Double = 0.7,
    private val decayK: Double = 2.0,
) : PropositionReviser, SimilarityCutoff {

    companion object {

        @JvmStatic
        fun withLlm(llm: LlmOptions): Builder = Builder(llm)

        class Builder(private val llmOptions: LlmOptions) {

            fun withAi(ai: Ai): LlmPropositionReviser =
                LlmPropositionReviser(
                    llmOptions = llmOptions,
                    ai = ai,
                )
        }
    }

    private val logger = LoggerFactory.getLogger(LlmPropositionReviser::class.java)

    /**
     * Set the number of similar propositions to retrieve for classification.
     */
    fun withTopK(topK: Int): LlmPropositionReviser =
        copy(topK = topK)

    /**
     * Set the minimum similarity threshold.
     * Candidates below this threshold are skipped (no LLM call).
     */
    fun withSimilarityThreshold(threshold: Double): LlmPropositionReviser =
        copy(similarityThreshold = threshold)

    /**
     * Set the minimum similarity score for SIMILAR classifications to be accepted.
     * If the LLM classifies as SIMILAR but with a score below this threshold,
     * the classification is treated as UNRELATED.
     */
    fun withMinSimilarityForReinforce(threshold: Double): LlmPropositionReviser =
        copy(minSimilarityForReinforce = threshold)

    /**
     * Set the decay constant for time-based confidence reduction.
     */
    fun withDecayK(k: Double): LlmPropositionReviser =
        copy(decayK = k)

    override fun revise(
        newProposition: Proposition,
        repository: PropositionRepository,
    ): RevisionResult {
        // 1. Retrieve similar propositions within the SAME CONTEXT using vector similarity
        val similarWithScores = repository.findSimilarWithScores(
            TextSimilaritySearchRequest(
                query = newProposition.text,
                topK = topK,
                similarityThreshold = similarityThreshold,
            ),
            PropositionQuery(
                contextId = newProposition.contextId,
                status = PropositionStatus.ACTIVE,
            ),
        )

        if (similarWithScores.isEmpty()) {
            // No similar propositions above threshold - return as new (skip LLM call)
            logger.debug("New proposition (no similar above {}): {}", similarityThreshold, newProposition.text)
            return RevisionResult.New(newProposition)
        }

        val similar = similarWithScores.map { it.match }
        logger.debug(
            "Found {} candidates above {} similarity for: {}",
            similar.size, similarityThreshold, newProposition.text.take(50)
        )

        // 2. Apply decay to retrieved propositions for ranking
        val decayed = similar.map { prop -> prop.withDecayApplied(decayK) }

        // 3. Classify relationships using LLM
        val classified = classify(newProposition, decayed)

        // 4. Find the best match
        val identical = classified.find { it.relation == PropositionRelation.IDENTICAL }
        val contradictory = classified.find { it.relation == PropositionRelation.CONTRADICTORY }
        val generalizes = classified.filter { it.relation == PropositionRelation.GENERALIZES }
        val mostSimilar = classified
            .filter { it.relation == PropositionRelation.SIMILAR && it.similarity >= minSimilarityForReinforce }
            .maxByOrNull { it.similarity }

        // Log rejected SIMILAR classifications for debugging
        val rejectedSimilar = classified.filter {
            it.relation == PropositionRelation.SIMILAR && it.similarity < minSimilarityForReinforce
        }
        if (rejectedSimilar.isNotEmpty()) {
            logger.debug(
                "Rejected {} SIMILAR classifications with low similarity (< {}): {}",
                rejectedSimilar.size,
                minSimilarityForReinforce,
                rejectedSimilar.map { "${it.proposition.id.take(8)}=${it.similarity}" }
            )
        }

        return when {
            // Handle identical - merge propositions
            identical != null -> {
                val original = repository.findById(identical.proposition.id)
                    ?: identical.proposition
                val merged = mergePropositions(original, newProposition)
                logger.debug("Merged: {} + {} -> {}", original.text, newProposition.text, merged.text)
                RevisionResult.Merged(original, merged)
            }

            // Handle contradiction - reduce old confidence
            contradictory != null -> {
                val original = repository.findById(contradictory.proposition.id)
                    ?: contradictory.proposition
                val reducedConfidence = (original.confidence * 0.3).coerceAtLeast(0.05)
                val contradicted = original
                    .withConfidence(reducedConfidence)
                    .withStatus(PropositionStatus.CONTRADICTED)
                logger.debug(
                    "Contradicted: {} (conf: {}) vs new: {}",
                    original.text, reducedConfidence, newProposition.text
                )
                RevisionResult.Contradicted(contradicted, newProposition)
            }

            // Handle generalizes - it's a higher-level abstraction
            generalizes.isNotEmpty() -> {
                val generalizedProps = generalizes.map { it.proposition }
                logger.debug(
                    "Generalized: {} generalizes {} existing propositions",
                    newProposition.text, generalizedProps.size
                )
                RevisionResult.Generalized(newProposition, generalizedProps)
            }

            // Handle similar - reinforce/revise
            mostSimilar != null -> {
                val original = repository.findById(mostSimilar.proposition.id)
                    ?: mostSimilar.proposition
                val revised = reinforceProposition(original, newProposition)
                logger.debug("Reinforced: {} -> {}", original.text, revised.text)
                RevisionResult.Reinforced(original, revised)
            }

            // No significant match - return as new
            else -> {
                logger.debug("New proposition (unrelated): {}", newProposition.text)
                RevisionResult.New(newProposition)
            }
        }
    }

    override fun classify(
        newProposition: Proposition,
        candidates: List<Proposition>,
    ): List<ClassifiedProposition> {
        if (candidates.isEmpty()) return emptyList()

        // Build candidate data for template
        val candidateData = candidates.map { p ->
            mapOf(
                "id" to p.id,
                "text" to p.text,
                "confidence" to p.effectiveConfidence(),
            )
        }

        val response = ai
            .withLlm(llmOptions)
            .withId("classify-proposition")
            .creating(ClassificationResponse::class.java)
            .fromTemplate(
                "dice/classify_proposition",
                mapOf(
                    "newProposition" to mapOf(
                        "text" to newProposition.text,
                        "confidence" to newProposition.confidence,
                        "reasoning" to (newProposition.reasoning ?: "N/A"),
                    ),
                    "candidates" to candidateData,
                )
            )
        logger.info(
            "Classified proposition {} against {} candidates:\n\t{}",
            trim(s = newProposition.text, max = 60, keepRight = 3),
            candidates.size,
            response.classifications.joinToString { "${it.propositionId}=${it.relation}" }
        )

        return response.classifications.mapNotNull { classification ->
            val candidate = candidates.find { it.id == classification.propositionId }
                ?: return@mapNotNull null
            ClassifiedProposition(
                proposition = candidate,
                relation = when (classification.relation.uppercase()) {
                    "IDENTICAL" -> PropositionRelation.IDENTICAL
                    "SIMILAR" -> PropositionRelation.SIMILAR
                    "CONTRADICTORY" -> PropositionRelation.CONTRADICTORY
                    "GENERALIZES" -> PropositionRelation.GENERALIZES
                    else -> PropositionRelation.UNRELATED
                },
                similarity = classification.similarity.coerceIn(0.0, 1.0),
                reasoning = classification.reasoning,
            )
        }
    }

    /**
     * Merge two propositions that express identical information.
     * Combines grounding, boosts confidence, uses most recent text.
     */
    private fun mergePropositions(existing: Proposition, new: Proposition): Proposition {
        // Boost confidence when we see the same information again
        val boostedConfidence = (existing.confidence + new.confidence * 0.3).coerceAtMost(0.99)
        // Average the decay rates
        val avgDecay = (existing.decay + new.decay) / 2
        // Combine grounding
        val combinedGrounding = (existing.grounding + new.grounding).distinct()

        return existing.copy(
            confidence = boostedConfidence,
            decay = avgDecay,
            grounding = combinedGrounding,
            revised = Instant.now(),
        )
    }

    /**
     * Reinforce an existing proposition with new supporting evidence.
     * Slightly boosts confidence and adds grounding.
     */
    private fun reinforceProposition(existing: Proposition, new: Proposition): Proposition {
        // Smaller confidence boost for similar (not identical)
        val boostedConfidence = (existing.confidence + new.confidence * 0.1).coerceAtMost(0.95)
        // Combine grounding
        val combinedGrounding = (existing.grounding + new.grounding).distinct()

        return existing.copy(
            confidence = boostedConfidence,
            grounding = combinedGrounding,
            revised = Instant.now(),
        )
    }
}

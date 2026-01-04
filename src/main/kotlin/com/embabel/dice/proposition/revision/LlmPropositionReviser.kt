package com.embabel.dice.proposition.revision

import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.core.types.SimilarityCutoff
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.proposition.Proposition
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
 * @param decayK Decay constant for time-based confidence reduction
 */
data class LlmPropositionReviser(
    private val llmOptions: LlmOptions,
    private val ai: Ai,
    override val topK: Int = 5,
    override val similarityThreshold: Double = 0.5,
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
     * Set the decay constant for time-based confidence reduction.
     */
    fun withDecayK(k: Double): LlmPropositionReviser =
        copy(decayK = k)

    override fun revise(
        newProposition: Proposition,
        repository: PropositionRepository,
    ): RevisionResult {
        // 1. Retrieve similar propositions using vector similarity with threshold
        val similarWithScores = repository.findSimilarWithScores(
            TextSimilaritySearchRequest(
                query = newProposition.text,
                topK = topK,
                similarityThreshold = similarityThreshold,
            )
        ).filter { it.match.status == PropositionStatus.ACTIVE }

        if (similarWithScores.isEmpty()) {
            // No similar propositions above threshold - store as new (skip LLM call)
            repository.save(newProposition)
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
        val mostSimilar = classified
            .filter { it.relation == PropositionRelation.SIMILAR }
            .maxByOrNull { it.similarity }

        return when {
            // Handle identical - merge propositions
            identical != null -> {
                val original = repository.findById(identical.proposition.id)
                    ?: identical.proposition
                val merged = mergePropositions(original, newProposition)
                repository.save(merged)
                logger.debug("Merged: {} + {} -> {}", original.text, newProposition.text, merged.text)
                RevisionResult.Merged(original, merged)
            }

            // Handle contradiction - reduce old confidence, store new
            contradictory != null -> {
                val original = repository.findById(contradictory.proposition.id)
                    ?: contradictory.proposition
                val reducedConfidence = (original.confidence * 0.3).coerceAtLeast(0.05)
                val contradicted = original
                    .withConfidence(reducedConfidence)
                    .withStatus(PropositionStatus.CONTRADICTED)
                repository.save(contradicted)
                repository.save(newProposition)
                logger.debug(
                    "Contradicted: {} (conf: {}) vs new: {}",
                    original.text, reducedConfidence, newProposition.text
                )
                RevisionResult.Contradicted(contradicted, newProposition)
            }

            // Handle similar - reinforce/revise
            mostSimilar != null -> {
                val original = repository.findById(mostSimilar.proposition.id)
                    ?: mostSimilar.proposition
                val revised = reinforceProposition(original, newProposition)
                repository.save(revised)
                logger.debug("Reinforced: {} -> {}", original.text, revised.text)
                RevisionResult.Reinforced(original, revised)
            }

            // No significant match - store as new
            else -> {
                repository.save(newProposition)
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
            .withId("classify-classify")
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
            "Classified proposition against {} candidates: {}",
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
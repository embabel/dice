package com.embabel.dice.proposition.revision

import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.core.types.ZeroToOne
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Classification of the relationship between two propositions.
 * Used by the Revise module to determine how to handle new propositions.
 */
enum class PropositionRelation {
    /** Propositions express the same information - should be merged */
    IDENTICAL,

    /** Propositions are related but not identical - may need revision */
    SIMILAR,

    /** Propositions are unrelated - new proposition stored separately */
    UNRELATED,

    /** Propositions contradict each other - confidence adjustment needed */
    CONTRADICTORY,
}

/**
 * A retrieved proposition with its computed relation to a new proposition.
 */
data class ClassifiedProposition(
    val proposition: Proposition,
    val relation: PropositionRelation,
    val similarity: Double,
    val reasoning: String? = null,
)

/**
 * Result of revising a proposition against the existing store.
 */
sealed class RevisionResult {
    /** Merged with an existing identical proposition */
    data class Merged(
        val original: Proposition,
        val revised: Proposition,
    ) : RevisionResult()

    /** Reinforced an existing similar proposition */
    data class Reinforced(
        val original: Proposition,
        val revised: Proposition,
    ) : RevisionResult()

    /** Contradicted an existing proposition (both stored, old with reduced confidence) */
    data class Contradicted(
        val original: Proposition,
        val new: Proposition,
    ) : RevisionResult()

    /** Stored as a new proposition (no similar ones found) */
    data class New(
        val proposition: Proposition,
    ) : RevisionResult()
}

/**
 * Revises propositions by comparing against existing ones in the repository.
 *
 * The revision process:
 * 1. Retrieve similar propositions using vector similarity
 * 2. Classify relationships (identical, similar, contradictory, unrelated)
 * 3. Merge, reinforce, or store new based on classification
 * 4. Never delete - contradicted propositions get reduced confidence
 */
interface PropositionReviser {

    /**
     * Revise a new proposition against the existing repository.
     *
     * @param newProposition The newly extracted proposition
     * @param repository The proposition repository for retrieval and storage
     * @return The result of revision (merged, reinforced, contradicted, or new)
     */
    fun revise(
        newProposition: Proposition,
        repository: PropositionRepository,
    ): RevisionResult

    /**
     * Revise multiple propositions, returning results for each.
     *
     * @param propositions The propositions to revise
     * @param repository The proposition repository
     * @return List of revision results
     */
    fun reviseAll(
        propositions: List<Proposition>,
        repository: PropositionRepository,
    ): List<RevisionResult> = propositions.map { revise(it, repository) }

    /**
     * Classify the relationship between propositions.
     *
     * @param newProposition The new proposition
     * @param candidates Retrieved similar propositions to compare against
     * @return Classified propositions with their relations
     */
    fun classify(
        newProposition: Proposition,
        candidates: List<Proposition>,
    ): List<ClassifiedProposition>
}

/**
 * LLM-based implementation of PropositionReviser.
 * Uses structured output to classify and revise propositions.
 */
class LlmPropositionReviser(
    private val ai: Ai,
    private val llmOptions: LlmOptions = LlmOptions(),
    private val retrievalTopK: Int = 5,
    private val minSimilarity: Double = 0.5, // Skip LLM if no candidates above this threshold
    private val decayK: Double = 2.0,
) : PropositionReviser {

    private val logger = LoggerFactory.getLogger(LlmPropositionReviser::class.java)

    override fun revise(
        newProposition: Proposition,
        repository: PropositionRepository,
    ): RevisionResult {
        // 1. Retrieve similar propositions using vector similarity with threshold
        val similarWithScores = repository.findSimilarWithScores(
            SimpleTextSimilaritySearchRequest(
                query = newProposition.text,
                topK = retrievalTopK,
                similarityThreshold = minSimilarity,
            )
        ).filter { it.match.status == PropositionStatus.ACTIVE }

        if (similarWithScores.isEmpty()) {
            // No similar propositions above threshold - store as new (skip LLM call)
            repository.save(newProposition)
            logger.debug("New proposition (no similar above {}): {}", minSimilarity, newProposition.text)
            return RevisionResult.New(newProposition)
        }

        val similar = similarWithScores.map { it.match }
        logger.debug(
            "Found {} candidates above {} similarity for: {}",
            similar.size, minSimilarity, newProposition.text.take(50)
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
            .withId("proposition-classify")
            .creating(ClassificationResponse::class.java)
            .fromTemplate(
                "gum_classify",
                mapOf(
                    "newProposition" to mapOf(
                        "text" to newProposition.text,
                        "confidence" to newProposition.confidence,
                        "reasoning" to (newProposition.reasoning ?: "N/A"),
                    ),
                    "candidates" to candidateData,
                )
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

/**
 * Response structure for classification.
 */
data class ClassificationResponse(
    @param:JsonPropertyDescription("Classification results for each candidate proposition")
    val classifications: List<ClassificationItem> = emptyList(),
)

data class ClassificationItem(
    @param:JsonPropertyDescription("ID of the proposition being classified")
    val propositionId: String,
    @param:JsonPropertyDescription("Relation type: IDENTICAL, SIMILAR, CONTRADICTORY, or UNRELATED")
    val relation: String,
    @param:JsonPropertyDescription("Similarity score 0.0-1.0")
    val similarity: Double,
    @param:JsonPropertyDescription("Brief reasoning for this classification")
    val reasoning: String,
)

private data class SimpleTextSimilaritySearchRequest(
    override val query: String,
    override val similarityThreshold: ZeroToOne,
    override val topK: Int,
) : TextSimilaritySearchRequest

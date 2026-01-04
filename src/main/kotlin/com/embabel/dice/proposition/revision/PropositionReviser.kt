package com.embabel.dice.proposition.revision

import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.fasterxml.jackson.annotation.JsonPropertyDescription

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

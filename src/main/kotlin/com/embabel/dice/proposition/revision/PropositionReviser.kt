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

    /** New proposition generalizes or abstracts existing ones - stored as new */
    GENERALIZES,
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

    /** Stored as a new proposition that generalizes existing ones */
    data class Generalized(
        val proposition: Proposition,
        val generalizes: List<Proposition>,
    ) : RevisionResult()
}

/**
 * Revises propositions by comparing against existing ones in the repository.
 *
 * The revision process:
 * 1. Retrieve similar propositions using vector similarity
 * 2. Classify relationships (identical, similar, contradictory, unrelated)
 * 3. Return revision result indicating how to handle the proposition
 *
 * **Important**: The reviser does NOT persist propositions. It only returns
 * the revision result. The caller is responsible for persisting using
 * [PersistablePropositionResults.persist].
 */
interface PropositionReviser {

    /**
     * Revise a new proposition against the existing repository.
     *
     * Does NOT persist the result - the caller must persist using
     * [PersistablePropositionResults.persist] after collecting all results.
     *
     * @param newProposition The newly extracted proposition
     * @param repository The proposition repository for retrieval (read-only)
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
    @param:JsonPropertyDescription("Relation type: IDENTICAL, SIMILAR, CONTRADICTORY, UNRELATED, or GENERALIZES")
    val relation: String,
    @param:JsonPropertyDescription("Similarity score 0.0-1.0")
    val similarity: Double,
    @param:JsonPropertyDescription("Brief reasoning for this classification")
    val reasoning: String,
)

/**
 * Response structure for batch classification of multiple propositions at once.
 */
data class BatchClassificationResponse(
    @param:JsonPropertyDescription("Classification results for each new proposition")
    val propositions: List<PropositionClassifications> = emptyList(),
)

data class PropositionClassifications(
    @param:JsonPropertyDescription("Zero-based index of the new proposition in the batch")
    val propositionIndex: Int,
    @param:JsonPropertyDescription("Classifications of each candidate for this proposition")
    val classifications: List<ClassificationItem> = emptyList(),
)

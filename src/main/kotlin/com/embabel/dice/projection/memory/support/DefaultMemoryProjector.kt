package com.embabel.dice.projection.memory.support

import com.embabel.dice.common.KnowledgeType
import com.embabel.dice.projection.memory.KnowledgeTypeClassifier
import com.embabel.dice.projection.memory.MemoryProjection
import com.embabel.dice.projection.memory.MemoryProjector
import com.embabel.dice.proposition.Proposition

/**
 * Default implementation of MemoryProjector.
 *
 * Simply classifies propositions by knowledge type using a [KnowledgeTypeClassifier].
 * The caller is responsible for querying propositions (via [PropositionQuery]).
 *
 * Example usage:
 * ```kotlin
 * // Query propositions using PropositionQuery
 * val props = repository.query(
 *     PropositionQuery.forEntity(userId)
 *         .withMinEffectiveConfidence(0.5)
 *         .orderedByEffectiveConfidence()
 * )
 *
 * // Project into memory types
 * val memory = projector.project(props)
 *
 * // Use the classified propositions
 * memory.semantic   // facts
 * memory.procedural // preferences/rules
 * memory.episodic   // events
 * ```
 *
 * @param classifier Strategy for classifying propositions into knowledge types
 */
data class DefaultMemoryProjector(
    private val classifier: KnowledgeTypeClassifier = HeuristicKnowledgeTypeClassifier,
) : MemoryProjector {

    companion object {
        /** Default instance with heuristic classifier */
        @JvmField
        val DEFAULT = DefaultMemoryProjector()

        /** Create with a specific classifier (Java-friendly factory) */
        @JvmStatic
        fun create(classifier: KnowledgeTypeClassifier) =
            DefaultMemoryProjector(classifier)
    }

    override fun project(propositions: List<Proposition>): MemoryProjection {
        val grouped = propositions.groupBy { classifier.classify(it) }
        return MemoryProjection(
            semantic = grouped[KnowledgeType.SEMANTIC] ?: emptyList(),
            episodic = grouped[KnowledgeType.EPISODIC] ?: emptyList(),
            procedural = grouped[KnowledgeType.PROCEDURAL] ?: emptyList(),
            working = grouped[KnowledgeType.WORKING] ?: emptyList(),
        )
    }

    /**
     * Create a new projector with a different classifier.
     */
    fun withClassifier(classifier: KnowledgeTypeClassifier): DefaultMemoryProjector =
        copy(classifier = classifier)
}

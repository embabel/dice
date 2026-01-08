package com.embabel.dice.projection.memory

import com.embabel.dice.common.KnowledgeType
import com.embabel.dice.proposition.Proposition

/**
 * Strategy interface for classifying propositions into knowledge types.
 * Implementations can use different heuristics based on domain needs.
 */
fun interface KnowledgeTypeClassifier {

    /**
     * Classify a proposition into a knowledge type.
     * @param proposition The proposition to classify
     * @return The inferred knowledge type
     */
    fun classify(proposition: Proposition): KnowledgeType
}

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
 * @param knowledgeTypeClassifier Strategy for classifying propositions into knowledge types
 */
data class DefaultMemoryProjector(
    private val knowledgeTypeClassifier: KnowledgeTypeClassifier = HeuristicKnowledgeTypeClassifier,
) : MemoryProjector {

    companion object {
        /** Default instance with heuristic classifier */
        @JvmField
        val DEFAULT = DefaultMemoryProjector()

        /** Create with a specific classifier (Java-friendly factory) */
        @JvmStatic
        fun withKnowledgeTypeClassifier(knowledgeTypeClassifier: KnowledgeTypeClassifier) =
            DefaultMemoryProjector(knowledgeTypeClassifier)
    }

    override fun project(propositions: List<Proposition>): MemoryProjection {
        val grouped = propositions.groupBy { knowledgeTypeClassifier.classify(it) }
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
        copy(knowledgeTypeClassifier = classifier)
}

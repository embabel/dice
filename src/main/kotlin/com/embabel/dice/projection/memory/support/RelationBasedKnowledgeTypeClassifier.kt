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
import com.embabel.dice.common.Relations
import com.embabel.dice.projection.memory.KnowledgeTypeClassifier
import com.embabel.dice.proposition.Proposition

/**
 * Classifies propositions based on matching predicates from a Relations collection.
 *
 * This classifier looks for relation predicates within proposition text and
 * returns the corresponding knowledge type. This is more accurate than keyword
 * matching because it uses the domain-specific predicates defined for the schema.
 *
 * @property relations The relations collection to match against
 * @property fallback Classifier to use when no relation matches (default: heuristic-based)
 * @property caseSensitive Whether predicate matching is case-sensitive (default: false)
 */
class RelationBasedKnowledgeTypeClassifier @JvmOverloads constructor(
    private val relations: Relations,
    private val fallback: KnowledgeTypeClassifier = HeuristicKnowledgeTypeClassifier,
    private val caseSensitive: Boolean = false,
) : KnowledgeTypeClassifier {

    override fun classify(proposition: Proposition): KnowledgeType {
        val text = if (caseSensitive) proposition.text else proposition.text.lowercase()

        // Try to match a relation predicate in the proposition text
        for (relation in relations) {
            val predicate = if (caseSensitive) relation.predicate else relation.predicate.lowercase()
            if (text.contains(predicate)) {
                return relation.knowledgeType
            }
        }

        // No match found, use fallback
        return fallback.classify(proposition)
    }

    /**
     * Create a new classifier with additional relations.
     */
    fun withRelations(additionalRelations: Relations): RelationBasedKnowledgeTypeClassifier =
        RelationBasedKnowledgeTypeClassifier(
            relations = relations + additionalRelations,
            fallback = fallback,
            caseSensitive = caseSensitive,
        )

    /**
     * Create a new classifier with a different fallback.
     */
    fun withFallback(fallback: KnowledgeTypeClassifier): RelationBasedKnowledgeTypeClassifier =
        RelationBasedKnowledgeTypeClassifier(
            relations = relations,
            fallback = fallback,
            caseSensitive = caseSensitive,
        )

    companion object {
        /**
         * Create a classifier from relations with default fallback.
         */
        @JvmStatic
        fun from(relations: Relations): RelationBasedKnowledgeTypeClassifier =
            RelationBasedKnowledgeTypeClassifier(relations)
    }
}

/**
 * Heuristic-based classifier using confidence/decay values.
 * Used as fallback when no relation predicate matches.
 */
object HeuristicKnowledgeTypeClassifier : KnowledgeTypeClassifier {

    override fun classify(proposition: Proposition): KnowledgeType {
        // High decay suggests episodic (events decay quickly)
        if (proposition.decay > 0.5) {
            return KnowledgeType.EPISODIC
        }

        // High confidence + low decay suggests semantic (stable facts)
        if (proposition.confidence > 0.7 && proposition.decay < 0.3) {
            return KnowledgeType.SEMANTIC
        }

        // Default to working memory for uncertain/transient propositions
        return KnowledgeType.WORKING
    }
}

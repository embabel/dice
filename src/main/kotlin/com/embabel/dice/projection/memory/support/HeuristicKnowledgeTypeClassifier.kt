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
import com.embabel.dice.proposition.Proposition

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

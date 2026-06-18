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
package com.embabel.dice.projection.graph

import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition

/**
 * Determines whether a proposition should be projected to the graph.
 * Projection creates relationships in the knowledge graph.
 */
interface ProjectionPolicy {

    /**
     * Determine if the given proposition should be projected.
     * @param proposition The proposition to evaluate
     * @return true if the proposition meets projection criteria
     */
    fun shouldProject(proposition: Proposition): Boolean
}

/**
 * Default projection policy requiring high confidence and fully resolved entities.
 *
 * @property confidenceThreshold Minimum confidence required (default 0.85)
 * @property requireFullResolution If true, all entity mentions must be resolved (default true)
 */
class DefaultProjectionPolicy(
    private val confidenceThreshold: Double = 0.85,
    private val requireFullResolution: Boolean = true,
) : ProjectionPolicy {

    override fun shouldProject(proposition: Proposition): Boolean {
        if (proposition.confidence < confidenceThreshold) {
            return false
        }
        if (requireFullResolution && !proposition.isFullyResolved()) {
            return false
        }
        return true
    }
}

/**
 * Lenient projection policy that allows partial resolution.
 * Requires at least subject and object mentions to be resolved.
 *
 * @property confidenceThreshold Minimum confidence required (default 0.7)
 */
class LenientProjectionPolicy(
    private val confidenceThreshold: Double = 0.7,
) : ProjectionPolicy {

    override fun shouldProject(proposition: Proposition): Boolean {
        if (proposition.confidence < confidenceThreshold) {
            return false
        }
        // At least need subject and object resolved for a relationship
        val hasResolvedSubject = proposition.mentions.any {
            it.role == MentionRole.SUBJECT && it.resolvedId != null
        }
        val hasResolvedObject = proposition.mentions.any {
            it.role == MentionRole.OBJECT && it.resolvedId != null
        }
        return hasResolvedSubject && hasResolvedObject
    }
}

/**
 * Always project policy - useful for testing or when all propositions should be projected.
 */
object AlwaysProjectPolicy : ProjectionPolicy {
    override fun shouldProject(proposition: Proposition): Boolean = true
}

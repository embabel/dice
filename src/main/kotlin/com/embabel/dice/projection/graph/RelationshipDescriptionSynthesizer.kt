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

import com.embabel.dice.proposition.Proposition

/**
 * Synthesizes a concise human-readable description for a relationship edge
 * from the set of propositions that mention both entities.
 */
interface RelationshipDescriptionSynthesizer {

    fun synthesize(request: SynthesisRequest): SynthesisResult
}

/**
 * Input for relationship description synthesis.
 *
 * @property sourceEntityId Resolved ID of the source entity
 * @property sourceEntityName Display name of the source entity
 * @property targetEntityId Resolved ID of the target entity
 * @property targetEntityName Display name of the target entity
 * @property relationshipType The relationship type (e.g. "KNOWS")
 * @property propositions All propositions mentioning both entities
 * @property existingDescription Current description on the edge, if any
 */
data class SynthesisRequest(
    val sourceEntityId: String,
    val sourceEntityName: String,
    val targetEntityId: String,
    val targetEntityName: String,
    val relationshipType: String,
    val propositions: List<Proposition>,
    val existingDescription: String? = null,
)

/**
 * Result of relationship description synthesis.
 *
 * @property description The synthesized description
 * @property confidence Confidence in the synthesized description (0.0-1.0)
 * @property sourcePropositionIds IDs of propositions used in synthesis
 */
data class SynthesisResult(
    val description: String,
    val confidence: Double,
    val sourcePropositionIds: List<String>,
)

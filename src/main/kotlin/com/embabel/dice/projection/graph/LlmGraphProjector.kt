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

import com.embabel.agent.api.common.Ai
import com.embabel.agent.core.AllowedRelationship
import com.embabel.agent.core.DataDictionary
import com.embabel.common.ai.model.LlmOptions
import com.embabel.dice.common.Relation
import com.embabel.dice.common.Relations
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.ProjectionFailed
import com.embabel.dice.proposition.ProjectionResult
import com.embabel.dice.proposition.ProjectionSkipped
import com.embabel.dice.proposition.ProjectionSuccess
import com.embabel.dice.proposition.Proposition
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import org.slf4j.LoggerFactory

/**
 * LLM-based graph projector.
 * Uses an LLM to classify propositions into relationship types
 * from both the [DataDictionary] schema and [Relations] predicates.
 *
 * ## Builder Usage (Java)
 *
 * ```java
 * LlmGraphProjector projector = LlmGraphProjector
 *     .withLlm(llmOptions)
 *     .withAi(ai)
 *     .withRelations(relations)
 *     .withLenientPolicy();
 * ```
 *
 * ## Direct Construction (Kotlin)
 *
 * ```kotlin
 * val projector = LlmGraphProjector(
 *     ai = ai,
 *     relations = relations,
 *     policy = LenientProjectionPolicy(),
 *     llmOptions = llmOptions,
 * )
 * ```
 *
 * @param ai AI service for LLM calls
 * @param relations Relation predicates to include as candidate relationship types
 * @param policy Policy to filter propositions before projection
 * @param llmOptions LLM configuration
 */
data class LlmGraphProjector(
    private val ai: Ai,
    private val relations: Relations = Relations.empty(),
    private val policy: ProjectionPolicy = DefaultProjectionPolicy(),
    private val llmOptions: LlmOptions = LlmOptions(),
) : GraphProjector {

    companion object {

        private val logger = LoggerFactory.getLogger(LlmGraphProjector::class.java)

        @JvmStatic
        fun withLlm(llm: LlmOptions): Builder = Builder(llm)

        class Builder(
            private val llmOptions: LlmOptions,
        ) {
            fun withAi(ai: Ai): LlmGraphProjector =
                LlmGraphProjector(
                    ai = ai,
                    llmOptions = llmOptions,
                )
        }
    }

    /**
     * Set the relation predicates.
     */
    fun withRelations(relations: Relations): LlmGraphProjector =
        copy(relations = relations)

    /**
     * Set the projection policy.
     */
    fun withPolicy(policy: ProjectionPolicy): LlmGraphProjector =
        copy(policy = policy)

    /**
     * Use a [LenientProjectionPolicy] with default confidence threshold.
     */
    fun withLenientPolicy(): LlmGraphProjector =
        copy(policy = LenientProjectionPolicy())

    /**
     * Use a [LenientProjectionPolicy] with the given confidence threshold.
     */
    fun withLenientPolicy(confidenceThreshold: Double): LlmGraphProjector =
        copy(policy = LenientProjectionPolicy(confidenceThreshold))

    /**
     * Use a [DefaultProjectionPolicy] with default confidence threshold.
     */
    fun withDefaultPolicy(): LlmGraphProjector =
        copy(policy = DefaultProjectionPolicy())

    /**
     * Use a [DefaultProjectionPolicy] with the given confidence threshold.
     */
    fun withDefaultPolicy(confidenceThreshold: Double): LlmGraphProjector =
        copy(policy = DefaultProjectionPolicy(confidenceThreshold))

    /**
     * Override LLM options after construction.
     */
    fun withLlmOptions(llmOptions: LlmOptions): LlmGraphProjector =
        copy(llmOptions = llmOptions)

    override fun project(
        proposition: Proposition,
        schema: DataDictionary,
    ): ProjectionResult<ProjectedRelationship> {
        // Check policy first
        if (!policy.shouldProject(proposition)) {
            val reason = buildPolicyRejectionReason(proposition)
            logger.debug("Proposition skipped by policy: {}", reason)
            return ProjectionSkipped(proposition, reason)
        }

        // Get allowed relationships based on entity types in proposition
        val mentionTypes = proposition.mentions.map { it.type }.toSet()
        val allowedRelationships = schema.allowedRelationships().filter { rel ->
            mentionTypes.contains(rel.from.name) || mentionTypes.contains(rel.to.name)
        }

        // Filter Relations by mention types (match subject and/or object type constraints)
        val matchingRelations = relations.filter { relation ->
            val subjectMatches = relation.subjectType == null || mentionTypes.contains(relation.subjectType)
            val objectMatches = relation.objectType == null || mentionTypes.contains(relation.objectType)
            subjectMatches && objectMatches
        }

        if (allowedRelationships.isEmpty() && matchingRelations.isEmpty()) {
            logger.debug("No allowed relationships or relations for mention types: {}", mentionTypes)
            return ProjectionFailed(
                proposition,
                "No allowed relationships between entity types: $mentionTypes"
            )
        }

        // Build the set of valid relationship type names for validation
        val validRelationshipTypes = buildSet {
            allowedRelationships.forEach { add(it.name) }
            matchingRelations.forEach { add(RelationBasedGraphProjector.toRelationshipType(it.predicate)) }
        }

        // Ask LLM to classify
        val classification = classifyRelationship(proposition, allowedRelationships, matchingRelations)

        if (!classification.hasRelationship) {
            logger.debug("LLM determined no relationship: {}", classification.reasoning)
            return ProjectionFailed(proposition, classification.reasoning ?: "No relationship implied")
        }

        // Find the source and target entity IDs
        val fromMention = proposition.mentions.find {
            it.span.equals(classification.fromMentionSpan, ignoreCase = true) ||
                it.role == MentionRole.SUBJECT
        }
        val toMention = proposition.mentions.find {
            it.span.equals(classification.toMentionSpan, ignoreCase = true) ||
                it.role == MentionRole.OBJECT
        }

        if (fromMention?.resolvedId == null || toMention?.resolvedId == null) {
            logger.debug("Could not resolve entity IDs for relationship")
            return ProjectionFailed(
                proposition,
                "Could not resolve entity IDs: from=${fromMention?.span}, to=${toMention?.span}"
            )
        }

        // Validate the relationship type exists in schema or relations
        val relationshipType = classification.relationshipType
        if (relationshipType != null && relationshipType !in validRelationshipTypes) {
            logger.warn("LLM suggested unknown relationship type: {}", relationshipType)
            return ProjectionFailed(
                proposition,
                "Relationship type '$relationshipType' not in schema or relations"
            )
        }

        // Create the projected relationship
        val relationship = ProjectedRelationship(
            sourceId = fromMention.resolvedId!!,
            targetId = toMention.resolvedId!!,
            type = relationshipType ?: "RELATED_TO",
            confidence = proposition.confidence,
            decay = proposition.decay,
            description = proposition.text,
            sourcePropositionIds = listOf(proposition.id),
        )

        logger.debug("Projected proposition to relationship: {}", relationship.infoString(true))
        return ProjectionSuccess(proposition, relationship)
    }

    private fun classifyRelationship(
        proposition: Proposition,
        allowedRelationships: List<AllowedRelationship>,
        matchingRelations: List<Relation>,
    ): RelationshipClassification {
        return ai
            .withLlm(llmOptions)
            .withId("promote-relationship")
            .creating(RelationshipClassification::class.java)
            .fromTemplate(
                "promote_relationship",
                mapOf(
                    "proposition" to proposition,
                    "allowedRelationships" to allowedRelationships,
                    "relations" to matchingRelations.map { relation ->
                        mapOf(
                            "type" to RelationBasedGraphProjector.toRelationshipType(relation.predicate),
                            "predicate" to relation.predicate,
                            "meaning" to relation.meaning,
                            "subjectType" to (relation.subjectType ?: "Any"),
                            "objectType" to (relation.objectType ?: "Any"),
                        )
                    },
                )
            )
    }

    private fun buildPolicyRejectionReason(proposition: Proposition): String {
        val reasons = mutableListOf<String>()
        if (proposition.confidence < 0.85) {
            reasons.add("low confidence (${proposition.confidence})")
        }
        if (!proposition.isFullyResolved()) {
            val unresolved = proposition.mentions.filter { it.resolvedId == null }.map { it.span }
            reasons.add("unresolved entities: $unresolved")
        }
        return reasons.joinToString(", ").ifEmpty { "policy criteria not met" }
    }
}

/**
 * LLM output for relationship classification.
 */
internal data class RelationshipClassification(
    @param:JsonPropertyDescription("Whether this proposition implies a relationship between two entities")
    val hasRelationship: Boolean,
    @param:JsonPropertyDescription("The relationship type name (e.g. 'OWNS', 'LISTENS_TO', 'employer'), or null if no relationship")
    val relationshipType: String?,
    @param:JsonPropertyDescription("The entity span that is the relationship source")
    val fromMentionSpan: String?,
    @param:JsonPropertyDescription("The entity span that is the relationship target")
    val toMentionSpan: String?,
    @param:JsonPropertyDescription("Brief explanation of the classification")
    val reasoning: String?,
)

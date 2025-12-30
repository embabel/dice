package com.embabel.dice.projection.graph

import com.embabel.agent.api.common.Ai
import com.embabel.agent.core.AllowedRelationship
import com.embabel.agent.core.DataDictionary
import com.embabel.common.ai.model.LlmOptions
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
 * Uses an LLM to classify propositions into relationship types.
 *
 * @param ai AI service for LLM calls
 * @param policy Policy to filter propositions before projection
 * @param llmOptions LLM configuration
 */
class LlmGraphProjector(
    private val ai: Ai,
    private val policy: ProjectionPolicy = DefaultProjectionPolicy(),
    private val llmOptions: LlmOptions = LlmOptions(),
) : GraphProjector {

    private val logger = LoggerFactory.getLogger(LlmGraphProjector::class.java)

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

        if (allowedRelationships.isEmpty()) {
            logger.debug("No allowed relationships for mention types: {}", mentionTypes)
            return ProjectionFailed(
                proposition,
                "No allowed relationships between entity types: $mentionTypes"
            )
        }

        // Ask LLM to classify
        val classification = classifyRelationship(proposition, allowedRelationships)

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

        // Validate the relationship type exists in schema
        val relationshipType = classification.relationshipType
        val matchingRel = allowedRelationships.find { it.name == relationshipType }
        if (matchingRel == null && relationshipType != null) {
            logger.warn("LLM suggested non-existent relationship type: {}", relationshipType)
            return ProjectionFailed(
                proposition,
                "Relationship type '$relationshipType' not in schema"
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
    @param:JsonPropertyDescription("Whether this proposition implies a relationship")
    val hasRelationship: Boolean,
    @param:JsonPropertyDescription("The relationship type from schema, or null")
    val relationshipType: String?,
    @param:JsonPropertyDescription("The entity span that is the relationship source")
    val fromMentionSpan: String?,
    @param:JsonPropertyDescription("The entity span that is the relationship target")
    val toMentionSpan: String?,
    @param:JsonPropertyDescription("Brief explanation of the classification")
    val reasoning: String?,
)

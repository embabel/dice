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

import com.embabel.agent.core.AllowedRelationship
import com.embabel.agent.core.DataDictionary
import com.embabel.dice.common.Relation
import com.embabel.dice.common.Relations
import com.embabel.dice.proposition.*
import org.slf4j.LoggerFactory

/**
 * Result of matching a proposition against known predicates.
 */
private sealed interface MatchedRelationship {
    val predicate: String
    val relationshipType: String
    val fromType: String?
    val toType: String?
}

/**
 * Match from DataDictionary schema relationship.
 * Uses the property name as the relationship type.
 */
private data class SchemaMatch(
    val allowedRelationship: AllowedRelationship,
    override val predicate: String,
) : MatchedRelationship {
    override val relationshipType: String get() = allowedRelationship.name
    override val fromType: String get() = allowedRelationship.from.ownLabel
    override val toType: String get() = allowedRelationship.to.ownLabel
}

/**
 * Match from Relations predicate.
 * Derives relationship type from predicate using UPPER_SNAKE_CASE.
 */
private data class RelationMatch(
    val relation: Relation,
) : MatchedRelationship {
    override val predicate: String get() = relation.predicate
    override val relationshipType: String get() = RelationBasedGraphProjector.toRelationshipType(relation.predicate)
    override val fromType: String? get() = relation.subjectType
    override val toType: String? get() = relation.objectType
}

/**
 * Graph projector that uses predicates from both the [DataDictionary] schema
 * and [Relations] to determine relationship types.
 *
 * Does not use LLM - matches proposition text directly against known predicates.
 *
 * **Matching priority:**
 * 1. Schema relationships from [DataDictionary.allowedRelationships] with explicit predicates
 *    defined via `@Semantics(With(key = Proposition.PREDICATE, value = "..."))`.
 *    Uses the property name as the relationship type.
 * 2. Schema relationships with derived predicates from the property name.
 *    For example, property `employer` derives predicate "has employer".
 * 3. Fallback to [Relations] predicates, deriving relationship type from predicate
 *    using UPPER_SNAKE_CASE convention.
 *
 * Example with explicit @Semantics predicate:
 * ```kotlin
 * // Given: Person.employer property annotated with @Semantics predicate="works at"
 * val schema = DataDictionary.fromClasses(Person::class.java, Company::class.java)
 * val projector = RelationBasedGraphProjector.from(Relations.empty())
 *
 * // "Bob works at Acme" -> (bob)-[:employer]->(acme)
 * // Uses property name "employer" as relationship type
 * ```
 *
 * Example with derived predicate (no annotation needed):
 * ```kotlin
 * // Given: Person.employer property with no @Semantics annotation
 * // Predicate "has employer" is derived automatically
 *
 * // "Bob has employer Acme" -> (bob)-[:employer]->(acme)
 * ```
 *
 * Example with Relations fallback:
 * ```kotlin
 * val relations = Relations.empty()
 *     .withProcedural("likes", "expresses preference for")
 *
 * val projector = RelationBasedGraphProjector.from(relations)
 *
 * // "Alice likes jazz" -> (alice)-[:LIKES]->(jazz)
 * // Derives LIKES from predicate
 * ```
 *
 * @param relations The relation predicates to match against (fallback)
 * @param policy Optional policy to filter propositions before projection
 * @param caseSensitive Whether predicate matching is case-sensitive (default: false)
 */
class RelationBasedGraphProjector @JvmOverloads constructor(
    private val relations: Relations = Relations.empty(),
    private val policy: ProjectionPolicy = DefaultProjectionPolicy(),
    private val caseSensitive: Boolean = false,
) : GraphProjector {

    private val logger = LoggerFactory.getLogger(RelationBasedGraphProjector::class.java)

    companion object {
        /**
         * Create a projector from relations.
         */
        @JvmStatic
        fun from(relations: Relations): RelationBasedGraphProjector =
            RelationBasedGraphProjector(relations)

        /**
         * Convert a predicate to a graph relationship type name.
         * "likes" -> "LIKES"
         * "works at" -> "WORKS_AT"
         * "is expert in" -> "IS_EXPERT_IN"
         */
        @JvmStatic
        fun toRelationshipType(predicate: String): String =
            predicate
                .trim()
                .uppercase()
                .replace(Regex("\\s+"), "_")

        /**
         * Derive a natural language predicate from a relationship or property name.
         * Used when no explicit [@Semantics] predicate is defined.
         *
         * Examples:
         * - "employer" -> "has employer"
         * - "HAS_EMPLOYER" -> "has employer"
         * - "WORKS_AT" -> "works at"
         * - "directReports" -> "has direct reports"
         */
        @JvmStatic
        fun derivePredicate(relationshipName: String): String {
            // If already has HAS_ prefix, convert to lowercase with spaces
            if (relationshipName.startsWith("HAS_")) {
                return relationshipName
                    .lowercase()
                    .replace('_', ' ')
            }
            // If UPPER_SNAKE_CASE without HAS_, just convert to lowercase with spaces
            if (relationshipName.contains('_')) {
                return relationshipName
                    .lowercase()
                    .replace('_', ' ')
            }
            // camelCase or simple name: add "has " prefix and convert to spaced lowercase
            val spaced = relationshipName
                .replace(Regex("([a-z])([A-Z])")) { "${it.groupValues[1]} ${it.groupValues[2]}" }
                .lowercase()
            return "has $spaced"
        }
    }

    /**
     * Add more relations to this projector.
     */
    fun withRelations(additional: Relations): RelationBasedGraphProjector =
        RelationBasedGraphProjector(relations + additional, policy, caseSensitive)

    /**
     * Set the projection policy.
     */
    fun withPolicy(policy: ProjectionPolicy): RelationBasedGraphProjector =
        RelationBasedGraphProjector(relations, policy, caseSensitive)

    /**
     * Set case sensitivity for predicate matching.
     */
    fun withCaseSensitive(caseSensitive: Boolean): RelationBasedGraphProjector =
        RelationBasedGraphProjector(relations, policy, caseSensitive)

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

        // Find the first matching relationship (schema first, then Relations fallback)
        val matched = findMatchingRelationship(proposition, schema)
            ?: return ProjectionFailed(
                proposition,
                "No matching predicate found in schema or relations: ${proposition.text}"
            )

        // Validate entity types
        val typeValidation = validateEntityTypes(proposition, matched)
        if (typeValidation != null) {
            logger.debug("Type validation failed: {}", typeValidation)
            return ProjectionFailed(proposition, typeValidation)
        }

        // Extract subject and object mentions
        val subjectMention = proposition.mentions.find { it.role == MentionRole.SUBJECT }
        val objectMention = proposition.mentions.find { it.role == MentionRole.OBJECT }

        if (subjectMention?.resolvedId == null || objectMention?.resolvedId == null) {
            logger.debug("Missing resolved entity IDs: subject={}, object={}",
                subjectMention?.resolvedId, objectMention?.resolvedId)
            return ProjectionFailed(
                proposition,
                "Could not resolve entity IDs: subject=${subjectMention?.span}, object=${objectMention?.span}"
            )
        }

        // Create the projected relationship
        val relationship = ProjectedRelationship(
            sourceId = subjectMention.resolvedId!!,
            targetId = objectMention.resolvedId!!,
            type = matched.relationshipType,
            confidence = proposition.confidence,
            decay = proposition.decay,
            description = proposition.text,
            sourcePropositionIds = listOf(proposition.id),
        )

        val source = if (matched is SchemaMatch) "schema" else "relations"
        logger.debug("Projected '{}' -> {} relationship (from {}): {}",
            matched.predicate, matched.relationshipType, source, relationship.infoString(true))
        return ProjectionSuccess(proposition, relationship)
    }

    /**
     * Find a matching relationship by predicate.
     * Priority:
     * 1. Schema relationships with explicit [@Semantics] predicates
     * 2. Schema relationships with derived predicates from property name
     * 3. Relations predicates (fallback)
     */
    private fun findMatchingRelationship(proposition: Proposition, schema: DataDictionary): MatchedRelationship? {
        val text = if (caseSensitive) proposition.text else proposition.text.lowercase()

        // 1. Try schema relationships with explicit predicates first
        for (allowedRel in schema.allowedRelationships()) {
            val predicate = allowedRel.metadata[Proposition.PREDICATE] ?: continue
            val predicateToMatch = if (caseSensitive) predicate else predicate.lowercase()
            if (text.contains(predicateToMatch)) {
                return SchemaMatch(allowedRel, predicate)
            }
        }

        // 2. Try schema relationships with derived predicates (from property name)
        for (allowedRel in schema.allowedRelationships()) {
            // Skip if already has explicit predicate (handled above)
            if (allowedRel.metadata.containsKey(Proposition.PREDICATE)) continue

            val derivedPredicate = derivePredicate(allowedRel.name)
            val predicateToMatch = if (caseSensitive) derivedPredicate else derivedPredicate.lowercase()
            if (text.contains(predicateToMatch)) {
                return SchemaMatch(allowedRel, derivedPredicate)
            }
        }

        // 3. Fall back to Relations predicates
        for (relation in relations) {
            val predicate = if (caseSensitive) relation.predicate else relation.predicate.lowercase()
            if (text.contains(predicate)) {
                return RelationMatch(relation)
            }
        }

        return null
    }

    /**
     * Validate that entity types match relationship constraints.
     * Returns null if valid, or error message if invalid.
     */
    private fun validateEntityTypes(proposition: Proposition, matched: MatchedRelationship): String? {
        val subjectMention = proposition.mentions.find { it.role == MentionRole.SUBJECT }
        val objectMention = proposition.mentions.find { it.role == MentionRole.OBJECT }

        // Check subject type constraint
        if (matched.fromType != null && subjectMention != null) {
            if (subjectMention.type != matched.fromType) {
                return "Subject type '${subjectMention.type}' does not match expected '${matched.fromType}'"
            }
        }

        // Check object type constraint
        if (matched.toType != null && objectMention != null) {
            if (objectMention.type != matched.toType) {
                return "Object type '${objectMention.type}' does not match expected '${matched.toType}'"
            }
        }

        return null
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

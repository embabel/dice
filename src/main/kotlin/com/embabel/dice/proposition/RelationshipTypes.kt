package com.embabel.dice.proposition

/**
 * Relationship types for DICE graph schema.
 *
 * Graph schema:
 * ```
 * // Direct entity extraction (NER)
 * (Chunk)-[:HAS_ENTITY]->(__Entity__)
 *
 * // Proposition extraction
 * (Chunk)-[:HAS_PROPOSITION]->(Proposition)
 *
 * // Entity mentions in propositions
 * (Proposition)-[:MENTIONS {role: 'SUBJECT'}]->(__Entity__)
 * (Proposition)-[:MENTIONS {role: 'OBJECT'}]->(__Entity__)
 * ```
 *
 * @see com.embabel.agent.rag.model.RetrievableEntity.HAS_ENTITY
 * @see MentionRole
 */
object RelationshipTypes {

    /**
     * Relationship from Chunk to Proposition.
     * Created when propositions are extracted from a chunk.
     * ```
     * (Chunk)-[:HAS_PROPOSITION]->(Proposition)
     * ```
     */
    const val HAS_PROPOSITION = "HAS_PROPOSITION"

    /**
     * Relationship from Proposition to Entity.
     * Use with [MentionRole] to indicate subject vs object.
     * ```
     * (Proposition)-[:MENTIONS {role: 'SUBJECT'}]->(__Entity__)
     * (Proposition)-[:MENTIONS {role: 'OBJECT'}]->(__Entity__)
     * ```
     */
    const val MENTIONS = "MENTIONS"

    /**
     * Property key for the role in a MENTIONS relationship.
     * Value should be a [MentionRole] name.
     */
    const val ROLE_PROPERTY = "role"
}

package com.embabel.dice.common

/**
 * Defines a relationship type that can exist between entities.
 * Used to provide the LLM with vocabulary for expressing relationships in propositions.
 *
 * @param predicate The verb phrase expressing the relationship, e.g., "likes", "works at"
 * @param meaning A description of what the relationship means, e.g., "expresses positive preference for"
 * @param subjectType Optional constraint on the subject entity type, e.g., "Person"
 * @param objectType Optional constraint on the object entity type, e.g., "Company"
 */
data class Relation @JvmOverloads constructor(
    val predicate: String,
    val meaning: String,
    val subjectType: String? = null,
    val objectType: String? = null,
) {
    companion object {
        /**
         * Create a relation with no type constraints.
         */
        @JvmStatic
        fun of(predicate: String, meaning: String): Relation =
            Relation(predicate, meaning)

        /**
         * Create a relation with subject type constraint.
         */
        @JvmStatic
        fun forSubject(predicate: String, meaning: String, subjectType: String): Relation =
            Relation(predicate, meaning, subjectType = subjectType)

        /**
         * Create a relation with both type constraints.
         */
        @JvmStatic
        fun between(predicate: String, meaning: String, subjectType: String, objectType: String): Relation =
            Relation(predicate, meaning, subjectType = subjectType, objectType = objectType)
    }
}

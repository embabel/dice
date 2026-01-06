package com.embabel.dice.common

/**
 * Defines a relationship type that can exist between entities.
 * Used to provide the LLM with vocabulary for expressing relationships in propositions.
 *
 * @param predicate The verb phrase expressing the relationship, e.g., "likes", "works at"
 * @param meaning A description of what the relationship means, e.g., "expresses positive preference for"
 * @param knowledgeType The epistemological nature of this relationship
 * @param subjectType Optional constraint on the subject entity type, e.g., "Person"
 * @param objectType Optional constraint on the object entity type, e.g., "Company"
 */
data class Relation @JvmOverloads constructor(
    val predicate: String,
    val meaning: String,
    val knowledgeType: KnowledgeType,
    val subjectType: String? = null,
    val objectType: String? = null,
) {

    fun withSubject(type: Class<*>): Relation =
        copy(subjectType = type.simpleName)

    fun withObject(type: Class<*>): Relation =
        copy(objectType = type.simpleName)

    companion object {

        /**
         * Create a semantic (factual) relation with no type constraints.
         */
        @JvmStatic
        @JvmOverloads
        fun semantic(predicate: String, meaning: String = predicate): Relation =
            Relation(predicate, meaning, KnowledgeType.SEMANTIC)

        /**
         * Create a procedural (preference/behavioral) relation with no type constraints.
         */
        @JvmStatic
        @JvmOverloads
        fun procedural(predicate: String, meaning: String = predicate): Relation =
            Relation(predicate, meaning, KnowledgeType.PROCEDURAL)

        /**
         * Create an episodic (event-based) relation with no type constraints.
         */
        @JvmStatic
        @JvmOverloads
        fun episodic(predicate: String, meaning: String = predicate): Relation =
            Relation(predicate, meaning, KnowledgeType.EPISODIC)

        /**
         * Create a semantic relation with subject type constraint.
         */
        @JvmStatic
        fun semanticForSubject(predicate: String, meaning: String, subjectType: String): Relation =
            Relation(predicate, meaning, KnowledgeType.SEMANTIC, subjectType = subjectType)

        /**
         * Create a procedural relation with subject type constraint.
         */
        @JvmStatic
        fun proceduralForSubject(predicate: String, meaning: String, subjectType: String): Relation =
            Relation(predicate, meaning, KnowledgeType.PROCEDURAL, subjectType = subjectType)

        /**
         * Create a semantic relation with both type constraints.
         */
        @JvmStatic
        fun semanticBetween(predicate: String, meaning: String, subjectType: String, objectType: String): Relation =
            Relation(predicate, meaning, KnowledgeType.SEMANTIC, subjectType = subjectType, objectType = objectType)
    }
}

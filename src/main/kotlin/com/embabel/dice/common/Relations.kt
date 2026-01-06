package com.embabel.dice.common

/**
 * A reusable collection of relation types with builder-style methods.
 * Can be shared across multiple SourceAnalysisContext instances.
 *
 * Usage:
 * ```kotlin
 * val relations = Relations.empty()
 *     .withSemantic("works at", "is employed by")
 *     .withProcedural("prefers", "has preference for")
 *     .withProceduralForSubject("Person", "likes", "dislikes", "prefers")
 *
 * val context = SourceAnalysisContext.withContextId("ctx")
 *     .withEntityResolver(resolver)
 *     .withSchema(schema)
 *     .withRelations(relations)
 * ```
 *
 * @property relations The list of relation types
 */
data class Relations(
    val relations: List<Relation> = emptyList(),
) : Iterable<Relation> {

    companion object {
        /**
         * Create an empty Relations collection.
         */
        @JvmStatic
        fun empty(): Relations = Relations()

        /**
         * Create a Relations collection from existing relations.
         */
        @JvmStatic
        fun of(vararg relations: Relation): Relations = Relations(relations.toList())

        /**
         * Create a Relations collection from a list of relations.
         */
        @JvmStatic
        fun of(relations: List<Relation>): Relations = Relations(relations)
    }

    override fun iterator(): Iterator<Relation> = relations.iterator()

    /**
     * Returns the number of relations in this collection.
     */
    fun size(): Int = relations.size

    /**
     * Returns true if this collection is empty.
     */
    fun isEmpty(): Boolean = relations.isEmpty()

    /**
     * Combines this collection with another.
     */
    operator fun plus(other: Relations): Relations =
        Relations(relations + other.relations)

    /**
     * Adds a single relation.
     */
    operator fun plus(relation: Relation): Relations =
        Relations(relations + relation)

    /**
     * Add a semantic (factual) relation.
     */
    @JvmOverloads
    fun withSemantic(predicate: String, meaning: String = predicate): Relations =
        this + Relation.semantic(predicate, meaning)

    /**
     * Add a procedural (behavioral/preference) relation.
     */
    @JvmOverloads
    fun withProcedural(predicate: String, meaning: String = predicate): Relations =
        this + Relation.procedural(predicate, meaning)

    /**
     * Add an episodic (event-based) relation.
     */
    @JvmOverloads
    fun withEpisodic(predicate: String, meaning: String = predicate): Relations =
        this + Relation.episodic(predicate, meaning)

    /**
     * Add a semantic relation with subject type constraint.
     */
    fun withSemanticForSubject(subjectType: String, predicate: String, meaning: String): Relations =
        this + Relation.semanticForSubject(predicate, meaning, subjectType)

    /**
     * Add a procedural relation with subject type constraint.
     */
    fun withProceduralForSubject(subjectType: String, predicate: String, meaning: String): Relations =
        this + Relation.proceduralForSubject(predicate, meaning, subjectType)

    /**
     * Add a semantic relation with both subject and object type constraints.
     */
    fun withSemanticBetween(
        subjectType: String,
        objectType: String,
        predicate: String,
        meaning: String,
    ): Relations = this + Relation.semanticBetween(predicate, meaning, subjectType, objectType)

    /**
     * Add multiple relations for the same subject type and knowledge type.
     * Uses the predicate as the meaning for brevity.
     *
     * @param subjectType The entity type that can be the subject
     * @param knowledgeType The epistemological nature of these relations
     * @param predicates The predicate strings to add
     */
    fun withPredicatesForSubject(
        subjectType: String,
        knowledgeType: KnowledgeType,
        vararg predicates: String,
    ): Relations {
        val newRelations = predicates.map { predicate ->
            Relation(
                predicate = predicate,
                meaning = predicate,
                knowledgeType = knowledgeType,
                subjectType = subjectType,
            )
        }
        return Relations(relations + newRelations)
    }

    /**
     * Add multiple relations for the same subject type and knowledge type.
     * Uses the predicate as the meaning for brevity.
     *
     * @param subjectType The entity class that can be the subject
     * @param knowledgeType The epistemological nature of these relations
     * @param predicates The predicate strings to add
     */
    fun withPredicatesForSubject(
        subjectType: Class<*>,
        knowledgeType: KnowledgeType,
        vararg predicates: String,
    ): Relations = withPredicatesForSubject(subjectType.simpleName, knowledgeType, *predicates)

    /**
     * Add multiple procedural relations for a subject type.
     * Convenience method for common preference/behavior predicates.
     */
    fun withProceduralPredicatesForSubject(subjectType: String, vararg predicates: String): Relations =
        withPredicatesForSubject(subjectType, KnowledgeType.PROCEDURAL, *predicates)

    /**
     * Add multiple semantic relations for a subject type.
     * Convenience method for common factual predicates.
     */
    fun withSemanticPredicatesForSubject(subjectType: String, vararg predicates: String): Relations =
        withPredicatesForSubject(subjectType, KnowledgeType.SEMANTIC, *predicates)

    /**
     * Returns the relations as a List for compatibility.
     */
    fun toList(): List<Relation> = relations
}

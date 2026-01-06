package com.embabel.dice.common

/**
 * Configuration for how strictly extraction should adhere to the schema.
 *
 * @param entities If true, only extract entities with types defined in the schema.
 *                 If false, prefer schema types but allow important entities outside the schema.
 * @param predicates If true, only extract propositions with predicates defined in the schema's
 *                   relationship metadata. If false, allow any predicates.
 */
data class SchemaAdherence @JvmOverloads constructor(
    val entities: Boolean = true,
    val predicates: Boolean = false,
) {
    companion object {
        /**
         * Strict adherence: only extract entities and predicates defined in the schema.
         */
        @JvmField
        val STRICT = SchemaAdherence(entities = true, predicates = true)

        /**
         * Default: lock entities to schema but allow any predicates.
         */
        @JvmField
        val DEFAULT = SchemaAdherence(entities = true, predicates = false)

        /**
         * Relaxed: prefer schema types but allow entities and predicates outside the schema.
         */
        @JvmField
        val RELAXED = SchemaAdherence(entities = false, predicates = false)
    }
}

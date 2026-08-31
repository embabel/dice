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
package com.embabel.dice.metamodel

import com.embabel.agent.core.Cardinality
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.core.DomainTypePropertyDefinition
import com.embabel.agent.core.NamedPropertyDefinition
import com.embabel.agent.core.PropertyDefinition
import java.security.MessageDigest
import java.util.Objects

/**
 * What a property looks like structurally: its name, whether it holds a plain value or points at
 * another type, what that value or target is, and how many of them there can be.
 *
 * The stamp holds signatures rather than bare names. Turning `age` from a string into an integer,
 * or a single `worksAt` into a list of them, changes what the graph can hold; with only names in
 * the stamp, both would leave [MetamodelVersion.contentHash] untouched.
 *
 * Descriptions and semantic metadata are left out. They steer extraction, but they don't change the
 * shape of what gets stored, so re-wording a description doesn't count as a schema change.
 *
 * @property name The property name.
 * @property kind Whether the property holds a value or points at another domain type.
 * @property type The value type (`string`, `integer`, ...), or the target type's name for a
 *   reference. Empty when [kind] is [Kind.UNKNOWN].
 * @property cardinality How many values the property holds: optional, one, a list, or a set.
 */
data class PropertySignature(
    val name: String,
    val kind: Kind,
    val type: String,
    val cardinality: Cardinality,
) : Comparable<PropertySignature> {

    /** Whether a property holds a value of its own or points at another type in the schema. */
    enum class Kind {
        /** A plain value: string, integer, and so on. */
        VALUE,

        /** A reference to another domain type, which shows up as a relationship in the graph. */
        REFERENCE,

        /**
         * A property kind the agent platform has added that this module doesn't recognise. The
         * name and cardinality still count towards the hash; the type is recorded as empty.
         */
        UNKNOWN,
    }

    override fun compareTo(other: PropertySignature): Int = ORDER.compare(this, other)

    companion object {

        /** Total order used to canonicalise a property set before hashing. */
        private val ORDER = compareBy<PropertySignature>({ it.name }, { it.kind }, { it.type }, { it.cardinality })

        /**
         * Read the structural signature off a property definition.
         *
         * @param property The property definition to summarise.
         * @return Its structural signature.
         */
        @JvmStatic
        fun of(property: PropertyDefinition): PropertySignature = when (property) {
            is DomainTypePropertyDefinition ->
                PropertySignature(property.name, Kind.REFERENCE, property.type.name, property.cardinality)

            is NamedPropertyDefinition ->
                PropertySignature(property.name, Kind.VALUE, property.type, property.cardinality)

            else ->
                PropertySignature(property.name, Kind.UNKNOWN, "", property.cardinality)
        }
    }
}

/**
 * An immutable stamp that captures the identity and structural content of the governed part of a
 * [DataDictionary] at a point in time.
 *
 * Two [MetamodelVersion] instances with the same [contentHash] represent semantically equivalent
 * schemas. A proposition can record the version it was created under via the
 * `dice.metamodel.version` metadata key.
 *
 * The hash is computed here from the structural fields, and a caller cannot supply it. It is the
 * store's natural key and what [hasSameContentAs] compares, so if it were an ordinary constructor
 * argument, two structurally different schemas could claim the same hash, compare as equal, and
 * overwrite each other in storage.
 *
 * Everything handed to the constructor is copied into JVM-immutable collections and
 * canonicalised: type and relationship lists come out sorted and free of duplicates. A stamp whose
 * collections could still be changed from the outside, or that listed the same relationship twice
 * because two same-named types declared it, would disagree with its own precomputed hash. This is
 * a plain class rather than a `data class` for the same reason: a generated `copy()` would hand its
 * arguments straight to the fields and skip all of that.
 *
 * @property schemaName The [DataDictionary.name] at the time the stamp was taken.
 * @property entityTypeNames Governed entity type names, sorted and deduplicated.
 * @property entityTypeLabels Full label set per type (including inherited labels), keyed by type
 *   name. Captured so label-only changes are detectable and reflected in [contentHash].
 * @property entityTypeProperties Full property signature set per type (including inherited
 *   properties), keyed by type name. Signatures rather than bare names, so a property changing type
 *   or cardinality moves [contentHash].
 * @property relationshipNames Rendered `From-[name]->To` descriptors for the relationships the
 *   governed types declare, sorted and deduplicated.
 * @property contentHash SHA-256 hex digest of the schema's entity types, label sets, property
 *   signatures, and allowed relationships, derived from the four fields above. The schema name is
 *   excluded so that two structurally identical schemas are equal regardless of how they are named.
 *   Stable across JVM restarts. Any structural change produces a different hash, including a
 *   property's type changing on a type whose name is unchanged.
 */
class MetamodelVersion(
    schemaName: String,
    entityTypeNames: List<String>,
    entityTypeLabels: Map<String, Set<String>>,
    entityTypeProperties: Map<String, Set<PropertySignature>>,
    relationshipNames: List<String>,
) {

    val schemaName: String = schemaName

    val entityTypeNames: List<String> = immutableCopy(entityTypeNames.distinct().sorted())

    val entityTypeLabels: Map<String, Set<String>> = immutableCopy(entityTypeLabels)

    val entityTypeProperties: Map<String, Set<PropertySignature>> = immutableCopy(entityTypeProperties)

    val relationshipNames: List<String> = immutableCopy(relationshipNames.distinct().sorted())

    init {
        // Only types named in entityTypeNames are walked when hashing, so a map entry keyed by
        // anything else never reaches contentHash, and two stamps holding different labels or
        // properties could share a hash and the store's natural key with it. `this.` is required
        // here: the constructor parameters are still in scope and would shadow the copied fields.
        val known = this.entityTypeNames.toSet()
        val strayLabelKeys = this.entityTypeLabels.keys - known
        require(strayLabelKeys.isEmpty()) {
            "entityTypeLabels is keyed by types missing from entityTypeNames: ${strayLabelKeys.sorted()}. " +
                "Labels for a type that isn't listed never reach contentHash."
        }
        val strayPropertyKeys = this.entityTypeProperties.keys - known
        require(strayPropertyKeys.isEmpty()) {
            "entityTypeProperties is keyed by types missing from entityTypeNames: ${strayPropertyKeys.sorted()}. " +
                "Properties for a type that isn't listed never reach contentHash."
        }
    }

    val contentHash: String = fingerprint()

    /**
     * Returns `true` when this version and [other] have the same structural content (entity types,
     * label sets, property signatures, and relationships), regardless of schema name. Compares
     * [contentHash].
     */
    fun hasSameContentAs(other: MetamodelVersion): Boolean = contentHash == other.contentHash

    /**
     * A deterministic encoding of the structural fields, hashed with SHA-256 and rendered as
     * lowercase hex.
     *
     * This is a persisted format. The digest is the store's natural key, so changing the encoding
     * orphans everything already saved against it. `MetamodelVersionTest` pins the digest of a
     * fixed schema with a literal assertion; changing the encoding means changing that literal
     * deliberately and planning a migration.
     */
    private fun fingerprint(): String {
        // Every name, label and property component is length-prefixed, and each set is preceded by
        // its size. These names come from free-text and LLM extraction and routinely contain ';',
        // '[', '=' and spaces, so a delimiter-joined encoding could make ["a;b"] and ["a", "b"]
        // hash identically and hide a lossy schema change.
        // The schema name is excluded, so two structurally identical schemas produce the same hash
        // even when named differently (e.g. dev vs prod environments).
        val hashInput = buildString {
            append("types:").append(entityTypeNames.size).append('|')
            entityTypeNames.forEach { name ->
                appendSized(name)
                val labels = entityTypeLabels[name].orEmpty().sorted()
                append("labels:").append(labels.size).append('|')
                labels.forEach { appendSized(it) }
                val properties = entityTypeProperties[name].orEmpty().sorted()
                append("props:").append(properties.size).append('|')
                properties.forEach { property ->
                    appendSized(property.name)
                    appendSized(property.kind.name)
                    appendSized(property.type)
                    appendSized(property.cardinality.name)
                }
            }
            append("rels:").append(relationshipNames.size).append('|')
            relationshipNames.forEach { appendSized(it) }
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(hashInput.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MetamodelVersion) return false
        return schemaName == other.schemaName &&
            entityTypeNames == other.entityTypeNames &&
            entityTypeLabels == other.entityTypeLabels &&
            entityTypeProperties == other.entityTypeProperties &&
            relationshipNames == other.relationshipNames
    }

    override fun hashCode(): Int =
        Objects.hash(schemaName, entityTypeNames, entityTypeLabels, entityTypeProperties, relationshipNames)

    override fun toString(): String =
        "MetamodelVersion(schemaName=$schemaName, contentHash=$contentHash, " +
            "entityTypeNames=$entityTypeNames, entityTypeLabels=$entityTypeLabels, " +
            "entityTypeProperties=$entityTypeProperties, relationshipNames=$relationshipNames)"

    companion object {

        /** Append [token] length-prefixed (`<len>:<token>`) so concatenation can't be ambiguous. */
        private fun StringBuilder.appendSized(token: String) {
            append(token.length).append(':').append(token)
        }

        /** Copy [values] into a JVM-immutable list, which a Java caller can't mutate via a getter. */
        private fun <T> immutableCopy(values: List<T>): List<T> = java.util.List.copyOf(values)

        /** Copy a map of sets so both the map and every set inside it are JVM-immutable. */
        private fun <K, V> immutableCopy(values: Map<K, Set<V>>): Map<K, Set<V>> =
            java.util.Map.copyOf(values.mapValues { (_, set) -> java.util.Set.copyOf(set) })

        /**
         * Create a [MetamodelVersion] stamp covering every type in [dataDictionary], which is the
         * right stamp for a domain that is closed-world throughout.
         *
         * @param dataDictionary The schema to stamp.
         * @return An immutable version stamp.
         */
        @JvmStatic
        fun from(dataDictionary: DataDictionary): MetamodelVersion =
            from(dataDictionary, GovernedTypeSelector.ALL)

        /**
         * Create a [MetamodelVersion] stamp covering only the types [selector] governs.
         *
         * DICE domains are usually part closed-world and part open-world. Some types are committed
         * to, and their shape changing is worth noticing; the rest are exploratory, proposed by
         * extraction, and churn by design. Stamping the whole dictionary makes that churn look like
         * a schema change: every new exploratory type produces a new content hash, and the version
         * history fills with entries nobody chose. Governance is therefore per type and opt-in, the
         * way Hibernate's `@Version` is per entity.
         *
         * Adding, removing or reshaping an ungoverned type leaves [contentHash] untouched; doing
         * the same to a governed one changes it. Relationships follow the type that declares them.
         * A governed type's outgoing relationship is part of that type's declared shape and stays
         * in the stamp even when it points at an ungoverned type, while a relationship declared
         * *by* an ungoverned type is left out entirely.
         *
         * @param dataDictionary The schema to stamp.
         * @param selector Which of its types are under governance. [GovernedTypeSelector.ALL]
         *   reproduces [from] exactly.
         * @return An immutable version stamp covering the governed subset.
         */
        @JvmStatic
        fun from(dataDictionary: DataDictionary, selector: GovernedTypeSelector): MetamodelVersion {
            val governedTypes = dataDictionary.domainTypes.filter { selector.governs(it) }

            // A DataDictionary can legally hold two domain types that share a name but differ in
            // shape (DynamicType is a data class, so same-named instances with different labels are
            // not equal and both survive a set). Labels and properties are unioned per name.
            // Keeping only the last would drop a label or property from the fingerprint, and
            // removing it later wouldn't change the hash.
            val entityTypeLabels = governedTypes
                .groupBy { it.name }
                .mapValues { (_, types) -> types.flatMap { it.labels }.toSet() }

            val entityTypeProperties = governedTypes
                .groupBy { it.name }
                .mapValues { (_, types) -> types.flatMap { type -> type.properties.map(PropertySignature::of) }.toSet() }

            // Splitting one type into two same-named declarations, or merging two back into one,
            // can render the same relationship descriptor twice. It is the same schema either way,
            // so it has to be the same hash; the constructor sorts and deduplicates both lists.
            val relationshipNames = dataDictionary.allowedRelationships()
                .filter { selector.governs(it.from) }
                .map { rel -> "${rel.from.name}-[${rel.name}]->${rel.to.name}" }

            return MetamodelVersion(
                schemaName = dataDictionary.name,
                entityTypeNames = governedTypes.map { it.name },
                entityTypeLabels = entityTypeLabels,
                entityTypeProperties = entityTypeProperties,
                relationshipNames = relationshipNames,
            )
        }

        /**
         * The bare relationship type names the governed types declare: the same relationships
         * [from] renders into [relationshipNames], un-rendered.
         *
         * Kept here so the governance rule (a relationship belongs to the type that declares it)
         * lives in one place, and a [DeclaredSchema] can't drift from the stamp beside it.
         */
        internal fun governedRelationshipTypeNames(
            dataDictionary: DataDictionary,
            selector: GovernedTypeSelector,
        ): Set<String> = dataDictionary.allowedRelationships()
            .filter { selector.governs(it.from) }
            .map { it.name }
            .toSet()
    }
}

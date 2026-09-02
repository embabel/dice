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
 * @property aliases Names this property used to go by, so a rename pairs up instead of reading as a
 *   removal and an addition. Declared through [SchemaAliases] and empty unless someone declared
 *   them. Part of the signature, so an alias-only edit moves [MetamodelVersion.contentHash].
 *   Held as handed in, including a mutable set: a `data class` component can't be normalised on the
 *   way through, since a constructor `val` takes no initialiser and the generated `copy` calls that
 *   same constructor. [MetamodelVersion] therefore copies the set into an immutable one when it
 *   takes a signature, and a signature that never reaches a stamp keeps whatever set it was built
 *   with. Experimental: shape may change before 1.0.
 */
data class PropertySignature @JvmOverloads constructor(
    val name: String,
    val kind: Kind,
    val type: String,
    val cardinality: Cardinality,
    val aliases: Set<String> = emptySet(),
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

        /**
         * Total order used to canonicalise a property set before hashing. Aliases come last, so a
         * set holding two signatures that differ only in their aliases still sorts the same way
         * every time.
         */
        private val ORDER = compareBy<PropertySignature>({ it.name }, { it.kind }, { it.type }, { it.cardinality })
            .thenComparator { left, right -> compareAliases(left.aliases, right.aliases) }

        /** Compare two alias sets as sorted lists: element by element, then by size. */
        private fun compareAliases(left: Set<String>, right: Set<String>): Int {
            val sortedLeft = left.sorted()
            val sortedRight = right.sorted()
            for (i in 0 until minOf(sortedLeft.size, sortedRight.size)) {
                val comparison = sortedLeft[i].compareTo(sortedRight[i])
                if (comparison != 0) return comparison
            }
            return sortedLeft.size.compareTo(sortedRight.size)
        }

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
 * schemas. The extraction run records the version it ran under by carrying this content hash, and a
 * proposition's version is answered through its run lineage.
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
 * @property entityTypeAliases Names each entity type used to go by, keyed by its current name, so a
 *   type rename pairs up instead of reading as a type vanishing and another appearing. Declared
 *   through [SchemaAliases] and empty unless someone declared them. Hashed, so an alias-only edit
 *   moves [contentHash]. Experimental: shape may change before 1.0.
 * @property contentHash SHA-256 hex digest of the schema's entity types, label sets, property
 *   signatures, type aliases, and allowed relationships. The schema name is excluded so that two
 *   structurally identical schemas are equal regardless of how they are named. Stable across JVM
 *   restarts. Any structural change produces a different hash, including a property's type changing
 *   on a type whose name is unchanged.
 */
class MetamodelVersion @JvmOverloads constructor(
    schemaName: String,
    entityTypeNames: List<String>,
    entityTypeLabels: Map<String, Set<String>>,
    entityTypeProperties: Map<String, Set<PropertySignature>>,
    relationshipNames: List<String>,
    entityTypeAliases: Map<String, Set<String>> = emptyMap(),
) {

    val schemaName: String = schemaName

    val entityTypeNames: List<String> = immutableCopy(entityTypeNames.distinct().sorted())

    val entityTypeLabels: Map<String, Set<String>> = immutableCopy(entityTypeLabels)

    val entityTypeProperties: Map<String, Set<PropertySignature>> =
        immutableCopy(entityTypeProperties.mapValues { (_, signatures) -> signatures.map(::withImmutableAliases).toSet() })

    val relationshipNames: List<String> = immutableCopy(relationshipNames.distinct().sorted())

    val entityTypeAliases: Map<String, Set<String>> = immutableCopy(entityTypeAliases)

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
        val strayAliasKeys = this.entityTypeAliases.keys - known
        require(strayAliasKeys.isEmpty()) {
            "entityTypeAliases is keyed by types missing from entityTypeNames: ${strayAliasKeys.sorted()}. " +
                "Aliases for a type that isn't listed never reach contentHash."
        }

        // An entry mapping a type to no former names at all hashes differently from having no entry,
        // while saying the same thing, so two stamps of one schema could land on different keys.
        val emptyAliasKeys = this.entityTypeAliases.filterValues { it.isEmpty() }.keys
        require(emptyAliasKeys.isEmpty()) {
            "entityTypeAliases holds empty alias sets for: ${emptyAliasKeys.sorted()}. " +
                "Drop the entry instead; it means the same thing and hashes the same as the types around it."
        }

        requireNoTypeAliasReuse(known, this.entityTypeAliases)
        requireNoAliasesOnDuplicateNames(this.entityTypeProperties)
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
        // Alias blocks are written only when there is something in them, so a schema that declares
        // no former names renders exactly the bytes this encoding produced before aliases existed
        // and keeps every hash already recorded against it. Each block is `<tag>:<count>|` followed
        // by length-prefixed entries in sorted order, which is what the rest of the encoding does.
        val hashInput = buildString {
            append("types:").append(entityTypeNames.size).append('|')
            entityTypeNames.forEach { name ->
                appendSized(name)
                appendAliasBlock("typealiases", entityTypeAliases[name].orEmpty())
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
                    appendAliasBlock("aliases", property.aliases)
                }
            }
            append("rels:").append(relationshipNames.size).append('|')
            relationshipNames.forEach { appendSized(it) }
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(hashInput.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /** Structural equality: same schema name, types, labels, property signatures, relationships, and aliases. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MetamodelVersion) return false
        return schemaName == other.schemaName &&
            entityTypeNames == other.entityTypeNames &&
            entityTypeLabels == other.entityTypeLabels &&
            entityTypeProperties == other.entityTypeProperties &&
            relationshipNames == other.relationshipNames &&
            entityTypeAliases == other.entityTypeAliases
    }

    override fun hashCode(): Int = Objects.hash(
        schemaName,
        entityTypeNames,
        entityTypeLabels,
        entityTypeProperties,
        relationshipNames,
        entityTypeAliases,
    )

    override fun toString(): String =
        "MetamodelVersion(schemaName=$schemaName, contentHash=$contentHash, " +
            "entityTypeNames=$entityTypeNames, entityTypeLabels=$entityTypeLabels, " +
            "entityTypeProperties=$entityTypeProperties, relationshipNames=$relationshipNames, " +
            "entityTypeAliases=$entityTypeAliases)"

    companion object {

        /** Append [token] length-prefixed (`<len>:<token>`) so concatenation can't be ambiguous. */
        private fun StringBuilder.appendSized(token: String) {
            append(token.length).append(':').append(token)
        }

        /**
         * Append `<tag>:<count>|` and the sorted, length-prefixed [aliases], writing nothing at all
         * when there are none.
         */
        private fun StringBuilder.appendAliasBlock(tag: String, aliases: Set<String>) {
            if (aliases.isEmpty()) return
            append(tag).append(':').append(aliases.size).append('|')
            aliases.sorted().forEach { appendSized(it) }
        }

        /** Copy [values] into a JVM-immutable list, which a Java caller can't mutate via a getter. */
        private fun <T> immutableCopy(values: List<T>): List<T> = java.util.List.copyOf(values)

        /** Copy a map of sets so both the map and every set inside it are JVM-immutable. */
        private fun <K, V> immutableCopy(values: Map<K, Set<V>>): Map<K, Set<V>> =
            java.util.Map.copyOf(values.mapValues { (_, set) -> java.util.Set.copyOf(set) })

        /**
         * Re-wrap a signature's alias set as a JVM-immutable one. The signature is a data class, so
         * its alias set arrives however the caller built it; a caller who kept a mutable set and
         * added to it afterwards would leave the stamp disagreeing with its own precomputed hash.
         *
         * The copy is unconditional. An empty set is the dangerous case, not the safe one: a caller
         * holding an empty `mutableSetOf` can add to it after the stamp is built, which moves the
         * signature's own `hashCode` while it sits in a hash-based set and leaves it unfindable in
         * the collection that contains it. `Set.copyOf` of an empty set is a shared singleton, so
         * skipping it buys nothing.
         */
        private fun withImmutableAliases(signature: PropertySignature): PropertySignature =
            signature.copy(aliases = java.util.Set.copyOf(signature.aliases))

        /**
         * Reject a declared type name showing up in another type's alias set.
         *
         * One name would then belong to two types at once: the live type that carries it, and the
         * renamed type that still claims it as a former name. Nothing downstream can tell which of
         * the two a piece of data under that name belongs to. Reusing a retired name means first
         * deleting the alias that still claims it.
         */
        private fun requireNoTypeAliasReuse(
            declaredTypeNames: Set<String>,
            entityTypeAliases: Map<String, Set<String>>,
        ) {
            entityTypeAliases.forEach { (typeName, aliases) ->
                val reused = (aliases - typeName).filter { it in declaredTypeNames }.sorted()
                require(reused.isEmpty()) {
                    "Entity type '$typeName' declares alias(es) $reused, and those name types the " +
                        "schema still declares. Retire the alias before reusing the name."
                }
            }
        }

        /**
         * Reject aliases on a property name a type holds more than one signature for.
         *
         * Two same-named domain types can each declare `age` with a different shape, and the union
         * keeps both signatures. A comparison has no way to say which of the two an old name refers
         * to, so the declaration is refused and the name compares as a removal plus an addition.
         */
        private fun requireNoAliasesOnDuplicateNames(
            entityTypeProperties: Map<String, Set<PropertySignature>>,
        ) {
            entityTypeProperties.forEach { (typeName, signatures) ->
                signatures
                    .groupBy { it.name }
                    .filterValues { withName -> withName.size > 1 }
                    .forEach { (propertyName, withName) ->
                        val declared = withName.flatMap { it.aliases }.distinct().sorted()
                        require(declared.isEmpty()) {
                            "Property '$propertyName' on entity type '$typeName' has " +
                                "${withName.size} signatures and declares alias(es) $declared. " +
                                "Retire the alias: a name with more than one signature can't say " +
                                "which one an old name meant."
                        }
                    }
            }
        }

        /**
         * Create a [MetamodelVersion] stamp covering every type in [dataDictionary], which is the
         * right stamp for a domain that is closed-world throughout.
         *
         * @param dataDictionary The schema to stamp.
         * @return An immutable version stamp.
         */
        @JvmStatic
        fun from(dataDictionary: DataDictionary): MetamodelVersion =
            from(dataDictionary, GovernedTypeSelector.ALL, SchemaAliases.NONE)

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
        fun from(dataDictionary: DataDictionary, selector: GovernedTypeSelector): MetamodelVersion =
            from(dataDictionary, selector, SchemaAliases.NONE)

        /**
         * Create a [MetamodelVersion] stamp covering the types [selector] governs, carrying the
         * former names [aliases] declares for them.
         *
         * The upstream `PropertyDefinition` has nowhere to hold a former name, so aliases are
         * declared alongside the dictionary and applied while the stamp is built: each signature
         * picks up the former names declared for its property, and the type-level map is carried
         * through for the governed types. Aliases declared for a type the selector doesn't govern
         * are dropped, the same way everything else about an ungoverned type is.
         *
         * [aliases] has no default, and the two shorter overloads are separate functions rather
         * than defaulted parameters on this one. A default here would fold all three into one
         * function whose synthetic `from$default` descriptor replaced the shipped one, which is a
         * link error for any caller already compiled against it.
         *
         * @param dataDictionary The schema to stamp.
         * @param selector Which of its types are under governance. [GovernedTypeSelector.ALL]
         *   governs all of them.
         * @param aliases Former names for the schema's types and properties. [SchemaAliases.NONE]
         *   declares none. Experimental: shape may change before 1.0.
         * @return An immutable version stamp covering the governed subset.
         * @throws IllegalArgumentException when a declared type name appears in another type's
         *   alias set, or when aliases are declared for a property name the governed types hold
         *   more than one signature for.
         */
        @JvmStatic
        fun from(
            dataDictionary: DataDictionary,
            selector: GovernedTypeSelector,
            aliases: SchemaAliases,
        ): MetamodelVersion {
            val governedTypes = dataDictionary.domainTypes.filter { selector.governs(it) }

            // A DataDictionary can legally hold two domain types that share a name but differ in
            // shape (DynamicType is a data class, so same-named instances with different labels are
            // not equal and both survive a set). Labels and properties are unioned per name.
            // Keeping only the last would drop a label or property from the fingerprint, and
            // removing it later wouldn't change the hash.
            val entityTypeLabels = governedTypes
                .groupBy { it.name }
                .mapValues { (_, types) -> types.flatMap { it.labels }.toSet() }

            // Decorating inside this loop is the only place it can happen: the stamp is immutable
            // and hashes at construction. Every signature sharing a property name picks up the same
            // declared alias set, so decoration can neither create nor collapse a duplicate, and
            // the constructor's duplicate-name guard sees exactly the duplicates the union holds.
            val entityTypeProperties = governedTypes
                .groupBy { it.name }
                .mapValues { (typeName, types) ->
                    types.flatMap { type ->
                        type.properties.map { property ->
                            val signature = PropertySignature.of(property)
                            val declared = aliases.propertyAliasesFor(typeName, signature.name)
                            if (declared.isEmpty()) signature else signature.copy(aliases = declared)
                        }
                    }.toSet()
                }

            // Splitting one type into two same-named declarations, or merging two back into one,
            // can render the same relationship descriptor twice. It is the same schema either way,
            // so it has to be the same hash; the constructor sorts and deduplicates both lists.
            val relationshipNames = dataDictionary.allowedRelationships()
                .filter { selector.governs(it.from) }
                .map { rel -> "${rel.from.name}-[${rel.name}]->${rel.to.name}" }

            val governedNames = governedTypes.map { it.name }.toSet()
            val entityTypeAliases = aliases.typeAliases.filterKeys { it in governedNames }

            // The two refusals run here as well as in the constructor so a declaration that can't
            // be stamped fails at the call that stamped it, naming the alias to retire.
            requireNoTypeAliasReuse(governedNames, entityTypeAliases)
            requireNoAliasesOnDuplicateNames(entityTypeProperties)

            return MetamodelVersion(
                schemaName = dataDictionary.name,
                entityTypeNames = governedTypes.map { it.name },
                entityTypeLabels = entityTypeLabels,
                entityTypeProperties = entityTypeProperties,
                relationshipNames = relationshipNames,
                entityTypeAliases = entityTypeAliases,
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

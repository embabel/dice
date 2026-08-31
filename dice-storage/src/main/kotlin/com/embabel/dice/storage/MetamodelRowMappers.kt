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
package com.embabel.dice.storage

import com.embabel.agent.core.Cardinality
import com.embabel.dice.metamodel.MetamodelVersion
import com.embabel.dice.metamodel.PropertySignature
import com.embabel.dice.metamodel.StampProvenance
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant

private val objectMapper = ObjectMapper()

/**
 * Translate metamodel versions to and from the property maps the Neo4j graph store reads and
 * writes.
 *
 * Neo4j properties are scalars and flat arrays, while a version's content is lists, a map of label
 * sets, and a map of property signature sets, so all four structural fields are serialized to JSON
 * strings. JSON also handles names containing pipes, tabs, newlines and quotes, which these names
 * routinely do: they come out of LLM extraction.
 *
 * The save instant is informational, and is written twice. `savedAt` is the ISO-8601 string, which
 * is what you want when you're looking at a node and wondering when it landed. `savedAtEpochMillis`
 * is the same instant as a number, for filtering or grouping by time in an ad-hoc query; the string
 * is no use for that, since `Instant.toString()` drops the fraction entirely at a whole second and
 * `'Z'` sorts above `'.'`, making `"…T00:00:00Z"` compare greater than `"…T00:00:00.500Z"`.
 *
 * Neither field orders the history. The `sequence` property does that; see
 * [DrivineMetamodelVersionStore] for why a clock can't express write order. Nothing here writes or
 * reads `sequence`: Cypher assigns it off a per-schema counter, and it is storage bookkeeping, so
 * it stays out of the strict round-trip below.
 *
 * Reads are strict. A property this mapper wrote must be present when it is read again; a node
 * missing one is corrupt, so the accessor throws and the store's surrounding guard skips the row
 * with a warning.
 *
 * Four things are optional, and absent means "none of these were declared": the version-level
 * `entityTypeAliases` property, the `aliases` field inside a stored property signature, and the
 * `origin` and `lastStamped` properties. Writing them only when they hold something means an
 * alias-free stamp with no provenance stores exactly the properties this mapper stored before any
 * of the four existed, and a node written by that older build reads back here as a stamp declaring
 * none of them. Aliases feed the content hash, so a stamp carrying them and failing to store them
 * would fail its own integrity check on the way back in and be unreadable for good.
 */
object MetamodelVersionRowMapper {

    /**
     * Bind values for a write. The natural key is (schemaName, contentHash).
     *
     * [savedAt] is a parameter, so this stays a pure function of its arguments and a test can pin
     * the instant a version was stored at.
     *
     * `entityTypeAliases`, `origin` and `lastStamped` bind `null` when the version declares none.
     * A Cypher `SET` of `null` leaves no property behind, which is the encoding the read side
     * expects and the shape an older writer left.
     */
    fun bindMap(version: MetamodelVersion, savedAt: Instant): Map<String, Any?> = mapOf(
        "schemaName" to version.schemaName,
        "contentHash" to version.contentHash,
        "entityTypeNames" to serializeList(version.entityTypeNames),
        "entityTypeLabels" to serializeMapOfLabelSets(version.entityTypeLabels),
        "entityTypeProperties" to serializeMapOfSignatureSets(version.entityTypeProperties),
        "relationshipNames" to serializeList(version.relationshipNames),
        "entityTypeAliases" to serializeAliasMap(version.entityTypeAliases),
        "origin" to serializeProvenance(version.origin),
        "lastStamped" to serializeProvenance(version.lastStamped),
        "savedAt" to savedAt.toString(),
        "savedAtEpochMillis" to savedAt.toEpochMilli(),
    )

    /**
     * Rebuild a [MetamodelVersion] from a returned node's property map, and check its integrity
     * on the way.
     *
     * A version's content hash is derived from its structural fields, so the reconstructed object
     * computes its own hash and the `contentHash` property on the node acts as a checksum.
     * Recomputing it and getting a different answer means the node was written by an older hash
     * format, hand-edited, or corrupted, so this throws and the caller skips it. The stored hash is
     * also half the natural key, so a mismatch also means a re-save of the same content lands on a
     * different node.
     *
     * Aliases are part of that derivation, at both levels, so a node that dropped either alias
     * field fails here rather than reading back as an alias-free stamp with the wrong hash.
     * Provenance is not, so a node's `origin` and `lastStamped` are carried through untouched by
     * the check.
     */
    fun fromRow(row: Map<*, *>): MetamodelVersion {
        val storedHash = row.str("contentHash")
        val version = MetamodelVersion(
            schemaName = row.str("schemaName"),
            entityTypeNames = deserializeList(row.str("entityTypeNames")),
            entityTypeLabels = deserializeMapOfLabelSets(row.str("entityTypeLabels")),
            entityTypeProperties = deserializeMapOfSignatureSets(row.str("entityTypeProperties")),
            relationshipNames = deserializeList(row.str("relationshipNames")),
            entityTypeAliases = deserializeAliasMap(row.optionalStr("entityTypeAliases")),
            origin = deserializeProvenance(row.optionalStr("origin"), "origin"),
            lastStamped = deserializeProvenance(row.optionalStr("lastStamped"), "lastStamped"),
        )
        require(version.contentHash == storedHash) {
            "MetamodelVersion '${version.schemaName}' fails its integrity check: stored contentHash " +
                "$storedHash, but the persisted structural fields hash to ${version.contentHash}"
        }
        return version
    }
}

// Serialization helpers: JSON, for escape-safe round-trip encoding.

private fun serializeList(items: List<String>): String =
    objectMapper.writeValueAsString(items)

private fun deserializeList(serialized: String): List<String> =
    if (serialized.isEmpty()) emptyList()
    else objectMapper.readValue(
        serialized,
        objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java)
    )

/**
 * Serialize the per-type label sets as `{"Person": ["Agent", "Entity"], ...}`.
 *
 * Sets have no order, so they're written sorted. Nothing reads the order back, but a deterministic
 * encoding means re-saving the same version writes byte-identical JSON, which keeps an idempotent
 * MERGE a no-op and makes a stored node diffable by hand.
 */
private fun serializeMapOfLabelSets(map: Map<String, Set<String>>): String =
    objectMapper.writeValueAsString(map.toSortedMap().mapValues { (_, labels) -> labels.sorted() })

/**
 * Serialize the former names each entity type goes by, in the same shape as the label sets, and
 * write nothing at all when no type declares any.
 *
 * The empty case has to leave no property behind. This map feeds the content hash, and a stamp that
 * declares no aliases hashes to the same digest it did before aliases existed, so its node must
 * also look the way the older writer left it — otherwise the two spellings of one schema are two
 * different-looking nodes on the same key.
 */
private fun serializeAliasMap(aliases: Map<String, Set<String>>): String? =
    if (aliases.isEmpty()) null else serializeMapOfLabelSets(aliases)

/** Inverse of [serializeAliasMap]; an absent property means no type declared a former name. */
private fun deserializeAliasMap(serialized: String?): Map<String, Set<String>> =
    if (serialized.isNullOrEmpty()) emptyMap() else deserializeMapOfLabelSets(serialized)

/**
 * Serialize a [StampProvenance] as `{"actor": ..., "trigger": ...}`, and write nothing when the
 * stamp carries none.
 *
 * A JSON object rather than two scalar properties, because `StampProvenance()` with both fields
 * unset is a real provenance and has to stay distinguishable from no provenance at all; two scalar
 * properties would encode both as two absent values.
 *
 * Nothing here sizes the value. [StampProvenance] caps `actor` and `trigger` at 256 characters, and
 * a Neo4j string property has no declared width, so there is no column to size. A backend that
 * stores them in a byte-sized column needs room for the up-to-1024 UTF-8 bytes 256 characters can
 * take.
 */
private fun serializeProvenance(provenance: StampProvenance?): String? = provenance?.let {
    objectMapper.writeValueAsString(linkedMapOf("actor" to it.actor, "trigger" to it.trigger))
}

/**
 * Inverse of [serializeProvenance]. An absent property means the stamp carried no provenance, which
 * is what a node written before provenance existed looks like.
 *
 * Malformed content throws, naming the [property] it came from. The character cap is re-applied by
 * [StampProvenance]'s own constructor, so a hand-edit that pushes `actor` past it makes the node
 * unreadable and the store skips it with a warning rather than handing back a value the model says
 * is impossible.
 */
private fun deserializeProvenance(serialized: String?, property: String): StampProvenance? {
    if (serialized.isNullOrEmpty()) return null
    val parsed = objectMapper.readValue(serialized, Any::class.java)
    val fields = parsed as? Map<*, *> ?: throw IllegalArgumentException(
        "the stored '$property' is a ${parsed?.javaClass?.simpleName ?: "null"} where a provenance " +
            "object with 'actor' and 'trigger' was expected"
    )
    return StampProvenance(
        actor = fields.provenanceField(property, "actor"),
        trigger = fields.provenanceField(property, "trigger"),
    )
}

/** Read one nullable field of a stored provenance object, refusing anything that isn't a string. */
private fun Map<*, *>.provenanceField(property: String, field: String): String? =
    when (val value = this[field]) {
        null -> null
        is String -> value
        else -> throw IllegalArgumentException(
            "the '$field' of the stored '$property' is a ${value.javaClass.simpleName} where a string was expected"
        )
    }

/** Inverse of [serializeMapOfLabelSets]. */
private fun deserializeMapOfLabelSets(serialized: String): Map<String, Set<String>> {
    if (serialized.isEmpty()) return emptyMap()
    @Suppress("UNCHECKED_CAST")
    val mapOfLists = objectMapper.readValue(
        serialized,
        objectMapper.typeFactory.constructMapType(Map::class.java, String::class.java, List::class.java),
    ) as Map<String, List<String>>
    return mapOfLists.mapValues { (_, labels) -> labels.toSet() }
}

/**
 * Serialize the per-type property signatures as a JSON object of arrays of four-field objects:
 *
 * ```json
 * {"Person": [{"name": "age", "kind": "VALUE", "type": "integer", "cardinality": "ONE"}]}
 * ```
 *
 * The fields are written out one by one. This shape on disk is a persisted format that feeds the
 * version's own content hash on the way back in, so it has to stay put when someone renames a
 * Kotlin property or when `jackson-module-kotlin` leaves the classpath, which is what handing the
 * object to Jackson's bean serializer would risk.
 *
 * A property that declares former names gets a fifth field, `"aliases": ["oldName", ...]`, sorted
 * for the same determinism as everywhere else. A property with none gets exactly the four fields
 * above, so a signature that declares no aliases encodes the bytes this mapper wrote before aliases
 * existed and a stored four-field signature still reads.
 *
 * Enums are stored by `name`. An ordinal would re-point at a different constant the moment someone
 * inserts a value into [Cardinality] or [PropertySignature.Kind].
 */
private fun serializeMapOfSignatureSets(map: Map<String, Set<PropertySignature>>): String =
    objectMapper.writeValueAsString(
        map.toSortedMap().mapValues { (_, signatures) ->
            signatures.sorted().map { signature ->
                // A LinkedHashMap, so the keys land in the JSON in this order and the encoding is
                // fully determined by the content.
                linkedMapOf<String, Any>(
                    "name" to signature.name,
                    "kind" to signature.kind.name,
                    "type" to signature.type,
                    "cardinality" to signature.cardinality.name,
                ).apply {
                    if (signature.aliases.isNotEmpty()) put("aliases", signature.aliases.sorted())
                }
            }
        }
    )

/**
 * Inverse of [serializeMapOfSignatureSets], and strict about it: a signature object missing a
 * field, or naming an enum constant this build doesn't have, throws. Patching it up with a default
 * would change the structural content, and the version's integrity check would then reject the
 * whole row with a message about a hash mismatch that hides the real problem.
 */
private fun deserializeMapOfSignatureSets(serialized: String): Map<String, Set<PropertySignature>> {
    if (serialized.isEmpty()) return emptyMap()
    @Suppress("UNCHECKED_CAST")
    val mapOfLists = objectMapper.readValue(
        serialized,
        objectMapper.typeFactory.constructMapType(Map::class.java, String::class.java, List::class.java),
    ) as Map<String, List<Any?>>
    return mapOfLists.mapValues { (typeName, encoded) ->
        encoded.map { element ->
            val fields = element as? Map<*, *>
                ?: throw IllegalArgumentException(
                    "entityTypeProperties for '$typeName' holds ${element?.javaClass?.simpleName ?: "null"} " +
                        "where a property signature object was expected"
                )
            PropertySignature(
                name = fields.signatureField(typeName, "name"),
                kind = enumConstant(fields.signatureField(typeName, "kind"), typeName, "kind"),
                type = fields.signatureField(typeName, "type"),
                cardinality = enumConstant(fields.signatureField(typeName, "cardinality"), typeName, "cardinality"),
                aliases = fields.signatureAliases(typeName),
            )
        }.toSet()
    }
}

/** Read one field of a stored property signature, blowing up by name if it isn't there. */
private fun Map<*, *>.signatureField(typeName: String, field: String): String =
    this[field]?.toString() ?: throw IllegalArgumentException(
        "a property signature for '$typeName' is missing its '$field' field"
    )

/**
 * Read a stored signature's former names. An absent `aliases` field means none were declared, which
 * is every signature written before aliases existed. Anything present but not a list of names
 * throws: aliases are part of the signature and feed the content hash, so quietly dropping a
 * malformed one would surface later as a hash mismatch instead.
 */
private fun Map<*, *>.signatureAliases(typeName: String): Set<String> {
    val encoded = this["aliases"] ?: return emptySet()
    val names = encoded as? List<*> ?: throw IllegalArgumentException(
        "a property signature for '$typeName' has an 'aliases' field holding a " +
            "${encoded.javaClass.simpleName} where a list of former names was expected"
    )
    return names.map { name ->
        name?.toString() ?: throw IllegalArgumentException(
            "a property signature for '$typeName' has a null entry in its 'aliases' field"
        )
    }.toSet()
}

/** Turn a stored enum constant name back into the constant, naming what failed if it's unknown. */
private inline fun <reified E : Enum<E>> enumConstant(stored: String, typeName: String, field: String): E =
    enumValues<E>().firstOrNull { it.name == stored } ?: throw IllegalArgumentException(
        "a property signature for '$typeName' has '$field' = '$stored', which is not a known " +
            "${E::class.simpleName} — the node was written by a different version of the schema model"
    )

/**
 * Read a property that must be there, and blow up if it isn't.
 *
 * Returning `""` for an absent property would let a node missing `schemaName` come back as a
 * real-looking version named `""`, indistinguishable from data, and the caller's "skip the
 * unreadable row" guard would never fire for the most likely kind of corruption there is. Throwing
 * is what gives that guard something to catch.
 */
private fun Map<*, *>.str(key: String): String =
    this[key]?.toString() ?: throw IllegalArgumentException("required property '$key' is missing from the stored node")

/**
 * Read a property that may legitimately not be there, where absent means the stamp declared nothing
 * to put in it. Only the alias map and the two provenance fields are read this way; everything else
 * goes through [str].
 */
private fun Map<*, *>.optionalStr(key: String): String? = this[key]?.toString()

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
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant

private val objectMapper = ObjectMapper()

/**
 * Translate metamodel versions to and from the property maps the Neo4j graph store reads and
 * writes.
 *
 * Neo4j properties are scalars and flat arrays, and a version's content is neither — it is lists,
 * a map of label sets, and a map of property *signature* sets. So all four structural fields are
 * serialized to JSON strings. JSON also handles names containing pipes, tabs, newlines and quotes,
 * which matters because these names come out of LLM extraction and routinely do.
 *
 * **The timestamp is informational, and is written twice anyway.** `savedAt` is the ISO-8601 string,
 * which is what you want when you're looking at a node and wondering when it landed. `savedAtEpochMillis`
 * is the same instant as a number, which is what you want when you're filtering or grouping by time
 * in an ad-hoc query — the string is no use for that, since `Instant.toString()` drops the fraction
 * entirely at a whole second and `'Z'` sorts above `'.'`, making `"…T00:00:00Z"` compare greater than
 * `"…T00:00:00.500Z"`.
 *
 * Neither one orders the history. That's the `sequence` property's job — see
 * [DrivineMetamodelVersionStore], which explains why a clock can't express write order. Nothing here
 * writes or reads `sequence`: it's assigned by Cypher off a per-schema counter, and it is storage
 * bookkeeping rather than part of the stamp, so it stays out of the strict round-trip below.
 *
 * **Reads are strict.** A property this mapper wrote must be present when it is read again. A node
 * missing one is corrupt — not a version with a blank name — so the accessor throws and the store's
 * surrounding guard skips the row with a warning instead of quietly materializing junk.
 */
object MetamodelVersionRowMapper {

    /**
     * Bind values for a write — the natural key is (schemaName, contentHash).
     *
     * [savedAt] is passed in rather than read from the wall clock here, so this stays a pure
     * function of its arguments and a test can pin the instant a version was stored at.
     */
    fun bindMap(version: MetamodelVersion, savedAt: Instant): Map<String, Any?> = mapOf(
        "schemaName" to version.schemaName,
        "contentHash" to version.contentHash,
        "entityTypeNames" to serializeList(version.entityTypeNames),
        "entityTypeLabels" to serializeMapOfLabelSets(version.entityTypeLabels),
        "entityTypeProperties" to serializeMapOfSignatureSets(version.entityTypeProperties),
        "relationshipNames" to serializeList(version.relationshipNames),
        "savedAt" to savedAt.toString(),
        "savedAtEpochMillis" to savedAt.toEpochMilli(),
    )

    /**
     * Rebuild a [MetamodelVersion] from a returned node's property map, and check its integrity
     * on the way.
     *
     * A version's content hash is derived from its structural fields, not stored alongside them as
     * an independent value, so the reconstructed object computes its own hash. The `contentHash`
     * property on the node is therefore a checksum rather than data: recomputing it and finding a
     * different answer means the node was written by an older hash format, hand-edited, or
     * corrupted. Either way it is not the version it claims to be, so this throws and the caller
     * skips it. Note the stored hash is also half the natural key, so a mismatch would additionally
     * mean a re-save of the same content lands on a *different* node.
     */
    fun fromRow(row: Map<*, *>): MetamodelVersion {
        val storedHash = row.str("contentHash")
        val version = MetamodelVersion(
            schemaName = row.str("schemaName"),
            entityTypeNames = deserializeList(row.str("entityTypeNames")),
            entityTypeLabels = deserializeMapOfLabelSets(row.str("entityTypeLabels")),
            entityTypeProperties = deserializeMapOfSignatureSets(row.str("entityTypeProperties")),
            relationshipNames = deserializeList(row.str("relationshipNames")),
        )
        require(version.contentHash == storedHash) {
            "MetamodelVersion '${version.schemaName}' fails its integrity check: stored contentHash " +
                "$storedHash, but the persisted structural fields hash to ${version.contentHash}"
        }
        return version
    }
}

// Serialization helpers: JSON for escape-safe round-trip encoding.

/** Serialize a list to a JSON string. */
private fun serializeList(items: List<String>): String =
    objectMapper.writeValueAsString(items)

/** Deserialize a JSON string back to a list. */
private fun deserializeList(serialized: String): List<String> =
    if (serialized.isEmpty()) emptyList()
    else objectMapper.readValue(
        serialized,
        objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java)
    )

/**
 * Serialize the per-type label sets as `{"Person": ["Agent", "Entity"], ...}`.
 *
 * Sets have no order, so they're written sorted. Nothing reads the order back — the sets go into a
 * `Set` again — but a deterministic encoding means re-saving the same version writes byte-identical
 * JSON, which keeps an idempotent MERGE genuinely a no-op and makes a stored node diffable by hand.
 */
private fun serializeMapOfLabelSets(map: Map<String, Set<String>>): String =
    objectMapper.writeValueAsString(map.toSortedMap().mapValues { (_, labels) -> labels.sorted() })

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
 * Written out field by field rather than handed to Jackson's bean serializer. The shape on disk is
 * a persisted format — it feeds the version's own content hash on the way back in — so it is spelled
 * out here where you can see it, and doesn't move because someone renames a Kotlin property or
 * because `jackson-module-kotlin` is or isn't on the classpath.
 *
 * Enums are stored by `name`, not ordinal. An ordinal would silently re-point at a different
 * constant the moment someone inserts a value into [Cardinality] or [PropertySignature.Kind].
 */
private fun serializeMapOfSignatureSets(map: Map<String, Set<PropertySignature>>): String =
    objectMapper.writeValueAsString(
        map.toSortedMap().mapValues { (_, signatures) ->
            signatures.sorted().map { signature ->
                // A LinkedHashMap, so the keys land in this order in the JSON and the encoding is
                // fully determined by the content.
                linkedMapOf(
                    "name" to signature.name,
                    "kind" to signature.kind.name,
                    "type" to signature.type,
                    "cardinality" to signature.cardinality.name,
                )
            }
        }
    )

/**
 * Inverse of [serializeMapOfSignatureSets], and strict about it: a signature object missing a field,
 * or naming an enum constant this build doesn't have, throws rather than being patched up with a
 * default. A guessed default would change the structural content, and the version's integrity check
 * would then reject the whole row anyway — with a confusing message about a hash mismatch instead of
 * the real problem.
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
            )
        }.toSet()
    }
}

/** Read one field of a stored property signature, blowing up by name if it isn't there. */
private fun Map<*, *>.signatureField(typeName: String, field: String): String =
    this[field]?.toString() ?: throw IllegalArgumentException(
        "a property signature for '$typeName' is missing its '$field' field"
    )

/** Turn a stored enum constant name back into the constant, naming what failed if it's unknown. */
private inline fun <reified E : Enum<E>> enumConstant(stored: String, typeName: String, field: String): E =
    enumValues<E>().firstOrNull { it.name == stored } ?: throw IllegalArgumentException(
        "a property signature for '$typeName' has '$field' = '$stored', which is not a known " +
            "${E::class.simpleName} — the node was written by a different version of the schema model"
    )

/**
 * Read a property that must be there, and blow up if it isn't.
 *
 * Returning `""` for an absent property would be the friendlier-looking choice and is precisely
 * the wrong one: a node missing `schemaName` would come back as a real-looking version named `""`,
 * indistinguishable from data, and the caller's "skip the unreadable row" guard would never fire
 * for the most likely kind of corruption there is. Throwing is what makes that guard mean
 * something.
 */
private fun Map<*, *>.str(key: String): String =
    this[key]?.toString() ?: throw IllegalArgumentException("required property '$key' is missing from the stored node")

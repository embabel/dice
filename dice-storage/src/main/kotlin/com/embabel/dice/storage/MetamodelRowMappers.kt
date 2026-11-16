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
import com.embabel.agent.core.ContextId
import com.embabel.dice.metamodel.DriftReport
import com.embabel.dice.metamodel.MetamodelChange
import com.embabel.dice.metamodel.MetamodelDiff
import com.embabel.dice.metamodel.MetamodelVersion
import com.embabel.dice.metamodel.PropertySignature
import com.fasterxml.jackson.core.type.TypeReference
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
 * with a warning. An empty string is corrupt too: an empty collection is written as `[]` or
 * `{}`, so `""` never comes from this mapper, and it fails the read like any other bad JSON.
 *
 * Two things are optional, and absent means "no former names were declared": the version-level
 * `entityTypeAliases` property, and the `aliases` field inside a stored property signature. Writing
 * them only when they hold something means an alias-free stamp stores exactly the properties this
 * mapper stored before either existed, and a node written by that older build reads back here as a
 * stamp declaring neither. Aliases feed the content hash, so a stamp carrying them and failing to
 * store them would fail its own integrity check on the way back in and be unreadable for good.
 */
object MetamodelVersionRowMapper {

    /**
     * Bind values for a write. The natural key is (schemaName, contentHash).
     *
     * [savedAt] is a parameter, so this stays a pure function of its arguments and a test can pin
     * the instant a version was stored at.
     *
     * `entityTypeAliases` binds `null` when the version declares no former names. A Cypher `SET` of
     * `null` leaves no property behind, which is the encoding the read side expects and the shape an
     * older writer left.
     */
    fun bindMap(version: MetamodelVersion, savedAt: Instant): Map<String, Any?> = mapOf(
        "schemaName" to version.schemaName,
        "contentHash" to version.contentHash,
        "entityTypeNames" to serializeList(version.entityTypeNames),
        "entityTypeLabels" to serializeMapOfLabelSets(version.entityTypeLabels),
        "entityTypeProperties" to serializeMapOfSignatureSets(version.entityTypeProperties),
        "relationshipNames" to serializeList(version.relationshipNames),
        "entityTypeAliases" to serializeAliasMap(version.entityTypeAliases),
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
     */
    fun fromRow(row: Map<*, *>): MetamodelVersion {
        val storedHash = row.str("contentHash")
        val version = MetamodelVersion(
            schemaName = row.str("schemaName"),
            entityTypeNames = deserializeList(row.str("entityTypeNames")),
            entityTypeLabels = deserializeMapOfLabelSets(row.str("entityTypeLabels")),
            entityTypeProperties = deserializeMapOfSignatureSets(row.str("entityTypeProperties")),
            relationshipNames = deserializeList(row.str("relationshipNames")),
            entityTypeAliases = deserializeAliasMap(row.strOrNull("entityTypeAliases")),
        )
        require(version.contentHash == storedHash) {
            "MetamodelVersion '${version.schemaName}' fails its integrity check: stored contentHash " +
                "$storedHash, but the persisted structural fields hash to ${version.contentHash}"
        }
        return version
    }
}

/**
 * Translate drift reports to and from the property maps the Neo4j graph store reads and writes.
 *
 * Neo4j properties are scalars and flat arrays, while a report's drifted type sets are collections,
 * so both sets are serialized to JSON strings. JSON also handles names containing pipes, tabs,
 * newlines and quotes, which these names routinely do: they come out of LLM extraction. Both sets
 * are written sorted, so re-saving one observation writes byte-identical JSON and the MERGE is a
 * no-op. Nothing reads the order back; the sets are read into a `Set`.
 *
 * The capture instant is written three ways. `capturedAt` is the ISO-8601 string and is half the
 * natural key, so it is what round trips. `capturedAtEpochSecond` and `capturedAtNano` let the
 * database sort and range-filter on the instant at full precision. Epoch milliseconds, which a
 * version stamp uses, truncate: two reports captured 500µs apart would compare equal, leaving
 * "newest first" arbitrary between them, and a `since` bound falling inside a millisecond would
 * sweep in reports captured just before it. Sorting on the ISO string has its own failure —
 * `Instant.toString()` writes no fraction on a whole second and `'Z'` outranks `'.'`, so
 * `12:00:00Z` sorts after `12:00:00.500Z`.
 *
 * A fourth property, `contextKey`, encodes the report's scope; see [GLOBAL_CONTEXT_KEY].
 *
 * The report's declared diff is stored as one JSON string in `declaredDiff`, holding `fromVersion`,
 * `toVersion` and `changes`. Both versions are written in full with the field encodings a
 * `(:MetamodelVersion)` node uses, so the report reads back without a version store. Each change is
 * an object whose `kind` is the change's simple class name, with its fields written one by one and
 * its sets sorted. This is a persisted format: renaming a change kind or a field leaves stored
 * reports unreadable.
 *
 * Reads are strict: a property this mapper wrote must be present when it is read again. `contextId`
 * and `declaredDiff` are the two exceptions. An absent `contextId` is how a global report is
 * encoded, and an absent `declaredDiff` is how a report with no declared diff is encoded, which
 * covers every report node an older build wrote. A node missing anything else is corrupt,
 * so the accessor throws and the store's surrounding guard skips the row with a warning. A
 * `declaredDiff` that is present and cannot be read throws the same way.
 */
object DriftReportRowMapper {

    /**
     * The stand-in `contextKey` a global (unscoped) report carries.
     *
     * A nullable `contextId` can't go into a MERGE key directly: Cypher property-map equality
     * against a literal `null` matches nothing, including a node that has no such property, so a
     * global report would take the CREATE branch on every retry and duplicate. `contextKey` is
     * never null, so MERGE can key on it and a global report is as idempotent as a scoped one.
     */
    const val GLOBAL_CONTEXT_KEY: String = "global"

    /** The prefix every real context's key carries. */
    private const val CONTEXT_KEY_PREFIX = "ctx:"

    /**
     * The MERGE key a report's scope contributes: `global`, or `ctx:` followed by the context id.
     *
     * The prefix is what makes the encoding injective. `ContextId` accepts any non-blank string, so
     * encoding scope as the bare context id with a sentinel standing in for global lets a caller
     * name a context after the sentinel. A global report and that context's report would then share
     * a MERGE key, land on one node, and each save would rewrite the other's scope, surfacing a
     * context-scoped finding as whole-graph drift or the reverse. With the prefix the two spaces are
     * disjoint: `global` carries no `ctx:` prefix, so no context id can produce it, and
     * `ContextId("global")` maps to `ctx:global`.
     */
    fun contextKeyFor(contextId: ContextId?): String =
        contextId?.let { CONTEXT_KEY_PREFIX + it.value } ?: GLOBAL_CONTEXT_KEY

    /**
     * Bind values for a write. The natural key is
     * `(schemaName, versionHash, capturedAt, contextKey)`.
     */
    fun bindMap(report: DriftReport): Map<String, Any?> = mapOf(
        "schemaName" to report.schemaName,
        "versionHash" to report.versionHash,
        "capturedAt" to report.capturedAt.toString(),
        "capturedAtEpochSecond" to report.capturedAt.epochSecond,
        "capturedAtNano" to report.capturedAt.nano,
        "driftedEntityTypes" to serializeList(report.driftedEntityTypes.sorted()),
        "driftedRelationshipTypes" to serializeList(report.driftedRelationshipTypes.sorted()),
        "contextKey" to contextKeyFor(report.contextId),
        // Null for a global report, which leaves the node with no `contextId` property at all. The
        // store's global read matches on that absence.
        "contextId" to report.contextId?.value,
        // Null when the report has no declared diff, which leaves the node with no property.
        "declaredDiff" to report.declaredDiff?.let(::serializeDeclaredDiff),
    )

    /**
     * Rebuild a [DriftReport] from a returned node's property map, checking on the way that the two
     * halves of its scope still agree.
     *
     * The scope is stored twice: as `contextId`, absent when global, and as the never-null
     * `contextKey` the natural key merges on. Reads and writes use different halves — a scoped read
     * matches `contextId`, a save merges on `contextKey`. A node where the two disagree would answer
     * to one scope when read and another when re-saved, so the row is refused.
     */
    fun fromRow(row: Map<*, *>): DriftReport {
        val contextId = row.strOrNull("contextId")?.let { ContextId(it) }
        val storedKey = row.str("contextKey")
        require(storedKey == contextKeyFor(contextId)) {
            "MetamodelDriftReport for '${row.strOrNull("schemaName")}' fails its scope check: it is stored " +
                "under contextKey '$storedKey' but reads back as " +
                "${contextId?.let { "context '${it.value}'" } ?: "a global report"}"
        }
        return DriftReport(
            schemaName = row.str("schemaName"),
            versionHash = row.str("versionHash"),
            driftedEntityTypes = deserializeSet(row.str("driftedEntityTypes")),
            driftedRelationshipTypes = deserializeSet(row.str("driftedRelationshipTypes")),
            capturedAt = Instant.parse(row.str("capturedAt")),
            contextId = contextId,
            declaredDiff = row.strOrNull("declaredDiff")?.let(::deserializeDeclaredDiff),
        )
    }
}

// Serialization helpers: JSON, for escape-safe round-trip encoding.

private fun serializeList(items: List<String>): String =
    objectMapper.writeValueAsString(items)

private fun deserializeList(serialized: String): List<String> =
    objectMapper.readValue(
        serialized,
        objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java)
    )

/** Deserialize a JSON string back to a set, the form a drift report's drifted type fields take. */
private fun deserializeSet(serialized: String): Set<String> =
    if (serialized.isEmpty()) emptySet()
    else objectMapper.readValue(
        serialized,
        objectMapper.typeFactory.constructCollectionType(Set::class.java, String::class.java)
    )

/**
 * Serialize the per-type label sets as `{"Person": ["Agent", "Entity"], ...}`.
 *
 * Sets have no order, so they're written sorted. Nothing reads the order back, but a deterministic
 * encoding means re-saving the same version writes byte-identical JSON, which keeps an idempotent
 * MERGE a no-op and makes a stored node diffable by hand.
 */
private fun serializeMapOfLabelSets(map: Map<String, Set<String>>): String =
    objectMapper.writeValueAsString(labelSetsStructure(map))

/**
 * Build the JSON structure [serializeMapOfLabelSets] writes: keys sorted, each set as a sorted list.
 *
 * @param map The name sets to encode, keyed by entity type.
 * @return A map in key order whose values are sorted lists.
 */
private fun labelSetsStructure(map: Map<String, Set<String>>): Map<String, List<String>> =
    map.toSortedMap().mapValues { (_, labels) -> labels.sorted() }

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
    if (serialized == null) emptyMap() else deserializeMapOfLabelSets(serialized)

/** Inverse of [serializeMapOfLabelSets]. */
private fun deserializeMapOfLabelSets(serialized: String): Map<String, Set<String>> {
    val mapOfLists = objectMapper.readValue(
        serialized,
        object : TypeReference<Map<String, List<String>>>() {},
    )
    return mapOfLists.mapValues { (_, labels) -> labels.toSet() }
}

/**
 * Read a nested label or alias map back from parsed JSON, checking each value is a list of names.
 *
 * @param field The name of the field the map came from, for the error message.
 * @param encoded The parsed JSON object.
 * @return The name sets, keyed by entity type.
 */
private fun labelSetsFrom(field: String, encoded: Map<*, *>): Map<String, Set<String>> =
    encoded.entries.associate { (typeName, names) ->
        typeName.toString() to stringsIn("$field for '$typeName'", names).toSet()
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
    objectMapper.writeValueAsString(signatureSetsStructure(map))

/**
 * Build the JSON structure [serializeMapOfSignatureSets] writes: types sorted, each type's
 * signatures sorted and encoded by [signatureStructure].
 *
 * @param map The property signatures to encode, keyed by entity type.
 * @return A map in key order whose values are lists of signature objects.
 */
private fun signatureSetsStructure(map: Map<String, Set<PropertySignature>>): Map<String, List<Map<String, Any>>> =
    map.toSortedMap().mapValues { (_, signatures) -> signatures.sorted().map(::signatureStructure) }

/**
 * Build one stored property signature: its four named fields, plus `aliases` when it declares any.
 *
 * @param signature The signature to encode.
 * @return The signature's fields in a fixed key order.
 */
private fun signatureStructure(signature: PropertySignature): Map<String, Any> =
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

/**
 * Inverse of [serializeMapOfSignatureSets], and strict about it: a signature object missing a
 * field, or naming an enum constant this build doesn't have, throws. Patching it up with a default
 * would change the structural content, and the version's integrity check would then reject the
 * whole row with a message about a hash mismatch that hides the real problem.
 */
private fun deserializeMapOfSignatureSets(serialized: String): Map<String, Set<PropertySignature>> {
    val mapOfLists = objectMapper.readValue(
        serialized,
        object : TypeReference<Map<String, List<Any?>>>() {},
    )
    return mapOfLists.mapValues { (typeName, encoded) ->
        encoded.map { element -> signatureFrom(typeName, element) }.toSet()
    }
}

/**
 * Read a nested map of property signature sets back from parsed JSON.
 *
 * @param encoded The parsed JSON object, keyed by entity type.
 * @return The property signatures, keyed by entity type.
 */
private fun signatureSetsFrom(encoded: Map<*, *>): Map<String, Set<PropertySignature>> =
    encoded.entries.associate { (typeName, signatures) ->
        val elements = signatures as? List<*>
        require(elements != null) {
            "entityTypeProperties for '$typeName' holds ${signatures?.javaClass?.simpleName ?: "null"} " +
                "where a list of property signatures was expected"
        }
        typeName.toString() to elements.map { signatureFrom(typeName.toString(), it) }.toSet()
    }

/**
 * Read one stored property signature object, strict about every field.
 *
 * @param typeName The entity type the signature belongs to, for the error message.
 * @param element The parsed JSON value that should be a signature object.
 * @return The signature.
 */
private fun signatureFrom(typeName: String, element: Any?): PropertySignature {
    val fields = element as? Map<*, *>
    require(fields != null) {
        "entityTypeProperties for '$typeName' holds ${element?.javaClass?.simpleName ?: "null"} " +
            "where a property signature object was expected"
    }
    return PropertySignature(
        name = fields.signatureField(typeName, "name"),
        kind = enumConstant(fields.signatureField(typeName, "kind"), typeName, "kind"),
        type = fields.signatureField(typeName, "type"),
        cardinality = enumConstant(fields.signatureField(typeName, "cardinality"), typeName, "cardinality"),
        aliases = fields.signatureAliases(typeName),
    )
}

/** Read one field of a stored property signature, blowing up by name if it isn't there. */
private fun Map<*, *>.signatureField(typeName: String, field: String): String =
    requireNotNull(this[field]) {
        "a property signature for '$typeName' is missing its '$field' field"
    }.toString()

/**
 * Read a stored signature's former names. An absent `aliases` field means none were declared, which
 * is every signature written before aliases existed. Anything present but not a list of names
 * throws: aliases are part of the signature and feed the content hash, so quietly dropping a
 * malformed one would surface later as a hash mismatch instead.
 */
private fun Map<*, *>.signatureAliases(typeName: String): Set<String> {
    val encoded = this["aliases"] ?: return emptySet()
    val names = encoded as? List<*>
    require(names != null) {
        "a property signature for '$typeName' has an 'aliases' field holding a " +
            "${encoded.javaClass.simpleName} where a list of former names was expected"
    }
    return names.map { name ->
        requireNotNull(name) {
            "a property signature for '$typeName' has a null entry in its 'aliases' field"
        }.toString()
    }.toSet()
}

/** Turn a stored enum constant name back into the constant, naming what failed if it's unknown. */
private inline fun <reified E : Enum<E>> enumConstant(stored: String, typeName: String, field: String): E =
    requireNotNull(enumValues<E>().firstOrNull { it.name == stored }) {
        "a property signature for '$typeName' has '$field' = '$stored', which is not a known " +
            "${E::class.simpleName}. The node was written by a different version of the schema model"
    }

/**
 * Write a drift report's declared diff as the JSON string stored in `declaredDiff`.
 *
 * @param diff The declared diff to store.
 * @return The JSON, the same bytes every time for the same diff.
 */
private fun serializeDeclaredDiff(diff: MetamodelDiff): String =
    objectMapper.writeValueAsString(
        linkedMapOf(
            "fromVersion" to versionStructure(diff.fromVersion),
            "toVersion" to versionStructure(diff.toVersion),
            "changes" to diff.changes.map(::changeStructure),
        ),
    )

/**
 * Build a version as nested JSON with the field encodings a `(:MetamodelVersion)` node uses. No
 * content hash is written, because the version computes its own when it is read back.
 *
 * @param version The version to encode.
 * @return The version's fields in a fixed key order, with `entityTypeAliases` only when it declares some.
 */
private fun versionStructure(version: MetamodelVersion): Map<String, Any> =
    linkedMapOf<String, Any>(
        "schemaName" to version.schemaName,
        "entityTypeNames" to version.entityTypeNames,
        "entityTypeLabels" to labelSetsStructure(version.entityTypeLabels),
        "entityTypeProperties" to signatureSetsStructure(version.entityTypeProperties),
        "relationshipNames" to version.relationshipNames,
    ).apply {
        if (version.entityTypeAliases.isNotEmpty()) put("entityTypeAliases", labelSetsStructure(version.entityTypeAliases))
    }

/**
 * Build one change as a JSON object: `kind` first, then the change's fields one by one, sets sorted.
 *
 * The `when` has no `else`, so a new change kind fails compilation here until it has an encoding.
 *
 * @param change The change to encode.
 * @return The change's fields in a fixed key order.
 */
private fun changeStructure(change: MetamodelChange): Map<String, Any> = when (change) {
    is MetamodelChange.EntityTypeAdded -> linkedMapOf(
        "kind" to "EntityTypeAdded",
        "typeName" to change.typeName,
    )
    is MetamodelChange.EntityTypeRemoved -> linkedMapOf(
        "kind" to "EntityTypeRemoved",
        "typeName" to change.typeName,
    )
    is MetamodelChange.EntityTypeRenamed -> linkedMapOf(
        "kind" to "EntityTypeRenamed",
        "before" to change.before,
        "after" to change.after,
    )
    is MetamodelChange.AmbiguousEntityTypeRename -> linkedMapOf(
        "kind" to "AmbiguousEntityTypeRename",
        "formerNames" to change.formerNames.sorted(),
        "candidates" to change.candidates.sorted(),
    )
    is MetamodelChange.EntityTypeAliasesChanged -> linkedMapOf(
        "kind" to "EntityTypeAliasesChanged",
        "typeName" to change.typeName,
        "before" to change.before.sorted(),
        "after" to change.after.sorted(),
    )
    is MetamodelChange.EntityTypeModified -> linkedMapOf(
        "kind" to "EntityTypeModified",
        "typeName" to change.typeName,
        "addedLabels" to change.addedLabels.sorted(),
        "removedLabels" to change.removedLabels.sorted(),
        "addedProperties" to change.addedProperties.sorted().map(::signatureStructure),
        "removedProperties" to change.removedProperties.sorted().map(::signatureStructure),
    )
    is MetamodelChange.PropertyRenamed -> linkedMapOf(
        "kind" to "PropertyRenamed",
        "typeName" to change.typeName,
        "before" to signatureStructure(change.before),
        "after" to signatureStructure(change.after),
    )
    is MetamodelChange.AmbiguousPropertyRename -> linkedMapOf(
        "kind" to "AmbiguousPropertyRename",
        "typeName" to change.typeName,
        "formerNames" to change.formerNames.sorted(),
        "candidates" to change.candidates.sorted(),
    )
    is MetamodelChange.PropertySignatureChanged -> linkedMapOf(
        "kind" to "PropertySignatureChanged",
        "typeName" to change.typeName,
        "propertyName" to change.propertyName,
        "before" to signatureStructure(change.before),
        "after" to signatureStructure(change.after),
    )
    is MetamodelChange.RelationshipAdded -> linkedMapOf(
        "kind" to "RelationshipAdded",
        "descriptor" to change.descriptor,
    )
    is MetamodelChange.RelationshipRemoved -> linkedMapOf(
        "kind" to "RelationshipRemoved",
        "descriptor" to change.descriptor,
    )
}

/**
 * Read a stored `declaredDiff` back into a [MetamodelDiff], strict about every part of it.
 *
 * Bad JSON, an unknown change kind, a missing field and a value a constructor refuses all throw an
 * [IllegalArgumentException] whose message starts with `declaredDiff`, so the store's guard skips
 * the row and the warning says which property was unreadable.
 *
 * @param serialized The stored JSON string.
 * @return The declared diff.
 */
private fun deserializeDeclaredDiff(serialized: String): MetamodelDiff =
    try {
        val fields = objectMapper.readValue(serialized, Map::class.java)
        MetamodelDiff(
            fromVersion = versionFrom(fields.jsonObject("fromVersion")),
            toVersion = versionFrom(fields.jsonObject("toVersion")),
            changes = fields.jsonList("changes").map { element ->
                val change = element as? Map<*, *>
                require(change != null) {
                    "'changes' holds ${element?.javaClass?.simpleName ?: "null"} where a change object was expected"
                }
                changeFrom(change)
            },
        )
    } catch (e: Exception) {
        throw IllegalArgumentException("declaredDiff could not be read: ${e.message}", e)
    }

/**
 * Rebuild a version from the nested JSON [versionStructure] writes.
 *
 * @param fields The parsed version object.
 * @return The version, which computes its own content hash.
 */
private fun versionFrom(fields: Map<*, *>): MetamodelVersion = MetamodelVersion(
    schemaName = fields.jsonString("schemaName"),
    entityTypeNames = fields.jsonStrings("entityTypeNames"),
    entityTypeLabels = labelSetsFrom("entityTypeLabels", fields.jsonObject("entityTypeLabels")),
    entityTypeProperties = signatureSetsFrom(fields.jsonObject("entityTypeProperties")),
    relationshipNames = fields.jsonStrings("relationshipNames"),
    entityTypeAliases = if (fields["entityTypeAliases"] == null) emptyMap()
    else labelSetsFrom("entityTypeAliases", fields.jsonObject("entityTypeAliases")),
)

/**
 * Rebuild one change from the JSON object [changeStructure] writes.
 *
 * @param fields The parsed change object.
 * @return The change, built through its own constructor and so through its own checks.
 */
private fun changeFrom(fields: Map<*, *>): MetamodelChange = when (val kind = fields.jsonString("kind")) {
    "EntityTypeAdded" -> MetamodelChange.EntityTypeAdded(fields.jsonString("typeName"))
    "EntityTypeRemoved" -> MetamodelChange.EntityTypeRemoved(fields.jsonString("typeName"))
    "EntityTypeRenamed" -> MetamodelChange.EntityTypeRenamed(fields.jsonString("before"), fields.jsonString("after"))
    "AmbiguousEntityTypeRename" -> MetamodelChange.AmbiguousEntityTypeRename(
        formerNames = fields.jsonStrings("formerNames").toSet(),
        candidates = fields.jsonStrings("candidates").toSet(),
    )
    "EntityTypeAliasesChanged" -> MetamodelChange.EntityTypeAliasesChanged(
        typeName = fields.jsonString("typeName"),
        before = fields.jsonStrings("before").toSet(),
        after = fields.jsonStrings("after").toSet(),
    )
    "EntityTypeModified" -> {
        val typeName = fields.jsonString("typeName")
        MetamodelChange.EntityTypeModified(
            typeName = typeName,
            addedLabels = fields.jsonStrings("addedLabels").toSet(),
            removedLabels = fields.jsonStrings("removedLabels").toSet(),
            addedProperties = fields.jsonList("addedProperties").map { signatureFrom(typeName, it) }.toSet(),
            removedProperties = fields.jsonList("removedProperties").map { signatureFrom(typeName, it) }.toSet(),
        )
    }
    "PropertyRenamed" -> {
        val typeName = fields.jsonString("typeName")
        MetamodelChange.PropertyRenamed(
            typeName = typeName,
            before = signatureFrom(typeName, fields.jsonObject("before")),
            after = signatureFrom(typeName, fields.jsonObject("after")),
        )
    }
    "AmbiguousPropertyRename" -> MetamodelChange.AmbiguousPropertyRename(
        typeName = fields.jsonString("typeName"),
        formerNames = fields.jsonStrings("formerNames").toSet(),
        candidates = fields.jsonStrings("candidates").toSet(),
    )
    "PropertySignatureChanged" -> {
        val typeName = fields.jsonString("typeName")
        MetamodelChange.PropertySignatureChanged(
            typeName = typeName,
            propertyName = fields.jsonString("propertyName"),
            before = signatureFrom(typeName, fields.jsonObject("before")),
            after = signatureFrom(typeName, fields.jsonObject("after")),
        )
    }
    "RelationshipAdded" -> MetamodelChange.RelationshipAdded(fields.jsonString("descriptor"))
    "RelationshipRemoved" -> MetamodelChange.RelationshipRemoved(fields.jsonString("descriptor"))
    else -> throw IllegalArgumentException("change kind '$kind' is not one this build knows")
}

/**
 * Read a field of a parsed JSON object that must be present.
 *
 * @param field The field name.
 * @return The field's value.
 */
private fun Map<*, *>.jsonField(field: String): Any =
    requireNotNull(this[field]) { "field '$field' is missing" }

/**
 * Read a field of a parsed JSON object that must be a string.
 *
 * @param field The field name.
 * @return The string.
 */
private fun Map<*, *>.jsonString(field: String): String {
    val value = jsonField(field)
    require(value is String) { "field '$field' holds ${value.javaClass.simpleName} where a string was expected" }
    return value
}

/**
 * Read a field of a parsed JSON object that must be an object.
 *
 * @param field The field name.
 * @return The object as a map.
 */
private fun Map<*, *>.jsonObject(field: String): Map<*, *> {
    val value = jsonField(field)
    require(value is Map<*, *>) { "field '$field' holds ${value.javaClass.simpleName} where an object was expected" }
    return value
}

/**
 * Read a field of a parsed JSON object that must be an array.
 *
 * @param field The field name.
 * @return The array's elements.
 */
private fun Map<*, *>.jsonList(field: String): List<*> {
    val value = jsonField(field)
    require(value is List<*>) { "field '$field' holds ${value.javaClass.simpleName} where an array was expected" }
    return value
}

/**
 * Read a field of a parsed JSON object that must be an array of strings.
 *
 * @param field The field name.
 * @return The strings, in stored order.
 */
private fun Map<*, *>.jsonStrings(field: String): List<String> = stringsIn("field '$field'", jsonField(field))

/**
 * Check that a parsed JSON value is an array of strings.
 *
 * @param what What the value is, for the error message.
 * @param value The parsed JSON value.
 * @return The strings, in stored order.
 */
private fun stringsIn(what: String, value: Any?): List<String> {
    require(value is List<*>) { "$what holds ${value?.javaClass?.simpleName ?: "null"} where an array of names was expected" }
    return value.map { name ->
        require(name is String) { "$what holds ${name?.javaClass?.simpleName ?: "null"} where a name was expected" }
        name
    }
}

/**
 * Read a property that must be there, and blow up if it isn't.
 *
 * Returning `""` for an absent property would let a node missing `schemaName` come back as a
 * real-looking version named `""`, indistinguishable from data, and the caller's "skip the
 * unreadable row" guard would never fire for the most likely kind of corruption there is. Throwing
 * is what gives that guard something to catch.
 */
private fun Map<*, *>.str(key: String): String =
    requireNotNull(this[key]) { "required property '$key' is missing from the stored node" }.toString()

/**
 * Read a property whose absence carries meaning: a stamp that declared no aliases, a drift report
 * whose check covered the whole graph and so wrote no `contextId`, or a drift report with no
 * `declaredDiff`. Every other property goes through [str].
 */
private fun Map<*, *>.strOrNull(key: String): String? = this[key]?.toString()

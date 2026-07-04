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

import com.embabel.dice.metamodel.DriftReport
import com.embabel.dice.metamodel.MetamodelVersion
import java.time.Instant

/**
 * Translate metamodel versions to and from the property maps the Neo4j graph stores read and
 * write. Type sets (entity type names, labels per type, properties per type, relationship names)
 * are serialized to pipe-delimited lists and deserialized back into their original structures.
 *
 * Timestamps are written as ISO-8601 strings and parsed back, which keeps reads off the
 * database's native temporal types and gives a single, predictable round-trip. A bad timestamp
 * on read falls back to [Instant.EPOCH] rather than "now", so a corrupt record never looks
 * freshly written.
 */
object MetamodelVersionRowMapper {

    /** Bind values for a write — the natural key is (schemaName, contentHash). */
    fun bindMap(version: MetamodelVersion): Map<String, Any?> = mapOf(
        "schemaName" to version.schemaName,
        "contentHash" to version.contentHash,
        "entityTypeNames" to serializeList(version.entityTypeNames),
        "entityTypeLabels" to serializeMapOfSets(version.entityTypeLabels),
        "entityTypeProperties" to serializeMapOfSets(version.entityTypeProperties),
        "relationshipNames" to serializeList(version.relationshipNames),
        "savedAt" to Instant.now().toString(),
    )

    /** Rebuild a [MetamodelVersion] from a returned node's property map. */
    fun fromRow(row: Map<*, *>): MetamodelVersion = MetamodelVersion(
        schemaName = row.str("schemaName"),
        contentHash = row.str("contentHash"),
        entityTypeNames = deserializeList(row.strOrNull("entityTypeNames") ?: ""),
        entityTypeLabels = deserializeMapOfSets(row.strOrNull("entityTypeLabels") ?: ""),
        entityTypeProperties = deserializeMapOfSets(row.strOrNull("entityTypeProperties") ?: ""),
        relationshipNames = deserializeList(row.strOrNull("relationshipNames") ?: ""),
    )
}

/**
 * Translate drift reports to and from the property maps the Neo4j graph stores read and write.
 * The drifting entity and relationship type sets are serialized to pipe-delimited strings and
 * deserialized back.
 *
 * Timestamps are written as ISO-8601 strings and parsed back. A bad timestamp falls back to
 * [Instant.EPOCH].
 */
object DriftReportRowMapper {

    /** Bind values for a write — the natural key is (schemaName, versionHash, capturedAt). */
    fun bindMap(report: DriftReport): Map<String, Any?> = mapOf(
        "schemaName" to report.schemaName,
        "versionHash" to report.versionHash,
        "capturedAt" to report.capturedAt.toString(),
        "driftingEntityTypes" to serializeList(report.driftingEntityTypes.sorted()),
        "driftingRelationshipTypes" to serializeList(report.driftingRelationshipTypes.sorted()),
    )

    /** Rebuild a [DriftReport] from a returned node's property map. */
    fun fromRow(row: Map<*, *>): DriftReport = DriftReport(
        schemaName = row.str("schemaName"),
        versionHash = row.str("versionHash"),
        driftingEntityTypes = deserializeSet(row.strOrNull("driftingEntityTypes") ?: ""),
        driftingRelationshipTypes = deserializeSet(row.strOrNull("driftingRelationshipTypes") ?: ""),
        capturedAt = parseInstant(row.strOrNull("capturedAt")),
    )
}

// Serialization helpers: tab/newline-delimited for robust round-trip fidelity.

/** Serialize a list to a pipe-delimited string. */
private fun serializeList(items: List<String>): String =
    items.joinToString("|")

/** Deserialize a pipe-delimited string back to a list. */
private fun deserializeList(serialized: String): List<String> =
    if (serialized.isEmpty()) emptyList()
    else serialized.split("|")

/** Deserialize a pipe-delimited string back to a set. */
private fun deserializeSet(serialized: String): Set<String> =
    if (serialized.isEmpty()) emptySet()
    else serialized.split("|").toSet()

/**
 * Serialize a map of Set<String> to a tab/newline-delimited string.
 * Format: "type1\tlab1\tlab2\ntype2\tlab3\n"
 * Uses tabs within a type entry and newlines to separate types.
 */
private fun serializeMapOfSets(map: Map<String, Set<String>>): String =
    map.toSortedMap().entries.joinToString("\n") { (typeName, labels) ->
        (listOf(typeName) + labels.sorted()).joinToString("\t")
    }

/**
 * Deserialize a tab/newline-delimited string back to a map of sets.
 * Inverse of [serializeMapOfSets].
 */
private fun deserializeMapOfSets(serialized: String): Map<String, Set<String>> {
    if (serialized.isEmpty()) return emptyMap()
    return serialized.split("\n")
        .filter { it.isNotEmpty() }
        .map { line ->
            val parts = line.split("\t")
            if (parts.isEmpty()) null
            else parts.first() to parts.drop(1).toSet()
        }
        .filterNotNull()
        .toMap()
}

private fun Map<*, *>.str(key: String): String = this[key]?.toString().orEmpty()

private fun Map<*, *>.strOrNull(key: String): String? = this[key]?.toString()

private fun parseInstant(value: String?): Instant =
    value?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH

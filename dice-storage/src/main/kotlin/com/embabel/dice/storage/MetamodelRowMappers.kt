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

import com.embabel.agent.core.ContextId
import com.embabel.dice.metamodel.DriftReport
import com.embabel.dice.metamodel.MetamodelVersion
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant

private val objectMapper = ObjectMapper()

/**
 * Translate metamodel versions to and from the property maps the Neo4j graph stores read and
 * write. Type sets (entity type names, labels per type, properties per type, relationship names)
 * are serialized to JSON strings for escape-safe round-trip encoding that handles names
 * containing any characters (pipes, tabs, newlines, quotes, etc.).
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
 * The drifting entity and relationship type sets are serialized to JSON strings for escape-safe
 * round-trip encoding that handles names containing any characters (pipes, tabs, newlines,
 * quotes, etc.).
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
        // Left out of the node entirely (not written as an empty/sentinel string) when null, so a
        // global report has no contextId property at all -- see fromRow, and the store's scoped
        // driftReports query, which relies on that absence. ContextId is stored as its plain
        // string value -- the Neo4j property shape is unchanged from before ContextId replaced
        // the raw String here.
        "contextId" to report.contextId?.value,
    )

    /** Rebuild a [DriftReport] from a returned node's property map. */
    fun fromRow(row: Map<*, *>): DriftReport = DriftReport(
        schemaName = row.str("schemaName"),
        versionHash = row.str("versionHash"),
        driftingEntityTypes = deserializeSet(row.strOrNull("driftingEntityTypes") ?: ""),
        driftingRelationshipTypes = deserializeSet(row.strOrNull("driftingRelationshipTypes") ?: ""),
        capturedAt = parseInstant(row.strOrNull("capturedAt")),
        contextId = row.strOrNull("contextId")?.let { ContextId(it) },
    )
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

/** Deserialize a JSON string back to a set. */
private fun deserializeSet(serialized: String): Set<String> =
    if (serialized.isEmpty()) emptySet()
    else objectMapper.readValue(
        serialized,
        objectMapper.typeFactory.constructCollectionType(Set::class.java, String::class.java)
    )

/**
 * Serialize a map of Set<String> to a JSON string.
 * The map is stored as: {"type1": ["lab1", "lab2"], "type2": ["lab3"], ...}
 */
private fun serializeMapOfSets(map: Map<String, Set<String>>): String =
    objectMapper.writeValueAsString(map.mapValues { (_, v) -> v.toList() })

/**
 * Deserialize a JSON string back to a map of sets.
 * Inverse of [serializeMapOfSets]: parses JSON object with lists and converts them to sets.
 */
private fun deserializeMapOfSets(serialized: String): Map<String, Set<String>> {
    if (serialized.isEmpty()) return emptyMap()
    @Suppress("UNCHECKED_CAST")
    val mapOfLists: Map<String, List<String>> = objectMapper.readValue(
        serialized,
        objectMapper.typeFactory.constructMapType(
            Map::class.java,
            String::class.java,
            List::class.java
        )
    ) as Map<String, List<String>>
    return mapOfLists.mapValues { (_, v) -> v.toSet() }
}

private fun Map<*, *>.str(key: String): String = this[key]?.toString().orEmpty()

private fun Map<*, *>.strOrNull(key: String): String? = this[key]?.toString()

private fun parseInstant(value: String?): Instant =
    value?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH

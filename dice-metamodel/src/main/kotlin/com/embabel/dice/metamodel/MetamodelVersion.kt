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

import com.embabel.agent.core.DataDictionary
import java.security.MessageDigest

/**
 * An immutable stamp that captures the identity and structural content of a
 * [DataDictionary] at a point in time.
 *
 * Two [MetamodelVersion] instances with the same [contentHash] represent semantically
 * equivalent schemas. A proposition can record the version it was created under via
 * the `DiceMetadataKeys.METAMODEL_VERSION` metadata key.
 *
 * @property schemaName The [DataDictionary.name] at the time the stamp was taken.
 * @property contentHash SHA-256 hex digest of the schema's entity types, label sets, property
 *   names, and allowed relationships — structural content only. The schema name is intentionally
 *   excluded so that two structurally identical schemas are equal regardless of how they are named.
 *   Stable across JVM restarts; any change — even labels or properties on a type whose name is
 *   preserved — produces a different hash.
 * @property entityTypeNames Sorted list of entity type names at stamp time.
 * @property entityTypeLabels Full label set per type (including inherited labels), keyed by type
 *   name. Captured so label-only drift is detectable and reflected in [contentHash].
 * @property entityTypeProperties Full property-name set per type (including inherited properties),
 *   keyed by type name. Captured so property-only drift is detectable and reflected in [contentHash].
 * @property relationshipNames Sorted list of allowed relationship names at stamp time.
 */
data class MetamodelVersion(
    val schemaName: String,
    val contentHash: String,
    val entityTypeNames: List<String>,
    val entityTypeLabels: Map<String, Set<String>>,
    val entityTypeProperties: Map<String, Set<String>>,
    val relationshipNames: List<String>,
) {

    /**
     * Returns `true` when this version and [other] have the same structural content —
     * identical entity types, label sets, property names, and relationships — regardless
     * of schema name. Uses [contentHash] for the comparison.
     */
    fun hasSameContentAs(other: MetamodelVersion): Boolean = contentHash == other.contentHash

    companion object {

        /**
         * Create a [MetamodelVersion] stamp from the given [DataDictionary].
         *
         * The content hash is derived from a deterministic string representation of
         * all entity type names (sorted) and all allowed relationship descriptors
         * (sorted), so renaming or adding/removing any type or relationship produces
         * a different hash.
         *
         * @param dataDictionary The schema to stamp.
         * @return An immutable version stamp.
         */
        @JvmStatic
        fun from(dataDictionary: DataDictionary): MetamodelVersion {
            // A DataDictionary can legally hold two domain types that share a name but differ in
            // shape (DynamicType is a data class, so same-named instances with different labels are
            // not equal and both survive a set). Merge their labels and properties by union per name
            // rather than letting associate() keep only the last — otherwise a label or property
            // present under that name would vanish from the fingerprint, and later removing it
            // wouldn't change the hash, hiding a real drift.
            val entityTypeLabels = dataDictionary.domainTypes
                .groupBy { it.name }
                .mapValues { (_, types) -> types.flatMap { it.labels }.toSet() }

            val entityTypeProperties = dataDictionary.domainTypes
                .groupBy { it.name }
                .mapValues { (_, types) -> types.flatMap { type -> type.properties.map { it.name } }.toSet() }

            val entityTypeNames = entityTypeLabels.keys.sorted()

            val relationshipNames = dataDictionary.allowedRelationships()
                .map { rel -> "${rel.from.name}-[${rel.name}]->${rel.to.name}" }
                .sorted()

            val hashInput = buildString {
                append("|types:")
                // Each type contributes its name, its sorted label set, and its sorted property-name
                // set, so a label-only or property-only change (same name) yields a different hash.
                // Schema name is deliberately excluded: two structurally identical schemas must
                // produce the same hash even when named differently (e.g. dev vs prod environments).
                entityTypeNames.forEach { name ->
                    append(name).append("=labels[")
                    entityTypeLabels[name].orEmpty().sorted().forEach { append(it).append(';') }
                    append("]props[")
                    entityTypeProperties[name].orEmpty().sorted().forEach { append(it).append(';') }
                    append("],")
                }
                append("|rels:")
                relationshipNames.forEach { append(it).append(',') }
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(hashInput.toByteArray(Charsets.UTF_8))
            val contentHash = hashBytes.joinToString("") { "%02x".format(it) }

            return MetamodelVersion(
                schemaName = dataDictionary.name,
                contentHash = contentHash,
                entityTypeNames = entityTypeNames,
                entityTypeLabels = entityTypeLabels,
                entityTypeProperties = entityTypeProperties,
                relationshipNames = relationshipNames,
            )
        }
    }
}

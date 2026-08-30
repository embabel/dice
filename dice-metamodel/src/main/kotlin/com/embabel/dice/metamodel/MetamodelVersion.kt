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
 * The hash is computed here, from the structural fields, and is not something a caller can
 * supply. That matters because the hash is load-bearing: it is the store's natural key, it is
 * what [hasSameContentAs] compares, and it is what a `DriftReport` records as the baseline it
 * checked against. If it were an ordinary constructor argument, two structurally different
 * schemas could claim the same hash, compare as equal, and overwrite each other in storage.
 *
 * @property schemaName The [DataDictionary.name] at the time the stamp was taken.
 * @property entityTypeNames Sorted list of entity type names at stamp time.
 * @property entityTypeLabels Full label set per type (including inherited labels), keyed by type
 *   name. Captured so label-only drift is detectable and reflected in [contentHash].
 * @property entityTypeProperties Full property-name set per type (including inherited properties),
 *   keyed by type name. Captured so property-only drift is detectable and reflected in [contentHash].
 * @property relationshipNames Sorted list of allowed relationship names at stamp time.
 * @property contentHash SHA-256 hex digest of the schema's entity types, label sets, property
 *   names, and allowed relationships — structural content only, derived from the four fields
 *   above. The schema name is intentionally excluded so that two structurally identical schemas
 *   are equal regardless of how they are named. Stable across JVM restarts; any change — even
 *   labels or properties on a type whose name is preserved — produces a different hash.
 */
data class MetamodelVersion(
    val schemaName: String,
    val entityTypeNames: List<String>,
    val entityTypeLabels: Map<String, Set<String>>,
    val entityTypeProperties: Map<String, Set<String>>,
    val relationshipNames: List<String>,
) {

    val contentHash: String =
        fingerprint(entityTypeNames, entityTypeLabels, entityTypeProperties, relationshipNames)

    /**
     * Returns `true` when this version and [other] have the same structural content —
     * identical entity types, label sets, property names, and relationships — regardless
     * of schema name. Uses [contentHash] for the comparison.
     */
    fun hasSameContentAs(other: MetamodelVersion): Boolean = contentHash == other.contentHash

    companion object {

        /** Append [token] length-prefixed (`<len>:<token>`) so concatenation can't be ambiguous. */
        private fun StringBuilder.appendSized(token: String) {
            append(token.length).append(':').append(token)
        }

        /**
         * The content hash itself: a deterministic encoding of the structural fields, hashed with
         * SHA-256 and rendered as lowercase hex.
         *
         * This is a **persisted** format. The digest is a MERGE key in the store and a stored
         * foreign key on every drift report, so changing the encoding orphans every report already
         * on disk. `MetamodelVersionTest` pins the digest of a fixed schema with a literal
         * assertion for exactly that reason — if you change the encoding, change that literal
         * deliberately and plan a migration.
         */
        private fun fingerprint(
            entityTypeNames: List<String>,
            entityTypeLabels: Map<String, Set<String>>,
            entityTypeProperties: Map<String, Set<String>>,
            relationshipNames: List<String>,
        ): String {
            // Build a collision-free fingerprint by length-prefixing every name, label, and property
            // (and counting each set) before hashing. A plain delimiter-joined encoding isn't safe
            // here: these names come from free-text / LLM extraction and routinely contain ';', '[',
            // '=' and spaces, so joining with those characters could make ["a;b"] and ["a", "b"] hash
            // identically and hide a real, lossy schema change. Length-prefixing makes the encoding
            // unambiguous, so distinct content always yields a distinct hash.
            // Schema name is deliberately excluded: two structurally identical schemas must produce
            // the same hash even when named differently (e.g. dev vs prod environments).
            val hashInput = buildString {
                val sortedTypeNames = entityTypeNames.sorted()
                append("types:").append(sortedTypeNames.size).append('|')
                sortedTypeNames.forEach { name ->
                    appendSized(name)
                    val labels = entityTypeLabels[name].orEmpty().sorted()
                    append("labels:").append(labels.size).append('|')
                    labels.forEach { appendSized(it) }
                    val props = entityTypeProperties[name].orEmpty().sorted()
                    append("props:").append(props.size).append('|')
                    props.forEach { appendSized(it) }
                }
                val sortedRelationshipNames = relationshipNames.sorted()
                append("rels:").append(sortedRelationshipNames.size).append('|')
                sortedRelationshipNames.forEach { appendSized(it) }
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(hashInput.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * Create a [MetamodelVersion] stamp from the given [DataDictionary].
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

            return MetamodelVersion(
                schemaName = dataDictionary.name,
                entityTypeNames = entityTypeNames,
                entityTypeLabels = entityTypeLabels,
                entityTypeProperties = entityTypeProperties,
                relationshipNames = relationshipNames,
            )
        }
    }
}

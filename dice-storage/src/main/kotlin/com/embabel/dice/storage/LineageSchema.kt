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

import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.UniquenessConstraintSpec

/**
 * The node labels the lineage record stores write, and the natural key each one is upserted on, as
 * plain data.
 *
 * Three things read this and have to agree. [DrivineProjectionRecordStore] and
 * [DrivineCollectorRecordStore] build their MERGE patterns from [mergePattern], so a record is
 * always upserted on the key named here. A host, and the integration-test harness, registers this
 * object as a [DiceStorageSchema] bean, which is what makes those MERGEs race-free: a MERGE on an
 * unconstrained key lets concurrent writers all miss the match, all create, and fill the lineage
 * table with duplicates. The same registration tells [DiceOwnedSchema] which
 * `(:ProjectionRecord)` and `(:CollectorRecord)` nodes in a graph are dice's own.
 *
 * Keeping all three off one map is what stops them drifting apart. A key that appears in a store's
 * Cypher and nowhere else can lose its constraint, or stop matching the shape an observation
 * recognises, with nothing failing to say so.
 */
object LineageSchema : DiceStorageSchema {

    /** One node per projection outcome. */
    const val PROJECTION_RECORD: String = "ProjectionRecord"

    /** One node per collector decision about a proposition. */
    const val COLLECTOR_RECORD: String = "CollectorRecord"

    /** One node per collector run. */
    const val COLLECTOR_RUN: String = "CollectorRun"

    /**
     * The properties each record is keyed on: the values that decide whether a write updates an
     * existing node or creates one.
     */
    val NATURAL_KEYS: Map<String, List<String>> = mapOf(
        PROJECTION_RECORD to listOf("propositionId", "runId", "target"),
        COLLECTOR_RECORD to listOf("propositionId", "runId"),
        COLLECTOR_RUN to listOf("runId"),
    )

    /** Every node label the lineage stores write, for test cleanup and for drift exclusion. */
    val LABELS: List<String> = NATURAL_KEYS.keys.toList()

    /**
     * A uniqueness constraint per natural key, which is what makes the stores' MERGEs race-free,
     * plus the range indexes the lineage reads look records up by.
     *
     * The lookup indexes live here alongside the constraints so one registration covers everything
     * these stores need. A host that ensures the constraints while missing the indexes gets correct
     * answers off full scans, which is the kind of gap a hand-copied schema list produces.
     */
    override fun specs(): List<SchemaItemSpec> = NATURAL_KEYS.map { (label, key) ->
        UniquenessConstraintSpec(label = label, properties = key)
    } + listOf(
        RangeIndexSpec(PROJECTION_RECORD, "propositionId"),
        RangeIndexSpec(PROJECTION_RECORD, "lifecycle"),
        RangeIndexSpec(COLLECTOR_RECORD, "propositionId"),
    )

    /**
     * The `(alias:Label {property: $property, ...})` pattern a store MERGEs on.
     *
     * Label and property names are this object's own compile-time constants, and every value is a
     * bound parameter named after its property, so nothing caller-derived is assembled into Cypher.
     * The parameter names line up with the bind maps in `LineageRowMappers`.
     *
     * @param alias The variable the pattern binds the node to.
     * @param label One of the labels above.
     * @return The pattern text, ready to follow a `MERGE`.
     */
    fun mergePattern(alias: String, label: String): String {
        val key = requireNotNull(NATURAL_KEYS[label]) { "'$label' is no lineage label" }
        return "($alias:$label {" + key.joinToString(", ") { "$it: ${'$'}$it" } + "})"
    }
}

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

import org.jetbrains.annotations.ApiStatus

import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.UniquenessConstraintSpec

/**
 * The constraints and node labels the metamodel governance stores need, as plain data.
 *
 * A host, and the integration-test harness, registers this object as a [DiceStorageSchema] bean.
 * That one registration makes the stores' MERGEs race-free, because Drivine ensures [specs] from it,
 * and tells [DiceOwnedSchema] these labels are dice's own, which keeps governance from reporting its
 * own bookkeeping as domain drift.
 */
@ApiStatus.Experimental
object MetamodelSchema : DiceStorageSchema {

    /**
     * Every MERGE these stores perform needs its key to be unique, because a MERGE is race-free
     * only then. Without that, concurrent saves all miss the match, all create, and the history
     * fills with duplicates.
     *
     * The two `sequence` constraints back the ordering. They make two records of one schema sharing
     * a position impossible to store, so a lost counter update fails with a constraint violation the
     * caller can retry.
     */
    override fun specs(): List<SchemaItemSpec> = listOf(
        // Version stamps -- see DrivineMetamodelVersionStore.
        UniquenessConstraintSpec(label = "MetamodelVersion", properties = listOf("schemaName", "contentHash")),
        UniquenessConstraintSpec(label = "MetamodelSchemaCounter", property = "schemaName"),
        UniquenessConstraintSpec(label = "MetamodelVersion", properties = listOf("schemaName", "sequence")),

        // Drift reports -- see DrivineDriftReportStore. The natural key carries `contextKey`
        // because a Cypher MERGE can't key on a null, and a global report has no `contextId`.
        UniquenessConstraintSpec(
            label = "MetamodelDriftReport",
            properties = listOf("schemaName", "versionHash", "capturedAt", "contextKey"),
        ),
        UniquenessConstraintSpec(label = "MetamodelDriftReportCounter", property = "schemaName"),
        UniquenessConstraintSpec(label = "MetamodelDriftReport", properties = listOf("schemaName", "sequence")),
    )

    /** Every node label the metamodel stores write, for test cleanup and for drift exclusion. */
    val LABELS: List<String> = listOf(
        "MetamodelVersion",
        "MetamodelSchemaCounter",
        "MetamodelDriftReport",
        "MetamodelDriftReportCounter",
    )
}

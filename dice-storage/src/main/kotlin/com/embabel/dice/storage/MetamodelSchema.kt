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

import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.UniquenessConstraintSpec

/**
 * The constraints and node labels the metamodel governance stores need, as plain data.
 *
 * Two things depend on this list and they must not disagree. A host (and the integration-test
 * harness) declares [specs] so the stores' MERGEs are race-free; [LABELS] is what
 * [DrivineObservedSchemaSource] subtracts from an observation so governance never reports its own
 * bookkeeping as domain drift. Keeping both here means adding a governance node label is one edit,
 * not two edits in two modules that quietly drift apart.
 */
object MetamodelSchema {

    /**
     * Every MERGE these stores perform needs its key to be unique, because a MERGE is only
     * race-free when it is. Without that, concurrent saves all miss the match, all create, and the
     * history fills with duplicates.
     *
     * The two `sequence` constraints are the safety net under the ordering. They make two records
     * of one schema sharing a position impossible to store, so a lost counter update fails loudly
     * and retryably instead of quietly making "newest first" arbitrary.
     */
    fun specs(): List<SchemaItemSpec> = listOf(
        // Version stamps -- see DrivineMetamodelVersionStore.
        UniquenessConstraintSpec(label = "MetamodelVersion", properties = listOf("schemaName", "contentHash")),
        UniquenessConstraintSpec(label = "MetamodelSchemaCounter", property = "schemaName"),
        UniquenessConstraintSpec(label = "MetamodelVersion", properties = listOf("schemaName", "sequence")),

        // Drift reports -- see DrivineDriftReportStore. The natural key carries `contextKey` rather
        // than `contextId` because a Cypher MERGE can't key on a null, so a global report needs a
        // non-null stand-in to be as idempotent as a scoped one.
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

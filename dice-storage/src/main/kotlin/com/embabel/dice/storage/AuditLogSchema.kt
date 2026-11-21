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
import org.jetbrains.annotations.ApiStatus

/** Graph schema factory for hosts that opt in to durable audit history. */
@ApiStatus.Experimental
object AuditLogSchema {
    internal const val CONTEXT = "DiceAuditContext"
    internal const val RECORD = "DiceAuditRecord"
    internal const val IDENTITY_FENCE = "DiceAuditIdentityFence"

    /** Register this schema for ownership and publish its DDL in a [diceStorageCatalog] targeted at the repository's datasource. */
    @JvmStatic
    fun forRegistration(): DiceStorageSchema = object : DiceStorageSchema {
        override fun specs(): List<SchemaItemSpec> = listOf(
            UniquenessConstraintSpec(label = CONTEXT, property = "contextId"),
            UniquenessConstraintSpec(label = RECORD, properties = listOf("contextId", "sequence")),
            UniquenessConstraintSpec(label = IDENTITY_FENCE, property = "stripe"),
            RangeIndexSpec(RECORD, "contextId"),
            RangeIndexSpec(RECORD, listOf("contextId", "propositionId")),
        )
    }
}

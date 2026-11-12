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

import com.embabel.agent.core.ContextId
import java.time.Instant

/**
 * Builds drift values scoped to a context. Their constructors take a [ContextId], which Java cannot
 * pass, so the Java interop test gets its scoped values here.
 */
object DriftContextIdFixtures {

    private val version = MetamodelVersion("support", emptyList(), emptyMap(), emptyMap(), emptyList())

    @JvmStatic
    fun scopedReport(contextIdValue: String): DriftReport = DriftReport(
        schemaName = "support",
        versionHash = version.contentHash,
        driftedEntityTypes = emptySet(),
        driftedRelationshipTypes = emptySet(),
        capturedAt = Instant.EPOCH,
        contextId = ContextId(contextIdValue),
    )

    @JvmStatic
    fun scopedResult(contextIdValue: String): DriftCheckResult =
        DriftCheckResult(scopedReport(contextIdValue), version)
}

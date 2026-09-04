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
 * In-memory [SweptBaselineStore] for tests: upserts on `(schemaName, contentHash)`, keeps history
 * newest first, and tracks the reconciled baseline as its own pointer, the same way the real
 * contracts describe.
 */
internal class InMemoryMetamodelVersionStore : SweptBaselineStore {

    private val versions = mutableListOf<MetamodelVersion>()

    /** How many writes reached the store, idempotent ones included, so a test can see stamp order. */
    var saveCount: Int = 0
        private set

    // The reconciled-baseline pointer, tracked apart from `versions`' write order -- see
    // SweptBaselineStore.sweptVersion's doc for why this can't be answered off `latestVersion`.
    private val swept = mutableMapOf<String, MetamodelVersion>()

    override fun saveVersion(version: MetamodelVersion) {
        saveCount++
        val alreadyStored = versions.any {
            it.schemaName == version.schemaName && it.contentHash == version.contentHash
        }
        if (!alreadyStored) {
            versions.add(0, version)
        }
    }

    override fun latestVersion(schemaName: String): MetamodelVersion? =
        versions.firstOrNull { it.schemaName == schemaName }

    override fun versionHistory(schemaName: String): List<MetamodelVersion> =
        versions.filter { it.schemaName == schemaName }

    override fun markSwept(version: MetamodelVersion) {
        saveVersion(version)
        swept[version.schemaName] = version
    }

    override fun sweptVersion(schemaName: String): MetamodelVersion? = swept[schemaName]
}

/**
 * In-memory [DriftReportStore] for tests, and the reference reading of the bounded contract.
 *
 * Each read applies its scope before its limit, which is the part a real backend has to copy.
 * Filtering a limited page afterwards would let a schema whose recent history is mostly
 * context-scoped report zero global drift while plenty sat in the store, which is why the interface
 * has no default bodies for the three reads.
 */
internal class InMemoryDriftReportStore : DriftReportStore {

    private val reports = mutableListOf<DriftReport>()

    override fun saveDriftReport(report: DriftReport) {
        // Upsert on the natural key: the same schema, version, capture instant and context is the
        // same observation, so it replaces rather than duplicating.
        val existing = reports.indexOfFirst {
            it.schemaName == report.schemaName &&
                it.versionHash == report.versionHash &&
                it.capturedAt == report.capturedAt &&
                it.contextId == report.contextId
        }
        if (existing >= 0) {
            reports[existing] = report
        } else {
            reports.add(report)
        }
    }

    override fun driftReports(schemaName: String, limit: Int, since: Instant?): List<DriftReport> =
        page(limit, since) { it.schemaName == schemaName }

    override fun globalDriftReports(schemaName: String, limit: Int, since: Instant?): List<DriftReport> =
        page(limit, since) { it.schemaName == schemaName && it.contextId == null }

    override fun driftReportsInContext(
        schemaName: String,
        contextId: ContextId,
        limit: Int,
        since: Instant?,
    ): List<DriftReport> = page(limit, since) { it.schemaName == schemaName && it.contextId == contextId }

    private fun page(limit: Int, since: Instant?, scope: (DriftReport) -> Boolean): List<DriftReport> {
        require(limit > 0) { "limit must be positive, but was $limit" }
        return reports
            .filter(scope)
            .filter { since == null || !it.capturedAt.isBefore(since) }
            .sortedByDescending { it.capturedAt }
            .take(limit)
    }
}

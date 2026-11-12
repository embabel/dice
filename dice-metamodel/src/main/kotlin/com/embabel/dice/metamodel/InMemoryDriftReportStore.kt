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
import org.jetbrains.annotations.ApiStatus

/**
 * In-memory [DriftReportStore] for tests and demos. It keeps every report it is given, with no upper
 * bound, and nothing survives the JVM.
 *
 * A save with the same schema name, version hash, capture instant and context replaces the report
 * already stored under that key, so one observation is kept once. Each read applies its scope before
 * its limit, so a schema whose newest reports belong to other contexts still returns its own global or
 * in-context reports, newest first.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
class InMemoryDriftReportStore : DriftReportStore {

    private val reports = mutableListOf<DriftReport>()

    override fun saveDriftReport(report: DriftReport) {
        synchronized(reports) {
            // Upsert on the natural key. The same schema, version, capture instant and context is one
            // observation, so a second save overwrites the first.
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

    /**
     * Returns the newest stored reports that pass [scope] and were captured at or after [since].
     *
     * The scope and the time filter run before the limit, so the page holds up to [limit] matching
     * reports.
     *
     * @param limit The most reports to return. Must be positive.
     * @param since When non-null, only reports captured at or after this instant.
     * @param scope The test a stored report must pass to be returned.
     * @return The matching reports, newest first.
     */
    private fun page(limit: Int, since: Instant?, scope: (DriftReport) -> Boolean): List<DriftReport> =
        synchronized(reports) {
            require(limit > 0) { "limit must be positive, but was $limit" }
            reports
                .filter(scope)
                .filter { since == null || !it.capturedAt.isBefore(since) }
                .sortedByDescending { it.capturedAt }
                .take(limit)
        }
}

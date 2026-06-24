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
package com.embabel.dice.projection.lineage

/**
 * Append-only store of [CollectorRecord]s — the audit trail of "what the collector
 * marked or swept, why, and by which strategy".
 *
 * Implementations may be in-memory, graph-backed, or relational. The store is
 * append-only: there is no method to remove or mutate records, so the history is
 * non-destructive. The default query methods are expressed in terms of [all] and [runs]
 * purely as a fallback for trivial in-memory stores. A durable store MUST override each
 * finder with a scoped query so a single-key lookup never loads the whole table into
 * memory — the SPI default is not an acceptable data-access path for a database.
 */
interface CollectorRecordStore {

    /**
     * Record a collector outcome.
     *
     * @param record The collector record to store
     */
    fun record(record: CollectorRecord)

    /**
     * Record a finished run header.
     *
     * Persisting the run header ensures even a run with zero marks leaves a trace in the audit
     * trail, grouping all [CollectorRecord]s for the run under a shared [CollectorRun.runId].
     *
     * @param run The finished run header to store
     */
    fun recordRun(run: CollectorRun)

    /**
     * Find the run header for a given run id.
     *
     * @param runId ID of the collection run
     * @return the run whose [CollectorRun.runId] matches, or null if none was recorded
     */
    fun findRun(runId: String): CollectorRun? = runs().firstOrNull { it.runId == runId }

    /**
     * @return all run headers held by this store, in insertion order
     */
    fun runs(): List<CollectorRun>

    /**
     * Find all records for a given proposition.
     *
     * @param propositionId ID of the proposition
     * @return records whose [CollectorRecord.propositionId] matches
     */
    fun findByProposition(propositionId: String): List<CollectorRecord> =
        all().filter { it.propositionId == propositionId }

    /**
     * Find all records produced by a given run.
     *
     * @param runId ID of the collection run
     * @return records whose [CollectorRecord.runId] matches
     */
    fun findByRun(runId: String): List<CollectorRecord> =
        all().filter { it.runId == runId }

    /**
     * @return all records held by this store
     */
    fun all(): List<CollectorRecord>
}

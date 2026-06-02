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
 * Store of [ProjectionRecord]s: an inverse index of which propositions projected
 * to which targets.
 *
 * The query methods are defined in terms of [all], so an implementation need only
 * provide [record] and [all]. Backing stores may be in-memory, graph-backed, or
 * relational.
 */
interface ProjectionRecordStore {

    /**
     * Record a projection outcome.
     *
     * @param record The projection record to store
     */
    fun record(record: ProjectionRecord)

    /**
     * Find all records for a given proposition.
     *
     * @param propositionId ID of the proposition
     * @return records whose [ProjectionRecord.propositionId] matches
     */
    fun findByProposition(propositionId: String): List<ProjectionRecord> =
        all().filter { it.propositionId == propositionId }

    /**
     * Find all records for a given target.
     *
     * @param target The projection target (e.g. "graph")
     * @return records whose [ProjectionRecord.target] matches
     */
    fun findByTarget(target: String): List<ProjectionRecord> =
        all().filter { it.target == target }

    /**
     * Find all records produced by a given run.
     *
     * @param runId ID of the projection run
     * @return records whose [ProjectionRecord.runId] matches
     */
    fun findByRun(runId: String): List<ProjectionRecord> =
        all().filter { it.runId == runId }

    /**
     * Find all records currently in the [ProjectionLifecycle.STALE] state.
     *
     * @return records whose lifecycle is STALE
     */
    fun findStale(): List<ProjectionRecord> =
        all().filter { it.lifecycle == ProjectionLifecycle.STALE }

    /**
     * @return all records held by this store
     */
    fun all(): List<ProjectionRecord>
}

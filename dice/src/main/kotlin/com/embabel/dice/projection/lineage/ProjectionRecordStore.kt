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
 * Store of [ProjectionRecord]s — the inverse index of "what projected where".
 *
 * Implementations may be in-memory, graph-backed, or relational. The default
 * query methods are expressed in terms of [all] so that simple implementations
 * only need to supply [record] and [all].
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
     * @param target The projection target (e.g. "neo4j")
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
     * Trace from a produced/adopted artifact back to the projection records that
     * reference it. Starting from a target artifact reference (e.g. a graph node ID),
     * this returns every record whose [ProjectionRecord.targetRef] matches, across all
     * lifecycles — so a reviewer can see whether the artifact was created (PROJECTED),
     * adopted/aligned (ADOPTED), skipped, failed, or has gone stale.
     *
     * @param targetRef Reference to the produced/adopted artifact in the target
     * @return records whose [ProjectionRecord.targetRef] matches
     */
    fun findByTargetRef(targetRef: String): List<ProjectionRecord> =
        all().filter { it.targetRef == targetRef }

    /**
     * Find all records currently in the [ProjectionLifecycle.STALE] state.
     *
     * @return records whose lifecycle is STALE
     */
    fun findStale(): List<ProjectionRecord> =
        all().filter { it.lifecycle == ProjectionLifecycle.STALE }

    /**
     * Marks every record for the given proposition as [ProjectionLifecycle.STALE].
     *
     * Defaults to a no-op returning 0. Implementations that hold mutable state
     * should override this to replace each matching record with a stale copy.
     *
     * @param propositionId ID of the proposition whose records should go stale
     * @return the number of records transitioned to STALE
     */
    fun markStaleByProposition(propositionId: String): Int = 0

    /**
     * @return all records held by this store
     */
    fun all(): List<ProjectionRecord>
}

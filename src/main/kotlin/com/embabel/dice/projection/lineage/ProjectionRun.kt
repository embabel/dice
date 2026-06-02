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

import java.time.Instant

/**
 * A single batch of projection work against a target.
 *
 * A run groups its [ProjectionRecord]s under a shared [runId], giving projection
 * and reconciliation jobs a common run history. This is a plain record; aggregate
 * counts are out of scope.
 *
 * @property runId Unique identifier for this run (matches [ProjectionRecord.runId])
 * @property startedAt When the run started
 * @property finishedAt When the run finished, or null if still running
 * @property target The projection target this run wrote to (e.g. "graph")
 */
data class ProjectionRun @JvmOverloads constructor(
    val runId: String,
    val startedAt: Instant,
    val finishedAt: Instant? = null,
    val target: String,
) {

    init {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(target.isNotBlank()) { "target must not be blank" }
        require(finishedAt == null || !finishedAt.isBefore(startedAt)) {
            "finishedAt must be null or >= startedAt"
        }
    }

    /**
     * Create a copy marked finished at the given instant.
     *
     * @param at When the run finished (defaults to now)
     * @return a copy with [finishedAt] set
     */
    fun finished(at: Instant = Instant.now()): ProjectionRun = copy(finishedAt = at)
}

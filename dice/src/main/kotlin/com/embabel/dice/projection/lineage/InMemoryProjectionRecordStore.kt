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

import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe in-memory implementation of [ProjectionRecordStore].
 *
 * Intended as a default/stub for demos and tests. Records are append-only and
 * returned in insertion order. Backed by a [CopyOnWriteArrayList] so reads never
 * block writes.
 */
class InMemoryProjectionRecordStore : ProjectionRecordStore {

    private val logger = LoggerFactory.getLogger(InMemoryProjectionRecordStore::class.java)

    private val records = CopyOnWriteArrayList<ProjectionRecord>()

    override fun record(record: ProjectionRecord) {
        records.add(record)
        logger.debug(
            "Recorded projection lineage: propositionId={}, target={}, lifecycle={}, runId={}",
            record.propositionId, record.target, record.lifecycle, record.runId,
        )
    }

    /**
     * Marks every record for [propositionId] that isn't already STALE as [ProjectionLifecycle.STALE],
     * preserving insertion order and leaving all other records untouched.
     *
     * @param propositionId ID of the proposition whose records should go stale
     * @return the number of records transitioned to STALE
     */
    override fun markStaleByProposition(propositionId: String): Int = synchronized(records) {
        // The check (is this record non-STALE?) and the set must be atomic together: two concurrent
        // calls for the same proposition could otherwise both observe the same non-STALE record and
        // both count it, double-counting the transition (and racing the index write).
        var count = 0
        for (index in records.indices) {
            val current = records[index]
            if (current.propositionId == propositionId &&
                current.lifecycle != ProjectionLifecycle.STALE
            ) {
                records[index] = current.copy(lifecycle = ProjectionLifecycle.STALE)
                count++
            }
        }
        count
    }

    override fun all(): List<ProjectionRecord> = records.toList()
}

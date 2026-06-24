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

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thread-safe in-memory implementation of [CollectorRecordStore].
 *
 * Intended as a default/stub for demos and tests. Records are append-only and
 * returned in insertion order. Backed by a [CopyOnWriteArrayList] so reads never
 * block writes.
 */
class InMemoryCollectorRecordStore : CollectorRecordStore {

    private val records = CopyOnWriteArrayList<CollectorRecord>()
    private val runHeaders = CopyOnWriteArrayList<CollectorRun>()

    override fun record(record: CollectorRecord) {
        records.add(record)
    }

    override fun recordRun(run: CollectorRun) {
        runHeaders.add(run)
    }

    override fun findByProposition(propositionId: String): List<CollectorRecord> =
        records.filter { it.propositionId == propositionId }

    override fun findByRun(runId: String): List<CollectorRecord> =
        records.filter { it.runId == runId }

    override fun findRun(runId: String): CollectorRun? =
        runHeaders.firstOrNull { it.runId == runId }

    override fun all(): List<CollectorRecord> = records.toList()

    override fun runs(): List<CollectorRun> = runHeaders.toList()
}

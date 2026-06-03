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
 * Thread-safe in-memory [ProjectionRecordStore].
 *
 * Records are append-only and returned in insertion order. Backed by a
 * [CopyOnWriteArrayList], so reads never block writes. Intended as a default
 * implementation for tests and lightweight usage; production deployments should
 * supply a persistent store.
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

    override fun all(): List<ProjectionRecord> = records.toList()
}

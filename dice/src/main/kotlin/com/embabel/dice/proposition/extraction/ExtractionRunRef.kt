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
package com.embabel.dice.proposition.extraction

import org.jetbrains.annotations.ApiStatus

/**
 * Names one extraction run.
 *
 * This is identity and nothing else. It holds no timing, no status, no counts, no lineage —
 * just the id, so a caller can say "this analysis belongs to that run" without DICE having
 * anywhere to store a run yet. Durable extraction runs arrive with DICE #67 and will be keyed
 * by ([com.embabel.agent.core.ContextId], `ExtractionRunRef`); shipping the reference first
 * means the entry points and the run model meet at an opaque string rather than at a type one
 * of them has to import from the other's release.
 *
 * The id is opaque. DICE compares it and carries it and parses nothing out of it. It also never
 * mints one: a run is something the host (or, later, DICE's own run coordinator) starts. Nothing
 * here checks that the run exists, because there is nowhere yet to check against — carrying a
 * reference is always allowed. What a store does with a reference to a run it has never seen is
 * DICE #67's to decide, and this type makes no promise about it either way.
 *
 * Run identity is deliberately not part of source-provenance equality. Two runs over the same
 * material still produce one piece of source evidence; what differs is which runs are
 * attributed to it.
 *
 * A run reference is not an authorization token and must not carry a secret, a direct
 * identifier, or anything a reader could dereference into personal data. Hosts mint it.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property runId Host-minted opaque identifier for the run
 */
@ApiStatus.Experimental
data class ExtractionRunRef(
    val runId: String,
) {

    init {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(runId.length <= MAX_RUN_ID_LENGTH) {
            "runId must be at most $MAX_RUN_ID_LENGTH characters, was ${runId.length}"
        }
    }

    companion object {

        /**
         * Longest run id DICE accepts. #67 keys stored runs on this string and indexes it, so
         * an unbounded id would become an unbounded key. A uuid, a ULID, or a host's own
         * correlation id all fit with room to spare.
         */
        const val MAX_RUN_ID_LENGTH: Int = 256
    }
}

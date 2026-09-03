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
package com.embabel.dice.storage

import com.embabel.agent.core.ContextId
import org.drivine.schema.RangeIndexSpec
import org.drivine.schema.SchemaItemSpec
import org.drivine.schema.UniquenessConstraintSpec

/**
 * The constraints, indexes and node labels [DrivineExtractionRunStore] needs, as plain data.
 *
 * A host declares [specs] in a `SchemaCatalog` bean and Drivine's schema manager ensures them on
 * startup; the module's `TestApplication` shows the shape. [LABELS] is every label the store writes,
 * which is what a test teardown or an operator's audit sweep needs.
 *
 * The three constraints are not optional tuning. Every write here is a `MERGE` or a `CREATE` on a
 * natural key, and neither is race-free without a uniqueness constraint on that key: concurrent
 * writers all miss the match, all take the create branch, and the store fills with copies of one
 * run. The terminal-write constraint does more than that — see [DrivineExtractionRunStore] for why
 * it is what makes compare-and-set hold across processes rather than only across threads.
 */
object ExtractionRunSchema {

    /** The `(:ExtractionRun)` header: one node per run, per tenant. */
    const val RUN_LABEL: String = "ExtractionRun"

    /** The `(:ExtractionRunInvocation)` child: one node per attempt at one planned model call. */
    const val INVOCATION_LABEL: String = "ExtractionRunInvocation"

    /** The `(:ExtractionRunTerminalWrite)` node: at most one per run, and the thing that says so. */
    const val TERMINAL_WRITE_LABEL: String = "ExtractionRunTerminalWrite"

    /** Header to invocation child. */
    const val RECORDED_REL: String = "RECORDED"

    /** Header to the terminal write that ended it. */
    const val ENDED_BY_REL: String = "ENDED_BY"

    /**
     * The longest tenant id this store will write.
     *
     * `ContextId` accepts any non-blank string, and the tenant is the leading property of every key
     * and index here. A tenant id of unbounded length is an index entry of unbounded length, and
     * Neo4j fails that write with an index-key-size error — mid-extraction, with a message about
     * bytes rather than about the tenant. The cap turns that into an argument rejection at the
     * store's boundary, named, before anything is written.
     *
     * 1024 characters matches [com.embabel.dice.proposition.extraction.ExtractionRunLimits.MAX_SOURCE_KEY_LENGTH],
     * the most generous bound the run model already puts on a key-like string. It is far above any
     * real tenant id and comfortably below the index key limit even once the rest of a composite key
     * is added.
     *
     * The cap is applied on writes only. A read for a tenant longer than this matches nothing,
     * because nothing that long was ever stored — which is the fail-closed answer, and the only one
     * a read could give.
     */
    const val MAX_CONTEXT_ID_LENGTH: Int = 1024

    /**
     * Every constraint and index the store depends on.
     *
     * **Constraints.**
     * - `ExtractionRun(contextId, runId)` — the tenant-qualified natural key. Two tenants minting
     *   the same run id are two runs, which is the whole reason the tenant is in the key.
     * - `ExtractionRunInvocation(contextId, runId, invocationIndex, attempt)` — the deterministic
     *   child key. A retried attempt lands on its own row, and a replayed write of the same attempt
     *   upserts in place.
     * - `ExtractionRunTerminalWrite(contextId, runId)` — at most one terminal write per run, ever.
     *   This is the compare-and-set. Two writers that both read a run as `RUNNING` both try to
     *   create this node, and the database lets exactly one of them commit.
     *
     * **Indexes.** Each one is what a specific read seeks on, and none of them carries `status` or
     * the terminal fingerprint — deliberately, because the compare-and-set reads both after taking
     * its lock and an indexed property could be served from an index entry read before it.
     * - `(contextId)` — `runsInContext`. A composite index cannot stand in: Neo4j will not use one
     *   for a predicate on only its leading property, so without this the tenant predicate is a
     *   label scan. Same reasoning as the `Proposition(contextId)` index.
     * - `(contextId, rootRunId)` — `runsOfRoot`, the read the denormalized root exists for.
     * - `(contextId, parentRunId)` — `childrenOf`, one hop down the parent axis.
     * - `(contextId, startedAtEpochSecond)` — the paging sort key. Pages are newest-first by start
     *   time within one tenant.
     * - `ExtractionRunInvocation(contextId, runId)` — reading one run's attempts. The four-property
     *   constraint index is keyed on the whole identity and is not a substitute for a lookup on the
     *   first two.
     */
    fun specs(): List<SchemaItemSpec> = listOf(
        UniquenessConstraintSpec(label = RUN_LABEL, properties = listOf("contextId", "runId")),
        UniquenessConstraintSpec(
            label = INVOCATION_LABEL,
            properties = listOf("contextId", "runId", "invocationIndex", "attempt"),
        ),
        UniquenessConstraintSpec(label = TERMINAL_WRITE_LABEL, properties = listOf("contextId", "runId")),

        RangeIndexSpec(label = RUN_LABEL, property = "contextId"),
        RangeIndexSpec(label = RUN_LABEL, properties = listOf("contextId", "rootRunId")),
        RangeIndexSpec(label = RUN_LABEL, properties = listOf("contextId", "parentRunId")),
        RangeIndexSpec(label = RUN_LABEL, properties = listOf("contextId", "startedAtEpochSecond")),
        RangeIndexSpec(label = INVOCATION_LABEL, properties = listOf("contextId", "runId")),
    )

    /** Every node label the run store writes, for test cleanup and for an operator's audit sweep. */
    val LABELS: List<String> = listOf(RUN_LABEL, INVOCATION_LABEL, TERMINAL_WRITE_LABEL)

    /**
     * Rejects a tenant this store cannot key on, before anything is written.
     *
     * @throws IllegalArgumentException if the tenant id is longer than [MAX_CONTEXT_ID_LENGTH]. The
     *   message reports the length, not the value: a tenant id is a host identifier and does not
     *   belong in a log line.
     */
    fun requireStorableTenant(contextId: ContextId) {
        require(contextId.value.length <= MAX_CONTEXT_ID_LENGTH) {
            "contextId must be at most $MAX_CONTEXT_ID_LENGTH characters to be stored as part of a " +
                "run key, was ${contextId.value.length}"
        }
    }
}

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
package com.embabel.dice.projection.graph

import com.embabel.agent.core.DataDictionary
import com.embabel.dice.projection.lineage.ReconciliationDecision
import com.embabel.dice.projection.lineage.Reconciler
import com.embabel.dice.projection.lineage.AlwaysCreateReconciler
import com.embabel.dice.projection.lineage.ProjectionLifecycle
import com.embabel.dice.projection.lineage.ProjectionRecord
import com.embabel.dice.projection.lineage.ProjectionRecordStore
import com.embabel.dice.proposition.ProjectionFailed
import com.embabel.dice.proposition.ProjectionResults
import com.embabel.dice.proposition.ProjectionSkipped
import com.embabel.dice.proposition.ProjectionSuccess
import com.embabel.dice.proposition.Proposition
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Bundles a [GraphProjector], [GraphRelationshipPersister], and [DataDictionary] together
 * so you can project propositions to graph relationships and persist them in one call.
 *
 * @param graphProjector converts propositions to relationships
 * @param persister writes those relationships to the graph
 * @param schema data dictionary used for relationship validation
 * @param recordStore optional lineage store; when supplied, one [ProjectionRecord] is
 *   written per result (PROJECTED / ADOPTED / SKIPPED / FAILED). Nothing is recorded
 *   when null.
 * @param reconciler decides per successful projection whether to create a new artifact
 *   (PROJECTED) or align with an existing one (ADOPTED). Defaults to [AlwaysCreateReconciler].
 *   Only consulted when a [recordStore] is present. Node-level de-duplication on the
 *   graph side is a planned follow-up.
 */
class GraphProjectionService(
    private val graphProjector: GraphProjector,
    private val persister: GraphRelationshipPersister,
    private val schema: DataDictionary,
    private val recordStore: ProjectionRecordStore? = null,
    private val reconciler: Reconciler = AlwaysCreateReconciler,
) {

    private val logger = LoggerFactory.getLogger(GraphProjectionService::class.java)

    companion object {
        @JvmStatic
        @JvmOverloads
        fun create(
            graphProjector: GraphProjector,
            persister: GraphRelationshipPersister,
            schema: DataDictionary,
            recordStore: ProjectionRecordStore? = null,
            reconciler: Reconciler = AlwaysCreateReconciler,
        ): GraphProjectionService =
            GraphProjectionService(graphProjector, persister, schema, recordStore, reconciler)
    }

    /**
     * Projects the given propositions to graph relationships and persists them.
     * When a [recordStore] is configured, one [ProjectionRecord] is emitted per result,
     * all sharing a single run ID for the batch.
     *
     * @param propositions propositions to project and persist
     * @return projection results paired with persistence results
     */
    fun projectAndPersist(
        propositions: List<Proposition>,
    ): Pair<ProjectionResults<ProjectedRelationship>, RelationshipPersistenceResult> {
        val pair = persister.projectAndPersist(propositions, graphProjector, schema)
        val store = recordStore
        if (store != null) {
            val runId = UUID.randomUUID().toString()
            pair.first.results.forEach { result ->
                val (lifecycle, targetRef, reason) = when (result) {
                    is ProjectionSuccess -> when (
                        val decision = reconciler.reconcile(result.proposition, "neo4j")
                    ) {
                        is ReconciliationDecision.CreateNew -> Triple(
                            ProjectionLifecycle.PROJECTED,
                            (result.projected as? ProjectedRelationship)?.sourceId,
                            null,
                        )

                        is ReconciliationDecision.Adopt -> Triple(
                            ProjectionLifecycle.ADOPTED,
                            decision.targetRef,
                            "adopted existing artifact",
                        )

                        is ReconciliationDecision.Align -> Triple(
                            ProjectionLifecycle.ADOPTED,
                            decision.targetRef,
                            "aligned with existing artifact (node merge deferred)",
                        )
                    }

                    is ProjectionSkipped -> Triple(
                        ProjectionLifecycle.SKIPPED,
                        null,
                        result.structuredReason?.describe() ?: result.reason,
                    )

                    is ProjectionFailed -> Triple(
                        ProjectionLifecycle.FAILED,
                        null,
                        result.structuredReason?.describe() ?: result.reason,
                    )
                }
                // The graph side is already persisted above; a failure writing one lineage record
                // must not abort the loop and drop the records for every remaining result. Isolate
                // each write so a flaky record store loses at most the one row, not the whole trail.
                runCatching {
                    store.record(
                        ProjectionRecord.of(
                            propositionId = result.proposition.id,
                            target = "neo4j",
                            lifecycle = lifecycle,
                            runId = runId,
                            targetRef = targetRef,
                            reason = reason,
                        ),
                    )
                }.onFailure { logger.warn("Failed to record projection lineage for {}: {}", result.proposition.id, it.message) }
            }
        }
        return pair
    }
}

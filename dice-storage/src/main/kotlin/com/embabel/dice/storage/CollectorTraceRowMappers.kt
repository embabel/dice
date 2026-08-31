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

import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.spi.CollectorCandidateEdge
import com.embabel.dice.spi.CollectorComponent
import com.embabel.dice.spi.CollectorDecision
import com.embabel.dice.spi.CollectorSignalScore
import com.embabel.dice.spi.RetiredProposition

/**
 * Translate the collector trace types to and from the property maps the durable graph reads and
 * writes. Kept separate from [DrivineCollectorTraceStore] (and free of any Drivine types) so the
 * flattening — natural-key ids, list-of-primitive properties, enums by name — can be unit-tested
 * without a database.
 */

/** One `(:CollectorCandidateEdge)` row plus its flattened `(:CollectorSignalScore)` child rows. */
object CollectorCandidateEdgeRowMapper {

    /** The edge's own natural-key id: unique per run + anchor + member pair. */
    fun edgeId(runId: String, edge: CollectorCandidateEdge): String = "$runId|${edge.anchorId}|${edge.memberId}"

    /** Bind values for the edge node itself (excludes `runId`/`contextId`, supplied by the caller). */
    fun edgeBindMap(runId: String, edge: CollectorCandidateEdge): Map<String, Any?> = mapOf(
        "id" to edgeId(runId, edge),
        "runId" to runId,
        "anchorId" to edge.anchorId,
        "memberId" to edge.memberId,
        "aggregateScore" to edge.aggregateScore,
        "vetoed" to edge.vetoed,
    )

    /** Bind values for one signal child row, scored against its edge id. */
    fun signalBindMap(runId: String, edge: CollectorCandidateEdge, signal: CollectorSignalScore): Map<String, Any?> = mapOf(
        "id" to "${edgeId(runId, edge)}|${signal.signal}",
        "edgeId" to edgeId(runId, edge),
        "runId" to runId,
        "signal" to signal.signal,
        "score" to signal.score,
        "weight" to signal.weight,
        "veto" to signal.veto,
        "explanation" to signal.explanation,
        "evidenceRef" to signal.evidenceRef,
    )

    /** Rebuild one [CollectorCandidateEdge] from its node row plus its already-grouped signal rows. */
    fun fromRow(row: Map<*, *>, signals: List<CollectorSignalScore>): CollectorCandidateEdge = CollectorCandidateEdge(
        anchorId = row.str("anchorId"),
        memberId = row.str("memberId"),
        aggregateScore = row.double("aggregateScore"),
        vetoed = row.bool("vetoed"),
        signals = signals,
    )

    fun signalFromRow(row: Map<*, *>): CollectorSignalScore = CollectorSignalScore(
        signal = row.str("signal"),
        score = row.double("score"),
        weight = row["weight"]?.toString()?.toDoubleOrNull() ?: 1.0,
        veto = row.bool("veto"),
        explanation = row.strOrNull2("explanation"),
        evidenceRef = row.strOrNull2("evidenceRef"),
    )
}

/** One `(:CollectorComponent)` row. */
object CollectorComponentRowMapper {

    fun componentId(runId: String, component: CollectorComponent): String = "$runId|${component.componentId}"

    fun bindMap(runId: String, component: CollectorComponent): Map<String, Any?> = mapOf(
        "id" to componentId(runId, component),
        "runId" to runId,
        "componentId" to component.componentId,
        "memberIds" to component.memberIds,
    )

    fun fromRow(row: Map<*, *>): CollectorComponent = CollectorComponent(
        componentId = row.str("componentId"),
        memberIds = row.stringList("memberIds"),
    )
}

/** One `(:CollectorDecision)` row plus its flattened `(:CollectorRetired)` child rows. */
object CollectorDecisionRowMapper {

    fun decisionId(runId: String, decision: CollectorDecision): String = "$runId|${decision.componentId}"

    fun bindMap(runId: String, decision: CollectorDecision): Map<String, Any?> = mapOf(
        "id" to decisionId(runId, decision),
        "runId" to runId,
        "componentId" to decision.componentId,
        "survivorId" to decision.survivorId,
        "action" to decision.action,
    )

    fun retiredBindMap(runId: String, decision: CollectorDecision, retired: RetiredProposition): Map<String, Any?> = mapOf(
        "id" to "${decisionId(runId, decision)}|${retired.propositionId}",
        "decisionId" to decisionId(runId, decision),
        "runId" to runId,
        "propositionId" to retired.propositionId,
        "priorStatus" to retired.priorStatus.name,
        "foldedGrounding" to retired.foldedGrounding,
        "foldedProvenanceRefs" to retired.foldedProvenanceRefs,
        "foldedSourceIds" to retired.foldedSourceIds,
        "foldedProvenanceEvidenceKeys" to retired.foldedProvenanceEvidenceKeys,
    )

    fun fromRow(row: Map<*, *>, retired: List<RetiredProposition>): CollectorDecision = CollectorDecision(
        runId = row.str("runId"),
        componentId = row.str("componentId"),
        survivorId = row.str("survivorId"),
        action = row.str("action"),
        retired = retired,
    )

    fun retiredFromRow(row: Map<*, *>): RetiredProposition = RetiredProposition(
        propositionId = row.str("propositionId"),
        priorStatus = PropositionStatus.valueOf(row.str("priorStatus")),
        foldedGrounding = row.stringList("foldedGrounding"),
        foldedProvenanceRefs = row.stringList("foldedProvenanceRefs"),
        foldedSourceIds = row.stringList("foldedSourceIds"),
        // A row written before evidence keys existed has no such property, and reads back empty.
        foldedProvenanceEvidenceKeys = row.stringList("foldedProvenanceEvidenceKeys"),
    )
}

private fun Map<*, *>.str(key: String): String = this[key]?.toString().orEmpty()

private fun Map<*, *>.strOrNull2(key: String): String? = this[key]?.toString()

private fun Map<*, *>.double(key: String): Double = this[key]?.toString()?.toDoubleOrNull() ?: 0.0

private fun Map<*, *>.bool(key: String): Boolean = this[key]?.toString()?.toBooleanStrictOrNull() ?: false

@Suppress("UNCHECKED_CAST")
private fun Map<*, *>.stringList(key: String): List<String> =
    (this[key] as? List<*>)?.map { it.toString() } ?: emptyList()

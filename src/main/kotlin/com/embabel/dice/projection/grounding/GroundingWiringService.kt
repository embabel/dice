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
package com.embabel.dice.projection.grounding

import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.RelationshipData
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.dice.proposition.Proposition
import org.slf4j.LoggerFactory

/**
 * Materialises `(:Proposition)-[:GROUNDED_IN]->(:<entity>)` edges by
 * walking each proposition's existing `grounding: List<String>` and,
 * for every id that resolves via [NamedEntityDataRepository.findById],
 * writing the edge to the resolved entity.
 *
 * **What this gives consumers.** Edge-anchored provenance — given any
 * KG edge or proposition you can trace back to the actual source
 * entity (EmailSignal, MeetingSignal, Document, WebPage, File, …)
 * the system was reading when it inferred the fact. One-hop graph
 * query instead of an opaque string id.
 *
 * **Backward-compatible by design.**
 *  - `Proposition.grounding` is unchanged — still a `List<String>`.
 *  - Ids that don't resolve to a stored entity (legacy chunk hashes,
 *    free-text fingerprints, message-level ids that don't have their
 *    own entity row) are silently skipped — no edge written, no
 *    error.
 *  - Idempotent via [NamedEntityDataRepository.mergeRelationship]:
 *    re-running over the same propositions does not duplicate edges.
 *  - Any entity type the dictionary knows about is a valid target;
 *    the wiring doesn't care which.
 *
 * **Convention for `grounding` ids that should anchor edges.** Pass
 * the entity's stable id verbatim — `EmailSignal.id`,
 * `MeetingSignal.id`, `Document.id`, etc. — into
 * `Proposition.grounding`. The extractor populating grounding decides
 * the contract; this service just follows what's there.
 *
 * Wired into [com.embabel.dice.proposition.extraction.IncrementalPropositionExtraction]
 * via an optional ctor parameter; consumers that don't supply one
 * see no behaviour change.
 */
class GroundingWiringService(
    private val entityRepository: NamedEntityDataRepository,
    private val resolver: GroundingResolver = DefaultGroundingResolver(entityRepository),
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Walk [propositions] and write `GROUNDED_IN` edges for every
     * grounding id that resolves to a stored entity. Returns a small
     * report; most callers just want to log the count.
     */
    fun wire(propositions: List<Proposition>): GroundingReport {
        if (propositions.isEmpty()) return GroundingReport.EMPTY
        var attempted = 0
        var written = 0
        var skipped = 0
        var failed = 0
        for (p in propositions) {
            if (p.grounding.isEmpty()) continue
            for (groundingId in p.grounding.distinct()) {
                attempted++
                // Shared resolution: exact id, else namespace-suffix match.
                // A grounding id may legitimately back several source nodes.
                val targets = resolver.resolveAll(groundingId)
                if (targets.isEmpty()) {
                    // Legacy chunk hashes, free-text source fingerprints,
                    // ids the extractor invented locally — fine to skip.
                    skipped++
                    continue
                }
                for (target in targets) {
                    try {
                        entityRepository.mergeRelationship(
                            // Endpoint id is the RESOLVED node's real id — never the
                            // grounding string — so a stripped id can't MERGE-create a
                            // phantom bare {id} node.
                            a = RetrievableIdentifier(id = p.id, type = PROPOSITION_TYPE),
                            b = RetrievableIdentifier(id = target.id, type = target.labels().firstOrNull() ?: PROPOSITION_TYPE),
                            relationship = RelationshipData(
                                name = GROUNDED_IN_REL,
                                properties = emptyMap(),
                            ),
                        )
                        written++
                    } catch (e: Exception) {
                        failed++
                        logger.warn(
                            "[grounding] GROUNDED_IN write failed for proposition={} → {}: {}",
                            p.id, target.id, e.message,
                        )
                    }
                }
            }
        }
        if (attempted > 0) {
            logger.info(
                "[grounding] wired {} GROUNDED_IN edge(s) over {} proposition(s) — {} attempted, {} skipped (no entity match), {} failed",
                written, propositions.size, attempted, skipped, failed,
            )
        }
        return GroundingReport(
            propositions = propositions.size,
            attempted = attempted,
            written = written,
            skipped = skipped,
            failed = failed,
        )
    }

    data class GroundingReport(
        val propositions: Int,
        val attempted: Int,
        val written: Int,
        val skipped: Int,
        val failed: Int,
    ) {
        companion object {
            val EMPTY: GroundingReport = GroundingReport(0, 0, 0, 0, 0)
        }
    }

    companion object {
        const val GROUNDED_IN_REL: String = "GROUNDED_IN"

        // Proposition rows carry the `Proposition` label by convention
        // in DICE's PropositionRepository implementations. The type
        // string is only used by RetrievableIdentifier for the
        // mergeRelationship cypher template; it doesn't have to be
        // semantically meaningful.
        const val PROPOSITION_TYPE: String = "Proposition"
    }
}

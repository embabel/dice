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
package com.embabel.dice.eval

import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.common.DiceEvent
import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.common.PropositionStatusChanged
import com.embabel.dice.ingestion.support.TextIngestionHandler
import com.embabel.dice.pipeline.PropositionPipeline
import com.embabel.dice.projection.graph.GraphProjectionService
import com.embabel.dice.projection.graph.RelationBasedGraphProjector
import com.embabel.dice.projection.lineage.InMemoryProjectionRecordStore
import com.embabel.dice.projection.lineage.ProjectionLifecycle
import com.embabel.dice.projection.memory.DecayCollectorStrategy
import com.embabel.dice.projection.memory.DefaultCollectorRunner
import com.embabel.dice.projection.memory.StatusTransitionSweepPolicy
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.query.graph.GraphQuery
import com.embabel.dice.report.StructuredReportProjector
import com.embabel.dice.report.TwoHopSemanticLinkDiscoverer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reusable canonical-flow contract test (a TCK base).
 *
 * Drives the real shipped components — ingestion front door, relation-based graph projection, the
 * portable graph-query facade, two-hop link discovery, the mark-and-sweep collector, lifecycle
 * events, and the structured report projector — end-to-end against deterministic offline fixtures,
 * with no LLM, embedding model, network, or container.
 *
 * Subclasses supply only a store through [newStore]; everything else is wired here. A future store
 * adapter can subclass this base and override [newStore] to run the identical assertions against
 * its own implementation. The store is typed as [PropositionRepository] because the collector and
 * the graph-query facade require that contract.
 *
 * Override [newEmbeddingService] only if an adapter needs a different deterministic embedder; the
 * default offline embedder serves the in-memory subclass.
 */
abstract class AbstractCanonicalFlowTest {

    /** The store under test. Implementations return a fresh, empty store per call. */
    protected abstract fun newStore(): PropositionRepository

    /** The deterministic, offline embedder the store may use for its vector path. */
    protected open fun newEmbeddingService(): FixedVectorEmbeddingService = FixedVectorEmbeddingService()

    /** Records every lifecycle event the collector emits, in order. */
    private class RecordingListener : DiceEventListener {
        val events = mutableListOf<DiceEvent>()
        override fun onEvent(event: DiceEvent) {
            events.add(event)
        }
    }

    @Test
    fun `canonical knowledge flow runs end to end on deterministic fixtures`() {
        val store = newStore()
        val fixtures = CanonicalFlowFixtures

        // Stage 1 — ingest: the no-LLM extractor drives a real reviser-free pipeline; the
        // resulting propositions are persisted into the store under test.
        val extractor = FixedPropositionExtractor()
        val handler = TextIngestionHandler(PropositionPipeline.withExtractor(extractor))
        val ingestion = handler.ingest(fixtures.ingestionBatch(), fixtures.context)
        val ingested = ingestion.propositions
        assertEquals(1, extractor.extractCalls, "extraction runs once for the single artifact")
        assertTrue(ingested.isNotEmpty(), "ingest yields propositions")
        store.saveAll(ingested)

        // Stage 2 — project: relation-based (AI-free) projection into the in-test persister, with a
        // lineage record store capturing one PROJECTED record per successful edge.
        val persister = InMemoryGraphRelationshipPersister()
        val recordStore = InMemoryProjectionRecordStore()
        val projectionService = GraphProjectionService(
            graphProjector = RelationBasedGraphProjector.from(fixtures.relations),
            persister = persister,
            schema = fixtures.schema,
            recordStore = recordStore,
        )
        val (projectionResults, persistence) = projectionService.projectAndPersist(ingested)
        // Exactly the two 0.95-confidence edges clear the 0.85 projection threshold; the
        // 0.2-confidence decay candidate is skipped. Pinning the exact counts means a broken
        // threshold that began persisting the low-confidence candidate would fail this gate.
        assertEquals(2, persistence.persistedCount, "exactly the two high-confidence edges persist")
        assertEquals(2, persister.persisted.size, "exactly two projected relationships captured")
        assertEquals(2, projectionResults.successCount, "exactly two propositions project successfully")
        assertEquals(1, projectionResults.skipCount, "exactly the decay candidate is skipped")
        val projectedRecords = recordStore.all().filter { it.lifecycle == ProjectionLifecycle.PROJECTED }
        assertEquals(
            setOf("prop-alice-bob", "prop-bob-carol"),
            projectedRecords.map { it.propositionId }.toSet(),
            "the two high-confidence edges each emit a PROJECTED lineage record",
        )
        val skippedRecords = recordStore.all().filter { it.lifecycle == ProjectionLifecycle.SKIPPED }
        assertEquals(
            listOf(fixtures.decayCandidateId),
            skippedRecords.map { it.propositionId },
            "the low-confidence decay candidate is the sole SKIPPED lineage record",
        )

        // Stage 3 — query: the portable graph facade derives edges, paths, and lineage from the store.
        val graphQuery = GraphQuery(store, fixtures.contextId)
        val neighborhood = graphQuery.neighborhood(fixtures.ALICE, depth = 1)
        assertTrue(
            neighborhood.neighbours.any { it.entityId == fixtures.BOB },
            "alice is directly related to bob",
        )
        val path = graphQuery.pathBetween(fixtures.ALICE, fixtures.CAROL)
        // The only path is the two-edge alice -> bob -> carol traversal.
        val hop = path.single()
        assertEquals(
            listOf(fixtures.ALICE, fixtures.BOB, fixtures.CAROL),
            hop.entityIds,
            "the path traverses alice -> bob -> carol",
        )
        val lineage = graphQuery.whyExplain("prop-alice-bob")
        assertNotNull(lineage, "lineage is assembled for a known proposition")
        assertEquals("prop-alice-bob", lineage!!.proposition.id, "lineage explains the requested proposition")
        assertEquals(
            listOf("chunk-prop-alice-bob"),
            lineage.groundingChunkIds,
            "lineage surfaces the proposition's grounding chunk",
        )
        assertEquals(PropositionStatus.ACTIVE, lineage.status, "lineage reports the proposition's live status")

        // Vector path: prove the offline embedder is non-degenerate. It must map distinct texts to
        // distinct vectors, and a similarity query with one proposition's exact text must rank that
        // proposition first (self-similarity is the maximum cosine). A constant/degenerate embedder
        // would collapse these distinctions and fail here.
        val embedder = newEmbeddingService()
        assertNotEquals(
            embedder.embed("Alice works with Bob").toList(),
            embedder.embed("Carol works with Dana").toList(),
            "the embedder differentiates distinct texts",
        )
        val similar = store.findSimilarWithScores(
            TextSimilaritySearchRequest(query = "Alice works with Bob", similarityThreshold = 0.0, topK = 3),
        )
        assertEquals(
            "prop-alice-bob",
            similar.first().match.id,
            "a query matching one proposition's text ranks that proposition first",
        )

        // Stage 4 — surprising links: the alice—bob—carol—dana chain yields exactly two two-hop
        // links between non-co-mentioned pairs — alice↔carol via bob and bob↔dana via carol. Each
        // link's endpoints are canonicalised source < target lexicographically.
        val links = TwoHopSemanticLinkDiscoverer().discover(ingested)
        assertEquals(
            listOf(
                Triple(fixtures.ALICE, fixtures.CAROL, listOf(fixtures.BOB)),
                Triple(fixtures.BOB, fixtures.DANA, listOf(fixtures.CAROL)),
            ),
            links.map { Triple(it.sourceEntityId, it.targetEntityId, it.connectingEntityIds) },
            "the chain yields alice↔carol via bob and bob↔dana via carol",
        )

        // Stage 5/6 — collector + event: a decay sweep transitions the low-utility candidate off
        // ACTIVE and the runner emits a PropositionStatusChanged to its installed listener.
        val listener = RecordingListener()
        val runner = DefaultCollectorRunner(
            repository = store,
            strategies = listOf(DecayCollectorStrategy(retireBelow = 0.5)),
            policy = StatusTransitionSweepPolicy(),
            recordStore = null,
            listener = listener,
        )
        val runResult = runner.run(fixtures.contextId, dryRun = false)
        assertTrue(runResult.applied.isNotEmpty(), "the sweep applies at least one transition")
        val swept = store.findById(fixtures.decayCandidateId)
        assertNotNull(swept)
        assertEquals(
            PropositionStatus.STALE,
            swept!!.status,
            "the decay candidate is transitioned to STALE",
        )
        val statusEvent = listener.events.filterIsInstance<PropositionStatusChanged>().single()
        assertEquals(fixtures.decayCandidateId, statusEvent.proposition.id)
        assertEquals(PropositionStatus.STALE, statusEvent.newStatus)

        // Stage 7 — report: a deterministic structured report over the final propositions.
        val finalProps = store.query(PropositionQuery.forContextId(fixtures.contextId))
        val report = StructuredReportProjector.create(topN = 5).report(finalProps, "Canonical Flow")
        assertEquals("Canonical Flow", report.title)
        // The sweep marks the candidate STALE but never deletes it, so all three propositions
        // remain. Top-by-confidence is effective-confidence descending, ties broken by id ascending:
        // the two 0.95 / decay=0.0 edges lead (alice-bob before bob-carol), the decayed candidate last.
        assertEquals(ingested.size, report.totalCount)
        assertEquals(
            listOf("prop-alice-bob", "prop-bob-carol", fixtures.decayCandidateId),
            report.topByConfidence.map { it.id },
            "report orders propositions by effective confidence, ties by id",
        )
    }
}

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

import com.embabel.dice.projection.lineage.CollectorOutcome
import com.embabel.dice.projection.lineage.CollectorRecord
import com.embabel.dice.projection.lineage.CollectorRun
import com.embabel.dice.projection.lineage.ProjectionLifecycle
import com.embabel.dice.projection.lineage.ProjectionRecord
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.spi.MarkReason
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Instant

/**
 * Integration tests for the durable lineage stores against a Neo4j testcontainer (provided by
 * Drivine's test support). Each test starts from an empty graph via [cleanUp].
 */
@SpringBootTest(classes = [TestApplication::class])
class DrivineLineageRecordStoreIntegrationTest {

    @Autowired
    private lateinit var projectionStore: DrivineProjectionRecordStore

    @Autowired
    private lateinit var collectorStore: DrivineCollectorRecordStore

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    @AfterEach
    fun cleanUp() {
        listOf("ProjectionRecord", "CollectorRecord", "CollectorRun").forEach { label ->
            persistenceManager.execute(QuerySpecification.withStatement("MATCH (n:$label) DETACH DELETE n"))
        }
    }

    // ---- ProjectionRecordStore ----

    @Test
    fun `projection record persists and reads back every field`() {
        val record = ProjectionRecord(
            propositionId = "p1",
            target = "neo4j",
            targetRef = "node-42",
            lifecycle = ProjectionLifecycle.ADOPTED,
            runId = "run-1",
            at = Instant.parse("2026-01-01T00:00:00Z"),
            reason = "matched existing entity",
        )

        projectionStore.record(record)

        assertEquals(record, projectionStore.all().single())
    }

    @Test
    fun `re-recording the same proposition-run-target updates in place`() {
        projectionStore.record(
            ProjectionRecord("p1", "neo4j", "n1", ProjectionLifecycle.PROJECTED, "run-1", Instant.EPOCH, "first"),
        )
        projectionStore.record(
            ProjectionRecord("p1", "neo4j", "n1", ProjectionLifecycle.ADOPTED, "run-1", Instant.EPOCH, "second"),
        )

        val only = projectionStore.all().single()
        assertEquals(ProjectionLifecycle.ADOPTED, only.lifecycle)
        assertEquals("second", only.reason)
    }

    @Test
    fun `markStaleByProposition flips matching records and reports the transitioned count`() {
        // two distinct projections of p1 (different targets), plus an unrelated p2
        projectionStore.record(
            ProjectionRecord("p1", "neo4j", "n1", ProjectionLifecycle.PROJECTED, "run-1"),
        )
        projectionStore.record(
            ProjectionRecord("p1", "elastic", "n2", ProjectionLifecycle.ADOPTED, "run-1"),
        )
        projectionStore.record(
            ProjectionRecord("p2", "neo4j", "n3", ProjectionLifecycle.PROJECTED, "run-1"),
        )

        assertEquals(2, projectionStore.markStaleByProposition("p1"))

        assertEquals(
            listOf(ProjectionLifecycle.STALE, ProjectionLifecycle.STALE),
            projectionStore.findByProposition("p1").map { it.lifecycle },
        )
        // a different proposition is untouched
        assertEquals(ProjectionLifecycle.PROJECTED, projectionStore.findByProposition("p2").single().lifecycle)
        assertEquals(2, projectionStore.findStale().size)
    }

    @Test
    fun `markStaleByProposition is idempotent on replay and a no-op for an unknown proposition`() {
        projectionStore.record(
            ProjectionRecord("p1", "neo4j", "n1", ProjectionLifecycle.PROJECTED, "run-1"),
        )

        assertEquals(1, projectionStore.markStaleByProposition("p1"))
        // already stale → nothing transitions on replay
        assertEquals(0, projectionStore.markStaleByProposition("p1"))
        // never seen → genuine zero
        assertEquals(0, projectionStore.markStaleByProposition("does-not-exist"))
    }

    @Test
    fun `scoped projection finders push the predicate into Cypher and return only matching records`() {
        // Three records spread across propositions, targets, runs, refs and lifecycles. Each finder
        // must return exactly its matching subset (proving the predicate runs in the database), never
        // the whole table, and an empty list for a key with no matches.
        projectionStore.record(ProjectionRecord("pA", "neo4j", "edge-A", ProjectionLifecycle.PROJECTED, "run-1"))
        projectionStore.record(ProjectionRecord("pB", "neo4j", "edge-B", ProjectionLifecycle.ADOPTED, "run-1"))
        projectionStore.record(ProjectionRecord("pC", "elastic", "edge-C", ProjectionLifecycle.STALE, "run-2"))

        assertEquals(setOf("pA"), projectionStore.findByProposition("pA").map { it.propositionId }.toSet())
        assertEquals(setOf("pA", "pB"), projectionStore.findByTarget("neo4j").map { it.propositionId }.toSet())
        assertEquals(setOf("pA", "pB"), projectionStore.findByRun("run-1").map { it.propositionId }.toSet())
        assertEquals(setOf("pB"), projectionStore.findByTargetRef("edge-B").map { it.propositionId }.toSet())
        assertEquals(setOf("pC"), projectionStore.findStale().map { it.propositionId }.toSet())
        assertTrue(projectionStore.findByProposition("missing").isEmpty())
        assertEquals(3, projectionStore.all().size)
    }

    @Test
    fun `findByContext returns only the requested context's records, never another's`() {
        projectionStore.record(ProjectionRecord("pA", "neo4j", "eA", ProjectionLifecycle.PROJECTED, "run-1", contextId = "ctx-1"))
        projectionStore.record(ProjectionRecord("pB", "neo4j", "eB", ProjectionLifecycle.ADOPTED, "run-1", contextId = "ctx-2"))

        assertEquals(setOf("pA"), projectionStore.findByContext("ctx-1").map { it.propositionId }.toSet())
        assertEquals(setOf("pB"), projectionStore.findByContext("ctx-2").map { it.propositionId }.toSet())
        assertTrue(projectionStore.findByContext("ctx-missing").isEmpty())
    }

    // ---- CollectorRecordStore ----

    @Test
    fun `collector records and run headers persist and read back`() {
        val run = CollectorRun(
            runId = "run-1",
            startedAt = Instant.parse("2026-01-01T00:00:00Z"),
            finishedAt = Instant.parse("2026-01-01T00:05:00Z"),
            dryRun = false,
        )
        val record = CollectorRecord(
            propositionId = "p1",
            reason = MarkReason.Duplicate("survivor-7"),
            outcome = CollectorOutcome.TRANSITIONED,
            strategyName = "dedup",
            runId = "run-1",
            at = Instant.parse("2026-01-01T00:01:00Z"),
            previousStatus = PropositionStatus.ACTIVE,
            newStatus = PropositionStatus.SUPERSEDED,
        )

        collectorStore.recordRun(run)
        collectorStore.record(record)

        assertEquals(run, collectorStore.runs().single())
        val reloaded = collectorStore.all().single()
        assertEquals(record, reloaded)
        // the survivor id of a Duplicate reason survives the round-trip
        assertEquals("survivor-7", (reloaded.reason as MarkReason.Duplicate).survivorId)
    }

    @Test
    fun `collector record is idempotent on the same proposition-run pair`() {
        collectorStore.record(
            CollectorRecord("p1", MarkReason.Stale, CollectorOutcome.MARKED, "decay", "run-1"),
        )
        collectorStore.record(
            CollectorRecord("p1", MarkReason.Stale, CollectorOutcome.TRANSITIONED, "decay", "run-1"),
        )

        val only = collectorStore.all().single()
        assertEquals(CollectorOutcome.TRANSITIONED, only.outcome)
    }

    @Test
    fun `scoped collector finders push the predicate into Cypher and return only matching records`() {
        collectorStore.record(CollectorRecord("pA", MarkReason.Stale, CollectorOutcome.MARKED, "decay", "run-1"))
        collectorStore.record(CollectorRecord("pB", MarkReason.Stale, CollectorOutcome.TRANSITIONED, "decay", "run-1"))
        collectorStore.record(CollectorRecord("pC", MarkReason.Stale, CollectorOutcome.MARKED, "decay", "run-2"))

        assertEquals(setOf("pA"), collectorStore.findByProposition("pA").map { it.propositionId }.toSet())
        assertEquals(setOf("pA", "pB"), collectorStore.findByRun("run-1").map { it.propositionId }.toSet())
        assertEquals(setOf("pC"), collectorStore.findByRun("run-2").map { it.propositionId }.toSet())
        assertTrue(collectorStore.findByProposition("missing").isEmpty())
        assertEquals(3, collectorStore.all().size)
    }

    @Test
    fun `collector run finished-at and dry-run survive, and an unfinished run reads back null`() {
        val unfinished = CollectorRun(runId = "run-2", startedAt = Instant.parse("2026-02-01T00:00:00Z"), dryRun = true)

        collectorStore.recordRun(unfinished)

        val reloaded = collectorStore.findRun("run-2")
        assertTrue(reloaded != null)
        assertNull(reloaded!!.finishedAt)
        assertTrue(reloaded.dryRun)
    }
}

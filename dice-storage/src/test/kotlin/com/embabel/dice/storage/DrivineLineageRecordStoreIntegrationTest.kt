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
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Integration tests for the durable lineage stores against a Neo4j testcontainer (provided by
 * Drivine's test support). Each test starts from an empty graph via [cleanUp].
 *
 * Uses the shared [Neo4jTestContainer]; see that class for why Drivine's built-in testcontainer
 * is bypassed.
 */
@SpringBootTest(classes = [TestApplication::class])
class DrivineLineageRecordStoreIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }

    @Autowired
    private lateinit var projectionStore: DrivineProjectionRecordStore

    @Autowired
    private lateinit var collectorStore: DrivineCollectorRecordStore

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    @AfterEach
    fun cleanUp() {
        LineageSchema.LABELS.forEach { label ->
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
    fun `the applied merge target and its undo stamp round-trip, and stamping updates in place`() {
        val record = CollectorRecord(
            propositionId = "p-merged",
            reason = MarkReason.Duplicate("proposed-survivor"),
            outcome = CollectorOutcome.TRANSITIONED,
            strategyName = "dedup",
            runId = "run-merge",
            at = Instant.parse("2026-01-01T00:01:00Z"),
            previousStatus = PropositionStatus.ACTIVE,
            newStatus = PropositionStatus.STALE,
            mergedIntoId = "applied-survivor",
        )

        collectorStore.record(record)
        assertEquals(record, collectorStore.all().single())

        // Finishing an undo re-records the same natural key with the stamp set.
        val stamped = record.copy(undoneAt = Instant.parse("2026-01-02T00:00:00Z"))
        collectorStore.record(stamped)

        val reloaded = collectorStore.all().single()
        assertEquals(stamped, reloaded)
        assertEquals("applied-survivor", reloaded.mergedIntoId)
        assertEquals(Instant.parse("2026-01-02T00:00:00Z"), reloaded.undoneAt)

        // Replaying the original outcome must not wipe the stamp: a replayed record carries none,
        // and a plain SET would clear the stored one and re-authorize the collapse.
        collectorStore.record(record)

        assertEquals(stamped, collectorStore.all().single())
    }

    @Test
    fun `a collector row written before the merge and undo properties existed reads back without them`() {
        // Simulates a graph written by an earlier build: the node has no mergedIntoId or undoneAt.
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                CREATE (n:CollectorRecord {
                    propositionId: 'p-legacy', runId: 'run-legacy', reason: 'duplicate',
                    survivorId: 'survivor-legacy', outcome: 'TRANSITIONED', strategyName: 'dedup',
                    at: '2026-01-01T00:00:00Z', previousStatus: 'ACTIVE', newStatus: 'STALE'
                })
                """.trimIndent(),
            ),
        )

        val legacy = collectorStore.findByProposition("p-legacy").single()

        assertNull(legacy.mergedIntoId, "an old row records no merge, so it cannot authorize an undo")
        assertNull(legacy.undoneAt)
        assertEquals("survivor-legacy", (legacy.reason as MarkReason.Duplicate).survivorId)
        assertEquals(CollectorOutcome.TRANSITIONED, legacy.outcome)
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

    @Test
    fun `run headers an earlier build wrote come first, by finish instant and then run id`() {
        val legacy = listOf(
            CollectorRun("legacy-1", Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:05Z")),
            CollectorRun("legacy-2", Instant.parse("2026-01-01T00:00:01Z"), Instant.parse("2026-01-01T00:00:05.500Z"), dryRun = true),
            CollectorRun("legacy-3", Instant.parse("2026-01-01T00:00:06Z")),
            CollectorRun("legacy-4", Instant.parse("2026-01-01T00:00:07Z"), Instant.parse("2026-01-01T00:00:09Z")),
            CollectorRun("legacy-5", Instant.parse("2026-01-01T00:00:08Z"), Instant.parse("2026-01-01T00:00:09Z")),
        )
        // Created in reverse, so a read that returns nodes in creation order gets them backwards.
        legacy.reversed().forEach(::writeEarlierBuildHeader)
        val recorded = CollectorRun(
            runId = "run-recorded",
            startedAt = Instant.parse("2025-06-01T00:00:00Z"),
            finishedAt = Instant.parse("2025-06-01T00:01:00Z"),
        )
        collectorStore.recordRun(recorded)

        assertEquals(legacy + recorded, collectorStore.runs())
        assertNull(collectorStore.findRun("legacy-3")!!.finishedAt)
    }

    @Test
    fun `a header with no number comes before every numbered header, whenever it was created`() {
        collectorStore.recordRun(CollectorRun("run-numbered", Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:01:00Z")))
        writeEarlierBuildHeader(
            CollectorRun("legacy-late", Instant.parse("2027-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:01:00Z")),
        )

        assertEquals(listOf("legacy-late", "run-numbered"), collectorStore.runs().map { it.runId })
    }

    @Test
    fun `recording a run again keeps its number, and an earlier-build header stays unnumbered`() {
        val legacy = CollectorRun("legacy", Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:01:00Z"))
        writeEarlierBuildHeader(legacy)
        val runA = CollectorRun("run-a", Instant.parse("2026-03-01T00:00:00Z"))
        val runB = CollectorRun("run-b", Instant.parse("2026-02-01T00:00:00Z"))
        val runC = CollectorRun("run-c", Instant.parse("2026-01-15T00:00:00Z"))
        listOf(runA, runB, runC).forEach(collectorStore::recordRun)

        assertEquals(listOf(1L, 2L, 3L), listOf("run-a", "run-b", "run-c").map(::storedSequence))
        assertEquals(3L, counterValue())

        val finishedA = runA.copy(finishedAt = Instant.parse("2026-03-01T00:10:00Z"))
        collectorStore.recordRun(finishedA)
        val flippedLegacy = legacy.copy(dryRun = !legacy.dryRun)
        collectorStore.recordRun(flippedLegacy)

        assertEquals(listOf("legacy", "run-a", "run-b", "run-c"), collectorStore.runs().map { it.runId })
        assertEquals(3L, counterValue())
        assertNull(storedSequence("legacy"))
        assertEquals(finishedA, collectorStore.findRun("run-a"))
        assertEquals(flippedLegacy, collectorStore.findRun("legacy"))

        // Among unnumbered headers a re-recorded one moves to wherever its new finish instant puts it.
        writeEarlierBuildHeader(CollectorRun("legacy-later", Instant.parse("2026-01-01T00:00:30Z"), Instant.parse("2026-01-01T00:02:00Z")))
        collectorStore.recordRun(flippedLegacy.copy(finishedAt = Instant.parse("2026-01-01T00:03:00Z")))

        assertEquals(listOf("legacy-later", "legacy", "run-a", "run-b", "run-c"), collectorStore.runs().map { it.runId })
        assertNull(storedSequence("legacy"))
        assertEquals(3L, counterValue())
    }

    @Test
    fun `run headers recorded at once from many threads get distinct numbers`() {
        val threads = 48
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val ready = CountDownLatch(threads)
            val go = CountDownLatch(1)
            val starts = (1..threads).map { i ->
                pool.submit<Unit> {
                    ready.countDown()
                    assertTrue(go.await(30, TimeUnit.SECONDS), "the start signal never came")
                    collectorStore.recordRun(CollectorRun("concurrent-$i", Instant.parse("2026-01-01T00:00:00Z")))
                }
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "not every thread reached the start line")
            go.countDown()
            starts.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        val numbers = (1..threads).map { storedSequence("concurrent-$it") }
        assertEquals((1L..threads.toLong()).toList(), numbers.map { requireNotNull(it) }.sorted())
        assertEquals(threads.toLong(), counterValue())
    }

    /**
     * Write a run header the way a build without numbered headers did, with raw Cypher. That build
     * set `runId`, `startedAt` and `dryRun`, plus `finishedAt` for a finished run, and no `sequence`.
     *
     * @param run The header to write.
     */
    private fun writeEarlierBuildHeader(run: CollectorRun) {
        val finished = run.finishedAt?.let { ", finishedAt: ${'$'}finishedAt" }.orEmpty()
        persistenceManager.execute(
            QuerySpecification.withStatement(
                "CREATE (n:CollectorRun {runId: ${'$'}runId, startedAt: ${'$'}startedAt, dryRun: ${'$'}dryRun$finished})",
            ).bind(
                mapOf(
                    "runId" to run.runId,
                    "startedAt" to run.startedAt.toString(),
                    "dryRun" to run.dryRun,
                    "finishedAt" to run.finishedAt?.toString(),
                ),
            ),
        )
    }

    /**
     * Read the `sequence` a run header holds. It is storage bookkeeping, so read it straight out.
     *
     * @param runId The header's run id.
     * @return The number, or null when the header has none.
     */
    private fun storedSequence(runId: String): Long? = persistenceManager.maybeGetOne(
        QuerySpecification.withStatement(
            "MATCH (n:CollectorRun {runId: ${'$'}runId}) RETURN n.sequence AS sequence",
        ).bind(mapOf("runId" to runId)).transform(Long::class.javaObjectType),
    )

    /**
     * Read how far the run header counter has been advanced.
     *
     * @return The counter's value, or null when no counter exists.
     */
    private fun counterValue(): Long? = persistenceManager.maybeGetOne(
        QuerySpecification.withStatement(
            "MATCH (c:CollectorRunCounter) RETURN c.sequence AS sequence",
        ).transform(Long::class.javaObjectType),
    )
}

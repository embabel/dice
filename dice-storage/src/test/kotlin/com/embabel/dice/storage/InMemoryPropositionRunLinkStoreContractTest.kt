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

import com.embabel.dice.proposition.extraction.ExtractionRunKey
import com.embabel.dice.proposition.extraction.ExtractionRunRef
import com.embabel.dice.proposition.extraction.ExtractionRunStatus
import com.embabel.dice.proposition.extraction.ExtractionRunTransition
import com.embabel.dice.proposition.extraction.InMemoryExtractionRunStore
import com.embabel.dice.proposition.extraction.InMemoryPropositionRunLinkStore
import com.embabel.dice.proposition.extraction.PropositionRunLinkStore
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The reference implementation against the cross-backend contract. It is the executable statement
 * of what the Drivine store is held to, so it runs the same suite from the same fixtures.
 */
class InMemoryPropositionRunLinkStoreContractTest : AbstractPropositionRunLinkStoreContractTest() {

    private lateinit var runs: InMemoryExtractionRunStore
    private lateinit var propositions: InMemoryPropositionRepository

    override fun store(): PropositionRunLinkStore = store(maxRuns = 10_000)

    private fun store(maxRuns: Int): InMemoryPropositionRunLinkStore {
        runs = InMemoryExtractionRunStore()
        propositions = InMemoryPropositionRepository()
        listOf(tenant, neighbour).forEach { context ->
            fixtureRunIds.forEach { runs.save(run(it, context)) }
        }
        (fixturePropositionIds + disposablePropositionId).forEach {
            propositions.save(proposition(it, tenant))
        }
        neighbourPropositionIds.forEach { propositions.save(proposition(it, neighbour)) }
        return InMemoryPropositionRunLinkStore(runs, propositions, maxRuns)
    }

    /**
     * Past the cap the store forgets the links of the run it linked earliest, once that run has
     * ended. A run still running keeps its links even when it is the oldest, so the cap is a bound
     * on finished lineage, not a way to lose a run that is still being attributed to.
     */
    @Test
    fun `past the cap the earliest linked ended run loses its links and a running one keeps them`() {
        val store = store(maxRuns = 2)
        val first = ExtractionRunKey(tenant, ExtractionRunRef(fixtureRunIds[0]))
        val second = ExtractionRunKey(tenant, ExtractionRunRef(fixtureRunIds[1]))
        val third = ExtractionRunKey(tenant, ExtractionRunRef("link-run-c"))
        runs.save(run(third.runRef.runId, tenant))
        val id = fixturePropositionIds.first()
        store.link(first, listOf(id))
        store.link(second, listOf(id))
        runs.transition(first, completed())
        runs.transition(second, completed())

        store.link(third, listOf(id))

        assertThat(store.propositionsOf(first, 10)).isEmpty()
        assertThat(store.propositionsOf(second, 10)).containsExactly(id)
        assertThat(store.propositionsOf(third, 10)).containsExactly(id)
        assertThat(store.runsOf(tenant.value, id, 10).map { it.runId })
            .containsExactly(fixtureRunIds[1], third.runRef.runId)
    }

    @Test
    fun `a running run is never evicted for the cap`() {
        val store = store(maxRuns = 1)
        val first = ExtractionRunKey(tenant, ExtractionRunRef(fixtureRunIds[0]))
        val second = ExtractionRunKey(tenant, ExtractionRunRef(fixtureRunIds[1]))
        val id = fixturePropositionIds.first()
        store.link(first, listOf(id))

        store.link(second, listOf(id))

        assertThat(store.propositionsOf(first, 10)).containsExactly(id)
        assertThat(store.propositionsOf(second, 10)).containsExactly(id)
    }

    private fun completed() = ExtractionRunTransition(ExtractionRunStatus.COMPLETED, Instant.now())

    @Test
    fun `the cap must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { store(maxRuns = 0) }
    }

    override fun deleteProposition(id: String) {
        propositions.delete(id)
    }

    /**
     * The reference store prunes a stale link when a read finds it, and the proof is that the id
     * cannot come back: re-saving a proposition under the same id after the prune does not revive
     * the link, because the link is gone, not merely hidden.
     */
    @Test
    fun `a link to a deleted proposition is pruned on read and does not revive with the id`() {
        val store = store()
        val key = ExtractionRunKey(tenant, ExtractionRunRef(fixtureRunIds.first()))
        store.link(key, listOf(disposablePropositionId, fixturePropositionIds.first()))

        deleteProposition(disposablePropositionId)
        assertThat(store.propositionsOf(key, 10)).containsExactly(fixturePropositionIds.first())

        propositions.save(proposition(disposablePropositionId, tenant))
        assertThat(store.propositionsOf(key, 10)).containsExactly(fixturePropositionIds.first())
        assertThat(store.runsOf(tenant.value, disposablePropositionId, 10)).isEmpty()
    }

    /**
     * Two threads linking the same claim to the same run leave one edge.
     *
     * `PRODUCED_BY_RUN` is a set relation: a run either produced a claim or it did not, and there is
     * no such thing as producing it twice. Re-extraction is the normal case, and lineage is written
     * from a path that retries, so concurrent writers landing on the same pair is the expected
     * shape here. Duplicated edges would inflate every audit answer built on this
     * relation while each individual link still looked correct.
     *
     * The guard is that the relation is stored as a set behind one monitor, so the check and the
     * write that depends on it cannot interleave. Swap that set for a list and this test reports two.
     */
    @Test
    fun `concurrent links of the same pair leave exactly one edge`() {
        val store = store()
        val key = ExtractionRunKey(tenant, ExtractionRunRef(fixtureRunIds.first()))
        val propositionId = fixturePropositionIds.first()

        val threads = 8
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val failures = CopyOnWriteArrayList<Throwable>()
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                pool.submit {
                    try {
                        ready.countDown()
                        // Every thread blocks here, so the writes overlap, with no thread queueing
                        // up behind another's startup.
                        go.await()
                        store.link(key, listOf(propositionId))
                    } catch (e: Throwable) {
                        failures += e
                    }
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "workers did not start")
            go.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "workers did not finish")
        } finally {
            pool.shutdownNow()
        }

        assertTrue(failures.isEmpty(), "concurrent links failed: $failures")
        assertEquals(
            listOf(propositionId),
            store.propositionsOf(key, 10),
            "the run produced this claim once, however many writers said so",
        )
        assertEquals(
            listOf(key.runRef),
            store.runsOf(tenant, propositionId, 10),
            "and the claim names that run once, from the other direction",
        )
    }
}

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
import com.embabel.dice.proposition.extraction.InMemoryExtractionRunStore
import com.embabel.dice.proposition.extraction.InMemoryPropositionRunLinkStore
import com.embabel.dice.proposition.extraction.PropositionRunLinkStore
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The reference implementation against the cross-backend contract. It is the executable statement
 * of what the Drivine store is held to, so it runs the same suite from the same fixtures.
 */
class InMemoryPropositionRunLinkStoreContractTest : AbstractPropositionRunLinkStoreContractTest() {

    private lateinit var propositions: InMemoryPropositionRepository

    override fun store(): PropositionRunLinkStore {
        val runs = InMemoryExtractionRunStore()
        propositions = InMemoryPropositionRepository()
        listOf(tenant, neighbour).forEach { context ->
            fixtureRunIds.forEach { runs.save(run(it, context)) }
        }
        (fixturePropositionIds + disposablePropositionId).forEach {
            propositions.save(proposition(it, tenant))
        }
        neighbourPropositionIds.forEach { propositions.save(proposition(it, neighbour)) }
        return InMemoryPropositionRunLinkStore(runs, propositions)
    }

    override fun deleteProposition(id: String) {
        propositions.delete(id)
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

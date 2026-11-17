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

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.Relations
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.pipeline.PropositionPipeline
import com.embabel.dice.projection.graph.GraphProjectionService
import com.embabel.dice.proposition.PropositionRepository
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A run link store and a writer bound from two threads never both bind.
 */
class PersistUnitBindingRaceTest {

    private val pipeline = mockk<PropositionPipeline>(relaxed = true)
    private val history = mockk<ChunkHistoryStore>(relaxed = true)
    private val propositions = mockk<PropositionRepository>(relaxed = true)
    private val entities = mockk<NamedEntityDataRepository>(relaxed = true)
    private val resolver = mockk<EntityResolver>(relaxed = true)
    private val projection = mockk<GraphProjectionService>(relaxed = true)
    private val linkStore = mockk<PropositionRunLinkStore>()
    private val writer = PersistUnitWriter { error("never called") }

    /**
     * A fresh extractor with nothing bound.
     *
     * @return An extractor over the shared mocks.
     */
    private fun extraction() = IncrementalPropositionExtraction(
        propositionPipeline = pipeline,
        chunkHistoryStore = history,
        dataDictionary = DataDictionary.fromClasses("binding-race"),
        relations = Relations.empty(),
        propositionRepository = propositions,
        entityRepository = entities,
        entityResolver = resolver,
        graphProjectionService = projection,
        properties = PropositionExtractionProperties(),
    )

    /**
     * Runs a binding call and says whether it bound.
     *
     * @param bind The binding call.
     * @return True when the call returned, false when it threw `IllegalStateException`.
     */
    private fun bound(bind: () -> Unit): Boolean =
        try {
            bind()
            true
        } catch (e: IllegalStateException) {
            false
        }

    /**
     * Holds the first binding call between its checks and its write, then starts the second.
     *
     * The second call is given until it has ended or is blocked on the lock, then the first call is
     * released.
     *
     * @param extraction The extractor both calls bind on.
     * @param first The call that is held.
     * @param second The call that is started while the first is held.
     * @return Whether the first call bound, and whether the second did.
     */
    private fun bindSecondInsideFirst(
        extraction: IncrementalPropositionExtraction,
        first: () -> Unit,
        second: () -> Unit,
    ): Pair<Boolean, Boolean> {
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstBound = AtomicBoolean()
        val secondBound = AtomicBoolean()
        extraction.afterBindingCheck = {
            if (Thread.currentThread().name == "first-binder") {
                held.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        val firstThread = Thread({ firstBound.set(bound(first)) }, "first-binder")
        val secondThread = Thread({ secondBound.set(bound(second)) }, "second-binder")
        firstThread.start()
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue()
        secondThread.start()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (secondThread.state != Thread.State.BLOCKED &&
            secondThread.state != Thread.State.TERMINATED &&
            System.nanoTime() < deadline
        ) {
            Thread.onSpinWait()
        }
        release.countDown()
        firstThread.join(10_000)
        secondThread.join(10_000)
        return firstBound.get() to secondBound.get()
    }

    @Test
    fun `E1 a writer bound while a run link store binding is between its checks and its write is refused`() {
        val extraction = extraction()

        val (storeBound, writerBound) = bindSecondInsideFirst(
            extraction,
            first = { extraction.withRunLineage(linkStore) },
            second = { extraction.withPersistUnitWriter(writer) },
        )

        assertThat(storeBound).isTrue()
        assertThat(writerBound).isFalse()
    }

    @Test
    fun `E2 a run link store bound while a writer binding is between its checks and its write is refused`() {
        val extraction = extraction()

        val (writerBound, storeBound) = bindSecondInsideFirst(
            extraction,
            first = { extraction.withPersistUnitWriter(writer) },
            second = { extraction.withRunLineage(linkStore) },
        )

        assertThat(writerBound).isTrue()
        assertThat(storeBound).isFalse()
    }

    @Test
    fun `E3 over two thousand extractors a store and a writer released together never both bind`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(2_000) { attempt ->
                val extraction = extraction()
                val start = CyclicBarrier(2)
                val store = pool.submit<Boolean> {
                    start.await(10, TimeUnit.SECONDS)
                    bound { extraction.withRunLineage(linkStore) }
                }
                val bindWriter = pool.submit<Boolean> {
                    start.await(10, TimeUnit.SECONDS)
                    bound { extraction.withPersistUnitWriter(writer) }
                }
                val storeBound = store.get(10, TimeUnit.SECONDS)
                val writerBound = bindWriter.get(10, TimeUnit.SECONDS)
                assertThat(storeBound && writerBound).describedAs("attempt %d bound both", attempt).isFalse()
                assertThat(storeBound || writerBound).describedAs("attempt %d bound neither", attempt).isTrue()
            }
        } finally {
            pool.shutdownNow()
        }
    }
}

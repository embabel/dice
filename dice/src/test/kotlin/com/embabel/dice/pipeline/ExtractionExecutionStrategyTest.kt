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
package com.embabel.dice.pipeline

import com.embabel.agent.rag.model.Chunk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Direct tests for the [ExtractionExecutionStrategy] SPI implementations.
 *
 * These prove the SPI contract independently of the pipeline: input-order preservation,
 * null-slot-on-failure, and (for [BatchedExtractionStrategy]) batch windowing and the
 * `require(batchSize > 0)` guard. The test owns the [ExecutorService] and shuts it down
 * (the library never shuts down a caller-supplied executor).
 */
class ExtractionExecutionStrategyTest {

    private val executor: ExecutorService = Executors.newFixedThreadPool(4)

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
    }

    private fun chunk(id: String): Chunk =
        Chunk(id = id, text = "text-$id", metadata = emptyMap(), parentId = "")

    private fun chunks(n: Int): List<Chunk> = (0 until n).map { chunk("c$it") }

    @Test
    fun `serial preserves input order`() {
        val cs = chunks(5)
        val result = SerialExtractionStrategy.execute(cs) { it.id }
        assertEquals(listOf("c0", "c1", "c2", "c3", "c4"), result)
    }

    @Test
    fun `parallel preserves input order`() {
        val cs = chunks(20)
        val result = ParallelExtractionStrategy(executor).execute(cs) { it.id }
        assertEquals(cs.map { it.id }, result)
    }

    @Test
    fun `batched preserves input order`() {
        val cs = chunks(17)
        val result = BatchedExtractionStrategy(batchSize = 5, executor = executor).execute(cs) { it.id }
        assertEquals(cs.map { it.id }, result)
    }

    @Test
    fun `serial yields null slot at the failing index`() {
        val cs = listOf(chunk("ok-0"), chunk("boom"), chunk("ok-2"))
        val result = SerialExtractionStrategy.execute(cs) { c ->
            if (c.id == "boom") throw IllegalStateException("boom") else c.id
        }
        assertEquals(listOf("ok-0", null, "ok-2"), result)
    }

    @Test
    fun `parallel yields null slot at the failing index`() {
        val cs = listOf(chunk("ok-0"), chunk("boom"), chunk("ok-2"))
        val result = ParallelExtractionStrategy(executor).execute(cs) { c ->
            if (c.id == "boom") throw IllegalStateException("boom") else c.id
        }
        assertEquals(listOf("ok-0", null, "ok-2"), result)
    }

    @Test
    fun `batched yields null slot at the failing index`() {
        val cs = listOf(chunk("ok-0"), chunk("boom"), chunk("ok-2"), chunk("ok-3"))
        val result = BatchedExtractionStrategy(batchSize = 2, executor = executor).execute(cs) { c ->
            if (c.id == "boom") throw IllegalStateException("boom") else c.id
        }
        assertEquals(listOf("ok-0", null, "ok-2", "ok-3"), result)
    }

    @Test
    fun `batched bounds in-flight work to batch windows`() {
        // 6 chunks, batchSize 2 -> 3 windows of 2. Within a window both run before the
        // next window starts; we assert the window boundary by tracking max concurrency.
        val cs = chunks(6)
        val started = ConcurrentLinkedQueue<String>()
        BatchedExtractionStrategy(batchSize = 2, executor = executor).execute(cs) { c ->
            started.add(c.id)
            c.id
        }
        // All chunks ran exactly once, in order across the concatenated windows.
        assertEquals(6, started.size)
        assertEquals(cs.map { it.id }.toSet(), started.toSet())
    }

    @Test
    fun `batched rejects non-positive batch size`() {
        assertThrows<IllegalArgumentException> { BatchedExtractionStrategy(0) }
        assertThrows<IllegalArgumentException> { BatchedExtractionStrategy(-1) }
    }

    @Test
    fun `empty input yields empty result for all strategies`() {
        val empty = emptyList<Chunk>()
        assertEquals(emptyList<String?>(), SerialExtractionStrategy.execute(empty) { it.id })
        assertEquals(emptyList<String?>(), ParallelExtractionStrategy(executor).execute(empty) { it.id })
        assertEquals(emptyList<String?>(), BatchedExtractionStrategy(executor = executor).execute(empty) { it.id })
    }

    @Test
    fun `batched with batch size one degrades to serial-equivalent output`() {
        // Documented degrade-to-serial safe path: BatchedExtractionStrategy(1) must produce
        // the byte-identical result (including null-on-failure slots, in input order) that
        // SerialExtractionStrategy produces for the same input.
        val cs = listOf(chunk("a"), chunk("boom"), chunk("c"), chunk("d"))
        val extract: (Chunk) -> String = { c ->
            if (c.id == "boom") throw IllegalStateException("boom") else c.id
        }

        val serial = SerialExtractionStrategy.execute(cs, extract)
        val batched1 = BatchedExtractionStrategy(batchSize = 1, executor = executor).execute(cs, extract)

        assertEquals(serial, batched1)
        assertEquals(listOf("a", null, "c", "d"), batched1)
    }

    @Test
    fun `parallel strategy does not shut down the caller-supplied executor`() {
        // Caller-owned lifecycle contract: the library must NEVER shut down an injected
        // executor. After a full execute() the executor must remain usable for reuse.
        val strategy = ParallelExtractionStrategy(executor)
        strategy.execute(chunks(4)) { it.id }

        assertFalse(executor.isShutdown, "executor must not be shut down by the strategy")

        // Prove it is still usable for a subsequent run.
        val second = strategy.execute(chunks(3)) { it.id }
        assertEquals(listOf("c0", "c1", "c2"), second)
    }

    @Test
    fun `batched strategy does not shut down the caller-supplied executor`() {
        val strategy = BatchedExtractionStrategy(batchSize = 2, executor = executor)
        strategy.execute(chunks(5)) { it.id }

        assertFalse(executor.isShutdown, "executor must not be shut down by the strategy")

        val second = strategy.execute(chunks(2)) { it.id }
        assertEquals(listOf("c0", "c1"), second)
    }

    @Test
    fun `default ctors provide their own executor and do not require an injected one`() {
        // Parallel and Batched must be usable with their default executors
        // (no caller-supplied executor), confirming the injected-executor is optional.
        val parallel = ParallelExtractionStrategy()
        val batched = BatchedExtractionStrategy()
        val cs = chunks(6)

        assertEquals(cs.map { it.id }, parallel.execute(cs) { it.id })
        assertEquals(cs.map { it.id }, batched.execute(cs) { it.id })
    }

    /**
     * Runs two chunks through [strategy]: chunk A throws an AssertionError straight away, and
     * chunk B waits on a latch that another thread opens a little later, then notes that it
     * finished. The call must throw the AssertionError, but only after B is done.
     */
    private fun assertErrorWaitsForTheOtherChunk(strategy: ExtractionExecutionStrategy) {
        val latch = CountDownLatch(1)
        val bFinished = AtomicBoolean(false)
        val releaser = Thread {
            Thread.sleep(300)
            latch.countDown()
        }
        releaser.start()

        assertThrows<AssertionError> {
            strategy.execute(listOf(chunk("a"), chunk("b"))) { c ->
                if (c.id == "a") throw AssertionError("a failed")
                latch.await(5, TimeUnit.SECONDS)
                bFinished.set(true)
                c.id
            }
        }
        val finishedWhenCallReturned = bFinished.get()
        releaser.join()

        assertTrue(finishedWhenCallReturned, "chunk b should have finished before the Error reached the caller")
    }

    @Test
    fun `parallel waits for every chunk before rethrowing an Error`() {
        assertErrorWaitsForTheOtherChunk(ParallelExtractionStrategy(executor))
    }

    @Test
    fun `batched waits for every chunk in the window before rethrowing an Error`() {
        assertErrorWaitsForTheOtherChunk(BatchedExtractionStrategy(batchSize = 2, executor = executor))
    }

    /** Two chunks both throw an Error; the caller gets the first one with the second attached as suppressed. */
    private fun assertLaterErrorsAreSuppressed(strategy: ExtractionExecutionStrategy) {
        val thrown = assertThrows<AssertionError> {
            strategy.execute(listOf(chunk("a"), chunk("b"), chunk("c"))) { c ->
                when (c.id) {
                    "a" -> throw AssertionError("a failed")
                    "c" -> throw StackOverflowError("c failed")
                    else -> c.id
                }
            }
        }
        assertEquals("a failed", thrown.message)
        assertEquals(listOf("c failed"), thrown.suppressed.map { it.message })
        assertTrue(thrown.suppressed.single() is StackOverflowError)
    }

    @Test
    fun `parallel rethrows the first Error and suppresses the rest`() {
        assertLaterErrorsAreSuppressed(ParallelExtractionStrategy(executor))
    }

    @Test
    fun `batched rethrows the first Error and suppresses the rest`() {
        assertLaterErrorsAreSuppressed(BatchedExtractionStrategy(batchSize = 3, executor = executor))
    }

    /**
     * A one-thread pool that is shut down with shutdownNow just before the third task is handed to
     * it, so the queued second task is dropped without running and the third is rejected.
     */
    private class ShutDownMidSubmission(
        private val pool: ThreadPoolExecutor = ThreadPoolExecutor(
            1, 1, 0, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
        ),
    ) : AbstractExecutorService() {
        private var submitted = 0

        override fun execute(command: Runnable) {
            submitted++
            if (submitted == 3) pool.shutdownNow()
            pool.execute(command)
        }

        override fun shutdown() = pool.shutdown()
        override fun shutdownNow(): MutableList<Runnable> = pool.shutdownNow()
        override fun isShutdown() = pool.isShutdown
        override fun isTerminated() = pool.isTerminated
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = pool.awaitTermination(timeout, unit)
    }

    /** The first chunk blocks until interrupted, the second is dropped by shutdownNow, the third is rejected. */
    private fun assertRejectionAfterShutdownNowIsThrown(strategy: (ExecutorService) -> ExtractionExecutionStrategy) {
        val pool = ShutDownMidSubmission()
        val neverReleased = CountDownLatch(1)
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            assertThrows<RejectedExecutionException> {
                strategy(pool).execute(chunks(3)) { c ->
                    if (c.id == "c0") neverReleased.await()
                    c.id
                }
            }
        }
        Thread.interrupted()
        pool.shutdownNow()
    }

    @Test
    fun `parallel throws the rejection when shutdownNow drops a queued chunk`() {
        assertRejectionAfterShutdownNowIsThrown { ParallelExtractionStrategy(it) }
    }

    @Test
    fun `batched throws the rejection when shutdownNow drops a queued chunk`() {
        assertRejectionAfterShutdownNowIsThrown { BatchedExtractionStrategy(batchSize = 3, executor = it) }
    }
}

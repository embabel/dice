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
import com.embabel.dice.common.attemptCatching
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Function

/** One chunk's result, and whether the extraction left the worker thread interrupted. */
private class Slot<T>(val value: T?, val interrupted: Boolean)

/**
 * One chunk handed to the executor. [claimed] is set once, either by the task when it starts or by
 * the strategy when it gives up on a task that has not started, so exactly one of them wins.
 */
private class Handoff<T>(val claimed: AtomicBoolean, val future: CompletableFuture<Slot<T>?>)

/** Remembers that an extraction was interrupted, until the strategy hands the interrupt back. */
private class PendingInterrupt {
    var seen = false
}

/**
 * Runs [body] and then sets the calling thread's interrupt flag if any extraction during it was
 * interrupted, whether [body] returned or threw. Holding the interrupt until the end keeps an
 * extraction that runs on the calling thread, as it does under CallerRunsPolicy, from failing
 * because of an interrupt an earlier chunk raised.
 */
private inline fun <R> handingBackInterrupts(body: (PendingInterrupt) -> R): R {
    val pending = PendingInterrupt()
    try {
        return body(pending)
    } finally {
        if (pending.seen) Thread.currentThread().interrupt()
    }
}

/**
 * Runs [extract] on every chunk on [executor] and hands back the results in input order. This is
 * the shared body of the two concurrent strategies.
 *
 * Nothing is rethrown until every extraction that was started has finished, so no extraction is
 * still running when the caller sees a failure. That covers an [Error] thrown by an extraction and
 * a [RejectedExecutionException] from an executor that will not take more work: the chunks already
 * handed over are waited for, then the failure is thrown. When the executor rejected the chunk
 * because it was shut down, only the chunks that already started are waited for. The rest are
 * given up, because `shutdownNow` drops queued tasks so they never run. A given-up task that does
 * run later does nothing. If several things fail, the first Error
 * in input order is thrown (or the first failure of any kind when none is an Error), with the
 * others attached as suppressed. An Error arrives wrapped in a [CompletionException] and is unwrapped,
 * so the caller sees the Error as it was.
 *
 * An interrupt raised during an extraction is cleared from the thread it ran on, so neither the
 * pool nor the caller is left interrupted while other chunks run, and recorded in [pending] for
 * [handingBackInterrupts] to set on the calling thread at the end.
 */
private fun <T> runAll(
    executor: ExecutorService,
    chunks: List<Chunk>,
    pending: PendingInterrupt,
    extract: (Chunk) -> T,
): List<T?> {
    val handoffs = ArrayList<Handoff<T>>(chunks.size)
    var rejection: RejectedExecutionException? = null
    for (chunk in chunks) {
        val claimed = AtomicBoolean(false)
        try {
            val future = CompletableFuture.supplyAsync({
                if (claimed.compareAndSet(false, true)) {
                    val value = attemptCatching { extract(chunk) }.getOrNull()
                    Slot(value, Thread.interrupted())
                } else {
                    null
                }
            }, executor)
            handoffs += Handoff(claimed, future)
        } catch (e: RejectedExecutionException) {
            rejection = e
            break
        }
    }
    val awaited = if (rejection == null || !executor.isShutdown) {
        handoffs
    } else {
        handoffs.filterNot { it.claimed.compareAndSet(false, true) }
    }
    val failures = mutableListOf<Throwable>()
    val results = awaited.map { handoff ->
        try {
            val slot = handoff.future.join()
            if (slot?.interrupted == true) pending.seen = true
            slot?.value
        } catch (e: Throwable) {
            failures += (e as? CompletionException)?.cause as? Error ?: e
            null
        }
    }
    rejection?.let { failures += it }
    if (failures.isNotEmpty()) {
        val primary = failures.firstOrNull { it is Error } ?: failures.first()
        failures.filter { it !== primary }.forEach { primary.addSuppressed(it) }
        throw primary
    }
    return results
}

/**
 * Controls how the [PropositionPipeline] dispatches the stateless per-chunk extraction stage.
 * Implementations choose serial or concurrent execution; the resolution stage (entity resolution)
 * always runs serially regardless of the strategy, preserving cross-chunk entity identity.
 *
 * ## Contract
 * - **Input order is preserved**: the returned list has exactly `chunks.size` elements and
 *   `result[i]` corresponds to `chunks[i]` (or `null` if extraction of `chunks[i]` failed).
 * - **Failure is a `null` slot**: when `extract(chunk)` throws an Exception, that slot is `null`.
 *   An Error is not caught and reaches the caller of [execute], though a concurrent strategy
 *   first waits for the other chunks it already started to finish. An interrupt gives a `null` slot
 *   and sets the interrupt flag of the thread that called [execute], even when the chunk ran on a
 *   pool thread. If the executor rejects a chunk, the strategy waits for the chunks it already
 *   handed over and then throws the rejection. If the executor was shut down, it waits only for
 *   the chunks that already started and gives up the ones still waiting to start. The pipeline maps a `null` slot to a
 *   [ChunkPropositionResult.Failed]. No element is ever dropped or reordered.
 *
 * ## Executor ownership
 * Each implementation owns its execution mechanism. The [execute] signature intentionally
 * does not mention [ExecutorService], because any executor is an injected implementation detail.
 * A caller-supplied executor is never shut down by the strategy, so its lifecycle stays the
 * caller's. When a strategy creates its own pool (the no-executor constructors), that pool is
 * the strategy's to clean up: both concurrent strategies are [AutoCloseable], and [close]
 * shuts down only an internally-created pool.
 *
 * ## Thread-safety gate for production
 * The default is [SerialExtractionStrategy] (no concurrency). Before switching to
 * [ParallelExtractionStrategy] or [BatchedExtractionStrategy] with `batchSize > 1`, verify
 * that the `Ai` implementation backing your `PropositionExtractor` is thread-safe for
 * concurrent `extract()` calls. Until then, `BatchedExtractionStrategy(batchSize = 1)` is
 * the safe degrade-to-serial path.
 *
 * Note: this is a plain `interface` rather than a `fun interface` because Kotlin SAM
 * conversion does not support abstract methods with their own type parameters (`fun <T> execute(...)`).
 */
interface ExtractionExecutionStrategy {

    /**
     * Apply [extract] to each chunk, returning results in INPUT ORDER. A `null` slot at
     * index `i` means `extract(chunks[i])` threw and was caught (see contract above).
     *
     * @param chunks the chunks to extract, in order
     * @param extract the stateless per-chunk extraction function
     * @return a list of size `chunks.size`; `result[i]` is the extraction of `chunks[i]` or `null` on failure
     */
    fun <T> execute(chunks: List<Chunk>, extract: (Chunk) -> T): List<T?>

    /**
     * This is [execute] for a `java.util.function.Function`, with the same input order and `null`
     * slot rules. A Java caller can pass a `Function` it already holds and does not need a Kotlin
     * function type. If you need to know which chunks failed and why, use [attemptAll] instead.
     *
     * @param chunks the chunks to extract, in order
     * @param extract the stateless per-chunk extraction function
     * @return a list of size `chunks.size`; `result[i]` is the extraction of `chunks[i]` or `null` on failure
     */
    fun <T> executeAll(chunks: List<Chunk>, extract: Function<in Chunk, out T>): List<T?> =
        execute(chunks) { extract.apply(it) }

    /**
     * Like [executeAll], but every chunk comes back as a [ChunkOutcome] that says whether it worked,
     * which chunk it was, and the exception if it failed. Outcomes are in input order. If the
     * strategy leaves a slot empty, that chunk is reported as a failure.
     *
     * @param chunks the chunks to extract, in order
     * @param extract the stateless per-chunk extraction function
     * @return one outcome per chunk, in the same order as `chunks`
     */
    fun <T> attemptAll(chunks: List<Chunk>, extract: Function<in Chunk, out T>): List<ChunkOutcome<T>> =
        execute(chunks) { chunk ->
            attemptCatching { extract.apply(chunk) }.fold(
                { ChunkOutcome.success(chunk, it) },
                { ChunkOutcome.failure<T>(chunk, it as Exception) },
            )
        }.mapIndexed { i, outcome ->
            outcome ?: ChunkOutcome.failure<T>(
                chunks[i],
                IllegalStateException("The strategy returned no result for chunk ${chunks[i].id}"),
            )
        }
}

/**
 * Default strategy: processes each chunk one at a time on the calling thread. A failed
 * extraction produces a `null` slot rather than propagating the exception.
 */
object SerialExtractionStrategy : ExtractionExecutionStrategy {
    override fun <T> execute(chunks: List<Chunk>, extract: (Chunk) -> T): List<T?> =
        chunks.map { attemptCatching { extract(it) }.getOrNull() }
}

/**
 * Fans all chunks out concurrently on the [executor] via [CompletableFuture], then joins them in
 * input order. A failed extraction yields a `null` slot.
 *
 * A caller-supplied executor is never shut down here; a pool created by the no-arg constructor is
 * shut down by [close].
 *
 * Production use requires verifying extractor thread-safety first (see [ExtractionExecutionStrategy]).
 */
class ParallelExtractionStrategy private constructor(
    private val executor: ExecutorService,
    private val ownsExecutor: Boolean,
) : ExtractionExecutionStrategy, AutoCloseable {

    /** Use a caller-supplied [executor]; [close] will not shut it down. */
    constructor(executor: ExecutorService) : this(executor, ownsExecutor = false)

    /**
     * Create an internal fixed pool sized to the available processors. The pool is owned by this
     * strategy, so call [close] (or use it as a resource) when you are done with it.
     */
    constructor() : this(
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()),
        ownsExecutor = true,
    )

    override fun <T> execute(chunks: List<Chunk>, extract: (Chunk) -> T): List<T?> =
        handingBackInterrupts { pending -> runAll(executor, chunks, pending, extract) }

    /** Shuts down an internally-created pool; a no-op when the executor was caller-supplied. */
    override fun close() {
        if (ownsExecutor) executor.shutdown()
    }
}

/**
 * Processes chunks in fixed-size windows: each window fans out concurrently on the injected
 * [executor] and is fully joined before the next window starts. This is the rate-limit lever:
 * it caps in-flight `extract()` calls to [batchSize] at a time. Results are returned in input order.
 *
 * `BatchedExtractionStrategy(batchSize = 1)` is the safe degrade-to-serial path until extractor
 * thread-safety is verified (see [ExtractionExecutionStrategy]). A caller-supplied executor is
 * never shut down here; a pool created by the no-executor constructor is shut down by [close].
 *
 * @param batchSize maximum concurrent extractions per window; must be positive
 */
class BatchedExtractionStrategy private constructor(
    private val batchSize: Int,
    private val executor: ExecutorService,
    private val ownsExecutor: Boolean,
) : ExtractionExecutionStrategy, AutoCloseable {

    init {
        require(batchSize > 0) { "batchSize must be positive" }
    }

    /** Use a caller-supplied [executor]; [close] will not shut it down. */
    constructor(batchSize: Int = 5, executor: ExecutorService) : this(batchSize, executor, ownsExecutor = false)

    /**
     * Create an internal fixed pool sized to [batchSize]. The pool is owned by this strategy, so
     * call [close] (or use it as a resource) when you are done with it.
     */
    constructor(batchSize: Int = 5) : this(batchSize, Executors.newFixedThreadPool(batchSize), ownsExecutor = true)

    override fun <T> execute(chunks: List<Chunk>, extract: (Chunk) -> T): List<T?> =
        handingBackInterrupts { pending ->
            chunks.chunked(batchSize).flatMap { window -> runAll(executor, window, pending, extract) }
        }

    /** Shuts down an internally-created pool; a no-op when the executor was caller-supplied. */
    override fun close() {
        if (ownsExecutor) executor.shutdown()
    }
}

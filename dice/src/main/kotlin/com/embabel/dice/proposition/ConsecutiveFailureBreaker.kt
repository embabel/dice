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
package com.embabel.dice.proposition

import com.embabel.dice.common.attemptCatching
import java.util.AbstractMap
import java.util.concurrent.Callable
import java.util.function.Consumer

/**
 * Mostly stops calling a service once [maxConsecutiveFailures] calls in a row have failed.
 *
 * A re-embed makes one embedding call per proposition. When the embedding service is down, every
 * call fails, and each one waits out its own timeout and retries before the next starts, so trying
 * them all can take a very long time. With this, one bad proposition is still skipped and the run
 * continues, but a run of failures means the service is probably down: later calls are skipped, and
 * the caller reports them as not attempted.
 *
 * A run of failures can also be a block of inputs the service always rejects, and propositions load
 * in a stable order, so every rerun would trip in the same place. So once tripped, the breaker still
 * lets one call through as a probe after skipping 1, then 2, 4, 8… calls. A probe that succeeds
 * resets it. Against a dead service that costs about log2(n) extra calls over n. [attemptEach] also
 * goes back for what was skipped once a probe gets through.
 *
 * Not thread-safe; use one per run.
 */
class ConsecutiveFailureBreaker @JvmOverloads constructor(
    val maxConsecutiveFailures: Int = DEFAULT_MAX_CONSECUTIVE_FAILURES,
) {

    init {
        require(maxConsecutiveFailures > 0) { "maxConsecutiveFailures must be positive" }
    }

    private var consecutiveFailures = 0

    /** How many calls to skip before the next probe. Doubles after each failed probe. */
    private var probeGap = 1

    private var skippedSinceProbe = 0

    /** Whether the limit has been reached, so calls are skipped apart from the occasional probe. */
    val tripped: Boolean get() = consecutiveFailures >= maxConsecutiveFailures

    /**
     * Run [call] and return its result, or null without running it while [tripped] and between
     * probes. An [Exception] is returned as a failure and counts towards the limit; a success resets
     * the count. An [Error] propagates. When the call is interrupted, the result is a failure and the
     * thread's interrupt flag is set again.
     */
    fun <T> attempt(call: () -> T): Result<T>? {
        val probing = tripped
        if (probing) {
            if (skippedSinceProbe < probeGap) {
                skippedSinceProbe++
                return null
            }
            skippedSinceProbe = 0
        }
        val result = attemptCatching(call)
        when {
            result.isSuccess -> {
                consecutiveFailures = 0
                probeGap = 1
            }
            probing -> probeGap = (probeGap * 2).coerceAtMost(MAX_PROBE_GAP)
            else -> consecutiveFailures++
        }
        return result
    }

    /**
     * Runs [call] on each of [items] in order through [attempt], and returns each item with its
     * result, or with null if it was never run.
     *
     * When a call succeeds after earlier items in the same pass were skipped, the service is up, so
     * the skipped items get another pass. That is what keeps a block of always-rejected items from
     * hiding the items behind it on every run. No item runs twice, so the whole thing costs at most
     * one call per item.
     *
     * [onChunk] is given each pass's results [chunkSize] items at a time, as soon as they are in, with
     * null for the items skipped. An item skipped in one pass can come back with a result in a later
     * one.
     *
     * @param items what to run [call] on
     * @param chunkSize how many results [onChunk] gets at once; must be positive
     * @param onChunk receives each chunk of results as it completes
     * @param call the work to run on one item
     * Java code calls [attemptEachCall], which returns [Outcome]s.
     *
     * @return every item, in the order given, with its result, or null if it was never run
     */
    @JvmSynthetic
    fun <K, T> attemptEach(
        items: List<K>,
        chunkSize: Int = Int.MAX_VALUE,
        onChunk: (List<Pair<K, Result<T>?>>) -> Unit = {},
        call: (K) -> T,
    ): List<Pair<K, Result<T>?>> {
        require(chunkSize > 0) { "chunkSize must be positive" }
        val results = MutableList<Result<T>?>(items.size) { null }
        var pending = items.indices.toList()
        while (pending.isNotEmpty()) {
            val skipped = mutableListOf<Int>()
            var upAfterSkip = false
            pending.chunked(chunkSize).forEach { chunk ->
                onChunk(
                    chunk.map { i ->
                        val result = attempt { call(items[i]) }
                        if (result == null) {
                            skipped += i
                        } else {
                            results[i] = result
                            if (result.isSuccess && skipped.isNotEmpty()) upAfterSkip = true
                        }
                        items[i] to result
                    },
                )
            }
            pending = if (upAfterSkip) skipped else emptyList()
        }
        return items.indices.map { items[it] to results[it] }
    }

    /**
     * Runs [call] and returns its [Outcome], or a skipped outcome without running it while [tripped]
     * and between probes.
     * An [Exception] is returned as a failure and counts towards the limit. A success resets the
     * count, which [attempt] and [attemptCall] share. An [Error] propagates. When the call is
     * interrupted, the result is a failure and the thread's interrupt flag is set again.
     *
     * It takes a [Callable] and returns an [Outcome] so that Java code can call it and read the result.
     *
     * @param call the work to run; it may throw checked exceptions
     * @return a success with the value, a failure with the exception, or a skip if the breaker has
     * tripped and this call is not a probe
     */
    fun <T> attemptCall(call: Callable<T>): Outcome<T> = outcomeOf(attempt { call.call() })

    /**
     * Runs [call] on each of [items] as [attemptEach] does, and returns one [Outcome] per item, in
     * the order given. An item that was never run has a skipped outcome.
     *
     * It takes an [ItemCall] and returns [Outcome]s so that Java code can call it and read the results.
     *
     * @param items what to run [call] on
     * @param call the work to run on one item; it may throw checked exceptions
     * @return one outcome per item, at the item's index
     */
    fun <K, T> attemptEachCall(items: List<K>, call: ItemCall<K, T>): List<Outcome<T>> =
        attemptEachCall(items, Int.MAX_VALUE, {}, call)

    /**
     * Runs [call] on each of [items] as [attemptEach] does, handing [onChunk] each pass's outcomes
     * [chunkSize] items at a time, each paired with its item. An item skipped in one pass can come
     * back with a result in a later one.
     *
     * @param items what to run [call] on
     * @param chunkSize how many outcomes [onChunk] gets at once; must be positive
     * @param onChunk receives each chunk of items and their outcomes as it completes
     * @param call the work to run on one item; it may throw checked exceptions
     * @return one outcome per item, at the item's index
     */
    fun <K, T> attemptEachCall(
        items: List<K>,
        chunkSize: Int,
        onChunk: Consumer<List<Map.Entry<K, Outcome<T>>>>,
        call: ItemCall<K, T>,
    ): List<Outcome<T>> =
        attemptEach(
            items,
            chunkSize,
            onChunk = { chunk ->
                onChunk.accept(chunk.map { (item, result) -> AbstractMap.SimpleImmutableEntry(item, outcomeOf(result)) })
            },
        ) { item -> call.call(item) }.map { (_, result) -> outcomeOf(result) }

    private fun <T> outcomeOf(result: Result<T>?): Outcome<T> =
        result?.fold(
            onSuccess = { Outcome.success(it) },
            onFailure = { Outcome.failure<T>(it as Exception) },
        ) ?: Outcome.skipped()

    /**
     * The work [attemptEachCall] runs on one item.
     *
     * @param K the item type
     * @param T what the work returns
     */
    fun interface ItemCall<K, T> {

        /**
         * Run the work on [item].
         *
         * @param item the item to work on
         * @return the result
         * @throws Exception when the work fails; the breaker counts it as a failure
         */
        @Throws(Exception::class)
        fun call(item: K): T
    }

    /**
     * The result of one [attemptCall]: a value, a failure, or a skip.
     *
     * Build one with [success], [failure] or [skipped]. The constructor is internal, but Java can
     * still reach it, so it refuses values that contradict each other.
     *
     * @property value the call's result on success, otherwise null
     * @property exception the exception the call threw on failure, otherwise null
     */
    class Outcome<T> internal constructor(
        private val skipped: Boolean,
        val value: T?,
        val exception: Exception?,
    ) {

        init {
            require(!skipped || (value == null && exception == null)) {
                "A skipped outcome has no value and no exception"
            }
            require(value == null || exception == null) {
                "An outcome has a value or an exception, never both"
            }
        }

        /** True when the breaker had tripped, so the call was not run. */
        val isSkipped: Boolean get() = skipped

        /** True when the call ran and threw an exception. */
        val isFailure: Boolean get() = exception != null

        /** True when the call ran and returned a value. */
        val isSuccess: Boolean get() = !skipped && exception == null

        override fun toString(): String = when {
            skipped -> "Outcome(skipped)"
            exception != null -> "Outcome(failure=$exception)"
            else -> "Outcome(success=$value)"
        }

        companion object {

            /**
             * A successful outcome holding [value].
             *
             * @param value the result of the call
             */
            @JvmStatic
            fun <T> success(value: T): Outcome<T> = Outcome(skipped = false, value = value, exception = null)

            /**
             * A failed outcome holding the [exception] the call threw.
             *
             * @param exception what the call threw
             */
            @JvmStatic
            fun <T> failure(exception: Exception): Outcome<T> =
                Outcome(skipped = false, value = null, exception = exception)

            /** An outcome for a call that was never made because the breaker had tripped. */
            @JvmStatic
            fun <T> skipped(): Outcome<T> = Outcome(skipped = true, value = null, exception = null)
        }
    }

    companion object {

        /** Enough that a few bad propositions in a row don't stop a run, few enough to stop quickly. */
        const val DEFAULT_MAX_CONSECUTIVE_FAILURES = 5

        private const val MAX_PROBE_GAP = 1 shl 30
    }
}

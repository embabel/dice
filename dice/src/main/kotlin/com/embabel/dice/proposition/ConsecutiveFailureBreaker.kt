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

import java.util.concurrent.Callable

/**
 * Stops calling a service once [maxConsecutiveFailures] calls in a row have failed.
 *
 * A re-embed makes one embedding call per proposition. When the embedding service is down, every
 * call fails, and each one waits out its own timeout and retries before the next starts, so trying
 * them all can take a very long time. With this, one bad proposition is still skipped and the run
 * continues, but a run of failures means the service is down: the remaining propositions are not
 * attempted, and the caller reports them as such.
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

    /** Whether the limit has been reached, so no more calls are made. */
    val tripped: Boolean get() = consecutiveFailures >= maxConsecutiveFailures

    /**
     * Run [call] and return its result, or null without running it once [tripped]. An [Exception]
     * is returned as a failure and counts towards the limit; a success resets the count. An [Error]
     * propagates.
     */
    fun <T> attempt(call: () -> T): Result<T>? {
        if (tripped) return null
        return try {
            Result.success(call()).also { consecutiveFailures = 0 }
        } catch (e: Exception) {
            consecutiveFailures++
            Result.failure(e)
        }
    }

    /**
     * Runs [call] and returns its [Outcome], or a skipped outcome without running it once [tripped].
     * An [Exception] is returned as a failure and counts towards the limit. A success resets the
     * count, which [attempt] and [attemptCall] share. An [Error] propagates.
     *
     * It takes a [Callable] and returns an [Outcome] so that Java code can call it and read the result.
     *
     * @param call the work to run; it may throw checked exceptions
     * @return a success with the value, a failure with the exception, or a skip if the breaker has tripped
     */
    fun <T> attemptCall(call: Callable<T>): Outcome<T> {
        val result = attempt { call.call() } ?: return Outcome(skipped = true, value = null, exception = null)
        return result.fold(
            onSuccess = { Outcome(skipped = false, value = it, exception = null) },
            onFailure = { Outcome(skipped = false, value = null, exception = it as Exception) },
        )
    }

    /**
     * The result of one [attemptCall]: a value, a failure, or a skip.
     *
     * @property value the call's result on success, otherwise null
     * @property exception the exception the call threw on failure, otherwise null
     */
    class Outcome<T> internal constructor(
        private val skipped: Boolean,
        val value: T?,
        val exception: Exception?,
    ) {

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
    }

    companion object {

        /** Enough that a few bad propositions in a row don't stop a run, few enough to stop quickly. */
        const val DEFAULT_MAX_CONSECUTIVE_FAILURES = 5
    }
}

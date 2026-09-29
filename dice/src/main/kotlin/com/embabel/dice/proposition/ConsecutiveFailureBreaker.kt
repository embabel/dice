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

    companion object {

        /** Enough that a few bad propositions in a row don't stop a run, few enough to stop quickly. */
        const val DEFAULT_MAX_CONSECUTIVE_FAILURES = 5
    }
}

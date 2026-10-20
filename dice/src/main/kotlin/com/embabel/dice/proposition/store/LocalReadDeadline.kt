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
package com.embabel.dice.proposition.store

import com.embabel.dice.proposition.ScopedReadException
import com.embabel.dice.proposition.ScopedReadFailure
import java.time.Duration
import java.util.concurrent.TimeoutException

/**
 * A time limit for one local read, measured on a monotonic nanosecond clock. It is checked between
 * steps, so it cannot interrupt a slow call such as an embedder: the read fails at the first check
 * after that call returns, and nothing it found is returned.
 */
internal class LocalReadDeadline(
    timeout: Duration,
    private val failure: ScopedReadFailure,
    private val nanoTime: () -> Long,
) {
    private var started = nanoTime()
    private val allowance = if (timeout > Duration.ofNanos(Long.MAX_VALUE)) Long.MAX_VALUE else timeout.toNanos()

    /** Runs [block] without charging its time to this deadline. Vector search uses it for the embedder. */
    fun <T> excluding(block: () -> T): T {
        val before = nanoTime()
        try {
            return block()
        } finally {
            started += nanoTime() - before
        }
    }

    /** Throws a [ScopedReadException] with this deadline's failure, caused by a [TimeoutException], once time is up. */
    fun check() {
        if (nanoTime() - started >= allowance) {
            throw ScopedReadException(failure, TimeoutException("Local read deadline exceeded"))
        }
    }
}

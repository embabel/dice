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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ConsecutiveFailureBreakerTest {

    private fun fail(): Nothing = throw IllegalStateException("down")

    @Test
    fun `trips after the limit and makes no more calls`() {
        val breaker = ConsecutiveFailureBreaker(3)
        repeat(3) { assertTrue(breaker.attempt { fail() }?.isFailure == true) }

        var called = false
        assertNull(breaker.attempt { called = true })
        assertFalse(called)
        assertTrue(breaker.tripped)
    }

    @Test
    fun `a success resets the count, so scattered failures never trip it`() {
        val breaker = ConsecutiveFailureBreaker(3)
        repeat(10) {
            breaker.attempt { fail() }
            breaker.attempt { fail() }
            assertEquals(Result.success(1), breaker.attempt { 1 })
        }
        assertFalse(breaker.tripped)
    }

    @Test
    fun `an Error propagates rather than counting as a failure`() {
        val breaker = ConsecutiveFailureBreaker()
        assertThrows<StackOverflowError> { breaker.attempt { throw StackOverflowError() } }
    }

    @Test
    fun `a failure through attempt then one through attemptCall trips at a limit of two`() {
        val breaker = ConsecutiveFailureBreaker(2)
        assertTrue(breaker.attempt { fail() }?.isFailure == true)
        assertFalse(breaker.tripped)

        assertTrue(breaker.attemptCall<Int> { fail() }.isFailure)
        assertTrue(breaker.tripped)
        assertNull(breaker.attempt { 1 })
        assertTrue(breaker.attemptCall { 1 }.isSkipped)
    }

    @Test
    fun `a failure through attemptCall then one through attempt trips at a limit of two`() {
        val breaker = ConsecutiveFailureBreaker(2)
        assertTrue(breaker.attemptCall<Int> { fail() }.isFailure)
        assertFalse(breaker.tripped)

        assertTrue(breaker.attempt { fail() }?.isFailure == true)
        assertTrue(breaker.tripped)
        assertTrue(breaker.attemptCall { 1 }.isSkipped)
        assertNull(breaker.attempt { 1 })
    }

    @Test
    fun `a success through attemptCall resets failures counted through attempt`() {
        val breaker = ConsecutiveFailureBreaker(2)
        breaker.attempt { fail() }
        assertTrue(breaker.attemptCall { 1 }.isSuccess)
        breaker.attempt { fail() }

        assertFalse(breaker.tripped)
        assertEquals(Result.success(1), breaker.attempt { 1 })
    }

    @Test
    fun `a success through attempt resets failures counted through attemptCall`() {
        val breaker = ConsecutiveFailureBreaker(2)
        breaker.attemptCall<Int> { fail() }
        assertEquals(Result.success(1), breaker.attempt { 1 })
        breaker.attemptCall<Int> { fail() }

        assertFalse(breaker.tripped)
        assertTrue(breaker.attemptCall { 1 }.isSuccess)
    }
}

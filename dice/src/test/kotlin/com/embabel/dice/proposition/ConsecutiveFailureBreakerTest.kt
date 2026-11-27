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
    fun `trips after the limit and skips the next call`() {
        val breaker = ConsecutiveFailureBreaker(3)
        repeat(3) { assertTrue(breaker.attempt { fail() }?.isFailure == true) }

        var called = false
        assertNull(breaker.attempt { called = true })
        assertFalse(called)
        assertTrue(breaker.tripped)
    }

    @Test
    fun `once tripped it probes after skipping 1, 2, 4 and 8 calls`() {
        val breaker = ConsecutiveFailureBreaker(3)
        val ran = (0 until 40).filter { i -> breaker.attempt { fail() } != null }

        assertEquals(listOf(0, 1, 2, 4, 7, 12, 21, 38), ran)
        assertTrue(breaker.tripped)
    }

    @Test
    fun `a probe that succeeds resets the breaker`() {
        val breaker = ConsecutiveFailureBreaker(2)
        repeat(2) { breaker.attempt { fail() } }
        assertNull(breaker.attempt { 1 })

        assertEquals(Result.success(2), breaker.attempt { 2 })
        assertFalse(breaker.tripped)
        assertEquals(Result.success(3), breaker.attempt { 3 })
    }

    @Test
    fun `after a successful probe the gap starts again at one`() {
        val breaker = ConsecutiveFailureBreaker(1)
        breaker.attempt { fail() }
        assertNull(breaker.attempt { 0 })
        assertTrue(breaker.attempt { fail() }?.isFailure == true, "the first probe fails, so the gap grows to two")
        assertNull(breaker.attempt { 0 })
        assertNull(breaker.attempt { 0 })
        assertEquals(Result.success(0), breaker.attempt { 0 })

        breaker.attempt { fail() }

        assertNull(breaker.attempt { 0 })
        assertEquals(Result.success(1), breaker.attempt { 1 }, "the next probe comes after one skip, not four")
    }

    @Test
    fun `attemptEach goes back for what it skipped once a probe gets through`() {
        val rejected = (0 until 6).toSet()
        val calls = mutableListOf<Int>()
        val results = ConsecutiveFailureBreaker(5).attemptEach((0 until 20).toList()) { i ->
            calls += i
            if (i in rejected) fail() else i
        }

        assertEquals((0 until 20).toList(), results.map { it.first })
        assertEquals(rejected, results.filter { it.second?.isFailure == true }.map { it.first }.toSet())
        assertTrue(results.none { it.second == null }, "nothing is left unattempted")
        assertEquals((0 until 20).toList(), calls.sorted(), "each item runs once")
    }

    @Test
    fun `attemptEach against a dead service stops after the probes`() {
        val calls = mutableListOf<Int>()
        val results = ConsecutiveFailureBreaker(5).attemptEach((0 until 20).toList()) { i ->
            calls += i
            fail()
        }

        assertEquals(listOf(0, 1, 2, 3, 4, 6, 9, 14), calls)
        assertEquals(12, results.count { it.second == null })
    }

    @Test
    fun `attemptEach hands each pass to onChunk in chunks, skipped items included`() {
        val chunks = mutableListOf<List<Pair<Int, Boolean?>>>()
        ConsecutiveFailureBreaker(2).attemptEach(
            (0 until 5).toList(),
            chunkSize = 2,
            onChunk = { chunk -> chunks += chunk.map { (i, result) -> i to result?.isSuccess } },
        ) { i -> if (i < 2) fail() else i }

        assertEquals(
            listOf(
                listOf(0 to false, 1 to false),
                listOf(2 to null, 3 to true),
                listOf(4 to true),
                listOf(2 to true),
            ),
            chunks,
        )
    }

    @Test
    fun `attemptEach refuses a chunk size below one`() {
        assertThrows<IllegalArgumentException> { ConsecutiveFailureBreaker().attemptEach(listOf(1), chunkSize = 0) { it } }
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
    }

    @Test
    fun `a failure through attemptCall then one through attempt trips at a limit of two`() {
        val breaker = ConsecutiveFailureBreaker(2)
        assertTrue(breaker.attemptCall<Int> { fail() }.isFailure)
        assertFalse(breaker.tripped)

        assertTrue(breaker.attempt { fail() }?.isFailure == true)
        assertTrue(breaker.tripped)
        assertTrue(breaker.attemptCall { 1 }.isSkipped)
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

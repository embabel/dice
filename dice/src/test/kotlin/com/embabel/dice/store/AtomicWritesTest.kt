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
package com.embabel.dice.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.function.Supplier

/*
 * Contract for AtomicWrites, which runs several store writes as one unit.
 *
 * E1: runAtomically runs its work once, through atomically.
 * E2: An exception thrown by the work reaches the caller as the same instance.
 */
class AtomicWritesTest {

    private var calls = 0
    private val ran = mutableListOf<String>()

    private val writes = object : AtomicWrites {
        override fun <T> atomically(work: Supplier<T>): T {
            calls++
            return work.get()
        }
    }

    @Test
    fun `E1 runAtomically runs the work once through atomically`() {
        writes.runAtomically { ran.add("x") }

        assertEquals(listOf("x"), ran)
        assertEquals(1, calls)
    }

    @Test
    fun `E2 an exception from the work reaches the caller unchanged`() {
        val boom = IllegalStateException("boom")

        val fromAtomically = assertThrows<IllegalStateException> {
            writes.atomically<Unit> { throw boom }
        }
        assertSame(boom, fromAtomically)

        val fromRunAtomically = assertThrows<IllegalStateException> {
            writes.runAtomically { throw boom }
        }
        assertSame(boom, fromRunAtomically)
    }
}

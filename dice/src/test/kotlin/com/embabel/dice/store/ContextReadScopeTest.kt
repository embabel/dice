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

import com.embabel.agent.core.ContextId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [ContextReadScope].
 *
 * Contract:
 * E1 A scope with no id, or with a blank id, cannot be built.
 * E2 A scope copies its ids, drops duplicates and keeps whitespace inside an id. The set is read-only.
 * E3 Scopes over the same contexts are equal whatever the order, and typed ids give the same scope.
 * E4 A scope names exactly the ids it was built with.
 */
class ContextReadScopeTest {

    @Test
    fun `E1 a scope with no id or a blank id cannot be built`() {
        for (ids in listOf(emptyList(), listOf(""), listOf("A", " "), listOf("\t\n"))) {
            assertThrows(IllegalArgumentException::class.java) { ContextReadScope.ofValues(ids) }
        }
        assertThrows(IllegalArgumentException::class.java) { ContextReadScope.ofValues("A", " ") }
        assertThrows(IllegalArgumentException::class.java) { ContextReadScope.of(emptyList()) }
    }

    @Test
    fun `E2 a scope copies its ids, drops duplicates and keeps whitespace inside an id`() {
        val ids = mutableListOf("A", "B", "A", " A ")
        val scope = ContextReadScope.ofValues(ids)
        ids.clear()
        assertEquals(listOf("A", "B", " A "), scope.contextIdValues.toList())
        assertThrows(UnsupportedOperationException::class.java) {
            (scope.contextIdValues as MutableSet<String>).add("C")
        }
    }

    @Test
    fun `E3 scopes over the same contexts are equal whatever the order`() {
        val forward = ContextReadScope.ofValues("A", "B")
        val reversed = ContextReadScope.ofValues(listOf("B", "A", "A"))
        assertEquals(forward, reversed)
        assertEquals(forward.hashCode(), reversed.hashCode())
        assertEquals(forward, ContextReadScope.of(listOf(ContextId("A"), ContextId("B"))))
        assertNotEquals(ContextReadScope.ofValues("A"), ContextReadScope.ofValues("A "))
    }

    @Test
    fun `E4 a scope names exactly the ids it was built with`() {
        val scope = ContextReadScope.ofValues("A", "B")
        assertTrue(scope.containsValue("B"))
        assertTrue(ContextId("A") in scope)
        assertFalse(scope.containsValue("C"))
        assertFalse(scope.containsValue("*"))
        assertFalse(ContextId("a") in scope)
    }
}

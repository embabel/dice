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
package com.embabel.dice.projection.grounding

import com.embabel.agent.filter.PropertyFilter
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * The single resolution convention: exact id first, then namespace
 * suffix (everything after the first ':'). Bridges the stripped
 * `email:<hash>` grounding convention to the stored `email:<user>:<hash>`
 * node, without inventing edges for bare chunk hashes.
 */
class DefaultGroundingResolverTest {

    private fun entity(id: String) = SimpleNamedEntityData(
        id = id, name = id, description = "", labels = setOf("EmailSignal", "__Entity__"), properties = emptyMap(),
    )

    @Test
    fun `exact id resolves without a suffix scan`() {
        val node = entity("email:rod:hash1")
        val repo = mock<NamedEntityDataRepository> { on { findById("email:rod:hash1") } doReturn node }
        val resolver = DefaultGroundingResolver(repo)

        assertEquals(listOf(node), resolver.resolveAll("email:rod:hash1"))
        assertEquals(node, resolver.resolve("email:rod:hash1"))
        verify(repo, never()).find(any<String>(), any())
    }

    @Test
    fun `stripped grounding id resolves to the namespaced node by suffix`() {
        val node = entity("email:rod_johnson_assistant:19e62389fa109b1b")
        val repo = mock<NamedEntityDataRepository> {
            on { findById("email:19e62389fa109b1b") } doReturn null
            on { find("__Entity__", PropertyFilter.endsWith("id", "19e62389fa109b1b")) } doReturn listOf(node)
        }
        val resolver = DefaultGroundingResolver(repo)

        assertEquals(listOf(node), resolver.resolveAll("email:19e62389fa109b1b"))
        assertEquals(node, resolver.resolve("email:19e62389fa109b1b"))
    }

    @Test
    fun `ambiguous suffix returns all for resolveAll but null for resolve`() {
        val a = entity("email:userA:dup")
        val b = entity("email:userB:dup")
        val repo = mock<NamedEntityDataRepository> {
            on { findById("email:dup") } doReturn null
            on { find("__Entity__", PropertyFilter.endsWith("id", "dup")) } doReturn listOf(a, b)
        }
        val resolver = DefaultGroundingResolver(repo)

        assertEquals(listOf(a, b), resolver.resolveAll("email:dup"))
        assertNull(resolver.resolve("email:dup"), "ambiguous match must not collapse to an arbitrary node")
    }

    @Test
    fun `bare id with no colon never triggers a suffix scan`() {
        // Legacy chunk hashes / free-text fingerprints stay unresolved.
        val repo = mock<NamedEntityDataRepository> { on { findById("chunkhash123") } doReturn null }
        val resolver = DefaultGroundingResolver(repo)

        assertTrue(resolver.resolveAll("chunkhash123").isEmpty())
        assertNull(resolver.resolve("chunkhash123"))
        verify(repo, never()).find(any<String>(), any())
    }

    @Test
    fun `blank id resolves to nothing`() {
        val repo = mock<NamedEntityDataRepository>()
        val resolver = DefaultGroundingResolver(repo)

        assertTrue(resolver.resolveAll("").isEmpty())
        assertNull(resolver.resolve(""))
        verify(repo, never()).findById(any())
    }
}

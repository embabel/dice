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

import com.embabel.dice.proposition.PropositionQuery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Tests for [TraversalRequest], the immutable request an [AbstractionTraversal] runs.
 *
 * Contract:
 * E1 sourcesOf and abstractionsOf give one step in their direction.
 * E2 The block and the chain give equal requests, and a request toward sources does not equal one toward abstractions.
 * E3 Depth is accepted from one to sixteen and rejected outside that range.
 * E4 A blank start, a limit below one, and a filter with an ordering or a limit are rejected.
 */
class TraversalRequestTest {

    @Test
    fun `E1 sourcesOf and abstractionsOf give one step in their direction`() {
        val f = PropositionQuery.againstContext("c")

        val sources = TraversalRequest.sourcesOf("p1", f)
        assertEquals("p1", sources.startId)
        assertEquals(TraversalDirection.TO_SOURCES, sources.direction)
        assertEquals(1, sources.maxDepth)
        assertEquals(TraversalRequest.DEFAULT_LIMIT, sources.limit)
        assertEquals(100, sources.limit)
        assertEquals(f, sources.filter)

        assertEquals(TraversalDirection.TO_ABSTRACTIONS, TraversalRequest.abstractionsOf("p1", f).direction)
    }

    @Test
    fun `E2 the block and the chain give equal requests`() {
        val f = PropositionQuery.againstContext("c")

        val abstractionsBlock = TraversalRequest.abstractionsOf("p1", f) {
            maxDepth = 3
            limit = 7
        }
        val abstractionsChain = TraversalRequest.abstractionsOf("p1", f).withMaxDepth(3).withLimit(7)
        assertEquals(abstractionsBlock, abstractionsChain)
        assertEquals(abstractionsBlock.hashCode(), abstractionsChain.hashCode())

        val sourcesBlock = TraversalRequest.sourcesOf("p1", f) {
            maxDepth = 3
            limit = 7
        }
        val sourcesChain = TraversalRequest.sourcesOf("p1", f).withMaxDepth(3).withLimit(7)
        assertEquals(sourcesBlock, sourcesChain)
        assertEquals(sourcesBlock.hashCode(), sourcesChain.hashCode())

        assertNotEquals(TraversalRequest.sourcesOf("p1", f), TraversalRequest.abstractionsOf("p1", f))
    }

    @Test
    fun `E3 depth is accepted from one to sixteen`() {
        val f = PropositionQuery.againstContext("c")

        assertEquals(1, TraversalRequest.sourcesOf("p1", f).withMaxDepth(1).maxDepth)
        assertEquals(16, TraversalRequest.MAX_DEPTH)
        assertEquals(16, TraversalRequest.sourcesOf("p1", f).withMaxDepth(16).maxDepth)

        assertThrows(IllegalArgumentException::class.java) { TraversalRequest.sourcesOf("p1", f).withMaxDepth(0) }
        assertThrows(IllegalArgumentException::class.java) { TraversalRequest.sourcesOf("p1", f).withMaxDepth(17) }
        assertThrows(IllegalArgumentException::class.java) {
            TraversalRequest.sourcesOf("p1", f) { maxDepth = 17 }
        }
    }

    @Test
    fun `E4 a blank start, a limit below one, and a filter with an ordering or a limit are rejected`() {
        val f = PropositionQuery.againstContext("c")

        assertThrows(IllegalArgumentException::class.java) { TraversalRequest.sourcesOf(" ", f) }
        assertThrows(IllegalArgumentException::class.java) { TraversalRequest.abstractionsOf("", f) }
        assertThrows(IllegalArgumentException::class.java) { TraversalRequest.sourcesOf("p1", f).withLimit(0) }
        assertThrows(IllegalArgumentException::class.java) { TraversalRequest.sourcesOf("p1", f.orderedByCreated()) }
        assertThrows(IllegalArgumentException::class.java) { TraversalRequest.abstractionsOf("p1", f.withLimit(5)) }
    }
}

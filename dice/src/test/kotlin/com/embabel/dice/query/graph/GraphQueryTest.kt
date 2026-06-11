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
package com.embabel.dice.query.graph

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.temporal.TemporalMetadata
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Behavioural contract for the portable graph-query facade over proposition edges.
 *
 * A proposition mentioning two resolved entities is the edge between them; neighbourhoods and paths
 * are derived from those edges with no graph backend. Lineage is assembled from the proposition's
 * own durable fields. Absent paths return an empty list rather than throwing, and cyclic data
 * terminates.
 */
class GraphQueryTest {

    private val contextId = ContextId("graph-test")

    private fun edge(id: String, a: String, b: String): Proposition =
        Proposition(
            id = id,
            contextId = contextId,
            text = "$a relates to $b",
            mentions = listOf(
                EntityMention(span = a, type = "Entity", resolvedId = a, role = MentionRole.SUBJECT),
                EntityMention(span = b, type = "Entity", resolvedId = b, role = MentionRole.OBJECT),
            ),
            confidence = 0.9,
        )

    /** A→B and B→C edges (a simple chain) plus a cyclic A↔B reinforcement. */
    private fun chainStore(): InMemoryPropositionRepository {
        val store = InMemoryPropositionRepository()
        store.save(edge("ab", "A", "B"))
        store.save(edge("bc", "B", "C"))
        store.save(edge("ba", "B", "A")) // cycle A<->B
        return store
    }

    @Test
    fun `neighborhood returns related entities with the connecting proposition`() {
        val gq = GraphQuery(chainStore(), contextId)

        val n = gq.neighborhood("A")

        assertEquals("A", n.entityId)
        val b = n.neighbours.single { it.entityId == "B" }
        assertTrue(b.via.any { it.id == "ab" }, "B is connected to A via the ab proposition")
    }

    @Test
    fun `neighborhood reports hop distance and attributes each edge to its immediate predecessor`() {
        val gq = GraphQuery(chainStore(), contextId)

        // Depth 2 from A: B is direct (1 hop via ab/ba), C is reached through B (2 hops via bc).
        val n = gq.neighborhood("A", depth = 2)

        val b = n.neighbours.single { it.entityId == "B" }
        assertEquals(1, b.distance, "B is directly related to A")
        assertTrue(b.via.all { it.id == "ab" || it.id == "ba" }, "B's edges connect it to A")

        val c = n.neighbours.single { it.entityId == "C" }
        assertEquals(2, c.distance, "C is two hops from A")
        // C's via must be the B->C edge, never the A<->B edge (which does not mention C).
        assertTrue(c.via.all { it.id == "bc" }, "C is attributed only the edge linking it to B")
        assertTrue(c.via.none { it.id == "ab" || it.id == "ba" }, "C never claims an A-incident edge")
    }

    @Test
    fun `neighborhood does not double-count edges when traversal terminates early`() {
        val gq = GraphQuery(chainStore(), contextId)

        // bound 5 over a graph that exhausts at depth 2: the frontier empties early and must not
        // re-scan already-visited nodes and re-append their edges.
        val n = gq.neighborhood("A", depth = 5)

        val b = n.neighbours.single { it.entityId == "B" }
        // A<->B has exactly two distinct edges (ab, ba); each appears once, not duplicated by re-scans.
        assertEquals(2, b.via.size, "B carries exactly its two distinct connecting edges")
        assertEquals(setOf("ab", "ba"), b.via.map { it.id }.toSet())

        val c = n.neighbours.single { it.entityId == "C" }
        assertEquals(1, c.via.size, "C carries exactly its single connecting edge")
        assertEquals("bc", c.via.single().id)
    }

    @Test
    fun `constructor rejects a maxDepth below one`() {
        assertThrows(IllegalArgumentException::class.java) {
            GraphQuery(chainStore(), contextId, maxDepth = 0)
        }
    }

    @Test
    fun `pathBetween finds a path through an intermediate entity`() {
        val gq = GraphQuery(chainStore(), contextId)

        val paths = gq.pathBetween("A", "C")

        assertTrue(paths.isNotEmpty(), "A reaches C through B")
        val path = paths.first()
        assertEquals("A", path.entityIds.first())
        assertEquals("C", path.entityIds.last())
        assertTrue(path.entityIds.contains("B"), "the path runs through B")
        assertTrue(path.found)
    }

    @Test
    fun `pathBetween returns empty list and never throws when unreachable`() {
        val gq = GraphQuery(chainStore(), contextId)

        val paths = assertDoesNotThrow<List<GraphPath>> { gq.pathBetween("A", "Z") }

        assertTrue(paths.isEmpty(), "no path to an unknown entity yields an empty list")
    }

    @Test
    fun `cyclic data terminates for neighborhood and path`() {
        val gq = GraphQuery(chainStore(), contextId)

        // A<->B cycle present; depth>1 BFS must terminate rather than loop forever.
        assertDoesNotThrow { gq.neighborhood("A", depth = 5) }
        assertDoesNotThrow { gq.pathBetween("A", "C") }
    }

    @Test
    fun `whyExplain assembles lineage from the proposition's own durable fields`() {
        val store = InMemoryPropositionRepository()
        val grounding = listOf(ProvenanceEntry(locator = UriLocator("doc://1"), chunkId = "chunk-1"))
        val now = Instant.now()
        val grounded = Proposition(
            id = "grounded",
            contextId = contextId,
            text = "A is grounded",
            mentions = listOf(EntityMention(span = "A", type = "Entity", resolvedId = "A", role = MentionRole.SUBJECT)),
            confidence = 0.9,
            grounding = listOf("chunk-1"),
            provenanceEntries = grounding,
            reinforceCount = 3,
            status = PropositionStatus.SUPERSEDED,
            temporal = TemporalMetadata(observedAt = now, validFrom = now),
        )
        store.save(grounded)
        val gq = GraphQuery(store, contextId)

        val lineage = gq.whyExplain("grounded")!!

        assertEquals(grounding, lineage.provenanceEntries)
        assertEquals(listOf("chunk-1"), lineage.groundingChunkIds)
        assertEquals(3, lineage.reinforceCount)
        assertEquals(PropositionStatus.SUPERSEDED, lineage.status, "lineage surfaces non-ACTIVE standing")
        assertEquals(now, lineage.temporal?.observedAt)
    }

    @Test
    fun `whyExplain returns null for an unknown proposition`() {
        val gq = GraphQuery(chainStore(), contextId)
        assertNull(gq.whyExplain("does-not-exist"))
    }
}

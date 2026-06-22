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
import com.embabel.dice.spi.AuthorityTier
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.GraphQueryCapable
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.provenance.ConnectorRef
import com.embabel.dice.provenance.ContentAddressedLocator
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.SourceLocator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The portable graph query can confine traversal to edges of at least a given source authority — so
 * "the neighbourhood reachable via strongly-grounded edges only" is answerable without a graph
 * backend. A weak structural edge (e.g. a relationship inferred from derived material) drops out.
 */
class GraphQueryAuthorityFilterTest {

    private val contextId = ContextId("authority-test")

    private fun edge(id: String, a: String, b: String, locator: SourceLocator): Proposition =
        Proposition(
            id = id,
            contextId = contextId,
            text = "$a relates to $b",
            mentions = listOf(
                EntityMention(span = a, type = "Entity", resolvedId = a, role = MentionRole.SUBJECT),
                EntityMention(span = b, type = "Entity", resolvedId = b, role = MentionRole.OBJECT),
            ),
            confidence = 0.9,
        ).withProvenanceEntries(listOf(ProvenanceEntry(locator)))

    /** A→B grounded in a connector (PRIMARY); A→C grounded in derived material (DERIVED). */
    private fun store(): InMemoryPropositionRepository {
        val store = InMemoryPropositionRepository()
        store.save(edge("ab", "A", "B", ConnectorRef("gmail", "msg-1")))
        store.save(edge("ac", "A", "C", ContentAddressedLocator("deadbeef")))
        return store
    }

    @Test
    fun `unfiltered neighborhood includes both strong and weak edges`() {
        val n = GraphQuery(store(), contextId).neighborhood("A")
        assertTrue(n.neighbours.any { it.entityId == "B" })
        assertTrue(n.neighbours.any { it.entityId == "C" })
    }

    @Test
    fun `minAuthority filter keeps strong edges and drops weak ones`() {
        // SECONDARY floor: PRIMARY (A→B) clears it, DERIVED (A→C) does not.
        val n = GraphQuery(store(), contextId).neighborhood("A", minAuthority = AuthorityTier.SECONDARY)
        assertTrue(n.neighbours.any { it.entityId == "B" }, "strongly-grounded B is kept")
        assertFalse(n.neighbours.any { it.entityId == "C" }, "weakly-grounded C is filtered out")
    }

    @Test
    fun `legacy edges with no provenance resolve to UNKNOWN and are dropped by any non-null minAuthority`() {
        // A proposition with no provenance entries resolves to UNKNOWN (ordinal 3),
        // which is weaker than every named tier. Even the most permissive named floor
        // (DERIVED, ordinal 2) is still lower than UNKNOWN, so the edge is dropped.
        val store = InMemoryPropositionRepository()
        store.save(
            Proposition(
                id = "no-provenance",
                contextId = contextId,
                text = "A relates to D",
                mentions = listOf(
                    EntityMention(span = "A", type = "Entity", resolvedId = "A", role = MentionRole.SUBJECT),
                    EntityMention(span = "D", type = "Entity", resolvedId = "D", role = MentionRole.OBJECT),
                ),
                confidence = 0.9,
            )
            // deliberately no .withProvenanceEntries(...)
        )

        val unfiltered = GraphQuery(store, contextId).neighborhood("A")
        assertTrue(unfiltered.neighbours.any { it.entityId == "D" }, "unfiltered query sees D")

        val filtered = GraphQuery(store, contextId).neighborhood("A", minAuthority = AuthorityTier.DERIVED)
        assertFalse(filtered.neighbours.any { it.entityId == "D" },
            "legacy edge with no provenance (UNKNOWN) is dropped even by the weakest named floor")
    }

    @Test
    fun `authority-filtered query routes to a native adapter that honours the floor`() {
        val canned = GraphNeighborhood(
            entityId = "A",
            neighbours = listOf(RelatedEntity(entityId = "NATIVE", via = emptyList(), distance = 1)),
        )
        val native = HonoringNativeStore(store(), canned)

        val result = GraphQuery(native, contextId).neighborhood("A", minAuthority = AuthorityTier.SECONDARY)

        assertTrue(result.neighbours.any { it.entityId == "NATIVE" }, "native adapter's answer is used")
        assertEquals(AuthorityTier.SECONDARY, native.receivedFloor, "the floor is handed to the adapter")
    }

    @Test
    fun `authority-filtered query falls back to the portable path when the native adapter does not honour the floor`() {
        val native = NonHonoringNativeStore(store())

        val result = GraphQuery(native, contextId).neighborhood("A", minAuthority = AuthorityTier.SECONDARY)

        // Portable filtering ran: PRIMARY B kept, DERIVED C dropped, and the adapter's sentinel
        // neighbour never appears because the adapter was not consulted for the filtered query.
        assertTrue(result.neighbours.any { it.entityId == "B" })
        assertFalse(result.neighbours.any { it.entityId == "C" })
        assertFalse(result.neighbours.any { it.entityId == "NATIVE" })
    }

    /** A native store that filters by authority itself, recording the floor it was handed. */
    private class HonoringNativeStore(
        delegate: InMemoryPropositionRepository,
        private val answer: GraphNeighborhood,
    ) : PropositionStore by delegate, GraphQueryCapable {
        override val honorsAuthorityFilter = true
        var receivedFloor: AuthorityTier? = null
            private set

        override fun neighborhood(entityId: String, depth: Int, minAuthority: AuthorityTier?): GraphNeighborhood {
            receivedFloor = minAuthority
            return answer
        }
    }

    /** A native store that only answers unfiltered queries; it never opts into authority filtering. */
    private class NonHonoringNativeStore(
        delegate: InMemoryPropositionRepository,
    ) : PropositionStore by delegate, GraphQueryCapable {
        override fun neighborhood(entityId: String, depth: Int): GraphNeighborhood =
            GraphNeighborhood(
                entityId = entityId,
                neighbours = listOf(RelatedEntity(entityId = "NATIVE", via = emptyList(), distance = 1)),
            )
    }
}

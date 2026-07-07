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
import com.embabel.dice.proposition.GraphQueryCapable
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The facade's own [GraphQuery.maxDepth] ceiling must reach a native [GraphQueryCapable] store, not
 * just the portable BFS path — otherwise a store that clamps its walk to some hardcoded default (as
 * the native Neo4j adapter used to) silently truncates a caller who asked for a deeper walk than
 * that default, even though the facade was configured to allow it.
 */
class GraphQueryMaxDepthTest {

    private val contextId = ContextId("maxdepth-test")

    /**
     * A native store that confines walks to a context (so the facade routes to it) and records the
     * ceiling it was handed, for both neighbourhood and path queries.
     */
    private class RecordingNativeStore(
        delegate: InMemoryPropositionRepository,
    ) : PropositionStore by delegate, GraphQueryCapable {
        override val honorsContextFilter = true
        var receivedNeighborhoodMaxDepth: Int? = null
            private set
        var receivedPathMaxDepth: Int? = null
            private set

        override fun neighborhood(entityId: String, depth: Int, contextId: ContextId?, maxDepth: Int): GraphNeighborhood {
            receivedNeighborhoodMaxDepth = maxDepth
            return GraphNeighborhood(entityId, emptyList())
        }

        override fun pathBetween(entityIdA: String, entityIdB: String, contextId: ContextId?, maxDepth: Int): List<GraphPath> {
            receivedPathMaxDepth = maxDepth
            return emptyList()
        }
    }

    @Test
    fun `neighborhood passes the facade's configured maxDepth to the native adapter`() {
        val native = RecordingNativeStore(InMemoryPropositionRepository())
        val gq = GraphQuery(native, contextId, maxDepth = 8)

        gq.neighborhood("A")

        assertEquals(8, native.receivedNeighborhoodMaxDepth, "the facade's maxDepth ceiling, not a hardcoded default, is handed to the adapter")
    }

    @Test
    fun `pathBetween passes the facade's configured maxDepth to the native adapter`() {
        val native = RecordingNativeStore(InMemoryPropositionRepository())
        val gq = GraphQuery(native, contextId, maxDepth = 8)

        gq.pathBetween("A", "B")

        assertEquals(8, native.receivedPathMaxDepth, "the facade's maxDepth ceiling, not a hardcoded default, is handed to the adapter")
    }

    @Test
    fun `default facade maxDepth still reaches the native adapter`() {
        val native = RecordingNativeStore(InMemoryPropositionRepository())
        val gq = GraphQuery(native, contextId)

        gq.neighborhood("A")

        assertEquals(5, native.receivedNeighborhoodMaxDepth, "the facade's default maxDepth of 5 is still threaded through")
    }
}

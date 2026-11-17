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
package com.embabel.dice.projection.memory

import com.embabel.agent.core.ContextId
import com.embabel.dice.operations.consolidation.SessionConsolidationPass
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Consolidation against a store whose plain query leaves provenance out, as a graph store's does.
 * A session proposition whose provenance the stored one already holds must still count as a replay.
 */
class LeanReadConsolidationTest {

    private val contextId = ContextId("lean-read")
    private val entry = ProvenanceEntry(UriLocator("https://example.com/a"), chunkId = "chunk-a")

    private val stored = Proposition(
        contextId = contextId,
        text = "Alice works at Acme",
        mentions = listOf(EntityMention("Alice", "Person", resolvedId = "alice")),
        confidence = 0.6,
        grounding = listOf("chunk-a"),
        provenanceEntries = listOf(entry),
    )

    /**
     * A repository over [backing] whose plain query drops provenance, and whose provenance-loading
     * query keeps it.
     *
     * @param backing the store that holds the full propositions
     * @return the lean-reading repository
     */
    private fun leanReads(backing: InMemoryPropositionRepository): PropositionRepository =
        object : PropositionRepository by backing {
            override fun query(query: PropositionQuery): List<Proposition> =
                backing.query(query).map { it.copy(provenanceEntries = emptyList()) }

            override fun query(query: PropositionQuery, withProvenance: Boolean): List<Proposition> =
                if (withProvenance) backing.query(query) else query(query)
        }

    @Test
    fun `maintenance does not reinforce with provenance the stored proposition already holds`() {
        val backing = InMemoryPropositionRepository()
        backing.save(stored)

        val result = DefaultMemoryMaintenanceOrchestrator(leanReads(backing), DefaultMemoryConsolidator())
            .maintain(contextId, listOf(stored.copy(id = "session")))

        assertEquals(emptyList<Proposition>(), result.consolidation!!.reinforced)
        assertEquals(stored.reinforceCount, backing.findById(stored.id)!!.reinforceCount)
    }

    @Test
    fun `the dream loop does not reinforce with provenance the stored proposition already holds`() {
        val backing = InMemoryPropositionRepository()
        backing.save(stored)

        DefaultDreamLoopOrchestrator.withRepository(leanReads(backing))
            .withPass(SessionConsolidationPass(DefaultMemoryConsolidator(), listOf(stored.copy(id = "session"))))
            .consolidateNow(contextId)

        val saved = backing.findById(stored.id)!!
        assertEquals(stored.reinforceCount, saved.reinforceCount)
        assertEquals(stored.confidence, saved.confidence)
    }
}

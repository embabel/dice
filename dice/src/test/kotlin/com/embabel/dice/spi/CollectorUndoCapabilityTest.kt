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
package com.embabel.dice.spi

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CollectorUndoCapabilityTest {

    @Test
    fun `undo uses authoritative provenance replacement through a base store decorator`() {
        val store = AppendPreservingStore()
        val trace = InMemoryCollectorTraceStore()
        val contextId = ContextId("ctx-base-store-undo")
        val keep = ProvenanceEntry(UriLocator("https://example.com/keep"))
        val folded = ProvenanceEntry(UriLocator("https://example.com/folded"))
        val survivor = store.save(proposition("survivor", contextId, listOf(keep, folded)))
        val retired = store.save(
            proposition(
                id = "retired",
                contextId = contextId,
                provenance = listOf(folded),
                status = PropositionStatus.STALE,
            ),
        )
        val runId = "run-base-store-undo"
        trace.recordRunContext(runId, contextId)
        trace.recordDecision(
            runId,
            CollectorDecision(
                runId = runId,
                componentId = "component-base-store-undo",
                survivorId = survivor.id,
                action = "duplicate-merge",
                retired = listOf(
                    RetiredProposition(
                        propositionId = retired.id,
                        priorStatus = PropositionStatus.ACTIVE,
                        foldedProvenanceRefs = listOf(folded.locator.key()),
                    ),
                ),
            ),
        )

        val result = undoSingleCollapse(trace, store, survivor.id, retired.id)

        assertEquals(listOf(keep), result?.survivor?.provenanceEntries)
        assertEquals(listOf(keep), store.findById(survivor.id)?.provenanceEntries)
        assertEquals(PropositionStatus.ACTIVE, result?.restored?.status)
    }

    private fun proposition(
        id: String,
        contextId: ContextId,
        provenance: List<ProvenanceEntry>,
        status: PropositionStatus = PropositionStatus.ACTIVE,
    ) = Proposition(
        id = id,
        contextId = contextId,
        text = "$id proposition",
        mentions = emptyList(),
        confidence = 0.9,
        provenanceEntries = provenance,
        status = status,
    )

    /** Models a persistent backend whose ordinary save path never removes unloaded evidence. */
    private class AppendPreservingStore(
        private val delegate: InMemoryPropositionRepository = InMemoryPropositionRepository(),
    ) : PropositionStore by delegate {

        override fun save(proposition: Proposition): Proposition {
            val existing = delegate.findById(proposition.id) ?: return delegate.save(proposition)
            return delegate.save(
                proposition.copy(
                    provenanceEntries = (existing.provenanceEntries + proposition.provenanceEntries).distinct(),
                ),
            )
        }

        override fun setProvenance(
            propositionId: String,
            entries: List<ProvenanceEntry>,
        ): Proposition? = delegate.findById(propositionId)?.let { existing ->
            delegate.save(existing.withProvenance(entries))
        }
    }
}

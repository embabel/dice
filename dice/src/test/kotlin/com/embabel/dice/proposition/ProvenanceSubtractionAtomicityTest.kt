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

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.ProvenanceEvidenceKey
import com.embabel.dice.provenance.UriLocator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What [ProvenanceSubtractionCapable] promises, checked against the in-memory store that
 * implements it: the named evidence goes, everything else stays, a concurrent addition survives,
 * and a deleted proposition is never put back.
 *
 * The concurrency test is what the capability exists for. Reversing a collapse used to read a
 * proposition's entries, work out which should remain, and write those back, so an extraction
 * landing between the read and the write was replaced away with nothing left to recover it from.
 */
class ProvenanceSubtractionAtomicityTest {

    private val contextId = ContextId("ctx-subtraction")
    private val anchor = ProvenanceEntry(UriLocator("https://example.com/anchor"))
    private val folded = ProvenanceEntry(UriLocator("https://example.com/folded"))

    @Test
    fun `evidence added while a subtraction is running survives it`() {
        // Two real threads released together on one proposition: one takes the folded evidence off,
        // the other adds a fresh entry. Whichever lands first, both effects have to be there at the
        // end. A read-modify-write implementation loses the addition whenever the subtraction's
        // read happens first, so this is repeated to give the interleaving room to show up.
        val store = InMemoryPropositionRepository()
        val executor = Executors.newFixedThreadPool(2)
        val rounds = 200
        try {
            repeat(rounds) { round ->
                val id = "survivor-$round"
                val fresh = ProvenanceEntry(UriLocator("https://example.com/fresh-$round"))
                store.save(proposition(id, listOf(anchor, folded)))
                val start = CountDownLatch(1)

                val subtraction = executor.submit<Proposition?> {
                    start.await()
                    store.subtractProvenance(id, listOf(ProvenanceEvidenceKey.encode(folded)))
                }
                val addition = executor.submit<Proposition?> {
                    start.await()
                    store.addProvenance(id, listOf(fresh))
                }
                start.countDown()
                subtraction.get(30, TimeUnit.SECONDS)
                addition.get(30, TimeUnit.SECONDS)

                val entries = store.findById(id)!!.provenanceEntries
                assertTrue(fresh in entries, "round $round lost the concurrent addition: $entries")
                assertFalse(folded in entries, "round $round left the subtracted evidence behind: $entries")
                assertTrue(anchor in entries, "round $round dropped evidence nobody named: $entries")
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `subtracting from a proposition another writer deleted answers null and leaves it deleted`() {
        // The last entry coming off a proposition that has since been deleted must not put it back.
        // Undo reads this null as "somebody deleted the survivor" and stops there.
        val store = InMemoryPropositionRepository()
        val saved = store.save(proposition("deleted-midway", listOf(anchor)))
        assertTrue(store.delete(saved.id))

        assertNull(store.subtractProvenance(saved.id, listOf(ProvenanceEvidenceKey.encode(anchor))))

        assertNull(store.findById(saved.id))
        assertEquals(0, store.count())
        assertNull(store.subtractProvenance("never-existed", listOf(ProvenanceEvidenceKey.encode(anchor))))
    }

    @Test
    fun `a subtraction takes exactly the evidence it names`() {
        val store = InMemoryPropositionRepository()
        val revisionOne = ProvenanceEntry(UriLocator("https://example.com/doc"), sourceRevision = "r1")
        val revisionTwo = ProvenanceEntry(UriLocator("https://example.com/doc"), sourceRevision = "r2")
        val saved = store.save(proposition("exact", listOf(anchor, revisionOne, revisionTwo)))

        val updated = store.subtractProvenance(saved.id, listOf(ProvenanceEvidenceKey.encode(revisionTwo)))

        assertEquals(listOf(anchor, revisionOne), updated?.provenanceEntries)
        assertEquals(listOf(anchor, revisionOne), store.findById(saved.id)?.provenanceEntries)
    }

    @Test
    fun `a subtraction that names nothing reads the proposition and writes nothing`() {
        val store = InMemoryPropositionRepository()
        val saved = store.save(proposition("untouched", listOf(anchor, folded)))

        assertEquals(saved, store.subtractProvenance(saved.id, emptyList()))
        assertEquals(listOf(anchor, folded), store.findById(saved.id)?.provenanceEntries)

        val unmatched = ProvenanceEvidenceKey.encode(ProvenanceEntry(UriLocator("https://example.com/other")))
        assertEquals(saved, store.subtractProvenance(saved.id, listOf(unmatched)))
        assertEquals(listOf(anchor, folded), store.findById(saved.id)?.provenanceEntries)
    }

    private fun proposition(id: String, provenance: List<ProvenanceEntry>) = Proposition(
        id = id,
        contextId = contextId,
        text = "$id proposition",
        mentions = emptyList(),
        confidence = 0.9,
        provenanceEntries = provenance,
    )
}

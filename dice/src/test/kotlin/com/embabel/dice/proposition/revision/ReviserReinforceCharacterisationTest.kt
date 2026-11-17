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
package com.embabel.dice.proposition.revision

import com.embabel.agent.core.ContextId
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Pins what the reviser's reinforce and merge paths return. Drives the SIMILAR and IDENTICAL
 * branches of classifiedToResult directly, so no model call is made.
 */
class ReviserReinforceCharacterisationTest {

    private val old = Instant.parse("2020-01-01T00:00:00Z")
    private val pA = ProvenanceEntry(UriLocator("https://example.com/a"), chunkId = "chunk-a")
    private val pB = ProvenanceEntry(UriLocator("https://example.com/b"), chunkId = "chunk-b")

    private fun proposition(
        text: String,
        confidence: Double,
        decay: Double = 0.0,
        grounding: List<String> = emptyList(),
        provenance: List<ProvenanceEntry> = emptyList(),
        reinforceCount: Int = 0,
        sourceIds: List<String> = emptyList(),
    ) = Proposition(
        contextId = ContextId("test-context"),
        text = text,
        mentions = emptyList(),
        confidence = confidence,
        decay = decay,
        grounding = grounding,
        created = old,
        contentRevised = old,
        metadataRevised = old,
        lastAccessed = old,
        reinforceCount = reinforceCount,
        provenanceEntries = provenance,
        sourceIds = sourceIds,
    )

    private fun existing(confidence: Double = 0.6) = proposition(
        text = "Alice is a developer",
        confidence = confidence,
        decay = 0.2,
        grounding = listOf("g1", "g2"),
        provenance = listOf(pA),
        reinforceCount = 2,
    )

    private fun incoming(confidence: Double = 0.8) = proposition(
        text = "Alice works as a software engineer",
        confidence = confidence,
        grounding = listOf("g2", "g3"),
        provenance = listOf(pA, pB),
    )

    private fun reinforce(existing: Proposition, incoming: Proposition): RevisionResult =
        LlmPropositionReviser(llmOptions = io.mockk.mockk(), ai = io.mockk.mockk()).classifiedToResult(
            incoming,
            listOf(ClassifiedProposition(existing, PropositionRelation.SIMILAR, 0.9, "Related")),
            InMemoryPropositionRepository(),
        )

    private fun revisedFrom(existing: Proposition, incoming: Proposition): Proposition =
        (reinforce(existing, incoming) as RevisionResult.Reinforced).revised

    private fun mergedFrom(existing: Proposition, incoming: Proposition): Proposition =
        (LlmPropositionReviser(llmOptions = io.mockk.mockk(), ai = io.mockk.mockk()).classifiedToResult(
            incoming,
            listOf(ClassifiedProposition(existing, PropositionRelation.IDENTICAL, 1.0, "Same")),
            InMemoryPropositionRepository(),
        ) as RevisionResult.Merged).revised

    /**
     * Builds a reviser whose trust scorer gives every proposition 0.42.
     *
     * @return the scoring reviser
     */
    private fun scoringReviser(): LlmPropositionReviser =
        LlmPropositionReviser(llmOptions = io.mockk.mockk(), ai = io.mockk.mockk()).withTrustScorer { _, _, _ -> 0.42 }

    /**
     * Classifies the incoming proposition against the stored one with a scoring reviser.
     *
     * @param existing the stored proposition
     * @param incoming the new proposition
     * @param relation how the incoming proposition relates to the stored one
     * @param similarity the similarity the classifier reports
     * @return the revision result
     */
    private fun scoredResult(
        existing: Proposition,
        incoming: Proposition,
        relation: PropositionRelation,
        similarity: Double,
    ): RevisionResult =
        scoringReviser().classifiedToResult(
            incoming,
            listOf(ClassifiedProposition(existing, relation, similarity, "Related")),
            InMemoryPropositionRepository(),
        )

    private fun inWindow(stamp: Instant, before: Instant, after: Instant) =
        !stamp.isBefore(before) && !stamp.isAfter(after)

    @Test
    fun `R-E1 confidence adds a tenth of the new confidence`() {
        val revised = revisedFrom(existing(), incoming())
        assertEquals(0.68, revised.confidence, 1e-9)
    }

    @Test
    fun `R-E2 confidence is capped at 0_95`() {
        val revised = revisedFrom(existing(confidence = 0.9), incoming(confidence = 0.9))
        assertEquals(0.95, revised.confidence, 1e-9)
    }

    @Test
    fun `R-E3 confidence above the cap is left where it is`() {
        val revised = revisedFrom(existing(confidence = 0.98), incoming(confidence = 0.5))
        assertEquals(0.98, revised.confidence, 1e-9)
    }

    @Test
    fun `R-E4 decay is slowed to 85 percent`() {
        val revised = revisedFrom(existing(), incoming())
        assertEquals(0.17, revised.decay, 1e-9)
    }

    @Test
    fun `R-E5 grounding and provenance are unioned without duplicates in order`() {
        val revised = revisedFrom(existing(), incoming())
        assertEquals(listOf("g1", "g2", "g3"), revised.grounding)
        assertEquals(listOf(pA, pB), revised.provenanceEntries)
    }

    @Test
    fun `R-E6 reinforce count goes up by one`() {
        val revised = revisedFrom(existing(), incoming())
        assertEquals(3, revised.reinforceCount)
    }

    @Test
    fun `R-E7 content revised and last accessed are stamped now`() {
        val before = Instant.now()
        val revised = revisedFrom(existing(), incoming())
        val after = Instant.now()
        assertTrue(inWindow(revised.contentRevised, before, after), "contentRevised ${revised.contentRevised}")
        assertTrue(inWindow(revised.lastAccessed, before, after), "lastAccessed ${revised.lastAccessed}")
        assertTrue(inWindow(revised.metadataRevised, before, after), "metadataRevised ${revised.metadataRevised}")
        assertEquals(revised.contentRevised, revised.metadataRevised)
        assertEquals(revised.contentRevised, revised.lastAccessed)
    }

    @Test
    fun `R-I1 every other field is carried over from the existing proposition`() {
        val existing = existing()
        val revised = revisedFrom(existing, incoming())
        assertEquals(
            existing.copy(
                confidence = revised.confidence,
                decay = revised.decay,
                grounding = revised.grounding,
                provenanceEntries = revised.provenanceEntries,
                reinforceCount = 3,
                contentRevised = revised.contentRevised,
                metadataRevised = revised.metadataRevised,
                lastAccessed = revised.lastAccessed,
            ),
            revised,
        )
    }

    @Test
    fun `R-I2 the result is Reinforced and its original is the existing proposition`() {
        val existing = existing()
        val result = reinforce(existing, incoming())
        assertTrue(result is RevisionResult.Reinforced, "got $result")
        assertEquals(existing, (result as RevisionResult.Reinforced).original)
    }

    @Test
    fun `source ids of the new proposition are added without duplicates`() {
        val stored = existing().copy(sourceIds = listOf("s1"))
        val revised = revisedFrom(stored, proposition("Alice codes", 0.8, sourceIds = listOf("s1", "s2")))
        assertEquals(listOf("s1", "s2"), revised.sourceIds)
    }

    @Test
    fun `evidence naming only the stored proposition as its source changes nothing`() {
        val stored = proposition("Alice is a developer", 0.6, grounding = listOf("g1"))
        val evidence = proposition("Alice codes", 0.8, grounding = listOf("g1"), sourceIds = listOf(stored.id))
        for (revised in listOf(revisedFrom(stored, evidence), mergedFrom(stored, evidence))) {
            assertEquals(emptyList<String>(), revised.sourceIds)
            assertSame(stored, revised)
        }
    }

    @Test
    fun `the stored proposition's own id is left out of its source ids`() {
        val stored = proposition("Alice is a developer", 0.6, grounding = listOf("g1"))
        val evidence = proposition("Alice codes", 0.8, grounding = listOf("g2"), sourceIds = listOf(stored.id, "q"))
        for (revised in listOf(revisedFrom(stored, evidence), mergedFrom(stored, evidence))) {
            assertEquals(listOf("q"), revised.sourceIds)
            assertEquals(listOf("g1", "g2"), revised.grounding)
            assertEquals(stored.reinforceCount + 1, revised.reinforceCount)
        }
    }

    @Test
    fun `a proposition whose grounding and provenance are already held changes nothing`() {
        val existing = existing()
        val incoming = proposition("Alice codes", 0.8, grounding = listOf("g1"), provenance = listOf(pA))
        assertEquals(existing, revisedFrom(existing, incoming))
    }

    @Test
    fun `a proposition with held grounding and no provenance changes nothing`() {
        val existing = existing()
        val incoming = proposition("Alice codes", 0.8, grounding = listOf("g1"))
        assertEquals(existing, revisedFrom(existing, incoming))
    }

    @Test
    fun `a proposition that names no source still reinforces`() {
        val revised = revisedFrom(existing(), proposition("Alice codes", 0.8))
        assertEquals(3, revised.reinforceCount)
        assertEquals(0.68, revised.confidence, 1e-9)
    }

    @Test
    fun `an identical proposition adds three tenths of its confidence`() {
        val revised = mergedFrom(existing(), incoming())
        assertEquals(0.84, revised.confidence, 1e-9)
    }

    @Test
    fun `an identical proposition leaves a confidence above the cap where it is`() {
        assertEquals(1.0, mergedFrom(existing(confidence = 1.0), incoming()).confidence, 1e-9)
        assertEquals(0.995, mergedFrom(existing(confidence = 0.995), incoming()).confidence, 1e-9)
    }

    @Test
    fun `an identical proposition slows decay to 70 percent`() {
        val revised = mergedFrom(existing(), incoming())
        assertEquals(0.14, revised.decay, 1e-9)
    }

    @Test
    fun `an identical proposition stamps one instant`() {
        val revised = mergedFrom(existing(), incoming())
        assertEquals(revised.contentRevised, revised.metadataRevised)
        assertEquals(revised.contentRevised, revised.lastAccessed)
        assertTrue(revised.contentRevised.isAfter(old), "contentRevised ${revised.contentRevised}")
    }

    @Test
    fun `an identical proposition adds its source ids`() {
        val stored = existing().copy(sourceIds = listOf("s1"))
        val revised = mergedFrom(stored, proposition("Alice codes", 0.8, sourceIds = listOf("s1", "s2")))
        assertEquals(listOf("s1", "s2"), revised.sourceIds)
    }

    @Test
    fun `an identical proposition whose evidence is already held changes nothing`() {
        val existing = existing()
        val incoming = proposition("Alice codes", 0.8, grounding = listOf("g1"), provenance = listOf(pA))
        assertEquals(existing, mergedFrom(existing, incoming))
    }

    @Test
    fun `a replay with a trust scorer returns the stored proposition for a similar match`() {
        val incoming = proposition("Alice codes", 0.8, grounding = listOf("g1"), provenance = listOf(pA))
        val result = scoredResult(existing(), incoming, PropositionRelation.SIMILAR, 0.9) as RevisionResult.Reinforced
        assertEquals(result.original, result.revised)
        assertSame(result.original, result.revised)
    }

    @Test
    fun `a replay with a trust scorer returns the stored proposition for an identical match`() {
        val incoming = proposition("Alice codes", 0.8, grounding = listOf("g1"), provenance = listOf(pA))
        val result = scoredResult(existing(), incoming, PropositionRelation.IDENTICAL, 0.9) as RevisionResult.Merged
        assertEquals(result.original, result.revised)
        assertSame(result.original, result.revised)
    }

    @Test
    fun `a reinforcement with new evidence is still scored`() {
        val result = scoredResult(existing(), incoming(), PropositionRelation.SIMILAR, 0.9) as RevisionResult.Reinforced
        assertEquals(0.42, result.revised.metadata[DiceMetadataKeys.TRUST_SCORE])
    }
}

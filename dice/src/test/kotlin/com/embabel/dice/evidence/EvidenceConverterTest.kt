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
package com.embabel.dice.evidence

import com.embabel.agent.core.ContextId
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.revision.RevisionResult
import com.embabel.dice.provenance.ContentAddressedLocator
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.query.graph.PropositionLineage
import com.embabel.dice.spi.ConflictType
import com.embabel.dice.temporal.TemporalMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class EvidenceConverterTest {

    private val asOf = Instant.parse("2026-03-01T12:00:00Z")

    private val tenDaysBefore = asOf.minus(Duration.ofDays(10))

    private val converter = EvidenceConverter.create().withClock(Clock.fixed(asOf, ZoneOffset.UTC))

    private val searched = Searched.of("ctx").withQuery("Where does Alice live?")

    private val provenance = ProvenanceEntry(locator = ContentAddressedLocator("source-a"), chunkId = "chunk-1")

    /**
     * Creates a claim with confidence 0.9 and decay 0.05, last revised ten days before [asOf].
     *
     * @param id the claim id
     * @param text the claim text
     * @param status the lifecycle status
     * @param level the abstraction level, 0 for an observation
     * @param sourceIds ids of the claims this one was abstracted from
     * @param grounding ids of the grounding chunks
     * @return the claim
     */
    private fun claim(
        id: String,
        text: String = "Alice lives in Berlin.",
        status: PropositionStatus = PropositionStatus.ACTIVE,
        level: Int = 0,
        sourceIds: List<String> = emptyList(),
        grounding: List<String> = emptyList(),
    ): Proposition = Proposition(
        id = id,
        contextId = ContextId("ctx"),
        text = text,
        mentions = emptyList(),
        confidence = 0.9,
        decay = 0.05,
        grounding = grounding,
        created = Instant.parse("2026-01-01T00:00:00Z"),
        contentRevised = tenDaysBefore,
        lastAccessed = tenDaysBefore,
        status = status,
        level = level,
        sourceIds = sourceIds,
    )

    @Test
    fun `a claim converts field by field into an item`() {
        val proposition = Proposition(
            id = "p-9",
            contextId = ContextId("ctx"),
            text = "Alice lives in Berlin.",
            mentions = emptyList(),
            confidence = 0.9,
            decay = 0.05,
            grounding = listOf("chunk-1", "chunk-2"),
            created = Instant.parse("2026-01-01T00:00:00Z"),
            contentRevised = tenDaysBefore,
            lastAccessed = tenDaysBefore,
            status = PropositionStatus.SUPERSEDED,
            uri = "doc://alice",
            provenanceEntries = listOf(provenance),
        )

        val item = converter.item(proposition)

        assertEquals("Alice lives in Berlin.", item.text)
        assertEquals("p-9", item.citation.propositionId)
        assertEquals(ContextId("ctx"), item.citation.contextId)
        assertEquals(listOf("chunk-1", "chunk-2"), item.citation.chunkIds)
        assertEquals(listOf(provenance), item.citation.provenance)
        assertEquals("doc://alice", item.citation.uri)
        assertEquals(0.9, item.confidence)
        assertEquals(proposition.effectiveConfidenceAt(asOf, 2.0), item.effectiveConfidence)
        assertEquals(PropositionStatus.SUPERSEDED, item.status)
        assertEquals(proposition.created, item.recordedAt)
        assertEquals(tenDaysBefore, item.revisedAt)
        assertNull(item.score)
        assertTrue(item.retrievals.isEmpty())
        assertFalse(item.isDerived)
    }

    @Test
    fun `the decay rate multiplier sets the effective confidence`() {
        val proposition = claim("p-1")

        val item = converter.withDecayK(4.0).item(proposition)

        assertEquals(proposition.effectiveConfidenceAt(asOf, 4.0), item.effectiveConfidence)
        assertNotEquals(converter.item(proposition).effectiveConfidence, item.effectiveConfidence)
    }

    @Test
    fun `blank grounding ids are left out and source ids become derived ids`() {
        val grounded = converter.item(claim("p-1", grounding = listOf("chunk-1", " ")))
        val abstraction = converter.item(claim("p-3", level = 1, sourceIds = listOf("p-1", "p-2")))

        assertEquals(listOf("chunk-1"), grounded.citation.chunkIds)
        assertEquals(listOf("p-1", "p-2"), abstraction.derivedFromIds)
        assertTrue(abstraction.isDerived)
    }

    @Test
    fun `a claim with blank text is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            converter.item(claim("p-1", text = "  "))
        }
    }

    @Test
    fun `a scored hit carries its score and one ranked retrieval`() {
        val item = converter.item(SimpleSimilaritySearchResult(claim("p-1"), 0.83), RetrieverId.VECTOR, 2)

        assertEquals(0.83, item.score)
        assertEquals(1, item.retrievals.size)
        assertEquals(RetrieverId.VECTOR, item.retrievals[0].retriever)
        assertEquals(2, item.retrievals[0].rank)
        assertEquals(0.83, item.retrievals[0].score)
    }

    @Test
    fun `a hit with a score that is not finite has no score and a rank only`() {
        val item = converter.item(SimpleSimilaritySearchResult(claim("p-1"), Double.NaN), RetrieverId.VECTOR, 2)

        assertNull(item.score)
        assertEquals(1, item.retrievals.size)
        assertEquals(2, item.retrievals[0].rank)
        assertNull(item.retrievals[0].score)
    }

    @Test
    fun `no claims convert to missing evidence for what was searched`() {
        val evidence = converter.fromPropositions(emptyList(), searched)

        assertEquals(searched, assertInstanceOf(Evidence.Missing::class.java, evidence).searched)
    }

    @Test
    fun `claims with blank text are skipped and the rest are ranked from 1`() {
        val claims = listOf(claim("p-1"), claim("p-2", text = ""), claim("p-3"))

        val evidence = assertInstanceOf(
            Evidence.Recorded::class.java,
            converter.fromPropositions(claims, searched, RetrieverId.KEYWORD),
        )

        assertEquals(listOf("p-1", "p-3"), evidence.items.map { it.citation.propositionId })
        assertEquals(listOf(1, 2), evidence.items.map { it.retrievals.single().rank })
        assertTrue(evidence.items.all { it.retrievals.single().retriever == RetrieverId.KEYWORD })
    }

    @Test
    fun `claims converted without a retriever have no retrievals`() {
        val evidence = assertInstanceOf(
            Evidence.Recorded::class.java,
            converter.fromPropositions(listOf(claim("p-1"), claim("p-2")), searched),
        )

        assertTrue(evidence.items.all { it.retrievals.isEmpty() })
    }

    @Test
    fun `scored hits are ranked from 1 by the vector retriever and keep their scores`() {
        val hits = listOf(
            SimpleSimilaritySearchResult(claim("p-1"), 0.91),
            SimpleSimilaritySearchResult(claim("p-2"), 0.74),
        )

        val evidence = assertInstanceOf(Evidence.Recorded::class.java, converter.fromSimilarityResults(hits, searched))

        assertEquals(listOf(1, 2), evidence.items.map { it.retrievals.single().rank })
        assertTrue(evidence.items.all { it.retrievals.single().retriever == RetrieverId.VECTOR })
        assertEquals(listOf(0.91, 0.74), evidence.items.map { it.score })
    }

    @Test
    fun `no scored hits convert to missing evidence`() {
        assertInstanceOf(Evidence.Missing::class.java, converter.fromSimilarityResults(emptyList(), searched))
    }

    @Test
    fun `the lineage of an observation is recorded with the lineage chunk ids`() {
        val proposition = claim("p-1", grounding = listOf("old-chunk"))
        val lineage = PropositionLineage(
            proposition = proposition,
            provenanceEntries = listOf(provenance),
            groundingChunkIds = listOf("chunk-1", "chunk-2"),
            sources = emptyList(),
            reinforceCount = 0,
            status = proposition.status,
            temporal = null,
        )

        val evidence = assertInstanceOf(Evidence.Recorded::class.java, converter.fromLineage(lineage))

        assertEquals(1, evidence.items.size)
        assertEquals(listOf("chunk-1", "chunk-2"), evidence.items[0].citation.chunkIds)
    }

    @Test
    fun `the lineage of an abstraction is derived from its sources`() {
        val abstraction = claim("p-3", text = "Alice is settled in Berlin.", level = 1, sourceIds = listOf("p-1", "p-2"))
        val lineage = PropositionLineage(
            proposition = abstraction,
            provenanceEntries = emptyList(),
            groundingChunkIds = listOf("chunk-1"),
            sources = listOf(claim("p-1"), claim("p-2")),
            reinforceCount = 0,
            status = abstraction.status,
            temporal = null,
        )

        val derived = assertInstanceOf(Evidence.Derived::class.java, converter.fromLineage(lineage))

        assertEquals(abstraction.text, derived.conclusion)
        assertEquals(DerivationRule.ABSTRACTION, derived.rule)
        assertEquals(abstraction.sourceIds, derived.inputIds)
        assertEquals(2, derived.inputs.size)
        assertEquals("p-3", derived.citation?.propositionId)

        val unloaded = assertInstanceOf(
            Evidence.Derived::class.java,
            converter.fromLineage(lineage.copy(sources = emptyList())),
        )
        assertEquals(abstraction.sourceIds, unloaded.inputIds)
        assertTrue(unloaded.inputs.isEmpty())
    }

    /**
     * Builds a lineage for a claim with no provenance, chunk ids or loaded sources.
     *
     * @param proposition the claim the lineage is for
     * @return the lineage
     */
    private fun lineageOf(proposition: Proposition): PropositionLineage = PropositionLineage(
        proposition = proposition,
        provenanceEntries = emptyList(),
        groundingChunkIds = emptyList(),
        sources = emptyList(),
        reinforceCount = 0,
        status = proposition.status,
        temporal = null,
    )

    @Test
    fun `an abstraction carries the effective confidence and status of its claim`() {
        val abstraction = claim("p-3", status = PropositionStatus.SUPERSEDED, level = 1, sourceIds = listOf("p-1", "p-2"))

        val derived = assertInstanceOf(Evidence.Derived::class.java, converter.fromLineage(lineageOf(abstraction)))

        assertEquals(abstraction.effectiveConfidenceAt(asOf, 2.0), derived.effectiveConfidence)
        assertEquals(0.9, derived.confidence)
        assertEquals(abstraction.status, derived.status)
    }

    @Test
    fun `an invalidated abstraction has zero effective confidence and keeps its stored confidence`() {
        val abstraction = claim("p-3", status = PropositionStatus.SUPERSEDED, level = 1, sourceIds = listOf("p-1", "p-2"))
            .withTemporal(TemporalMetadata(invalidatedAt = asOf))

        val derived = assertInstanceOf(Evidence.Derived::class.java, converter.fromLineage(lineageOf(abstraction)))

        assertEquals(0.0, derived.effectiveConfidence)
        assertEquals(0.9, derived.confidence)
    }

    @Test
    fun `the decay rate multiplier sets the effective confidence of an abstraction`() {
        val abstraction = claim("p-3", level = 1, sourceIds = listOf("p-1", "p-2"))

        val derived = assertInstanceOf(
            Evidence.Derived::class.java,
            converter.withDecayK(4.0).fromLineage(lineageOf(abstraction)),
        )

        assertEquals(abstraction.effectiveConfidenceAt(asOf, 4.0), derived.effectiveConfidence)
        assertNotEquals(abstraction.effectiveConfidenceAt(asOf, 2.0), derived.effectiveConfidence)
    }

    @Test
    fun `the lineage of an abstraction whose source ids are all blank is rejected`() {
        val abstraction = claim("p-3", level = 1, sourceIds = listOf(" "))
        val lineage = PropositionLineage(
            proposition = abstraction,
            provenanceEntries = emptyList(),
            groundingChunkIds = emptyList(),
            sources = emptyList(),
            reinforceCount = 0,
            status = abstraction.status,
            temporal = null,
        )

        assertThrows(IllegalArgumentException::class.java) { converter.fromLineage(lineage) }
    }

    @Test
    fun `a contradiction converts to conflicting evidence with the original first`() {
        val original = claim("p-1", status = PropositionStatus.CONTRADICTED)
        val new = claim("p-2", text = "Alice lives in Paris.")

        val evidence = converter.fromContradiction(
            RevisionResult.Contradicted(original, new, ConflictType.WorldProgression),
        )

        assertEquals(original.id, evidence.items[0].citation.propositionId)
        assertEquals(new.id, evidence.items[1].citation.propositionId)
        assertEquals(ConflictType.WorldProgression, evidence.conflictType)
        assertEquals(original.status, evidence.items[0].status)
    }

    @Test
    fun `a negative or NaN decay rate multiplier is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { converter.withDecayK(-1.0) }
        assertThrows(IllegalArgumentException::class.java) { converter.withDecayK(Double.NaN) }
    }
}

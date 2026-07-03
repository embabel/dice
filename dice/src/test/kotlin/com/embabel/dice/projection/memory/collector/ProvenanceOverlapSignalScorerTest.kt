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
package com.embabel.dice.projection.memory.collector

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.spi.CandidatePair
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ProvenanceOverlapSignalScorerTest {

    private val contextId = ContextId("test-context")

    private fun proposition(text: String, provenanceEntries: List<ProvenanceEntry> = emptyList()): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = emptyList<EntityMention>(),
            confidence = 0.8,
            provenanceEntries = provenanceEntries,
        )

    private fun entry(uri: String): ProvenanceEntry = ProvenanceEntry(locator = UriLocator(uri))

    @Test
    fun `scores full overlap as 1 when both sides cite the same source locator`() {
        val pair = CandidatePair(
            proposition("a", provenanceEntries = listOf(entry("https://example.com/doc"))),
            proposition("b", provenanceEntries = listOf(entry("https://example.com/doc"))),
        )

        val score = ProvenanceOverlapSignalScorer().score(pair, contextId)

        requireNotNull(score)
        assertEquals("provenance-overlap", score.signal)
        assertEquals(1.0, score.score, 1e-9)
        assertEquals(0.5, score.weight, 1e-9)
        assertFalse(score.veto)
    }

    @Test
    fun `scores partial overlap as jaccard of the locator sets`() {
        val pair = CandidatePair(
            proposition("a", provenanceEntries = listOf(entry("https://example.com/doc-1"), entry("https://example.com/doc-2"))),
            proposition("b", provenanceEntries = listOf(entry("https://example.com/doc-1"))),
        )

        val score = ProvenanceOverlapSignalScorer().score(pair, contextId)

        requireNotNull(score)
        assertEquals(0.5, score.score, 1e-9)
    }

    @Test
    fun `abstains when the anchor has no provenance entries`() {
        val pair = CandidatePair(
            proposition("a", provenanceEntries = emptyList()),
            proposition("b", provenanceEntries = listOf(entry("https://example.com/doc"))),
        )

        assertNull(ProvenanceOverlapSignalScorer().score(pair, contextId))
    }

    @Test
    fun `abstains when the member has no provenance entries`() {
        val pair = CandidatePair(
            proposition("a", provenanceEntries = listOf(entry("https://example.com/doc"))),
            proposition("b", provenanceEntries = emptyList()),
        )

        assertNull(ProvenanceOverlapSignalScorer().score(pair, contextId))
    }

    @Test
    fun `uses default weight of 0 5 when constructed without args`() {
        val pair = CandidatePair(
            proposition("a", provenanceEntries = listOf(entry("https://example.com/doc"))),
            proposition("b", provenanceEntries = listOf(entry("https://example.com/doc"))),
        )

        val score = ProvenanceOverlapSignalScorer().score(pair, contextId)

        requireNotNull(score)
        assertEquals(0.5, score.weight, 1e-9)
    }

    @Test
    fun `uses custom weight when provided in constructor`() {
        val pair = CandidatePair(
            proposition("a", provenanceEntries = listOf(entry("https://example.com/doc"))),
            proposition("b", provenanceEntries = listOf(entry("https://example.com/doc"))),
        )

        val score = ProvenanceOverlapSignalScorer(weight = 0.7).score(pair, contextId)

        requireNotNull(score)
        assertEquals(0.7, score.weight, 1e-9)
    }
}

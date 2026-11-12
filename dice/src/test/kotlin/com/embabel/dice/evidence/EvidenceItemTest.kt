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

import com.embabel.dice.proposition.PropositionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class EvidenceItemTest {

    private val citation = Citation.of("prop-42", "ctx-main")

    private val vectorHit = Retrieval.of(RetrieverId.VECTOR).withRank(1).withScore(0.87)

    private val keywordHit = Retrieval.of(RetrieverId.KEYWORD).withRank(3)

    @Test
    fun `blank text is rejected`() {
        listOf("", "   ").forEach { text ->
            assertThrows(IllegalArgumentException::class.java) {
                EvidenceItem.of(text, citation, 0.5)
            }
        }
    }

    @Test
    fun `text is stored exactly as given`() {
        assertEquals("  Alice moved to Berlin.  ", EvidenceItem.of("  Alice moved to Berlin.  ", citation, 0.5).text)
    }

    @Test
    fun `confidence outside zero to one or NaN is rejected`() {
        listOf(-0.1, 1.1, Double.NaN).forEach { confidence ->
            assertThrows(IllegalArgumentException::class.java) {
                EvidenceItem.of("t", citation, confidence)
            }
        }
    }

    @Test
    fun `confidence of zero and one is accepted`() {
        assertEquals(0.0, EvidenceItem.of("t", citation, 0.0).confidence)
        assertEquals(1.0, EvidenceItem.of("t", citation, 1.0).confidence)
    }

    @Test
    fun `effective confidence is checked against the same bounds`() {
        val item = EvidenceItem.of("t", citation, 0.5)
        listOf(-0.1, 1.1, Double.NaN).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                item.withEffectiveConfidence(value)
            }
        }
        assertEquals(0.0, item.withEffectiveConfidence(0.0).effectiveConfidence)
        assertEquals(1.0, item.withEffectiveConfidence(1.0).effectiveConfidence)
    }

    @Test
    fun `NaN and infinite scores are rejected`() {
        val item = EvidenceItem.of("t", citation, 0.5)
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { score ->
            assertThrows(IllegalArgumentException::class.java) {
                item.withScore(score)
            }
        }
    }

    @Test
    fun `negative finite score is accepted`() {
        assertEquals(-2.0, EvidenceItem.of("t", citation, 0.5).withScore(-2.0).score)
    }

    @Test
    fun `derived ids make the item derived`() {
        val derived = EvidenceItem.of("t", citation, 0.5).withDerivedFromIds(listOf("p-1", "p-2"))
        assertTrue(derived.isDerived)
        assertFalse(EvidenceItem.of("t", citation, 0.5).isDerived)
    }

    @Test
    fun `blank derived id is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            EvidenceItem.of("t", citation, 0.5).withDerivedFromIds(listOf("p-1", " "))
        }
    }

    @Test
    fun `derived ids are copied and cannot be modified through the item`() {
        val source = mutableListOf("p-1")
        val item = EvidenceItem.of("t", citation, 0.5).withDerivedFromIds(source)
        source.add("p-2")
        assertEquals(listOf("p-1"), item.derivedFromIds)
        @Suppress("UNCHECKED_CAST")
        val returned = item.derivedFromIds as MutableList<String>
        assertThrows(UnsupportedOperationException::class.java) { returned.add("x") }
    }

    @Test
    fun `two retrievals are kept in the order they were added`() {
        val item = EvidenceItem.of("t", citation, 0.5)
            .withRetrieval(vectorHit)
            .withRetrieval(keywordHit)
        assertEquals(listOf(vectorHit, keywordHit), item.retrievals)
    }

    @Test
    fun `withRetrievals replaces the earlier retrievals`() {
        val item = EvidenceItem.of("t", citation, 0.5)
            .withRetrieval(vectorHit)
            .withRetrievals(listOf(keywordHit))
        assertEquals(listOf(keywordHit), item.retrievals)
    }

    @Test
    fun `retrieval list passed to withRetrievals is copied and cannot be modified through the item`() {
        val source = mutableListOf(vectorHit)
        val item = EvidenceItem.of("t", citation, 0.5).withRetrievals(source)
        source.add(keywordHit)
        assertEquals(listOf(vectorHit), item.retrievals)
        @Suppress("UNCHECKED_CAST")
        val returned = item.retrievals as MutableList<Retrieval>
        assertThrows(UnsupportedOperationException::class.java) { returned.add(keywordHit) }
    }

    @Test
    fun `block that sets every optional value matches the chain with the same arguments`() {
        val recordedAt = Instant.parse("2024-01-01T00:00:00Z")
        val revisedAt = Instant.parse("2024-06-01T00:00:00Z")
        val fromBlock = EvidenceItem("t", citation, 0.8) {
            effectiveConfidence = 0.6
            status = PropositionStatus.SUPERSEDED
            this.recordedAt = recordedAt
            this.revisedAt = revisedAt
            derivedFromIds = listOf("p-1", "p-2")
            score = 0.42
            retrieval(vectorHit)
            retrieval(keywordHit)
        }
        val fromChain = EvidenceItem.of("t", citation, 0.8)
            .withEffectiveConfidence(0.6)
            .withStatus(PropositionStatus.SUPERSEDED)
            .withRecordedAt(recordedAt)
            .withRevisedAt(revisedAt)
            .withDerivedFromIds(listOf("p-1", "p-2"))
            .withScore(0.42)
            .withRetrieval(vectorHit)
            .withRetrieval(keywordHit)
        assertEquals(fromChain, fromBlock)
        assertEquals(fromChain.hashCode(), fromBlock.hashCode())
    }

    @Test
    fun `item built without a block equals the factory result`() {
        assertEquals(EvidenceItem.of("t", citation, 0.8), EvidenceItem("t", citation, 0.8))
    }

    @Test
    fun `block that sets effective confidence above one is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            EvidenceItem("t", citation, 0.8) { effectiveConfidence = 2.0 }
        }
    }

    @Test
    fun `items built the same way are equal with the same hash code`() {
        val first = EvidenceItem.of("t", citation, 0.8).withScore(0.5).withRetrieval(vectorHit)
        val second = EvidenceItem.of("t", citation, 0.8).withScore(0.5).withRetrieval(vectorHit)
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    @Test
    fun `items that differ only in status are not equal`() {
        assertNotEquals(
            EvidenceItem.of("t", citation, 0.8).withStatus(PropositionStatus.ACTIVE),
            EvidenceItem.of("t", citation, 0.8).withStatus(PropositionStatus.CONTRADICTED),
        )
    }

    @Test
    fun `scores of zero and negative zero make items unequal`() {
        assertNotEquals(
            EvidenceItem.of("t", citation, 0.8).withScore(0.0),
            EvidenceItem.of("t", citation, 0.8).withScore(-0.0),
        )
    }
}

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
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EvidenceOriginTest {

    private val provenance = ProvenanceEntry(UriLocator("https://example.com/notes"))

    @Test
    fun `retriever id rejects blank, spaced, and leading-hyphen names`() {
        listOf("", "  ", "my retriever", "-x").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { RetrieverId.of(name) }
        }
    }

    @Test
    fun `retriever id trims and lower-cases its name`() {
        assertEquals(RetrieverId.VECTOR, RetrieverId.of(" Vector "))
    }

    @Test
    fun `retrieval rejects a rank of zero`() {
        assertThrows(IllegalArgumentException::class.java) {
            Retrieval.of(RetrieverId.VECTOR).withRank(0)
        }
    }

    @Test
    fun `retrieval accepts a rank of one`() {
        assertEquals(1, Retrieval.of(RetrieverId.VECTOR).withRank(1).rank)
    }

    @Test
    fun `retrieval rejects NaN and infinite scores`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { score ->
            assertThrows(IllegalArgumentException::class.java) {
                Retrieval.of(RetrieverId.KEYWORD).withScore(score)
            }
        }
    }

    @Test
    fun `retrieval accepts a finite negative score`() {
        assertEquals(-2.5, Retrieval.of(RetrieverId.KEYWORD).withScore(-2.5).score)
    }

    @Test
    fun `retrieval rejects negative hops`() {
        assertThrows(IllegalArgumentException::class.java) {
            Retrieval.of(RetrieverId.GRAPH_WALK).withHops(-1)
        }
    }

    @Test
    fun `retrieval accepts zero hops`() {
        assertEquals(0, Retrieval.of(RetrieverId.GRAPH_WALK).withHops(0).hops)
    }

    @Test
    fun `entity path sets hops to its step count`() {
        val retrieval = Retrieval.of(RetrieverId.GRAPH_WALK).withEntityPath(listOf("a", "b", "c"))
        assertEquals(2, retrieval.hops)
    }

    @Test
    fun `entity path rejects an empty list`() {
        assertThrows(IllegalArgumentException::class.java) {
            Retrieval.of(RetrieverId.GRAPH_WALK).withEntityPath(emptyList())
        }
    }

    @Test
    fun `entity path rejects a blank entity id`() {
        assertThrows(IllegalArgumentException::class.java) {
            Retrieval.of(RetrieverId.GRAPH_WALK).withEntityPath(listOf("a", " "))
        }
    }

    @Test
    fun `hops without a path leaves the entity path empty`() {
        val retrieval = Retrieval.of(RetrieverId.GRAPH_WALK).withHops(3)
        assertEquals(3, retrieval.hops)
        assertTrue(retrieval.entityPath.isEmpty())
    }

    @Test
    fun `hops that disagree with a non-empty entity path are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Retrieval.of(RetrieverId.GRAPH_WALK).withEntityPath(listOf("a", "b")).withHops(5)
        }
    }

    @Test
    fun `hops that match a non-empty entity path are accepted`() {
        val retrieval = Retrieval.of(RetrieverId.GRAPH_WALK).withEntityPath(listOf("a", "b")).withHops(1)
        assertEquals(1, retrieval.hops)
    }

    @Test
    fun `entity path ignores later changes to the caller's list`() {
        val path = mutableListOf("a", "b")
        val retrieval = Retrieval.of(RetrieverId.GRAPH_WALK).withEntityPath(path)
        path.add("c")
        assertEquals(listOf("a", "b"), retrieval.entityPath)
    }

    @Test
    fun `entity path cannot be modified through the returned list`() {
        val retrieval = Retrieval.of(RetrieverId.GRAPH_WALK).withEntityPath(listOf("a", "b"))
        @Suppress("UNCHECKED_CAST")
        val returned = retrieval.entityPath as MutableList<String>
        assertThrows(UnsupportedOperationException::class.java) { returned.add("x") }
    }

    @Test
    fun `citation rejects a blank proposition id`() {
        assertThrows(IllegalArgumentException::class.java) { Citation.of("", "ctx") }
    }

    @Test
    fun `citation rejects a blank context id`() {
        assertThrows(IllegalArgumentException::class.java) { Citation.of("p", " ") }
    }

    @Test
    fun `string and ContextId entries build equal citations`() {
        val fromString = Citation.of("p", "ctx")
        assertEquals(fromString, Citation.of("p", ContextId("ctx")))
        assertEquals("ctx", fromString.contextIdValue)
    }

    @Test
    fun `chunk ids are copied and cannot be changed through the returned list`() {
        val chunks = mutableListOf("c1")
        val citation = Citation.of("p", "ctx").withChunkIds(chunks)
        chunks.add("c2")
        assertEquals(listOf("c1"), citation.chunkIds)
        @Suppress("UNCHECKED_CAST")
        val returned = citation.chunkIds as MutableList<String>
        assertThrows(UnsupportedOperationException::class.java) { returned.add("x") }
    }

    @Test
    fun `chunk ids reject a blank entry`() {
        assertThrows(IllegalArgumentException::class.java) {
            Citation.of("p", "ctx").withChunkIds(listOf("c1", " "))
        }
    }

    @Test
    fun `provenance is copied and cannot be changed through the returned list`() {
        val entries = mutableListOf(provenance)
        val citation = Citation.of("p", "ctx").withProvenance(entries)
        entries.clear()
        assertEquals(listOf(provenance), citation.provenance)
        @Suppress("UNCHECKED_CAST")
        val returned = citation.provenance as MutableList<ProvenanceEntry>
        assertThrows(UnsupportedOperationException::class.java) { returned.add(provenance) }
    }

    @Test
    fun `uri rejects a blank value`() {
        assertThrows(IllegalArgumentException::class.java) {
            Citation.of("p", "ctx").withUri(" ")
        }
    }

    @Test
    fun `values built the same way are equal and values differing in one property are not`() {
        assertEquals(RetrieverId.of("vector"), RetrieverId.VECTOR)
        assertEquals(RetrieverId.of("vector").hashCode(), RetrieverId.VECTOR.hashCode())
        assertNotEquals(RetrieverId.VECTOR, RetrieverId.KEYWORD)

        val retrieval = Retrieval.of(RetrieverId.VECTOR).withRank(2).withScore(0.5)
        assertEquals(retrieval, Retrieval.of(RetrieverId.VECTOR).withRank(2).withScore(0.5))
        assertEquals(
            retrieval.hashCode(),
            Retrieval.of(RetrieverId.VECTOR).withRank(2).withScore(0.5).hashCode(),
        )
        assertNotEquals(retrieval, Retrieval.of(RetrieverId.VECTOR).withRank(3).withScore(0.5))
        assertNotEquals(
            Retrieval.of(RetrieverId.KEYWORD).withScore(0.0),
            Retrieval.of(RetrieverId.KEYWORD).withScore(-0.0),
        )

        val citation = Citation.of("p", "ctx").withChunkIds(listOf("c1"))
        assertEquals(citation, Citation.of("p", "ctx").withChunkIds(listOf("c1")))
        assertEquals(citation.hashCode(), Citation.of("p", "ctx").withChunkIds(listOf("c1")).hashCode())
        assertNotEquals(citation, Citation.of("p", "ctx").withChunkIds(listOf("c2")))
        assertNotEquals(citation, Citation.of("q", "ctx").withChunkIds(listOf("c1")))
        assertNotEquals(citation, Citation.of("p", "other").withChunkIds(listOf("c1")))
    }
}

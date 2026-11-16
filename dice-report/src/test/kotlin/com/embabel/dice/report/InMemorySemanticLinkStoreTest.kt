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
package com.embabel.dice.report

import com.embabel.agent.core.ContextId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

internal class InMemorySemanticLinkStoreTest {

    private val contextA = ContextId("context-a")
    private val contextB = ContextId("context-b")

    @Test
    fun `record stores a candidate and refreshes evidence without resurrecting a rejection`() {
        val store = InMemorySemanticLinkStore()
        val candidate = link(confidence = 0.4, rationale = "initial")

        assertEquals(ReviewStatus.CANDIDATE, store.record(contextA, candidate).reviewStatus)

        val id = store.idOf(candidate)
        store.updateReviewStatus(contextA, id, ReviewStatus.REJECTED)
        val refreshed = store.record(
            contextA,
            link(confidence = 0.9, rationale = "refreshed"),
        )

        assertEquals(ReviewStatus.REJECTED, refreshed.reviewStatus)
        assertEquals(0.9, refreshed.confidence)
        assertEquals("refreshed", refreshed.rationale)
        assertEquals(listOf(refreshed), store.find(contextA))
    }

    @Test
    fun `record preserves an accepted review decision`() {
        val store = InMemorySemanticLinkStore()
        val candidate = link()
        val id = store.idOf(candidate)
        store.record(contextA, candidate)
        store.updateReviewStatus(contextA, id, ReviewStatus.ACCEPTED)

        val refreshed = store.record(contextA, link(confidence = 0.8))

        assertEquals(ReviewStatus.ACCEPTED, refreshed.reviewStatus)
        assertEquals(0.8, refreshed.confidence)
    }

    @Test
    fun `find filters by review status and isolates contexts`() {
        val store = InMemorySemanticLinkStore()
        val linkA = link()
        val linkB = link(source = "entity-c", target = "entity-d")
        store.record(contextA, linkA)
        store.record(contextA, linkB)
        store.record(contextB, linkA.copy(reviewStatus = ReviewStatus.ACCEPTED))
        store.updateReviewStatus(contextA, store.idOf(linkB), ReviewStatus.REJECTED)

        assertEquals(listOf(linkA), store.find(contextA, ReviewStatus.CANDIDATE))
        assertEquals(
            listOf(ReviewStatus.CANDIDATE, ReviewStatus.REJECTED),
            store.find(contextA).map(SemanticLink::reviewStatus),
        )
        assertEquals(listOf(ReviewStatus.ACCEPTED), store.find(contextB).map(SemanticLink::reviewStatus))
    }

    @Test
    fun `update returns null for unknown ids and cannot cross context boundaries`() {
        val store = InMemorySemanticLinkStore()
        val candidate = link()
        val id = store.idOf(candidate)
        store.record(contextA, candidate)

        assertNull(store.updateReviewStatus(contextA, "missing", ReviewStatus.REJECTED))
        assertNull(store.updateReviewStatus(contextB, id, ReviewStatus.REJECTED))
        assertEquals(ReviewStatus.CANDIDATE, store.find(contextA).single().reviewStatus)
    }

    @Test
    fun `reversed endpoints share one natural key and one stored row`() {
        val store = InMemorySemanticLinkStore()
        val forward = link(source = "entity-a", target = "entity-b", rationale = "forward")
        val reversed = link(source = "entity-b", target = "entity-a", rationale = "reversed")

        assertEquals(store.idOf(forward), store.idOf(reversed))
        store.record(contextA, forward)
        store.record(contextA, reversed)

        assertEquals(1, store.find(contextA).size)
        assertEquals("reversed", store.find(contextA).single().rationale)
    }

    private fun link(
        source: String = "entity-a",
        target: String = "entity-b",
        confidence: Double = 0.5,
        rationale: String? = null,
    ): SemanticLink = SemanticLink(
        sourceEntityId = source,
        targetEntityId = target,
        connectingEntityIds = listOf("bridge"),
        sourcePropositionIds = listOf("proposition-1"),
        confidence = confidence,
        rationale = rationale,
    )
}

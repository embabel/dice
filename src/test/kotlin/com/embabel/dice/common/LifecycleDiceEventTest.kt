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
package com.embabel.dice.common

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Asserts the lifecycle transition events are defined as first-class [DiceEvent]
 * subtypes carrying the full proposition snapshot, so they can flow through the
 * existing single `DiceEventListener` surface (no parallel listener type).
 */
class LifecycleDiceEventTest {

    private val contextId = ContextId("test-context")

    private fun prop(status: PropositionStatus = PropositionStatus.ACTIVE) = Proposition(
        contextId = contextId,
        text = "a fact",
        mentions = emptyList(),
        confidence = 0.7,
        status = status,
    )

    @Test
    fun `status changed event is a DiceEvent carrying both statuses and the snapshot`() {
        val p = prop(PropositionStatus.STALE)
        val event = PropositionStatusChanged(
            proposition = p,
            previousStatus = PropositionStatus.ACTIVE,
            newStatus = PropositionStatus.STALE,
            reason = "decayed below threshold",
        )

        assertTrue(event is DiceEvent)
        assertSame(p, event.proposition)
        assertEquals(PropositionStatus.ACTIVE, event.previousStatus)
        assertEquals(PropositionStatus.STALE, event.newStatus)
        assertEquals("decayed below threshold", event.reason)
    }

    @Test
    fun `status changed reason is optional`() {
        val event = PropositionStatusChanged(
            proposition = prop(),
            previousStatus = PropositionStatus.ACTIVE,
            newStatus = PropositionStatus.SUPERSEDED,
        )
        assertEquals(null, event.reason)
    }

    @Test
    fun `pinned event is a DiceEvent carrying the snapshot`() {
        val p = prop()
        val event = PropositionPinned(proposition = p)
        assertTrue(event is DiceEvent)
        assertSame(p, event.proposition)
    }

    @Test
    fun `unpinned event is a DiceEvent carrying the snapshot`() {
        val p = prop()
        val event = PropositionUnpinned(proposition = p)
        assertTrue(event is DiceEvent)
        assertSame(p, event.proposition)
    }

    @Test
    fun `lifecycle events dispatch through a single DiceEventListener surface`() {
        // A consumer-style listener over the common DiceEvent type must be able to
        // receive every lifecycle subtype without a parallel listener interface.
        val received = mutableListOf<DiceEvent>()
        val listener: (DiceEvent) -> Unit = { received.add(it) }

        val p = prop()
        listener(PropositionStatusChanged(p, PropositionStatus.ACTIVE, PropositionStatus.STALE))
        listener(PropositionPinned(p))
        listener(PropositionUnpinned(p))

        assertEquals(3, received.size)
        assertTrue(received[0] is PropositionStatusChanged)
        assertTrue(received[1] is PropositionPinned)
        assertTrue(received[2] is PropositionUnpinned)
    }
}

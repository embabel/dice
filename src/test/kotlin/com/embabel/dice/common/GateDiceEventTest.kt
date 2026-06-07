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
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract for the routing-decision [DiceEvent] subtypes the gate layer emits.
 *
 * Each carries the full [Proposition] (not an id) plus a developer-authored `reason`,
 * mirroring the existing event convention in [DiceEvent].
 */
class GateDiceEventTest {

    private val contextId = ContextId("test-context")

    private fun proposition(text: String = "Jim is an expert in GOAP"): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = listOf(
                EntityMention(span = "Jim", type = "Person", role = MentionRole.SUBJECT),
            ),
            confidence = 0.9,
        )

    @Test
    fun `PropositionRejected is a DiceEvent carrying the proposition and reason`() {
        val p = proposition()
        val event = PropositionRejected(p, "low confidence")

        assertTrue(event is DiceEvent)
        assertNotNull(event.timestamp)
        assertSame(p, event.proposition)
        assertEquals("low confidence", event.reason)
    }

    @Test
    fun `PropositionRoutedToReview is a DiceEvent carrying the proposition and reason`() {
        val p = proposition()
        val event = PropositionRoutedToReview(p, "merge candidate")

        assertTrue(event is DiceEvent)
        assertNotNull(event.timestamp)
        assertSame(p, event.proposition)
        assertEquals("merge candidate", event.reason)
    }

    @Test
    fun `PropositionProjectionSkipped is a DiceEvent carrying the proposition and reason`() {
        val p = proposition()
        val event = PropositionProjectionSkipped(p, "low confidence")

        assertTrue(event is DiceEvent)
        assertNotNull(event.timestamp)
        assertSame(p, event.proposition)
        assertEquals("low confidence", event.reason)
    }

    @Test
    fun `gate-decision events are deliverable through a DiceEventListener`() {
        val received = mutableListOf<DiceEvent>()
        val listener = DiceEventListener { received += it }
        val p = proposition()

        listener.onEvent(PropositionRejected(p, "low confidence"))
        listener.onEvent(PropositionRoutedToReview(p, "merge candidate"))
        listener.onEvent(PropositionProjectionSkipped(p, "low confidence"))

        assertEquals(3, received.size)
        assertTrue(received[0] is PropositionRejected)
        assertTrue(received[1] is PropositionRoutedToReview)
        assertTrue(received[2] is PropositionProjectionSkipped)
    }
}

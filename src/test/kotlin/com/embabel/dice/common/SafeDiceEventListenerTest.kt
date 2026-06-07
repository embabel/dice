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
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Contract for the utility listeners:
 * [SafeDiceEventListener] (swallows + logs throwables), [CompositeDiceEventListener]
 * (fan-out), and [LoggingDiceEventListener] (no-throw logging).
 */
class SafeDiceEventListenerTest {

    private val contextId = ContextId("test-context")

    private fun event(): DiceEvent = PropositionPersisted(
        Proposition(
            contextId = contextId,
            text = "Jim is an expert in GOAP",
            mentions = listOf(EntityMention(span = "Jim", type = "Person", role = MentionRole.SUBJECT)),
            confidence = 0.9,
        )
    )

    private val throwing = DiceEventListener { error("listener boom") }

    @Test
    fun `SafeDiceEventListener does not propagate a throwing delegate`() {
        val safe = SafeDiceEventListener(throwing)

        assertDoesNotThrow {
            safe.onEvent(event())
        }
    }

    @Test
    fun `CompositeDiceEventListener still delivers to later listeners after one throws`() {
        val recording = RecordingDiceEventListener()
        // The first listener throws; a robust composite must still reach the recording one.
        val composite = CompositeDiceEventListener(listOf(SafeDiceEventListener(throwing), recording))

        composite.onEvent(event())

        assertEquals(1, recording.count(), "Recording listener should receive the event despite an earlier throw")
    }

    @Test
    fun `CompositeDiceEventListener isolates a raw throwing listener without caller pre-wrapping`() {
        val recording = RecordingDiceEventListener()
        // The caller supplies a RAW throwing listener (NOT pre-wrapped in SafeDiceEventListener).
        // The composite itself must apply throw-isolation so the later listener still receives the event.
        val composite = CompositeDiceEventListener(listOf(throwing, recording))

        assertDoesNotThrow { composite.onEvent(event()) }

        assertEquals(
            1,
            recording.count(),
            "Composite must internally isolate a raw throwing listener so later listeners still receive the event",
        )
    }

    @Test
    fun `LoggingDiceEventListener onEvent does not throw`() {
        val logging = LoggingDiceEventListener()

        assertDoesNotThrow {
            logging.onEvent(event())
        }
    }
}

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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * A reinforcement records a content revision only when it changes the confidence.
 */
class ReinforceRevisionRecordTest {

    private val old = Instant.parse("2020-01-01T00:00:00Z")
    private val revisedAt = Instant.parse("2020-06-01T00:00:00Z")
    private val capped = ReinforcementRule(confidence = { e, n -> (e.confidence + n.confidence).coerceAtMost(1.0) })

    private fun proposition(confidence: Double) = Proposition(
        contextId = ContextId("test-context"),
        text = "Alice works at Acme",
        mentions = emptyList(),
        confidence = confidence,
        created = old,
        contentRevised = old,
        metadataRevised = old,
        lastAccessed = old,
    )

    @Test
    fun `a reinforcement that leaves the confidence alone records no revision`() {
        val reinforced = reinforce(proposition(1.0), proposition(0.5), capped)

        assertTrue(reinforced.contentRevised.isAfter(old))
        assertNull(reinforced.lastContentRevision)
    }

    @Test
    fun `a later reinforcement that leaves the confidence alone keeps the earlier revision time`() {
        val revised = proposition(1.0).withMetadataValue(DiceMetadataKeys.CONTENT_REVISED, revisedAt.toString())

        val reinforced = reinforce(revised, proposition(0.5), capped)

        assertTrue(reinforced.contentRevised.isAfter(revisedAt))
        assertEquals(revisedAt, reinforced.lastContentRevision)
    }

    @Test
    fun `a reinforcement that raises the confidence records a revision at the stamped time`() {
        val reinforced = reinforce(proposition(0.4), proposition(0.5), capped)

        assertEquals(0.9, reinforced.confidence, 1e-9)
        assertEquals(reinforced.contentRevised, reinforced.lastContentRevision)
    }
}

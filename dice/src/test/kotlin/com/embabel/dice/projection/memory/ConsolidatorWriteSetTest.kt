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
package com.embabel.dice.projection.memory

import com.embabel.agent.core.ContextId
import com.embabel.dice.operations.consolidation.ConsolidationPassResult
import com.embabel.dice.operations.consolidation.SessionConsolidationPass
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the consolidator hands back for saving: only stored propositions that changed, and one
 * merge per stored proposition however many session propositions merge into it.
 */
class ConsolidatorWriteSetTest {

    private fun proposition(text: String, grounding: List<String>, confidence: Double = 0.6) = Proposition(
        contextId = ContextId("test-context"),
        text = text,
        mentions = listOf(EntityMention("Alice", "Person", resolvedId = "alice")),
        confidence = confidence,
        grounding = grounding,
    )

    @Test
    fun `a session proposition the stored one already holds is not reported as reinforced`() {
        val stored = proposition("Alice works at Acme", listOf("g1"))

        val result = DefaultMemoryConsolidator().consolidate(listOf(stored.copy(id = "session")), listOf(stored))

        assertEquals(emptyList<Proposition>(), result.reinforced)
        assertEquals(0, result.storedCount)
    }

    @Test
    fun `two session propositions that merge into one stored proposition make one merge`() {
        val stored = proposition("Alice works at Acme", listOf("g0"))
        // Each scores 0.86 against the stored text: enough to merge, not enough to reinforce.
        val first = proposition("Alice works at Acme Corp", listOf("g1"), confidence = 0.3)
        val second = proposition("Alice works at Acme Inc", listOf("g2"), confidence = 0.4)

        val result = DefaultMemoryConsolidator().consolidate(listOf(first, second), listOf(stored))

        val merge = result.merged.single()
        assertEquals(listOf(stored, first, second), merge.sources)
        assertTrue(merge.result.grounding.containsAll(listOf("g0", "g1", "g2")), "grounding: ${merge.result.grounding}")
        assertEquals(listOf(0.6, 0.3, 0.4).average(), merge.result.confidence, 1e-9)
    }

    @Test
    fun `a session consolidation pass that only replays stored evidence changes nothing`() {
        val stored = proposition("Alice works at Acme", listOf("g1"))

        val result = SessionConsolidationPass(DefaultMemoryConsolidator(), listOf(stored.copy(id = "session")))
            .run(ContextId("test-context"), listOf(stored))

        assertInstanceOf(ConsolidationPassResult.NoOp::class.java, result)
    }
}

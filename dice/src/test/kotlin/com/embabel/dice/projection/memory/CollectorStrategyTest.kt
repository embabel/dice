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
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.spi.MarkReason
import com.embabel.dice.spi.PropositionMark
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class CollectorStrategyTest {

    private val contextId = ContextId("test-context")
    private fun proposition(text: String = "p"): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = emptyList(),
            confidence = 0.8,
            decay = 0.1,
        )

    @Test
    fun `MarkReason cases expose stable machine keys`() {
        assertEquals("stale", MarkReason.Stale.key)
        assertEquals("duplicate", MarkReason.Duplicate("survivor-1").key)
        assertEquals("survivor-1", MarkReason.Duplicate("survivor-1").survivorId)
        val custom = MarkReason.Custom("k", "desc")
        assertEquals("k", custom.key)
        assertEquals("desc", custom.description)
    }

    @Test
    fun `MarkReason is a sealed family usable in exhaustive when`() {
        val reasons: List<MarkReason> = listOf(
            MarkReason.Stale,
            MarkReason.Duplicate("s"),
            MarkReason.Custom("c", "d"),
        )
        reasons.forEach { reason ->
            val label: String = when (reason) {
                is MarkReason.Stale -> reason.key
                is MarkReason.Duplicate -> reason.key
                is MarkReason.Custom -> reason.key
            }
            assertTrue(label.isNotBlank())
        }
    }

    @Test
    fun `PropositionMark defaults at to now and carries fields`() {
        val before = Instant.now()
        val mark = PropositionMark("p1", MarkReason.Stale, "decay")
        assertEquals("p1", mark.propositionId)
        assertEquals(MarkReason.Stale, mark.reason)
        assertEquals("decay", mark.strategyName)
        assertTrue(!mark.at.isBefore(before))
    }

    @Test
    fun `PropositionMark rejects blank propositionId`() {
        assertThrows(IllegalArgumentException::class.java) {
            PropositionMark("  ", MarkReason.Stale, "decay")
        }
    }

    @Test
    fun `CollectorStrategy mark returns marks for candidates`() {
        val repository = mockk<PropositionRepository>(relaxed = true)
        val strategy = CollectorStrategy { candidates, _, _ ->
            candidates.map { PropositionMark(it.id, MarkReason.Stale, "test") }
        }
        val candidates = listOf(proposition("a"), proposition("b"))
        val marks = strategy.mark(candidates, repository, contextId)
        assertEquals(2, marks.size)
        assertTrue(marks.all { it.reason == MarkReason.Stale })
    }

    @Test
    fun `a Custom mark reason carries its consumer key and description`() {
        val reason = MarkReason.Custom(key = "schema-version", description = "extracted under an old schema")
        assertEquals("schema-version", reason.key)
        assertEquals("extracted under an old schema", reason.description)

        // A custom reason flows through a mark like the shipped reasons do.
        val mark = PropositionMark(propositionId = "p1", reason = reason, strategyName = "custom")
        assertEquals("schema-version", mark.reason.key)
    }
}

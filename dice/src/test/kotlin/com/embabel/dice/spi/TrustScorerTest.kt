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
package com.embabel.dice.spi

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private val testContextId = ContextId("test-context")

class TrustScorerTest {

    @Test
    fun `neutral scorer returns 1 point 0 with no optional inputs`() {
        val prop = createProposition("Alice is a software engineer")

        val score = NeutralTrustScorer.score(prop)

        assertEquals(1.0, score, 0.0)
    }

    @Test
    fun `neutral scorer ignores authority tier and conflict type and still returns 1 point 0`() {
        val prop = createProposition("Alice prefers Kotlin")

        val score = NeutralTrustScorer.score(
            prop,
            authorityTier = AuthorityTier.PRIMARY,
            conflictType = ConflictType.Contradiction,
        )

        assertEquals(1.0, score, 0.0)
    }

    @Test
    fun `neutral scorer returns 1 point 0 across a range of authority tiers`() {
        val prop = createProposition("Alice works somewhere")

        AuthorityTier.entries.forEach { tier ->
            val score = NeutralTrustScorer.score(prop, authorityTier = tier)
            assertEquals(1.0, score, 0.0, "Neutral scorer must ignore tier $tier")
        }
    }

    private fun createProposition(text: String) = Proposition(
        contextId = testContextId,
        text = text,
        mentions = emptyList(),
        confidence = 0.8,
    )
}

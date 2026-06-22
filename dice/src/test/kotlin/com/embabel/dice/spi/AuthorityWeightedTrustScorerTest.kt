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

class AuthorityWeightedTrustScorerTest {

    private val scorer = AuthorityWeightedTrustScorer()

    @Test
    fun `default weights score more authoritative tiers higher`() {
        val prop = createProposition("Alice is a software engineer")

        assertEquals(0.9, scorer.score(prop, AuthorityTier.PRIMARY), 0.0)
        assertEquals(0.75, scorer.score(prop, AuthorityTier.SECONDARY), 0.0)
        assertEquals(0.6, scorer.score(prop, AuthorityTier.DERIVED), 0.0)
        assertEquals(0.5, scorer.score(prop, AuthorityTier.UNKNOWN), 0.0)
    }

    @Test
    fun `a null tier falls back to the unknown score`() {
        val prop = createProposition("Alice works somewhere")

        assertEquals(0.5, scorer.score(prop), 0.0)
    }

    @Test
    fun `custom weights are honoured`() {
        val prop = createProposition("Alice prefers Kotlin")
        val custom = AuthorityWeightedTrustScorer(
            weights = mapOf(AuthorityTier.PRIMARY to 1.0),
            unknownScore = 0.1,
        )

        assertEquals(1.0, custom.score(prop, AuthorityTier.PRIMARY), 0.0)
        // DERIVED is absent from the custom map, so it falls back to the unknown score.
        assertEquals(0.1, custom.score(prop, AuthorityTier.DERIVED), 0.0)
    }

    @Test
    fun `out of range weights are clamped to the unit interval`() {
        val prop = createProposition("Alice is real")
        val custom = AuthorityWeightedTrustScorer(
            weights = mapOf(AuthorityTier.PRIMARY to 5.0, AuthorityTier.UNKNOWN to -3.0),
        )

        assertEquals(1.0, custom.score(prop, AuthorityTier.PRIMARY), 0.0)
        assertEquals(0.0, custom.score(prop, AuthorityTier.UNKNOWN), 0.0)
    }

    private fun createProposition(text: String) = Proposition(
        contextId = testContextId,
        text = text,
        mentions = emptyList(),
        confidence = 0.8,
    )
}

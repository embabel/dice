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
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Edge cases of revising within one run: an abstraction whose only source is the stored
 * proposition, and a deferred classification against a proposition the run has since contradicted.
 */
class InRunEdgeCaseTest {

    private val contextId = ContextId("in-run")

    @Test
    fun `an abstraction whose only source is the stored proposition leaves it unchanged`() {
        val existing = Proposition(contextId = contextId, text = "Alice knows Bob", mentions = emptyList(), confidence = 0.8)
        val evidence = Proposition(
            contextId = contextId,
            text = "Alice and Bob are acquainted",
            mentions = emptyList(),
            confidence = 0.8,
            level = 1,
            sourceIds = listOf(existing.id),
        )

        assertSame(existing, reinforce(existing, evidence, ReinforcementRule(confidence = { e, _ -> e.confidence })))
    }

    @Test
    fun `a deferred classification does not reinforce a proposition the run contradicted`() {
        val repository = TestPropositionRepository()
        val stored = Proposition(contextId = contextId, text = "Alice works at Acme", mentions = emptyList(), confidence = 0.9)
        repository.save(stored)
        val view = InRunPropositions.over(repository)
        val contradicting = Proposition(contextId = contextId, text = "Alice left Acme", mentions = emptyList(), confidence = 0.9)
        view.record(
            RevisionResult.Contradicted(stored.copy(confidence = 0.27, status = PropositionStatus.CONTRADICTED), contradicting),
        )
        val similar = Proposition(
            contextId = contextId,
            text = "Alice is employed by Acme",
            mentions = emptyList(),
            confidence = 0.9,
            grounding = listOf("g1"),
        )

        val result = LlmPropositionReviser(llmOptions = io.mockk.mockk(), ai = io.mockk.mockk()).classifiedToResult(
            similar,
            listOf(ClassifiedProposition(stored, PropositionRelation.SIMILAR, 0.95)),
            view,
        )

        assertTrue(result is RevisionResult.New, "result: $result")
    }
}

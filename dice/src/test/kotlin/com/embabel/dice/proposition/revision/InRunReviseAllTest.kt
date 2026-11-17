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
import com.embabel.dice.proposition.PropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When two propositions in one `reviseAll` call back up the same stored proposition, the second
 * one is revised against the copy the first one produced, so neither one's evidence is lost.
 */
class InRunReviseAllTest {

    private val contextId = ContextId("in-run")

    private fun proposition(text: String, grounding: List<String>) = Proposition(
        contextId = contextId,
        text = text,
        mentions = emptyList(),
        confidence = 0.8,
        grounding = grounding,
    )

    @Test
    fun `the llm reviser folds two auto-merges of one stored proposition in order`() {
        val repository = TestPropositionRepository(defaultScore = 0.97)
        val stored = proposition("Alice is a software engineer", listOf("g0"))
        repository.save(stored)

        val results = LlmPropositionReviser(llmOptions = io.mockk.mockk(), ai = io.mockk.mockk()).reviseAll(
            listOf(
                proposition("Alice works as a software engineer", listOf("g1")),
                proposition("Alice writes software for a living", listOf("g2")),
            ),
            repository,
        )

        val (first, second) = results.map { it as RevisionResult.Merged }
        assertEquals(first.revised, second.original)
        assertEquals(stored.reinforceCount + 2, second.revised.reinforceCount)
        assertTrue(second.revised.grounding.containsAll(listOf("g0", "g1", "g2")), "grounding: ${second.revised.grounding}")
    }

    @Test
    fun `the default reviseAll revises each proposition against the previous result`() {
        val repository = TestPropositionRepository()
        val stored = proposition("Alice is a software engineer", listOf("g0"))
        repository.save(stored)
        val reviser = object : PropositionReviser {
            override fun revise(newProposition: Proposition, repository: PropositionRepository): RevisionResult {
                val original = repository.findById(stored.id)!!
                return RevisionResult.Reinforced(original, original.absorbEvidence(newProposition))
            }

            override fun classify(newProposition: Proposition, candidates: List<Proposition>) = emptyList<ClassifiedProposition>()
        }

        val results = reviser.reviseAll(
            listOf(proposition("one", listOf("g1")), proposition("two", listOf("g2"))),
            repository,
        )

        val (first, second) = results.map { it as RevisionResult.Reinforced }
        assertEquals(first.revised, second.original)
        assertEquals(stored.reinforceCount + 2, second.revised.reinforceCount)
    }
}

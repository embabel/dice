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
package com.embabel.dice.pipeline

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.revision.RevisionResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the `additionalGrounding` enrichment behind
 * [PropositionPipeline.processOnce] / `rememberText`: extra source-record ids
 * are merged into each persisted proposition's `grounding`, on top of the
 * primary chunk sourceId, without disturbing anything when empty.
 */
class AdditionalGroundingTest {

    private val ctx = ContextId("test-context")

    private fun prop(text: String, grounding: List<String> = listOf("chat-recovery:c1")) =
        Proposition(contextId = ctx, text = text, mentions = emptyList(), confidence = 0.9, grounding = grounding)

    @Test
    fun `empty additional grounding is a no-op on a RevisionResult`() {
        val r = RevisionResult.New(prop("x"))
        assertEquals(r, r.withAdditionalGrounding(emptyList()))
    }

    @Test
    fun `New revision gains the extra grounding ids, keeping the original`() {
        val r = RevisionResult.New(prop("x")).withAdditionalGrounding(listOf("email:t1", "hubspot:42"))
        val g = (r as RevisionResult.New).proposition.grounding
        assertTrue(g.containsAll(listOf("chat-recovery:c1", "email:t1", "hubspot:42")), "got $g")
    }

    @Test
    fun `Merged and Reinforced enrich the revised proposition`() {
        val merged = RevisionResult.Merged(original = prop("o"), revised = prop("r"))
            .withAdditionalGrounding(listOf("email:t1")) as RevisionResult.Merged
        assertTrue("email:t1" in merged.revised.grounding)

        val reinforced = RevisionResult.Reinforced(original = prop("o"), revised = prop("r"))
            .withAdditionalGrounding(listOf("email:t1")) as RevisionResult.Reinforced
        assertTrue("email:t1" in reinforced.revised.grounding)
    }

    @Test
    fun `Contradicted enriches only the new proposition, not the pre-existing original`() {
        val c = RevisionResult.Contradicted(original = prop("o"), new = prop("n"))
            .withAdditionalGrounding(listOf("email:t1")) as RevisionResult.Contradicted
        assertTrue("email:t1" in c.new.grounding, "new should be attributed to this source")
        assertTrue("email:t1" !in c.original.grounding, "pre-existing original keeps its own provenance")
    }

    @Test
    fun `grounding ids are de-duplicated`() {
        val r = RevisionResult.New(prop("x", grounding = listOf("email:t1")))
            .withAdditionalGrounding(listOf("email:t1")) as RevisionResult.New
        assertEquals(listOf("email:t1"), r.proposition.grounding)
    }
}

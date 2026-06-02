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
package com.embabel.dice.projection.grounding

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntity
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.RetrievableIdentifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

/**
 * Tests for the additive `GROUNDED_IN` wiring that turns proposition
 * grounding ids into edges. Verifies the backward-compat contract:
 * unresolvable ids skipped, resolvable ones wired, no errors on empty
 * input, idempotent over re-runs.
 *
 * `InMemoryNamedEntityDataRepository.mergeRelationship` accepts the
 * call but doesn't materialise edges into a queryable form for tests,
 * so we assert on the [GroundingWiringService.GroundingReport] counts
 * (which faithfully reflect what the service attempted / wrote /
 * skipped).
 */
interface SourceDocument : NamedEntity

class GroundingWiringServiceTest {

    private val schema = DataDictionary.fromClasses("test", SourceDocument::class.java)

    private fun newRepo(): InMemoryNamedEntityDataRepository =
        InMemoryNamedEntityDataRepository(schema)

    private fun proposition(id: String, grounding: List<String>): Proposition = Proposition(
        id = id,
        contextId = ContextId("test-ctx"),
        text = "test proposition",
        mentions = listOf(
            EntityMention(span = "Alice", type = "Person", resolvedId = "alice-1", role = MentionRole.SUBJECT),
        ),
        confidence = 0.9,
        grounding = grounding,
    )

    private fun seedEntity(repo: InMemoryNamedEntityDataRepository, id: String, name: String) {
        repo.save(
            SimpleNamedEntityData(
                id = id,
                name = name,
                description = name,
                labels = setOf("SourceDocument", "__Entity__"),
                properties = emptyMap(),
            ),
        )
    }

    @Test
    fun `wire writes one edge per resolvable grounding id`() {
        val repo = newRepo()
        seedEntity(repo, "doc-1", "Document 1")
        seedEntity(repo, "doc-2", "Document 2")
        val service = GroundingWiringService(repo)
        val p = proposition("prop-1", grounding = listOf("doc-1", "doc-2"))

        val report = service.wire(listOf(p))

        assertEquals(1, report.propositions)
        assertEquals(2, report.attempted)
        assertEquals(2, report.written, "both grounding ids resolved → 2 edges")
        assertEquals(0, report.skipped)
        assertEquals(0, report.failed)
    }

    @Test
    fun `wire silently skips grounding ids that do not resolve`() {
        // Backward-compat: legacy chunk hashes / message ids that
        // aren't entity rows must NOT cause failure. Skipped counts
        // them so operators can see what's happening.
        val repo = newRepo()
        seedEntity(repo, "doc-1", "Document 1")
        val service = GroundingWiringService(repo)
        val p = proposition(
            "prop-1",
            grounding = listOf("doc-1", "chunk-hash-not-an-entity", "another-bogus-id"),
        )

        val report = service.wire(listOf(p))

        assertEquals(3, report.attempted)
        assertEquals(1, report.written)
        assertEquals(2, report.skipped)
        assertEquals(0, report.failed)
    }

    @Test
    fun `wire deduplicates grounding ids within a single proposition`() {
        // If the extractor accidentally lists the same id twice, we
        // shouldn't attempt the merge twice — it'd be a no-op via
        // MERGE but wastes a round trip.
        val repo = newRepo()
        seedEntity(repo, "doc-1", "Document 1")
        val service = GroundingWiringService(repo)
        val p = proposition("prop-1", grounding = listOf("doc-1", "doc-1", "doc-1"))

        val report = service.wire(listOf(p))

        assertEquals(1, report.attempted)
        assertEquals(1, report.written)
    }

    @Test
    fun `wire handles propositions with empty grounding`() {
        val repo = newRepo()
        seedEntity(repo, "doc-1", "Document 1")
        val service = GroundingWiringService(repo)
        val p = proposition("prop-1", grounding = emptyList())

        val report = service.wire(listOf(p))

        assertEquals(1, report.propositions)
        assertEquals(0, report.attempted)
        assertEquals(0, report.written)
    }

    @Test
    fun `wire returns EMPTY report for empty proposition list`() {
        val repo = newRepo()
        val service = GroundingWiringService(repo)

        val report = service.wire(emptyList())

        assertEquals(GroundingWiringService.GroundingReport.EMPTY, report)
    }

    @Test
    fun `wire is idempotent — re-running over the same propositions does not error`() {
        // mergeRelationship is MERGE-by-key under the hood; re-runs
        // should be no-ops. The service must not throw or count
        // failures on the second pass.
        val repo = newRepo()
        seedEntity(repo, "doc-1", "Document 1")
        val service = GroundingWiringService(repo)
        val p = proposition("prop-1", grounding = listOf("doc-1"))

        service.wire(listOf(p))   // first pass
        val secondReport = service.wire(listOf(p)) // second pass

        assertEquals(1, secondReport.attempted)
        assertEquals(1, secondReport.written, "MERGE means re-runs report write success, not failure")
        assertEquals(0, secondReport.failed)
    }

    @Test
    fun `wire aggregates edges across multiple propositions`() {
        val repo = newRepo()
        seedEntity(repo, "doc-1", "Document 1")
        seedEntity(repo, "doc-2", "Document 2")
        val service = GroundingWiringService(repo)
        val propositions = listOf(
            proposition("prop-a", grounding = listOf("doc-1")),
            proposition("prop-b", grounding = listOf("doc-1", "doc-2")),
            proposition("prop-c", grounding = listOf("doc-2", "not-a-thing")),
        )

        val report = service.wire(propositions)

        assertEquals(3, report.propositions)
        // distinct() is per-proposition (so the same id can appear
        // across propositions and still produce one attempt per
        // proposition). prop-a:doc-1, prop-b:doc-1+doc-2,
        // prop-c:doc-2+not-a-thing = 1+2+2 = 5 attempts.
        assertEquals(5, report.attempted)
        assertEquals(4, report.written, "4 of 5 attempted grounding ids resolve (doc-1×2, doc-2×2)")
        assertEquals(1, report.skipped, "'not-a-thing' is the only unresolved id")
    }

    @Test
    fun `wire writes the edge to the resolved node's real id, not the grounding string`() {
        // The core of issue #297: a stripped grounding id ("email:<hash>")
        // must wire to the namespaced node ("email:<user>:<hash>"), and the
        // edge endpoint must be that REAL id — never the grounding string,
        // which would MERGE-create a phantom bare {id} node.
        val realNode: NamedEntityData = SimpleNamedEntityData(
            id = "email:rod_johnson_assistant:hash9",
            name = "Email",
            description = "",
            labels = setOf("EmailSignal", "__Entity__"),
            properties = emptyMap(),
        )
        val resolver = object : GroundingResolver {
            override fun resolveAll(groundingId: String) =
                if (groundingId == "email:hash9") listOf(realNode) else emptyList()

            override fun resolve(groundingId: String) = resolveAll(groundingId).singleOrNull()
        }
        val repo = mock<NamedEntityDataRepository>()
        val service = GroundingWiringService(repo, resolver)

        val report = service.wire(listOf(proposition("prop-1", grounding = listOf("email:hash9"))))

        assertEquals(1, report.written)
        verify(repo).mergeRelationship(
            a = any(),
            b = check { assertEquals("email:rod_johnson_assistant:hash9", it.id) },
            relationship = any(),
        )
    }

    @Test
    fun `wire works with any entity type — not just one schema class`() {
        // The point of the additive design: GROUNDED_IN edges work
        // for ANY label in the dictionary. Verify by seeding entities
        // with different label sets.
        val repo = newRepo()
        repo.save(
            SimpleNamedEntityData(
                id = "email-1",
                name = "Email Subject",
                description = "",
                labels = setOf("EmailSignal", "__Entity__"),
                properties = emptyMap(),
            ),
        )
        repo.save(
            SimpleNamedEntityData(
                id = "meeting-1",
                name = "Sprint Review",
                description = "",
                labels = setOf("MeetingSignal", "Meeting", "__Entity__"),
                properties = emptyMap(),
            ),
        )
        val service = GroundingWiringService(repo)
        val p = proposition("prop-1", grounding = listOf("email-1", "meeting-1"))

        val report = service.wire(listOf(p))

        assertEquals(2, report.attempted)
        assertEquals(2, report.written)
        assertTrue(
            report.failed == 0,
            "wiring must succeed regardless of target label set — was '${report.failed}' failures",
        )
    }
}

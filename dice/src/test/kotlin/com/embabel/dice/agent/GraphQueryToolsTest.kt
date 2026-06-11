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
package com.embabel.dice.agent

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.query.graph.GraphQuery
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Shape and behaviour contract for the agent-facing graph-query tool group.
 *
 * The tools are a thin wrapper over the portable [GraphQuery] facade: they reflect into a
 * registerable list of tools, return graceful text when nothing is found, and never throw on
 * empty results.
 */
class GraphQueryToolsTest {

    private val contextId = ContextId("graph-tools-test")

    private fun edge(id: String, a: String, b: String): Proposition =
        Proposition(
            id = id,
            contextId = contextId,
            text = "$a relates to $b",
            mentions = listOf(
                EntityMention(span = a, type = "Entity", resolvedId = a, role = MentionRole.SUBJECT),
                EntityMention(span = b, type = "Entity", resolvedId = b, role = MentionRole.OBJECT),
            ),
            confidence = 0.9,
        )

    /** A→B and B→C edges plus a grounded proposition for lineage. */
    private fun fixtureQuery(): GraphQuery {
        val store = InMemoryPropositionRepository()
        store.save(edge("ab", "A", "B"))
        store.save(edge("bc", "B", "C"))
        store.save(
            Proposition(
                id = "grounded",
                contextId = contextId,
                text = "A is well documented",
                mentions = listOf(
                    EntityMention(span = "A", type = "Entity", resolvedId = "A", role = MentionRole.SUBJECT),
                ),
                confidence = 0.9,
                grounding = listOf("chunk-1"),
                provenanceEntries = listOf(ProvenanceEntry(locator = UriLocator("doc://1"), chunkId = "chunk-1")),
                reinforceCount = 2,
            ),
        )
        return GraphQuery(store, contextId)
    }

    @Test
    fun `asTools reflects the three graph query tools into a registerable list`() {
        val tools = GraphQueryTools.asTools(fixtureQuery())

        assertTrue(tools.size >= 3, "expected at least three tools, got ${tools.size}")
        val names = tools.map { it.definition.name }.toSet()
        assertTrue(names.contains("entity_neighborhood"), "names were $names")
        assertTrue(names.contains("path_between"), "names were $names")
        assertTrue(names.contains("why_explain"), "names were $names")
    }

    @Test
    fun `entityNeighborhood returns text naming the related entity`() {
        val tools = GraphQueryTools(fixtureQuery())

        val result = tools.entityNeighborhood("A", depth = 1)

        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("B"), "neighbourhood text should mention B: $text")
    }

    @Test
    fun `pathBetween returns graceful text and never throws when unreachable`() {
        val tools = GraphQueryTools(fixtureQuery())

        val result = assertDoesNotThrow<Tool.Result> { tools.pathBetween("A", "Z") }

        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("No path found"), "expected graceful text, got: $text")
    }

    @Test
    fun `whyExplain returns lineage text for a known proposition`() {
        val tools = GraphQueryTools(fixtureQuery())

        val result = tools.whyExplain("grounded")

        val text = (result as Tool.Result.Text).content
        assertTrue(text.contains("A is well documented"), "lineage should include the statement: $text")
    }

    @Test
    fun `whyExplain returns an error result for an unknown proposition`() {
        val tools = GraphQueryTools(fixtureQuery())

        val result = tools.whyExplain("does-not-exist")

        assertTrue(result is Tool.Result.Error, "unknown id should yield an error result, got: $result")
    }
}

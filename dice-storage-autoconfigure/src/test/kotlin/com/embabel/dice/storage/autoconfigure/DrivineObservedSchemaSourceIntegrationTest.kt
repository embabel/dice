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
package com.embabel.dice.storage.autoconfigure

import com.embabel.agent.core.ContextId
import org.assertj.core.api.Assertions.assertThat
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Real-Neo4j proof for [DrivineObservedSchemaSource]'s scoped relationship-type join: fakes can
 * only assert what Cypher text we *sent*, not whether the join it describes actually behaves as
 * designed. This exercises the `sourcePropositions` join against a live testcontainer, writing
 * fixture nodes/edges directly with Cypher (never through the dice/ write path -- this class only
 * reads).
 *
 * Every test starts from an empty graph via [cleanUp].
 */
@SpringBootTest(classes = [TestApplication::class])
class DrivineObservedSchemaSourceIntegrationTest {

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    private val source: DrivineObservedSchemaSource by lazy { DrivineObservedSchemaSource(persistenceManager) }

    @AfterEach
    fun cleanUp() {
        persistenceManager.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    }

    /** Writes a bare `:Proposition {id, contextId}` node -- all the join needs from a proposition. */
    private fun writeProposition(id: String, contextId: String) {
        persistenceManager.execute(
            QuerySpecification
                .withStatement("CREATE (:Proposition {id: \$id, contextId: \$contextId})")
                .bind(mapOf("id" to id, "contextId" to contextId)),
        )
    }

    /**
     * Writes a `(:Entity)-[r:type]->(:Entity)` edge carrying [sourcePropositionIds] as its
     * `sourcePropositions` property -- via `apoc.create.relationship`, which takes the
     * relationship type as a plain string value (not Cypher's native dynamic-type syntax),
     * so it's safe to call repeatedly with different type strings in the same test run.
     */
    private fun writeRelationship(from: String, to: String, type: String, sourcePropositionIds: List<String>) {
        persistenceManager.execute(
            QuerySpecification
                .withStatement(
                    """
                    MERGE (a:Entity {id: ${'$'}from})
                    MERGE (b:Entity {id: ${'$'}to})
                    WITH a, b
                    CALL apoc.create.relationship(a, ${'$'}relType, {sourcePropositions: ${'$'}sourcePropositions}, b) YIELD rel
                    RETURN rel
                    """.trimIndent(),
                )
                .bind(
                    mapOf(
                        "from" to from,
                        "to" to to,
                        "relType" to type,
                        "sourcePropositions" to sourcePropositionIds,
                    ),
                ),
        )
    }

    @Test
    fun `an edge sourced from context A's proposition appears in A's scoped relationship set`() {
        writeProposition(id = "p1", contextId = "tenant-a")
        writeRelationship(from = "e1", to = "e2", type = "WORKS_AT", sourcePropositionIds = listOf("p1"))

        val observed = source.observe(ContextId("tenant-a"))

        assertThat(observed.relationshipTypeNames).contains("WORKS_AT")
    }

    @Test
    fun `an edge sourced from both A and B's propositions appears in BOTH scoped sets -- no collapse`() {
        writeProposition(id = "p-a", contextId = "tenant-a")
        writeProposition(id = "p-b", contextId = "tenant-b")
        writeRelationship(from = "e1", to = "e2", type = "SHARED_REL", sourcePropositionIds = listOf("p-a", "p-b"))

        val observedA = source.observe(ContextId("tenant-a"))
        val observedB = source.observe(ContextId("tenant-b"))

        assertThat(observedA.relationshipTypeNames)
            .describedAs("multi-context edge must appear in A's set too -- drift in A's data, regardless of B's involvement")
            .contains("SHARED_REL")
        assertThat(observedB.relationshipTypeNames)
            .describedAs("and in B's set -- neither context loses visibility of it")
            .contains("SHARED_REL")
    }

    @Test
    fun `an edge sourced only from context C does not appear in A's scoped set`() {
        writeProposition(id = "p-c", contextId = "tenant-c")
        writeRelationship(from = "e1", to = "e2", type = "ONLY_C_REL", sourcePropositionIds = listOf("p-c"))
        // Give tenant-a some unrelated data so it isn't just an empty-graph pass.
        writeProposition(id = "p-a", contextId = "tenant-a")

        val observedA = source.observe(ContextId("tenant-a"))

        assertThat(observedA.relationshipTypeNames).doesNotContain("ONLY_C_REL")
    }

    @Test
    fun `the global (null-context) path is unchanged -- reports all relationship types via db-relationshipTypes`() {
        writeProposition(id = "p1", contextId = "tenant-a")
        writeRelationship(from = "e1", to = "e2", type = "GLOBAL_REL", sourcePropositionIds = listOf("p1"))

        val observedGlobal = source.observe(null)

        assertThat(observedGlobal.relationshipTypeNames).contains("GLOBAL_REL")
    }

    @Test
    fun `a delimiter-bearing relationship type round-trips into the scoped observed set intact`() {
        val weirdType = "REL|WITH\tTAB\nAND\"QUOTE"
        writeProposition(id = "p1", contextId = "tenant-a")
        writeRelationship(from = "e1", to = "e2", type = weirdType, sourcePropositionIds = listOf("p1"))

        val observed = source.observe(ContextId("tenant-a"))

        assertThat(observed.relationshipTypeNames).contains(weirdType)
    }

    @Test
    fun `type(r) as returned by the join equals the exact declared name -- no case-folding or normalization`() {
        // A name that would expose any hidden normalization (mixed case, underscores) if the
        // Neo4j write/read path silently canonicalized relationship types.
        val mixedCaseType = "Works_At_Company"
        writeProposition(id = "p1", contextId = "tenant-a")
        writeRelationship(from = "e1", to = "e2", type = mixedCaseType, sourcePropositionIds = listOf("p1"))

        val observed = source.observe(ContextId("tenant-a"))

        assertThat(observed.relationshipTypeNames)
            .describedAs("type(r) must equal the declared name byte-for-byte, not an upper/lower-cased form")
            .containsExactly(mixedCaseType)
    }
}

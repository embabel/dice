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
package com.embabel.dice.storage

import com.embabel.agent.core.ContextId
import com.embabel.dice.metamodel.DriftReport
import com.embabel.dice.metamodel.MetamodelVersion
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant

/**
 * [DrivineObservedSchemaSource] against a Neo4j testcontainer.
 *
 * The last group covers governance observing its own bookkeeping. Stamping a version and writing a
 * drift report both add nodes to the graph the next check looks at, so without the exclusion every
 * run reports the previous run as drift.
 */
@SpringBootTest(classes = [TestApplication::class])
class DrivineObservedSchemaSourceIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }

    @Autowired
    private lateinit var source: DrivineObservedSchemaSource

    @Autowired
    private lateinit var versionStore: DrivineMetamodelVersionStore

    @Autowired
    private lateinit var reportStore: DrivineDriftReportStore

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    private val tenantA = ContextId("tenant-a")
    private val tenantB = ContextId("tenant-b")

    @AfterEach
    fun cleanUp() {
        persistenceManager.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    }

    // ---- Context-scoped observation ----

    @Test
    fun `scoped entity types are the mention types of that context's propositions`() {
        writeProposition("p-a", tenantA)
        writeProposition("p-b", tenantB)
        writeMention("p-a", "m-a", type = "Person")
        writeMention("p-b", "m-b", type = "SecretType")

        assertEquals(setOf("Person"), source.observe(tenantA).entityTypeNames)
        assertEquals(setOf("SecretType"), source.observe(tenantB).entityTypeNames)
    }

    @Test
    fun `a relationship sourced from one context is not seen from another`() {
        writeProposition("p-a", tenantA)
        writeProposition("p-b", tenantB)
        writeRelationship(type = "ONLY_A_REL", sourcePropositionIds = listOf("p-a"))

        assertEquals(setOf("ONLY_A_REL"), source.observe(tenantA).relationshipTypeNames)
        assertTrue(source.observe(tenantB).relationshipTypeNames.isEmpty())
    }

    @Test
    fun `a relationship two contexts produced is drift in both of them`() {
        // The join does not collapse to one owner: an undeclared relationship type present in a
        // context's data is drift in that context, whoever else produced it.
        writeProposition("p-a", tenantA)
        writeProposition("p-b", tenantB)
        writeRelationship(type = "SHARED_REL", sourcePropositionIds = listOf("p-a", "p-b"))

        assertTrue(source.observe(tenantA).relationshipTypeNames.contains("SHARED_REL"))
        assertTrue(source.observe(tenantB).relationshipTypeNames.contains("SHARED_REL"))
    }

    @Test
    fun `relationship names come back exactly as stored, with no normalization`() {
        writeProposition("p-a", tenantA)
        writeRelationship(type = "Works_At_Company", sourcePropositionIds = listOf("p-a"))

        assertEquals(setOf("Works_At_Company"), source.observe(tenantA).relationshipTypeNames)
    }

    @Test
    fun `an empty context observes nothing rather than the whole graph`() {
        writeProposition("p-a", tenantA)
        writeMention("p-a", "m-a", type = "Person")

        val observed = source.observe(ContextId("nobody-here"))

        assertTrue(observed.entityTypeNames.isEmpty())
        assertTrue(observed.relationshipTypeNames.isEmpty())
    }

    // ---- Whole-graph observation ----

    @Test
    fun `whole-graph observation reports domain labels and relationship types`() {
        writeProposition("p-a", tenantA)
        writeRelationship(type = "GLOBAL_REL", sourcePropositionIds = listOf("p-a"))

        val observed = source.observe()

        assertTrue(observed.entityTypeNames.contains("Entity"), "got ${observed.entityTypeNames}")
        assertTrue(observed.relationshipTypeNames.contains("GLOBAL_REL"), "got ${observed.relationshipTypeNames}")
    }

    @Test
    fun `an observation is stamped with the instant it was taken`() {
        val before = Instant.now()

        val capturedAt = source.observe().capturedAt

        assertFalse(capturedAt.isBefore(before), "capturedAt $capturedAt predates the call at $before")
    }

    // ---- Governance never observes its own bookkeeping ----

    @Test
    fun `dice's own storage labels are never reported as domain drift`() {
        writeProposition("p-a", tenantA)
        writeMention("p-a", "m-a", type = "Person")
        persistenceManager.execute(QuerySpecification.withStatement("CREATE (:Source {key: 'src'})"))
        persistenceManager.execute(QuerySpecification.withStatement("CREATE (:ProcessedChunk {id: 'chunk'})"))
        // Without this precondition the test would pass on an empty observation. It pins that the
        // database is reporting these labels, so the exclusion is what keeps them out.
        assertTrue(
            rawLabels().containsAll(setOf("Proposition", "Mention", "Source", "ProcessedChunk")),
            "precondition: the raw catalogue must hold the bookkeeping labels, but was ${rawLabels()}",
        )

        val observed = source.observe()

        assertTrue(
            observed.entityTypeNames.none { it in DICE_BOOKKEEPING_LABELS },
            "bookkeeping leaked into the observation: ${observed.entityTypeNames intersect DICE_BOOKKEEPING_LABELS}",
        )
    }

    @Test
    fun `the metamodel's own nodes are never reported as domain drift`() {
        // A drift check stamps a version and writes a report, and both land in the graph the next
        // check observes. Without the exclusion the second run reports MetamodelVersion and
        // MetamodelDriftReport as undeclared entity types, as does every run after it.
        val version = MetamodelVersion("observed-schema", listOf("Person"), emptyMap(), emptyMap(), emptyList())
        versionStore.saveVersion(version)
        reportStore.saveDriftReport(
            DriftReport(
                schemaName = "observed-schema",
                versionHash = version.contentHash,
                driftedEntityTypes = setOf("Ghost"),
                driftedRelationshipTypes = emptySet(),
                capturedAt = Instant.parse("2026-01-01T00:00:00Z"),
            ),
        )
        assertEquals(4, governanceNodeCount(), "precondition: all four governance node kinds exist")
        assertTrue(
            rawLabels().containsAll(MetamodelSchema.LABELS.toSet()),
            "precondition: the raw catalogue must hold every governance label, but was ${rawLabels()}",
        )

        val observed = source.observe()

        MetamodelSchema.LABELS.forEach { label ->
            assertFalse(
                observed.entityTypeNames.contains(label),
                "governance observed its own '$label' node as domain drift; got ${observed.entityTypeNames}",
            )
        }
    }

    @Test
    fun `dice's own relationship types are never reported as domain drift`() {
        // HAS_MENTION and DERIVED_FROM sit on every proposition ever stored. Without the exclusion,
        // the first whole-graph check against a populated graph reports them as relationship drift,
        // as does every check after it.
        writeProposition("p-a", tenantA)
        writeMention("p-a", "m-a", type = "Person")
        persistenceManager.execute(
            QuerySpecification.withStatement(
                "MATCH (p:Proposition {id: 'p-a'}) CREATE (p)-[:DERIVED_FROM]->(:Source {key: 'src'})",
            ),
        )
        assertTrue(
            rawRelationshipTypes().containsAll(setOf("HAS_MENTION", "DERIVED_FROM")),
            "precondition: the raw catalogue must hold the bookkeeping edges, but was ${rawRelationshipTypes()}",
        )

        val observed = source.observe()

        assertTrue(
            observed.relationshipTypeNames.none { it in DICE_BOOKKEEPING_RELATIONSHIP_TYPES },
            "bookkeeping edges leaked: " +
                "${observed.relationshipTypeNames intersect DICE_BOOKKEEPING_RELATIONSHIP_TYPES}",
        )
    }

    // ---- Exclusion by shape, not by label name ----

    @Test
    fun `a domain node that only shares a bookkeeping label's name is still observed`() {
        // Excluding the name `Source` would hide an app's own undeclared `Source` type from every
        // report. Exclusion goes by node shape so that type stays observable.
        persistenceManager.execute(
            QuerySpecification.withStatement("CREATE (:Source {companyName: 'Acme', founded: 1999})"),
        )
        assertTrue(rawLabels().contains("Source"))

        val observed = source.observe()

        assertTrue(
            observed.entityTypeNames.contains("Source"),
            "a Source node with none of dice's shape is domain data; got ${observed.entityTypeNames}",
        )
    }

    @Test
    fun `dice's own nodes are still excluded when nothing else claims their label`() {
        // The other half of the shape test: dice's own conforming nodes still get excluded.
        writeProposition("p-a", tenantA)
        writeMention("p-a", "m-a", type = "Person")
        persistenceManager.execute(QuerySpecification.withStatement("CREATE (:Source {key: 'src-1'})"))
        persistenceManager.execute(QuerySpecification.withStatement("CREATE (:ProcessedChunk {id: 'chunk-1'})"))

        val observed = source.observe()

        listOf("Proposition", "Mention", "Source", "ProcessedChunk").forEach { label ->
            assertFalse(
                observed.entityTypeNames.contains(label),
                "dice's own '$label' nodes match its shape and must stay excluded; got ${observed.entityTypeNames}",
            )
        }
    }

    @Test
    fun `a domain relationship sharing a bookkeeping type's name is still observed`() {
        // Same rule on the relationship side, decided by the marker the scoped path uses: an edge
        // carrying sourcePropositions was projected from domain data, whatever it is called.
        writeProposition("p-a", tenantA)
        writeRelationship(type = "DERIVED_FROM", sourcePropositionIds = listOf("p-a"))

        assertTrue(
            source.observe().relationshipTypeNames.contains("DERIVED_FROM"),
            "got ${source.observe().relationshipTypeNames}",
        )
        assertTrue(
            source.observe(tenantA).relationshipTypeNames.contains("DERIVED_FROM"),
            "the scoped path must agree; got ${source.observe(tenantA).relationshipTypeNames}",
        )
    }

    @Test
    fun `every label the trace and metamodel stores write has a shape entry`() {
        // Keeps the shape map in step with the schema objects: a new node label added to either
        // store without a shape here would stop being excluded.
        (CollectorTraceSchema.LABELS + MetamodelSchema.LABELS).forEach { label ->
            assertTrue(
                DICE_BOOKKEEPING_LABEL_SHAPES.containsKey(label),
                "'$label' is written by a dice store but has no bookkeeping shape",
            )
        }
    }

    // ---- helpers ----

    /**
     * A proposition node carrying dice's full shape, which is how the observer recognises it as
     * dice's own rather than a domain node sharing the label.
     */
    private fun writeProposition(id: String, contextId: ContextId) {
        persistenceManager.execute(
            QuerySpecification.withStatement(
                "CREATE (:Proposition {id: \$id, contextId: \$contextId, text: \$text})",
            ).bind(mapOf("id" to id, "contextId" to contextId.value, "text" to "a fact about $id")),
        )
    }

    /** Likewise a mention: `id`, `span`, `type` and `role`, as `PropositionGraphMapper` writes it. */
    private fun writeMention(propositionId: String, mentionId: String, type: String) {
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                MATCH (p:Proposition {id: ${'$'}propositionId})
                CREATE (p)-[:HAS_MENTION]->(
                    :Mention {id: ${'$'}mentionId, span: ${'$'}span, type: ${'$'}type, role: 'SUBJECT'}
                )
                """.trimIndent(),
            ).bind(
                mapOf(
                    "propositionId" to propositionId,
                    "mentionId" to mentionId,
                    "span" to type.lowercase(),
                    "type" to type,
                ),
            ),
        )
    }

    /**
     * A projected domain edge, carrying the `sourcePropositions` property the graph writer stamps
     * on everything it persists. APOC, because the type is a parameter and Cypher can't take one
     * literally.
     */
    private fun writeRelationship(type: String, sourcePropositionIds: List<String>) {
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                MERGE (a:Entity {id: 'e1'})
                MERGE (b:Entity {id: 'e2'})
                WITH a, b
                CALL apoc.create.relationship(a, ${'$'}relType, {sourcePropositions: ${'$'}sources}, b) YIELD rel
                RETURN rel
                """.trimIndent(),
            ).bind(mapOf("relType" to type, "sources" to sourcePropositionIds)),
        )
    }

    /** What the database's own catalogue says, before the source subtracts anything from it. */
    private fun rawLabels(): Set<String> = queryStrings("CALL db.labels() YIELD label RETURN label")

    private fun rawRelationshipTypes(): Set<String> =
        queryStrings("CALL db.relationshipTypes() YIELD relationshipType RETURN relationshipType")

    private fun queryStrings(statement: String): Set<String> = persistenceManager
        .query(QuerySpecification.withStatement(statement).transform(String::class.java))
        .filterNotNull()
        .toSet()

    private fun governanceNodeCount(): Int = MetamodelSchema.LABELS.count { label ->
        (
            persistenceManager.maybeGetOne(
                QuerySpecification.withStatement("MATCH (n:$label) RETURN count(n) AS c")
                    .transform(Long::class.java),
            ) ?: 0L
            ) > 0L
    }
}

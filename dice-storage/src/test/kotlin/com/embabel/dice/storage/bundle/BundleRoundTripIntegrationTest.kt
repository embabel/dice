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
package com.embabel.dice.storage.bundle

import com.embabel.agent.core.ContextId
import com.embabel.dice.bundle.BundleImportOutcome
import com.embabel.dice.bundle.EmbeddingPort
import com.embabel.dice.bundle.ImportConflictPolicy
import com.embabel.dice.bundle.KnowledgeBundleAssembler
import com.embabel.dice.bundle.support.JacksonKnowledgeBundleExporter
import com.embabel.dice.bundle.support.JacksonKnowledgeBundleImporter
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.storage.DrivinePropositionRepository
import com.embabel.dice.storage.Neo4jTestContainer
import com.embabel.dice.storage.TestApplication
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant

/**
 * End-to-end integration test proving backup/restore for a single context via knowledge bundles,
 * run against dice-storage's own Drivine testcontainer harness (same wiring as
 * [com.embabel.dice.storage.DrivineEmbeddingAccessIntegrationTest] and
 * [com.embabel.dice.storage.DrivineMetamodelStoreIntegrationTest]).
 *
 * This lives in dice-storage rather than dice-integration-tests because that's where the
 * Drivine testcontainer harness is proven to register the 'neo' database; bootstrapping it
 * standalone from dice-integration-tests failed with "No database is registered under name: neo".
 *
 * The test exercises the full roundtrip:
 * 1. Seeds two contexts (ctx-A, ctx-B) with propositions, mentions, resolved IDs, and embeddings
 * 2. Exports ctx-A via [KnowledgeBundleAssembler] with embeddings via [EmbeddingPort]
 * 3. Deletes all ctx-A propositions from the Neo4j store
 * 4. Re-imports the JSON bundle, asserting full fidelity:
 *    - Propositions restored with identical IDs, text, contextId, mentions
 *    - Decay-anchor timestamps (created, contentRevised, metadataRevised) byte-identical to seed
 *    - Embeddings restored exactly via the port (no re-embedding)
 *    - ctx-B remains untouched
 * 5. Tests re-scoped restore with targetContextId to a fresh context (ctx-fresh)
 * 6. Tests delimiter-adversarial inputs: proposition text and mention spans containing
 *    pipes, tabs, newlines, quotes are restored verbatim
 */
@SpringBootTest(classes = [TestApplication::class])
class BundleRoundTripIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }

    @Autowired
    private lateinit var repository: DrivinePropositionRepository

    @Autowired
    private lateinit var embeddingAccess: EmbeddingPort

    private val exporter = JacksonKnowledgeBundleExporter()

    // embeddingImporter must be wired here, or every restored proposition silently loses its
    // embedding: JacksonKnowledgeBundleImporter treats a null embeddingImporter as "this consumer
    // doesn't handle embeddings" and skips the bundle's embeddings section entirely (see its KDoc).
    private val importer by lazy { JacksonKnowledgeBundleImporter(embeddingImporter = embeddingAccess) }

    @AfterEach
    fun cleanUp() {
        repository.clearAll()
    }

    private fun contextA() = ContextId("ctx-A")
    private fun contextB() = ContextId("ctx-B")
    private fun contextFresh() = ContextId("ctx-fresh")

    /**
     * Create a proposition with the given text in the given context.
     * Seed distinct timestamps for decay-anchor testing.
     */
    private fun seedProposition(
        contextId: ContextId,
        text: String,
        mentionText: String? = null,
        resolvedId: String? = null,
        created: Instant = Instant.parse("2025-06-01T08:00:00Z"),
        contentRevised: Instant = Instant.parse("2025-11-15T09:30:00Z"),
        metadataRevised: Instant = Instant.parse("2026-01-20T14:00:00Z"),
    ): Proposition {
        val mentions = if (mentionText != null && resolvedId != null) {
            listOf(
                EntityMention(
                    span = mentionText,
                    type = "Entity",
                    resolvedId = resolvedId,
                    role = MentionRole.SUBJECT,
                )
            )
        } else {
            emptyList()
        }

        return Proposition(
            contextId = contextId,
            text = text,
            mentions = mentions,
            confidence = 0.8,
            decay = 0.05,
            status = PropositionStatus.ACTIVE,
            created = created,
            contentRevised = contentRevised,
            metadataRevised = metadataRevised,
        )
    }

    /**
     * Seed a context with a mix of propositions: basic, with mentions+resolvedIds,
     * and store deterministic embeddings.
     */
    private fun seedContext(contextId: ContextId, propositionTexts: List<String>) {
        for ((idx, text) in propositionTexts.withIndex()) {
            val mentionText = "entity-${idx}"
            val resolvedId = "ent-${idx}"
            val created = Instant.parse("2025-06-01T08:00:00Z").plusSeconds(idx.toLong() * 100)

            val prop = seedProposition(
                contextId = contextId,
                text = text,
                mentionText = mentionText,
                resolvedId = resolvedId,
                created = created,
            )
            val saved = repository.save(prop)

            // Write a deterministic embedding vector
            val vector = listOf(0.1f * (idx + 1), 0.2f * (idx + 1), 0.3f * (idx + 1))
            embeddingAccess.importEmbedding(saved.id, vector)
        }
    }

    @Test
    fun `scoped export excludes unrelated context`() {
        // Seed both contexts
        seedContext(contextA(), listOf("Alice works with Bob", "Bob knows Carol"))
        seedContext(contextB(), listOf("Unrelated fact 1", "Unrelated fact 2"))

        val assembler = KnowledgeBundleAssembler(
            repository = repository,
            embeddingExporter = embeddingAccess,
        )
        val bundle = assembler.forContext("ctx-A")

        assertEquals("ctx-A", bundle.contextId.value)
        assertEquals(2, bundle.propositions.size)
        assertTrue(bundle.propositions.all { it.contextId == contextA() })
    }

    @Test
    fun `export to JSON and reimport restores all propositions with fidelity`() {
        // Seed ctx-A with propositions that have mentions, resolved IDs, and embeddings
        seedContext(contextA(), listOf("Alice works with Bob", "Bob knows Carol", "Carol leads Alice"))

        // Export ctx-A with embeddings
        val assembler = KnowledgeBundleAssembler(
            repository = repository,
            embeddingExporter = embeddingAccess,
        )
        val bundle = assembler.forContext("ctx-A")
        val json = exporter.exportToString(bundle)

        // Delete ctx-A from the store
        val propsToDelete = repository.findByContextId(contextA())
        for (prop in propsToDelete) {
            repository.delete(prop.id)
        }
        assertEquals(0, repository.findByContextId(contextA()).size)

        // Re-import the JSON
        val outcome = importer.importFromString(json, repository, ImportConflictPolicy.SKIP_EXISTING)
        assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        val success = outcome as BundleImportOutcome.Success
        assertEquals(3, success.result.imported)
        assertEquals(0, success.result.rejected)

        // Verify all propositions restored with fidelity
        val restoredProps = repository.findByContextId(contextA()).sortedBy { it.text }
        assertEquals(3, restoredProps.size)

        // Verify original IDs preserved
        val originalProps = bundle.propositions.sortedBy { it.text }
        for ((original, restored) in originalProps.zip(restoredProps)) {
            assertEquals(original.id, restored.id, "Proposition ID must match")
            assertEquals(original.text, restored.text, "Proposition text must match")
            assertEquals(original.contextId, restored.contextId, "Context ID must match")
            assertEquals(original.confidence, restored.confidence, "Confidence must match")
            assertEquals(original.decay, restored.decay, "Decay must match")
            assertEquals(original.status, restored.status, "Status must match")
        }
    }

    @Test
    fun `decay-anchor timestamps are byte-identical after roundtrip`() {
        // Seed with explicit, distinct timestamps
        val created = Instant.parse("2025-06-01T08:15:30.123456789Z")
        val contentRevised = Instant.parse("2025-11-15T09:30:45.987654321Z")
        val metadataRevised = Instant.parse("2026-01-20T14:00:00.000000001Z")

        val prop = seedProposition(
            contextId = contextA(),
            text = "Timestamped fact",
            created = created,
            contentRevised = contentRevised,
            metadataRevised = metadataRevised,
        )
        val saved = repository.save(prop)
        val vector = listOf(0.5f, 0.5f, 0.5f)
        embeddingAccess.importEmbedding(saved.id, vector)

        // Export and reimport
        val assembler = KnowledgeBundleAssembler(repository, embeddingExporter = embeddingAccess)
        val bundle = assembler.forContext("ctx-A")
        val json = exporter.exportToString(bundle)

        repository.delete(saved.id)

        val outcome = importer.importFromString(json, repository)
        assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)

        val restored = repository.findById(saved.id)!!
        // Timestamps must be identical (byte-level, including nanoseconds)
        assertEquals(created, restored.created, "created timestamp must be identical")
        assertEquals(contentRevised, restored.contentRevised, "contentRevised must be identical")
        assertEquals(metadataRevised, restored.metadataRevised, "metadataRevised must be identical")
    }

    @Test
    fun `embeddings are restored from bundle without re-embedding`() {
        // Seed a proposition
        seedContext(contextA(), listOf("Vector-backed fact"))

        val originalProp = repository.findByContextId(contextA()).single()
        val originalVector = listOf(0.1f, 0.2f, 0.3f)
        embeddingAccess.importEmbedding(originalProp.id, originalVector)

        // Export with embedding port
        val assembler = KnowledgeBundleAssembler(repository, embeddingExporter = embeddingAccess)
        val bundle = assembler.forContext("ctx-A")

        // Verify the bundle carries the embedding entry
        assertEquals(1, bundle.embeddings.size)
        assertEquals(originalProp.id, bundle.embeddings.first().propositionId)

        val json = exporter.exportToString(bundle)

        // Delete and re-import
        repository.delete(originalProp.id)
        val outcome = importer.importFromString(json, repository)
        assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)

        // Read back the embedding
        val restoredVector = embeddingAccess.embeddingFor(originalProp.id)
        assertNotNull(restoredVector)
        assertEquals(originalVector.size, restoredVector!!.size)
        for (i in originalVector.indices) {
            assertEquals(originalVector[i], restoredVector[i], 1e-6f, "Embedding vectors must match elementwise")
        }
    }

    @Test
    fun `mentions with resolvedIds are restored exactly`() {
        // Seed with explicit mentions
        val mention = EntityMention(
            span = "Alice",
            type = "Person",
            resolvedId = "person-42",
            role = MentionRole.SUBJECT,
        )
        val prop = Proposition(
            contextId = contextA(),
            text = "Alice works at Acme Corp",
            mentions = listOf(mention),
            confidence = 0.9,
            decay = 0.05,
            status = PropositionStatus.ACTIVE,
        )
        val saved = repository.save(prop)
        embeddingAccess.importEmbedding(saved.id, listOf(1.0f, 2.0f, 3.0f))

        // Export and reimport
        val assembler = KnowledgeBundleAssembler(repository, embeddingExporter = embeddingAccess)
        val bundle = assembler.forContext("ctx-A")
        val json = exporter.exportToString(bundle)

        repository.delete(saved.id)

        val outcome = importer.importFromString(json, repository)
        assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)

        val restored = repository.findById(saved.id)!!
        assertEquals(1, restored.mentions.size)
        val restoredMention = restored.mentions.first()
        assertEquals("Alice", restoredMention.span)
        assertEquals("Person", restoredMention.type)
        assertEquals("person-42", restoredMention.resolvedId)
        assertEquals(MentionRole.SUBJECT, restoredMention.role)
    }

    @Test
    fun `contextB remains untouched during cxa export and import`() {
        seedContext(contextA(), listOf("Fact A1", "Fact A2"))
        seedContext(contextB(), listOf("Fact B1", "Fact B2", "Fact B3"))

        val originalCountB = repository.findByContextId(contextB()).size
        assertEquals(3, originalCountB)

        // Export and reimport only ctx-A
        val assembler = KnowledgeBundleAssembler(repository, embeddingExporter = embeddingAccess)
        val bundle = assembler.forContext("ctx-A")
        val json = exporter.exportToString(bundle)

        repository.findByContextId(contextA()).forEach { repository.delete(it.id) }

        importer.importFromString(json, repository)

        // ctx-B must be unchanged
        val finalCountB = repository.findByContextId(contextB()).size
        assertEquals(originalCountB, finalCountB)
        val finalPropsB = repository.findByContextId(contextB()).sortedBy { it.text }
        assertTrue(finalPropsB.any { it.text == "Fact B1" })
        assertTrue(finalPropsB.any { it.text == "Fact B2" })
        assertTrue(finalPropsB.any { it.text == "Fact B3" })
    }

    @Test
    fun `rescoped import with targetContextId lands propositions in new context`() {
        seedContext(contextA(), listOf("Original fact A", "Another fact A"))

        val assembler = KnowledgeBundleAssembler(repository, embeddingExporter = embeddingAccess)
        val bundle = assembler.forContext("ctx-A")
        val json = exporter.exportToString(bundle)

        // PropositionStore keys by id alone, not (contextId, id) — see KnowledgeBundleImporter's
        // KDoc on targetContextId: an id already present under a DIFFERENT context than the one
        // being imported into is a genuine storage-level collision, correctly refused rather than
        // imported. So re-scoping into ctx-fresh while the original ctx-A rows (same ids) are still
        // live isn't a valid "restore succeeds" scenario — it's the collision-rejection path. Model
        // the realistic migrate-into-a-new-context case instead: the source rows are gone (deleted,
        // migrated away, whatever) by the time the re-scoped import runs.
        repository.findByContextId(contextA()).forEach { repository.delete(it.id) }

        // Import into a fresh context
        val outcome = importer.importFromString(
            json,
            repository,
            conflictPolicy = ImportConflictPolicy.SKIP_EXISTING,
            targetContextId = "ctx-fresh",
        )
        assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        val success = outcome as BundleImportOutcome.Success
        assertEquals(2, success.result.imported)

        // Verify propositions landed in ctx-fresh
        val freshProps = repository.findByContextId(contextFresh())
        assertEquals(2, freshProps.size)
        assertTrue(freshProps.all { it.contextId == contextFresh() })
        assertTrue(freshProps.any { it.text == "Original fact A" })
        assertTrue(freshProps.any { it.text == "Another fact A" })

        // ctx-A stays empty — re-scoping only ever writes to the target context
        assertEquals(0, repository.findByContextId(contextA()).size)
    }

    @Test
    fun `delimiter-adversarial proposition text survives roundtrip verbatim`() {
        // Test propositions with pipes, tabs, newlines, quotes
        val adversarialTexts = listOf(
            "Alice | Bob | Carol",
            "Text with\ttabs\there",
            "Line one\nLine two\nLine three",
            """Text with "quotes" and 'apostrophes'""",
            "Combination: |pipe\ttab\nnewline\"quote'",
        )

        for ((idx, text) in adversarialTexts.withIndex()) {
            val prop = Proposition(
                contextId = contextA(),
                text = text,
                mentions = emptyList(),
                confidence = 0.8,
                decay = 0.05,
                status = PropositionStatus.ACTIVE,
            )
            val saved = repository.save(prop)
            embeddingAccess.importEmbedding(saved.id, listOf(0.5f * (idx + 1)))
        }

        val assembler = KnowledgeBundleAssembler(repository, embeddingExporter = embeddingAccess)
        val bundle = assembler.forContext("ctx-A")
        val json = exporter.exportToString(bundle)

        repository.findByContextId(contextA()).forEach { repository.delete(it.id) }

        val outcome = importer.importFromString(json, repository)
        assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)

        val restored = repository.findByContextId(contextA()).sortedBy { it.text }
        val sortedOriginals = adversarialTexts.sortedBy { it }
        assertEquals(sortedOriginals.size, restored.size, "Restored count must match original")
        for (i in sortedOriginals.indices) {
            assertEquals(sortedOriginals[i], restored[i].text, "Adversarial text must survive roundtrip verbatim")
        }
    }

    @Test
    fun `delimiter-adversarial mention spans survive roundtrip verbatim`() {
        // Test mention spans with delimiters
        val adversarialMentions = listOf(
            "Span|with|pipes",
            "Span\twith\ttabs",
            "Span\nwith\nnewlines",
            """Span"with"quotes""",
        )

        for ((idx, mentionText) in adversarialMentions.withIndex()) {
            val mention = EntityMention(
                span = mentionText,
                type = "Type",
                resolvedId = "ent-$idx",
                role = MentionRole.SUBJECT,
            )
            val prop = Proposition(
                contextId = contextA(),
                text = "Proposition containing $mentionText",
                mentions = listOf(mention),
                confidence = 0.8,
                decay = 0.05,
                status = PropositionStatus.ACTIVE,
            )
            val saved = repository.save(prop)
            embeddingAccess.importEmbedding(saved.id, listOf(0.25f * (idx + 1)))
        }

        val assembler = KnowledgeBundleAssembler(repository, embeddingExporter = embeddingAccess)
        val bundle = assembler.forContext("ctx-A")
        val json = exporter.exportToString(bundle)

        repository.findByContextId(contextA()).forEach { repository.delete(it.id) }

        val outcome = importer.importFromString(json, repository)
        assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)

        val restored = repository.findByContextId(contextA()).sortedBy { it.text }
        for ((original, restored) in adversarialMentions.sortedBy { it }.zip(restored)) {
            assertEquals(1, restored.mentions.size)
            assertEquals(original, restored.mentions.first().span, "Mention span must survive verbatim")
        }
    }
}

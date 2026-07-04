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
package com.embabel.dice.bundle

import com.embabel.agent.core.ContextId
import com.embabel.dice.bundle.support.JacksonKnowledgeBundleExporter
import com.embabel.dice.bundle.support.JacksonKnowledgeBundleImporter
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the two consumer ports ([EntitySnapshotPort], [EmbeddingPort]): default no-op behaviour,
 * bundle round-trip of the optional `entities`/`embeddings` sections (including absent and empty),
 * assembler wiring, importer ordering (entities before propositions, embeddings after), and the
 * skipped-with-warning path when a section is present but no port is configured.
 */
class KnowledgeBundlePortsTest {

    private val exporter = JacksonKnowledgeBundleExporter()
    private val contextId = ContextId("ports-ctx")

    private fun proposition(
        text: String,
        mentions: List<EntityMention> = emptyList(),
        id: String = java.util.UUID.randomUUID().toString(),
    ): Proposition =
        Proposition(
            id = id,
            contextId = contextId,
            text = text,
            mentions = mentions,
            confidence = 0.8,
            decay = 0.05,
            status = PropositionStatus.ACTIVE,
        )

    // -------------------------------------------------------------------------
    // 1. Null-object defaults keep everything else green
    // -------------------------------------------------------------------------

    @Test
    fun `NoOpEntitySnapshotPort contributes nothing`() {
        assertTrue(NoOpEntitySnapshotPort.snapshotsFor(setOf("a", "b")).isEmpty())
        // Must not throw even with data handed to it.
        NoOpEntitySnapshotPort.importSnapshots(listOf(EntitySnapshot("a", "Person")))
    }

    @Test
    fun `NoOpEmbeddingPort contributes nothing`() {
        assertNull(NoOpEmbeddingPort.embeddingFor("prop-1"))
        // Must not throw even with data handed to it.
        NoOpEmbeddingPort.importEmbedding("prop-1", listOf(1.0f, 2.0f))
    }

    @Test
    fun `assembler with no ports wired produces a bundle with empty sections`() {
        val store = InMemoryPropositionRepository()
        store.save(proposition("A fact", mentions = listOf(EntityMention("Acme", "Organization", resolvedId = "org-1"))))

        val assembler = KnowledgeBundleAssembler(store)
        val bundle = assembler.forContext(contextId.value)

        assertTrue(bundle.entities.isEmpty())
        assertTrue(bundle.embeddings.isEmpty())
    }

    // -------------------------------------------------------------------------
    // 2. Bundle section round-trip: absent, empty, and populated
    // -------------------------------------------------------------------------

    @Test
    fun `bundle without entities or embeddings round-trips with both sections empty`() {
        val bundle = KnowledgeBundle.from(contextId, listOf(proposition("No sections here")))
        val json = exporter.exportToString(bundle)

        // Old-shape bundles (produced before these fields existed) look exactly like this: the
        // fields are simply absent from the JSON. Simulate that directly rather than relying on
        // the exporter to omit empty collections.
        val oldShapeJson = removeField(removeField(json, "entities"), "embeddings")

        val importer = JacksonKnowledgeBundleImporter()
        val store = InMemoryPropositionRepository()
        val outcome = importer.importFromString(oldShapeJson, store)

        val success = assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        assertEquals(1, success.result.imported)
        assertTrue(success.bundle.entities.isEmpty())
        assertTrue(success.bundle.embeddings.isEmpty())
    }

    @Test
    fun `bundle with explicitly empty sections round-trips cleanly`() {
        val bundle = KnowledgeBundle.from(
            contextId,
            listOf(proposition("Explicit empties")),
            entities = emptyList(),
            embeddings = emptyList(),
        )
        val json = exporter.exportToString(bundle)

        val importer = JacksonKnowledgeBundleImporter()
        val store = InMemoryPropositionRepository()
        val outcome = importer.importFromString(json, store)

        val success = assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        assertEquals(1, success.result.imported)
        assertTrue(success.bundle.entities.isEmpty())
        assertTrue(success.bundle.embeddings.isEmpty())
    }

    @Test
    fun `populated entities and embeddings sections survive export and import parsing`() {
        val prop = proposition("Has a vector", id = "prop-vec")
        val entity = EntitySnapshot(
            id = "org-1",
            type = "Organization",
            properties = mapOf(
                "name" to "Acme | Corp",
                "notes" to "line one\nline two\ttabbed \"quoted\" value",
            ),
        )
        val vector = listOf(0.1f, -0.2f, 3.5f, 0.0f)
        val embeddingEntry = EmbeddingEntry(propositionId = "prop-vec", vectorBase64 = EmbeddingCodec.encode(vector))

        val bundle = KnowledgeBundle.from(
            contextId,
            listOf(prop),
            entities = listOf(entity),
            embeddings = listOf(embeddingEntry),
        )
        val json = exporter.exportToString(bundle)

        val importedEntities = mutableListOf<EntitySnapshot>()
        val importedEmbeddings = mutableListOf<Pair<String, List<Float>>>()
        val importer = JacksonKnowledgeBundleImporter(
            entitySnapshotImporter = EntitySnapshotImporter { snapshots -> importedEntities += snapshots },
            embeddingImporter = EmbeddingImporter { propositionId, v -> importedEmbeddings += propositionId to v },
        )
        val store = InMemoryPropositionRepository()
        val outcome = importer.importFromString(json, store)

        val success = assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        assertEquals(1, success.result.imported)

        // The bundle itself preserved the free-text property values through Jackson escaping —
        // pipes, newlines, tabs, and quotes all round-trip intact.
        assertEquals(1, success.bundle.entities.size)
        assertEquals("Acme | Corp", success.bundle.entities.single().properties["name"])
        assertEquals(
            "line one\nline two\ttabbed \"quoted\" value",
            success.bundle.entities.single().properties["notes"],
        )

        // The ports were actually invoked.
        assertEquals(1, importedEntities.size)
        assertEquals("org-1", importedEntities.single().id)
        assertEquals(1, importedEmbeddings.size)
        assertEquals("prop-vec", importedEmbeddings.single().first)
        assertEquals(vector, importedEmbeddings.single().second)
    }

    // -------------------------------------------------------------------------
    // 3. Importer ordering: entities before propositions, embeddings after
    // -------------------------------------------------------------------------

    @Test
    fun `entities are imported before propositions and embeddings after`() {
        val order = mutableListOf<String>()
        val store = RecordingPropositionStore(InMemoryPropositionRepository()) { order += "proposition:$it" }

        val prop = proposition("Ordered fact", id = "prop-order")
        val entity = EntitySnapshot(id = "entity-order", type = "Thing")
        val embeddingEntry = EmbeddingEntry(propositionId = "prop-order", vectorBase64 = EmbeddingCodec.encode(listOf(1.0f)))

        val bundle = KnowledgeBundle.from(
            contextId,
            listOf(prop),
            entities = listOf(entity),
            embeddings = listOf(embeddingEntry),
        )
        val json = exporter.exportToString(bundle)

        val importer = JacksonKnowledgeBundleImporter(
            entitySnapshotImporter = EntitySnapshotImporter { snapshots ->
                order += "entities:${snapshots.map { it.id }}"
            },
            embeddingImporter = EmbeddingImporter { propositionId, _ ->
                order += "embedding:$propositionId"
            },
        )

        val outcome = importer.importFromString(json, store)
        assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)

        assertEquals(
            listOf("entities:[entity-order]", "proposition:prop-order", "embedding:prop-order"),
            order,
        )
    }

    // -------------------------------------------------------------------------
    // 4. Skipped-with-warning: section present, no port configured
    // -------------------------------------------------------------------------

    @Test
    fun `entities section present with no port configured is noted, not silently dropped`() {
        val prop = proposition("Fact without entity port")
        val entity = EntitySnapshot(id = "orphan-entity", type = "Thing")
        val bundle = KnowledgeBundle.from(contextId, listOf(prop), entities = listOf(entity))
        val json = exporter.exportToString(bundle)

        val importer = JacksonKnowledgeBundleImporter()
        val store = InMemoryPropositionRepository()
        val outcome = importer.importFromString(json, store)

        val success = assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        assertEquals(1, success.result.imported)
        assertTrue(
            success.result.notes.any {
                it.propositionId == "orphan-entity" && it.reason.contains("EntitySnapshotImporter")
            },
            "expected a note about the unhandled entity snapshot, got: ${success.result.notes}",
        )
    }

    @Test
    fun `embeddings section present with no port configured is noted, not silently dropped`() {
        val prop = proposition("Fact without embedding port", id = "prop-no-port")
        val embeddingEntry = EmbeddingEntry(propositionId = "prop-no-port", vectorBase64 = EmbeddingCodec.encode(listOf(1.0f, 2.0f)))
        val bundle = KnowledgeBundle.from(contextId, listOf(prop), embeddings = listOf(embeddingEntry))
        val json = exporter.exportToString(bundle)

        val importer = JacksonKnowledgeBundleImporter()
        val store = InMemoryPropositionRepository()
        val outcome = importer.importFromString(json, store)

        val success = assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        assertEquals(1, success.result.imported)
        assertTrue(
            success.result.notes.any {
                it.propositionId == "prop-no-port" && it.reason.contains("EmbeddingImporter")
            },
            "expected a note about the unhandled embedding, got: ${success.result.notes}",
        )
    }

    // -------------------------------------------------------------------------
    // 5. EmbeddingCodec round-trip
    // -------------------------------------------------------------------------

    @Test
    fun `EmbeddingCodec encode-decode round-trips exactly`() {
        val vector = listOf(0.0f, 1.0f, -1.0f, 3.14159f, -273.15f, Float.MIN_VALUE, Float.MAX_VALUE)
        val encoded = EmbeddingCodec.encode(vector)
        val decoded = EmbeddingCodec.decode(encoded)
        assertEquals(vector, decoded)
    }

    @Test
    fun `EmbeddingCodec round-trips an empty vector`() {
        val encoded = EmbeddingCodec.encode(emptyList())
        assertTrue(EmbeddingCodec.decode(encoded).isEmpty())
    }

    // -------------------------------------------------------------------------
    // 6. Assembler: entities and embeddings populated when ports are wired
    // -------------------------------------------------------------------------

    @Test
    fun `assembler populates entities and embeddings sections when ports are wired`() {
        val store = InMemoryPropositionRepository()
        val mentioned = proposition(
            "Mentions Acme",
            mentions = listOf(EntityMention("Acme", "Organization", resolvedId = "org-1")),
            id = "prop-mentioned",
        )
        store.save(mentioned)

        val entitySnapshotExporter = EntitySnapshotExporter { resolvedIds ->
            resolvedIds.map { EntitySnapshot(id = it, type = "Organization") }
        }
        val embeddingExporter = EmbeddingExporter { propositionId ->
            if (propositionId == "prop-mentioned") listOf(1.0f, 2.0f) else null
        }

        val assembler = KnowledgeBundleAssembler(store, entitySnapshotExporter, embeddingExporter)
        val bundle = assembler.forContext(contextId.value)

        assertEquals(1, bundle.entities.size)
        assertEquals("org-1", bundle.entities.single().id)
        assertEquals(1, bundle.embeddings.size)
        assertEquals("prop-mentioned", bundle.embeddings.single().propositionId)
        assertEquals(listOf(1.0f, 2.0f), EmbeddingCodec.decode(bundle.embeddings.single().vectorBase64))
    }

    @Test
    fun `assembler contributes no entities when no proposition has a resolved mention`() {
        val store = InMemoryPropositionRepository()
        store.save(proposition("Unresolved mention", mentions = listOf(EntityMention("Someone", "Person", resolvedId = null))))

        val entitySnapshotExporter = EntitySnapshotExporter { resolvedIds ->
            throw AssertionError("should not be called with an empty resolvedId set: $resolvedIds")
        }
        val assembler = KnowledgeBundleAssembler(store, entitySnapshotExporter)
        val bundle = assembler.forContext(contextId.value)

        assertTrue(bundle.entities.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Removes a single top-level `"field":...` member from a flat JSON object string (test-only, not a general parser). */
    private fun removeField(json: String, field: String): String {
        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().findAndRegisterModules()
        val node = mapper.readTree(json) as com.fasterxml.jackson.databind.node.ObjectNode
        node.remove(field)
        return mapper.writeValueAsString(node)
    }

    /** Wraps [PropositionStore] to record when [save] is called, for ordering assertions. */
    private class RecordingPropositionStore(
        private val delegate: com.embabel.dice.proposition.PropositionStore,
        private val onSave: (String) -> Unit,
    ) : com.embabel.dice.proposition.PropositionStore by delegate {
        override fun save(proposition: Proposition): Proposition {
            onSave(proposition.id)
            return delegate.save(proposition)
        }

        override fun saveIfAbsent(proposition: Proposition): Proposition? {
            onSave(proposition.id)
            return delegate.saveIfAbsent(proposition)
        }
    }
}

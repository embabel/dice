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
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.dice.bundle.support.JacksonKnowledgeBundleExporter
import com.embabel.dice.bundle.support.JacksonKnowledgeBundleImporter
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.UriLocator
import com.embabel.dice.temporal.TemporalMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.time.Instant

class KnowledgeBundleRoundTripTest {

    private val exporter = JacksonKnowledgeBundleExporter()
    private val importer = JacksonKnowledgeBundleImporter()
    private val contextId = ContextId("test-ctx")

    private fun proposition(text: String, confidence: Double = 0.8): Proposition =
        Proposition(
            contextId = contextId,
            text = text,
            mentions = emptyList(),
            confidence = confidence,
            decay = 0.05,
            status = PropositionStatus.ACTIVE,
        )

    // -------------------------------------------------------------------------
    // Round-trip: export N propositions → JSON → import into fresh store
    // -------------------------------------------------------------------------

    @Test
    fun `round-trip preserves all propositions and their core fields`() {
        val props = listOf(
            proposition("The sky is blue", 0.9),
            proposition("Water is wet", 0.85),
            proposition("Fire is hot", 0.75),
        )
        val bundle = KnowledgeBundle.from(contextId, props, mapOf("author" to "test"))

        val json = exporter.exportToString(bundle)

        val store = InMemoryPropositionRepository()
        val outcome = importer.importFromString(json, store)

        assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        val success = outcome as BundleImportOutcome.Success
        assertEquals(3, success.result.imported)
        assertEquals(0, success.result.skipped)
        assertEquals(0, success.result.rejected)
        assertEquals(3, store.count())

        for (original in props) {
            val restored = store.findById(original.id)
            checkNotNull(restored) { "Expected proposition ${original.id} in store" }
            assertEquals(original.id, restored.id)
            assertEquals(original.contextId, restored.contextId)
            assertEquals(original.text, restored.text)
            assertEquals(original.confidence, restored.confidence)
            assertEquals(original.decay, restored.decay)
            assertEquals(original.status, restored.status)
            assertEquals(original.contentRevised, restored.contentRevised)
        }
    }

    @Test
    fun `round-trip preserves abstraction, pinning, and reinforcement fields`() {
        // A derived (level 1) proposition that is pinned, reinforced, and carries source IDs and
        // reasoning. These fields are easy to drop silently if one is ever excluded from Jackson,
        // so assert each one survives rather than trusting the core-field check above.
        val abstracted = Proposition(
            contextId = contextId,
            text = "Derived insight",
            mentions = emptyList(),
            confidence = 0.7,
            importance = 0.9,
            reasoning = "merged from two observations",
            pinned = true,
            level = 1,
            sourceIds = listOf("src-1", "src-2"),
            reinforceCount = 3,
        )
        val json = exporter.exportToString(KnowledgeBundle.from(contextId, listOf(abstracted)))
        val store = InMemoryPropositionRepository()
        importer.importFromString(json, store)

        val restored = store.findById(abstracted.id)!!
        assertEquals(0.9, restored.importance)
        assertEquals("merged from two observations", restored.reasoning)
        assertTrue(restored.pinned, "pinned flag must survive the round-trip")
        assertEquals(1, restored.level)
        assertEquals(listOf("src-1", "src-2"), restored.sourceIds)
        assertEquals(3, restored.reinforceCount)
    }

    @Test
    fun `round-trip preserves metadata on propositions`() {
        val prop = proposition("Knowledge decays over time").withMetadataValue("dice.trust.score", 0.95)
        val bundle = KnowledgeBundle.from(contextId, listOf(prop))

        val json = exporter.exportToString(bundle)
        val store = InMemoryPropositionRepository()
        importer.importFromString(json, store)

        val restored = store.findById(prop.id)!!
        assertEquals(prop.metadata["dice.trust.score"], restored.metadata["dice.trust.score"])
    }

    @Test
    fun `bundle metadata is preserved through round-trip`() {
        val bundle = KnowledgeBundle.from(contextId, emptyList(), mapOf("env" to "prod", "version" to "42"))
        val json = exporter.exportToString(bundle)
        val store = InMemoryPropositionRepository()
        val outcome = importer.importFromString(json, store) as BundleImportOutcome.Success

        assertEquals("prod", outcome.bundle.metadata["env"])
        assertEquals("42", outcome.bundle.metadata["version"])
        assertEquals(KnowledgeBundle.FORMAT_VERSION, outcome.bundle.formatVersion)
    }

    // -------------------------------------------------------------------------
    // EntityMention.hints round-trip (covers CR-03 type-fidelity)
    // Jackson deserialises Map<String, Any> integer values as Integer (not Long)
    // and floating-point values as Double. These behaviors are documented and tested here.
    // -------------------------------------------------------------------------

    @Test
    fun `round-trip preserves EntityMention with hints map`() {
        val mention = EntityMention(
            span = "Alice",
            type = "Person",
            resolvedId = "person-42",
            role = MentionRole.SUBJECT,
            hints = mapOf(
                "alias" to "Al",
                "confidence" to 0.88,   // Double — survives as Double
                "priority" to 1,        // Int — Jackson deserialises as Integer (not Long)
            ),
        )
        val prop = Proposition(
            contextId = contextId,
            text = "Alice works at Acme",
            mentions = listOf(mention),
            confidence = 0.9,
            decay = 0.05,
            status = PropositionStatus.ACTIVE,
        )
        val bundle = KnowledgeBundle.from(contextId, listOf(prop))
        val json = exporter.exportToString(bundle)
        val store = InMemoryPropositionRepository()
        importer.importFromString(json, store)

        val restored = store.findById(prop.id)!!
        assertEquals(1, restored.mentions.size)
        val restoredMention = restored.mentions.first()

        assertEquals("Alice", restoredMention.span)
        assertEquals("Person", restoredMention.type)
        assertEquals("person-42", restoredMention.resolvedId)
        assertEquals(MentionRole.SUBJECT, restoredMention.role)

        // String hint survives unchanged
        assertEquals("Al", restoredMention.hints["alias"])

        // Double hint survives as Double
        assertEquals(0.88, restoredMention.hints["confidence"] as Double, 1e-9)

        // Jackson deserialises JSON integers in Map<String, Any> as java.lang.Integer
        // (not Long). An original Kotlin Int value will equal the restored Integer via
        // auto-boxing, but callers storing Long values should be aware they will come
        // back as Integer if within Int range.
        val priorityValue = restoredMention.hints["priority"]
        assertNotNull(priorityValue)
        assertEquals(1, (priorityValue as Number).toInt())
    }

    @Test
    fun `round-trip preserves TemporalMetadata`() {
        val validFrom = Instant.parse("2026-01-01T00:00:00Z")
        val validTo = Instant.parse("2026-12-31T23:59:59Z")
        val temporal = TemporalMetadata(
            observedAt = Instant.parse("2025-12-15T10:00:00Z"),
            validFrom = validFrom,
            validTo = validTo,
        )
        val prop = Proposition(
            contextId = contextId,
            text = "Alice leads the project in 2026",
            mentions = emptyList(),
            confidence = 0.9,
            decay = 0.0,
            status = PropositionStatus.ACTIVE,
            temporal = temporal,
        )
        val bundle = KnowledgeBundle.from(contextId, listOf(prop))
        val json = exporter.exportToString(bundle)
        val store = InMemoryPropositionRepository()
        importer.importFromString(json, store)

        val restored = store.findById(prop.id)!!
        assertNotNull(restored.temporal)
        assertEquals(validFrom, restored.temporal!!.validFrom)
        assertEquals(validTo, restored.temporal!!.validTo)
        assertEquals(Instant.parse("2025-12-15T10:00:00Z"), restored.temporal!!.observedAt)
    }

    @Test
    fun `round-trip preserves ProvenanceEntry with UriLocator`() {
        val locator = UriLocator(uri = "https://example.com/doc/42", display = "Meeting notes")
        val entry = ProvenanceEntry(
            locator = locator,
            chunkId = "chunk-7",
            startOffset = 120,
            endOffset = 180,
        )
        val prop = Proposition(
            contextId = contextId,
            text = "The decision was made in the meeting",
            mentions = emptyList(),
            confidence = 0.85,
            decay = 0.05,
            status = PropositionStatus.ACTIVE,
            provenanceEntries = listOf(entry),
        )
        val bundle = KnowledgeBundle.from(contextId, listOf(prop))
        val json = exporter.exportToString(bundle)
        val store = InMemoryPropositionRepository()
        importer.importFromString(json, store)

        val restored = store.findById(prop.id)!!
        assertEquals(1, restored.provenanceEntries.size)
        val restoredEntry = restored.provenanceEntries.first()
        val restoredLocator = restoredEntry.locator as UriLocator

        assertEquals("https://example.com/doc/42", restoredLocator.uri)
        assertEquals("Meeting notes", restoredLocator.display)
        assertEquals("chunk-7", restoredEntry.chunkId)
        assertEquals(120, restoredEntry.startOffset)
        assertEquals(180, restoredEntry.endOffset)
    }

    // -------------------------------------------------------------------------
    // Format-version rejection
    // -------------------------------------------------------------------------

    @Test
    fun `unknown formatVersion is rejected before any store writes`() {
        val bundle = KnowledgeBundle.from(contextId, listOf(proposition("should not land")))
        val json = exporter.exportToString(bundle)
            .replace("\"formatVersion\":\"${KnowledgeBundle.FORMAT_VERSION}\"", "\"formatVersion\":\"99.0\"")

        val store = InMemoryPropositionRepository()
        val outcome = importer.importFromString(json, store)

        assertInstanceOf(BundleImportOutcome.UnknownFormatVersion::class.java, outcome)
        val rejection = outcome as BundleImportOutcome.UnknownFormatVersion
        assertEquals("99.0", rejection.foundVersion)
        assertTrue(KnowledgeBundle.FORMAT_VERSION in rejection.supportedVersions)
        // Nothing must have been written to the store
        assertEquals(0, store.count())
    }

    // -------------------------------------------------------------------------
    // Conflict policy
    // -------------------------------------------------------------------------

    @Test
    fun `SKIP_EXISTING leaves pre-existing proposition unchanged`() {
        val original = proposition("Original text", 0.9)
        val store = InMemoryPropositionRepository()
        store.save(original)

        // Export the same proposition ID with different text
        val incoming = original.copy(text = "Modified text", confidence = 0.5)
        val bundle = KnowledgeBundle.from(contextId, listOf(incoming))
        val json = exporter.exportToString(bundle)

        val outcome = importer.importFromString(json, store, ImportConflictPolicy.SKIP_EXISTING)

        assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        val success = outcome as BundleImportOutcome.Success
        assertEquals(0, success.result.imported)
        assertEquals(1, success.result.skipped)
        // Store still holds original
        assertEquals("Original text", store.findById(original.id)!!.text)
        assertEquals(0.9, store.findById(original.id)!!.confidence)
    }

    @Test
    fun `OVERWRITE replaces pre-existing proposition`() {
        val original = proposition("Original text", 0.9)
        val store = InMemoryPropositionRepository()
        store.save(original)

        val incoming = original.copy(text = "Overwritten text", confidence = 0.3)
        val bundle = KnowledgeBundle.from(contextId, listOf(incoming))
        val json = exporter.exportToString(bundle)

        val outcome = importer.importFromString(json, store, ImportConflictPolicy.OVERWRITE)

        assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        val success = outcome as BundleImportOutcome.Success
        // A replace of a pre-existing proposition is counted as overwritten, not imported, and
        // leaves a note so an idempotency audit can see the destructive write.
        assertEquals(0, success.result.imported)
        assertEquals(1, success.result.overwritten)
        assertEquals(0, success.result.skipped)
        assertEquals(1, success.result.total)
        assertTrue(success.result.notes.single().reason.contains("replaced existing"))
        assertEquals("Overwritten text", store.findById(original.id)!!.text)
        assertEquals(0.3, store.findById(original.id)!!.confidence)
    }

    @Test
    fun `duplicate ID within one bundle is imported once and the repeat is skipped`() {
        val store = InMemoryPropositionRepository()
        val prop = proposition("Only once", 0.8)
        // Same ID twice in a single bundle — KnowledgeBundle.from preserves duplicates.
        val bundle = KnowledgeBundle.from(contextId, listOf(prop, prop.copy(text = "Repeat")))
        val json = exporter.exportToString(bundle)

        val outcome = importer.importFromString(json, store, ImportConflictPolicy.OVERWRITE)

        val success = assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        // The repeat must not double-write or double-count: one import, one skip.
        assertEquals(1, success.result.imported)
        assertEquals(0, success.result.overwritten)
        assertEquals(1, success.result.skipped)
        assertEquals(2, success.result.total)
        assertEquals(1, store.count())
        assertTrue(success.result.notes.any { it.reason.contains("duplicate ID within the same bundle") })
        // The first occurrence is the one that wins.
        assertEquals("Only once", store.findById(prop.id)!!.text)
    }

    // -------------------------------------------------------------------------
    // Malformed input
    // -------------------------------------------------------------------------

    @Test
    fun `malformed JSON returns ParseFailure without throwing`() {
        val store = InMemoryPropositionRepository()
        val outcome = importer.importFromString("{ not valid json }", store)

        assertInstanceOf(BundleImportOutcome.ParseFailure::class.java, outcome)
        assertEquals(0, store.count())
    }

    @Test
    fun `ParseFailure toString includes cause class and message`() {
        val store = InMemoryPropositionRepository()
        val outcome = importer.importFromString("{ not valid json }", store)

        assertInstanceOf(BundleImportOutcome.ParseFailure::class.java, outcome)
        val failure = outcome as BundleImportOutcome.ParseFailure
        val str = failure.toString()
        // Should mention the cause type, not just the reason
        assertTrue(str.contains("caused by"), "Expected cause info in toString: $str")
    }

    // -------------------------------------------------------------------------
    // Size guard (WR-01)
    // -------------------------------------------------------------------------

    @Test
    fun `oversized bundle is rejected before deserialization`() {
        val tinyImporter = JacksonKnowledgeBundleImporter(maxBundleBytes = 10)
        val store = InMemoryPropositionRepository()
        val outcome = tinyImporter.importFromString(
            """{"formatVersion":"1.0","contextId":"x","propositions":[]}""",
            store,
        )

        assertInstanceOf(BundleImportOutcome.ParseFailure::class.java, outcome)
        val failure = outcome as BundleImportOutcome.ParseFailure
        assertTrue(failure.reason.contains("exceeds maximum"), "Expected size-limit message: ${failure.reason}")
        assertEquals(0, store.count())
    }

    @Test
    fun `bundle within the char count but over the byte limit is rejected`() {
        // Five 3-byte UTF-8 characters: 5 chars (under a 10-char count) but 15 bytes (over the 10-byte cap).
        // A char-length guard would wrongly admit this; the byte-length guard must reject it.
        val tinyImporter = JacksonKnowledgeBundleImporter(maxBundleBytes = 10)
        val store = InMemoryPropositionRepository()
        val outcome = tinyImporter.importFromString("界界界界界", store)

        assertInstanceOf(BundleImportOutcome.ParseFailure::class.java, outcome)
        val failure = outcome as BundleImportOutcome.ParseFailure
        assertTrue(failure.reason.contains("exceeds maximum"), "Expected byte-count size rejection: ${failure.reason}")
        assertEquals(0, store.count())
    }

    // -------------------------------------------------------------------------
    // Stream and reader import (WR-02)
    // -------------------------------------------------------------------------

    @Test
    fun `importFromStream produces the same result as importFromString`() {
        val props = listOf(proposition("Stream import works", 0.7))
        val bundle = KnowledgeBundle.from(contextId, props)
        val json = exporter.exportToString(bundle)

        val storeFromString = InMemoryPropositionRepository()
        importer.importFromString(json, storeFromString)

        val storeFromStream = InMemoryPropositionRepository()
        val stream = ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))
        val outcome = importer.importFromStream(stream, storeFromStream)

        assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        val success = outcome as BundleImportOutcome.Success
        assertEquals(1, success.result.imported)
        assertEquals(0, success.result.rejected)
        assertNotNull(storeFromStream.findById(props.first().id))
    }

    @Test
    fun `importFromReader produces the same result as importFromString`() {
        val props = listOf(proposition("Reader import works", 0.7))
        val bundle = KnowledgeBundle.from(contextId, props)
        val json = exporter.exportToString(bundle)

        val store = InMemoryPropositionRepository()
        val outcome = importer.importFromReader(StringReader(json), store)

        assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        val success = outcome as BundleImportOutcome.Success
        assertEquals(1, success.result.imported)
        assertNotNull(store.findById(props.first().id))
    }

    // -------------------------------------------------------------------------
    // deterministic createdAt equality (IN-03)
    // -------------------------------------------------------------------------

    @Test
    fun `bundles from same data with same createdAt are equal`() {
        val ts = Instant.parse("2026-01-01T12:00:00Z")
        val props = listOf(proposition("Deterministic bundle"))
        val b1 = KnowledgeBundle.from(contextId, props, createdAt = ts)
        val b2 = KnowledgeBundle.from(contextId, props, createdAt = ts)
        assertEquals(b1, b2)
    }

    @Test
    fun `bundles from same data with different createdAt are not equal`() {
        val props = listOf(proposition("Non-deterministic bundle"))
        val b1 = KnowledgeBundle.from(contextId, props, createdAt = Instant.parse("2026-01-01T12:00:00Z"))
        val b2 = KnowledgeBundle.from(contextId, props, createdAt = Instant.parse("2026-01-02T12:00:00Z"))
        assertTrue(b1 != b2, "Bundles with different createdAt should not be equal")
    }

    // -------------------------------------------------------------------------
    // Export stream/writer flush (WR-05)
    // -------------------------------------------------------------------------

    @Test
    fun `exportToStream flushes so ByteArrayOutputStream content is complete`() {
        val props = listOf(proposition("Flush test"))
        val bundle = KnowledgeBundle.from(contextId, props)

        val baos = java.io.ByteArrayOutputStream()
        exporter.exportToStream(bundle, baos)
        // If flush were missing, a BufferedOutputStream wrapper could leave bytes in its internal buffer.
        // With a ByteArrayOutputStream there is no buffer to flush, but we verify content is non-empty
        // and parses back correctly as a proxy for flush correctness.
        val json = baos.toString(Charsets.UTF_8)
        assertTrue(json.isNotBlank())

        val store = InMemoryPropositionRepository()
        val outcome = importer.importFromString(json, store)
        assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        assertEquals(1, (outcome as BundleImportOutcome.Success).result.imported)
    }

    @Test
    fun `exportToWriter flushes so StringWriter content is complete`() {
        val props = listOf(proposition("Writer flush test"))
        val bundle = KnowledgeBundle.from(contextId, props)

        val sw = java.io.StringWriter()
        exporter.exportToWriter(bundle, sw)
        val json = sw.toString()
        assertTrue(json.isNotBlank())

        val store = InMemoryPropositionRepository()
        val outcome = importer.importFromString(json, store)
        assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        assertEquals(1, (outcome as BundleImportOutcome.Success).result.imported)
    }

    // -------------------------------------------------------------------------
    // Store save failure → rejection path
    // -------------------------------------------------------------------------

    /**
     * A minimal PropositionStore stub whose save() always throws. Used to exercise the
     * rejection path in importParsedBundle without pulling in Mockito.
     */
    private inner class FailingPropositionStore : PropositionStore {
        override fun save(proposition: Proposition): Proposition =
            throw RuntimeException("simulated save failure")

        override fun findById(id: String): Proposition? = null
        override fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition> = emptyList()
        override fun findByStatus(status: PropositionStatus): List<Proposition> = emptyList()
        override fun findByGrounding(chunkId: String): List<Proposition> = emptyList()
        override fun findByMinLevel(minLevel: Int): List<Proposition> = emptyList()
        override fun findAll(): List<Proposition> = emptyList()
        override fun delete(id: String): Boolean = false
        override fun count(): Int = 0
    }

    @Test
    fun `store save failure counts proposition as rejected with a note`() {
        val props = listOf(proposition("First fact", 0.8), proposition("Second fact", 0.7))
        val bundle = KnowledgeBundle.from(contextId, props)
        val json = exporter.exportToString(bundle)

        val outcome = importer.importFromString(json, FailingPropositionStore())

        assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        val success = outcome as BundleImportOutcome.Success
        assertEquals(0, success.result.imported)
        assertEquals(2, success.result.rejected)
        assertEquals(0, success.result.skipped)
        assertEquals(2, success.result.total)
        assertEquals(2, success.result.notes.size)
        assertTrue(
            success.result.notes.any { it.reason.contains("save failed") },
            "Expected a note mentioning 'save failed': ${success.result.notes}",
        )
    }

    // -------------------------------------------------------------------------
    // SKIP_EXISTING note content
    // -------------------------------------------------------------------------

    @Test
    fun `SKIP_EXISTING note carries the proposition ID and policy name`() {
        val original = proposition("Existing proposition", 0.9)
        val store = InMemoryPropositionRepository()
        store.save(original)

        val bundle = KnowledgeBundle.from(contextId, listOf(original))
        val json = exporter.exportToString(bundle)
        val outcome = importer.importFromString(json, store, ImportConflictPolicy.SKIP_EXISTING)

        assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        val success = outcome as BundleImportOutcome.Success
        assertEquals(1, success.result.notes.size)
        val note = success.result.notes.single()
        assertEquals(original.id, note.propositionId)
        assertTrue(
            note.reason.contains("SKIP_EXISTING"),
            "Note reason should name the policy: ${note.reason}",
        )
    }

    // -------------------------------------------------------------------------
    // UnknownFormatVersion toString
    // -------------------------------------------------------------------------

    @Test
    fun `UnknownFormatVersion toString includes found and supported versions`() {
        val outcome = BundleImportOutcome.UnknownFormatVersion(
            foundVersion = "99.0",
            supportedVersions = setOf("1.0"),
        )
        val str = outcome.toString()
        assertTrue(str.contains("99.0"), "Expected found version in toString: $str")
        assertTrue(str.contains("1.0"), "Expected supported version in toString: $str")
    }

    // -------------------------------------------------------------------------
    // Empty bundle import counts (IN-04)
    // -------------------------------------------------------------------------

    @Test
    fun `empty bundle round-trip yields imported 0 and total 0`() {
        val bundle = KnowledgeBundle.from(contextId, emptyList())
        val json = exporter.exportToString(bundle)
        val store = InMemoryPropositionRepository()

        val outcome = importer.importFromString(json, store)

        assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        val success = outcome as BundleImportOutcome.Success
        assertEquals(0, success.result.imported)
        assertEquals(0, success.result.skipped)
        assertEquals(0, success.result.rejected)
        assertEquals(0, success.result.total)
        assertEquals(0, store.count())
    }
}

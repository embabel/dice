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
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers import re-scoping (`targetContextId`) and the honest OVERWRITE failure reporting that
 * goes with it. Round-trip fidelity and the pre-existing conflict-policy behaviour (no re-scoping)
 * are covered by [KnowledgeBundleRoundTripTest]; this file is scoped to what changes when a caller
 * asks to restore a bundle into a different context than the one it was exported from.
 */
class KnowledgeBundleImportRescopingTest {

    private val exporter = JacksonKnowledgeBundleExporter()
    private val importer = JacksonKnowledgeBundleImporter()
    private val sourceContext = ContextId("ctx-a")
    private val targetContext = ContextId("ctx-b")

    private fun proposition(
        contextId: ContextId,
        text: String,
        confidence: Double = 0.8,
        id: String = java.util.UUID.randomUUID().toString(),
    ): Proposition =
        Proposition(
            id = id,
            contextId = contextId,
            text = text,
            mentions = emptyList(),
            confidence = confidence,
            decay = 0.05,
            status = PropositionStatus.ACTIVE,
        )

    // -------------------------------------------------------------------------
    // 1. Re-scope happy path
    // -------------------------------------------------------------------------

    @Test
    fun `null targetContextId keeps the bundle's own context, matching prior behaviour`() {
        val props = listOf(proposition(sourceContext, "Unscoped import"))
        val bundle = KnowledgeBundle.from(sourceContext, props)
        val json = exporter.exportToString(bundle)
        val store = InMemoryPropositionRepository()

        val outcome = importer.importFromString(json, store, ImportConflictPolicy.SKIP_EXISTING, targetContextId = null)

        val success = assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        assertEquals(1, success.result.imported)
        val restored = store.findById(props.single().id)!!
        assertEquals(sourceContext, restored.contextId)
    }

    @Test
    fun `targetContextId re-scopes every imported proposition into the target context`() {
        val props = listOf(
            proposition(sourceContext, "Fact one"),
            proposition(sourceContext, "Fact two"),
        )
        val bundle = KnowledgeBundle.from(sourceContext, props)
        val json = exporter.exportToString(bundle)
        val store = InMemoryPropositionRepository()

        val outcome = importer.importFromString(
            json,
            store,
            ImportConflictPolicy.SKIP_EXISTING,
            targetContextId = targetContext.value,
        )

        val success = assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        assertEquals(2, success.result.imported)
        for (original in props) {
            val restored = store.findById(original.id)!!
            assertEquals(targetContext, restored.contextId)
            assertEquals(original.text, restored.text)
        }
        // The outcome's bundle still reports the source context — only the persisted rows
        // were re-scoped, not the bundle's own record of where the data came from.
        assertEquals(sourceContext, success.bundle.contextId)
    }

    // -------------------------------------------------------------------------
    // 2. Re-scope + SKIP_EXISTING against target-context duplicates, and no collision with ctx-A
    // -------------------------------------------------------------------------

    @Test
    fun `SKIP_EXISTING re-scoped import skips duplicates already in the target context`() {
        val shared = proposition(sourceContext, "Will collide in target", id = "shared-id")
        val bundle = KnowledgeBundle.from(sourceContext, listOf(shared))
        val json = exporter.exportToString(bundle)

        val store = InMemoryPropositionRepository()
        // ctx-b already has a row under this same id (e.g. a prior partial import).
        store.save(shared.copy(contextId = targetContext, text = "Already in target"))

        val outcome = importer.importFromString(
            json,
            store,
            ImportConflictPolicy.SKIP_EXISTING,
            targetContextId = targetContext.value,
        )

        val success = assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        assertEquals(0, success.result.imported)
        assertEquals(1, success.result.skipped)
        assertEquals("Already in target", store.findById("shared-id")!!.text)
    }

    @Test
    fun `re-scoped import does not collide with an untouched ctx-A row of the same id`() {
        // The bundle's own context (ctx-a) still holds a row with this id — importing the SAME
        // bundle into ctx-b must not be blocked by, or overwrite, that ctx-a row: PropositionStore
        // keys by id alone, so this is exactly the cross-context collision the importer must refuse
        // to touch rather than silently relocate.
        val shared = proposition(sourceContext, "Lives in ctx-a", id = "shared-id")
        val store = InMemoryPropositionRepository()
        store.save(shared)

        val bundle = KnowledgeBundle.from(sourceContext, listOf(shared))
        val json = exporter.exportToString(bundle)

        val outcome = importer.importFromString(
            json,
            store,
            ImportConflictPolicy.SKIP_EXISTING,
            targetContextId = targetContext.value,
        )

        val success = assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        // Neither imported (id collides at the store level) nor silently skipped as an ordinary
        // duplicate — it's rejected, with a note explaining why, and ctx-a's row is untouched.
        assertEquals(0, success.result.imported)
        assertEquals(0, success.result.skipped)
        assertEquals(1, success.result.rejected)
        assertTrue(success.result.notes.single().reason.contains("ctx-a"))
        assertEquals(sourceContext, store.findById("shared-id")!!.contextId)
        assertEquals("Lives in ctx-a", store.findById("shared-id")!!.text)
    }

    @Test
    fun `re-scoped OVERWRITE does not clobber an untouched ctx-A row of the same id`() {
        val shared = proposition(sourceContext, "Lives in ctx-a", id = "shared-id")
        val store = InMemoryPropositionRepository()
        store.save(shared)

        val incoming = shared.copy(text = "Attempted overwrite")
        val bundle = KnowledgeBundle.from(sourceContext, listOf(incoming))
        val json = exporter.exportToString(bundle)

        val outcome = importer.importFromString(
            json,
            store,
            ImportConflictPolicy.OVERWRITE,
            targetContextId = targetContext.value,
        )

        val success = assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        assertEquals(0, success.result.overwritten)
        assertEquals(1, success.result.rejected)
        assertEquals("Lives in ctx-a", store.findById("shared-id")!!.text)
        assertEquals(sourceContext, store.findById("shared-id")!!.contextId)
    }

    // -------------------------------------------------------------------------
    // 3. OVERWRITE failure reporting
    // -------------------------------------------------------------------------

    @Test
    fun `OVERWRITE failure mid-save is reported per-proposition, not silently swallowed`() {
        val existing = proposition(targetContext, "Pre-existing target row", id = "boom-id")
        val delegate = InMemoryPropositionRepository()
        delegate.save(existing)
        val store = FailingSaveStore(delegate, failOnSave = setOf("boom-id"))

        val incoming = proposition(sourceContext, "Incoming overwrite", id = "boom-id")
        val bundle = KnowledgeBundle.from(sourceContext, listOf(incoming))
        val json = exporter.exportToString(bundle)

        val outcome = importer.importFromString(
            json,
            store,
            ImportConflictPolicy.OVERWRITE,
            targetContextId = targetContext.value,
        )

        val success = assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        assertEquals(0, success.result.overwritten)
        assertEquals(1, success.result.rejected)
        assertEquals(listOf("boom-id"), success.result.overwriteFailures)
        val note = success.result.notes.single()
        assertEquals("boom-id", note.propositionId)
        assertTrue(note.reason.contains("overwriteFailures", ignoreCase = true) || note.reason.contains("not guaranteed"))
    }

    @Test
    fun `OVERWRITE success leaves overwriteFailures empty`() {
        val existing = proposition(sourceContext, "Original", id = "ok-id")
        val store = InMemoryPropositionRepository()
        store.save(existing)

        val incoming = existing.copy(text = "Replaced")
        val bundle = KnowledgeBundle.from(sourceContext, listOf(incoming))
        val json = exporter.exportToString(bundle)

        val outcome = importer.importFromString(json, store, ImportConflictPolicy.OVERWRITE)

        val success = assertInstanceOf(BundleImportOutcome.Success::class.java, outcome)
        assertEquals(1, success.result.overwritten)
        assertEquals(0, success.result.rejected)
        assertTrue(success.result.overwriteFailures.isEmpty())
    }
}

/**
 * Wraps a [PropositionRepository] and throws on [save] for a configured set of ids, so tests can
 * simulate a store that fails partway through an OVERWRITE — after the importer has already looked
 * the existing row up via `findById` but before the replacement lands.
 */
private class FailingSaveStore(
    private val delegate: PropositionRepository,
    private val failOnSave: Set<String>,
) : PropositionRepository by delegate {
    override fun save(proposition: Proposition): Proposition {
        if (proposition.id in failOnSave) {
            throw RuntimeException("simulated store failure while saving ${proposition.id}")
        }
        return delegate.save(proposition)
    }
}

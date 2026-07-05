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
package com.embabel.dice.metamodel

import com.embabel.agent.core.ContextId
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.metamodel.support.MentionTypeDriftQuarantinePolicy
import com.embabel.dice.metamodel.support.StructuralMetamodelDiffer
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Unit tests for [DefaultDriftCheckRunner] against fake [ObservedSchemaSource]/[DeclaredSchemaSource]/
 * [MetamodelStore] doubles, the real [StructuralMetamodelDiffer] (already covered on its own), and
 * the real [MentionTypeDriftQuarantinePolicy] (also covered on its own) so these tests exercise the
 * actual delegation, not a stand-in for it.
 */
class DriftCheckRunnerTest {

    private val contextId = ContextId("test-context")
    private val schemaName = "test-schema"

    private lateinit var store: FakeMetamodelStore
    private lateinit var propositionRepository: InMemoryPropositionRepository
    private lateinit var runner: DriftCheckRunner

    /** Declared schema: two entity types, one relationship. Overridable per test. */
    private var declaredEntityTypes = listOf("Person", "Company")
    private var declaredRelationshipTypeNames = setOf("WORKS_AT")

    /** Observed schema: overridable per test. Defaults to matching the declared schema exactly. */
    private var observedEntityTypes = setOf("Person", "Company")
    private var observedRelationshipTypeNames = setOf("WORKS_AT")

    @BeforeEach
    fun setUp() {
        store = FakeMetamodelStore()
        propositionRepository = InMemoryPropositionRepository()
    }

    private fun buildRunner(repo: PropositionRepository = propositionRepository): DriftCheckRunner {
        val declaredVersion = MetamodelVersion(
            schemaName = schemaName,
            contentHash = "hash-$schemaName",
            entityTypeNames = declaredEntityTypes,
            entityTypeLabels = declaredEntityTypes.associateWith { emptySet() },
            entityTypeProperties = declaredEntityTypes.associateWith { emptySet() },
            relationshipNames = declaredRelationshipTypeNames.map { "Person-[$it]->Company" },
        )
        val declaredSchema = DeclaredSchema(
            version = declaredVersion,
            relationshipTypeNames = declaredRelationshipTypeNames,
        )
        val observedSchema = ObservedSchema(
            entityTypeNames = observedEntityTypes,
            relationshipTypeNames = observedRelationshipTypeNames,
            capturedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )
        return DefaultDriftCheckRunner(
            observedSchemaSource = object : ObservedSchemaSource {
                override fun observe(contextId: ContextId?): ObservedSchema = observedSchema
            },
            declaredSchemaSource = DeclaredSchemaSource { declaredSchema },
            differ = StructuralMetamodelDiffer(),
            store = store,
            quarantinePolicy = MentionTypeDriftQuarantinePolicy(),
            propositionRepository = repo,
        )
    }

    private fun proposition(text: String, vararg mentionTypes: String): Proposition = Proposition(
        contextId = contextId,
        text = text,
        mentions = mentionTypes.map { type -> EntityMention(span = type.lowercase(), type = type, role = MentionRole.SUBJECT) },
        confidence = 0.9,
    )

    @Test
    fun `zero drift still persists a retrievable report`() {
        runner = buildRunner()

        val result = runner.run(dryRun = true)

        assertTrue(result.driftedEntityTypes.isEmpty())
        assertTrue(result.driftedRelationshipTypes.isEmpty())
        assertEquals(0, result.quarantinedCount)
        assertTrue(result.dryRun)

        val reports = store.driftReports(schemaName)
        assertEquals(1, reports.size, "even a clean check must leave a record behind")
        assertEquals(emptySet<String>(), reports.single().driftingEntityTypes)
        assertEquals(emptySet<String>(), reports.single().driftingRelationshipTypes)
        assertEquals(result.reportRef, reports.single())
    }

    @Test
    fun `drift with dryRun persists the report but quarantines nothing`() {
        observedEntityTypes = setOf("Person", "Company", "GhostType")
        propositionRepository.save(proposition("a ghost was mentioned", "GhostType"))
        runner = buildRunner()

        val result = runner.run(dryRun = true)

        assertEquals(setOf("GhostType"), result.driftedEntityTypes)
        assertEquals(0, result.quarantinedCount)
        assertTrue(result.dryRun)

        // The report is on record even though nothing was quarantined.
        val report = store.driftReports(schemaName).single()
        assertEquals(setOf("GhostType"), report.driftingEntityTypes)

        // No proposition was touched: the dry run applied no transition.
        val stillActive = propositionRepository.findAll().single()
        assertEquals(PropositionStatus.ACTIVE, stillActive.status)
        assertNull(stillActive.metadata[DiceMetadataKeys.QUARANTINE_REASON])
    }

    @Test
    fun `drift with a live run delegates quarantine to the configured policy`() {
        observedEntityTypes = setOf("Person", "Company", "GhostType")
        val affected = propositionRepository.save(proposition("a ghost was mentioned", "GhostType"))
        val safe = propositionRepository.save(proposition("Alice works at Acme", "Person", "Company"))
        runner = buildRunner()

        val result = runner.run(dryRun = false)

        assertEquals(setOf("GhostType"), result.driftedEntityTypes)
        assertEquals(1, result.quarantinedCount)
        assertTrue(!result.dryRun)

        val quarantined = propositionRepository.findById(affected.id)!!
        assertEquals(PropositionStatus.STALE, quarantined.status)
        assertNotNull(quarantined.metadata[DiceMetadataKeys.QUARANTINE_REASON])

        val untouched = propositionRepository.findById(safe.id)!!
        assertEquals(PropositionStatus.ACTIVE, untouched.status)

        // The report is still persisted, same as the dry-run case.
        assertEquals(1, store.driftReports(schemaName).size)
    }

    @Test
    fun `a live run with no entity-type drift never consults the quarantine policy`() {
        // Relationship-only drift: nothing a mention's type could ever match, so nothing should be
        // quarantined even on a live run.
        observedRelationshipTypeNames = setOf("WORKS_AT", "UNDECLARED_LINK")
        propositionRepository.save(proposition("Alice works at Acme", "Person", "Company"))
        runner = buildRunner()

        val result = runner.run(dryRun = false)

        assertTrue(result.driftedEntityTypes.isEmpty())
        assertEquals(setOf("UNDECLARED_LINK"), result.driftedRelationshipTypes)
        assertEquals(0, result.quarantinedCount)

        val untouched = propositionRepository.findAll().single()
        assertEquals(PropositionStatus.ACTIVE, untouched.status)
    }

    @Test
    fun `declared relationship type names with pipes, tabs, and newlines flow through untouched`() {
        val delimiterLaden = setOf("REL|WITH|PIPE", "REL\tWITH\tTAB", "REL\nWITH\nNEWLINE")
        declaredRelationshipTypeNames = delimiterLaden
        observedRelationshipTypeNames = delimiterLaden + "UNDECLARED|ALSO\tDELIMITED"
        runner = buildRunner()

        val result = runner.run(dryRun = true)

        // The declared names must reach the differ exactly as supplied -- no splitting, trimming,
        // or delimiter-based parsing -- so only the genuinely undeclared name shows up as drift.
        assertEquals(setOf("UNDECLARED|ALSO\tDELIMITED"), result.driftedRelationshipTypes)
        val report = store.driftReports(schemaName).single()
        assertEquals(setOf("UNDECLARED|ALSO\tDELIMITED"), report.driftingRelationshipTypes)
    }

    // ---- Context scoping ----

    @Test
    fun `a scoped run reads candidates via findByContextId, not findAll`() {
        observedEntityTypes = setOf("Person", "Company", "GhostType")
        propositionRepository.save(proposition("a ghost was mentioned", "GhostType"))
        val recording = RecordingPropositionRepository(propositionRepository)
        runner = buildRunner(recording)

        val result = runner.run(dryRun = false, contextId = contextId)

        assertEquals(contextId, recording.findByContextIdCall)
        assertNull(recording.findAllCall)
        assertEquals(contextId, result.contextId)
        assertEquals(contextId, result.reportRef.contextId)
    }

    @Test
    fun `a global run (no contextId) reads candidates via findAll, not findByContextId`() {
        observedEntityTypes = setOf("Person", "Company", "GhostType")
        propositionRepository.save(proposition("a ghost was mentioned", "GhostType"))
        val recording = RecordingPropositionRepository(propositionRepository)
        runner = buildRunner(recording)

        val result = runner.run(dryRun = false)

        assertTrue(recording.findAllCall == true)
        assertNull(recording.findByContextIdCall)
        assertNull(result.contextId)
        assertNull(result.reportRef.contextId)
    }

    @Test
    fun `a dry-run scoped check still stamps the report with the context, even with nothing to quarantine`() {
        runner = buildRunner()

        val result = runner.run(dryRun = true, contextId = contextId)

        assertEquals(contextId, result.contextId)
        assertEquals(contextId, result.reportRef.contextId)
        assertEquals(contextId, store.driftReports(schemaName).single().contextId)
    }

    /**
     * Records which candidate-read method [DefaultDriftCheckRunner] actually called, so the tests
     * above can assert the scoped/global read path directly rather than inferring it from a side
     * effect. Delegates everything else to the wrapped [InMemoryPropositionRepository] unchanged.
     */
    private class RecordingPropositionRepository(
        private val delegate: PropositionRepository,
    ) : PropositionRepository by delegate {

        var findAllCall: Boolean? = null
            private set
        var findByContextIdCall: ContextId? = null
            private set

        override fun findAll(): List<Proposition> {
            findAllCall = true
            return delegate.findAll()
        }

        override fun findByContextId(contextId: ContextId): List<Proposition> {
            findByContextIdCall = contextId
            return delegate.findByContextId(contextId)
        }
    }

    /** Minimal in-memory [MetamodelStore] fake: append-only, newest first, no deletion. */
    private class FakeMetamodelStore : MetamodelStore {
        private val versions = mutableListOf<MetamodelVersion>()
        private val reports = mutableListOf<DriftReport>()

        override fun saveVersion(version: MetamodelVersion) {
            if (versions.none { it.schemaName == version.schemaName && it.contentHash == version.contentHash }) {
                versions.add(0, version)
            }
        }

        override fun latestVersion(schemaName: String): MetamodelVersion? =
            versions.firstOrNull { it.schemaName == schemaName }

        override fun versionHistory(schemaName: String): List<MetamodelVersion> =
            versions.filter { it.schemaName == schemaName }

        override fun saveDriftReport(report: DriftReport) {
            reports.add(0, report)
        }

        override fun driftReports(schemaName: String): List<DriftReport> =
            reports.filter { it.schemaName == schemaName }
    }
}

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
import com.embabel.dice.metamodel.support.DefaultDriftCheckRunner
import com.embabel.dice.metamodel.support.MentionTypeDriftQuarantinePolicy
import com.embabel.dice.metamodel.support.StructuralMetamodelDiffer
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * [DefaultDriftCheckRunner] against fake sources and stores, with the real
 * [StructuralMetamodelDiffer] and [MentionTypeDriftQuarantinePolicy], each covered on its own
 * elsewhere. These tests exercise the actual delegation rather than a stand-in for it.
 */
class DriftCheckRunnerTest {

    private val contextId = ContextId("test-context")
    private val otherContextId = ContextId("other-context")
    private val schemaName = "test-schema"
    private val capturedAt = Instant.parse("2026-01-01T00:00:00Z")

    private lateinit var versionStore: InMemoryMetamodelVersionStore
    private lateinit var reportStore: OrderRecordingDriftReportStore
    private lateinit var propositionStore: InMemoryPropositionRepository

    /** Declared schema, overridable per test. Defaults to two types and one relationship. */
    private var declaredEntityTypes = listOf("Person", "Company")
    private var declaredEntityTypeLabels: Map<String, Set<String>>? = null
    private var declaredRelationshipTypeNames = setOf("WORKS_AT")

    /** Observed schema, overridable per test. Defaults to matching the declaration exactly. */
    private var observedEntityTypes = setOf("Person", "Company")
    private var observedRelationshipTypeNames = setOf("WORKS_AT")

    @BeforeEach
    fun setUp() {
        versionStore = InMemoryMetamodelVersionStore()
        reportStore = OrderRecordingDriftReportStore(versionStore)
        propositionStore = InMemoryPropositionRepository()
    }

    private fun declaredVersion(): MetamodelVersion = MetamodelVersion(
        schemaName = schemaName,
        entityTypeNames = declaredEntityTypes,
        entityTypeLabels = declaredEntityTypeLabels ?: declaredEntityTypes.associateWith { setOf(it) },
        entityTypeProperties = declaredEntityTypes.associateWith { emptySet() },
        relationshipNames = declaredRelationshipTypeNames.map { "Person-[$it]->Company" },
    )

    // Typed as the base persistence port rather than PropositionRepository, so whatever a test
    // passes in, the runner only gets store-and-retrieve out of it.
    private fun buildRunner(store: PropositionStore = propositionStore): DriftCheckRunner {
        val declaredSchema = DeclaredSchema(
            version = declaredVersion(),
            relationshipTypeNames = declaredRelationshipTypeNames,
        )
        return DefaultDriftCheckRunner(
            declaredSchemaSource = DeclaredSchemaSource { declaredSchema },
            versionStore = versionStore,
            // Each snapshot gets its own instant, a minute apart, the way real observations do. A
            // report's natural key includes the capture instant, so two checks sharing one count as
            // the same observation and collapse to a single record.
            observedSchemaSource = object : ObservedSchemaSource {
                private var observations = 0L

                override fun observe(contextId: ContextId?): ObservedSchema = ObservedSchema(
                    entityTypeNames = observedEntityTypes,
                    relationshipTypeNames = observedRelationshipTypeNames,
                    capturedAt = capturedAt.plusSeconds(60 * observations++),
                )
            },
            differ = StructuralMetamodelDiffer(),
            driftReportStore = reportStore,
            quarantinePolicy = MentionTypeDriftQuarantinePolicy(),
            propositionStore = store,
        )
    }

    private fun proposition(
        text: String,
        vararg mentionTypes: String,
        inContext: ContextId = contextId,
    ): Proposition = Proposition(
        contextId = inContext,
        text = text,
        mentions = mentionTypes.map { type ->
            EntityMention(span = type.lowercase(), type = type, role = MentionRole.SUBJECT)
        },
        confidence = 0.9,
    )

    private fun savedReports(): List<DriftReport> = reportStore.driftReports(schemaName, limit = 100)

    // ---- Stamping ----

    @Test
    fun `every run stamps the declared version, so the report's hash resolves`() {
        val runner = buildRunner()

        val result = runner.run(dryRun = true)

        val resolved = versionStore.findVersion(schemaName, result.report.versionHash)
        assertNotNull(resolved, "a report's versionHash is useless if nothing ever recorded that stamp")
        assertEquals(declaredVersion(), resolved)
    }

    @Test
    fun `the stamp is written before the report, not after`() {
        // The ordering is why the stamp is written every run. A report written first would name a
        // version hash nothing had recorded, for the length of that window, and permanently if the
        // second write failed.
        val runner = buildRunner()

        runner.run(dryRun = true)

        assertTrue(
            reportStore.versionWasResolvableWhenReportSaved.single(),
            "the declared version must already be in the version store when the report is written",
        )
    }

    @Test
    fun `a repeated run re-stamps idempotently rather than growing history`() {
        val runner = buildRunner()

        runner.run(dryRun = true)
        runner.run(dryRun = true)
        runner.run(dryRun = true)

        assertEquals(3, versionStore.saveCount, "the stamp is attempted every run")
        assertEquals(1, versionStore.versionHistory(schemaName).size, "but an unchanged schema stores once")
        assertEquals(3, savedReports().size, "while every check leaves its own report")
    }

    // ---- Reporting ----

    @Test
    fun `zero drift still leaves a retrievable report`() {
        val runner = buildRunner()

        val result = runner.run(dryRun = true)

        assertFalse(result.hasDrift)
        assertTrue(result.driftedEntityTypes.isEmpty())
        assertTrue(result.driftedRelationshipTypes.isEmpty())
        assertEquals(0, result.quarantinedCount)
        assertTrue(result.dryRun)

        val reports = savedReports()
        assertEquals(1, reports.size, "even a clean check must leave a record behind")
        assertEquals(result.report, reports.single())
        assertFalse(reports.single().hasDrift)
    }

    @Test
    fun `the report is stamped with the snapshot's instant, not the write's`() {
        observedEntityTypes = setOf("Person", "Company", "GhostType")
        val runner = buildRunner()

        val result = runner.run(dryRun = true)

        assertEquals(capturedAt, result.report.capturedAt)
    }

    @Test
    fun `the result reads its drift straight off the report it saved`() {
        observedEntityTypes = setOf("Person", "Company", "GhostType")
        observedRelationshipTypeNames = setOf("WORKS_AT", "UNDECLARED_LINK")
        val runner = buildRunner()

        val result = runner.run(dryRun = true)

        val saved = savedReports().single()
        assertEquals(saved.driftedEntityTypes, result.driftedEntityTypes)
        assertEquals(saved.driftedRelationshipTypes, result.driftedRelationshipTypes)
        assertEquals(saved.schemaName, result.schemaName)
        assertEquals(saved.contextId, result.contextId)
    }

    // ---- Dry run vs. live ----

    @Test
    fun `drift with dryRun persists the report but quarantines nothing`() {
        observedEntityTypes = setOf("Person", "Company", "GhostType")
        propositionStore.save(proposition("a ghost was mentioned", "GhostType"))
        val runner = buildRunner()

        val result = runner.run(dryRun = true)

        assertEquals(setOf("GhostType"), result.driftedEntityTypes)
        assertEquals(0, result.quarantinedCount)
        assertTrue(result.dryRun)
        assertEquals(setOf("GhostType"), savedReports().single().driftedEntityTypes)

        val untouched = propositionStore.findAll().single()
        assertEquals(PropositionStatus.ACTIVE, untouched.status)
        assertNull(untouched.metadata[DiceMetadataKeys.QUARANTINE_REASON])
    }

    @Test
    fun `the default run is a dry whole-graph check`() {
        observedEntityTypes = setOf("Person", "Company", "GhostType")
        propositionStore.save(proposition("a ghost was mentioned", "GhostType"))
        val runner = buildRunner()

        val result = runner.run()

        assertTrue(result.dryRun, "the no-argument form must be the safe one")
        assertNull(result.contextId)
        assertEquals(PropositionStatus.ACTIVE, propositionStore.findAll().single().status)
    }

    @Test
    fun `a live run delegates quarantine to the configured policy and persists what it flags`() {
        observedEntityTypes = setOf("Person", "Company", "GhostType")
        val affected = propositionStore.save(proposition("a ghost was mentioned", "GhostType"))
        val safe = propositionStore.save(proposition("Alice works at Acme", "Person", "Company"))
        val runner = buildRunner()

        val result = runner.run(dryRun = false)

        assertEquals(setOf("GhostType"), result.driftedEntityTypes)
        assertEquals(1, result.quarantinedCount)
        assertFalse(result.dryRun)

        val quarantined = propositionStore.findById(affected.id)!!
        assertEquals(PropositionStatus.STALE, quarantined.status)
        assertNotNull(quarantined.metadata[DiceMetadataKeys.QUARANTINE_REASON])

        assertEquals(PropositionStatus.ACTIVE, propositionStore.findById(safe.id)!!.status)
        assertEquals(1, savedReports().size, "the report is written on a live run just the same")
    }

    @Test
    fun `a plain store-and-retrieve backend can drive a live run`() {
        // The runner asks for the base persistence port, so a backend with no vector search, graph
        // traversal or temporal query can still check for drift.
        observedEntityTypes = setOf("Person", "Company", "GhostType")
        val affected = propositionStore.save(proposition("a ghost was mentioned", "GhostType"))
        val bareStore: PropositionStore = RecordingPropositionStore(propositionStore)
        val runner = buildRunner(bareStore)

        val result = runner.run(dryRun = false)

        assertEquals(1, result.quarantinedCount)
        assertEquals(PropositionStatus.STALE, propositionStore.findById(affected.id)!!.status)
    }

    @Test
    fun `a live run with only relationship drift never quarantines`() {
        // Nothing a mention's type can match, so a live run must touch nothing.
        observedRelationshipTypeNames = setOf("WORKS_AT", "UNDECLARED_LINK")
        propositionStore.save(proposition("Alice works at Acme", "Person", "Company"))
        val runner = buildRunner()

        val result = runner.run(dryRun = false)

        assertTrue(result.driftedEntityTypes.isEmpty())
        assertEquals(setOf("UNDECLARED_LINK"), result.driftedRelationshipTypes)
        assertEquals(0, result.quarantinedCount)
        assertEquals(PropositionStatus.ACTIVE, propositionStore.findAll().single().status)
    }

    // ---- Label closure ----

    @Test
    fun `an inherited label observed in the graph is not drift and never quarantines`() {
        // Declaring Person with parent Agent puts both labels on every Person node, so the graph
        // reports Agent too. Comparing observed labels against type names alone would report Agent
        // as undeclared and quarantine sound propositions on a schema nobody had touched.
        declaredEntityTypes = listOf("Person")
        declaredEntityTypeLabels = mapOf("Person" to setOf("Person", "Agent"))
        observedEntityTypes = setOf("Person", "Agent", "GhostType")
        val agentMention = propositionStore.save(proposition("Alice acts", "Agent"))
        val ghostMention = propositionStore.save(proposition("a ghost was mentioned", "GhostType"))
        val runner = buildRunner()

        val result = runner.run(dryRun = false)

        assertEquals(setOf("GhostType"), result.driftedEntityTypes, "the inherited label is declared")
        assertEquals(1, result.quarantinedCount)
        assertEquals(
            PropositionStatus.ACTIVE,
            propositionStore.findById(agentMention.id)!!.status,
            "a proposition mentioning an inherited label must survive a live run",
        )
        assertEquals(PropositionStatus.STALE, propositionStore.findById(ghostMention.id)!!.status)
    }

    @Test
    fun `a declared type with no data is reported as unobserved, never as drift`() {
        observedEntityTypes = setOf("Person")
        propositionStore.save(proposition("Alice is a person", "Person"))
        val runner = buildRunner()

        val result = runner.run(dryRun = false)

        assertTrue(result.driftedEntityTypes.isEmpty(), "declared-but-empty is an ordinary state")
        assertEquals(0, result.quarantinedCount)
    }

    // ---- Names that look like delimiters ----

    @Test
    fun `declared relationship names with pipes, tabs and newlines flow through untouched`() {
        val delimiterLaden = setOf("REL|WITH|PIPE", "REL\tWITH\tTAB", "REL\nWITH\nNEWLINE")
        declaredRelationshipTypeNames = delimiterLaden
        observedRelationshipTypeNames = delimiterLaden + "UNDECLARED|ALSO\tDELIMITED"
        val runner = buildRunner()

        val result = runner.run(dryRun = true)

        // The declared names must reach the differ exactly as supplied, with no splitting, trimming
        // or delimiter parsing, so only the undeclared name shows up as drift.
        assertEquals(setOf("UNDECLARED|ALSO\tDELIMITED"), result.driftedRelationshipTypes)
        assertEquals(setOf("UNDECLARED|ALSO\tDELIMITED"), savedReports().single().driftedRelationshipTypes)
    }

    // ---- Context scoping ----

    @Test
    fun `a scoped run reads candidates via findByContextId, not findAll`() {
        observedEntityTypes = setOf("Person", "Company", "GhostType")
        propositionStore.save(proposition("a ghost was mentioned", "GhostType"))
        val recording = RecordingPropositionStore(propositionStore)
        val runner = buildRunner(recording)

        val result = runner.run(dryRun = false, contextId = contextId)

        assertEquals(contextId, recording.findByContextIdCall)
        assertNull(recording.findAllCall)
        assertEquals(contextId, result.contextId)
        assertEquals(contextId, result.report.contextId)
    }

    @Test
    fun `an unscoped run reads candidates via findAll, not findByContextId`() {
        observedEntityTypes = setOf("Person", "Company", "GhostType")
        propositionStore.save(proposition("a ghost was mentioned", "GhostType"))
        val recording = RecordingPropositionStore(propositionStore)
        val runner = buildRunner(recording)

        val result = runner.run(dryRun = false)

        assertEquals(true, recording.findAllCall)
        assertNull(recording.findByContextIdCall)
        assertNull(result.contextId)
        assertNull(result.report.contextId)
    }

    @Test
    fun `a scoped live run leaves another context's propositions completely alone`() {
        // Both propositions mention the drifted type, and a global run would quarantine both.
        // Scoping to one context must reach exactly one of them.
        observedEntityTypes = setOf("Person", "Company", "GhostType")
        val inScope = propositionStore.save(proposition("a ghost in context A", "GhostType"))
        val outOfScope = propositionStore.save(
            proposition("a ghost in context B", "GhostType", inContext = otherContextId),
        )
        val runner = buildRunner()

        val result = runner.run(dryRun = false, contextId = contextId)

        assertEquals(setOf("GhostType"), result.driftedEntityTypes)
        assertEquals(1, result.quarantinedCount, "only context A's proposition is a candidate")
        assertEquals(PropositionStatus.STALE, propositionStore.findById(inScope.id)!!.status)

        val untouched = propositionStore.findById(outOfScope.id)!!
        assertEquals(
            PropositionStatus.ACTIVE,
            untouched.status,
            "a check scoped to one context must not be able to reach another's propositions",
        )
        assertNull(untouched.metadata[DiceMetadataKeys.QUARANTINE_REASON])
    }

    @Test
    fun `a scoped dry check still stamps the report with its context`() {
        val runner = buildRunner()

        val result = runner.run(dryRun = true, contextId = contextId)

        assertEquals(contextId, result.contextId)
        assertEquals(contextId, result.report.contextId)
        assertEquals(
            listOf(result.report),
            reportStore.driftReportsInContext(schemaName, contextId, limit = 10),
            "and it must come back from the context-scoped read",
        )
        assertTrue(
            reportStore.globalDriftReports(schemaName, limit = 10).isEmpty(),
            "a scoped check is not a global one",
        )
    }

    /**
     * Records which candidate-read the runner called, so a test can assert the scoped or global read
     * path directly rather than inferring it from a side effect. Everything else is delegated
     * unchanged.
     *
     * A bare [PropositionStore] rather than a `PropositionRepository`: passing one of these to the
     * runner is what shows a plain store-and-retrieve backend, with no vector search or graph
     * traversal, can drive a live drift check.
     */
    private class RecordingPropositionStore(
        private val delegate: PropositionStore,
    ) : PropositionStore by delegate {

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

    /**
     * An [InMemoryDriftReportStore] that also notes, at the moment each report is written, whether
     * the version store can already resolve the hash that report carries. That is the only instant
     * at which the stamp-before-report ordering can be observed to hold or fail.
     */
    private class OrderRecordingDriftReportStore(
        private val versionStore: MetamodelVersionStore,
        private val delegate: InMemoryDriftReportStore = InMemoryDriftReportStore(),
    ) : DriftReportStore by delegate {

        val versionWasResolvableWhenReportSaved = mutableListOf<Boolean>()

        override fun saveDriftReport(report: DriftReport) {
            versionWasResolvableWhenReportSaved +=
                versionStore.findVersion(report.schemaName, report.versionHash) != null
            delegate.saveDriftReport(report)
        }
    }
}

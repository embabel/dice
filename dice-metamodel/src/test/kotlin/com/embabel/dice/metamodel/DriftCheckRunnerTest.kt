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

import com.embabel.agent.core.Cardinality
import com.embabel.agent.core.ContextId
import com.embabel.dice.metamodel.support.DefaultDriftCheckRunner
import com.embabel.dice.metamodel.support.StructuralMetamodelDiffer
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
 * [StructuralMetamodelDiffer], which is covered on its own elsewhere. These tests exercise the
 * actual delegation and never a stand-in for it.
 *
 * A check reports and changes nothing, so everything here is about what reaches the report and what
 * the runner asks its stores for. Sweeping lives in `DriftSweepTest`.
 */
class DriftCheckRunnerTest {

    private val contextId = ContextId("test-context")
    private val schemaName = "test-schema"
    private val capturedAt = Instant.parse("2026-01-01T00:00:00Z")

    private lateinit var versionStore: InMemoryMetamodelVersionStore
    private lateinit var reportStore: OrderRecordingDriftReportStore

    /** Declared schema, overridable per test. Defaults to two types and one relationship. */
    private var declaredEntityTypes = listOf("Person", "Company")
    private var declaredEntityTypeLabels: Map<String, Set<String>>? = null
    private var declaredEntityTypeProperties: Map<String, Set<PropertySignature>>? = null
    private var declaredRelationshipTypeNames = setOf("WORKS_AT")

    /** Observed schema, overridable per test. Defaults to matching the declaration exactly. */
    private var observedEntityTypes = setOf("Person", "Company")
    private var observedRelationshipTypeNames = setOf("WORKS_AT")

    @BeforeEach
    fun setUp() {
        versionStore = InMemoryMetamodelVersionStore()
        reportStore = OrderRecordingDriftReportStore(versionStore)
    }

    private fun declaredVersion(): MetamodelVersion = MetamodelVersion(
        schemaName = schemaName,
        entityTypeNames = declaredEntityTypes,
        entityTypeLabels = declaredEntityTypeLabels ?: declaredEntityTypes.associateWith { setOf(it) },
        entityTypeProperties = declaredEntityTypeProperties ?: declaredEntityTypes.associateWith { emptySet() },
        relationshipNames = declaredRelationshipTypeNames.map { "Person-[$it]->Company" },
    )

    private fun buildRunner(
        versionStore: MetamodelVersionStore = this.versionStore,
    ): DriftCheckRunner {
        val declaredSchema = DeclaredSchema(
            version = declaredVersion(),
            relationshipTypeNames = declaredRelationshipTypeNames,
        )
        val differ = StructuralMetamodelDiffer()
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
            // StructuralMetamodelDiffer implements both differ interfaces; one instance plays both
            // roles here exactly as the class doc says a real wiring ordinarily does.
            differ = differ,
            metamodelDiffer = differ,
            driftReportStore = reportStore,
        )
    }

    /** A previous stamp for `schemaName`: `Person{age}` plus a bare `Company`. Overridable per test. */
    private fun previousVersionWithPersonAge(
        entityTypeNames: List<String> = listOf("Person", "Company"),
    ): MetamodelVersion = MetamodelVersion(
        schemaName = schemaName,
        entityTypeNames = entityTypeNames,
        entityTypeLabels = entityTypeNames.associateWith { setOf(it) },
        entityTypeProperties = entityTypeNames.associateWith { emptySet<PropertySignature>() } +
            mapOf("Person" to setOf(PropertySignature("age", PropertySignature.Kind.VALUE, "string", Cardinality.ONE))),
        relationshipNames = declaredRelationshipTypeNames.map { "Person-[$it]->Company" },
    )

    private fun savedReports(): List<DriftReport> = reportStore.driftReports(schemaName, limit = 100)

    // ---- Stamping ----

    @Test
    fun `every run stamps the declared version, so the report's hash resolves`() {
        val runner = buildRunner()

        val result = runner.run()

        val resolved = versionStore.findVersion(schemaName, result.report.versionHash)
        assertNotNull(resolved, "a report's versionHash is useless if nothing ever recorded that stamp")
        assertEquals(declaredVersion(), resolved)
    }

    @Test
    fun `the stamp is written before the report`() {
        // The ordering is why the stamp is written every run. A report written first would name a
        // version hash nothing had recorded, for the length of that window, and permanently if the
        // second write failed.
        val runner = buildRunner()

        runner.run()

        assertTrue(
            reportStore.versionWasResolvableWhenReportSaved.single(),
            "the declared version must already be in the version store when the report is written",
        )
    }

    @Test
    fun `a repeated run re-stamps idempotently and leaves history one record long`() {
        val runner = buildRunner()

        runner.run()
        runner.run()
        runner.run()

        assertEquals(1, versionStore.versionHistory(schemaName).size, "an unchanged schema stores once")
        assertEquals(3, savedReports().size, "while every check leaves its own report")
    }

    @Test
    fun `the baseline is read before this run's own stamp is written`() {
        // Reading afterwards would hand back the stamp this very run just wrote, so the
        // declared-vs-previous comparison would compare a declaration against itself.
        val recording = CallRecordingVersionStore()
        val runner = buildRunner(versionStore = recording)

        runner.run()

        assertEquals(listOf("sweptVersion", "saveVersion"), recording.calls)
    }

    // ---- Reporting ----

    @Test
    fun `zero drift still leaves a retrievable report`() {
        val runner = buildRunner()

        val result = runner.run()

        assertFalse(result.hasDrift)
        assertTrue(result.driftedEntityTypes.isEmpty())
        assertTrue(result.driftedRelationshipTypes.isEmpty())

        val reports = savedReports()
        assertEquals(1, reports.size, "even a clean check must leave a record behind")
        assertEquals(result.report, reports.single())
        assertFalse(reports.single().hasDrift)
    }

    @Test
    fun `the report is stamped with the snapshot's instant`() {
        // The report is a statement about the observation, so it carries the observation's instant
        // and never the instant of the write.
        observedEntityTypes = setOf("Person", "Company", "GhostType")
        val runner = buildRunner()

        val result = runner.run()

        assertEquals(capturedAt, result.report.capturedAt)
    }

    @Test
    fun `the result reads its drift straight off the report it saved`() {
        observedEntityTypes = setOf("Person", "Company", "GhostType")
        observedRelationshipTypeNames = setOf("WORKS_AT", "UNDECLARED_LINK")
        val runner = buildRunner()

        val result = runner.run()

        val saved = savedReports().single()
        assertEquals(saved.driftedEntityTypes, result.driftedEntityTypes)
        assertEquals(saved.driftedRelationshipTypes, result.driftedRelationshipTypes)
        assertEquals(saved.schemaName, result.schemaName)
        assertEquals(saved.contextId, result.contextId)
    }

    @Test
    fun `the no-argument run covers the whole graph`() {
        observedEntityTypes = setOf("Person", "Company", "GhostType")
        val runner = buildRunner()

        val result = runner.run()

        assertNull(result.contextId)
        assertEquals(setOf("GhostType"), result.driftedEntityTypes)
    }

    // ---- A check changes nothing ----

    @Test
    fun `many checks in a row move the swept baseline nowhere`() {
        // The baseline moves when a host says a sweep finished. A check reads it, diffs against it,
        // and leaves it alone however many times it runs -- otherwise the very next check would
        // compare the declaration against itself and the lossy change would vanish unswept.
        val baseline = previousVersionWithPersonAge()
        versionStore.markSwept(baseline)
        val recording = CallRecordingVersionStore(delegate = versionStore)
        val runner = buildRunner(versionStore = recording)

        repeat(5) { runner.run() }

        assertEquals(baseline, versionStore.sweptVersion(schemaName), "five checks retired nothing")
        assertEquals(
            List(5) { listOf("sweptVersion", "saveVersion") }.flatten(),
            recording.calls,
            "a check reads the baseline and stamps its declaration; markSwept is absent: ${recording.calls}",
        )
        repeat(5) { run ->
            assertNotNull(
                savedReports()[run].declaredDiff,
                "every one of the five checks still saw the same unreconciled change",
            )
        }
    }

    @Test
    fun `the runner is built with no proposition store at all`() {
        // The structural half of "a check changes nothing": there is no collaborator through which
        // a check could reach a proposition, so no amount of drift can move one. Matched on package
        // name, because this module no longer depends on dice core and so has no proposition type to
        // name here -- which is a stronger statement than the one this assertion used to make: not
        // one proposition-shaped collaborator of any kind can appear in the constructor.
        val parameterTypes = DefaultDriftCheckRunner::class.java.constructors
            .flatMap { it.parameterTypes.asIterable() }

        assertTrue(
            parameterTypes.none { it.name.startsWith("com.embabel.dice.proposition.") },
            "a drift check must have no way to reach propositions, but found: $parameterTypes",
        )
    }

    // ---- Declared-vs-previous drift reaches the report ----

    @Test
    fun `a lossy declared change with no observed drift still reaches the report`() {
        // Person was stamped with an `age` property on an earlier, completed sweep. The CURRENT
        // declaration has dropped it, but the observed graph matches the current declaration exactly
        // (default observedEntityTypes), so diffAgainstObserved alone -- declared against what the
        // graph holds right now -- finds nothing: there is no undeclared-but-observed type or label
        // anywhere. Only a declared-vs-previous-declared comparison sees the property vanished.
        versionStore.markSwept(previousVersionWithPersonAge())
        val runner = buildRunner()

        val result = runner.run()

        assertTrue(result.driftedEntityTypes.isEmpty(), "sanity: no declared-vs-observed drift at all")
        assertFalse(result.hasDrift, "the graph-truth half of the report is clean")
        assertTrue(result.hasAnyChange, "and the report still says something happened")
        assertNotNull(result.declaredDiff, "the declared comparison must reach the report")
        val declaredDiff = result.declaredDiff!!
        assertEquals(
            listOf("age"),
            declaredDiff.modifiedEntityTypes.single { it.typeName == "Person" }.removedPropertyNames.toList(),
            "the report must name the property the declaration dropped: $declaredDiff",
        )
    }

    @Test
    fun `the report a store hands back carries the declared comparison too`() {
        // The fix for the dry-check blind spot only holds if declaredDiff survives the round trip
        // through DriftReportStore; a result-only field would leave an operator reading a stored
        // report exactly as blind as before.
        versionStore.markSwept(previousVersionWithPersonAge())
        val runner = buildRunner()

        val result = runner.run()

        assertEquals(result.declaredDiff, savedReports().single().declaredDiff)
        assertEquals(result.report, savedReports().single())
    }

    @Test
    fun `the reported comparison is what a sweep would evaluate`() {
        // The whole point of carrying declaredDiff: quarantineDiff, built from the report alone,
        // must be the same object a deliberate sweep evaluates propositions against.
        versionStore.markSwept(previousVersionWithPersonAge())
        observedEntityTypes = setOf("Person", "Company", "GhostType")
        val runner = buildRunner()

        val result = runner.run()

        assertEquals(
            result.quarantineDiff,
            savedReports().single().quarantineDiff(result.declaredVersion),
            "a stored report resolves to the same comparison the live result did",
        )
        assertEquals(
            setOf("GhostType"),
            result.quarantineDiff.removedEntityTypes,
            "observed drift arrives as a removal a policy can judge",
        )
        assertEquals(
            listOf("age"),
            result.quarantineDiff.modifiedEntityTypes.single().removedPropertyNames.toList(),
            "and the declared property removal rides in the same diff",
        )
    }

    @Test
    fun `a first check has no baseline, so it reports no declared comparison`() {
        // Nothing has ever been swept for this schema, so sweptVersion is null and the
        // declared-vs-previous comparison doesn't run. It must not throw, and it must say so.
        val runner = buildRunner()

        val first = runner.run()

        assertNull(first.declaredDiff, "there is nothing to compare a schema's very first check against")
        assertNull(versionStore.sweptVersion(schemaName), "and the check established no baseline either")

        // A host sweeps and marks. From then on the comparison runs and finds nothing new.
        versionStore.markSwept(declaredVersion())
        val second = runner.run()

        assertNotNull(second.declaredDiff, "a marked baseline is a baseline the next check reads")
        val declaredDiff = second.declaredDiff!!
        assertTrue(declaredDiff.isEmpty, "the declaration hasn't moved since the sweep: $declaredDiff")
    }

    @Test
    fun `a version store that tracks no baseline reports no declared comparison`() {
        // A store implements SweptBaselineStore when it can. One that can't gets the graph-truth
        // half and an honest null, which beats a baseline guessed from write order: that guess moves
        // on every ordinary stamp, so it would quietly retire changes nothing had swept for.
        val untracked = BaselineFreeVersionStore()
        untracked.saveVersion(previousVersionWithPersonAge())
        val runner = buildRunner(versionStore = untracked)

        val result = runner.run()

        assertNull(result.declaredDiff)
        assertFalse(result.hasAnyChange)
    }

    @Test
    fun `a declaration reverted to an earlier stamp is diffed against what was actually swept`() {
        // A (with `age`) -> B (without, swept) -> A again (a plain re-save) -> B declared again.
        // latestVersion answers B the whole way through, because re-saving A keeps its original
        // write-order position (MetamodelVersionStore.saveVersion's contract), so a runner trusting
        // it would diff B against B at the last step and miss that `age` just vanished again.
        val a = previousVersionWithPersonAge()
        val b = MetamodelVersion(
            schemaName = schemaName,
            entityTypeNames = listOf("Person", "Company"),
            entityTypeLabels = mapOf("Person" to setOf("Person"), "Company" to setOf("Company")),
            entityTypeProperties = mapOf("Person" to emptySet(), "Company" to emptySet()),
            relationshipNames = declaredRelationshipTypeNames.map { "Person-[$it]->Company" },
        )
        versionStore.markSwept(a)
        versionStore.markSwept(b)
        versionStore.saveVersion(a) // a re-save only, and never a sweep
        assertEquals(b, versionStore.latestVersion(schemaName), "sanity: latestVersion still answers B")
        versionStore.markSwept(a)

        // The schema drops `age` again (declares B's shape again). Diffing against sweptVersion (A)
        // catches the reversion; diffing against latestVersion (B) would compare B with B.
        declaredEntityTypeProperties = mapOf("Person" to emptySet(), "Company" to emptySet())
        val runner = buildRunner()

        val result = runner.run()

        assertNotNull(result.declaredDiff, "sweptVersion answers A, so the comparison runs")
        val declaredDiff = result.declaredDiff!!
        assertEquals(
            listOf("age"),
            declaredDiff.modifiedEntityTypes.single { it.typeName == "Person" }.removedPropertyNames.toList(),
            "the reverted removal of `age` must be caught: $declaredDiff",
        )
    }

    @Test
    fun `a purely additive declared change leaves an empty comparison`() {
        val previousVersion = MetamodelVersion(
            schemaName = schemaName,
            entityTypeNames = listOf("Person"),
            entityTypeLabels = mapOf("Person" to setOf("Person")),
            entityTypeProperties = mapOf("Person" to emptySet()),
            relationshipNames = emptyList(),
        )
        versionStore.markSwept(previousVersion)
        // declaredEntityTypes defaults to Person, Company -- an added type versus previousVersion.
        val runner = buildRunner()

        val result = runner.run()

        assertNotNull(result.declaredDiff, "a baseline exists, so the comparison ran")
        val declaredDiff = result.declaredDiff!!
        assertTrue(declaredDiff.removedEntityTypes.isEmpty(), "adding a type removes nothing")
        assertTrue(
            result.quarantineDiff.changes.none { it is MetamodelChange.EntityTypeRemoved },
            "so the merged comparison carries no removal either: ${result.quarantineDiff.changes}",
        )
    }

    @Test
    fun `the merged comparison keeps every removed type in one sorted block, ahead of other changes`() {
        // Two removals from EACH source, interleaved alphabetically, so a merge that only
        // concatenates per-source contributions -- synthetic removals, then declared ones, each in
        // whatever order they arrived, without sorting the union as a whole -- would produce A, N,
        // M, B or some other source-grouped order that happens to look plausible while being wrong.
        // "M" and "B" are declared-vs-previous (dropped from the declaration outright); "A" and "N"
        // are observed-vs-declared (never declared by either version). Z survives with a lost
        // property. Asserting the complete, alphabetically interleaved list -- A, B, M, N, then Z's
        // modification -- is what makes the two orderings distinguishable.
        declaredEntityTypes = listOf("Person", "Company", "Z")
        val previousVersion = MetamodelVersion(
            schemaName = schemaName,
            entityTypeNames = declaredEntityTypes + listOf("M", "B"),
            entityTypeLabels = (declaredEntityTypes + listOf("M", "B")).associateWith { setOf(it) },
            entityTypeProperties = mapOf(
                "Person" to emptySet(),
                "Company" to emptySet(),
                "Z" to setOf(PropertySignature("p", PropertySignature.Kind.VALUE, "string", Cardinality.ONE)),
                "M" to emptySet(),
                "B" to emptySet(),
            ),
            relationshipNames = declaredRelationshipTypeNames.map { "Person-[$it]->Company" },
        )
        versionStore.markSwept(previousVersion)
        // Current declaration: Z loses "p"; "M" and "B" are dropped outright.
        declaredEntityTypeProperties = declaredEntityTypes.associateWith { emptySet() }
        observedEntityTypes = setOf("Person", "Company", "Z", "A", "N") // "A", "N" are undeclared drift
        val runner = buildRunner()

        val changes = runner.run().quarantineDiff.changes

        val zModification = changes.single { it !is MetamodelChange.EntityTypeRemoved }
        assertEquals(
            listOf(
                MetamodelChange.EntityTypeRemoved("A"),
                MetamodelChange.EntityTypeRemoved("B"),
                MetamodelChange.EntityTypeRemoved("M"),
                MetamodelChange.EntityTypeRemoved("N"),
                zModification,
            ),
            changes,
            "the removed-type block must merge both sources into one alphabetically sorted run, " +
                "ahead of Z's modification, and never a per-source grouping that looks sorted: $changes",
        )
    }

    // ---- Label closure ----

    @Test
    fun `an inherited label observed in the graph counts as declared`() {
        // Declaring Person with parent Agent puts both labels on every Person node, so the graph
        // reports Agent too. Comparing observed labels against type names alone would report Agent
        // as undeclared and a sweep would then flag sound propositions on a schema nobody touched.
        declaredEntityTypes = listOf("Person")
        declaredEntityTypeLabels = mapOf("Person" to setOf("Person", "Agent"))
        observedEntityTypes = setOf("Person", "Agent", "GhostType")
        val runner = buildRunner()

        val result = runner.run()

        assertEquals(setOf("GhostType"), result.driftedEntityTypes, "the inherited label is declared")
    }

    @Test
    fun `a declared type with no data is reported as unobserved, never as drift`() {
        observedEntityTypes = setOf("Person")
        val runner = buildRunner()

        val result = runner.run()

        assertTrue(result.driftedEntityTypes.isEmpty(), "declared-but-empty is an ordinary state")
    }

    // ---- Names that look like delimiters ----

    @Test
    fun `declared relationship names with pipes, tabs and newlines flow through untouched`() {
        val delimiterLaden = setOf("REL|WITH|PIPE", "REL\tWITH\tTAB", "REL\nWITH\nNEWLINE")
        declaredRelationshipTypeNames = delimiterLaden
        observedRelationshipTypeNames = delimiterLaden + "UNDECLARED|ALSO\tDELIMITED"
        val runner = buildRunner()

        val result = runner.run()

        // The declared names must reach the differ exactly as supplied, with no splitting, trimming
        // or delimiter parsing, so only the undeclared name shows up as drift.
        assertEquals(setOf("UNDECLARED|ALSO\tDELIMITED"), result.driftedRelationshipTypes)
        assertEquals(setOf("UNDECLARED|ALSO\tDELIMITED"), savedReports().single().driftedRelationshipTypes)
    }

    // ---- Scope ----

    @Test
    fun `a scoped check stamps the report with its context`() {
        val runner = buildRunner()

        val result = runner.run(contextId)

        assertEquals(contextId, result.contextId)
        assertEquals(contextId, result.report.contextId)
        assertEquals(
            listOf(result.report),
            reportStore.driftReportsInContext(schemaName, contextId, limit = 10),
            "and it must come back from the context-scoped read",
        )
        assertTrue(
            reportStore.globalDriftReports(schemaName, limit = 10).isEmpty(),
            "a scoped check is a different thing from a global one",
        )
    }

    @Test
    fun `a result refuses a stamp its report was never judged against`() {
        // The two halves have to agree, or quarantineDiff would merge one check's observed drift
        // into another declaration's changes.
        val report = DriftReport(
            schemaName = schemaName,
            versionHash = "some-other-hash",
            driftedEntityTypes = emptySet(),
            driftedRelationshipTypes = emptySet(),
            capturedAt = capturedAt,
        )

        val failure = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            DriftCheckResult(report = report, declaredVersion = declaredVersion())
        }
        assertTrue(failure.message!!.contains("some-other-hash"), failure.message)
    }

    /**
     * Records the version-store calls a run makes, in order, so a test can assert what a check does
     * and doesn't ask its store for. Reads and writes go through to [delegate].
     */
    private class CallRecordingVersionStore(
        private val delegate: InMemoryMetamodelVersionStore = InMemoryMetamodelVersionStore(),
    ) : SweptBaselineStore {

        val calls = mutableListOf<String>()

        override fun saveVersion(version: MetamodelVersion) {
            calls += "saveVersion"
            delegate.saveVersion(version)
        }

        override fun latestVersion(schemaName: String): MetamodelVersion? {
            calls += "latestVersion"
            return delegate.latestVersion(schemaName)
        }

        override fun versionHistory(schemaName: String): List<MetamodelVersion> =
            delegate.versionHistory(schemaName)

        override fun sweptVersion(schemaName: String): MetamodelVersion? {
            calls += "sweptVersion"
            return delegate.sweptVersion(schemaName)
        }

        override fun markSwept(version: MetamodelVersion) {
            calls += "markSwept"
            delegate.markSwept(version)
        }
    }

    /**
     * A version store that keeps stamps and tracks no reconciled baseline, which is what a backend
     * looks like before it implements [SweptBaselineStore].
     */
    private class BaselineFreeVersionStore : MetamodelVersionStore {
        private val versions = mutableListOf<MetamodelVersion>()

        override fun saveVersion(version: MetamodelVersion) {
            versions.removeIf { it.schemaName == version.schemaName && it.contentHash == version.contentHash }
            versions.add(0, version)
        }

        override fun latestVersion(schemaName: String): MetamodelVersion? =
            versions.firstOrNull { it.schemaName == schemaName }

        override fun versionHistory(schemaName: String): List<MetamodelVersion> =
            versions.filter { it.schemaName == schemaName }
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

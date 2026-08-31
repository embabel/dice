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
import com.embabel.dice.common.DiceEvent
import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.common.PropositionStatusChanged
import com.embabel.dice.common.SafeDiceEventListener
import com.embabel.dice.metamodel.support.DefaultDriftCheckRunner
import com.embabel.dice.metamodel.support.MentionTypeDriftQuarantinePolicy
import com.embabel.dice.metamodel.support.StructuralMetamodelDiffer
import com.embabel.dice.projection.lineage.InMemoryProjectionRecordStore
import com.embabel.dice.projection.lineage.ProjectionLifecycle
import com.embabel.dice.projection.lineage.ProjectionLineageStaleCascade
import com.embabel.dice.projection.lineage.ProjectionRecord
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
import org.junit.jupiter.api.Assertions.assertThrows
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
    private var declaredEntityTypeProperties: Map<String, Set<PropertySignature>>? = null
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
        entityTypeProperties = declaredEntityTypeProperties ?: declaredEntityTypes.associateWith { emptySet() },
        relationshipNames = declaredRelationshipTypeNames.map { "Person-[$it]->Company" },
    )

    // Typed as the base persistence port rather than PropositionRepository, so whatever a test
    // passes in, the runner only gets store-and-retrieve out of it.
    private fun buildRunner(
        store: PropositionStore = propositionStore,
        listener: DiceEventListener = DiceEventListener.DEV_NULL,
        quarantinePolicy: DriftQuarantinePolicy = MentionTypeDriftQuarantinePolicy(),
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
            quarantinePolicy = quarantinePolicy,
            propositionStore = store,
            listener = listener,
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
    fun `quarantine's status change reaches projection lineage through the listener`() {
        // ProjectionLineageStaleCascade is how a proposition going STALE is supposed to mark its
        // projection records stale in turn (see that class); it reacts to PropositionStatusChanged.
        // Wiring the runner's listener straight to it is what routes a quarantine transition there.
        observedEntityTypes = setOf("Person", "Company", "GhostType")
        val affected = propositionStore.save(proposition("a ghost was mentioned", "GhostType"))
        val recordStore = InMemoryProjectionRecordStore()
        recordStore.record(
            ProjectionRecord(
                propositionId = affected.id,
                target = "test-target",
                lifecycle = ProjectionLifecycle.PROJECTED,
                runId = "run-1",
            ),
        )
        val cascade = ProjectionLineageStaleCascade(recordStore)
        val runner = buildRunner(listener = SafeDiceEventListener(cascade))

        val result = runner.run(dryRun = false)

        assertEquals(1, result.quarantinedCount, "sanity: the quarantine itself did happen")
        assertEquals(PropositionStatus.STALE, propositionStore.findById(affected.id)!!.status)
        assertEquals(
            ProjectionLifecycle.STALE,
            recordStore.findByProposition(affected.id).single().lifecycle,
            "the cascade heard about the transition and marked its record stale in turn",
        )
    }

    @Test
    fun `a conforming proposition emits no status-changed event`() {
        // The listener should hear about quarantines, not about every proposition the sweep looked
        // at; a conforming proposition's status never moved and nothing should say it did.
        observedEntityTypes = setOf("Person", "Company", "GhostType")
        propositionStore.save(proposition("Alice works at Acme", "Person", "Company"))
        val recording = RecordingDiceEventListener()
        val runner = buildRunner(listener = recording)

        runner.run(dryRun = false)

        assertTrue(recording.events.isEmpty())
    }

    @Test
    fun `a dry run never emits a status-changed event`() {
        observedEntityTypes = setOf("Person", "Company", "GhostType")
        propositionStore.save(proposition("a ghost was mentioned", "GhostType"))
        val recording = RecordingDiceEventListener()
        val runner = buildRunner(listener = recording)

        runner.run(dryRun = true)

        assertTrue(recording.events.isEmpty(), "a dry run must not announce a transition it never made")
    }

    @Test
    fun `the emitted event carries the quarantine reason and the previous status`() {
        observedEntityTypes = setOf("Person", "Company", "GhostType")
        propositionStore.save(proposition("a ghost was mentioned", "GhostType"))
        val recording = RecordingDiceEventListener()
        val runner = buildRunner(listener = recording)

        runner.run(dryRun = false)

        val event = recording.events.filterIsInstance<PropositionStatusChanged>().single()
        assertEquals(PropositionStatus.ACTIVE, event.previousStatus)
        assertEquals(PropositionStatus.STALE, event.newStatus)
        assertNotNull(event.reason)
        assertTrue(event.reason!!.contains("GhostType"))
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

    // ---- Declared-vs-previous drift ----

    @Test
    fun `a lossy declared change with no observed drift still reaches quarantine`() {
        // Person was stamped with an `age` property on an earlier, completed live check. The
        // CURRENT declaration has dropped it, but the observed graph matches the current
        // declaration exactly (default observedEntityTypes), so diffAgainstObserved alone —
        // declared vs. what the graph holds right now — finds nothing: there is no
        // undeclared-but-observed type or label anywhere. Only a declared-vs-previous-declared
        // comparison sees the property actually vanished.
        val previousVersion = previousVersionWithPersonAge()
        versionStore.markSwept(previousVersion)
        val mentioning = propositionStore.save(proposition("Alice is 40", "Person"))
        val runner = buildRunner()

        val result = runner.run(dryRun = false)

        assertTrue(result.driftedEntityTypes.isEmpty(), "sanity: no declared-vs-observed drift at all")
        assertEquals(1, result.quarantinedCount, "the declared property removal must still reach quarantine")
        val quarantined = propositionStore.findById(mentioning.id)!!
        assertEquals(PropositionStatus.STALE, quarantined.status)
        assertTrue(
            (quarantined.metadata[DiceMetadataKeys.QUARANTINE_REASON] as String).contains("age"),
            "the reason should name the property the declaration dropped",
        )
    }

    @Test
    fun `the baseline is read before this run's own history write, even on a store with no independent tracking`() {
        // InMemoryMetamodelVersionStore (every other test in this class) tracks sweptVersion
        // independently of latestVersion, so it can't expose a read-before-save ordering bug: even a
        // buggy read-after-save would still see the old baseline through the independent pointer.
        // DefaultForwardingVersionStore has no such safety net -- sweptVersion falls through to the
        // interface default, latestVersion, which moves the instant saveVersion runs. If the runner
        // ever read the baseline after stamping the current declaration into history, this store
        // would hand back the declaration that write just made current, the declared-vs-previous
        // diff would compare that against itself, and the lossy change below would go uncaught.
        val forwardingStore = DefaultForwardingVersionStore()
        val previousVersion = previousVersionWithPersonAge()
        forwardingStore.saveVersion(previousVersion)
        val mentioning = propositionStore.save(proposition("Alice is 40", "Person"))
        val runner = buildRunner(versionStore = forwardingStore)

        val result = runner.run(dryRun = false)

        assertEquals(
            1,
            result.quarantinedCount,
            "the declared property removal must still reach quarantine, proving the baseline was " +
                "read before this run's own stamp overwrote what latestVersion answers",
        )
        assertEquals(PropositionStatus.STALE, propositionStore.findById(mentioning.id)!!.status)
    }

    @Test
    fun `establishing the baseline on the first live check means a later identical declaration finds nothing new`() {
        // No prior sweep exists for this schema, so sweptVersion is null and the declared-vs-
        // previous comparison doesn't run at all on the first check — it must not throw, and it
        // must not just happen to find nothing because it never looked: the second run below
        // proves the first run actually established a baseline, not merely that it stayed silent.
        propositionStore.save(proposition("Alice is a person", "Person"))
        val runner = buildRunner()

        val first = runner.run(dryRun = false)

        assertEquals(0, first.quarantinedCount, "nothing to compare the very first check against")
        assertEquals(
            declaredVersion(),
            versionStore.sweptVersion(schemaName),
            "completing the first live check must establish the baseline for the next one",
        )

        val second = runner.run(dryRun = false)

        assertEquals(
            0,
            second.quarantinedCount,
            "reading the baseline after it was overwritten, or never establishing it, could each " +
                "produce a wrong non-zero result here just as easily as the correct zero",
        )
    }

    @Test
    fun `a purely additive declared change does not quarantine`() {
        val previousVersion = MetamodelVersion(
            schemaName = schemaName,
            entityTypeNames = listOf("Person"),
            entityTypeLabels = mapOf("Person" to setOf("Person")),
            entityTypeProperties = mapOf("Person" to emptySet()),
            relationshipNames = emptyList(),
        )
        versionStore.markSwept(previousVersion)
        // declaredEntityTypes defaults to Person, Company — an added type versus previousVersion.
        propositionStore.save(proposition("Alice is a person", "Person"))
        val runner = buildRunner()

        val result = runner.run(dryRun = false)

        assertEquals(0, result.quarantinedCount, "a purely additive declared change is not lossy")
    }

    @Test
    fun `a dry run does not consume a lossy declared change -- the next live run still catches it`() {
        val previousVersion = previousVersionWithPersonAge()
        versionStore.markSwept(previousVersion)
        val mentioning = propositionStore.save(proposition("Alice is 40", "Person"))
        val runner = buildRunner()

        val dry = runner.run(dryRun = true)

        assertEquals(0, dry.quarantinedCount, "sanity: a dry run never quarantines")
        assertEquals(
            previousVersion,
            versionStore.sweptVersion(schemaName),
            "a dry run only read the baseline; it must not retire it",
        )
        assertEquals(PropositionStatus.ACTIVE, propositionStore.findById(mentioning.id)!!.status)

        val live = runner.run(dryRun = false)

        assertEquals(1, live.quarantinedCount, "the lossy declared change must still reach quarantine")
        assertEquals(PropositionStatus.STALE, propositionStore.findById(mentioning.id)!!.status)
    }

    @Test
    fun `a context-scoped live run does not retire the baseline, so a later run still reaches other contexts`() {
        val previousVersion = previousVersionWithPersonAge()
        versionStore.markSwept(previousVersion)
        val inA = propositionStore.save(proposition("Alice is 40", "Person", inContext = contextId))
        val inB = propositionStore.save(proposition("Bob is 50", "Person", inContext = otherContextId))
        val runner = buildRunner()

        val scoped = runner.run(dryRun = false, contextId = contextId)

        assertEquals(1, scoped.quarantinedCount, "context A's candidate is reachable straight away")
        assertEquals(PropositionStatus.STALE, propositionStore.findById(inA.id)!!.status)
        assertEquals(
            PropositionStatus.ACTIVE,
            propositionStore.findById(inB.id)!!.status,
            "sanity: the scoped run never touched context B",
        )
        assertEquals(
            previousVersion,
            versionStore.sweptVersion(schemaName),
            "a run scoped to one context must not retire the schema-wide baseline",
        )

        val later = runner.run(dryRun = false, contextId = otherContextId)

        assertEquals(1, later.quarantinedCount, "the same declared-vs-previous drift is still there for B")
        assertEquals(PropositionStatus.STALE, propositionStore.findById(inB.id)!!.status)
    }

    @Test
    fun `a crash mid-sweep leaves the baseline unmoved, so the next check retries the same comparison`() {
        val previousVersion = previousVersionWithPersonAge()
        versionStore.markSwept(previousVersion)
        val mentioning = propositionStore.save(proposition("Alice is 40", "Person"))
        val crashingStore = object : PropositionStore by propositionStore {
            override fun save(proposition: Proposition): Proposition =
                throw IllegalStateException("simulated crash mid-sweep")
        }
        val crashingRunner = buildRunner(store = crashingStore)

        assertThrows(IllegalStateException::class.java) { crashingRunner.run(dryRun = false) }

        assertEquals(
            previousVersion,
            versionStore.sweptVersion(schemaName),
            "an interrupted sweep must not look like a completed reconciliation",
        )
        assertEquals(
            PropositionStatus.ACTIVE,
            propositionStore.findById(mentioning.id)!!.status,
            "sanity: the crashing save never actually landed",
        )

        // The retry: a fresh runner over the same stores, this time able to actually save. Nothing
        // about the earlier crash should have consumed or altered the comparison it interrupted.
        val retryRunner = buildRunner(store = propositionStore)

        val retried = retryRunner.run(dryRun = false)

        assertEquals(1, retried.quarantinedCount, "the retry must still catch the same lossy change")
        assertEquals(PropositionStatus.STALE, propositionStore.findById(mentioning.id)!!.status)
        assertEquals(
            declaredVersion(),
            versionStore.sweptVersion(schemaName),
            "the retry's own completed sweep is what finally advances the baseline",
        )
    }

    @Test
    fun `a declaration reverted to an earlier stamp is still diffed against what was actually swept`() {
        // A (with `age`) -> B (without, swept) -> A again (age restored, saved but never swept) ->
        // B declared again. latestVersion answers B the whole way through, because re-saving A
        // keeps its original write-order position (MetamodelVersionStore's own saveVersion
        // contract), so a runner trusting it would diff B against B at the last step and miss that
        // `age` just vanished again. sweptVersion must not make that mistake.
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
        versionStore.saveVersion(a) // re-save only -- not a sweep
        assertEquals(b, versionStore.latestVersion(schemaName), "sanity: latestVersion still answers B")
        assertEquals(b, versionStore.sweptVersion(schemaName), "sanity: B is still the reconciled baseline")

        versionStore.markSwept(a)
        assertEquals(
            a,
            versionStore.sweptVersion(schemaName),
            "sweptVersion tracks the pointer, not write order -- unlike latestVersion above",
        )

        // The schema drops `age` again (declares B's shape again). Diffing against sweptVersion
        // (A) catches the reversion; diffing against latestVersion (B, unchanged since the last
        // markSwept(b) two lines up) would compare B against B and find nothing.
        declaredEntityTypeProperties = mapOf("Person" to emptySet(), "Company" to emptySet())
        val mentioning = propositionStore.save(proposition("Alice is 40", "Person"))
        val runner = buildRunner()

        val result = runner.run(dryRun = false)

        assertEquals(1, result.quarantinedCount, "the reverted removal of `age` must be caught")
        assertEquals(PropositionStatus.STALE, propositionStore.findById(mentioning.id)!!.status)
    }

    @Test
    fun `a proposition already STALE from decay emits no status-changed event when quarantined`() {
        // The idempotency check only skips a proposition that's already quarantined (STALE with a
        // reason); one that's STALE from ordinary decay, with no reason yet, is still a fresh
        // candidate and does get quarantined -- but its status doesn't move, so no event should say
        // it did.
        val previousVersion = previousVersionWithPersonAge()
        versionStore.markSwept(previousVersion)
        val decayed = propositionStore.save(
            proposition("Alice is 40", "Person").withStatus(PropositionStatus.STALE),
        )
        val recording = RecordingDiceEventListener()
        val runner = buildRunner(listener = recording)

        val result = runner.run(dryRun = false)

        assertEquals(1, result.quarantinedCount, "sanity: it was quarantined")
        val quarantined = propositionStore.findById(decayed.id)!!
        assertEquals(PropositionStatus.STALE, quarantined.status)
        assertNotNull(
            quarantined.metadata[DiceMetadataKeys.QUARANTINE_REASON],
            "sanity: the reason was written even though the status didn't move",
        )
        assertTrue(
            recording.events.isEmpty(),
            "previousStatus and newStatus are both STALE -- nothing actually transitioned",
        )
    }

    @Test
    fun `the merged diff keeps every removed type in one sorted block, ahead of other declared changes`() {
        // Two removals from EACH source, interleaved alphabetically, so a merge that only
        // concatenates per-source contributions -- synthetic removals, then declared ones, each in
        // whatever order they arrived, without sorting the union as a whole -- would produce A, N,
        // M, B or some other source-grouped order that happens to look plausible but isn't the one
        // sorted run MetamodelDiff promises. "M" and "B" are declared-vs-previous (dropped from the
        // declaration outright, filed as declared EntityTypeRemoved); "A" and "N" are
        // observed-vs-declared (synthetic EntityTypeRemoved, never declared by either version). Z
        // survives with a lost property (a declared EntityTypeModified, filed under "Z"). The only
        // block ordering that survives both a true union-sort AND a naive per-source concatenation
        // for two of these four names is indistinguishable from a bug; asserting the complete,
        // alphabetically interleaved list -- A, B, M, N, then Z's modification -- is what makes the
        // two indistinguishable orderings actually distinguishable.
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
        // Current declaration: Z loses "p"; "M" and "B" are dropped outright (declared-vs-previous
        // removals).
        declaredEntityTypeProperties = declaredEntityTypes.associateWith { emptySet() }
        observedEntityTypes = setOf("Person", "Company", "Z", "A", "N") // "A", "N" are undeclared drift
        val recording = RecordingDriftQuarantinePolicy()
        val runner = buildRunner(quarantinePolicy = recording)

        runner.run(dryRun = false)

        val changes = recording.lastDiff!!.changes
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
                "ahead of Z's modification, not a per-source grouping that happens to look sorted: $changes",
        )
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
     * Wraps a real [DriftQuarantinePolicy] and remembers the last [MetamodelDiff] it was asked to
     * evaluate, so a test can inspect the diff the runner actually built and merged directly,
     * instead of inferring its shape from quarantine outcomes alone.
     */
    private class RecordingDriftQuarantinePolicy(
        private val delegate: DriftQuarantinePolicy = MentionTypeDriftQuarantinePolicy(),
    ) : DriftQuarantinePolicy {
        var lastDiff: MetamodelDiff? = null
            private set

        override fun evaluate(diff: MetamodelDiff, propositions: Iterable<Proposition>): QuarantineResult {
            lastDiff = diff
            return delegate.evaluate(diff, propositions)
        }
    }

    /**
     * Implements only the three original [MetamodelVersionStore] members, so `sweptVersion` and
     * `markSwept` fall through to the interface defaults -- `sweptVersion` answering `latestVersion`,
     * which moves on every [saveVersion]. Unlike [InMemoryMetamodelVersionStore] (which every other
     * test in this class uses, and which tracks the reconciled baseline independently), a store built
     * this way is exactly what exposes a read-before-save ordering bug: reading the baseline after the
     * current run's own history write would read back the stamp that write just made current.
     */
    private class DefaultForwardingVersionStore : MetamodelVersionStore {
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

    /** Captures every event handed to it, in order, so a test can assert on what the runner emits. */
    private class RecordingDiceEventListener : DiceEventListener {
        val events = mutableListOf<DiceEvent>()
        override fun onEvent(event: DiceEvent) {
            events += event
        }
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

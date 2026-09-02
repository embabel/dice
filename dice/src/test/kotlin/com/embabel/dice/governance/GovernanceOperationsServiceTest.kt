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
package com.embabel.dice.governance

import com.embabel.agent.core.Cardinality
import com.embabel.agent.core.ContextId
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.metamodel.DeclaredSchema
import com.embabel.dice.metamodel.DeclaredSchemaSource
import com.embabel.dice.metamodel.DriftReport
import com.embabel.dice.metamodel.DriftReportStore
import com.embabel.dice.metamodel.InMemoryMetamodelVersionStore
import com.embabel.dice.metamodel.MetamodelVersion
import com.embabel.dice.metamodel.ObservedSchema
import com.embabel.dice.metamodel.ObservedSchemaSource
import com.embabel.dice.metamodel.PropertySignature
import com.embabel.dice.metamodel.support.DefaultDriftCheckRunner
import com.embabel.dice.metamodel.support.StructuralMetamodelDiffer
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.spi.DriftSweepCapable
import com.embabel.dice.spi.MentionTypeDriftQuarantinePolicy
import com.embabel.dice.spi.PropositionStoreDriftSweep
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The operator surface driven against real in-memory collaborators: the shipped drift-check runner,
 * the shipped sweep and quarantine policy, an in-memory version store and proposition store, and a
 * drift log that keeps its reports in a list.
 *
 * Real objects throughout, because most of what these tests check is behaviour — that a check wrote
 * a report and moved nothing, that a release restored the exact status a proposition came from, that
 * a read scoped to one context never returns another's. Mocks would only let the assertions restate
 * the service's own code.
 */
class GovernanceOperationsServiceTest {

    private lateinit var declaredSchemaSource: MutableDeclaredSchemaSource
    private lateinit var versionStore: InMemoryMetamodelVersionStore
    private lateinit var driftReportStore: ListDriftReportStore
    private lateinit var observed: MutableObservedSchemaSource
    private lateinit var propositions: InMemoryPropositionRepository
    private lateinit var sweep: DriftSweepCapable
    private lateinit var runner: DefaultDriftCheckRunner
    private lateinit var service: GovernanceOperationsService

    @BeforeEach
    fun setUp() {
        declaredSchemaSource = MutableDeclaredSchemaSource(declaration("Person"))
        versionStore = InMemoryMetamodelVersionStore()
        driftReportStore = ListDriftReportStore()
        observed = MutableObservedSchemaSource(setOf("Person", "Ghost"))
        propositions = InMemoryPropositionRepository()
        sweep = PropositionStoreDriftSweep(propositions)
        runner = DefaultDriftCheckRunner(
            declaredSchemaSource = declaredSchemaSource,
            versionStore = versionStore,
            observedSchemaSource = observed,
            differ = StructuralMetamodelDiffer(),
            metamodelDiffer = StructuralMetamodelDiffer(),
            driftReportStore = driftReportStore,
        )
        service = GovernanceOperationsService(
            declaredSchemaSource = declaredSchemaSource,
            versionStore = versionStore,
            driftReportStore = driftReportStore,
            driftCheckRunner = runner,
            driftSweep = sweep,
            propositions = propositions,
        )
    }

    // ---- Reads ----

    @Test
    fun `the current declared version reports what the application declares`() {
        val declared = service.currentDeclaredVersion()

        assertThat(declared.schemaName).isEqualTo(SCHEMA_NAME)
        assertThat(declared.entityTypeNames).containsExactly("Person")
        assertThat(declared.contentHash).isEqualTo(declaration("Person").version.contentHash)
        // Nothing has stamped it and no sweep has completed, so both answers are honest negatives.
        assertThat(declared.stamped).isFalse()
        assertThat(declared.sweptVersionHash).isNull()
    }

    @Test
    fun `a declared version reports the baseline once a sweep has completed against one`() {
        val baseline = declaration("Person", "Retired").version
        versionStore.markSwept(baseline)

        val declared = service.currentDeclaredVersion()

        assertThat(declared.sweptVersionHash).isEqualTo(baseline.contentHash)
        // The declaration has moved on from what was last reconciled, which is what an operator is
        // looking for before deciding to sweep.
        assertThat(declared.sweptVersionHash).isNotEqualTo(declared.contentHash)
    }

    @Test
    fun `the current declared version follows the declaration as it moves`() {
        declaredSchemaSource.declaration = declaration("Person", "Robot")

        assertThat(service.currentDeclaredVersion().entityTypeNames).containsExactly("Person", "Robot")
    }

    @Test
    fun `latest reports answers whole-graph checks only, newest first`() {
        driftReportStore.saveDriftReport(report(capturedAt = EARLIER, contextId = null))
        driftReportStore.saveDriftReport(report(capturedAt = LATER, contextId = null))
        driftReportStore.saveDriftReport(report(capturedAt = LATER, contextId = CONTEXT))

        val reports = service.latestReports(limit = 10)

        assertThat(reports).hasSize(2)
        assertThat(reports.map { it.capturedAt }).containsExactly(LATER.toString(), EARLIER.toString())
        assertThat(reports).allMatch { it.contextId == null }
    }

    @Test
    fun `reports in context answers that context only`() {
        driftReportStore.saveDriftReport(report(capturedAt = LATER, contextId = null))
        driftReportStore.saveDriftReport(report(capturedAt = LATER, contextId = CONTEXT))
        driftReportStore.saveDriftReport(report(capturedAt = LATER, contextId = ContextId("elsewhere")))

        val reports = service.reportsInContext(CONTEXT.value, limit = 10)

        assertThat(reports).hasSize(1)
        assertThat(reports.single().contextId).isEqualTo(CONTEXT.value)
    }

    @Test
    fun `a since window bounds what a read returns`() {
        driftReportStore.saveDriftReport(report(capturedAt = EARLIER, contextId = null))
        driftReportStore.saveDriftReport(report(capturedAt = LATER, contextId = null))

        val reports = service.latestReports(limit = 10, since = LATER)

        assertThat(reports.map { it.capturedAt }).containsExactly(LATER.toString())
    }

    @Test
    fun `a read is scoped to the schema the application declares now`() {
        driftReportStore.saveDriftReport(report(capturedAt = LATER, contextId = null))
        declaredSchemaSource.declaration = declaration("Person", schemaName = "another-schema")

        assertThat(service.latestReports(limit = 10)).isEmpty()
    }

    // ---- Bounds ----

    @Test
    fun `a limit outside its bounds is refused with the bound named`() {
        assertThatThrownBy { service.latestReports(limit = 0) }
            .isInstanceOf(GovernanceRequestException::class.java)
            .hasMessageContaining("1")
            .hasMessageContaining("200")
            .hasMessageContaining("0")

        assertThatThrownBy { service.latestReports(limit = 201) }
            .isInstanceOf(GovernanceRequestException::class.java)
            .hasMessageContaining("201")

        assertThatThrownBy { service.reportsInContext(CONTEXT.value, limit = -1) }
            .isInstanceOf(GovernanceRequestException::class.java)
            .hasMessageContaining("-1")
    }

    @Test
    fun `a blank identifier is refused, and nothing is read`() {
        driftReportStore.saveDriftReport(report(capturedAt = LATER, contextId = CONTEXT))

        assertThatThrownBy { service.reportsInContext("  ", limit = 10) }
            .isInstanceOf(GovernanceRequestException::class.java)
            .hasMessageContaining("contextId")

        assertThatThrownBy { service.releaseProposition(CONTEXT.value, "") }
            .isInstanceOf(GovernanceRequestException::class.java)
            .hasMessageContaining("propositionId")
    }

    @Test
    fun `the bounds are checked before the store is asked`() {
        // The store throws if it is ever reached, so a refusal that reached it would fail here as
        // something other than a GovernanceRequestException.
        driftReportStore.refuseReads = true

        assertThatThrownBy { service.latestReports(limit = 0) }
            .isInstanceOf(GovernanceRequestException::class.java)
    }

    // ---- Running a check ----

    @Test
    fun `a check reports the drift the graph holds and writes it down`() {
        val result = service.runCheck()

        assertThat(result.driftedEntityTypes).containsExactly("Ghost")
        assertThat(result.hasDrift).isTrue()
        assertThat(result.contextId).isNull()
        assertThat(driftReportStore.saved).hasSize(1)
        assertThat(result.versionHash).isEqualTo(driftReportStore.saved.single().versionHash)
    }

    @Test
    fun `a scoped check reports under that context`() {
        val result = service.runCheck(CONTEXT.value)

        assertThat(result.contextId).isEqualTo(CONTEXT.value)
        assertThat(observed.lastScope).isEqualTo(CONTEXT)
    }

    @Test
    fun `a check moves no proposition`() {
        propositions.save(proposition("Ada haunts the archive", "Ghost"))
        propositions.save(proposition("Ada wrote the notes", "Person"))

        service.runCheck()

        assertThat(propositions.findAll()).allMatch { it.status == PropositionStatus.ACTIVE }
    }

    /**
     * The impact contract. A check's answer has to carry BOTH halves of the comparison, because a
     * sweep acts on both: the graph-truth half in the drift sets, and the declaration's own movement
     * in `declaredDiff`. A response carrying only the first could read clean while a sweep on the
     * same state quarantined.
     */
    @Test
    fun `a check answers with the full impact a sweep would evaluate`() {
        // A completed sweep against a declaration that still had `Retired` on it. The declaration in
        // force has dropped it, so the declared half of the comparison has a removal in it.
        versionStore.markSwept(declaration("Person", "Retired").version)

        val result = service.runCheck()

        // Half one: what the graph holds and nobody declared.
        assertThat(result.driftedEntityTypes).containsExactly("Ghost")

        // Half two: how the declaration itself moved since that sweep. This is the half a response
        // that dropped `declaredDiff` would lose.
        assertThat(result.declaredDiff).isNotNull
        assertThat(result.declaredDiff!!.removedEntityTypes).contains("Retired")
        assertThat(result.declaredDiff!!.empty).isFalse()

        // And the merged comparison a sweep evaluates propositions against holds both.
        assertThat(result.sweepImpact.removedEntityTypes).contains("Ghost", "Retired")
        assertThat(result.hasAnyChange).isTrue()
    }

    @Test
    fun `a check renders a narrowed property so a person can read it`() {
        versionStore.markSwept(declarationWithAge(type = "String", cardinality = Cardinality.LIST).version)
        declaredSchemaSource.declaration = declarationWithAge(type = "String", cardinality = Cardinality.ONE)

        val result = service.runCheck()

        assertThat(result.declaredDiff!!.changedProperties).hasSize(1)
        val changed = result.declaredDiff!!.changedProperties.single()
        assertThat(changed.typeName).isEqualTo("Person")
        assertThat(changed.propertyName).isEqualTo("age")
        assertThat(changed.renamedTo).isNull()
        assertThat(changed.before).isEqualTo("String LIST")
        assertThat(changed.after).isEqualTo("String ONE")
    }

    // ---- Releasing ----

    @Test
    fun `a release restores an ACTIVE proposition and clears the reason`() {
        val held = quarantine(proposition("Ada haunts the archive", "Ghost"), from = PropositionStatus.ACTIVE)

        val released = service.releaseProposition(CONTEXT.value, held.id)

        assertThat(released).isNotNull
        assertThat(released!!.propositionId).isEqualTo(held.id)
        assertThat(released.contextId).isEqualTo(CONTEXT.value)
        assertThat(released.status).isEqualTo(PropositionStatus.ACTIVE.name)
        assertThat(released.quarantined).isFalse()
        assertThat(released.quarantineReason).isNull()

        // And the store agrees, so the answer describes the state a later read would find.
        val reloaded = propositions.findById(held.id)!!
        assertThat(reloaded.status).isEqualTo(PropositionStatus.ACTIVE)
        assertThat(reloaded.metadata).doesNotContainKey(DiceMetadataKeys.QUARANTINE_REASON)
    }

    /**
     * A proposition can be quarantined from any status, so a release has to put it back exactly
     * where it came from. Restoring everything to ACTIVE would revive a proposition ordinary decay
     * had already retired.
     */
    @Test
    fun `a release restores a non-ACTIVE prior status`() {
        val held = quarantine(
            proposition("Ada wrote the notes", "Ghost").withStatus(PropositionStatus.STALE),
            from = PropositionStatus.STALE,
        )

        val released = service.releaseProposition(CONTEXT.value, held.id)

        assertThat(released!!.status).isEqualTo(PropositionStatus.STALE.name)
        assertThat(released.quarantined).isFalse()
        assertThat(propositions.findById(held.id)!!.status).isEqualTo(PropositionStatus.STALE)
    }

    @Test
    fun `a release in the wrong context writes nothing`() {
        val held = quarantine(proposition("Ada haunts the archive", "Ghost"), from = PropositionStatus.PROMOTED)

        assertThat(service.releaseProposition("some-other-context", held.id)).isNull()

        // Still held, still explained. The scope check happened before the release.
        val reloaded = propositions.findById(held.id)!!
        assertThat(reloaded.status).isEqualTo(PropositionStatus.QUARANTINED)
        assertThat(reloaded.metadata).containsKey(DiceMetadataKeys.QUARANTINE_REASON)
    }

    @Test
    fun `releasing an unknown id answers nothing`() {
        assertThat(service.releaseProposition(CONTEXT.value, "no-such-proposition")).isNull()
    }

    @Test
    fun `releasing a proposition that is not quarantined answers nothing`() {
        val active = propositions.save(proposition("Ada wrote the notes", "Person"))

        assertThat(service.releaseProposition(CONTEXT.value, active.id)).isNull()
        assertThat(propositions.findById(active.id)!!.status).isEqualTo(PropositionStatus.ACTIVE)
    }

    @Test
    fun `releasing twice is safe`() {
        val held = quarantine(proposition("Ada haunts the archive", "Ghost"), from = PropositionStatus.ACTIVE)

        assertThat(service.releaseProposition(CONTEXT.value, held.id)).isNotNull
        assertThat(service.releaseProposition(CONTEXT.value, held.id)).isNull()
    }

    /** The whole loop as an operator drives it: check, sweep, read, release. */
    @Test
    fun `check then sweep then release is a round trip`() {
        val stranded = propositions.save(proposition("Ada haunts the archive", "Ghost"))

        val check = service.runCheck()
        assertThat(check.hasDrift).isTrue()
        assertThat(check.sweepImpact.removedEntityTypes).contains("Ghost")

        // The sweep is the host's deliberate step; the service never performs it. It runs on the
        // runner's own merged comparison, which is what `sweepImpact` above showed the operator.
        val swept = sweep.sweep(
            diff = runner.run().quarantineDiff,
            policy = MentionTypeDriftQuarantinePolicy(),
            contextId = CONTEXT,
        )
        assertThat(swept.quarantined).hasSize(1)
        assertThat(propositions.findById(stranded.id)!!.status).isEqualTo(PropositionStatus.QUARANTINED)

        val released = service.releaseProposition(CONTEXT.value, stranded.id)

        assertThat(released!!.status).isEqualTo(PropositionStatus.ACTIVE.name)
        assertThat(released.quarantined).isFalse()
        assertThat(released.quarantineReason).isNull()
    }

    // ---- Fixtures ----

    /** Quarantine [proposition] by hand, the way a sweep would, recording where it came from. */
    private fun quarantine(proposition: Proposition, from: PropositionStatus): Proposition =
        propositions.save(
            proposition
                .withStatus(PropositionStatus.QUARANTINED)
                .withMetadataValue(DiceMetadataKeys.QUARANTINE_REASON, "Schema drift: type(s) [Ghost] removed")
                .withMetadataValue(PREVIOUS_STATUS_KEY, from.name),
        )

    private fun proposition(text: String, mentionType: String): Proposition = Proposition(
        contextId = CONTEXT,
        text = text,
        mentions = listOf(EntityMention(span = text, type = mentionType)),
        confidence = 0.9,
    )

    private fun report(capturedAt: Instant, contextId: ContextId?): DriftReport = DriftReport(
        schemaName = SCHEMA_NAME,
        versionHash = declaration("Person").version.contentHash,
        driftedEntityTypes = setOf("Ghost"),
        driftedRelationshipTypes = emptySet(),
        capturedAt = capturedAt,
        contextId = contextId,
    )

    private companion object {

        const val SCHEMA_NAME = "governance-test-schema"

        /** `DriftQuarantineKeys.PREVIOUS_STATUS`, spelled out so the fixture states what it writes. */
        const val PREVIOUS_STATUS_KEY = "dice.metamodel.quarantine.previousStatus"

        val CONTEXT = ContextId("governance-test-context")
        val EARLIER: Instant = Instant.parse("2026-01-01T00:00:00Z")
        val LATER: Instant = Instant.parse("2026-02-01T00:00:00Z")

        fun declaration(vararg entityTypeNames: String, schemaName: String = SCHEMA_NAME): DeclaredSchema =
            DeclaredSchema(
                version = MetamodelVersion(
                    schemaName = schemaName,
                    entityTypeNames = entityTypeNames.toList(),
                    entityTypeLabels = emptyMap(),
                    entityTypeProperties = emptyMap(),
                    relationshipNames = emptyList(),
                ),
                relationshipTypeNames = emptySet(),
            )

        /** One governed `Person` carrying an `age` property of the given shape. */
        fun declarationWithAge(type: String, cardinality: Cardinality): DeclaredSchema = DeclaredSchema(
            version = MetamodelVersion(
                schemaName = SCHEMA_NAME,
                entityTypeNames = listOf("Person"),
                entityTypeLabels = mapOf("Person" to setOf("Person")),
                entityTypeProperties = mapOf(
                    "Person" to setOf(
                        PropertySignature("age", PropertySignature.Kind.VALUE, type, cardinality),
                    ),
                ),
                relationshipNames = emptyList(),
            ),
            relationshipTypeNames = emptySet(),
        )
    }
}

/** A [DeclaredSchemaSource] whose declaration a test can move. */
private class MutableDeclaredSchemaSource(var declaration: DeclaredSchema) : DeclaredSchemaSource {
    override fun declare(): DeclaredSchema = declaration
}

/** An [ObservedSchemaSource] that answers one fixed snapshot and remembers what it was asked. */
private class MutableObservedSchemaSource(private val entityTypeNames: Set<String>) : ObservedSchemaSource {

    var lastScope: ContextId? = null
        private set

    override fun observe(contextId: ContextId?): ObservedSchema {
        lastScope = contextId
        return ObservedSchema(
            entityTypeNames = entityTypeNames,
            relationshipTypeNames = emptySet(),
            capturedAt = Instant.parse("2026-03-01T00:00:00Z"),
        )
    }
}

/** A [DriftReportStore] keeping its log in a list, with the three scoped reads done honestly. */
private class ListDriftReportStore : DriftReportStore {

    val saved = mutableListOf<DriftReport>()

    /** Set by a test that wants to prove a read never happened. */
    var refuseReads = false

    override fun saveDriftReport(report: DriftReport) {
        saved += report
    }

    override fun driftReports(schemaName: String, limit: Int, since: Instant?): List<DriftReport> =
        matching(schemaName, limit, since) { true }

    override fun globalDriftReports(schemaName: String, limit: Int, since: Instant?): List<DriftReport> =
        matching(schemaName, limit, since) { it.contextId == null }

    override fun driftReportsInContext(
        schemaName: String,
        contextId: ContextId,
        limit: Int,
        since: Instant?,
    ): List<DriftReport> = matching(schemaName, limit, since) { it.contextId == contextId }

    /** The scope filter is applied before the bound, the way a real backend's query would. */
    private fun matching(
        schemaName: String,
        limit: Int,
        since: Instant?,
        scope: (DriftReport) -> Boolean,
    ): List<DriftReport> {
        check(!refuseReads) { "the store was read when the request should have been refused first" }
        require(limit > 0) { "limit must be positive, but was $limit" }
        return saved
            .filter { it.schemaName == schemaName && scope(it) && (since == null || !it.capturedAt.isBefore(since)) }
            .sortedByDescending { it.capturedAt }
            .take(limit)
    }
}

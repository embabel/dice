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
package com.embabel.dice.agent

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.ContextId
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.governance.GovernanceOperationsService
import com.embabel.dice.metamodel.DeclaredSchema
import com.embabel.dice.metamodel.DeclaredSchemaSource
import com.embabel.dice.metamodel.DriftReport
import com.embabel.dice.metamodel.DriftReportStore
import com.embabel.dice.metamodel.InMemoryMetamodelVersionStore
import com.embabel.dice.metamodel.MetamodelVersion
import com.embabel.dice.metamodel.ObservedSchema
import com.embabel.dice.metamodel.ObservedSchemaSource
import com.embabel.dice.metamodel.support.DefaultDriftCheckRunner
import com.embabel.dice.metamodel.support.StructuralMetamodelDiffer
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import com.embabel.dice.spi.PropositionStoreDriftSweep
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * The agent-facing half of the operator surface, over the same service the REST controller uses.
 *
 * The point of these is that a tool answers the same facts the HTTP route does, and that a refused
 * request reaches the caller as an error carrying the bound that was broken.
 */
class GovernanceToolsTest {

    private lateinit var driftReportStore: ListDriftReportStore
    private lateinit var propositions: InMemoryPropositionRepository
    private lateinit var versionStore: InMemoryMetamodelVersionStore
    private lateinit var tools: GovernanceTools

    @BeforeEach
    fun setUp() {
        driftReportStore = ListDriftReportStore()
        propositions = InMemoryPropositionRepository()
        versionStore = InMemoryMetamodelVersionStore()
        val declaredSchemaSource = DeclaredSchemaSource { DECLARATION }
        tools = GovernanceTools(
            GovernanceOperationsService(
                declaredSchemaSource = declaredSchemaSource,
                versionStore = versionStore,
                driftReportStore = driftReportStore,
                driftCheckRunner = DefaultDriftCheckRunner(
                    declaredSchemaSource = declaredSchemaSource,
                    versionStore = versionStore,
                    observedSchemaSource = object : ObservedSchemaSource {
                        override fun observe(contextId: ContextId?): ObservedSchema = ObservedSchema(
                            entityTypeNames = setOf("Person", "Ghost"),
                            relationshipTypeNames = emptySet(),
                            capturedAt = CAPTURED_AT,
                        )
                    },
                    differ = StructuralMetamodelDiffer(),
                    metamodelDiffer = StructuralMetamodelDiffer(),
                    driftReportStore = driftReportStore,
                ),
                driftSweep = PropositionStoreDriftSweep(propositions),
                propositions = propositions,
            ),
        )
    }

    @Test
    fun `the whole operator surface is exposed as tools`() {
        val names = GovernanceTools.asTools(
            GovernanceOperationsService(
                declaredSchemaSource = { DECLARATION },
                versionStore = versionStore,
                driftReportStore = driftReportStore,
                driftCheckRunner = object : com.embabel.dice.metamodel.DriftCheckRunner {
                    override fun run(contextId: ContextId?) =
                        throw AssertionError("no check runs while tools are being listed")
                },
                driftSweep = PropositionStoreDriftSweep(propositions),
                propositions = propositions,
            ),
        ).map { it.definition.name }

        assertThat(names).containsExactlyInAnyOrder(
            "declared_schema_version",
            "latest_drift_reports",
            "drift_reports_in_context",
            "run_drift_check",
            "release_quarantined_proposition",
        )
    }

    @Test
    fun `the declared version tool reports the declaration in force`() {
        assertThat(textOf(tools.declaredSchemaVersion()))
            .contains("\"schemaName\":\"$SCHEMA_NAME\"")
            .contains("\"stamped\":false")
    }

    @Test
    fun `the report tools split whole-graph checks from a context's`() {
        driftReportStore.saveDriftReport(report(contextId = null))
        driftReportStore.saveDriftReport(report(contextId = CONTEXT))

        assertThat(textOf(tools.latestDriftReports())).contains("\"contextId\":null")
        assertThat(textOf(tools.driftReportsInContext(CONTEXT.value)))
            .contains("\"contextId\":\"${CONTEXT.value}\"")
    }

    @Test
    fun `the check tool carries both halves of the comparison`() {
        versionStore.markSwept(declaration("Person", "Retired").version)

        val text = textOf(tools.runDriftCheck())

        assertThat(text).contains("\"driftedEntityTypes\":[\"Ghost\"]")
        assertThat(text).contains("\"declaredDiff\":{")
        assertThat(text).contains("\"Retired\"")
    }

    @Test
    fun `a refused request comes back as an error naming the bound`() {
        val result = tools.latestDriftReports(limit = 500)

        assertThat(result).isInstanceOf(Tool.Result.Error::class.java)
        assertThat(result.toString()).contains("200")
    }

    @Test
    fun `the release tool answers the post-release state`() {
        val held = propositions.save(
            proposition("Ada haunts the archive", "Ghost")
                .withStatus(PropositionStatus.QUARANTINED)
                .withMetadataValue(DiceMetadataKeys.QUARANTINE_REASON, "Schema drift: type(s) [Ghost] removed")
                .withMetadataValue(PREVIOUS_STATUS_KEY, PropositionStatus.STALE.name),
        )

        val text = textOf(tools.releaseQuarantinedProposition(CONTEXT.value, held.id))

        assertThat(text).contains("\"status\":\"STALE\"")
        assertThat(text).contains("\"quarantined\":false")
        assertThat(text).contains("\"quarantineReason\":null")
    }

    @Test
    fun `releasing across contexts is refused and writes nothing`() {
        val held = propositions.save(
            proposition("Ada haunts the archive", "Ghost")
                .withStatus(PropositionStatus.QUARANTINED)
                .withMetadataValue(PREVIOUS_STATUS_KEY, PropositionStatus.ACTIVE.name),
        )

        assertThat(tools.releaseQuarantinedProposition("some-other-context", held.id))
            .isInstanceOf(Tool.Result.Error::class.java)
        assertThat(propositions.findById(held.id)!!.status).isEqualTo(PropositionStatus.QUARANTINED)
    }

    private fun textOf(result: Tool.Result): String = (result as Tool.Result.Text).content

    private fun proposition(text: String, mentionType: String): Proposition = Proposition(
        contextId = CONTEXT,
        text = text,
        mentions = listOf(EntityMention(span = text, type = mentionType)),
        confidence = 0.9,
    )

    private fun report(contextId: ContextId?): DriftReport = DriftReport(
        schemaName = SCHEMA_NAME,
        versionHash = DECLARATION.version.contentHash,
        driftedEntityTypes = setOf("Ghost"),
        driftedRelationshipTypes = emptySet(),
        capturedAt = CAPTURED_AT,
        contextId = contextId,
    )

    private companion object {

        const val SCHEMA_NAME = "governance-tools-schema"

        /** `DriftQuarantineKeys.PREVIOUS_STATUS`, spelled out so the fixture states what it writes. */
        const val PREVIOUS_STATUS_KEY = "dice.metamodel.quarantine.previousStatus"

        val CONTEXT = ContextId("governance-tools-context")
        val CAPTURED_AT: Instant = Instant.parse("2026-02-01T00:00:00Z")

        fun declaration(vararg entityTypeNames: String): DeclaredSchema = DeclaredSchema(
            version = MetamodelVersion(
                schemaName = SCHEMA_NAME,
                entityTypeNames = entityTypeNames.toList(),
                entityTypeLabels = emptyMap(),
                entityTypeProperties = emptyMap(),
                relationshipNames = emptyList(),
            ),
            relationshipTypeNames = emptySet(),
        )

        val DECLARATION: DeclaredSchema = declaration("Person")
    }
}

/** A [DriftReportStore] keeping its log in a list, with the three scoped reads done honestly. */
private class ListDriftReportStore : DriftReportStore {

    val saved = mutableListOf<DriftReport>()

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

    private fun matching(
        schemaName: String,
        limit: Int,
        since: Instant?,
        scope: (DriftReport) -> Boolean,
    ): List<DriftReport> {
        require(limit > 0) { "limit must be positive, but was $limit" }
        return saved
            .filter { it.schemaName == schemaName && scope(it) && (since == null || !it.capturedAt.isBefore(since)) }
            .sortedByDescending { it.capturedAt }
            .take(limit)
    }
}

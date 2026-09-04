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
package com.embabel.dice.web.rest

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
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

/**
 * The HTTP contract of the governance operator surface, driven through the real service over
 * in-memory stores. Nothing is mocked, so a route that answered `200` with the wrong body shows up
 * here as a wrong body. A satisfied mock could not have caught it.
 */
class GovernanceControllerTest {

    private lateinit var mockMvc: MockMvc
    private lateinit var driftReportStore: ListDriftReportStore
    private lateinit var propositions: InMemoryPropositionRepository
    private lateinit var versionStore: InMemoryMetamodelVersionStore

    @BeforeEach
    fun setUp() {
        driftReportStore = ListDriftReportStore()
        propositions = InMemoryPropositionRepository()
        versionStore = InMemoryMetamodelVersionStore()
        val declaredSchemaSource = DeclaredSchemaSource { DECLARATION }
        val service = GovernanceOperationsService(
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
        )
        val objectMapper = ObjectMapper()
            .registerModule(KotlinModule.Builder().build())
            .registerModule(JavaTimeModule())
        mockMvc = MockMvcBuilders.standaloneSetup(GovernanceController(service))
            .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
            .build()
    }

    // ---- Reads ----

    @Test
    fun `GET declared-version answers what the application declares`() {
        mockMvc.perform(get("/api/v1/metamodel/declared-version"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.schemaName").value(SCHEMA_NAME))
            .andExpect(jsonPath("$.contentHash").value(DECLARATION.version.contentHash))
            .andExpect(jsonPath("$.entityTypeNames[0]").value("Person"))
            .andExpect(jsonPath("$.stamped").value(false))
            .andExpect(jsonPath("$.sweptVersionHash").doesNotExist())
    }

    @Test
    fun `GET drift-reports answers whole-graph checks only`() {
        driftReportStore.saveDriftReport(report(CAPTURED_AT, contextId = null))
        driftReportStore.saveDriftReport(report(CAPTURED_AT, contextId = CONTEXT))

        mockMvc.perform(get("/api/v1/metamodel/drift-reports"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].contextId").doesNotExist())
            .andExpect(jsonPath("$[0].driftedEntityTypes[0]").value("Ghost"))
            .andExpect(jsonPath("$[0].hasDrift").value(true))
    }

    @Test
    fun `GET a context's drift-reports answers that context only`() {
        driftReportStore.saveDriftReport(report(CAPTURED_AT, contextId = null))
        driftReportStore.saveDriftReport(report(CAPTURED_AT, contextId = CONTEXT))

        mockMvc.perform(get("/api/v1/metamodel/contexts/{contextId}/drift-reports", CONTEXT.value))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].contextId").value(CONTEXT.value))
    }

    @Test
    fun `a since window bounds a read`() {
        driftReportStore.saveDriftReport(report(EARLIER, contextId = null))
        driftReportStore.saveDriftReport(report(CAPTURED_AT, contextId = null))

        mockMvc.perform(get("/api/v1/metamodel/drift-reports").param("since", CAPTURED_AT.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].capturedAt").value(CAPTURED_AT.toString()))
    }

    // ---- Bounds and validation ----

    @Test
    fun `an over-limit read answers 400 naming the bound`() {
        mockMvc.perform(get("/api/v1/metamodel/drift-reports").param("limit", "500"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value(containsString("500")))
            .andExpect(jsonPath("$.error").value(containsString("200")))
    }

    @Test
    fun `a zero limit answers 400 naming the bound`() {
        mockMvc.perform(
            get("/api/v1/metamodel/contexts/{contextId}/drift-reports", CONTEXT.value).param("limit", "0"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value(containsString("limit")))
    }

    @Test
    fun `an unparseable since answers 400 naming the parameter`() {
        mockMvc.perform(get("/api/v1/metamodel/drift-reports").param("since", "last Tuesday"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value(containsString("since")))
    }

    // ---- Running a check ----

    @Test
    fun `POST drift-checks answers the full impact a sweep would evaluate`() {
        versionStore.markSwept(declaration("Person", "Retired").version)

        mockMvc.perform(post("/api/v1/metamodel/drift-checks"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.schemaName").value(SCHEMA_NAME))
            .andExpect(jsonPath("$.contextId").doesNotExist())
            .andExpect(jsonPath("$.hasDrift").value(true))
            .andExpect(jsonPath("$.driftedEntityTypes[0]").value("Ghost"))
            // The declaration's own movement travels with the response. Without it, an operator
            // reading this could think a sweep had nothing to do.
            .andExpect(jsonPath("$.declaredDiff").exists())
            .andExpect(jsonPath("$.declaredDiff.removedEntityTypes[0]").value("Retired"))
            .andExpect(jsonPath("$.sweepImpact.removedEntityTypes.length()").value(2))
    }

    @Test
    fun `POST a context's drift-checks reports under that context`() {
        mockMvc.perform(post("/api/v1/metamodel/contexts/{contextId}/drift-checks", CONTEXT.value))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.contextId").value(CONTEXT.value))
    }

    @Test
    fun `a check writes a report and moves no proposition`() {
        propositions.save(proposition("Ada haunts the archive", "Ghost"))

        mockMvc.perform(post("/api/v1/metamodel/drift-checks")).andExpect(status().isOk)

        assertThat(driftReportStore.saved).hasSize(1)
        assertThat(propositions.findAll()).allMatch { it.status == PropositionStatus.ACTIVE }
    }

    // ---- Releasing ----

    @Test
    fun `POST release answers the post-release state`() {
        val held = quarantine(proposition("Ada haunts the archive", "Ghost"), from = PropositionStatus.ACTIVE)

        mockMvc.perform(releaseOf(CONTEXT.value, held.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.propositionId").value(held.id))
            .andExpect(jsonPath("$.contextId").value(CONTEXT.value))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.quarantined").value(false))
            .andExpect(jsonPath("$.quarantineReason").doesNotExist())
    }

    @Test
    fun `POST release restores a non-ACTIVE prior status`() {
        val held = quarantine(
            proposition("Ada wrote the notes", "Ghost").withStatus(PropositionStatus.PROMOTED),
            from = PropositionStatus.PROMOTED,
        )

        mockMvc.perform(releaseOf(CONTEXT.value, held.id))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("PROMOTED"))
            .andExpect(jsonPath("$.quarantined").value(false))
    }

    @Test
    fun `POST release in the wrong context answers 404 and writes nothing`() {
        val held = quarantine(proposition("Ada haunts the archive", "Ghost"), from = PropositionStatus.ACTIVE)

        mockMvc.perform(releaseOf("some-other-context", held.id))
            .andExpect(status().isNotFound)

        assertThat(propositions.findById(held.id)!!.status).isEqualTo(PropositionStatus.QUARANTINED)
    }

    @Test
    fun `POST release of an unknown proposition answers 404`() {
        mockMvc.perform(releaseOf(CONTEXT.value, "no-such-proposition"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `POST release of a proposition that is not quarantined answers 404`() {
        val active = propositions.save(proposition("Ada wrote the notes", "Person"))

        mockMvc.perform(releaseOf(CONTEXT.value, active.id))
            .andExpect(status().isNotFound)
    }

    // ---- Fixtures ----

    private fun releaseOf(contextId: String, propositionId: String) = post(
        "/api/v1/metamodel/contexts/{contextId}/quarantine/{propositionId}/release",
        contextId,
        propositionId,
    )

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
        versionHash = DECLARATION.version.contentHash,
        driftedEntityTypes = setOf("Ghost"),
        driftedRelationshipTypes = emptySet(),
        capturedAt = capturedAt,
        contextId = contextId,
    )

    private companion object {

        const val SCHEMA_NAME = "governance-rest-schema"

        /** `DriftQuarantineKeys.PREVIOUS_STATUS`, spelled out so the fixture states what it writes. */
        const val PREVIOUS_STATUS_KEY = "dice.metamodel.quarantine.previousStatus"

        val CONTEXT = ContextId("governance-rest-context")
        val EARLIER: Instant = Instant.parse("2026-01-01T00:00:00Z")
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

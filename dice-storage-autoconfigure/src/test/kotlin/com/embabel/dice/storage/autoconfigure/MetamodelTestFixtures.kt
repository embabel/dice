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
package com.embabel.dice.storage.autoconfigure

import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.dice.metamodel.DeclaredSchema
import com.embabel.dice.metamodel.DeclaredSchemaSource
import com.embabel.dice.metamodel.DriftReport
import com.embabel.dice.metamodel.DriftReportStore
import com.embabel.dice.metamodel.MetamodelVersion
import com.embabel.dice.metamodel.MetamodelVersionStore
import com.embabel.dice.metamodel.ObservedSchema
import com.embabel.dice.metamodel.ObservedSchemaSource
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStore
import java.time.Instant

/**
 * Hand-written stand-ins for the governance collaborators, used by the wiring tests.
 *
 * Real objects rather than mocks on purpose. Half of what these tests check is *behaviour* — did
 * the observe tier really refuse to quarantine, did a report really get written — and a mock that
 * records calls can't answer that without restating the runner's logic in the assertions. These
 * keep their state in a list you can read afterwards.
 */
internal object MetamodelTestFixtures {

    const val SCHEMA_NAME = "test-schema"

    val CONTEXT_ID = ContextId("metamodel-test-context")

    /** A declared schema that governs one entity type, `Person`, and no relationships. */
    fun declaredSchema(vararg entityTypeNames: String = arrayOf("Person")): DeclaredSchema =
        DeclaredSchema(
            version = MetamodelVersion(
                schemaName = SCHEMA_NAME,
                entityTypeNames = entityTypeNames.toList(),
                entityTypeLabels = emptyMap(),
                entityTypeProperties = emptyMap(),
                relationshipNames = emptyList(),
            ),
            relationshipTypeNames = emptySet(),
        )

    /** A proposition mentioning [mentionType], so the quarantine policy has something to catch. */
    fun proposition(text: String, mentionType: String): Proposition =
        Proposition(
            contextId = CONTEXT_ID,
            text = text,
            mentions = listOf(EntityMention(span = text, type = mentionType)),
            confidence = 0.9,
        )
}

/** A [DeclaredSchemaSource] handing back one fixed declaration. */
internal class FixedDeclaredSchemaSource(
    private val declared: DeclaredSchema = MetamodelTestFixtures.declaredSchema(),
) : DeclaredSchemaSource {
    override fun declare(): DeclaredSchema = declared
}

/** An [ObservedSchemaSource] handing back one fixed snapshot, whatever the scope. */
internal class FixedObservedSchemaSource(
    private val observed: ObservedSchema = ObservedSchema(
        entityTypeNames = setOf("Person", "Ghost"),
        relationshipTypeNames = emptySet(),
        capturedAt = Instant.parse("2026-01-01T00:00:00Z"),
    ),
) : ObservedSchemaSource {
    override fun observe(contextId: ContextId?): ObservedSchema = observed
}

/** A [MetamodelVersionStore] that keeps stamps in a list, newest last. */
internal class RecordingMetamodelVersionStore : MetamodelVersionStore {

    val saved = mutableListOf<MetamodelVersion>()

    override fun saveVersion(version: MetamodelVersion) {
        saved += version
    }

    override fun latestVersion(schemaName: String): MetamodelVersion? =
        saved.lastOrNull { it.schemaName == schemaName }

    override fun versionHistory(schemaName: String): List<MetamodelVersion> =
        saved.filter { it.schemaName == schemaName }.reversed()
}

/** A [DriftReportStore] that keeps reports in a list, newest last. */
internal class RecordingDriftReportStore : DriftReportStore {

    val saved = mutableListOf<DriftReport>()

    override fun saveDriftReport(report: DriftReport) {
        saved += report
    }

    override fun driftReports(schemaName: String, limit: Int, since: Instant?): List<DriftReport> =
        saved.filter { it.schemaName == schemaName }.reversed().take(limit)

    override fun globalDriftReports(schemaName: String, limit: Int, since: Instant?): List<DriftReport> =
        driftReports(schemaName, limit, since).filter { it.contextId == null }

    override fun driftReportsInContext(
        schemaName: String,
        contextId: ContextId,
        limit: Int,
        since: Instant?,
    ): List<DriftReport> = driftReports(schemaName, limit, since).filter { it.contextId == contextId }
}

/**
 * A [PropositionStore] over an in-memory map. Deliberately the *base* port and nothing more: a
 * context holding one of these has no `PropositionRepository`, which is what proves the drift
 * runner asks for the narrow port it actually uses.
 */
internal class MapPropositionStore(private val initial: List<Proposition> = emptyList()) : PropositionStore {

    private val byId = linkedMapOf<String, Proposition>()

    init {
        reset()
    }

    /** Back to the propositions this store was built with, all `ACTIVE` again. */
    fun reset() {
        byId.clear()
        initial.forEach { byId[it.id] = it }
    }

    override fun save(proposition: Proposition): Proposition {
        byId[proposition.id] = proposition
        return proposition
    }

    override fun findById(id: String): Proposition? = byId[id]

    override fun findByEntity(entityIdentifier: RetrievableIdentifier): List<Proposition> = emptyList()

    override fun findByStatus(status: PropositionStatus): List<Proposition> =
        byId.values.filter { it.status == status }

    override fun findByGrounding(chunkId: String): List<Proposition> =
        byId.values.filter { chunkId in it.grounding }

    override fun findByMinLevel(minLevel: Int): List<Proposition> = byId.values.filter { it.level >= minLevel }

    override fun findAll(): List<Proposition> = byId.values.toList()

    override fun delete(id: String): Boolean = byId.remove(id) != null

    override fun count(): Int = byId.size
}

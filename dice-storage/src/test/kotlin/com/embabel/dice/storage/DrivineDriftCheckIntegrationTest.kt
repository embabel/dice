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
package com.embabel.dice.storage

import com.embabel.agent.core.ContextId
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.metamodel.DeclaredSchema
import com.embabel.dice.metamodel.DeclaredSchemaSource
import com.embabel.dice.metamodel.DriftCheckRunner
import com.embabel.dice.metamodel.MetamodelVersion
import com.embabel.dice.metamodel.support.DefaultDriftCheckRunner
import com.embabel.dice.metamodel.support.MentionTypeDriftQuarantinePolicy
import com.embabel.dice.metamodel.support.StructuralMetamodelDiffer
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * The whole drift check end to end, on real Drivine stores against a Neo4j testcontainer:
 * `DefaultDriftCheckRunner` wired to [DrivineMetamodelVersionStore], [DrivineObservedSchemaSource],
 * [DrivineDriftReportStore] and [DrivinePropositionRepository], with the real differ and the real
 * quarantine policy.
 *
 * The unit tests in `dice-metamodel` pin the runner's sequencing against fakes. What only a database
 * can answer is whether the three persistent pieces line up: a report written by one store names a
 * hash the other store can resolve, and the proposition the policy flagged comes back out of the
 * graph flagged.
 */
@SpringBootTest(classes = [TestApplication::class])
class DrivineDriftCheckIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }

    @Autowired
    private lateinit var versionStore: DrivineMetamodelVersionStore

    @Autowired
    private lateinit var reportStore: DrivineDriftReportStore

    @Autowired
    private lateinit var observedSchemaSource: DrivineObservedSchemaSource

    @Autowired
    private lateinit var repository: DrivinePropositionRepository

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    private val schemaName = "governed-schema"
    private val contextId = ContextId("drift-ctx")

    /** Declares one entity type. Anything else the graph holds is drift. */
    private val declaredVersion = MetamodelVersion(
        schemaName = schemaName,
        entityTypeNames = listOf("Person"),
        entityTypeLabels = mapOf("Person" to setOf("Person")),
        entityTypeProperties = mapOf("Person" to emptySet()),
        relationshipNames = emptyList(),
    )

    // StructuralMetamodelDiffer implements both differ interfaces; one instance plays both roles,
    // the way DefaultDriftCheckRunner's own doc says it ordinarily does.
    private val differ = StructuralMetamodelDiffer()

    private val runner: DriftCheckRunner by lazy {
        DefaultDriftCheckRunner(
            declaredSchemaSource = DeclaredSchemaSource {
                DeclaredSchema(version = declaredVersion, relationshipTypeNames = emptySet())
            },
            versionStore = versionStore,
            observedSchemaSource = observedSchemaSource,
            differ = differ,
            metamodelDiffer = differ,
            driftReportStore = reportStore,
            quarantinePolicy = MentionTypeDriftQuarantinePolicy(),
            propositionStore = repository,
        )
    }

    @AfterEach
    fun cleanUp() {
        repository.clearAll()
        persistenceManager.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    }

    @Test
    fun `an undeclared mention type is reported, resolvable, and quarantined`() {
        val stranded = repository.save(
            Proposition(
                contextId = contextId,
                text = "The ghost haunts the manor",
                mentions = listOf(EntityMention(span = "the ghost", type = "Ghost", role = MentionRole.SUBJECT)),
                confidence = 0.9,
            ),
        )

        val result = runner.run(dryRun = false, contextId = contextId)

        // 1. The check saw the undeclared type and nothing else.
        assertEquals(setOf("Ghost"), result.report.driftedEntityTypes)
        assertEquals(1, result.quarantinedCount)

        // 2. The report is really in the graph, under the context it was scoped to.
        val persisted = reportStore.driftReportsInContext(schemaName, contextId, limit = 10)
        assertEquals(listOf(result.report), persisted)
        assertTrue(
            reportStore.globalDriftReports(schemaName, limit = 10).isEmpty(),
            "a context-scoped check must not show up as a whole-graph one",
        )

        // 3. Its hash resolves through the version store, which is what stamping before reporting
        //    guarantees. Only real stores on both sides can show it.
        val resolved = versionStore.findVersion(schemaName, persisted.single().versionHash)
        assertNotNull(resolved, "a persisted report named a version hash nothing recorded")
        assertEquals(declaredVersion, resolved)

        // 4. The stranded proposition came back out of the graph flagged, carrying a readable
        //    quarantine reason.
        val reloaded = repository.findById(stranded.id)
        assertNotNull(reloaded)
        assertEquals(PropositionStatus.STALE, reloaded!!.status)
        val reason = reloaded.metadata[DiceMetadataKeys.QUARANTINE_REASON] as? String
        assertNotNull(reason, "quarantine must say why; metadata was ${reloaded.metadata}")
        assertTrue(reason!!.contains("Ghost"), "the reason must name the drifted type, but was: $reason")
    }

    @Test
    fun `a dry run records the same report and touches no proposition`() {
        val untouched = repository.save(
            Proposition(
                contextId = contextId,
                text = "The ghost is still here",
                mentions = listOf(EntityMention(span = "the ghost", type = "Ghost", role = MentionRole.SUBJECT)),
                confidence = 0.9,
            ),
        )

        val result = runner.run(dryRun = true, contextId = contextId)

        assertEquals(setOf("Ghost"), result.report.driftedEntityTypes)
        assertEquals(0, result.quarantinedCount)
        assertEquals(1, reportStore.driftReportsInContext(schemaName, contextId, limit = 10).size)
        assertEquals(PropositionStatus.ACTIVE, repository.findById(untouched.id)!!.status)
    }

    @Test
    fun `a conforming context reports no drift, and the run is still on the record`() {
        // A zero-drift check still persists a report, so an audit can see that the check ran and
        // found the context clean.
        repository.save(
            Proposition(
                contextId = contextId,
                text = "Ada is a person",
                mentions = listOf(EntityMention(span = "Ada", type = "Person", role = MentionRole.SUBJECT)),
                confidence = 0.9,
            ),
        )

        val result = runner.run(dryRun = false, contextId = contextId)

        assertTrue(result.report.driftedEntityTypes.isEmpty(), "got ${result.report.driftedEntityTypes}")
        assertEquals(0, result.quarantinedCount)
        assertEquals(listOf(result.report), reportStore.driftReportsInContext(schemaName, contextId, limit = 10))
    }

    @Test
    fun `two checks of one context accumulate as two reports, newest first`() {
        repository.save(
            Proposition(
                contextId = contextId,
                text = "The ghost haunts the manor",
                mentions = listOf(EntityMention(span = "the ghost", type = "Ghost", role = MentionRole.SUBJECT)),
                confidence = 0.9,
            ),
        )

        val first = runner.run(dryRun = true, contextId = contextId)
        val second = runner.run(dryRun = true, contextId = contextId)

        val history = reportStore.driftReportsInContext(schemaName, contextId, limit = 10)
        assertEquals(listOf(second.report, first.report), history, "a drift log accumulates; it is not a gauge")
    }

    @Test
    fun `another context's drift never reaches this one`() {
        repository.save(
            Proposition(
                contextId = ContextId("elsewhere"),
                text = "The poltergeist rattles the door",
                mentions = listOf(EntityMention(span = "the poltergeist", type = "Poltergeist", role = MentionRole.SUBJECT)),
                confidence = 0.9,
            ),
        )

        val result = runner.run(dryRun = false, contextId = contextId)

        assertTrue(result.report.driftedEntityTypes.isEmpty(), "got ${result.report.driftedEntityTypes}")
        assertEquals(0, result.quarantinedCount)
        assertEquals(
            PropositionStatus.ACTIVE,
            repository.findByContextId(ContextId("elsewhere")).single().status,
            "a check scoped to one context must not be able to reach another's data",
        )
    }
}

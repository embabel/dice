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
import com.embabel.dice.spi.DriftSweepCapable
import com.embabel.dice.metamodel.MetamodelVersion
import com.embabel.dice.metamodel.support.DefaultDriftCheckRunner
import com.embabel.dice.spi.MentionTypeDriftQuarantinePolicy
import com.embabel.dice.spi.PropositionStoreDriftSweep
import com.embabel.dice.metamodel.support.StructuralMetamodelDiffer
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * The whole drift check end to end, on real Drivine stores against a Neo4j testcontainer:
 * `DefaultDriftCheckRunner` wired to [DrivineMetamodelVersionStore], [DrivineObservedSchemaSource]
 * and [DrivineDriftReportStore], with the real differ.
 *
 * A check reports and moves nothing, so acting on what it found is a second, deliberate step: these
 * tests sweep through [PropositionStoreDriftSweep] over [DrivinePropositionRepository], with the
 * real quarantine policy, exactly the way a host would.
 *
 * The unit tests in `dice-metamodel` pin the runner's sequencing against fakes. What only a database
 * can answer is whether the persistent pieces line up: a report written by one store names a hash
 * the other store can resolve, and the proposition the policy flagged comes back out of the graph
 * flagged.
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
        )
    }

    /**
     * A second declaration, governing one type that carries a parent label. Every `Person` node
     * carries `Agent` too, and nothing declares `Agent` a type of its own, which is the pair of facts
     * an unscoped check has to keep apart.
     */
    private val hierarchyVersion = MetamodelVersion(
        schemaName = schemaName,
        entityTypeNames = listOf("Person"),
        entityTypeLabels = mapOf("Person" to setOf("Person", "Agent")),
        entityTypeProperties = mapOf("Person" to emptySet()),
        relationshipNames = emptyList(),
    )

    private val hierarchyRunner: DriftCheckRunner by lazy {
        DefaultDriftCheckRunner(
            declaredSchemaSource = DeclaredSchemaSource {
                DeclaredSchema(version = hierarchyVersion, relationshipTypeNames = emptySet())
            },
            versionStore = versionStore,
            observedSchemaSource = observedSchemaSource,
            differ = differ,
            metamodelDiffer = differ,
            driftReportStore = reportStore,
        )
    }

    /** The deliberate half: what a host calls once it has read a check's report and decided. */
    private val sweep: DriftSweepCapable by lazy { PropositionStoreDriftSweep(repository) }

    private val policy = MentionTypeDriftQuarantinePolicy()

    @AfterEach
    fun cleanUp() {
        repository.clearAll()
        persistenceManager.execute(QuerySpecification.withStatement("MATCH (n) DETACH DELETE n"))
    }

    @Test
    fun `an undeclared mention type is reported, resolvable, and quarantined by a sweep`() {
        val stranded = repository.save(
            Proposition(
                contextId = contextId,
                text = "The ghost haunts the manor",
                mentions = listOf(EntityMention(span = "the ghost", type = "Ghost", role = MentionRole.SUBJECT)),
                confidence = 0.9,
            ),
        )

        val result = runner.run(contextId)

        // 1. The check saw the undeclared type and nothing else.
        assertEquals(setOf("Ghost"), result.report.driftedEntityTypes)

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

        // 4. The check moved nothing, so a host sweeps the context it decided to reconcile. The
        //    sweep evaluates the same merged comparison the report showed.
        val swept = sweep.sweep(result.quarantineDiff, policy, contextId)
        assertEquals(listOf(stranded.id), swept.quarantined.map { it.proposition.id })

        // 5. The stranded proposition came back out of the graph flagged, carrying a readable
        //    quarantine reason.
        val reloaded = repository.findById(stranded.id)
        assertNotNull(reloaded)
        assertEquals(PropositionStatus.QUARANTINED, reloaded!!.status)
        val reason = reloaded.metadata[DiceMetadataKeys.QUARANTINE_REASON] as? String
        assertNotNull(reason, "quarantine must say why; metadata was ${reloaded.metadata}")
        assertTrue(reason!!.contains("Ghost"), "the reason must name the drifted type, but was: $reason")
    }

    @Test
    fun `a check records its report and touches no proposition`() {
        val untouched = repository.save(
            Proposition(
                contextId = contextId,
                text = "The ghost is still here",
                mentions = listOf(EntityMention(span = "the ghost", type = "Ghost", role = MentionRole.SUBJECT)),
                confidence = 0.9,
            ),
        )

        val result = runner.run(contextId)

        assertEquals(setOf("Ghost"), result.report.driftedEntityTypes)
        assertEquals(1, reportStore.driftReportsInContext(schemaName, contextId, limit = 10).size)
        assertEquals(
            PropositionStatus.ACTIVE,
            repository.findById(untouched.id)!!.status,
            "a check reports what it found and leaves every proposition where it was",
        )
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

        val result = runner.run(contextId)

        assertTrue(result.report.driftedEntityTypes.isEmpty(), "got ${result.report.driftedEntityTypes}")
        assertTrue(
            sweep.sweep(result.quarantineDiff, policy, contextId).quarantined.isEmpty(),
            "a clean context gives a sweep nothing to do",
        )
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

        val first = runner.run(contextId)
        val second = runner.run(contextId)

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

        val result = runner.run(contextId)

        assertTrue(result.report.driftedEntityTypes.isEmpty(), "got ${result.report.driftedEntityTypes}")
        sweep.sweep(result.quarantineDiff, policy, contextId)
        assertEquals(
            PropositionStatus.ACTIVE,
            repository.findByContextId(ContextId("elsewhere")).single().status,
            "a check scoped to one context must not be able to reach another's data",
        )
    }

    @Test
    fun `an unscoped check reports a mention type nothing ever projected, and a sweep quarantines it`() {
        // The whole-graph path reads the database's label catalogue, and a mention type reaches that
        // catalogue only once something projects a node for it. An extraction that recorded `Ghost`
        // and produced no `(:Ghost)` node left the graph looking clean to every unscoped check while
        // a live proposition carried the undeclared type.
        val stranded = repository.save(
            Proposition(
                contextId = contextId,
                text = "The ghost haunts the manor",
                mentions = listOf(EntityMention(span = "the ghost", type = "Ghost", role = MentionRole.SUBJECT)),
                confidence = 0.9,
            ),
        )
        assertEquals(PropositionStatus.ACTIVE, repository.findById(stranded.id)!!.status)
        assertFalse(
            rawLabels().contains("Ghost"),
            "precondition: no typed graph projection for Ghost, but the catalogue held ${rawLabels()}",
        )

        val result = runner.run()

        assertTrue(
            result.report.driftedEntityTypes.contains("Ghost"),
            "the unscoped check missed a type only the propositions know about; got " +
                "${result.report.driftedEntityTypes}",
        )
        assertEquals(
            listOf(result.report),
            reportStore.globalDriftReports(schemaName, limit = 10),
            "an unscoped check records a whole-graph report",
        )

        // The quarantine half is the host's deliberate step, on the context holding the data.
        val swept = sweep.sweep(result.quarantineDiff, policy, contextId)

        assertEquals(listOf(stranded.id), swept.quarantined.map { it.proposition.id })
        assertEquals(PropositionStatus.QUARANTINED, repository.findById(stranded.id)!!.status)
    }

    @Test
    fun `a projected node's parent label is no drift on an unscoped check`() {
        // One half of the pair. The graph reports `Agent` as a label, because every governed
        // `Person` node carries its whole hierarchy, and the declaration says so.
        repository.save(
            Proposition(
                contextId = contextId,
                text = "Ada is a person",
                mentions = listOf(EntityMention(span = "Ada", type = "Person", role = MentionRole.SUBJECT)),
                confidence = 0.9,
            ),
        )
        projectNodeCarryingHierarchy()

        val result = hierarchyRunner.run()

        assertTrue(
            result.report.driftedEntityTypes.isEmpty(),
            "a governed type's own hierarchy label read as drift; got ${result.report.driftedEntityTypes}",
        )
    }

    @Test
    fun `a mention typed as a parent label is drift on an unscoped check`() {
        // The other half, on a graph holding the same node. Only the mention differs: this one claims
        // to BE an `Agent`, a type nothing declared. Reading both kinds of name under the label rule
        // let that claim ride the governed type's hierarchy through an unscoped check.
        val stranded = repository.save(
            Proposition(
                contextId = contextId,
                text = "Ada answers for the estate",
                mentions = listOf(EntityMention(span = "Ada", type = "Agent", role = MentionRole.SUBJECT)),
                confidence = 0.9,
            ),
        )
        projectNodeCarryingHierarchy()

        val result = hierarchyRunner.run()

        assertEquals(setOf("Agent"), result.report.driftedEntityTypes)
        val swept = sweep.sweep(result.quarantineDiff, policy, contextId)
        assertEquals(listOf(stranded.id), swept.quarantined.map { it.proposition.id })
        assertEquals(PropositionStatus.QUARANTINED, repository.findById(stranded.id)!!.status)
    }

    /** A projected entity node carrying a governed `Person`'s whole label hierarchy. */
    private fun projectNodeCarryingHierarchy() {
        persistenceManager.execute(QuerySpecification.withStatement("CREATE (:Person:Agent {id: 'e-ada'})"))
    }

    /** What the database's own catalogue reports, before an observation subtracts anything from it. */
    private fun rawLabels(): Set<String> = persistenceManager
        .query(
            QuerySpecification.withStatement("CALL db.labels() YIELD label RETURN label")
                .transform(String::class.java),
        )
        .filterNotNull()
        .toSet()
}

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

import com.embabel.dice.metamodel.DeclaredSchemaSource
import com.embabel.dice.metamodel.DriftCheckRunner
import com.embabel.dice.metamodel.DriftReportStore
import com.embabel.dice.metamodel.MetamodelVersionStore
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.storage.DrivineDriftReportStore
import com.embabel.dice.storage.DrivineMetamodelVersionStore
import com.embabel.dice.storage.MetamodelSchema
import org.assertj.core.api.Assertions.assertThat
import org.drivine.autoconfigure.EnableDrivine
import org.drivine.autoconfigure.EnableDrivineTestConfig
import org.drivine.manager.PersistenceManager
import org.drivine.manager.PersistenceManagerFactory
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * The auto-configured governance loop against a real Neo4j.
 *
 * The wiring tests cover which beans appear. This one covers whether they work together. An
 * application supplies one `DeclaredSchemaSource` bean and a graph connection, and gets a runner
 * that stamps a version, asks the live database what it holds, and leaves a drift report behind.
 * Every assertion reads that back out of the database.
 *
 * The drift comes from the graph: a `(:Ghost)` node nobody declared. The proposition side stays in
 * memory ([MapPropositionStore]), since this test is about the auto-configured Drivine stores and
 * the runner that sequences them, and `dice-storage`'s own integration tests already cover
 * quarantining through a graph-backed repository. It also exercises the runner's use of the base
 * `PropositionStore` port.
 */
@SpringBootTest(
    classes = [MetamodelIntegrationTestApplication::class],
    properties = ["embabel.dice.metamodel.drift.mode=quarantine"],
)
class MetamodelAutoConfigurationIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }

    @Autowired
    private lateinit var runner: DriftCheckRunner

    @Autowired
    private lateinit var versionStore: MetamodelVersionStore

    @Autowired
    private lateinit var driftReportStore: DriftReportStore

    @Autowired
    private lateinit var propositionStore: MapPropositionStore

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    @BeforeEach
    fun freshGraph() {
        // The Spring context is cached across methods, so both the graph and the in-memory
        // proposition store carry the previous test's writes unless they are reset.
        propositionStore.reset()
        (MetamodelSchema.LABELS + "Ghost").forEach { label ->
            persistenceManager.execute(QuerySpecification.withStatement("MATCH (n:$label) DETACH DELETE n"))
        }
        // A domain node nobody declared. This is the drift the check has to find, and it comes
        // out of the database rather than a canned snapshot.
        persistenceManager.execute(QuerySpecification.withStatement("CREATE (:Ghost {name: 'undeclared'})"))
    }

    @Test
    fun `the auto-configured beans are the Drivine ones`() {
        assertThat(versionStore).isInstanceOf(DrivineMetamodelVersionStore::class.java)
        assertThat(driftReportStore).isInstanceOf(DrivineDriftReportStore::class.java)
        assertThat(runner).isNotInstanceOf(ObserveOnlyDriftCheckRunner::class.java)
    }

    @Test
    fun `a live run stamps the schema, finds the undeclared type, and persists a report`() {
        val result = runner.run(dryRun = false, contextId = null)

        assertThat(result.dryRun).isFalse()
        assertThat(result.driftedEntityTypes).contains("Ghost")

        // The stamp resolves out of the database by the hash the report carries. That is what
        // stamping before reporting buys.
        val stamped = versionStore.findVersion(MetamodelTestFixtures.SCHEMA_NAME, result.report.versionHash)
        assertThat(stamped).isNotNull
        assertThat(stamped!!.entityTypeNames).containsExactly("Person")

        // The report is durable: read back through the store rather than off the result object.
        val reports = driftReportStore.driftReports(MetamodelTestFixtures.SCHEMA_NAME, limit = 10)
        assertThat(reports).hasSize(1)
        assertThat(reports.single().driftedEntityTypes).contains("Ghost")
        assertThat(reports.single().versionHash).isEqualTo(result.report.versionHash)

        // The tier did what it says: the proposition mentioning Ghost is quarantined, the one
        // mentioning Person is left alone.
        assertThat(result.quarantinedCount).isEqualTo(1)
        assertThat(propositionStore.findByStatus(PropositionStatus.STALE)).hasSize(1)
        assertThat(propositionStore.findByStatus(PropositionStatus.ACTIVE)).hasSize(1)
    }

    /**
     * Stamping a version and writing a report both add nodes to the graph the next check observes.
     * If those labels came back as drift, every check would report drift caused by the previous
     * check.
     *
     * `_DrivineSchema` is the same problem from outside dice: Drivine's `SchemaManager` writes that
     * inventory node when it applies a `SchemaCatalog`, and no application can silence it by
     * declaring it. `DrivineObservedSchemaSource` excludes it by shape along with dice's own
     * bookkeeping labels.
     */
    @Test
    fun `governance never reports its own bookkeeping as drift`() {
        runner.run()
        val second = runner.run()

        assertThat(second.driftedEntityTypes).contains("Ghost")
        assertThat(second.driftedEntityTypes).doesNotContainAnyElementsOf(MetamodelSchema.LABELS)
        assertThat(second.driftedEntityTypes).doesNotContain("_DrivineSchema")
    }
}

/**
 * A minimal host: Drivine's test support for the connection and transactions, one
 * `DeclaredSchemaSource` to open the gate, one `PropositionStore`, and nothing else. Everything the
 * governance loop needs comes from [MetamodelAutoConfiguration].
 *
 * `@ImportAutoConfiguration` keeps this a test of one auto-configuration;
 * `@EnableAutoConfiguration` would drag in every one on the classpath. The `.imports` registration
 * itself is checked in `MetamodelAutoConfigurationTest`.
 */
@Configuration(proxyBeanMethods = false)
@EnableDrivine
@EnableDrivineTestConfig
@EnableAspectJAutoProxy(proxyTargetClass = true)
@ImportAutoConfiguration(MetamodelAutoConfiguration::class)
internal open class MetamodelIntegrationTestApplication {

    @Bean
    open fun persistenceManager(factory: PersistenceManagerFactory): PersistenceManager = factory.get("neo")

    @Bean
    open fun declaredSchemaSource(): DeclaredSchemaSource = FixedDeclaredSchemaSource()

    @Bean
    open fun propositionStore(): MapPropositionStore = MapPropositionStore(
        listOf(
            MetamodelTestFixtures.proposition("Ada haunts the archive", mentionType = "Ghost"),
            MetamodelTestFixtures.proposition("Ada wrote the notes", mentionType = "Person"),
        ),
    )
}

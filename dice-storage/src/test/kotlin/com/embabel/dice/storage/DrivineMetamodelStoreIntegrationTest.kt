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
import com.embabel.dice.metamodel.DriftReport
import com.embabel.dice.metamodel.MetamodelVersion
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant

/**
 * Integration tests for [DrivineMetamodelStore] against a Neo4j testcontainer (provided by
 * Drivine's test support). Each test starts from an empty graph via [cleanUp].
 *
 * Runs against [Neo4jTestContainer], not Drivine's own built-in testcontainer -- see that class
 * for why.
 */
@SpringBootTest(classes = [TestApplication::class])
class DrivineMetamodelStoreIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }

    @Autowired
    private lateinit var store: DrivineMetamodelStore

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    @AfterEach
    fun cleanUp() {
        listOf("MetamodelVersion", "MetamodelDriftReport").forEach { label ->
            persistenceManager.execute(QuerySpecification.withStatement("MATCH (n:$label) DETACH DELETE n"))
        }
    }

    // ---- MetamodelVersion ----

    @Test
    fun `metamodel version persists and reads back every field`() {
        val version = MetamodelVersion(
            schemaName = "test-schema",
            contentHash = "abc123def456",
            entityTypeNames = listOf("Person", "Company", "Location"),
            entityTypeLabels = mapOf(
                "Person" to setOf("Agent", "Entity"),
                "Company" to setOf("Organization", "Entity"),
            ),
            entityTypeProperties = mapOf(
                "Person" to setOf("name", "age"),
                "Company" to setOf("name", "founded"),
            ),
            relationshipNames = listOf("WORKS_FOR", "LOCATED_IN"),
        )

        store.saveVersion(version)

        val reloaded = store.latestVersion("test-schema")
        assertEquals(version, reloaded)
    }

    @Test
    fun `latest version returns the most recent saved version`() {
        val v1 = MetamodelVersion(
            schemaName = "schema-a",
            contentHash = "hash1",
            entityTypeNames = listOf("Type1"),
            entityTypeLabels = mapOf("Type1" to setOf("Label1")),
            entityTypeProperties = mapOf("Type1" to setOf("prop1")),
            relationshipNames = emptyList(),
        )
        val v2 = MetamodelVersion(
            schemaName = "schema-a",
            contentHash = "hash2",
            entityTypeNames = listOf("Type2"),
            entityTypeLabels = mapOf("Type2" to setOf("Label2")),
            entityTypeProperties = mapOf("Type2" to setOf("prop2")),
            relationshipNames = emptyList(),
        )

        store.saveVersion(v1)
        Thread.sleep(10) // Ensure time passes for ordering
        store.saveVersion(v2)

        val latest = store.latestVersion("schema-a")
        assertEquals(v2, latest)
    }

    @Test
    fun `version history returns all versions for a schema in newest-first order`() {
        val v1 = MetamodelVersion(
            schemaName = "schema-b",
            contentHash = "hash1",
            entityTypeNames = listOf("Type1"),
            entityTypeLabels = emptyMap(),
            entityTypeProperties = emptyMap(),
            relationshipNames = emptyList(),
        )
        val v2 = MetamodelVersion(
            schemaName = "schema-b",
            contentHash = "hash2",
            entityTypeNames = listOf("Type2"),
            entityTypeLabels = emptyMap(),
            entityTypeProperties = emptyMap(),
            relationshipNames = emptyList(),
        )

        store.saveVersion(v1)
        Thread.sleep(10)
        store.saveVersion(v2)

        val history = store.versionHistory("schema-b")
        assertEquals(2, history.size)
        // Newest first
        assertEquals(v2, history[0])
        assertEquals(v1, history[1])
    }

    @Test
    fun `version history returns empty for unknown schema`() {
        val history = store.versionHistory("unknown-schema")
        assertEquals(emptyList<MetamodelVersion>(), history)
    }

    @Test
    fun `saving the same version idempotently updates in place`() {
        val version = MetamodelVersion(
            schemaName = "idempotent-schema",
            contentHash = "hash123",
            entityTypeNames = listOf("TypeA"),
            entityTypeLabels = mapOf("TypeA" to setOf("LabelA")),
            entityTypeProperties = mapOf("TypeA" to setOf("prop")),
            relationshipNames = listOf("REL"),
        )

        store.saveVersion(version)
        store.saveVersion(version)

        val history = store.versionHistory("idempotent-schema")
        // Should have one version, not two
        assertEquals(1, history.size)
        assertEquals(version, history[0])
    }

    // ---- DriftReport ----

    @Test
    fun `drift report persists and reads back every field`() {
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val report = DriftReport(
            schemaName = "test-schema",
            versionHash = "vhash123",
            driftingEntityTypes = setOf("UnknownPerson", "UnknownOrg"),
            driftingRelationshipTypes = setOf("UNDECLARED_LINK"),
            capturedAt = now,
        )

        store.saveDriftReport(report)

        val reloaded = store.driftReports("test-schema").single()
        assertEquals(report, reloaded)
    }

    @Test
    fun `drift reports for a schema return newest-first`() {
        val t1 = Instant.parse("2026-01-01T00:00:00Z")
        val t2 = Instant.parse("2026-01-02T00:00:00Z")

        val r1 = DriftReport(
            schemaName = "schema-c",
            versionHash = "v1",
            driftingEntityTypes = setOf("Type1"),
            driftingRelationshipTypes = emptySet(),
            capturedAt = t1,
        )
        val r2 = DriftReport(
            schemaName = "schema-c",
            versionHash = "v1",
            driftingEntityTypes = setOf("Type2"),
            driftingRelationshipTypes = emptySet(),
            capturedAt = t2,
        )

        store.saveDriftReport(r1)
        store.saveDriftReport(r2)

        val reports = store.driftReports("schema-c")
        assertEquals(2, reports.size)
        // Newest first
        assertEquals(r2, reports[0])
        assertEquals(r1, reports[1])
    }

    @Test
    fun `drift reports returns empty for unknown schema`() {
        val reports = store.driftReports("unknown-schema")
        assertEquals(emptyList<DriftReport>(), reports)
    }

    @Test
    fun `drift report with empty type sets round-trips correctly`() {
        val report = DriftReport(
            schemaName = "clean-schema",
            versionHash = "clean-hash",
            driftingEntityTypes = emptySet(),
            driftingRelationshipTypes = emptySet(),
            capturedAt = Instant.now(),
        )

        store.saveDriftReport(report)

        val reloaded = store.driftReports("clean-schema").single()
        assertEquals(0, reloaded.driftingEntityTypes.size)
        assertEquals(0, reloaded.driftingRelationshipTypes.size)
    }

    @Test
    fun `multiple schemas are isolated - latest version and drift reports query by schema`() {
        val schemaA = MetamodelVersion("schema-a", "hashA", listOf("TypeA"), emptyMap(), emptyMap(), emptyList())
        val schemaB = MetamodelVersion("schema-b", "hashB", listOf("TypeB"), emptyMap(), emptyMap(), emptyList())

        store.saveVersion(schemaA)
        store.saveVersion(schemaB)

        val reportA = DriftReport("schema-a", "hashA", setOf("DriftA"), emptySet(), Instant.now())
        val reportB = DriftReport("schema-b", "hashB", setOf("DriftB"), emptySet(), Instant.now())

        store.saveDriftReport(reportA)
        store.saveDriftReport(reportB)

        // Each schema returns only its own data
        assertEquals(schemaA, store.latestVersion("schema-a"))
        assertEquals(schemaB, store.latestVersion("schema-b"))
        assertEquals(setOf("DriftA"), store.driftReports("schema-a").single().driftingEntityTypes)
        assertEquals(setOf("DriftB"), store.driftReports("schema-b").single().driftingEntityTypes)
    }

    // ---- Adversarial serialization tests: names with delimiter characters ----

    @Test
    fun `version with entity type names containing pipe survives round-trip intact`() {
        val version = MetamodelVersion(
            schemaName = "pipe-test",
            contentHash = "hash-pipe",
            entityTypeNames = listOf("Type|WithPipe", "Normal", "A|B|C"),
            entityTypeLabels = mapOf(
                "Type|WithPipe" to setOf("Label1", "Label|2"),
                "Normal" to setOf("LabelA"),
            ),
            entityTypeProperties = mapOf(
                "Type|WithPipe" to setOf("prop|1", "prop2"),
            ),
            relationshipNames = listOf("REL|WITH|PIPES"),
        )

        store.saveVersion(version)

        val reloaded = store.latestVersion("pipe-test")
        assertEquals(version, reloaded)
    }

    @Test
    fun `version with entity type names containing tab survives round-trip intact`() {
        val version = MetamodelVersion(
            schemaName = "tab-test",
            contentHash = "hash-tab",
            entityTypeNames = listOf("Type\tWithTab", "Normal"),
            entityTypeLabels = mapOf(
                "Type\tWithTab" to setOf("Label\t1", "Label2"),
                "Normal" to setOf("LabelA"),
            ),
            entityTypeProperties = mapOf(
                "Type\tWithTab" to setOf("prop\t1", "prop2"),
            ),
            relationshipNames = listOf("REL\tWITH\tTAB"),
        )

        store.saveVersion(version)

        val reloaded = store.latestVersion("tab-test")
        assertEquals(version, reloaded)
    }

    @Test
    fun `version with entity type names containing newline survives round-trip intact`() {
        val version = MetamodelVersion(
            schemaName = "newline-test",
            contentHash = "hash-newline",
            entityTypeNames = listOf("Type\nWithNewline", "Normal"),
            entityTypeLabels = mapOf(
                "Type\nWithNewline" to setOf("Label\n1", "Label2"),
                "Normal" to setOf("LabelA"),
            ),
            entityTypeProperties = mapOf(
                "Type\nWithNewline" to setOf("prop\n1", "prop2"),
            ),
            relationshipNames = listOf("REL\nWITH\nNEWLINE"),
        )

        store.saveVersion(version)

        val reloaded = store.latestVersion("newline-test")
        assertEquals(version, reloaded)
    }

    @Test
    fun `version with entity type names containing double quotes survives round-trip intact`() {
        val version = MetamodelVersion(
            schemaName = "quote-test",
            contentHash = "hash-quote",
            entityTypeNames = listOf("Type\"WithQuote", "Normal"),
            entityTypeLabels = mapOf(
                "Type\"WithQuote" to setOf("Label\"1", "Label2"),
                "Normal" to setOf("LabelA"),
            ),
            entityTypeProperties = mapOf(
                "Type\"WithQuote" to setOf("prop\"1", "prop2"),
            ),
            relationshipNames = listOf("REL\"WITH\"QUOTE"),
        )

        store.saveVersion(version)

        val reloaded = store.latestVersion("quote-test")
        assertEquals(version, reloaded)
    }

    @Test
    fun `drift report with type names containing delimiters survives round-trip intact`() {
        val report = DriftReport(
            schemaName = "drift-delim-test",
            versionHash = "v-delim",
            driftingEntityTypes = setOf("Type|Pipe", "Type\tTab", "Type\nNewline", "Type\"Quote"),
            driftingRelationshipTypes = setOf("REL|PIPE", "REL\tTAB", "REL\nNEWLINE"),
            capturedAt = Instant.parse("2026-01-01T12:00:00Z"),
        )

        store.saveDriftReport(report)

        val reloaded = store.driftReports("drift-delim-test").single()
        assertEquals(report, reloaded)
    }

    // ---- Context-scoped drift reports ----

    @Test
    fun `a report with no contextId round-trips as null, not an empty string or sentinel`() {
        val report = DriftReport(
            schemaName = "context-null-test",
            versionHash = "v1",
            driftingEntityTypes = setOf("GhostType"),
            driftingRelationshipTypes = emptySet(),
            capturedAt = Instant.parse("2026-01-01T00:00:00Z"),
            contextId = null,
        )

        store.saveDriftReport(report)

        val reloaded = store.driftReports("context-null-test").single()
        assertEquals(report, reloaded)
        assertNull(reloaded.contextId)
    }

    @Test
    fun `a report's contextId round-trips intact, including delimiter characters`() {
        val delimiterLadenContextId = ContextId("tenant|with\ttab\nand\"quote")
        val report = DriftReport(
            schemaName = "context-delim-test",
            versionHash = "v1",
            driftingEntityTypes = setOf("GhostType"),
            driftingRelationshipTypes = emptySet(),
            capturedAt = Instant.parse("2026-01-01T00:00:00Z"),
            contextId = delimiterLadenContextId,
        )

        store.saveDriftReport(report)

        val reloaded = store.driftReports("context-delim-test").single()
        assertEquals(report, reloaded)
        assertEquals(delimiterLadenContextId, reloaded.contextId)
    }

    @Test
    fun `scoped driftReports overload returns only reports for that context, filtered in the database`() {
        val schemaName = "context-scoped-test"
        val tenantA = DriftReport(
            schemaName = schemaName,
            versionHash = "v1",
            driftingEntityTypes = setOf("TenantAType"),
            driftingRelationshipTypes = emptySet(),
            capturedAt = Instant.parse("2026-01-01T00:00:00Z"),
            contextId = ContextId("tenant-a"),
        )
        val tenantB = DriftReport(
            schemaName = schemaName,
            versionHash = "v1",
            driftingEntityTypes = setOf("TenantBType"),
            driftingRelationshipTypes = emptySet(),
            capturedAt = Instant.parse("2026-01-01T00:00:01Z"),
            contextId = ContextId("tenant-b"),
        )
        val global = DriftReport(
            schemaName = schemaName,
            versionHash = "v1",
            driftingEntityTypes = setOf("GlobalType"),
            driftingRelationshipTypes = emptySet(),
            capturedAt = Instant.parse("2026-01-01T00:00:02Z"),
            contextId = null,
        )

        store.saveDriftReport(tenantA)
        store.saveDriftReport(tenantB)
        store.saveDriftReport(global)

        assertEquals(listOf(tenantA), store.driftReports(schemaName, ContextId("tenant-a")))
        assertEquals(listOf(tenantB), store.driftReports(schemaName, ContextId("tenant-b")))
        assertEquals(listOf(global), store.driftReports(schemaName, null))
        // The unscoped overload still returns everything, newest first.
        assertEquals(3, store.driftReports(schemaName).size)
    }

    @Test
    fun `a null-context and a tenant-context report at the identical instant coexist as two nodes, not one overwriting the other`() {
        // Same schemaName + versionHash + capturedAt -- the exact collision the natural key must
        // not conflate. Before contextKey joined the MERGE key, the second save here would have
        // silently overwritten the first (same match, only the SET differed).
        val schemaName = "same-instant-collision-test"
        val sameInstant = Instant.parse("2026-01-01T00:00:00Z")
        val globalReport = DriftReport(
            schemaName = schemaName,
            versionHash = "v1",
            driftingEntityTypes = setOf("GlobalGhost"),
            driftingRelationshipTypes = emptySet(),
            capturedAt = sameInstant,
            contextId = null,
        )
        val tenantAReport = DriftReport(
            schemaName = schemaName,
            versionHash = "v1",
            driftingEntityTypes = setOf("TenantAGhost"),
            driftingRelationshipTypes = emptySet(),
            capturedAt = sameInstant,
            contextId = ContextId("tenant-a"),
        )

        store.saveDriftReport(globalReport)
        store.saveDriftReport(tenantAReport)

        val all = store.driftReports(schemaName)
        assertEquals(2, all.size, "both reports must survive as distinct nodes")
        assertEquals(setOf(globalReport, tenantAReport), all.toSet())

        assertEquals(listOf(tenantAReport), store.driftReports(schemaName, ContextId("tenant-a")))
        assertEquals(listOf(globalReport), store.driftReports(schemaName, null))
    }

    @Test
    fun `re-saving an identical global drift report MERGEs in place, not a duplicate`() {
        val schemaName = "drift-idempotent-global-test"
        val report = DriftReport(
            schemaName = schemaName,
            versionHash = "v1",
            driftingEntityTypes = setOf("GhostType"),
            driftingRelationshipTypes = emptySet(),
            capturedAt = Instant.parse("2026-01-01T00:00:00Z"),
            contextId = null,
        )

        store.saveDriftReport(report)
        store.saveDriftReport(report)

        val reports = store.driftReports(schemaName)
        assertEquals(1, reports.size, "identical schema+version+instant+context must MERGE, not duplicate")
        assertEquals(report, reports.single())
    }

    @Test
    fun `re-saving an identical context-scoped drift report MERGEs in place, not a duplicate`() {
        val schemaName = "drift-idempotent-scoped-test"
        val report = DriftReport(
            schemaName = schemaName,
            versionHash = "v1",
            driftingEntityTypes = setOf("GhostType"),
            driftingRelationshipTypes = emptySet(),
            capturedAt = Instant.parse("2026-01-01T00:00:00Z"),
            contextId = ContextId("tenant-a"),
        )

        store.saveDriftReport(report)
        store.saveDriftReport(report)

        val reports = store.driftReports(schemaName)
        assertEquals(1, reports.size, "identical schema+version+instant+context must MERGE, not duplicate")
        assertEquals(report, reports.single())
        assertEquals(listOf(report), store.driftReports(schemaName, ContextId("tenant-a")))
    }

    // ---- F3: savedAt reset behavior on re-save ----

    @Test
    fun `re-saving an old version preserves original savedAt and history order`() {
        val v1 = MetamodelVersion(
            schemaName = "history-test",
            contentHash = "hash1",
            entityTypeNames = listOf("Type1"),
            entityTypeLabels = emptyMap(),
            entityTypeProperties = emptyMap(),
            relationshipNames = emptyList(),
        )
        val v2 = MetamodelVersion(
            schemaName = "history-test",
            contentHash = "hash2",
            entityTypeNames = listOf("Type2"),
            entityTypeLabels = emptyMap(),
            entityTypeProperties = emptyMap(),
            relationshipNames = emptyList(),
        )

        store.saveVersion(v1)
        Thread.sleep(10)
        store.saveVersion(v2)

        // Verify v2 is latest before re-saving v1
        assertEquals(v2, store.latestVersion("history-test"))

        Thread.sleep(10)
        // Re-save v1 (idempotent MERGE)
        store.saveVersion(v1)

        // latestVersion should still be v2 — re-saving v1 must not change the history order
        val latest = store.latestVersion("history-test")
        assertEquals(v2, latest)

        // Verify history order is still [v2, v1]
        val history = store.versionHistory("history-test")
        assertEquals(2, history.size)
        assertEquals(v2, history[0], "Most recent should still be v2")
        assertEquals(v1, history[1], "Second should still be v1")
    }
}

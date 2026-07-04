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
import java.time.Instant

/**
 * Integration tests for [DrivineMetamodelStore] against a Neo4j testcontainer (provided by
 * Drivine's test support). Each test starts from an empty graph via [cleanUp].
 */
@SpringBootTest(classes = [TestApplication::class])
class DrivineMetamodelStoreIntegrationTest {

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
}

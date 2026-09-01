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
import com.embabel.dice.metamodel.support.StructuralMetamodelDiffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class DriftReportTest {

    private val capturedAt = Instant.parse("2026-01-01T00:00:00Z")

    private fun report(
        schemaName: String = "test-schema",
        versionHash: String = "abc123",
        driftedEntityTypes: Set<String> = emptySet(),
        driftedRelationshipTypes: Set<String> = emptySet(),
        capturedAt: Instant = this.capturedAt,
        contextId: ContextId? = null,
        declaredDiff: MetamodelDiff? = null,
    ) = DriftReport(
        schemaName = schemaName,
        versionHash = versionHash,
        driftedEntityTypes = driftedEntityTypes,
        driftedRelationshipTypes = driftedRelationshipTypes,
        capturedAt = capturedAt,
        contextId = contextId,
        declaredDiff = declaredDiff,
    )

    @Test
    fun `a report preserves everything it was given`() {
        val entities = setOf("UnknownPerson", "UnknownOrg")
        val relationships = setOf("UNDECLARED_LINKS")
        val context = ContextId("ctx-1")

        val report = report(
            driftedEntityTypes = entities,
            driftedRelationshipTypes = relationships,
            contextId = context,
        )

        assertEquals("test-schema", report.schemaName)
        assertEquals("abc123", report.versionHash)
        assertEquals(entities, report.driftedEntityTypes)
        assertEquals(relationships, report.driftedRelationshipTypes)
        assertEquals(capturedAt, report.capturedAt)
        assertEquals(context, report.contextId)
    }

    @Test
    fun `a clean report is a perfectly ordinary report`() {
        val clean = report()

        assertTrue(clean.driftedEntityTypes.isEmpty())
        assertTrue(clean.driftedRelationshipTypes.isEmpty())
        assertFalse(clean.hasDrift)
        assertNull(clean.contextId, "no context means the check covered the whole graph")
    }

    @Test
    fun `hasDrift fires on either kind of drift`() {
        assertTrue(report(driftedEntityTypes = setOf("Ghost")).hasDrift)
        assertTrue(report(driftedRelationshipTypes = setOf("HAUNTS")).hasDrift)
    }

    @Test
    fun `the drifted type sets cannot be changed after the fact`() {
        // A record of a moment has to stay that record. The caller's set is copied in, and what
        // comes back out throws on mutation rather than relying on Kotlin's read-only view.
        val mutable = mutableSetOf("Ghost")
        val report = report(driftedEntityTypes = mutable)

        mutable += "AddedLater"

        assertEquals(setOf("Ghost"), report.driftedEntityTypes, "the caller's later change must not leak in")
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (report.driftedEntityTypes as MutableSet<String>).add("AddedLater")
        }
    }

    @Test
    fun `two reports of the same observation are equal, and a different scope is not`() {
        assertEquals(report(driftedEntityTypes = setOf("Ghost")), report(driftedEntityTypes = setOf("Ghost")))
        assertEquals(
            report(driftedEntityTypes = setOf("Ghost")).hashCode(),
            report(driftedEntityTypes = setOf("Ghost")).hashCode(),
        )
        assertNotEquals(
            report(contextId = ContextId("ctx-1")),
            report(contextId = ContextId("ctx-2")),
        )
        assertNotEquals(report(contextId = ContextId("ctx-1")), report())
    }

    private fun version(
        types: List<String>,
        properties: Map<String, Set<PropertySignature>> = emptyMap(),
    ): MetamodelVersion = MetamodelVersion(
        schemaName = "test-schema",
        entityTypeNames = types,
        entityTypeLabels = types.associateWith { setOf(it) },
        entityTypeProperties = types.associateWith { properties[it].orEmpty() },
        relationshipNames = emptyList(),
    )

    /** `Person` loses its `age` property between two stamps. */
    private fun personLostAge(): MetamodelDiff = StructuralMetamodelDiffer().diff(
        version(
            listOf("Person"),
            mapOf("Person" to setOf(PropertySignature("age", PropertySignature.Kind.VALUE, "string", Cardinality.ONE))),
        ),
        version(listOf("Person")),
    )

    @Test
    fun `a report carries the declared comparison it was given`() {
        val declaredDiff = personLostAge()

        val report = report(declaredDiff = declaredDiff)

        assertEquals(declaredDiff, report.declaredDiff)
        assertFalse(report.hasDrift, "the graph-truth half is clean")
        assertTrue(report.hasAnyChange, "and the report still says something happened")
    }

    @Test
    fun `a report with neither kind of change reports nothing happened`() {
        val clean = report()

        assertNull(clean.declaredDiff)
        assertFalse(clean.hasAnyChange)
    }

    @Test
    fun `an empty declared comparison is not a change`() {
        val unchanged = version(listOf("Person"))
        val empty = StructuralMetamodelDiffer().diff(unchanged, unchanged)

        val report = report(declaredDiff = empty)

        assertFalse(report.hasAnyChange, "a declaration that hasn't moved strands nothing")
    }

    @Test
    fun `two reports differing only in the declared comparison are different reports`() {
        assertNotEquals(report(declaredDiff = personLostAge()), report())
        assertNotEquals(
            report(declaredDiff = personLostAge()).hashCode(),
            report().hashCode(),
        )
    }

    @Test
    fun `the quarantine comparison merges observed drift into the declared changes`() {
        val declaredVersion = version(listOf("Person"))

        val merged = report(
            versionHash = declaredVersion.contentHash,
            driftedEntityTypes = setOf("GhostType"),
            declaredDiff = personLostAge(),
        ).quarantineDiff(declaredVersion)

        assertEquals(setOf("GhostType"), merged.removedEntityTypes, "observed drift becomes a removal")
        assertEquals(
            listOf("age"),
            merged.modifiedEntityTypes.single().removedPropertyNames.toList(),
            "and the declared property removal rides along",
        )
        assertEquals(
            personLostAge().fromVersion,
            merged.fromVersion,
            "the declared comparison's own from-side carries through, so former names still resolve",
        )
    }

    @Test
    fun `a removal reported by both halves appears once`() {
        val declaredVersion = version(emptyList())
        val removal = StructuralMetamodelDiffer().diff(version(listOf("Ghost")), declaredVersion)

        val merged = report(
            versionHash = declaredVersion.contentHash,
            driftedEntityTypes = setOf("Ghost"),
            declaredDiff = removal,
        ).quarantineDiff(declaredVersion)

        assertEquals(
            listOf(MetamodelChange.EntityTypeRemoved("Ghost")),
            merged.changes,
            "the same type removed on both sides is one removal: ${merged.changes}",
        )
    }

    @Test
    fun `a report with no declared comparison still yields a usable quarantine comparison`() {
        val declaredVersion = version(listOf("Person"))

        val merged = report(
            versionHash = declaredVersion.contentHash,
            driftedEntityTypes = setOf("GhostType"),
        ).quarantineDiff(declaredVersion)

        assertEquals(setOf("GhostType"), merged.removedEntityTypes)
        assertEquals(declaredVersion, merged.fromVersion)
        assertEquals(declaredVersion, merged.toVersion)
    }
}

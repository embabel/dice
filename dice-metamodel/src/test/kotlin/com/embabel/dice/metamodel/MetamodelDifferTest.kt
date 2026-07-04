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

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.core.DynamicType
import com.embabel.agent.core.ValuePropertyDefinition
import com.embabel.dice.metamodel.support.StructuralMetamodelDiffer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

class MetamodelDifferTest {

    private lateinit var differ: MetamodelDiffer
    private lateinit var declaredObservedDiffer: DeclaredObservedDiffer

    @BeforeEach
    fun setUp() {
        val structural = StructuralMetamodelDiffer()
        differ = structural
        declaredObservedDiffer = structural
    }

    private fun schemaWith(name: String = "test", vararg typeNames: String): DataDictionary =
        DataDictionary.fromDomainTypes(name, typeNames.map { DynamicType(name = it) })

    @Nested
    inner class NoChanges {

        @Test
        fun `identical schemas produce an empty diff`() {
            val old = schemaWith(typeNames = arrayOf("Person", "Company"))
            val new = schemaWith(typeNames = arrayOf("Person", "Company"))
            val diff = differ.diff(old, new)
            assertTrue(diff.isEmpty)
            assertTrue(diff.changes.isEmpty())
        }

        @Test
        fun `empty schemas produce an empty diff`() {
            val diff = differ.diff(schemaWith(), schemaWith())
            assertTrue(diff.isEmpty)
        }
    }

    @Nested
    inner class AddedTypes {

        @Test
        fun `added entity type is reported`() {
            val old = schemaWith(typeNames = arrayOf("Person"))
            val new = schemaWith(typeNames = arrayOf("Person", "Company"))
            val diff = differ.diff(old, new)
            assertFalse(diff.isEmpty)
            val added = diff.addedEntityTypes
            assertTrue(added.contains("Company"), "Expected Company in added: $added")
            assertFalse(diff.removedEntityTypes.contains("Company"))
        }

        @Test
        fun `multiple added types are all reported`() {
            val old = schemaWith(typeNames = arrayOf("Person"))
            val new = schemaWith(typeNames = arrayOf("Person", "Company", "Technology"))
            val diff = differ.diff(old, new)
            assertEquals(setOf("Company", "Technology"), diff.addedEntityTypes)
        }
    }

    @Nested
    inner class RemovedTypes {

        @Test
        fun `removed entity type is reported`() {
            val old = schemaWith(typeNames = arrayOf("Person", "LegacyType"))
            val new = schemaWith(typeNames = arrayOf("Person"))
            val diff = differ.diff(old, new)
            assertFalse(diff.isEmpty)
            assertTrue(diff.removedEntityTypes.contains("LegacyType"),
                "Expected LegacyType in removed: ${diff.removedEntityTypes}")
        }

        @Test
        fun `multiple removed types are all reported`() {
            val old = schemaWith(typeNames = arrayOf("Person", "Foo", "Bar"))
            val new = schemaWith(typeNames = arrayOf("Person"))
            val diff = differ.diff(old, new)
            assertEquals(setOf("Foo", "Bar"), diff.removedEntityTypes)
        }

        @Test
        fun `removed type does not appear in added set`() {
            val old = schemaWith(typeNames = arrayOf("Person", "OldType"))
            val new = schemaWith(typeNames = arrayOf("Person"))
            val diff = differ.diff(old, new)
            assertFalse(diff.addedEntityTypes.contains("OldType"))
        }
    }

    @Nested
    inner class MixedChanges {

        @Test
        fun `simultaneous add and remove are both captured`() {
            val old = schemaWith(typeNames = arrayOf("Person", "LegacyType"))
            val new = schemaWith(typeNames = arrayOf("Person", "NewType"))
            val diff = differ.diff(old, new)
            assertTrue(diff.removedEntityTypes.contains("LegacyType"))
            assertTrue(diff.addedEntityTypes.contains("NewType"))
        }
    }

    @Nested
    inner class ModifiedTypes {

        /** A schema with a single `Person` type whose labels include the given parent's label. */
        private fun personWithParent(parent: String): DataDictionary =
            DataDictionary.fromDomainTypes(
                "test",
                listOf(DynamicType(name = "Person", parents = listOf(DynamicType(name = parent)))),
            )

        @Test
        fun `a label change on a same-named type is reported as modified, not add or remove`() {
            val old = personWithParent("Agent")   // labels: {Person, Agent}
            val new = personWithParent("Actor")   // labels: {Person, Actor}
            val diff = differ.diff(old, new)

            assertFalse(diff.isEmpty)
            assertTrue(diff.addedEntityTypes.isEmpty(), "no type was added: ${diff.addedEntityTypes}")
            assertTrue(diff.removedEntityTypes.isEmpty(), "no type was removed: ${diff.removedEntityTypes}")

            val modified = diff.modifiedEntityTypes
            assertEquals(1, modified.size, "expected exactly one modified type: $modified")
            val change = modified.single()
            assertEquals("Person", change.typeName)
            assertEquals(setOf("Actor"), change.addedLabels)
            assertEquals(setOf("Agent"), change.removedLabels)
        }

        @Test
        fun `unchanged labels yield no modified entry`() {
            val diff = differ.diff(personWithParent("Agent"), personWithParent("Agent"))
            assertTrue(diff.isEmpty)
            assertTrue(diff.modifiedEntityTypes.isEmpty())
        }

        /** A schema with a single `Person` type carrying the given property names. */
        private fun personWithProperties(vararg props: String): DataDictionary =
            DataDictionary.fromDomainTypes(
                "test",
                listOf(DynamicType(name = "Person", ownProperties = props.map { ValuePropertyDefinition(it) })),
            )

        @Test
        fun `a property change on a same-named type is reported as modified`() {
            val old = personWithProperties("age")
            val new = personWithProperties("age", "email")
            val diff = differ.diff(old, new)

            assertFalse(diff.isEmpty)
            assertTrue(diff.addedEntityTypes.isEmpty())
            assertTrue(diff.removedEntityTypes.isEmpty())

            val change = diff.modifiedEntityTypes.single()
            assertEquals("Person", change.typeName)
            assertEquals(setOf("email"), change.addedProperties)
            assertTrue(change.removedProperties.isEmpty())
            // labels are unchanged, so their deltas stay empty
            assertTrue(change.addedLabels.isEmpty())
            assertTrue(change.removedLabels.isEmpty())
        }

        @Test
        fun `simultaneous label and property change are captured in one modified entry`() {
            val old = DataDictionary.fromDomainTypes(
                "test",
                listOf(
                    DynamicType(
                        name = "Person",
                        ownProperties = listOf(ValuePropertyDefinition("age")),
                        parents = listOf(DynamicType(name = "Agent")),
                    ),
                ),
            )
            val new = DataDictionary.fromDomainTypes(
                "test",
                listOf(
                    DynamicType(
                        name = "Person",
                        ownProperties = listOf(ValuePropertyDefinition("email")),
                        parents = listOf(DynamicType(name = "Actor")),
                    ),
                ),
            )
            val change = differ.diff(old, new).modifiedEntityTypes.single()
            assertEquals("Person", change.typeName)
            assertEquals(setOf("Actor"), change.addedLabels)
            assertEquals(setOf("Agent"), change.removedLabels)
            assertEquals(setOf("email"), change.addedProperties)
            assertEquals(setOf("age"), change.removedProperties)
        }
    }

    @Nested
    inner class VersionOverload {

        @Test
        fun `MetamodelVersion overload produces same result as DataDictionary overload`() {
            val old = schemaWith(typeNames = arrayOf("Person", "Removed"))
            val new = schemaWith(typeNames = arrayOf("Person", "Added"))
            val fromVersions = differ.diff(MetamodelVersion.from(old), MetamodelVersion.from(new))
            val fromDicts = differ.diff(old, new)
            assertEquals(fromVersions.removedEntityTypes, fromDicts.removedEntityTypes)
            assertEquals(fromVersions.addedEntityTypes, fromDicts.addedEntityTypes)
        }
    }

    @Nested
    inner class DelimiterSafetyInNames {

        /**
         * A label name that contains a comma — possible when names come from LLM extraction.
         * The diff compares label sets directly, so punctuation in a name is just a character;
         * the change is still detected and the label comes back as one entry, not split.
         */
        @Test
        fun `label containing a comma is treated as a single label`() {
            // A parent whose name happens to contain a comma (e.g. from free-text extraction).
            val commaParent = DynamicType(name = "foo,bar")
            val old = DataDictionary.fromDomainTypes(
                "test",
                listOf(DynamicType(name = "Person", parents = listOf(commaParent))),
            )
            val new = DataDictionary.fromDomainTypes(
                "test",
                listOf(DynamicType(name = "Person")),
            )
            val diff = differ.diff(old, new)

            // The comma-bearing label was removed; it must come back as one entry, not split in two.
            val change = diff.modifiedEntityTypes.single()
            assertEquals(1, change.removedLabels.size)
            assertTrue(change.removedLabels.single().contains(","))
            assertTrue(change.addedLabels.isEmpty())
        }

        /**
         * Property names can contain spaces when they come from free-text / LLM extraction.
         * The two sets `{"a", "b c"}` and `{"a b", "c"}` are genuinely different, yet both
         * collapse to the string "a b c" if you compare a space-joined projection instead of
         * the sets themselves. A real property change must never be hidden by that collision.
         */
        @Test
        fun `property sets that collide under a space delimiter are still reported as modified`() {
            val old = DataDictionary.fromDomainTypes(
                "test",
                listOf(
                    DynamicType(
                        name = "Person",
                        ownProperties = listOf(ValuePropertyDefinition("a"), ValuePropertyDefinition("b c")),
                    ),
                ),
            )
            val new = DataDictionary.fromDomainTypes(
                "test",
                listOf(
                    DynamicType(
                        name = "Person",
                        ownProperties = listOf(ValuePropertyDefinition("a b"), ValuePropertyDefinition("c")),
                    ),
                ),
            )
            val diff = differ.diff(old, new)

            assertFalse(diff.isEmpty, "a real property change must not be hidden by a delimiter collision")
            val change = diff.modifiedEntityTypes.single()
            assertEquals("Person", change.typeName)
            assertEquals(setOf("a b", "c"), change.addedProperties)
            assertEquals(setOf("a", "b c"), change.removedProperties)
        }
    }

    @Nested
    inner class DeclaredVsObserved {

        private fun declared(vararg typeNames: String, relationshipNames: List<String> = emptyList()): MetamodelVersion =
            MetamodelVersion(
                schemaName = "test",
                contentHash = "irrelevant",
                entityTypeNames = typeNames.toList(),
                entityTypeLabels = typeNames.associateWith { setOf(it) },
                entityTypeProperties = typeNames.associateWith { emptySet() },
                relationshipNames = relationshipNames,
            )

        private fun observed(
            entityTypeNames: Set<String>,
            relationshipTypeNames: Set<String> = emptySet(),
            entityTypeCounts: Map<String, Long> = emptyMap(),
        ): ObservedSchema =
            ObservedSchema(
                entityTypeNames = entityTypeNames,
                relationshipTypeNames = relationshipTypeNames,
                entityTypeCounts = entityTypeCounts,
                capturedAt = Instant.parse("2026-01-01T00:00:00Z"),
            )

        @Test
        fun `an observed type with no declaration is reported as drift`() {
            val diff = declaredObservedDiffer.diffAgainstObserved(
                declared("Person"),
                observed(entityTypeNames = setOf("Person", "GhostIntegrationType")),
            )
            assertTrue(diff.hasDrift)
            assertEquals(setOf("GhostIntegrationType"), diff.driftedEntityTypes)
        }

        @Test
        fun `a declared type with zero observed instances is not drift`() {
            val diff = declaredObservedDiffer.diffAgainstObserved(
                declared("Person", "NeverSeenYet"),
                observed(entityTypeNames = setOf("Person")),
            )
            assertFalse(diff.hasDrift, "absence of a declared type must not count as drift")
            assertTrue(diff.driftedEntityTypes.isEmpty())
            assertEquals(setOf("NeverSeenYet"), diff.unobservedEntityTypes)
        }

        @Test
        fun `matching declared and observed types produce no drift and nothing unobserved`() {
            val diff = declaredObservedDiffer.diffAgainstObserved(
                declared("Person", "Company"),
                observed(entityTypeNames = setOf("Person", "Company")),
            )
            assertFalse(diff.hasDrift)
            assertTrue(diff.driftedEntityTypes.isEmpty())
            assertTrue(diff.unobservedEntityTypes.isEmpty())
        }

        @Test
        fun `drift and unobserved can both be present at once and don't overlap`() {
            val diff = declaredObservedDiffer.diffAgainstObserved(
                declared("Person", "NeverSeenYet"),
                observed(entityTypeNames = setOf("Person", "GhostIntegrationType")),
            )
            assertEquals(setOf("GhostIntegrationType"), diff.driftedEntityTypes)
            assertEquals(setOf("NeverSeenYet"), diff.unobservedEntityTypes)
        }

        @Test
        fun `relationship drift is compared on the bare relationship type name, not the full descriptor`() {
            // Declared descriptors carry from/to node types; an observed graph only reports
            // bare relationship type names (e.g. via a `db.relationshipTypes()`-style query).
            val diff = declaredObservedDiffer.diffAgainstObserved(
                declared("Person", "Company", relationshipNames = listOf("Person-[WORKS_AT]->Company")),
                observed(
                    entityTypeNames = setOf("Person", "Company"),
                    relationshipTypeNames = setOf("WORKS_AT"),
                ),
            )
            assertFalse(diff.hasDrift, "a declared relationship must not be reported as drift just because " +
                "its bare name matches")
            assertTrue(diff.driftedRelationshipTypes.isEmpty())
            assertTrue(diff.unobservedRelationshipTypes.isEmpty())
        }

        @Test
        fun `an observed relationship type with no declaration is reported as drift`() {
            val diff = declaredObservedDiffer.diffAgainstObserved(
                declared("Person", relationshipNames = emptyList()),
                observed(entityTypeNames = setOf("Person"), relationshipTypeNames = setOf("GHOST_REL")),
            )
            assertTrue(diff.hasDrift)
            assertEquals(setOf("GHOST_REL"), diff.driftedRelationshipTypes)
        }

        @Test
        fun `diff carries the declared version and observed schema unchanged`() {
            val declaredVersion = declared("Person")
            val observedSchema = observed(entityTypeNames = setOf("Person"))
            val diff = declaredObservedDiffer.diffAgainstObserved(declaredVersion, observedSchema)
            assertEquals(declaredVersion, diff.declaredVersion)
            assertEquals(observedSchema, diff.observedSchema)
        }
    }
}

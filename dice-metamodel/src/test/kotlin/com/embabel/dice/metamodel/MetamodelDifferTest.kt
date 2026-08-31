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
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.core.DynamicType
import com.embabel.agent.core.ValuePropertyDefinition
import com.embabel.dice.metamodel.support.StructuralMetamodelDiffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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

        @Test
        fun `schemas with identical property signatures produce an empty diff`() {
            val old = personWith(ValuePropertyDefinition("age", type = "integer", cardinality = Cardinality.ONE))
            val new = personWith(ValuePropertyDefinition("age", type = "integer", cardinality = Cardinality.ONE))
            val diff = differ.diff(old, new)
            assertTrue(diff.isEmpty, "no change means no entries: ${diff.changes}")
            assertTrue(diff.propertySignatureChanges.isEmpty())
        }

        @Test
        fun `an empty diff agrees with the content hash`() {
            val old = personWith(ValuePropertyDefinition("age", type = "integer"))
            val new = personWith(ValuePropertyDefinition("age", type = "integer"))
            assertTrue(MetamodelVersion.from(old).hasSameContentAs(MetamodelVersion.from(new)))
            assertTrue(differ.diff(old, new).isEmpty)
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
            assertTrue(
                diff.removedEntityTypes.contains("LegacyType"),
                "Expected LegacyType in removed: ${diff.removedEntityTypes}",
            )
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

        @Test
        fun `touchedEntityTypes gathers every type the diff says anything about`() {
            val old = DataDictionary.fromDomainTypes(
                "test",
                listOf(
                    DynamicType(name = "Person", ownProperties = listOf(ValuePropertyDefinition("age", type = "string"))),
                    DynamicType(name = "LegacyType"),
                ),
            )
            val new = DataDictionary.fromDomainTypes(
                "test",
                listOf(
                    DynamicType(name = "Person", ownProperties = listOf(ValuePropertyDefinition("age", type = "integer"))),
                    DynamicType(name = "NewType"),
                ),
            )
            val diff = differ.diff(old, new)
            // Person only shows up via a signature change; LegacyType and NewType via add/remove.
            assertEquals(setOf("Person", "LegacyType", "NewType"), diff.touchedEntityTypes)
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
            val old = personWithParent("Agent") // labels: {Person, Agent}
            val new = personWithParent("Actor") // labels: {Person, Actor}
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

        @Test
        fun `a wholly new property is reported as modified, with its full signature`() {
            val old = personWith(ValuePropertyDefinition("age", type = "integer"))
            val new = personWith(
                ValuePropertyDefinition("age", type = "integer"),
                ValuePropertyDefinition("email", type = "string", cardinality = Cardinality.OPTIONAL),
            )
            val diff = differ.diff(old, new)

            assertFalse(diff.isEmpty)
            assertTrue(diff.addedEntityTypes.isEmpty())
            assertTrue(diff.removedEntityTypes.isEmpty())

            val change = diff.modifiedEntityTypes.single()
            assertEquals("Person", change.typeName)
            assertEquals(setOf("email"), change.addedPropertyNames)
            assertEquals(
                setOf(PropertySignature("email", PropertySignature.Kind.VALUE, "string", Cardinality.OPTIONAL)),
                change.addedProperties,
                "an added property carries its shape, not just its name",
            )
            assertTrue(change.removedProperties.isEmpty())
            // labels are unchanged, so their deltas stay empty
            assertTrue(change.addedLabels.isEmpty())
            assertTrue(change.removedLabels.isEmpty())
            assertTrue(diff.propertySignatureChanges.isEmpty(), "nothing was reshaped, only added")
        }

        @Test
        fun `a dropped property is reported as modified, with its full signature`() {
            val old = personWith(
                ValuePropertyDefinition("age", type = "integer"),
                ValuePropertyDefinition("nickname", type = "string"),
            )
            val new = personWith(ValuePropertyDefinition("age", type = "integer"))
            val change = differ.diff(old, new).modifiedEntityTypes.single()
            assertEquals(setOf("nickname"), change.removedPropertyNames)
            assertEquals(
                setOf(PropertySignature("nickname", PropertySignature.Kind.VALUE, "string", Cardinality.ONE)),
                change.removedProperties,
            )
            assertTrue(change.addedProperties.isEmpty())
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
            assertEquals(setOf("email"), change.addedPropertyNames)
            assertEquals(setOf("age"), change.removedPropertyNames)
        }
    }

    /**
     * Why this module stamps property *signatures* rather than property names. Every case below
     * leaves both schemas with the same type names and the same property names, so a name-only diff
     * would report nothing, while what the graph can hold has changed.
     */
    @Nested
    inner class PropertySignatureChanges {

        @Test
        fun `narrowing a property type is reported as a signature change, not an add and a remove`() {
            val old = personWith(ValuePropertyDefinition("age", type = "string"))
            val new = personWith(ValuePropertyDefinition("age", type = "integer"))
            val diff = differ.diff(old, new)

            assertFalse(diff.isEmpty, "string -> integer is a real change to what the graph can hold")
            val change = diff.propertySignatureChanges.single()
            assertEquals("Person", change.typeName)
            assertEquals("age", change.propertyName)
            assertEquals("string", change.before.type)
            assertEquals("integer", change.after.type)
            assertTrue(change.typeChanged)
            assertFalse(change.cardinalityChanged)
            assertFalse(change.kindChanged)

            // Matched by name, so it must not double-report as an add plus a remove.
            assertTrue(
                diff.modifiedEntityTypes.isEmpty(),
                "a reshaped property is one change, not an add and a remove: ${diff.modifiedEntityTypes}",
            )
            assertEquals(1, diff.changes.size, "exactly one entry: ${diff.changes}")
        }

        @Test
        fun `changing cardinality is reported as a signature change`() {
            val old = personWith(ValuePropertyDefinition("nickname", type = "string", cardinality = Cardinality.ONE))
            val new = personWith(ValuePropertyDefinition("nickname", type = "string", cardinality = Cardinality.LIST))
            val diff = differ.diff(old, new)

            val change = diff.propertySignatureChanges.single()
            assertEquals("nickname", change.propertyName)
            assertEquals(Cardinality.ONE, change.before.cardinality)
            assertEquals(Cardinality.LIST, change.after.cardinality)
            assertTrue(change.cardinalityChanged)
            assertFalse(change.typeChanged)
            assertTrue(diff.modifiedEntityTypes.isEmpty())
        }

        @Test
        fun `turning a value into a reference is reported as a kind change`() {
            val old = stampOf(
                "Person" to setOf(
                    PropertySignature("worksAt", PropertySignature.Kind.VALUE, "string", Cardinality.ONE),
                ),
            )
            val new = stampOf(
                "Person" to setOf(
                    PropertySignature("worksAt", PropertySignature.Kind.REFERENCE, "Company", Cardinality.ONE),
                ),
            )
            val change = differ.diff(old, new).propertySignatureChanges.single()
            assertEquals("worksAt", change.propertyName)
            assertTrue(change.kindChanged, "VALUE -> REFERENCE is a kind change")
            assertTrue(change.typeChanged, "and the target moved from 'string' to 'Company'")
            assertFalse(change.cardinalityChanged)
        }

        @Test
        fun `one type change and one cardinality change on the same type both appear, in name order`() {
            val old = personWith(
                ValuePropertyDefinition("age", type = "string"),
                ValuePropertyDefinition("nickname", type = "string", cardinality = Cardinality.ONE),
            )
            val new = personWith(
                ValuePropertyDefinition("age", type = "integer"),
                ValuePropertyDefinition("nickname", type = "string", cardinality = Cardinality.SET),
            )
            val changes = differ.diff(old, new).propertySignatureChanges
            assertEquals(listOf("age", "nickname"), changes.map { it.propertyName })
            assertTrue(changes[0].typeChanged)
            assertTrue(changes[1].cardinalityChanged)
        }

        @Test
        fun `a signature change on one type leaves an untouched type out of the diff`() {
            val old = stampOf(
                "Person" to setOf(PropertySignature("age", PropertySignature.Kind.VALUE, "string", Cardinality.ONE)),
                "Company" to setOf(PropertySignature("name", PropertySignature.Kind.VALUE, "string", Cardinality.ONE)),
            )
            val new = stampOf(
                "Person" to setOf(PropertySignature("age", PropertySignature.Kind.VALUE, "integer", Cardinality.ONE)),
                "Company" to setOf(PropertySignature("name", PropertySignature.Kind.VALUE, "string", Cardinality.ONE)),
            )
            val diff = differ.diff(old, new)
            assertEquals(setOf("Person"), diff.touchedEntityTypes)
        }

        @Test
        fun `a signature change moves the content hash, so hash equality and an empty diff agree`() {
            val old = personWith(ValuePropertyDefinition("age", type = "string"))
            val new = personWith(ValuePropertyDefinition("age", type = "integer"))
            assertFalse(MetamodelVersion.from(old).hasSameContentAs(MetamodelVersion.from(new)))
            assertFalse(differ.diff(old, new).isEmpty)
        }

        /**
         * A `DataDictionary` may hold two same-named domain types whose properties get unioned into
         * one stamp, so a single property name can carry two signatures at once. There is no
         * before/after pair to report then, so the differing signatures come back as added and
         * removed.
         */
        @Test
        fun `a property name carrying two signatures falls back to added and removed`() {
            val old = stampOf(
                "Person" to setOf(
                    PropertySignature("age", PropertySignature.Kind.VALUE, "string", Cardinality.ONE),
                    PropertySignature("age", PropertySignature.Kind.VALUE, "integer", Cardinality.ONE),
                ),
            )
            val new = stampOf(
                "Person" to setOf(
                    PropertySignature("age", PropertySignature.Kind.VALUE, "integer", Cardinality.ONE),
                ),
            )
            val diff = differ.diff(old, new)
            assertTrue(diff.propertySignatureChanges.isEmpty(), "no honest pairing exists here")
            val change = diff.modifiedEntityTypes.single()
            assertEquals(
                setOf(PropertySignature("age", PropertySignature.Kind.VALUE, "string", Cardinality.ONE)),
                change.removedProperties,
            )
            assertTrue(change.addedProperties.isEmpty())
        }

        @Test
        fun `signature changes on a type that was itself removed are not reported`() {
            val old = stampOf(
                "Person" to setOf(PropertySignature("age", PropertySignature.Kind.VALUE, "string", Cardinality.ONE)),
            )
            val new = stampOf()
            val diff = differ.diff(old, new)
            assertEquals(setOf("Person"), diff.removedEntityTypes)
            assertTrue(diff.propertySignatureChanges.isEmpty(), "the whole type went; the property isn't news")
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
         * A label name that contains a comma, which happens when names come from LLM extraction.
         * The diff compares label sets directly, so punctuation in a name is just a character: the
         * change is still detected and the label comes back as one entry.
         */
        @Test
        fun `label containing a comma is treated as a single label`() {
            val commaParent = DynamicType(name = "foo,bar")
            val old = DataDictionary.fromDomainTypes(
                "test",
                listOf(DynamicType(name = "Person", parents = listOf(commaParent))),
            )
            val new = DataDictionary.fromDomainTypes("test", listOf(DynamicType(name = "Person")))
            val diff = differ.diff(old, new)

            val change = diff.modifiedEntityTypes.single()
            assertEquals(1, change.removedLabels.size)
            assertTrue(change.removedLabels.single().contains(","))
            assertTrue(change.addedLabels.isEmpty())
        }

        /**
         * Property names can contain spaces when they come from free-text or LLM extraction. The
         * two sets `{"a", "b c"}` and `{"a b", "c"}` are different, yet both collapse to the string
         * "a b c" under a space-joined projection. Comparing the sets themselves keeps a real
         * property change visible.
         */
        @Test
        fun `property sets that collide under a space delimiter are still reported as modified`() {
            val old = personWith(ValuePropertyDefinition("a"), ValuePropertyDefinition("b c"))
            val new = personWith(ValuePropertyDefinition("a b"), ValuePropertyDefinition("c"))
            val diff = differ.diff(old, new)

            assertFalse(diff.isEmpty, "a real property change must not be hidden by a delimiter collision")
            val change = diff.modifiedEntityTypes.single()
            assertEquals("Person", change.typeName)
            assertEquals(setOf("a b", "c"), change.addedPropertyNames)
            assertEquals(setOf("a", "b c"), change.removedPropertyNames)
        }
    }

    @Nested
    inner class DeclaredVsObserved {

        private fun declared(vararg typeNames: String, relationshipTypeNames: Set<String> = emptySet()): DeclaredSchema =
            DeclaredSchema(
                version = MetamodelVersion(
                    schemaName = "test",
                    entityTypeNames = typeNames.toList(),
                    entityTypeLabels = typeNames.associateWith { setOf(it) },
                    entityTypeProperties = typeNames.associateWith { emptySet() },
                    relationshipNames = relationshipTypeNames.map { "From-[$it]->To" },
                ),
                relationshipTypeNames = relationshipTypeNames,
            )

        private fun observed(
            entityTypeNames: Set<String>,
            relationshipTypeNames: Set<String> = emptySet(),
        ): ObservedSchema = ObservedSchema(
            entityTypeNames = entityTypeNames,
            relationshipTypeNames = relationshipTypeNames,
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
            // Declared descriptors carry from/to node types; an observed graph only reports bare
            // relationship type names (e.g. via a `db.relationshipTypes()`-style query).
            val diff = declaredObservedDiffer.diffAgainstObserved(
                declared("Person", "Company", relationshipTypeNames = setOf("WORKS_AT")),
                observed(entityTypeNames = setOf("Person", "Company"), relationshipTypeNames = setOf("WORKS_AT")),
            )
            assertFalse(diff.hasDrift)
            assertTrue(diff.driftedRelationshipTypes.isEmpty())
            assertTrue(diff.unobservedRelationshipTypes.isEmpty())
        }

        @Test
        fun `an observed relationship type with no declaration is reported as drift`() {
            val diff = declaredObservedDiffer.diffAgainstObserved(
                declared("Person"),
                observed(entityTypeNames = setOf("Person"), relationshipTypeNames = setOf("GHOST_REL")),
            )
            assertTrue(diff.hasDrift)
            assertEquals(setOf("GHOST_REL"), diff.driftedRelationshipTypes)
        }

        @Test
        fun `diff carries the declaration and observed schema unchanged`() {
            val declaration = declared("Person")
            val observedSchema = observed(entityTypeNames = setOf("Person"))
            val diff = declaredObservedDiffer.diffAgainstObserved(declaration, observedSchema)
            assertEquals(declaration, diff.declared)
            assertEquals(declaration.version, diff.declaredVersion)
            assertEquals(observedSchema, diff.observedSchema)
        }

        /**
         * The observed side has names and nothing else, so a declared property's shape is never part
         * of this comparison. Two declarations differing only in a property signature produce an
         * identical declared/observed diff, because the graph cannot answer that question.
         */
        @Test
        fun `a property signature difference is invisible to the declared-observed comparison`() {
            val observedSchema = observed(entityTypeNames = setOf("Person"))
            val asString = DeclaredSchema(
                version = MetamodelVersion(
                    schemaName = "test",
                    entityTypeNames = listOf("Person"),
                    entityTypeLabels = mapOf("Person" to setOf("Person")),
                    entityTypeProperties = mapOf(
                        "Person" to setOf(
                            PropertySignature("age", PropertySignature.Kind.VALUE, "string", Cardinality.ONE),
                        ),
                    ),
                    relationshipNames = emptyList(),
                ),
                relationshipTypeNames = emptySet(),
            )
            val asInteger = DeclaredSchema(
                version = MetamodelVersion(
                    schemaName = "test",
                    entityTypeNames = listOf("Person"),
                    entityTypeLabels = mapOf("Person" to setOf("Person")),
                    entityTypeProperties = mapOf(
                        "Person" to setOf(
                            PropertySignature("age", PropertySignature.Kind.VALUE, "integer", Cardinality.ONE),
                        ),
                    ),
                    relationshipNames = emptyList(),
                ),
                relationshipTypeNames = emptySet(),
            )

            val stringDiff = declaredObservedDiffer.diffAgainstObserved(asString, observedSchema)
            val integerDiff = declaredObservedDiffer.diffAgainstObserved(asInteger, observedSchema)

            assertFalse(stringDiff.hasDrift)
            assertFalse(integerDiff.hasDrift)
            assertEquals(stringDiff.driftedEntityTypes, integerDiff.driftedEntityTypes)
            assertEquals(stringDiff.unobservedEntityTypes, integerDiff.unobservedEntityTypes)
            // Declared-vs-declared is where that difference does show up.
            assertEquals(1, differ.diff(asString.version, asInteger.version).propertySignatureChanges.size)
        }

        /**
         * A declared type carries every label in its hierarchy, and a graph reports all of them:
         * declaring `Person` with parent `Agent` puts both labels on every Person node. Comparing
         * observed labels against type names alone would call `Agent` undeclared drift on a schema
         * nobody had touched.
         */
        @Test
        fun `an inherited label on a declared type is not drift`() {
            val personIsAnAgent = DeclaredSchema.from(
                DataDictionary.fromDomainTypes(
                    "test",
                    listOf(DynamicType(name = "Person", parents = listOf(DynamicType(name = "Agent")))),
                ),
            )
            val diff = declaredObservedDiffer.diffAgainstObserved(
                personIsAnAgent,
                observed(entityTypeNames = setOf("Person", "Agent")),
            )
            assertFalse(diff.hasDrift, "an inherited label is declared: ${diff.driftedEntityTypes}")
            assertTrue(diff.driftedEntityTypes.isEmpty())
        }

        @Test
        fun `a label matching no declared type or label is still drift`() {
            val personIsAnAgent = DeclaredSchema.from(
                DataDictionary.fromDomainTypes(
                    "test",
                    listOf(DynamicType(name = "Person", parents = listOf(DynamicType(name = "Agent")))),
                ),
            )
            val diff = declaredObservedDiffer.diffAgainstObserved(
                personIsAnAgent,
                observed(entityTypeNames = setOf("Person", "Agent", "GhostIntegrationType")),
            )
            assertTrue(diff.hasDrift)
            assertEquals(setOf("GhostIntegrationType"), diff.driftedEntityTypes)
        }

        /**
         * The other direction stays on type names. "Declared but with no data" is a statement about
         * types, so a parent label must not be listed as an unobserved type of its own.
         */
        @Test
        fun `an inherited label is not reported as an unobserved type`() {
            val personIsAnAgent = DeclaredSchema.from(
                DataDictionary.fromDomainTypes(
                    "test",
                    listOf(DynamicType(name = "Person", parents = listOf(DynamicType(name = "Agent")))),
                ),
            )
            val diff = declaredObservedDiffer.diffAgainstObserved(
                personIsAnAgent,
                observed(entityTypeNames = emptySet()),
            )
            assertEquals(setOf("Person"), diff.unobservedEntityTypes)
        }

        /**
         * A relationship name that embeds a `-[...]->`-shaped substring: the case a regex-based
         * implementation got wrong, where a greedy `.*-\[(.+)]->.*` backtracks to the *last* such
         * substring and parses `"A-[X]->B"` down to `"X"`. These names are free-text and
         * LLM-derived, so that shape occurs. Bare names travel with the declaration and are never
         * recovered from a descriptor, so the match holds whatever the name looks like.
         */
        @Test
        fun `a relationship name shaped like a full descriptor still matches by its bare name`() {
            val trickyRelName = "A-[X]->B"
            val diff = declaredObservedDiffer.diffAgainstObserved(
                declared("Foo", "Bar", relationshipTypeNames = setOf(trickyRelName)),
                observed(entityTypeNames = setOf("Foo", "Bar"), relationshipTypeNames = setOf(trickyRelName)),
            )
            assertFalse(diff.hasDrift, "the declared relationship must match the observed one by its full bare name")
            assertTrue(diff.driftedRelationshipTypes.isEmpty())
            assertTrue(diff.unobservedRelationshipTypes.isEmpty())
        }

        /**
         * The inverse check: observing the substring a greedy parse would have wrongly extracted
         * must not be treated as a match. It has to show up as both drift and unobserved.
         */
        @Test
        fun `an observed relationship equal to a substring of a tricky declared name is not a match`() {
            val trickyRelName = "A-[X]->B"
            val diff = declaredObservedDiffer.diffAgainstObserved(
                declared("Foo", "Bar", relationshipTypeNames = setOf(trickyRelName)),
                observed(entityTypeNames = setOf("Foo", "Bar"), relationshipTypeNames = setOf("X")),
            )
            assertTrue(diff.hasDrift, "observed 'X' must not be confused with the declared '$trickyRelName'")
            assertEquals(setOf("X"), diff.driftedRelationshipTypes)
            assertEquals(setOf(trickyRelName), diff.unobservedRelationshipTypes)
        }
    }

    /**
     * A finished diff must not be reshapeable. Mirrors `MetamodelVersionTest.Immutability`: Kotlin's
     * read-only collection types are a compile-time promise only, and a Java caller sees plain
     * `java.util` collections through the getters, so these have to refuse at runtime.
     */
    @Nested
    inner class Immutability {

        @Test
        fun `the change list a diff hands back cannot be mutated`() {
            val diff = differ.diff(
                personWith(ValuePropertyDefinition("age", type = "string")),
                personWith(ValuePropertyDefinition("age", type = "integer"), ValuePropertyDefinition("email")),
            )
            assertFalse(diff.isEmpty)

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (diff.changes as MutableList<MetamodelChange>).clear()
            }
        }

        @Test
        fun `mutating the change list passed in does not change the diff`() {
            val changes = mutableListOf<MetamodelChange>(MetamodelChange.EntityTypeAdded("Person"))
            val version = stampOf()
            val diff = MetamodelDiff(fromVersion = version, toVersion = version, changes = changes)

            changes += MetamodelChange.EntityTypeAdded("Sneaky")
            changes.clear()

            assertEquals(listOf(MetamodelChange.EntityTypeAdded("Person")), diff.changes)
        }

        @Test
        fun `the collections inside a modified-type change cannot be mutated`() {
            val change = differ.diff(
                personWith(ValuePropertyDefinition("age")),
                personWith(ValuePropertyDefinition("email")),
            ).modifiedEntityTypes.single()

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (change.addedProperties as MutableSet<PropertySignature>).clear()
            }

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (change.removedProperties as MutableSet<PropertySignature>).clear()
            }

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (change.addedLabels as MutableSet<String>).add("Sneaky")
            }

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (change.removedLabels as MutableSet<String>).add("Sneaky")
            }
        }

        @Test
        fun `the sets a declared-observed diff hands back cannot be mutated`() {
            val diff = StructuralMetamodelDiffer().diffAgainstObserved(
                DeclaredSchema.from(DataDictionary.fromDomainTypes("test", listOf(DynamicType(name = "Person")))),
                ObservedSchema(
                    entityTypeNames = setOf("Ghost"),
                    relationshipTypeNames = setOf("GHOST_REL"),
                    capturedAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
            )

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (diff.driftedEntityTypes as MutableSet<String>).clear()
            }

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (diff.driftedRelationshipTypes as MutableSet<String>).clear()
            }

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (diff.unobservedEntityTypes as MutableSet<String>).clear()
            }

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (diff.unobservedRelationshipTypes as MutableSet<String>).add("Sneaky")
            }
        }

        @Test
        fun `an observed snapshot cannot be mutated, and mutating what built it changes nothing`() {
            // A backend typically fills a mutable set as it walks query results, then hands it over.
            val names = mutableSetOf("Person")
            val relationships = mutableSetOf("WORKS_AT")
            val snapshot = ObservedSchema(names, relationships, Instant.parse("2026-01-01T00:00:00Z"))

            names += "Sneaky"
            relationships.clear()

            assertEquals(setOf("Person"), snapshot.entityTypeNames)
            assertEquals(setOf("WORKS_AT"), snapshot.relationshipTypeNames)

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (snapshot.entityTypeNames as MutableSet<String>).add("Sneaky")
            }

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (snapshot.relationshipTypeNames as MutableSet<String>).clear()
            }
        }
    }

    companion object {

        /** A one-type dictionary whose `Person` carries exactly the given properties. */
        private fun personWith(vararg properties: ValuePropertyDefinition): DataDictionary =
            DataDictionary.fromDomainTypes(
                "test",
                listOf(DynamicType(name = "Person", ownProperties = properties.toList())),
            )

        /**
         * A stamp built directly from property signatures, for shapes a `DataDictionary` makes
         * awkward to express: a `VALUE` and a `REFERENCE` under one name, or two signatures for the
         * same property name.
         */
        private fun stampOf(vararg types: Pair<String, Set<PropertySignature>>): MetamodelVersion =
            MetamodelVersion(
                schemaName = "test",
                entityTypeNames = types.map { it.first },
                entityTypeLabels = types.associate { it.first to setOf(it.first) },
                entityTypeProperties = types.toMap(),
                relationshipNames = emptyList(),
            )
    }
}

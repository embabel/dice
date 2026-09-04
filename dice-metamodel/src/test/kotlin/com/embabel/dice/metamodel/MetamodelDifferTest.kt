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
import com.embabel.agent.core.DomainTypePropertyDefinition
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

        /**
         * A JVM-backed type is declared by its class name, and what comes back at it is the simple
         * label: extraction records a mention type of `Person`, and the graph reports `Person` on
         * the node. Comparing the two spellings directly called every fully qualified type
         * unobserved, and read the label it was written under as drift — one declared type
         * reported twice, in both buckets, on a schema in perfect health.
         */
        @Test
        fun `a fully qualified declared type is matched by the simple label a graph reports`() {
            val diff = declaredObservedDiffer.diffAgainstObserved(
                declared("com.example.Person"),
                observed(entityTypeNames = setOf("Person")),
            )
            assertFalse(diff.hasDrift, "the observed label names the declared type: ${diff.driftedEntityTypes}")
            assertTrue(diff.driftedEntityTypes.isEmpty())
            assertTrue(
                diff.unobservedEntityTypes.isEmpty(),
                "the declared type was observed under its label: ${diff.unobservedEntityTypes}",
            )
        }

        @Test
        fun `a graph reporting a declared name in full still matches it`() {
            val diff = declaredObservedDiffer.diffAgainstObserved(
                declared("com.example.Person"),
                observed(entityTypeNames = setOf("com.example.Person")),
            )
            assertFalse(diff.hasDrift)
            assertTrue(diff.unobservedEntityTypes.isEmpty())
        }

        /**
         * The label match must stay a match on the declared name and nothing else. A type nobody
         * declared is still drift, which is the whole point of the bucket.
         */
        @Test
        fun `an observed label matching no declared name or own label is still drift`() {
            val diff = declaredObservedDiffer.diffAgainstObserved(
                declared("com.example.Person"),
                observed(entityTypeNames = setOf("Person", "Ghost")),
            )
            assertTrue(diff.hasDrift)
            assertEquals(setOf("Ghost"), diff.driftedEntityTypes)
            assertTrue(diff.unobservedEntityTypes.isEmpty())
        }

        @Test
        fun `a fully qualified declared type with no data is unobserved under its declared name`() {
            val diff = declaredObservedDiffer.diffAgainstObserved(
                declared("com.example.Person", "com.example.NeverSeenYet"),
                observed(entityTypeNames = setOf("Person")),
            )
            assertFalse(diff.hasDrift)
            assertEquals(setOf("com.example.NeverSeenYet"), diff.unobservedEntityTypes)
        }

        /**
         * An ungoverned type is declared by the same dictionary as a governed one, so its name can
         * be fully qualified too, and it reaches the drift check with no label set of its own to
         * fall back on. Reading it by its simple label is what keeps a deliberately ungoverned type
         * out of drift once its name is qualified.
         */
        @Test
        fun `a known but ungoverned fully qualified type is not drift when observed by its label`() {
            val person = DynamicType("com.example.Person")
            val sighting = DynamicType(
                name = "com.example.Sighting",
                ownProperties = listOf(DomainTypePropertyDefinition("about", person)),
            )
            val dictionary = DataDictionary.fromDomainTypes("test", listOf(person, sighting))
            val declaration = DeclaredSchema.from(dictionary, GovernedTypeSelector { it.name == "com.example.Person" })

            assertEquals(setOf("com.example.Sighting"), declaration.ungovernedEntityTypeNames)

            val diff = declaredObservedDiffer.diffAgainstObserved(
                declaration,
                observed(entityTypeNames = setOf("Person", "Sighting"), relationshipTypeNames = setOf("about")),
            )

            assertFalse(diff.hasDrift, "both types are declared: ${diff.driftedEntityTypes}")
            assertTrue(diff.driftedEntityTypes.isEmpty())
            assertTrue(diff.unobservedEntityTypes.isEmpty())
        }

        @Test
        fun `an ungoverned qualified type does not hide an undeclared one`() {
            val person = DynamicType("com.example.Person")
            val sighting = DynamicType("com.example.Sighting")
            val dictionary = DataDictionary.fromDomainTypes("test", listOf(person, sighting))
            val declaration = DeclaredSchema.from(dictionary, GovernedTypeSelector { it.name == "com.example.Person" })

            val diff = declaredObservedDiffer.diffAgainstObserved(
                declaration,
                observed(entityTypeNames = setOf("Person", "Sighting", "Ghost")),
            )

            assertTrue(diff.hasDrift)
            assertEquals(setOf("Ghost"), diff.driftedEntityTypes)
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

        /**
         * `Sighting` is in the dictionary and the selector deliberately excludes it. Observing it
         * has to read as an ordinary open-world type doing what open-world types do: a downstream
         * policy watching drift for signs of orphaned data would otherwise flag a proposition
         * resting on `Sighting` for a governance decision the host left it out of on purpose.
         */
        @Test
        fun `a known but ungoverned type is not drift when observed`() {
            val person = DynamicType("Person")
            val sighting = DynamicType(
                name = "Sighting",
                ownProperties = listOf(DomainTypePropertyDefinition("about", person)),
            )
            val dictionary = DataDictionary.fromDomainTypes("test", listOf(person, sighting))
            val declaration = DeclaredSchema.from(dictionary, GovernedTypeSelector { it.name == "Person" })

            val diff = declaredObservedDiffer.diffAgainstObserved(
                declaration,
                observed(entityTypeNames = setOf("Person", "Sighting"), relationshipTypeNames = setOf("about")),
            )

            assertFalse(diff.hasDrift, "Sighting and its relationship are known and deliberately ungoverned: " +
                "entity=${diff.driftedEntityTypes}, rel=${diff.driftedRelationshipTypes}")
            assertTrue(diff.driftedEntityTypes.isEmpty())
            assertTrue(diff.driftedRelationshipTypes.isEmpty())
        }

        /**
         * A selector that governs nothing must enforce nothing over what the dictionary already
         * names, and stay a statement about governance: a type the dictionary never declared at all
         * still surfaces as drift, keeping detection alive for genuinely undeclared types.
         */
        @Test
        fun `a selector governing no types enforces no types the dictionary declares`() {
            val person = DynamicType("Person")
            val sighting = DynamicType(
                name = "Sighting",
                ownProperties = listOf(DomainTypePropertyDefinition("about", person)),
            )
            val dictionary = DataDictionary.fromDomainTypes("test", listOf(person, sighting))
            val declaration = DeclaredSchema.from(dictionary, GovernedTypeSelector { false })

            val diff = declaredObservedDiffer.diffAgainstObserved(
                declaration,
                observed(
                    entityTypeNames = setOf("Person", "Sighting", "GhostIntegrationType"),
                    relationshipTypeNames = setOf("about"),
                ),
            )

            assertTrue(diff.hasDrift, "a type the dictionary never named must still be reported")
            assertEquals(setOf("GhostIntegrationType"), diff.driftedEntityTypes)
            assertTrue(diff.driftedRelationshipTypes.isEmpty())
        }
    }

    /**
     * A declared rename pairs an old name with a new one. Without the alias the same move is a
     * removal plus an addition, which reads as loss on data nobody stranded.
     */
    @Nested
    inner class TypeRenames {

        @Test
        fun `a renamed type with an alias is one change, not a removal and an addition`() {
            val old = versionOf(listOf("Person"))
            val new = versionOf(listOf("Human"), aliases = mapOf("Human" to setOf("Person")))
            val diff = differ.diff(old, new)

            assertEquals(listOf(MetamodelChange.EntityTypeRenamed("Person", "Human")), diff.changes)
            assertTrue(diff.removedEntityTypes.isEmpty())
            assertTrue(diff.addedEntityTypes.isEmpty())
            assertTrue(
                diff.modifiedEntityTypes.isEmpty(),
                "the own-name label swap rides in the rename: ${diff.modifiedEntityTypes}",
            )
        }

        @Test
        fun `a rename without an alias is still a removal and an addition`() {
            val diff = differ.diff(versionOf(listOf("Person")), versionOf(listOf("Human")))
            assertEquals(setOf("Person"), diff.removedEntityTypes)
            assertEquals(setOf("Human"), diff.addedEntityTypes)
        }

        /**
         * `A` became `B` became `C`, and the stamp for `B` was never diffed against. Pairing matches
         * the whole alias set, so `A` still pairs with `C`. The intermediate name comes with it: `C`
         * claims a name the older stamp knew nothing about, which is an alias change on top of the
         * rename. Diffing either hop on its own emits the rename alone.
         */
        @Test
        fun `an alias carried across two renames still pairs on a non-adjacent stamp`() {
            val first = versionOf(listOf("A"))
            val third = versionOf(listOf("C"), aliases = mapOf("C" to setOf("A", "B")))
            val diff = differ.diff(first, third)

            assertEquals(
                listOf(
                    MetamodelChange.EntityTypeRenamed("A", "C"),
                    MetamodelChange.EntityTypeAliasesChanged("C", emptySet(), setOf("A", "B")),
                ),
                diff.changes,
            )
            assertTrue(diff.removedEntityTypes.isEmpty())
            assertTrue(diff.addedEntityTypes.isEmpty())
        }

        @Test
        fun `a renamed type reports everything else that moved under its new name`() {
            val old = versionOf(
                listOf("Person"),
                labels = mapOf("Person" to setOf("Person", "Agent")),
                properties = mapOf("Person" to setOf(valueProperty("age", "integer"))),
            )
            val new = versionOf(
                listOf("Human"),
                labels = mapOf("Human" to setOf("Human", "Actor")),
                properties = mapOf("Human" to setOf(valueProperty("age", "string"))),
                aliases = mapOf("Human" to setOf("Person")),
            )
            val diff = differ.diff(old, new)

            assertEquals(
                listOf("EntityTypeRenamed", "EntityTypeModified", "PropertySignatureChanged"),
                diff.changes.map { it::class.simpleName },
            )
            val modified = diff.modifiedEntityTypes.single()
            assertEquals("Human", modified.typeName)
            assertEquals(setOf("Actor"), modified.addedLabels)
            assertEquals(setOf("Agent"), modified.removedLabels, "the parent label change is still reported")
            assertEquals("Human", diff.propertySignatureChanges.single().typeName)
        }

        @Test
        fun `every change kind for one type comes out in canonical order`() {
            val old = versionOf(
                listOf("Person"),
                labels = mapOf("Person" to setOf("Person", "Agent")),
                properties = mapOf(
                    "Person" to setOf(
                        valueProperty("age", "integer"),
                        valueProperty("nickname", "string"),
                    ),
                ),
            )
            val new = versionOf(
                listOf("Human"),
                labels = mapOf("Human" to setOf("Human", "Actor")),
                properties = mapOf(
                    "Human" to setOf(
                        valueProperty("years", "integer", aliases = setOf("age")),
                        valueProperty("nickname", "text"),
                    ),
                ),
                aliases = mapOf("Human" to setOf("Person", "Individual")),
            )
            val diff = differ.diff(old, new)

            assertEquals(
                listOf(
                    "EntityTypeRenamed",
                    "EntityTypeAliasesChanged",
                    "EntityTypeModified",
                    "PropertyRenamed",
                    "PropertySignatureChanged",
                ),
                diff.changes.map { it::class.simpleName },
            )
            val renamedProperty = diff.changes.filterIsInstance<MetamodelChange.PropertyRenamed>().single()
            assertEquals("Human", renamedProperty.typeName, "reported under the type's new name")
            assertEquals("age", renamedProperty.before.name)
            assertEquals("years", renamedProperty.after.name)
        }

        @Test
        fun `touchedEntityTypes carries both names of a rename`() {
            val diff = differ.diff(
                versionOf(listOf("Person")),
                versionOf(listOf("Human"), aliases = mapOf("Human" to setOf("Person"))),
            )
            assertEquals(setOf("Person", "Human"), diff.touchedEntityTypes)
        }

        /**
         * Relationship descriptors embed type names as free text the differ compares as atoms and
         * must never parse, so a relationship touching a renamed endpoint churns as a removal plus
         * an addition alongside the rename. Known exception to reporting each difference once;
         * quarantine ignores relationship changes, so it has no drift-policy effect.
         */
        @Test
        fun `a relationship touching a renamed type churns as a removal and an addition`() {
            val old = versionOf(
                listOf("Company", "Person"),
                relationships = listOf("Person-[worksAt]->Company"),
            )
            val new = versionOf(
                listOf("Company", "Human"),
                aliases = mapOf("Human" to setOf("Person")),
                relationships = listOf("Human-[worksAt]->Company"),
            )
            val diff = differ.diff(old, new)

            assertEquals(
                listOf(
                    MetamodelChange.EntityTypeRenamed("Person", "Human"),
                    MetamodelChange.RelationshipRemoved("Person-[worksAt]->Company"),
                    MetamodelChange.RelationshipAdded("Human-[worksAt]->Company"),
                ),
                diff.changes,
            )
        }

        @Test
        fun `the same two versions produce the same ordered change list every run`() {
            val old = versionOf(
                listOf("A", "Person", "Referrer"),
                labels = mapOf("Referrer" to setOf("Referrer", "Person")),
                properties = mapOf(
                    "Referrer" to setOf(
                        referenceProperty("link", "A"),
                        valueProperty("age", "integer"),
                    ),
                ),
            )
            val new = versionOf(
                listOf("B", "Human", "Referrer"),
                labels = mapOf("Referrer" to setOf("Referrer", "Human")),
                properties = mapOf(
                    "Referrer" to setOf(
                        referenceProperty("link", "B"),
                        valueProperty("years", "integer", aliases = setOf("age")),
                    ),
                ),
                aliases = mapOf("B" to setOf("A"), "Human" to setOf("Person")),
            )

            val first = differ.diff(old, new).changes
            repeat(20) {
                assertEquals(first, differ.diff(old, new).changes)
                assertEquals(first, differ.diff(rebuilt(old), rebuilt(new)).changes)
            }
        }
    }

    @Nested
    inner class PropertyRenames {

        @Test
        fun `a renamed property with an alias is one change, not an add and a remove`() {
            val old = versionOf(listOf("Person"), properties = mapOf("Person" to setOf(valueProperty("age", "integer"))))
            val new = versionOf(
                listOf("Person"),
                properties = mapOf("Person" to setOf(valueProperty("years", "integer", aliases = setOf("age")))),
            )
            val diff = differ.diff(old, new)

            assertEquals(
                listOf(
                    MetamodelChange.PropertyRenamed(
                        typeName = "Person",
                        before = valueProperty("age", "integer"),
                        after = valueProperty("years", "integer", aliases = setOf("age")),
                    ),
                ),
                diff.changes,
            )
            assertTrue(
                diff.modifiedEntityTypes.isEmpty(),
                "an EntityTypeModified emptied by pairing is not emitted: ${diff.modifiedEntityTypes}",
            )
        }

        @Test
        fun `a property renamed and retyped in one step carries the shape move inside the rename`() {
            val old = versionOf(listOf("Person"), properties = mapOf("Person" to setOf(valueProperty("age", "string"))))
            val new = versionOf(
                listOf("Person"),
                properties = mapOf(
                    "Person" to setOf(
                        valueProperty("years", "integer", cardinality = Cardinality.LIST, aliases = setOf("age")),
                    ),
                ),
            )
            val renamed = differ.diff(old, new).changes.filterIsInstance<MetamodelChange.PropertyRenamed>().single()

            assertTrue(renamed.typeChanged)
            assertTrue(renamed.cardinalityChanged)
            assertFalse(renamed.kindChanged)
        }

        /**
         * The type-merge path: two same-named domain types each declare `age` with a different
         * shape, so the name carries two signatures and there is no single before to pair.
         */
        @Test
        fun `a removed name carrying two signatures falls back to a removal and an addition`() {
            val old = versionOf(
                listOf("Person"),
                properties = mapOf(
                    "Person" to setOf(
                        valueProperty("age", "string"),
                        valueProperty("age", "integer"),
                    ),
                ),
            )
            val new = versionOf(
                listOf("Person"),
                properties = mapOf("Person" to setOf(valueProperty("years", "integer", aliases = setOf("age")))),
            )
            val diff = differ.diff(old, new)

            assertTrue(
                diff.changes.filterIsInstance<MetamodelChange.PropertyRenamed>().isEmpty(),
                "no honest pairing exists for a name with two signatures: ${diff.changes}",
            )
            val modified = diff.modifiedEntityTypes.single()
            assertEquals(setOf("age"), modified.removedPropertyNames)
            assertEquals(setOf("years"), modified.addedPropertyNames)
        }

        @Test
        fun `an alias naming a property that still exists pairs nothing`() {
            val old = versionOf(listOf("Person"), properties = mapOf("Person" to setOf(valueProperty("age", "integer"))))
            val new = versionOf(
                listOf("Person"),
                properties = mapOf(
                    "Person" to setOf(
                        valueProperty("age", "integer"),
                        valueProperty("years", "integer", aliases = setOf("age")),
                    ),
                ),
            )
            val diff = differ.diff(old, new)

            assertTrue(diff.changes.filterIsInstance<MetamodelChange.PropertyRenamed>().isEmpty())
            assertEquals(setOf("years"), diff.modifiedEntityTypes.single().addedPropertyNames)
        }
    }

    /**
     * Pairing needs an exclusive claim on both sides. Where more than one claim lands on a name the
     * declaration has said two things at once, nothing pairs, and the diff reports the tangle
     * rather than picking a winner off the sort order.
     */
    @Nested
    inner class ContestedRenames {

        /**
         * The case from the #85 review. `Person`'s `age` moved from integer to string on one of the
         * two claimants and stayed integer on the other, so choosing the alphabetically first would
         * report a signature change on a type the operator never nominated as the rename, and hand
         * the other one a clean bill as a brand-new type.
         */
        @Test
        fun `two added types claiming one former name pair with neither`() {
            val old = versionOf(
                listOf("Person"),
                properties = mapOf("Person" to setOf(valueProperty("age", "integer"))),
            )
            val new = versionOf(
                listOf("Customer", "Employee"),
                properties = mapOf(
                    "Customer" to setOf(valueProperty("age", "string")),
                    "Employee" to setOf(valueProperty("age", "integer")),
                ),
                aliases = mapOf("Customer" to setOf("Person"), "Employee" to setOf("Person")),
            )
            val diff = differ.diff(old, new)

            assertEquals(
                listOf(
                    MetamodelChange.EntityTypeRemoved("Person"),
                    MetamodelChange.EntityTypeAdded("Customer"),
                    MetamodelChange.EntityTypeAdded("Employee"),
                    MetamodelChange.AmbiguousEntityTypeRename(
                        formerNames = setOf("Person"),
                        candidates = setOf("Customer", "Employee"),
                    ),
                ),
                diff.changes,
            )
            assertEquals(setOf("Person"), diff.removedEntityTypes, "the old type reads as lost, which it is")
            assertTrue(
                diff.propertySignatureChanges.isEmpty(),
                "no old type's history is attached to a claimant: ${diff.changes}",
            )
        }

        @Test
        fun `one added type claiming two live former names pairs with neither`() {
            val old = versionOf(listOf("A", "B"))
            val new = versionOf(listOf("C"), aliases = mapOf("C" to setOf("A", "B")))

            assertEquals(
                listOf(
                    MetamodelChange.EntityTypeRemoved("A"),
                    MetamodelChange.EntityTypeRemoved("B"),
                    MetamodelChange.EntityTypeAdded("C"),
                    MetamodelChange.AmbiguousEntityTypeRename(
                        formerNames = setOf("A", "B"),
                        candidates = setOf("C"),
                    ),
                ),
                differ.diff(old, new).changes,
            )
        }

        /**
         * `A` is claimed by both, and `Y` claims `B` as well, so all four names hang together and
         * no part of the tangle can be resolved on its own. One entry names the whole group.
         */
        @Test
        fun `claims that chain together report as one group`() {
            val old = versionOf(listOf("A", "B"))
            val new = versionOf(
                listOf("X", "Y"),
                aliases = mapOf("X" to setOf("A"), "Y" to setOf("A", "B")),
            )

            assertEquals(
                listOf(
                    MetamodelChange.EntityTypeRemoved("A"),
                    MetamodelChange.EntityTypeRemoved("B"),
                    MetamodelChange.EntityTypeAdded("X"),
                    MetamodelChange.EntityTypeAdded("Y"),
                    MetamodelChange.AmbiguousEntityTypeRename(
                        formerNames = setOf("A", "B"),
                        candidates = setOf("X", "Y"),
                    ),
                ),
                differ.diff(old, new).changes,
            )
        }

        @Test
        fun `an uncontested rename beside a contested claim still pairs`() {
            val old = versionOf(listOf("A", "Person"))
            val new = versionOf(
                listOf("B", "Customer", "Employee"),
                aliases = mapOf(
                    "B" to setOf("A"),
                    "Customer" to setOf("Person"),
                    "Employee" to setOf("Person"),
                ),
            )

            assertEquals(
                listOf(
                    MetamodelChange.EntityTypeRemoved("Person"),
                    MetamodelChange.EntityTypeAdded("Customer"),
                    MetamodelChange.EntityTypeAdded("Employee"),
                    MetamodelChange.AmbiguousEntityTypeRename(
                        formerNames = setOf("Person"),
                        candidates = setOf("Customer", "Employee"),
                    ),
                    MetamodelChange.EntityTypeRenamed("A", "B"),
                ),
                differ.diff(old, new).changes,
            )
        }

        @Test
        fun `two contested groups come out ordered by their first former name`() {
            val old = versionOf(listOf("A", "P"))
            val new = versionOf(
                listOf("A1", "A2", "P1", "P2"),
                aliases = mapOf(
                    "A1" to setOf("A"),
                    "A2" to setOf("A"),
                    "P1" to setOf("P"),
                    "P2" to setOf("P"),
                ),
            )

            assertEquals(
                listOf(
                    MetamodelChange.AmbiguousEntityTypeRename(setOf("A"), setOf("A1", "A2")),
                    MetamodelChange.AmbiguousEntityTypeRename(setOf("P"), setOf("P1", "P2")),
                ),
                differ.diff(old, new).changes
                    .filterIsInstance<MetamodelChange.AmbiguousEntityTypeRename>(),
            )
        }

        /**
         * Substitution rides on a pair the differ made. Nothing paired here, so a referrer that
         * followed the move reports its reference change in full, the reading it would get with no
         * alias declared at all.
         */
        @Test
        fun `a contested claim substitutes nothing on the older side`() {
            val old = versionOf(
                listOf("Person", "Referrer"),
                properties = mapOf("Referrer" to setOf(referenceProperty("link", "Person"))),
            )
            val new = versionOf(
                listOf("Customer", "Employee", "Referrer"),
                properties = mapOf("Referrer" to setOf(referenceProperty("link", "Customer"))),
                aliases = mapOf("Customer" to setOf("Person"), "Employee" to setOf("Person")),
            )
            val diff = differ.diff(old, new)

            assertEquals(
                MetamodelChange.PropertySignatureChanged(
                    typeName = "Referrer",
                    propertyName = "link",
                    before = referenceProperty("link", "Person"),
                    after = referenceProperty("link", "Customer"),
                ),
                diff.propertySignatureChanges.single(),
            )
        }

        @Test
        fun `touchedEntityTypes carries every name in a contested claim`() {
            val diff = differ.diff(
                versionOf(listOf("Person")),
                versionOf(
                    listOf("Customer", "Employee"),
                    aliases = mapOf("Customer" to setOf("Person"), "Employee" to setOf("Person")),
                ),
            )
            assertEquals(setOf("Person", "Customer", "Employee"), diff.touchedEntityTypes)
        }

        @Test
        fun `two added signatures claiming one former name pair with neither`() {
            val old = versionOf(listOf("Person"), properties = mapOf("Person" to setOf(valueProperty("a"))))
            val new = versionOf(
                listOf("Person"),
                properties = mapOf(
                    "Person" to setOf(
                        valueProperty("b", aliases = setOf("a")),
                        valueProperty("c", aliases = setOf("a")),
                    ),
                ),
            )
            val diff = differ.diff(old, new)

            assertTrue(diff.changes.filterIsInstance<MetamodelChange.PropertyRenamed>().isEmpty())
            val modified = diff.modifiedEntityTypes.single()
            assertEquals(setOf("a"), modified.removedPropertyNames)
            assertEquals(setOf("b", "c"), modified.addedPropertyNames)
            assertEquals(
                MetamodelChange.AmbiguousPropertyRename(
                    typeName = "Person",
                    formerNames = setOf("a"),
                    candidates = setOf("b", "c"),
                ),
                diff.changes.filterIsInstance<MetamodelChange.AmbiguousPropertyRename>().single(),
            )
        }

        @Test
        fun `one added signature claiming two former names pairs with neither`() {
            val old = versionOf(
                listOf("Person"),
                properties = mapOf("Person" to setOf(valueProperty("a"), valueProperty("b"))),
            )
            val new = versionOf(
                listOf("Person"),
                properties = mapOf("Person" to setOf(valueProperty("c", aliases = setOf("a", "b")))),
            )
            val diff = differ.diff(old, new)

            assertTrue(diff.changes.filterIsInstance<MetamodelChange.PropertyRenamed>().isEmpty())
            val modified = diff.modifiedEntityTypes.single()
            assertEquals(setOf("a", "b"), modified.removedPropertyNames)
            assertEquals(setOf("c"), modified.addedPropertyNames)
            assertEquals(
                MetamodelChange.AmbiguousPropertyRename(
                    typeName = "Person",
                    formerNames = setOf("a", "b"),
                    candidates = setOf("c"),
                ),
                diff.changes.filterIsInstance<MetamodelChange.AmbiguousPropertyRename>().single(),
            )
        }

        @Test
        fun `a contested property claim sits after the paired renames on the same type`() {
            val old = versionOf(
                listOf("Person"),
                properties = mapOf("Person" to setOf(valueProperty("a"), valueProperty("x"))),
            )
            val new = versionOf(
                listOf("Person"),
                properties = mapOf(
                    "Person" to setOf(
                        valueProperty("b", aliases = setOf("a")),
                        valueProperty("c", aliases = setOf("a")),
                        valueProperty("y", aliases = setOf("x")),
                    ),
                ),
            )
            val diff = differ.diff(old, new)

            assertEquals(
                listOf("EntityTypeModified", "PropertyRenamed", "AmbiguousPropertyRename"),
                diff.changes.map { it::class.simpleName },
            )
            assertEquals("y", diff.changes.filterIsInstance<MetamodelChange.PropertyRenamed>().single().after.name)
        }

        /**
         * Exclusivity is judged among the types that are new in this version. `Keeper` survives and
         * declares `Person` as a former name too, which the stamp allows once `Person` is gone, but
         * a name arriving on a type that was already there is a merge rather than a rename
         * candidate. So it doesn't contest `Person → Customer`, and reports as an alias change on
         * `Keeper`.
         */
        @Test
        fun `a surviving type's alias on the removed name does not contest the pairing`() {
            val old = versionOf(listOf("Keeper", "Person"))
            val new = versionOf(
                listOf("Customer", "Keeper"),
                aliases = mapOf("Customer" to setOf("Person"), "Keeper" to setOf("Person")),
            )

            assertEquals(
                listOf(
                    MetamodelChange.EntityTypeRenamed("Person", "Customer"),
                    MetamodelChange.EntityTypeAliasesChanged("Keeper", emptySet(), setOf("Person")),
                ),
                differ.diff(old, new).changes,
            )
        }

        /**
         * A property name the type-merge path holds two signatures for is out of the running before
         * claims are read, so a signature naming it claims nothing there, and its other claim is
         * exclusive and pairs.
         */
        @Test
        fun `a name carrying two signatures cannot contest a claim`() {
            val old = versionOf(
                listOf("Person"),
                properties = mapOf(
                    "Person" to setOf(
                        valueProperty("age", "string"),
                        valueProperty("age", "integer"),
                        valueProperty("b"),
                    ),
                ),
            )
            val new = versionOf(
                listOf("Person"),
                properties = mapOf("Person" to setOf(valueProperty("years", aliases = setOf("age", "b")))),
            )
            val diff = differ.diff(old, new)

            assertTrue(diff.changes.filterIsInstance<MetamodelChange.AmbiguousPropertyRename>().isEmpty())
            val renamed = diff.changes.filterIsInstance<MetamodelChange.PropertyRenamed>().single()
            assertEquals("b", renamed.before.name)
            assertEquals("years", renamed.after.name)
        }

        @Test
        fun `a contested diff produces the same ordered change list every run`() {
            val old = versionOf(
                listOf("A", "B", "Holder", "Person"),
                properties = mapOf("Holder" to setOf(valueProperty("a"), valueProperty("x"))),
            )
            val new = versionOf(
                listOf("C", "Customer", "Employee", "Holder"),
                properties = mapOf(
                    "Holder" to setOf(
                        valueProperty("b", aliases = setOf("a")),
                        valueProperty("c", aliases = setOf("a")),
                        valueProperty("y", aliases = setOf("x")),
                    ),
                ),
                aliases = mapOf(
                    "C" to setOf("A", "B"),
                    "Customer" to setOf("Person"),
                    "Employee" to setOf("Person"),
                ),
            )

            val first = differ.diff(old, new).changes
            assertTrue(
                first.filterIsInstance<MetamodelChange.AmbiguousEntityTypeRename>().isNotEmpty(),
                "the fixture has to contain a contested claim: $first",
            )
            repeat(20) {
                assertEquals(first, differ.diff(old, new).changes)
                assertEquals(first, differ.diff(rebuilt(old), rebuilt(new)).changes)
            }
        }

        @Test
        fun `one former name and one candidate is a rename, and cannot be reported as contested`() {
            val thrown = assertThrows<IllegalArgumentException> {
                MetamodelChange.AmbiguousEntityTypeRename(setOf("Person"), setOf("Human"))
            }
            assertTrue(
                thrown.message!!.contains("ordinary rename"),
                "the message should say why: ${thrown.message}",
            )
        }
    }

    /**
     * A rename propagates: referrers point at the new name, children inherit the new label. Those
     * are the rename showing up again, so the before side is read modulo the renames the diff
     * already paired, and what vanishes under that substitution folds into the rename.
     */
    @Nested
    inner class ComparisonModuloRenames {

        @Test
        fun `a reference to a renamed type and an inherited label both fold into the rename`() {
            val old = versionOf(
                listOf("Employee", "Person"),
                labels = mapOf("Employee" to setOf("Employee", "Person")),
                properties = mapOf("Employee" to setOf(referenceProperty("boss", "Person"))),
            )
            val new = versionOf(
                listOf("Employee", "Human"),
                labels = mapOf("Employee" to setOf("Employee", "Human")),
                properties = mapOf("Employee" to setOf(referenceProperty("boss", "Human"))),
                aliases = mapOf("Human" to setOf("Person")),
            )
            val diff = differ.diff(old, new)

            assertEquals(listOf(MetamodelChange.EntityTypeRenamed("Person", "Human")), diff.changes)
            assertTrue(
                diff.modifiedEntityTypes.isEmpty(),
                "an EntityTypeModified emptied by substitution is not emitted: ${diff.modifiedEntityTypes}",
            )
        }

        @Test
        fun `a referrer that moved to a third type reports the residual in substituted-before form`() {
            val old = versionOf(
                listOf("A", "D", "Referrer"),
                properties = mapOf("Referrer" to setOf(referenceProperty("link", "A"))),
            )
            val new = versionOf(
                listOf("B", "D", "Referrer"),
                properties = mapOf("Referrer" to setOf(referenceProperty("link", "D"))),
                aliases = mapOf("B" to setOf("A")),
            )
            val diff = differ.diff(old, new)

            assertEquals(
                listOf("EntityTypeRenamed", "PropertySignatureChanged"),
                diff.changes.map { it::class.simpleName },
            )
            val change = diff.propertySignatureChanges.single()
            assertEquals("B", change.before.type, "the A->B half already rides in EntityTypeRenamed")
            assertEquals("D", change.after.type)
        }

        /**
         * A `VALUE` property's type is a free-text rendering of a JVM type. An entity type named
         * `Date` renaming to `Timestamp` must not rewrite every property that holds a `Date` value,
         * which is why substitution is scoped to `REFERENCE` targets.
         */
        @Test
        fun `a value type spelled like a renamed entity type is left alone`() {
            val old = versionOf(
                listOf("Date", "Person"),
                properties = mapOf(
                    "Person" to setOf(
                        valueProperty("birthday", "Date"),
                        referenceProperty("meetsOn", "Date"),
                    ),
                ),
            )
            val new = versionOf(
                listOf("Person", "Timestamp"),
                properties = mapOf(
                    "Person" to setOf(
                        valueProperty("birthday", "Date"),
                        referenceProperty("meetsOn", "Timestamp"),
                    ),
                ),
                aliases = mapOf("Timestamp" to setOf("Date")),
            )
            val diff = differ.diff(old, new)

            assertEquals(listOf(MetamodelChange.EntityTypeRenamed("Date", "Timestamp")), diff.changes)
            assertFalse(diff.touchedEntityTypes.contains("Person"), "nothing on Person moved")
        }
    }

    /**
     * Aliases are part of the hash, so declaring or retiring one has to surface as a change for an
     * empty diff and an equal content hash to keep meaning the same thing.
     */
    @Nested
    inner class AliasOnlyChanges {

        @Test
        fun `a property whose aliases alone changed is an ordinary signature change`() {
            val old = versionOf(listOf("Person"), properties = mapOf("Person" to setOf(valueProperty("age", "integer"))))
            val new = versionOf(
                listOf("Person"),
                properties = mapOf("Person" to setOf(valueProperty("age", "integer", aliases = setOf("yearsOld")))),
            )
            val diff = differ.diff(old, new)

            val change = diff.propertySignatureChanges.single()
            assertEquals("age", change.propertyName)
            assertEquals(emptySet<String>(), change.before.aliases)
            assertEquals(setOf("yearsOld"), change.after.aliases)
            assertFalse(change.typeChanged)
            assertFalse(change.cardinalityChanged)
            assertFalse(change.kindChanged)
        }

        @Test
        fun `a type whose aliases alone changed is an EntityTypeAliasesChanged`() {
            val old = versionOf(listOf("Human"), aliases = mapOf("Human" to setOf("Person")))
            val new = versionOf(listOf("Human"), aliases = mapOf("Human" to setOf("Person", "Individual")))
            val diff = differ.diff(old, new)

            val change = diff.changes.filterIsInstance<MetamodelChange.EntityTypeAliasesChanged>().single()
            assertEquals("Human", change.typeName)
            assertEquals(setOf("Person"), change.before)
            assertEquals(setOf("Individual", "Person"), change.after)
            assertEquals(1, diff.changes.size, "nothing about the data moved: ${diff.changes}")
        }

        @Test
        fun `a retired type alias is reported the same way`() {
            val old = versionOf(listOf("Human"), aliases = mapOf("Human" to setOf("Person")))
            val new = versionOf(listOf("Human"))
            val change = differ.diff(old, new).changes
                .filterIsInstance<MetamodelChange.EntityTypeAliasesChanged>()
                .single()
            assertEquals(setOf("Person"), change.before)
            assertTrue(change.after.isEmpty())
        }

        @Test
        fun `a pure rename emits no alias change beside it`() {
            val diff = differ.diff(
                versionOf(listOf("Person")),
                versionOf(listOf("Human"), aliases = mapOf("Human" to setOf("Person"))),
            )
            assertTrue(
                diff.changes.filterIsInstance<MetamodelChange.EntityTypeAliasesChanged>().isEmpty(),
                "the rename's own alias entry rides in EntityTypeRenamed: ${diff.changes}",
            )
        }

        @Test
        fun `a rename that also declares an unrelated former name reports both`() {
            val old = versionOf(listOf("Person"))
            val new = versionOf(listOf("Human"), aliases = mapOf("Human" to setOf("Person", "Individual")))
            val diff = differ.diff(old, new)

            assertEquals(
                listOf("EntityTypeRenamed", "EntityTypeAliasesChanged"),
                diff.changes.map { it::class.simpleName },
            )
        }

        @Test
        fun `an alias carried through a rename is not reported as an alias change`() {
            val old = versionOf(listOf("B"), aliases = mapOf("B" to setOf("A")))
            val new = versionOf(listOf("C"), aliases = mapOf("C" to setOf("A", "B")))
            assertEquals(listOf(MetamodelChange.EntityTypeRenamed("B", "C")), differ.diff(old, new).changes)
        }

        @Test
        fun `equal content hashes still mean an empty diff when both kinds of alias are declared`() {
            val old = aliasedStamp()
            val new = aliasedStamp()
            assertTrue(old.hasSameContentAs(new))
            assertTrue(differ.diff(old, new).isEmpty, "equal hash, empty diff: ${differ.diff(old, new).changes}")
        }

        @Test
        fun `an alias-only difference of either kind moves the hash and produces a change`() {
            val base = aliasedStamp()

            val typeAliasMoved = aliasedStamp(typeAliases = setOf("Person", "Individual"))
            assertFalse(base.hasSameContentAs(typeAliasMoved))
            assertFalse(differ.diff(base, typeAliasMoved).isEmpty)

            val propertyAliasMoved = aliasedStamp(propertyAliases = setOf("yearsOld", "howOld"))
            assertFalse(base.hasSameContentAs(propertyAliasMoved))
            assertFalse(differ.diff(base, propertyAliasMoved).isEmpty)
        }

        /** A stamp carrying both kinds of alias, so a rebuild of it hashes and diffs identically. */
        private fun aliasedStamp(
            typeAliases: Set<String> = setOf("Person"),
            propertyAliases: Set<String> = setOf("yearsOld"),
        ): MetamodelVersion = versionOf(
            listOf("Human"),
            properties = mapOf("Human" to setOf(valueProperty("age", "integer", aliases = propertyAliases))),
            aliases = mapOf("Human" to typeAliases),
        )
    }

    @Nested
    inner class RenamesAgainstObserved {

        private val declared = DeclaredSchema(
            version = versionOf(listOf("Human"), aliases = mapOf("Human" to setOf("Person"))),
            relationshipTypeNames = emptySet(),
        )

        private fun observed(vararg entityTypeNames: String): ObservedSchema = ObservedSchema(
            entityTypeNames = entityTypeNames.toSet(),
            relationshipTypeNames = emptySet(),
            capturedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

        @Test
        fun `data still labelled with a renamed type's old name is not drift`() {
            val diff = declaredObservedDiffer.diffAgainstObserved(declared, observed("Human", "Person"))
            assertFalse(diff.hasDrift, "the rename was declared, so the old label is known: ${diff.driftedEntityTypes}")
            assertTrue(diff.driftedEntityTypes.isEmpty())
        }

        @Test
        fun `a label matching neither a declared type nor a declared former name is still drift`() {
            val diff = declaredObservedDiffer.diffAgainstObserved(declared, observed("Human", "GhostIntegrationType"))
            assertTrue(diff.hasDrift)
            assertEquals(setOf("GhostIntegrationType"), diff.driftedEntityTypes)
        }

        /**
         * A declared former name is a declared name, so it carries its own label the same way a
         * live one does. Rename `com.example.Person` to `com.example.Human` and the nodes written
         * before the rename still come back labelled `Person`, which the declaration explains.
         */
        @Test
        fun `a qualified former name is known by its label too`() {
            val renamed = DeclaredSchema(
                version = versionOf(
                    listOf("com.example.Human"),
                    aliases = mapOf("com.example.Human" to setOf("com.example.Person")),
                ),
                relationshipTypeNames = emptySet(),
            )

            val diff = declaredObservedDiffer.diffAgainstObserved(renamed, observed("Human", "Person"))

            assertFalse(diff.hasDrift, "the rename was declared: ${diff.driftedEntityTypes}")
            assertTrue(diff.unobservedEntityTypes.isEmpty())

            val ghosted = declaredObservedDiffer.diffAgainstObserved(renamed, observed("Human", "Ghost"))
            assertEquals(setOf("Ghost"), ghosted.driftedEntityTypes)
        }

        @Test
        fun `a former name is not reported as an unobserved type`() {
            val diff = declaredObservedDiffer.diffAgainstObserved(declared, observed("Human"))
            assertTrue(
                diff.unobservedEntityTypes.isEmpty(),
                "a former name was never a type of its own: ${diff.unobservedEntityTypes}",
            )
        }

        @Test
        fun `a type whose data is still labelled with its former name is observed`() {
            val diff = declaredObservedDiffer.diffAgainstObserved(declared, observed("Person"))
            assertFalse(diff.hasDrift, "the rename was declared, so the old label is known: ${diff.driftedEntityTypes}")
            assertTrue(
                diff.unobservedEntityTypes.isEmpty(),
                "data under the declared former name is Human being seen, not a Human nobody wrote: ${diff.unobservedEntityTypes}",
            )
        }

        @Test
        fun `a qualified former name observed by its label counts as observed too`() {
            val renamed = DeclaredSchema(
                version = versionOf(
                    listOf("com.example.Human"),
                    aliases = mapOf("com.example.Human" to setOf("com.example.Person")),
                ),
                relationshipTypeNames = emptySet(),
            )

            val diff = declaredObservedDiffer.diffAgainstObserved(renamed, observed("Person"))

            assertFalse(diff.hasDrift, "the rename was declared: ${diff.driftedEntityTypes}")
            assertTrue(diff.unobservedEntityTypes.isEmpty())
        }

        @Test
        fun `a type with no data under any of its names is still unobserved`() {
            val diff = declaredObservedDiffer.diffAgainstObserved(declared, observed("Ghost"))
            assertEquals(setOf("Human"), diff.unobservedEntityTypes)
            assertEquals(setOf("Ghost"), diff.driftedEntityTypes)
        }
    }

    /**
     * The convenience accessors on [MetamodelDiff]. A caller reading one change kind should not have
     * to write `filterIsInstance`, and every kind the differ can emit needs one, so a caller
     * counting a diff through the accessors accounts for all of it.
     */
    @Nested
    inner class Accessors {

        @Test
        fun `ambiguousEntityTypeRenames carries every contested type claim`() {
            val diff = differ.diff(
                versionOf(listOf("Person")),
                versionOf(
                    listOf("Customer", "Employee"),
                    aliases = mapOf("Customer" to setOf("Person"), "Employee" to setOf("Person")),
                ),
            )

            assertEquals(
                listOf(
                    MetamodelChange.AmbiguousEntityTypeRename(
                        formerNames = setOf("Person"),
                        candidates = setOf("Customer", "Employee"),
                    ),
                ),
                diff.ambiguousEntityTypeRenames,
            )
            assertTrue(diff.ambiguousPropertyRenames.isEmpty(), "a type claim is not a property claim")
        }

        @Test
        fun `ambiguousPropertyRenames carries every contested property claim`() {
            val diff = differ.diff(
                versionOf(listOf("Person"), properties = mapOf("Person" to setOf(valueProperty("a")))),
                versionOf(
                    listOf("Person"),
                    properties = mapOf(
                        "Person" to setOf(
                            valueProperty("b", aliases = setOf("a")),
                            valueProperty("c", aliases = setOf("a")),
                        ),
                    ),
                ),
            )

            assertEquals(
                listOf(
                    MetamodelChange.AmbiguousPropertyRename(
                        typeName = "Person",
                        formerNames = setOf("a"),
                        candidates = setOf("b", "c"),
                    ),
                ),
                diff.ambiguousPropertyRenames,
            )
            assertTrue(diff.ambiguousEntityTypeRenames.isEmpty(), "a property claim is not a type claim")
        }

        /**
         * The fixture holds at least one change of every kind these accessors cover — contested
         * claims and relationships included — so the count through the accessors has to come out at
         * the length of the change list, and dropping any one accessor from the sum breaks it.
         *
         * It deliberately holds no `EntityTypeRenamed`, `EntityTypeAliasesChanged` or
         * `PropertyRenamed`, the three kinds still read through `filterIsInstance` here. Their
         * accessors arrive with the drift slice, and this test grows to cover them then.
         */
        @Test
        fun `each accessor sees only its own kind, and together they cover the change list`() {
            val diff = differ.diff(
                versionOf(
                    listOf("Gone", "Holder", "Person", "Stable"),
                    properties = mapOf(
                        "Holder" to setOf(valueProperty("a"), valueProperty("age", "string")),
                    ),
                    relationships = listOf("Stable-[knew]->Gone"),
                ),
                versionOf(
                    listOf("Customer", "Employee", "Holder", "New", "Stable"),
                    properties = mapOf(
                        "Holder" to setOf(
                            valueProperty("b", aliases = setOf("a")),
                            valueProperty("c", aliases = setOf("a")),
                            valueProperty("age", "integer"),
                        ),
                    ),
                    aliases = mapOf("Customer" to setOf("Person"), "Employee" to setOf("Person")),
                    relationships = listOf("Stable-[knows]->New"),
                ),
            )

            assertEquals(setOf("Gone", "Person"), diff.removedEntityTypes)
            assertEquals(setOf("Customer", "Employee", "New"), diff.addedEntityTypes)
            assertEquals(1, diff.ambiguousEntityTypeRenames.size)
            assertEquals(1, diff.modifiedEntityTypes.size)
            assertEquals(1, diff.ambiguousPropertyRenames.size)
            assertEquals(1, diff.propertySignatureChanges.size)
            assertEquals(setOf("Stable-[knows]->New"), diff.addedRelationships)
            assertEquals(setOf("Stable-[knew]->Gone"), diff.removedRelationships)

            val throughAccessors = diff.removedEntityTypes.size + diff.addedEntityTypes.size +
                diff.ambiguousEntityTypeRenames.size + diff.modifiedEntityTypes.size +
                diff.ambiguousPropertyRenames.size + diff.propertySignatureChanges.size +
                diff.addedRelationships.size + diff.removedRelationships.size
            assertEquals(11, diff.changes.size, "the fixture should exercise every covered kind: ${diff.changes}")
            assertEquals(diff.changes.size, throughAccessors, "the accessors should partition ${diff.changes}")
        }

        @Test
        fun `an empty diff gives every accessor an empty answer`() {
            val diff = differ.diff(versionOf(listOf("Person")), versionOf(listOf("Person")))

            assertTrue(diff.isEmpty)
            assertTrue(diff.removedEntityTypes.isEmpty())
            assertTrue(diff.addedEntityTypes.isEmpty())
            assertTrue(diff.modifiedEntityTypes.isEmpty())
            assertTrue(diff.propertySignatureChanges.isEmpty())
            assertTrue(diff.ambiguousEntityTypeRenames.isEmpty())
            assertTrue(diff.ambiguousPropertyRenames.isEmpty())
            assertTrue(diff.addedRelationships.isEmpty())
            assertTrue(diff.removedRelationships.isEmpty())
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
        fun `the alias sets inside an alias change cannot be mutated`() {
            val declaredNames = mutableSetOf("Person")
            val change = MetamodelChange.EntityTypeAliasesChanged("Human", declaredNames, setOf("Person", "Individual"))

            declaredNames += "Sneaky"
            assertEquals(setOf("Person"), change.before)

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (change.before as MutableSet<String>).add("Sneaky")
            }

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (change.after as MutableSet<String>).clear()
            }
        }

        @Test
        fun `the name sets inside a contested-rename change cannot be mutated`() {
            val claimants = mutableSetOf("Customer", "Employee")
            val typeChange = MetamodelChange.AmbiguousEntityTypeRename(setOf("Person"), claimants)
            val propertyChange = MetamodelChange.AmbiguousPropertyRename("Person", setOf("a"), mutableSetOf("b", "c"))

            claimants += "Sneaky"
            assertEquals(setOf("Customer", "Employee"), typeChange.candidates)

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (typeChange.formerNames as MutableSet<String>).add("Sneaky")
            }

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (typeChange.candidates as MutableSet<String>).clear()
            }

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (propertyChange.formerNames as MutableSet<String>).add("Sneaky")
            }

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (propertyChange.candidates as MutableSet<String>).clear()
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

        /**
         * A stamp assembled field by field, for the rename cases: declared former names, references
         * between types, and label sets that carry a parent. A type's own name is one of its labels
         * unless [labels] says otherwise, which is what a real stamp holds and what makes the
         * rename's own-name label swap show up.
         */
        private fun versionOf(
            types: List<String>,
            labels: Map<String, Set<String>> = emptyMap(),
            properties: Map<String, Set<PropertySignature>> = emptyMap(),
            aliases: Map<String, Set<String>> = emptyMap(),
            relationships: List<String> = emptyList(),
        ): MetamodelVersion = MetamodelVersion(
            schemaName = "test",
            entityTypeNames = types,
            entityTypeLabels = types.associateWith { labels[it] ?: setOf(it) },
            entityTypeProperties = types.associateWith { properties[it].orEmpty() },
            relationshipNames = relationships,
            entityTypeAliases = aliases,
        )

        /** A structurally identical rebuild, so a determinism check doesn't ride on instance reuse. */
        private fun rebuilt(version: MetamodelVersion): MetamodelVersion = MetamodelVersion(
            schemaName = version.schemaName,
            entityTypeNames = version.entityTypeNames,
            entityTypeLabels = version.entityTypeLabels,
            entityTypeProperties = version.entityTypeProperties,
            relationshipNames = version.relationshipNames,
            entityTypeAliases = version.entityTypeAliases,
        )

        private fun valueProperty(
            name: String,
            type: String = "string",
            cardinality: Cardinality = Cardinality.ONE,
            aliases: Set<String> = emptySet(),
        ): PropertySignature = PropertySignature(name, PropertySignature.Kind.VALUE, type, cardinality, aliases)

        private fun referenceProperty(
            name: String,
            target: String,
            cardinality: Cardinality = Cardinality.ONE,
            aliases: Set<String> = emptySet(),
        ): PropertySignature = PropertySignature(name, PropertySignature.Kind.REFERENCE, target, cardinality, aliases)
    }
}

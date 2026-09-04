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
import com.embabel.agent.core.DomainTypePropertyDefinition
import com.embabel.agent.core.DynamicType
import com.embabel.agent.core.ValuePropertyDefinition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DeclaredSchemaTest {

    private val company = DynamicType("Company")
    private val person = DynamicType(
        name = "Person",
        ownProperties = listOf(DomainTypePropertyDefinition("worksAt", company)),
    )
    private val sighting = DynamicType(
        name = "Sighting",
        ownProperties = listOf(DomainTypePropertyDefinition("about", person)),
    )

    private fun dictionary() = DataDictionary.fromDomainTypes("app", listOf(person, company, sighting))

    @Test
    fun `declaring everything carries every relationship name`() {
        val declared = DeclaredSchema.from(dictionary())

        assertEquals(setOf("worksAt", "about"), declared.relationshipTypeNames)
        assertEquals(MetamodelVersion.from(dictionary()), declared.version)
    }

    @Test
    fun `bare relationship names follow governance, so they can't disagree with the stamp`() {
        // `about` is declared by the ungoverned Sighting, so it is out of both halves. Taking the
        // stamp from a governed subset while taking the names from the whole dictionary would
        // declare a relationship the stamp never covered.
        val declared = DeclaredSchema.from(dictionary(), GovernedTypeSelector { it.name in setOf("Person", "Company") })

        assertEquals(setOf("worksAt"), declared.relationshipTypeNames)
        assertEquals(listOf("Person-[worksAt]->Company"), declared.version.relationshipNames)
        assertEquals(listOf("Company", "Person"), declared.version.entityTypeNames)
    }

    @Test
    fun `ungoverned types and their relationships are carried alongside the governed stamp`() {
        // Sighting declares `about`, so leaving Sighting ungoverned takes its relationship with it.
        val declared = DeclaredSchema.from(dictionary(), GovernedTypeSelector { it.name in setOf("Person", "Company") })

        assertEquals(setOf("Sighting"), declared.ungovernedEntityTypeNames)
        assertEquals(setOf("about"), declared.ungovernedRelationshipTypeNames)
    }

    @Test
    fun `a selector governing everything leaves nothing ungoverned`() {
        val declared = DeclaredSchema.from(dictionary())

        assertTrue(declared.ungovernedEntityTypeNames.isEmpty())
        assertTrue(declared.ungovernedRelationshipTypeNames.isEmpty())
    }

    @Test
    fun `a selector governing nothing leaves every dictionary type ungoverned`() {
        val declared = DeclaredSchema.from(dictionary(), GovernedTypeSelector { false })

        assertEquals(setOf("Person", "Company", "Sighting"), declared.ungovernedEntityTypeNames)
        assertEquals(setOf("worksAt", "about"), declared.ungovernedRelationshipTypeNames)
        assertTrue(declared.version.entityTypeNames.isEmpty(), "the stamp itself must cover nothing")
        assertTrue(declared.relationshipTypeNames.isEmpty())
    }

    /**
     * A JVM-backed type is declared by its class name, and the graph reports the last segment of it.
     * The own label is that segment, so the two sides of a drift check have a name in common.
     */
    @Test
    fun `a fully qualified declared name yields its simple own label`() {
        val declared = DeclaredSchema(
            version = MetamodelVersion(
                schemaName = "app",
                entityTypeNames = listOf("com.example.Person"),
                entityTypeLabels = mapOf("com.example.Person" to setOf("Person")),
                entityTypeProperties = mapOf("com.example.Person" to emptySet()),
                relationshipNames = emptyList(),
            ),
            relationshipTypeNames = emptySet(),
        )

        assertEquals(setOf("Person"), declared.entityTypeOwnLabels)
        assertEquals(listOf("com.example.Person"), declared.version.entityTypeNames)
    }

    @Test
    fun `an undotted declared name is its own label`() {
        val declared = DeclaredSchema.from(dictionary())

        assertEquals(setOf("Person", "Company", "Sighting"), declared.entityTypeOwnLabels)
    }

    /**
     * Two packages, one simple name. The set holds `Person` once, which is all a graph can hold:
     * a label carries no package, so a node written under either type comes back the same way.
     * A drift check therefore accepts an observed `Person` for both, and the report says so.
     */
    @Test
    fun `two declared names sharing a simple name collapse to one own label`() {
        val declared = DeclaredSchema(
            version = MetamodelVersion(
                schemaName = "app",
                entityTypeNames = listOf("com.example.Person", "com.other.Person"),
                entityTypeLabels = mapOf(
                    "com.example.Person" to setOf("Person"),
                    "com.other.Person" to setOf("Person"),
                ),
                entityTypeProperties = mapOf(
                    "com.example.Person" to emptySet(),
                    "com.other.Person" to emptySet(),
                ),
                relationshipNames = emptyList(),
            ),
            relationshipTypeNames = emptySet(),
        )

        assertEquals(setOf("Person"), declared.entityTypeOwnLabels)
        assertEquals(2, declared.version.entityTypeNames.size, "both types stay declared in full")
    }

    @Test
    fun `a name ending in a dot stands as its own label`() {
        // Nothing follows the dot, and an empty label would match every other name shaped this way.
        assertEquals("com.example.", DeclaredSchema.ownLabelOf("com.example."))
        assertEquals("Person", DeclaredSchema.ownLabelOf("com.example.Person"))
        assertEquals("Person", DeclaredSchema.ownLabelOf("Person"))
    }

    @Test
    fun `own labels cannot be mutated through the getter`() {
        val declared = DeclaredSchema.from(dictionary())

        @Suppress("UNCHECKED_CAST")
        assertThrows<UnsupportedOperationException> {
            (declared.entityTypeOwnLabels as MutableSet<String>).add("Sneaky")
        }
    }

    @Test
    fun `a source is just a supplier of the declaration`() {
        val source = DeclaredSchemaSource { DeclaredSchema.from(dictionary()) }

        assertEquals(DeclaredSchema.from(dictionary()), source.declare())
    }

    @Test
    fun `declaring no aliases matches declaring none explicitly`() {
        assertEquals(
            DeclaredSchema.from(dictionary()).version.contentHash,
            DeclaredSchema.from(dictionary(), GovernedTypeSelector.ALL, SchemaAliases.NONE).version.contentHash,
        )
    }

    @Test
    fun `declared aliases reach the stamp`() {
        val declared = DeclaredSchema.from(
            dictionary(),
            GovernedTypeSelector.ALL,
            SchemaAliases(
                typeAliases = mapOf("Person" to setOf("Human")),
                propertyAliases = mapOf("Person" to mapOf("worksAt" to setOf("employer"))),
            ),
        )

        assertEquals(setOf("Human"), declared.version.entityTypeAliases["Person"])
        assertEquals(
            setOf("employer"),
            declared.version.entityTypeProperties["Person"]!!.single { it.name == "worksAt" }.aliases,
        )
        assertNotEquals(DeclaredSchema.from(dictionary()).version.contentHash, declared.version.contentHash)
    }

    @Test
    fun `a type alias naming another declared type is refused`() {
        val thrown = assertThrows<IllegalArgumentException> {
            DeclaredSchema.from(
                dictionary(),
                GovernedTypeSelector.ALL,
                SchemaAliases(typeAliases = mapOf("Person" to setOf("Company"))),
            )
        }
        assertTrue(thrown.message!!.contains("Company"), thrown.message)
    }

    @Test
    fun `aliases on a property name the merge holds two signatures for are refused`() {
        // Two same-named Person declarations each carry their own `age`, so the union holds two
        // signatures and an old name can't say which of them it meant.
        val duplicated = DataDictionary.fromDomainTypes(
            "app",
            listOf(
                DynamicType(name = "Person", ownProperties = listOf(ValuePropertyDefinition("age", type = "string"))),
                DynamicType(name = "Person", ownProperties = listOf(ValuePropertyDefinition("age", type = "integer"))),
            ),
        )

        val thrown = assertThrows<IllegalArgumentException> {
            DeclaredSchema.from(
                duplicated,
                GovernedTypeSelector.ALL,
                SchemaAliases(propertyAliases = mapOf("Person" to mapOf("age" to setOf("years")))),
            )
        }
        assertTrue(thrown.message!!.contains("years"), thrown.message)
    }
}

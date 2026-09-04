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

class MetamodelStampingTest {

    private val company = DynamicType("Company")
    private val person = DynamicType(
        name = "Person",
        ownProperties = listOf(
            DomainTypePropertyDefinition("worksAt", company),
            ValuePropertyDefinition("emailAddress", "string"),
        ),
    )
    private val sighting = DynamicType(
        name = "Sighting",
        ownProperties = listOf(DomainTypePropertyDefinition("about", person)),
    )

    private val governed = setOf("Person", "Company")

    private fun dictionary() = DataDictionary.fromDomainTypes("app", listOf(person, company, sighting))

    private fun aliases() = SchemaAliases(
        propertyAliases = mapOf("Person" to mapOf("emailAddress" to setOf("email"))),
    )

    @Test
    fun `a bare stamping governs everything and declares no former names`() {
        val stamping = MetamodelVersion.stamping(dictionary())

        assertEquals(GovernedTypeSelector.ALL, stamping.governedTypes)
        assertEquals(SchemaAliases.NONE, stamping.aliases)
        assertEquals(MetamodelVersion.from(dictionary()), stamping.stamp())
    }

    @Test
    fun `the chain lands on the same stamp as the three-argument factory`() {
        val selector = GovernedTypeSelector { it.name in governed }

        val chained = MetamodelVersion.stamping(dictionary())
            .governedBy(selector)
            .withAliases(aliases())
            .stamp()

        assertEquals(MetamodelVersion.from(dictionary(), selector, aliases()), chained)
    }

    @Test
    fun `governing by name matches on the type's name`() {
        val byName = MetamodelVersion.stamping(dictionary()).governedBy(governed).stamp()

        assertEquals(listOf("Company", "Person"), byName.entityTypeNames)
        assertEquals(
            MetamodelVersion.from(dictionary(), GovernedTypeSelector { it.name in governed }),
            byName,
        )
    }

    @Test
    fun `a name no type carries governs nothing and is not an error`() {
        val stamp = MetamodelVersion.stamping(dictionary()).governedBy(setOf("Absent")).stamp()

        assertEquals(emptyList<String>(), stamp.entityTypeNames)
    }

    @Test
    fun `every step leaves the stamping it was called on alone`() {
        val base = MetamodelVersion.stamping(dictionary()).governedBy(governed)

        val withAliases = base.withAliases(aliases())

        assertEquals(SchemaAliases.NONE, base.aliases)
        assertEquals(aliases(), withAliases.aliases)
        assertNotEquals(base.stamp().contentHash, withAliases.stamp().contentHash)
    }

    @Test
    fun `a half-built stamping can be finished more than once`() {
        val base = MetamodelVersion.stamping(dictionary()).governedBy(governed)

        assertEquals(base.stamp(), base.stamp())
        assertEquals(MetamodelVersion.from(dictionary(), GovernedTypeSelector { it.name in governed }), base.stamp())
    }

    @Test
    fun `the last call of a step wins`() {
        val stamping = MetamodelVersion.stamping(dictionary())
            .governedBy(setOf("Person"))
            .governedBy(governed)

        assertEquals(listOf("Company", "Person"), stamping.stamp().entityTypeNames)
    }

    @Test
    fun `declaring lands on the same declaration as the three-argument factory`() {
        val selector = GovernedTypeSelector { it.name in governed }

        val chained = MetamodelVersion.stamping(dictionary())
            .governedBy(selector)
            .withAliases(aliases())
            .declare()

        assertEquals(DeclaredSchema.from(dictionary(), selector, aliases()), chained)
        assertEquals(setOf("worksAt"), chained.relationshipTypeNames)
    }

    @Test
    fun `a declaration the aliases don't fit fails where the stamp is built, not while chaining`() {
        val undeclarable = SchemaAliases(typeAliases = mapOf("Person" to setOf("Company")))

        // Chaining it is fine. The rule about a type name appearing in another type's alias set
        // belongs to the factory, so it fires on the terminal call.
        val stamping = MetamodelVersion.stamping(dictionary()).withAliases(undeclarable)

        assertThrows<IllegalArgumentException> { stamping.stamp() }
        assertThrows<IllegalArgumentException> { stamping.declare() }
    }
}

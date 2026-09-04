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

class MetamodelDslTest {

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

    @Test
    fun `an empty block stamps the whole dictionary`() {
        assertEquals(MetamodelVersion.from(dictionary()), MetamodelVersion(dictionary()) {})
        assertEquals(MetamodelVersion.from(dictionary()), MetamodelVersion(dictionary()))
    }

    @Test
    fun `governing by name lands on the same stamp as the selector form`() {
        val fromDsl = MetamodelVersion(dictionary()) {
            governedBy("Person", "Company")
        }

        assertEquals(MetamodelVersion.from(dictionary(), GovernedTypeSelector { it.name in governed }), fromDsl)
        assertEquals(listOf("Company", "Person"), fromDsl.entityTypeNames)
    }

    @Test
    fun `a set and a selector reach the same stamp as the names`() {
        val bySet = MetamodelVersion(dictionary()) { governedBy(governed) }
        val bySelector = MetamodelVersion(dictionary()) { governedBy(GovernedTypeSelector { it.name in governed }) }

        assertEquals(bySet, bySelector)
        assertEquals(bySet, MetamodelVersion(dictionary()) { governedBy("Person", "Company") })
    }

    @Test
    fun `aliases declared in the block reach the stamp`() {
        val fromDsl = MetamodelVersion(dictionary()) {
            governedBy(governed)
            aliases {
                type("Person", formerly = setOf("Human"))
                property("Person", "emailAddress", formerly = setOf("email"))
            }
        }

        val expected = SchemaAliases(
            typeAliases = mapOf("Person" to setOf("Human")),
            propertyAliases = mapOf("Person" to mapOf("emailAddress" to setOf("email"))),
        )
        assertEquals(
            MetamodelVersion.from(dictionary(), GovernedTypeSelector { it.name in governed }, expected),
            fromDsl,
        )
        assertEquals(mapOf("Person" to setOf("Human")), fromDsl.entityTypeAliases)
    }

    @Test
    fun `declaring the same name twice accumulates, so a rename chain keeps both`() {
        val fromDsl = MetamodelVersion(dictionary()) {
            governedBy(governed)
            aliases {
                type("Person", formerly = setOf("Human"))
                type("Person", formerly = setOf("Individual"))
            }
        }

        assertEquals(setOf("Human", "Individual"), fromDsl.entityTypeAliases["Person"])
    }

    @Test
    fun `a prebuilt SchemaAliases works the same as the block`() {
        val prebuilt = SchemaAliases(typeAliases = mapOf("Person" to setOf("Human")))

        val fromValue = MetamodelVersion(dictionary()) {
            governedBy(governed)
            aliases(prebuilt)
        }
        val fromBlock = MetamodelVersion(dictionary()) {
            governedBy(governed)
            aliases { type("Person", formerly = setOf("Human")) }
        }

        assertEquals(fromValue, fromBlock)
    }

    @Test
    fun `the last call of a step wins`() {
        val fromDsl = MetamodelVersion(dictionary()) {
            governedBy("Person")
            governedBy(governed)
        }

        assertEquals(listOf("Company", "Person"), fromDsl.entityTypeNames)
    }

    @Test
    fun `declaredSchema takes both halves from the one block`() {
        val declared = DeclaredSchema(dictionary()) {
            governedBy(governed)
        }

        assertEquals(DeclaredSchema.from(dictionary(), GovernedTypeSelector { it.name in governed }), declared)
        assertEquals(setOf("worksAt"), declared.relationshipTypeNames)
    }

    @Test
    fun `the block and the chain reach the same stamp`() {
        val aliases = SchemaAliases(propertyAliases = mapOf("Person" to mapOf("emailAddress" to setOf("email"))))

        val fromDsl = MetamodelVersion(dictionary()) {
            governedBy(governed)
            aliases(aliases)
        }
        val fromChain = MetamodelVersion.stamping(dictionary())
            .governedBy(governed)
            .withAliases(aliases)
            .stamp()

        assertEquals(fromChain, fromDsl)
    }

    @Test
    fun `aliases that don't fit the governed types fail when the block returns`() {
        assertThrows<IllegalArgumentException> {
            MetamodelVersion(dictionary()) {
                aliases { type("Person", formerly = setOf("Company")) }
            }
        }
        assertThrows<IllegalArgumentException> {
            DeclaredSchema(dictionary()) {
                aliases { type("Person", formerly = setOf("Company")) }
            }
        }
    }

    @Test
    fun `a governed name no type carries governs nothing`() {
        assertEquals(emptyList<String>(), MetamodelVersion(dictionary()) { governedBy("Absent") }.entityTypeNames)
    }
}

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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

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
        // stamp from a subset but the names from the whole dictionary would declare a relationship
        // the stamp never covered.
        val declared = DeclaredSchema.from(dictionary(), GovernedTypeSelector { it.name in setOf("Person", "Company") })

        assertEquals(setOf("worksAt"), declared.relationshipTypeNames)
        assertEquals(listOf("Person-[worksAt]->Company"), declared.version.relationshipNames)
        assertEquals(listOf("Company", "Person"), declared.version.entityTypeNames)
    }

    @Test
    fun `a source is just a supplier of the declaration`() {
        val source = DeclaredSchemaSource { DeclaredSchema.from(dictionary()) }

        assertEquals(DeclaredSchema.from(dictionary()), source.declare())
    }
}

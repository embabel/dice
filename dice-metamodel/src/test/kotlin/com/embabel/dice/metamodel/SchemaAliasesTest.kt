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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SchemaAliasesTest {

    @Test
    fun `NONE declares nothing`() {
        assertEquals(emptyMap<String, Set<String>>(), SchemaAliases.NONE.typeAliases)
        assertEquals(emptyMap<String, Map<String, Set<String>>>(), SchemaAliases.NONE.propertyAliases)
        assertEquals(SchemaAliases(), SchemaAliases.NONE)
    }

    @Test
    fun `an empty type alias set is dropped`() {
        // Saying a type has no former names is the same as saying nothing about it, and an empty
        // entry would otherwise be refused by the stamp's guard.
        val aliases = SchemaAliases(typeAliases = mapOf("Person" to emptySet(), "Company" to setOf("Corp")))

        assertEquals(mapOf("Company" to setOf("Corp")), aliases.typeAliases)
    }

    @Test
    fun `an empty property alias set is dropped, and a type left with none goes with it`() {
        val aliases = SchemaAliases(
            propertyAliases = mapOf(
                "Person" to mapOf("age" to emptySet(), "emailAddress" to setOf("email")),
                "Company" to mapOf("name" to emptySet()),
            ),
        )

        assertEquals(mapOf("Person" to mapOf("emailAddress" to setOf("email"))), aliases.propertyAliases)
    }

    @Test
    fun `propertyAliasesFor answers empty for anything undeclared`() {
        val aliases = SchemaAliases(
            propertyAliases = mapOf("Person" to mapOf("emailAddress" to setOf("email"))),
        )

        assertEquals(setOf("email"), aliases.propertyAliasesFor("Person", "emailAddress"))
        assertEquals(emptySet<String>(), aliases.propertyAliasesFor("Person", "age"))
        assertEquals(emptySet<String>(), aliases.propertyAliasesFor("Company", "emailAddress"))
    }

    @Test
    fun `alias names are case-sensitive`() {
        // LLM extraction drifts on case. Folding it here would pair two names nobody said were the
        // same one.
        val aliases = SchemaAliases(typeAliases = mapOf("Person" to setOf("Human")))

        assertNotEquals(aliases, SchemaAliases(typeAliases = mapOf("Person" to setOf("human"))))
        assertNotEquals(aliases, SchemaAliases(typeAliases = mapOf("person" to setOf("Human"))))
    }

    @Test
    fun `mutating what the caller passed in does not change the declaration`() {
        val types = mutableMapOf("Person" to mutableSetOf("Human"))
        val properties = mutableMapOf("Person" to mutableMapOf("emailAddress" to mutableSetOf("email")))

        val aliases = SchemaAliases(types, properties)

        types["Ghost"] = mutableSetOf("Spectre")
        types["Person"]!!.add("Actor")
        properties["Person"]!!["age"] = mutableSetOf("years")
        properties["Person"]!!["emailAddress"]!!.add("contact")

        assertEquals(mapOf("Person" to setOf("Human")), aliases.typeAliases)
        assertEquals(mapOf("Person" to mapOf("emailAddress" to setOf("email"))), aliases.propertyAliases)
    }

    @Test
    fun `the collections a declaration hands back cannot be mutated`() {
        val aliases = SchemaAliases(
            typeAliases = mapOf("Person" to setOf("Human")),
            propertyAliases = mapOf("Person" to mapOf("emailAddress" to setOf("email"))),
        )

        @Suppress("UNCHECKED_CAST")
        assertThrows<UnsupportedOperationException> {
            (aliases.typeAliases as MutableMap<String, Set<String>>).remove("Person")
        }

        @Suppress("UNCHECKED_CAST")
        assertThrows<UnsupportedOperationException> {
            (aliases.typeAliases["Person"] as MutableSet<String>).add("Sneaky")
        }

        @Suppress("UNCHECKED_CAST")
        assertThrows<UnsupportedOperationException> {
            (aliases.propertyAliases as MutableMap<String, Map<String, Set<String>>>).remove("Person")
        }

        @Suppress("UNCHECKED_CAST")
        assertThrows<UnsupportedOperationException> {
            (aliases.propertyAliases["Person"]!!["emailAddress"] as MutableSet<String>).add("Sneaky")
        }
    }

    @Test
    fun `equality is by content`() {
        val one = SchemaAliases(
            typeAliases = mapOf("Person" to setOf("Human")),
            propertyAliases = mapOf("Person" to mapOf("emailAddress" to setOf("email"))),
        )
        val two = SchemaAliases(
            typeAliases = mapOf("Person" to setOf("Human")),
            propertyAliases = mapOf("Person" to mapOf("emailAddress" to setOf("email"))),
        )

        assertEquals(one, two)
        assertEquals(one.hashCode(), two.hashCode())
        assertTrue(one.toString().contains("Human"))
    }
}

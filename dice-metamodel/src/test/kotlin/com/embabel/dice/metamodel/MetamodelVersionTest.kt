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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class MetamodelVersionTest {

    private fun schemaWith(vararg typeNames: String): DataDictionary =
        DataDictionary.fromDomainTypes(
            "test",
            typeNames.map { DynamicType(name = it) },
        )

    @Nested
    inner class ContentHash {

        @Test
        fun `identical schemas produce the same hash`() {
            val a = MetamodelVersion.from(schemaWith("Person", "Company"))
            val b = MetamodelVersion.from(schemaWith("Person", "Company"))
            assertEquals(a.contentHash, b.contentHash)
            assertTrue(a.hasSameContentAs(b))
        }

        @Test
        fun `different type sets produce different hashes`() {
            val a = MetamodelVersion.from(schemaWith("Person", "Company"))
            val b = MetamodelVersion.from(schemaWith("Person", "Technology"))
            assertNotEquals(a.contentHash, b.contentHash)
            assertFalse(a.hasSameContentAs(b))
        }

        @Test
        fun `type order does not affect hash`() {
            val a = MetamodelVersion.from(schemaWith("Company", "Person"))
            val b = MetamodelVersion.from(schemaWith("Person", "Company"))
            assertEquals(a.contentHash, b.contentHash)
        }

        @Test
        fun `adding a type changes the hash`() {
            val base = MetamodelVersion.from(schemaWith("Person"))
            val extended = MetamodelVersion.from(schemaWith("Person", "Company"))
            assertNotEquals(base.contentHash, extended.contentHash)
        }

        @Test
        fun `empty schema has a stable hash`() {
            val a = MetamodelVersion.from(schemaWith())
            val b = MetamodelVersion.from(schemaWith())
            assertEquals(a.contentHash, b.contentHash)
        }

        @Test
        fun `same types under different schema names produce the same hash`() {
            // contentHash covers structural content only; the schema name is excluded so that
            // dev/prod variants of the same schema compare as equal.
            val a = MetamodelVersion.from(
                DataDictionary.fromDomainTypes("schema-dev", listOf(DynamicType("Person")))
            )
            val b = MetamodelVersion.from(
                DataDictionary.fromDomainTypes("schema-prod", listOf(DynamicType("Person")))
            )
            assertEquals(a.contentHash, b.contentHash)
            assertTrue(a.hasSameContentAs(b))
        }
    }

    @Nested
    inner class VersionMetadata {

        @Test
        fun `entity type names are sorted`() {
            val version = MetamodelVersion.from(schemaWith("Zebra", "Apple", "Mango"))
            assertEquals(listOf("Apple", "Mango", "Zebra"), version.entityTypeNames)
        }

        @Test
        fun `schema name is captured`() {
            val dict = DataDictionary.fromDomainTypes("my-schema", listOf(DynamicType("Person")))
            val version = MetamodelVersion.from(dict)
            assertEquals("my-schema", version.schemaName)
        }

        @Test
        fun `per-type label sets are captured including inherited labels`() {
            val dict = DataDictionary.fromDomainTypes(
                "test",
                listOf(DynamicType(name = "Person", parents = listOf(DynamicType(name = "Agent")))),
            )
            val version = MetamodelVersion.from(dict)
            assertEquals(setOf("Person", "Agent"), version.entityTypeLabels["Person"])
        }

        @Test
        fun `per-type property sets are captured`() {
            val dict = DataDictionary.fromDomainTypes(
                "test",
                listOf(
                    DynamicType(
                        name = "Person",
                        ownProperties = listOf(ValuePropertyDefinition("age"), ValuePropertyDefinition("email")),
                    ),
                ),
            )
            val version = MetamodelVersion.from(dict)
            assertEquals(setOf("age", "email"), version.entityTypeProperties["Person"])
        }

        @Test
        fun `same-named types with different shapes are merged, not dropped`() {
            // A DataDictionary can hold two "Person" types with different shapes. The fingerprint
            // must union both, never silently keep only the last — otherwise a label or property
            // would disappear from the hash and its later removal would go undetected.
            val dict = DataDictionary.fromDomainTypes(
                "test",
                listOf(
                    DynamicType(
                        name = "Person",
                        parents = listOf(DynamicType(name = "Agent")),
                        ownProperties = listOf(ValuePropertyDefinition("age")),
                    ),
                    DynamicType(
                        name = "Person",
                        parents = listOf(DynamicType(name = "Robot")),
                        ownProperties = listOf(ValuePropertyDefinition("email")),
                    ),
                ),
            )
            val version = MetamodelVersion.from(dict)
            assertEquals(setOf("Person", "Agent", "Robot"), version.entityTypeLabels["Person"])
            assertEquals(setOf("age", "email"), version.entityTypeProperties["Person"])
            // The name is deduped in the sorted name list, not repeated.
            assertEquals(listOf("Person"), version.entityTypeNames)
        }
    }

    @Nested
    inner class LabelDrift {

        /** Same type name, but a different parent — so the label set differs while the name does not. */
        private fun personWithParent(parent: String): DataDictionary =
            DataDictionary.fromDomainTypes(
                "test",
                listOf(DynamicType(name = "Person", parents = listOf(DynamicType(name = parent)))),
            )

        @Test
        fun `a label-only change produces a different hash`() {
            val a = MetamodelVersion.from(personWithParent("Agent"))
            val b = MetamodelVersion.from(personWithParent("Actor"))
            // The type name set is identical; only the label sets differ.
            assertEquals(a.entityTypeNames, b.entityTypeNames)
            assertNotEquals(a.contentHash, b.contentHash)
            assertFalse(a.hasSameContentAs(b))
        }

        @Test
        fun `identical label sets produce the same hash`() {
            val a = MetamodelVersion.from(personWithParent("Agent"))
            val b = MetamodelVersion.from(personWithParent("Agent"))
            assertEquals(a.contentHash, b.contentHash)
        }
    }

    @Nested
    inner class PropertyDrift {

        /** Same type name, but a different property set. */
        private fun personWithProperties(vararg props: String): DataDictionary =
            DataDictionary.fromDomainTypes(
                "test",
                listOf(DynamicType(name = "Person", ownProperties = props.map { ValuePropertyDefinition(it) })),
            )

        @Test
        fun `a property-only change produces a different hash`() {
            val a = MetamodelVersion.from(personWithProperties("age"))
            val b = MetamodelVersion.from(personWithProperties("age", "email"))
            assertEquals(a.entityTypeNames, b.entityTypeNames)
            assertEquals(a.entityTypeLabels, b.entityTypeLabels)
            assertNotEquals(a.contentHash, b.contentHash)
            assertFalse(a.hasSameContentAs(b))
        }

        @Test
        fun `identical property sets produce the same hash`() {
            val a = MetamodelVersion.from(personWithProperties("age", "email"))
            val b = MetamodelVersion.from(personWithProperties("email", "age"))
            assertEquals(a.contentHash, b.contentHash)
        }
    }
}

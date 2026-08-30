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
        fun `a caller cannot supply the hash — it is always derived from the content`() {
            // The whole point of deriving it: two versions that differ structurally can never claim
            // the same hash, because there is no way to hand one in.
            val one = MetamodelVersion(
                schemaName = "s",
                entityTypeNames = listOf("A"),
                entityTypeLabels = mapOf("A" to setOf("A")),
                entityTypeProperties = mapOf("A" to emptySet()),
                relationshipNames = emptyList(),
            )
            val two = one.copy(
                entityTypeNames = listOf("A", "B"),
                entityTypeLabels = mapOf("A" to setOf("A"), "B" to setOf("B")),
                entityTypeProperties = mapOf("A" to emptySet(), "B" to emptySet()),
            )
            assertNotEquals(one.contentHash, two.contentHash)
            assertFalse(one.hasSameContentAs(two))
        }

        @Test
        fun `constructor takes an immutable snapshot of caller-owned collections`() {
            val typeNames = mutableListOf("A")
            val labels = mutableMapOf<String, Set<String>>("A" to mutableSetOf("A"))
            val properties = mutableMapOf<String, Set<String>>("A" to mutableSetOf("name"))
            val relationships = mutableListOf("A-[KNOWS]->A")
            val version = MetamodelVersion(
                schemaName = "s",
                entityTypeNames = typeNames,
                entityTypeLabels = labels,
                entityTypeProperties = properties,
                relationshipNames = relationships,
            )
            val originalHash = version.contentHash

            typeNames += "B"
            (labels.getValue("A") as MutableSet<String>) += "Agent"
            labels["B"] = mutableSetOf("B")
            (properties.getValue("A") as MutableSet<String>) += "email"
            relationships += "A-[LIKES]->B"

            assertEquals(listOf("A"), version.entityTypeNames)
            assertEquals(mapOf("A" to setOf("A")), version.entityTypeLabels)
            assertEquals(mapOf("A" to setOf("name")), version.entityTypeProperties)
            assertEquals(listOf("A-[KNOWS]->A"), version.relationshipNames)
            assertEquals(originalHash, version.contentHash)
            assertThrows(UnsupportedOperationException::class.java) {
                (version.entityTypeNames as MutableList<String>) += "B"
            }
            assertThrows(UnsupportedOperationException::class.java) {
                (version.entityTypeLabels.getValue("A") as MutableSet<String>) += "Agent"
            }
        }

        @Test
        fun `constructor rejects shape maps for absent entity types`() {
            val error = assertThrows(IllegalArgumentException::class.java) {
                MetamodelVersion(
                    schemaName = "s",
                    entityTypeNames = listOf("A"),
                    entityTypeLabels = mapOf("A" to setOf("A"), "B" to setOf("B")),
                    entityTypeProperties = mapOf("A" to emptySet()),
                    relationshipNames = emptyList(),
                )
            }

            assertTrue(error.message.orEmpty().contains("entityTypeLabels"))
        }

        @Test
        fun `copy requires labels and properties to remain aligned with entity type names`() {
            val version = MetamodelVersion(
                schemaName = "s",
                entityTypeNames = listOf("A", "B"),
                entityTypeLabels = mapOf("A" to setOf("A"), "B" to setOf("B")),
                entityTypeProperties = mapOf("A" to emptySet(), "B" to emptySet()),
                relationshipNames = emptyList(),
            )

            assertThrows(IllegalArgumentException::class.java) {
                version.copy(entityTypeNames = listOf("A"))
            }
            val aligned = version.copy(
                entityTypeNames = listOf("A"),
                entityTypeLabels = mapOf("A" to setOf("A")),
                entityTypeProperties = mapOf("A" to emptySet()),
            )
            assertEquals(listOf("A"), aligned.entityTypeNames)
        }

        @Test
        fun `value semantics match the former data class contract`() {
            val one = MetamodelVersion(
                schemaName = "s",
                entityTypeNames = listOf("B", "A"),
                entityTypeLabels = mapOf("A" to setOf("A"), "B" to setOf("B")),
                entityTypeProperties = mapOf("A" to emptySet(), "B" to setOf("name")),
                relationshipNames = listOf("B-[KNOWS]->A"),
            )
            val two = MetamodelVersion(
                schemaName = "s",
                entityTypeNames = listOf("A", "B"),
                entityTypeLabels = mapOf("B" to setOf("B"), "A" to setOf("A")),
                entityTypeProperties = mapOf("B" to setOf("name"), "A" to emptySet()),
                relationshipNames = listOf("B-[KNOWS]->A"),
            )

            assertEquals(one, two)
            assertEquals(one.hashCode(), two.hashCode())
            assertEquals(one, one.copy())
            assertEquals("s", one.component1())
            assertEquals(listOf("A", "B"), one.component2())
            assertTrue(one.toString().startsWith("MetamodelVersion(schemaName=s"))
        }

        @Test
        fun `constructor sorting does not deduplicate persisted hash input`() {
            val duplicated = MetamodelVersion(
                schemaName = "s",
                entityTypeNames = listOf("A", "A"),
                entityTypeLabels = mapOf("A" to setOf("A")),
                entityTypeProperties = mapOf("A" to emptySet()),
                relationshipNames = listOf("A-[KNOWS]->A", "A-[KNOWS]->A"),
            )
            val single = MetamodelVersion(
                schemaName = "s",
                entityTypeNames = listOf("A"),
                entityTypeLabels = mapOf("A" to setOf("A")),
                entityTypeProperties = mapOf("A" to emptySet()),
                relationshipNames = listOf("A-[KNOWS]->A"),
            )

            assertEquals(listOf("A", "A"), duplicated.entityTypeNames)
            assertEquals(2, duplicated.relationshipNames.size)
            assertNotEquals(single.contentHash, duplicated.contentHash)
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
    inner class GoldenHash {

        /**
         * A fixed schema whose every hashed ingredient is spelled out: two type names, one label
         * apiece, three properties on one of them, and one relationship.
         */
        private fun goldenSchema(): DataDictionary {
            val company = DynamicType(name = "Company")
            val person = DynamicType(
                name = "Person",
                ownProperties = listOf(
                    ValuePropertyDefinition("age"),
                    ValuePropertyDefinition("email"),
                    DomainTypePropertyDefinition("worksAt", company),
                ),
            )
            return DataDictionary.fromDomainTypes("golden-schema", listOf(person, company))
        }

        @Test
        fun `the fixture hashes exactly these ingredients`() {
            // Guards the golden vector below: if this fails, the fixture changed, not the format.
            val version = MetamodelVersion.from(goldenSchema())
            assertEquals(listOf("Company", "Person"), version.entityTypeNames)
            assertEquals(setOf("Company"), version.entityTypeLabels["Company"])
            assertEquals(setOf("Person"), version.entityTypeLabels["Person"])
            assertEquals(emptySet<String>(), version.entityTypeProperties["Company"])
            assertEquals(setOf("age", "email", "worksAt"), version.entityTypeProperties["Person"])
            assertEquals(listOf("Person-[worksAt]->Company"), version.relationshipNames)
        }

        @Test
        fun `golden vector — the digest of a fixed schema is pinned to a literal`() {
            // This literal is the persisted hash format, deliberately frozen. contentHash is the
            // store's MERGE key and every DriftReport records it as versionHash, so changing how
            // the fingerprint is encoded orphans every report already on disk. If you mean to
            // change the format, change this literal in the same commit and plan the migration —
            // do not "fix" the test by pasting in whatever the new code produces.
            assertEquals(
                "17e3a63380fe1871ec4944918521022c381dea0e314ee7518515391ecfb73ab9",
                MetamodelVersion.from(goldenSchema()).contentHash,
            )
        }

        @Test
        fun `the golden digest does not depend on the schema name`() {
            val renamed = DataDictionary.fromDomainTypes("some-other-name", goldenSchema().domainTypes)
            assertEquals(
                "17e3a63380fe1871ec4944918521022c381dea0e314ee7518515391ecfb73ab9",
                MetamodelVersion.from(renamed).contentHash,
            )
        }
    }

    @Nested
    inner class RelationshipDrift {

        /** Same types and properties throughout; only the relationship list varies. */
        private fun versionWithRelationships(vararg relationshipNames: String): MetamodelVersion =
            MetamodelVersion(
                schemaName = "test",
                entityTypeNames = listOf("Company", "Person"),
                entityTypeLabels = mapOf("Company" to setOf("Company"), "Person" to setOf("Person")),
                entityTypeProperties = mapOf("Company" to emptySet(), "Person" to emptySet()),
                relationshipNames = relationshipNames.toList(),
            )

        @Test
        fun `adding a relationship changes the hash`() {
            val before = versionWithRelationships()
            val after = versionWithRelationships("Person-[WORKS_AT]->Company")
            assertNotEquals(before.contentHash, after.contentHash)
            assertFalse(before.hasSameContentAs(after))
        }

        @Test
        fun `removing one relationship of two changes the hash`() {
            val both = versionWithRelationships("Person-[WORKS_AT]->Company", "Person-[FOUNDED]->Company")
            val one = versionWithRelationships("Person-[WORKS_AT]->Company")
            assertNotEquals(both.contentHash, one.contentHash)
        }

        @Test
        fun `relationship order does not affect the hash`() {
            val a = versionWithRelationships("Person-[WORKS_AT]->Company", "Person-[FOUNDED]->Company")
            val b = versionWithRelationships("Person-[FOUNDED]->Company", "Person-[WORKS_AT]->Company")
            assertEquals(a.contentHash, b.contentHash)
            assertTrue(a.hasSameContentAs(b))
        }

        @Test
        fun `dropping a relationship property from a dictionary changes the hash end to end`() {
            val company = DynamicType(name = "Company")
            val withRelationship = DataDictionary.fromDomainTypes(
                "test",
                listOf(
                    DynamicType(
                        name = "Person",
                        ownProperties = listOf(DomainTypePropertyDefinition("worksAt", company)),
                    ),
                    company,
                ),
            )
            val withoutRelationship = DataDictionary.fromDomainTypes(
                "test",
                listOf(DynamicType(name = "Person"), company),
            )

            val before = MetamodelVersion.from(withRelationship)
            val after = MetamodelVersion.from(withoutRelationship)

            assertEquals(listOf("Person-[worksAt]->Company"), before.relationshipNames)
            assertEquals(emptyList<String>(), after.relationshipNames)
            assertNotEquals(before.contentHash, after.contentHash)
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

        @Test
        fun `a property name containing the set delimiter does not collide with a split set`() {
            // ["a;b"] and ["a", "b"] are genuinely different property sets. A delimiter-joined
            // encoding would serialise both as "a;b;" and hash them identically, hiding a real
            // (lossy) schema change. Length-prefixed encoding keeps them distinct.
            val joined = MetamodelVersion.from(personWithProperties("a;b"))
            val split = MetamodelVersion.from(personWithProperties("a", "b"))
            assertNotEquals(joined.contentHash, split.contentHash)
            assertFalse(joined.hasSameContentAs(split))
        }
    }
}

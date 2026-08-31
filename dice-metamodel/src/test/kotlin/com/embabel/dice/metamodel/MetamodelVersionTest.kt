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
import com.embabel.agent.core.DomainType
import com.embabel.agent.core.DomainTypePropertyDefinition
import com.embabel.agent.core.DynamicType
import com.embabel.agent.core.PropertyDefinition
import com.embabel.agent.core.ValuePropertyDefinition
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

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
            val one = MetamodelVersion(
                schemaName = "s",
                entityTypeNames = listOf("A"),
                entityTypeLabels = mapOf("A" to setOf("A")),
                entityTypeProperties = mapOf("A" to emptySet()),
                relationshipNames = emptyList(),
            )
            val two = MetamodelVersion(
                schemaName = "s",
                entityTypeNames = listOf("A", "B"),
                entityTypeLabels = mapOf("A" to setOf("A"), "B" to setOf("B")),
                entityTypeProperties = mapOf("A" to emptySet(), "B" to emptySet()),
                relationshipNames = emptyList(),
            )
            assertNotEquals(one.contentHash, two.contentHash)
            assertFalse(one.hasSameContentAs(two))
        }

        @Test
        fun `same types under different schema names produce the same hash`() {
            // The schema name is excluded from contentHash, so dev and prod variants of one schema
            // compare as equal.
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
            assertEquals(emptySet<PropertySignature>(), version.entityTypeProperties["Company"])
            assertEquals(
                setOf(
                    PropertySignature("age", PropertySignature.Kind.VALUE, "string", Cardinality.ONE),
                    PropertySignature("email", PropertySignature.Kind.VALUE, "string", Cardinality.ONE),
                    PropertySignature("worksAt", PropertySignature.Kind.REFERENCE, "Company", Cardinality.ONE),
                ),
                version.entityTypeProperties["Person"],
            )
            assertEquals(listOf("Person-[worksAt]->Company"), version.relationshipNames)
        }

        @Test
        fun `golden vector — the digest of a fixed schema is pinned to a literal`() {
            // This literal pins the persisted hash format. contentHash is the store's natural key
            // and what extracted data records as the version it was created under, so changing how
            // the fingerprint is encoded orphans everything already saved against it. Changing the
            // format means changing this literal in the same commit and planning the migration;
            // don't "fix" the test by pasting in whatever the new code produces. This vector was
            // last regenerated when property signatures (type and cardinality, not just the name)
            // went into the encoding, before anything had been persisted against the old form.
            assertEquals(
                "0a5b5b62c125d8ade5bcd2af5b03e0ec5bcaaf5b0799b7cfe8c16be6e723de00",
                MetamodelVersion.from(goldenSchema()).contentHash,
            )
        }

        @Test
        fun `the golden digest does not depend on the schema name`() {
            val renamed = DataDictionary.fromDomainTypes("some-other-name", goldenSchema().domainTypes)
            assertEquals(
                "0a5b5b62c125d8ade5bcd2af5b03e0ec5bcaaf5b0799b7cfe8c16be6e723de00",
                MetamodelVersion.from(renamed).contentHash,
            )
        }

        @Test
        fun `an alias-free declaration still hashes to the digest pinned before aliases existed`() {
            // The literal below was produced by the encoding as it stood before PropertySignature
            // carried aliases and MetamodelVersion carried entityTypeAliases. Alias blocks are
            // written only when they hold something, so a schema declaring no former names has to
            // render the same bytes and keep every hash already recorded against it. All four ways
            // of saying "no aliases" have to land on it.
            val pinned = "0a5b5b62c125d8ade5bcd2af5b03e0ec5bcaaf5b0799b7cfe8c16be6e723de00"

            assertEquals(pinned, MetamodelVersion.from(goldenSchema()).contentHash)
            assertEquals(pinned, MetamodelVersion.from(goldenSchema(), GovernedTypeSelector.ALL).contentHash)
            assertEquals(
                pinned,
                MetamodelVersion.from(goldenSchema(), GovernedTypeSelector.ALL, SchemaAliases.NONE).contentHash,
            )
            assertEquals(
                pinned,
                MetamodelVersion.from(
                    goldenSchema(),
                    GovernedTypeSelector.ALL,
                    SchemaAliases(typeAliases = emptyMap(), propertyAliases = emptyMap()),
                ).contentHash,
            )
        }

        @Test
        fun `rebuilding the golden stamp through the public constructor hashes to the same literal`() {
            // The storage mapper reconstructs a stamp field by field rather than from a dictionary.
            // Passing an explicitly empty alias map has to reproduce the pinned digest, or a row
            // written before aliases existed could never be read back.
            val fromDictionary = MetamodelVersion.from(goldenSchema())
            val rebuilt = MetamodelVersion(
                schemaName = fromDictionary.schemaName,
                entityTypeNames = fromDictionary.entityTypeNames,
                entityTypeLabels = fromDictionary.entityTypeLabels,
                entityTypeProperties = fromDictionary.entityTypeProperties,
                relationshipNames = fromDictionary.relationshipNames,
                entityTypeAliases = emptyMap(),
            )
            assertEquals(
                "0a5b5b62c125d8ade5bcd2af5b03e0ec5bcaaf5b0799b7cfe8c16be6e723de00",
                rebuilt.contentHash,
            )
        }
    }

    @Nested
    inner class Relationships {

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

        @Test
        fun `a repeated descriptor is deduplicated, not counted twice`() {
            val once = versionWithRelationships("Person-[WORKS_AT]->Company")
            val twice = versionWithRelationships("Person-[WORKS_AT]->Company", "Person-[WORKS_AT]->Company")
            assertEquals(listOf("Person-[WORKS_AT]->Company"), twice.relationshipNames)
            assertEquals(once.contentHash, twice.contentHash)
        }

        @Test
        fun `splitting a type into two same-named declarations hashes like the merged one`() {
            // A DataDictionary can hold two "Person" types that both declare worksAt, which renders
            // the same descriptor twice. It is the same schema as one Person declaring it once, so
            // it has to be the same hash.
            val company = DynamicType(name = "Company")
            val worksAt = DomainTypePropertyDefinition("worksAt", company)
            val merged = DataDictionary.fromDomainTypes(
                "test",
                listOf(
                    DynamicType(
                        name = "Person",
                        ownProperties = listOf(ValuePropertyDefinition("age"), worksAt),
                    ),
                    company,
                ),
            )
            val split = DataDictionary.fromDomainTypes(
                "test",
                listOf(
                    DynamicType(name = "Person", ownProperties = listOf(ValuePropertyDefinition("age"), worksAt)),
                    DynamicType(name = "Person", ownProperties = listOf(worksAt)),
                    company,
                ),
            )

            val mergedVersion = MetamodelVersion.from(merged)
            val splitVersion = MetamodelVersion.from(split)

            assertEquals(listOf("Person-[worksAt]->Company"), splitVersion.relationshipNames)
            assertEquals(mergedVersion.contentHash, splitVersion.contentHash)
            assertTrue(mergedVersion.hasSameContentAs(splitVersion))
        }
    }

    @Nested
    inner class PropertySignatures {

        /** One Person with a single property, described however the test needs it. */
        private fun personWith(property: PropertyDefinition): DataDictionary =
            DataDictionary.fromDomainTypes(
                "test",
                listOf(DynamicType(name = "Person", ownProperties = listOf(property))),
            )

        @Test
        fun `changing a property's type produces a different hash`() {
            val asString = MetamodelVersion.from(personWith(ValuePropertyDefinition("age", type = "string")))
            val asInteger = MetamodelVersion.from(personWith(ValuePropertyDefinition("age", type = "integer")))

            assertEquals(asString.entityTypeNames, asInteger.entityTypeNames)
            assertEquals(asString.entityTypeLabels, asInteger.entityTypeLabels)
            assertNotEquals(asString.contentHash, asInteger.contentHash)
            assertFalse(asString.hasSameContentAs(asInteger))
        }

        @Test
        fun `changing a property's cardinality produces a different hash`() {
            val one = MetamodelVersion.from(
                personWith(ValuePropertyDefinition("nickname", cardinality = Cardinality.ONE))
            )
            val list = MetamodelVersion.from(
                personWith(ValuePropertyDefinition("nickname", cardinality = Cardinality.LIST))
            )

            assertNotEquals(one.contentHash, list.contentHash)
        }

        @Test
        fun `changing a relationship's cardinality produces a different hash`() {
            val company = DynamicType(name = "Company")
            val one = MetamodelVersion.from(
                personWith(DomainTypePropertyDefinition("worksAt", company, Cardinality.ONE))
            )
            val many = MetamodelVersion.from(
                personWith(DomainTypePropertyDefinition("worksAt", company, Cardinality.LIST))
            )

            // The rendered descriptor is identical; only the cardinality behind it moved.
            assertEquals(one.relationshipNames, many.relationshipNames)
            assertNotEquals(one.contentHash, many.contentHash)
        }

        @Test
        fun `retargeting a relationship produces a different hash`() {
            val toCompany = MetamodelVersion.from(
                personWith(DomainTypePropertyDefinition("worksAt", DynamicType("Company")))
            )
            val toCharity = MetamodelVersion.from(
                personWith(DomainTypePropertyDefinition("worksAt", DynamicType("Charity")))
            )

            assertNotEquals(toCompany.contentHash, toCharity.contentHash)
        }

        @Test
        fun `a value property and a reference of the same name hash differently`() {
            // "worksAt: string" and "worksAt -> Company" are different schemas even though the
            // property name, the declared type name, and the cardinality all read the same.
            val asValue = MetamodelVersion.from(personWith(ValuePropertyDefinition("worksAt", type = "Company")))
            val asReference = MetamodelVersion.from(
                personWith(DomainTypePropertyDefinition("worksAt", DynamicType("Company")))
            )

            assertNotEquals(asValue.contentHash, asReference.contentHash)
        }

        @Test
        fun `descriptions and metadata are not part of the signature`() {
            // They steer extraction, but they don't change what the graph can hold.
            val plain = MetamodelVersion.from(personWith(ValuePropertyDefinition("age")))
            val documented = MetamodelVersion.from(
                personWith(
                    ValuePropertyDefinition(
                        name = "age",
                        description = "how many years the person has been alive",
                        metadata = mapOf("predicate" to "is aged"),
                    )
                )
            )

            assertEquals(plain.contentHash, documented.contentHash)
        }

        @Test
        fun `the signature records name, kind, type and cardinality`() {
            val version = MetamodelVersion.from(
                personWith(ValuePropertyDefinition("nicknames", type = "string", cardinality = Cardinality.SET))
            )

            assertEquals(
                setOf(PropertySignature("nicknames", PropertySignature.Kind.VALUE, "string", Cardinality.SET)),
                version.entityTypeProperties["Person"],
            )
        }
    }

    @Nested
    inner class Immutability {

        private fun version(): MetamodelVersion = MetamodelVersion.from(
            DataDictionary.fromDomainTypes(
                "test",
                listOf(
                    DynamicType(name = "Person", ownProperties = listOf(ValuePropertyDefinition("age"))),
                    DynamicType("Company"),
                ),
            ),
        )

        @Test
        fun `the collections a stamp hands back cannot be mutated`() {
            // Kotlin's read-only types are a compile-time promise; a Java caller sees plain
            // java.util collections through the getters. These have to refuse at runtime, or a
            // caller could reshape a stamp out from under its own precomputed hash.
            val version = version()

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (version.entityTypeNames as MutableList<String>).add("Sneaky")
            }

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (version.relationshipNames as MutableList<String>).add("Sneaky")
            }

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (version.entityTypeLabels as MutableMap<String, Set<String>>).remove("Person")
            }

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (version.entityTypeLabels["Person"] as MutableSet<String>).add("Sneaky")
            }

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (version.entityTypeProperties as MutableMap<String, Set<PropertySignature>>).remove("Person")
            }

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (version.entityTypeProperties["Person"] as MutableSet<PropertySignature>).clear()
            }
        }

        @Test
        fun `mutating what the caller passed in does not change the stamp`() {
            val names = mutableListOf("Person")
            val labels = mutableMapOf("Person" to mutableSetOf("Person"))
            val properties = mutableMapOf(
                "Person" to mutableSetOf(
                    PropertySignature("age", PropertySignature.Kind.VALUE, "string", Cardinality.ONE)
                ),
            )
            val relationships = mutableListOf("Person-[worksAt]->Company")

            val version = MetamodelVersion("test", names, labels, properties, relationships)
            val hashAtConstruction = version.contentHash

            names.add("Company")
            labels["Person"]!!.add("Agent")
            properties["Person"]!!.clear()
            relationships.clear()

            assertEquals(listOf("Person"), version.entityTypeNames)
            assertEquals(setOf("Person"), version.entityTypeLabels["Person"])
            assertEquals(1, version.entityTypeProperties["Person"]!!.size)
            assertEquals(listOf("Person-[worksAt]->Company"), version.relationshipNames)
            assertEquals(hashAtConstruction, version.contentHash)
        }
    }

    @Nested
    inner class StructuralConsistency {

        @Test
        fun `labels keyed by a type that is not listed are rejected`() {
            // Only listed types are walked when hashing, so accepting this would let two stamps
            // with different labels share a content hash, and the store's natural key with it.
            val thrown = assertThrows<IllegalArgumentException> {
                MetamodelVersion(
                    schemaName = "test",
                    entityTypeNames = listOf("Person"),
                    entityTypeLabels = mapOf("Person" to setOf("Person"), "Ghost" to setOf("Ghost")),
                    entityTypeProperties = emptyMap(),
                    relationshipNames = emptyList(),
                )
            }
            assertTrue(thrown.message!!.contains("entityTypeLabels"), thrown.message)
            assertTrue(thrown.message!!.contains("Ghost"), thrown.message)
        }

        @Test
        fun `properties keyed by a type that is not listed are rejected`() {
            val thrown = assertThrows<IllegalArgumentException> {
                MetamodelVersion(
                    schemaName = "test",
                    entityTypeNames = listOf("Person"),
                    entityTypeLabels = emptyMap(),
                    entityTypeProperties = mapOf(
                        "Ghost" to setOf(
                            PropertySignature("age", PropertySignature.Kind.VALUE, "string", Cardinality.ONE)
                        ),
                    ),
                    relationshipNames = emptyList(),
                )
            }
            assertTrue(thrown.message!!.contains("entityTypeProperties"), thrown.message)
            assertTrue(thrown.message!!.contains("Ghost"), thrown.message)
        }

        @Test
        fun `entity type names are sorted and deduplicated on the way in`() {
            val version = MetamodelVersion(
                schemaName = "test",
                entityTypeNames = listOf("Person", "Company", "Person"),
                entityTypeLabels = emptyMap(),
                entityTypeProperties = emptyMap(),
                relationshipNames = emptyList(),
            )
            assertEquals(listOf("Company", "Person"), version.entityTypeNames)
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
            assertEquals(
                setOf("age", "email"),
                version.entityTypeProperties["Person"]!!.map { it.name }.toSet(),
            )
        }

        @Test
        fun `same-named types with different shapes are merged, not dropped`() {
            // A DataDictionary can hold two "Person" types with different shapes. The fingerprint
            // unions both. Keeping only the last would drop a label or property from the hash, and
            // its later removal would go undetected.
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
            assertEquals(
                setOf("age", "email"),
                version.entityTypeProperties["Person"]!!.map { it.name }.toSet(),
            )
            // The name is deduped in the sorted name list.
            assertEquals(listOf("Person"), version.entityTypeNames)
        }
    }

    @Nested
    inner class Labels {

        /** Same type name with a different parent, so the label set differs while the name doesn't. */
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
    inner class Properties {

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
            // ["a;b"] and ["a", "b"] are different property sets. A delimiter-joined encoding would
            // serialise both as "a;b;" and hash them identically, hiding a lossy schema change.
            // Length-prefixed encoding keeps them distinct.
            val joined = MetamodelVersion.from(personWithProperties("a;b"))
            val split = MetamodelVersion.from(personWithProperties("a", "b"))
            assertNotEquals(joined.contentHash, split.contentHash)
            assertFalse(joined.hasSameContentAs(split))
        }
    }

    @Nested
    inner class GovernedSubset {

        private val governed = GovernedTypeSelector { it.name in setOf("Person", "Company") }

        private fun dictionaryOf(vararg types: DomainType): DataDictionary =
            DataDictionary.fromDomainTypes("test", types.toList())

        @Test
        fun `the default selector governs everything`() {
            val dict = dictionaryOf(DynamicType("Person"), DynamicType("Company"))
            assertEquals(
                MetamodelVersion.from(dict).contentHash,
                MetamodelVersion.from(dict, GovernedTypeSelector.ALL).contentHash,
            )
            assertEquals(listOf("Company", "Person"), MetamodelVersion.from(dict, GovernedTypeSelector.ALL).entityTypeNames)
        }

        @Test
        fun `adding an ungoverned type leaves the hash alone`() {
            // Per-type governance exists so that extraction proposing a new exploratory type
            // doesn't fill the version history with stamps nobody chose.
            val before = dictionaryOf(DynamicType("Person"), DynamicType("Company"))
            val after = dictionaryOf(DynamicType("Person"), DynamicType("Company"), DynamicType("Sighting"))

            assertEquals(
                MetamodelVersion.from(before, governed).contentHash,
                MetamodelVersion.from(after, governed).contentHash,
            )
        }

        @Test
        fun `adding a governed type changes the hash`() {
            val before = dictionaryOf(DynamicType("Person"))
            val after = dictionaryOf(DynamicType("Person"), DynamicType("Company"))

            assertNotEquals(
                MetamodelVersion.from(before, governed).contentHash,
                MetamodelVersion.from(after, governed).contentHash,
            )
        }

        @Test
        fun `reshaping an ungoverned type leaves the hash alone`() {
            // Labels and properties on an ungoverned type are excluded along with its name.
            val plain = dictionaryOf(DynamicType("Person"), DynamicType("Sighting"))
            val reshaped = dictionaryOf(
                DynamicType("Person"),
                DynamicType(
                    name = "Sighting",
                    parents = listOf(DynamicType("Observation")),
                    ownProperties = listOf(ValuePropertyDefinition("seenAt")),
                ),
            )

            assertEquals(
                MetamodelVersion.from(plain, governed).contentHash,
                MetamodelVersion.from(reshaped, governed).contentHash,
            )
        }

        @Test
        fun `reshaping a governed type changes the hash`() {
            val plain = dictionaryOf(DynamicType("Person"), DynamicType("Sighting"))
            val reshaped = dictionaryOf(
                DynamicType(name = "Person", ownProperties = listOf(ValuePropertyDefinition("age"))),
                DynamicType("Sighting"),
            )

            assertNotEquals(
                MetamodelVersion.from(plain, governed).contentHash,
                MetamodelVersion.from(reshaped, governed).contentHash,
            )
        }

        @Test
        fun `only governed types are stamped`() {
            val version = MetamodelVersion.from(
                dictionaryOf(DynamicType("Person"), DynamicType("Company"), DynamicType("Sighting")),
                governed,
            )
            assertEquals(listOf("Company", "Person"), version.entityTypeNames)
            assertNull(version.entityTypeLabels["Sighting"])
            assertNull(version.entityTypeProperties["Sighting"])
        }

        @Test
        fun `a relationship declared by an ungoverned type is left out`() {
            val person = DynamicType("Person")
            val withSighting = dictionaryOf(
                person,
                DynamicType(name = "Sighting", ownProperties = listOf(DomainTypePropertyDefinition("about", person))),
            )
            val withoutSighting = dictionaryOf(person)

            val version = MetamodelVersion.from(withSighting, governed)
            assertEquals(emptyList<String>(), version.relationshipNames)
            assertEquals(MetamodelVersion.from(withoutSighting, governed).contentHash, version.contentHash)
        }

        @Test
        fun `a governed type's relationship to an ungoverned type stays in the stamp`() {
            // The relationship belongs to the type that declares it. Person saying it has a
            // `spotted` Sighting is part of Person's declared shape, whether or not Sighting is
            // itself governed, and whether or not Sighting is in the dictionary at all.
            val sighting = DynamicType("Sighting")
            val person = DynamicType(
                name = "Person",
                ownProperties = listOf(DomainTypePropertyDefinition("spotted", sighting)),
            )

            val version = MetamodelVersion.from(dictionaryOf(person, sighting), governed)
            assertEquals(listOf("Person-[spotted]->Sighting"), version.relationshipNames)
            // Listing the ungoverned type in the dictionary therefore changes nothing.
            assertEquals(MetamodelVersion.from(dictionaryOf(person), governed).contentHash, version.contentHash)
        }

        @Test
        fun `dropping a governed type's relationship changes the hash`() {
            val sighting = DynamicType("Sighting")
            val with = dictionaryOf(
                DynamicType(name = "Person", ownProperties = listOf(DomainTypePropertyDefinition("spotted", sighting))),
                sighting,
            )
            val without = dictionaryOf(DynamicType("Person"), sighting)

            assertNotEquals(
                MetamodelVersion.from(with, governed).contentHash,
                MetamodelVersion.from(without, governed).contentHash,
            )
        }

        @Test
        fun `governing nothing stamps an empty schema`() {
            val version = MetamodelVersion.from(
                dictionaryOf(DynamicType("Person"), DynamicType("Company")),
                GovernedTypeSelector { false },
            )
            assertEquals(emptyList<String>(), version.entityTypeNames)
            assertEquals(emptyList<String>(), version.relationshipNames)
            assertEquals(
                MetamodelVersion.from(DataDictionary.fromDomainTypes("test", emptyList())).contentHash,
                version.contentHash,
            )
        }

        @Test
        fun `the schema name is still captured when only a subset is governed`() {
            val version = MetamodelVersion.from(
                DataDictionary.fromDomainTypes("my-schema", listOf(DynamicType("Person"), DynamicType("Sighting"))),
                governed,
            )
            assertEquals("my-schema", version.schemaName)
        }
    }

    @Nested
    inner class Aliases {

        private fun personWith(vararg properties: PropertyDefinition): DataDictionary =
            DataDictionary.fromDomainTypes(
                "test",
                listOf(DynamicType(name = "Person", ownProperties = properties.toList())),
            )

        private fun stamp(dictionary: DataDictionary, aliases: SchemaAliases): MetamodelVersion =
            MetamodelVersion.from(dictionary, GovernedTypeSelector.ALL, aliases)

        @Test
        fun `a declared property alias lands on the signature`() {
            val version = stamp(
                personWith(ValuePropertyDefinition("emailAddress")),
                SchemaAliases(propertyAliases = mapOf("Person" to mapOf("emailAddress" to setOf("email")))),
            )

            assertEquals(
                setOf(
                    PropertySignature(
                        "emailAddress",
                        PropertySignature.Kind.VALUE,
                        "string",
                        Cardinality.ONE,
                        setOf("email"),
                    ),
                ),
                version.entityTypeProperties["Person"],
            )
        }

        @Test
        fun `declaring a property alias changes the hash`() {
            val plain = stamp(personWith(ValuePropertyDefinition("emailAddress")), SchemaAliases.NONE)
            val aliased = stamp(
                personWith(ValuePropertyDefinition("emailAddress")),
                SchemaAliases(propertyAliases = mapOf("Person" to mapOf("emailAddress" to setOf("email")))),
            )

            assertNotEquals(plain.contentHash, aliased.contentHash)
            assertFalse(plain.hasSameContentAs(aliased))
        }

        @Test
        fun `the order aliases are declared in does not affect the hash`() {
            val forwards = stamp(
                personWith(ValuePropertyDefinition("emailAddress")),
                SchemaAliases(
                    propertyAliases = mapOf("Person" to mapOf("emailAddress" to linkedSetOf("email", "contact"))),
                ),
            )
            val backwards = stamp(
                personWith(ValuePropertyDefinition("emailAddress")),
                SchemaAliases(
                    propertyAliases = mapOf("Person" to mapOf("emailAddress" to linkedSetOf("contact", "email"))),
                ),
            )

            assertEquals(forwards.contentHash, backwards.contentHash)
        }

        @Test
        fun `different alias sets on one property hash differently`() {
            val one = stamp(
                personWith(ValuePropertyDefinition("emailAddress")),
                SchemaAliases(propertyAliases = mapOf("Person" to mapOf("emailAddress" to setOf("email")))),
            )
            val two = stamp(
                personWith(ValuePropertyDefinition("emailAddress")),
                SchemaAliases(propertyAliases = mapOf("Person" to mapOf("emailAddress" to setOf("email", "contact")))),
            )

            assertNotEquals(one.contentHash, two.contentHash)
        }

        @Test
        fun `an alias containing the block delimiter does not collide with a split set`() {
            // Same reasoning as the property-name case: alias entries are length-prefixed, so
            // ["a;b"] and ["a", "b"] can't serialise to the same bytes.
            val joined = stamp(
                personWith(ValuePropertyDefinition("emailAddress")),
                SchemaAliases(propertyAliases = mapOf("Person" to mapOf("emailAddress" to setOf("a;b")))),
            )
            val split = stamp(
                personWith(ValuePropertyDefinition("emailAddress")),
                SchemaAliases(propertyAliases = mapOf("Person" to mapOf("emailAddress" to setOf("a", "b")))),
            )

            assertNotEquals(joined.contentHash, split.contentHash)
        }

        @Test
        fun `an alias for a property the type doesn't have changes nothing`() {
            val plain = stamp(personWith(ValuePropertyDefinition("age")), SchemaAliases.NONE)
            val stale = stamp(
                personWith(ValuePropertyDefinition("age")),
                SchemaAliases(propertyAliases = mapOf("Person" to mapOf("retired" to setOf("gone")))),
            )

            assertEquals(plain.contentHash, stale.contentHash)
        }

        @Test
        fun `an alias equal to the property's own name is kept and hashed`() {
            // It matches nothing at diff time — nothing looks data up by property name — so it is
            // inert there. It is still part of the signature, so it moves the hash.
            val plain = stamp(personWith(ValuePropertyDefinition("age")), SchemaAliases.NONE)
            val selfAliased = stamp(
                personWith(ValuePropertyDefinition("age")),
                SchemaAliases(propertyAliases = mapOf("Person" to mapOf("age" to setOf("age")))),
            )

            assertEquals(setOf("age"), selfAliased.entityTypeProperties["Person"]!!.single().aliases)
            assertNotEquals(plain.contentHash, selfAliased.contentHash)
        }

        @Test
        fun `a declared type alias is carried and changes the hash`() {
            val plain = stamp(personWith(), SchemaAliases.NONE)
            val aliased = stamp(personWith(), SchemaAliases(typeAliases = mapOf("Person" to setOf("Human"))))

            assertEquals(mapOf("Person" to setOf("Human")), aliased.entityTypeAliases)
            assertEquals(emptyMap<String, Set<String>>(), plain.entityTypeAliases)
            assertNotEquals(plain.contentHash, aliased.contentHash)
        }

        @Test
        fun `type alias order does not affect the hash`() {
            val forwards = stamp(personWith(), SchemaAliases(typeAliases = mapOf("Person" to linkedSetOf("Human", "Actor"))))
            val backwards = stamp(personWith(), SchemaAliases(typeAliases = mapOf("Person" to linkedSetOf("Actor", "Human"))))

            assertEquals(forwards.contentHash, backwards.contentHash)
        }

        @Test
        fun `a type alias and a property alias of the same name hash differently`() {
            // The two blocks carry different tags and sit in different places, so declaring "old"
            // as a former type name is a different schema from declaring it as a former property
            // name.
            val asTypeAlias = stamp(
                personWith(ValuePropertyDefinition("age")),
                SchemaAliases(typeAliases = mapOf("Person" to setOf("old"))),
            )
            val asPropertyAlias = stamp(
                personWith(ValuePropertyDefinition("age")),
                SchemaAliases(propertyAliases = mapOf("Person" to mapOf("age" to setOf("old")))),
            )

            assertNotEquals(asTypeAlias.contentHash, asPropertyAlias.contentHash)
        }

        @Test
        fun `aliases accumulate across successive renames`() {
            val version = stamp(
                DataDictionary.fromDomainTypes("test", listOf(DynamicType("C"))),
                SchemaAliases(typeAliases = mapOf("C" to setOf("A", "B"))),
            )

            assertEquals(setOf("A", "B"), version.entityTypeAliases["C"])
        }

        @Test
        fun `a type may list its own name, which is what a rename and back leaves behind`() {
            // A renamed to B and back to A accumulates {A, B}, so the alias set holds the current
            // name. The reuse guard is about other types' names.
            val version = stamp(
                DataDictionary.fromDomainTypes("test", listOf(DynamicType("A"))),
                SchemaAliases(typeAliases = mapOf("A" to setOf("A", "B"))),
            )

            assertEquals(setOf("A", "B"), version.entityTypeAliases["A"])
        }

        @Test
        fun `aliases for an ungoverned type are dropped`() {
            // Everything else about an ungoverned type is invisible to the stamp; aliases follow.
            val dictionary = DataDictionary.fromDomainTypes(
                "test",
                listOf(
                    DynamicType("Person"),
                    DynamicType(name = "Sighting", ownProperties = listOf(ValuePropertyDefinition("seenAt"))),
                ),
            )
            val governed = GovernedTypeSelector { it.name == "Person" }

            val plain = MetamodelVersion.from(dictionary, governed)
            val aliased = MetamodelVersion.from(
                dictionary,
                governed,
                SchemaAliases(
                    typeAliases = mapOf("Sighting" to setOf("Observation")),
                    propertyAliases = mapOf("Sighting" to mapOf("seenAt" to setOf("spottedAt"))),
                ),
            )

            assertEquals(emptyMap<String, Set<String>>(), aliased.entityTypeAliases)
            assertEquals(plain.contentHash, aliased.contentHash)
        }

        @Test
        fun `an explicitly empty alias set is dropped rather than hashed`() {
            val plain = stamp(personWith(ValuePropertyDefinition("age")), SchemaAliases.NONE)
            val declaredEmpty = stamp(
                personWith(ValuePropertyDefinition("age")),
                SchemaAliases(
                    typeAliases = mapOf("Person" to emptySet()),
                    propertyAliases = mapOf("Person" to mapOf("age" to emptySet())),
                ),
            )

            assertEquals(emptyMap<String, Set<String>>(), declaredEmpty.entityTypeAliases)
            assertEquals(plain.contentHash, declaredEmpty.contentHash)
        }
    }

    @Nested
    inner class SignatureOrdering {

        private fun signature(name: String, aliases: Set<String> = emptySet()): PropertySignature =
            PropertySignature(name, PropertySignature.Kind.VALUE, "string", Cardinality.ONE, aliases)

        @Test
        fun `aliases break ties only after name, kind, type and cardinality`() {
            val aliasedAge = signature("age", setOf("zzz"))
            val plainEmail = signature("email")

            // The name still decides, whatever the aliases say.
            assertTrue(aliasedAge < plainEmail)

            val aliasedString = PropertySignature(
                "age", PropertySignature.Kind.VALUE, "string", Cardinality.ONE, setOf("zzz"),
            )
            val plainInteger = PropertySignature(
                "age", PropertySignature.Kind.VALUE, "integer", Cardinality.ONE,
            )
            assertTrue(plainInteger < aliasedString)
        }

        @Test
        fun `signatures differing only in aliases sort deterministically`() {
            val none = signature("age")
            val one = signature("age", setOf("b"))
            val two = signature("age", setOf("a1", "b"))

            assertTrue(none < two)
            assertTrue(two < one)
            assertEquals(listOf(none, two, one), listOf(one, none, two).sorted())
            assertEquals(listOf(none, two, one), listOf(two, one, none).sorted())
        }

        @Test
        fun `alias order inside the set does not change the ordering`() {
            val forwards = signature("age", linkedSetOf("a", "b"))
            val backwards = signature("age", linkedSetOf("b", "a"))

            assertEquals(0, forwards.compareTo(backwards))
        }
    }

    @Nested
    inner class AliasGuards {

        private fun personTwice(vararg propertyTypes: String): DataDictionary =
            DataDictionary.fromDomainTypes(
                "test",
                propertyTypes.map { propertyType ->
                    DynamicType(
                        name = "Person",
                        ownProperties = listOf(ValuePropertyDefinition("age", type = propertyType)),
                    )
                },
            )

        @Test
        fun `type aliases keyed by a type that is not listed are rejected`() {
            val thrown = assertThrows<IllegalArgumentException> {
                MetamodelVersion(
                    schemaName = "test",
                    entityTypeNames = listOf("Person"),
                    entityTypeLabels = emptyMap(),
                    entityTypeProperties = emptyMap(),
                    relationshipNames = emptyList(),
                    entityTypeAliases = mapOf("Ghost" to setOf("Spectre")),
                )
            }
            assertTrue(thrown.message!!.contains("entityTypeAliases"), thrown.message)
            assertTrue(thrown.message!!.contains("Ghost"), thrown.message)
        }

        @Test
        fun `an empty type alias set is rejected`() {
            // An entry with no former names in it hashes differently from having no entry at all,
            // while meaning the same thing, so two stamps of one schema could land on two keys.
            val thrown = assertThrows<IllegalArgumentException> {
                MetamodelVersion(
                    schemaName = "test",
                    entityTypeNames = listOf("Person"),
                    entityTypeLabels = emptyMap(),
                    entityTypeProperties = emptyMap(),
                    relationshipNames = emptyList(),
                    entityTypeAliases = mapOf("Person" to emptySet()),
                )
            }
            assertTrue(thrown.message!!.contains("empty alias sets"), thrown.message)
            assertTrue(thrown.message!!.contains("Person"), thrown.message)
        }

        @Test
        fun `a declared type name in another type's alias set is rejected by the constructor`() {
            val thrown = assertThrows<IllegalArgumentException> {
                MetamodelVersion(
                    schemaName = "test",
                    entityTypeNames = listOf("Human", "Person"),
                    entityTypeLabels = emptyMap(),
                    entityTypeProperties = emptyMap(),
                    relationshipNames = emptyList(),
                    entityTypeAliases = mapOf("Human" to setOf("Person")),
                )
            }
            assertTrue(thrown.message!!.contains("Human"), thrown.message)
            assertTrue(thrown.message!!.contains("Person"), thrown.message)
            assertTrue(thrown.message!!.contains("Retire the alias"), thrown.message)
        }

        @Test
        fun `a declared type name in another type's alias set is rejected at the stamping seam`() {
            val dictionary = DataDictionary.fromDomainTypes(
                "test",
                listOf(DynamicType("Human"), DynamicType("Person")),
            )

            val thrown = assertThrows<IllegalArgumentException> {
                MetamodelVersion.from(
                    dictionary,
                    GovernedTypeSelector.ALL,
                    SchemaAliases(typeAliases = mapOf("Human" to setOf("Person"))),
                )
            }
            assertTrue(thrown.message!!.contains("Person"), thrown.message)
        }

        @Test
        fun `reusing the name of an ungoverned type is allowed, because the stamp never sees it`() {
            val dictionary = DataDictionary.fromDomainTypes(
                "test",
                listOf(DynamicType("Human"), DynamicType("Person")),
            )

            val version = MetamodelVersion.from(
                dictionary,
                GovernedTypeSelector { it.name == "Human" },
                SchemaAliases(typeAliases = mapOf("Human" to setOf("Person"))),
            )
            assertEquals(setOf("Person"), version.entityTypeAliases["Human"])
        }

        @Test
        fun `aliases on a property name with more than one signature are rejected by the constructor`() {
            val thrown = assertThrows<IllegalArgumentException> {
                MetamodelVersion(
                    schemaName = "test",
                    entityTypeNames = listOf("Person"),
                    entityTypeLabels = emptyMap(),
                    entityTypeProperties = mapOf(
                        "Person" to setOf(
                            PropertySignature(
                                "age", PropertySignature.Kind.VALUE, "string", Cardinality.ONE, setOf("years"),
                            ),
                            PropertySignature(
                                "age", PropertySignature.Kind.VALUE, "integer", Cardinality.ONE, setOf("years"),
                            ),
                        ),
                    ),
                    relationshipNames = emptyList(),
                )
            }
            assertTrue(thrown.message!!.contains("age"), thrown.message)
            assertTrue(thrown.message!!.contains("years"), thrown.message)
            assertTrue(thrown.message!!.contains("Retire the alias"), thrown.message)
        }

        @Test
        fun `aliases on a property name with more than one signature are rejected at the stamping seam`() {
            // Two same-named Person declarations each carry their own `age`, so the union holds two
            // signatures for one name and an old name can't say which it meant.
            val thrown = assertThrows<IllegalArgumentException> {
                MetamodelVersion.from(
                    personTwice("string", "integer"),
                    GovernedTypeSelector.ALL,
                    SchemaAliases(propertyAliases = mapOf("Person" to mapOf("age" to setOf("years")))),
                )
            }
            assertTrue(thrown.message!!.contains("age"), thrown.message)
            assertTrue(thrown.message!!.contains("years"), thrown.message)
        }

        @Test
        fun `a duplicated property name with no aliases declared is fine`() {
            val version = MetamodelVersion.from(personTwice("string", "integer"))
            assertEquals(2, version.entityTypeProperties["Person"]!!.size)
        }

        @Test
        fun `aliases on a single-signature property survive a duplicate elsewhere on the type`() {
            val dictionary = DataDictionary.fromDomainTypes(
                "test",
                listOf(
                    DynamicType(
                        name = "Person",
                        ownProperties = listOf(
                            ValuePropertyDefinition("age", type = "string"),
                            ValuePropertyDefinition("emailAddress"),
                        ),
                    ),
                    DynamicType(
                        name = "Person",
                        ownProperties = listOf(ValuePropertyDefinition("age", type = "integer")),
                    ),
                ),
            )

            val version = MetamodelVersion.from(
                dictionary,
                GovernedTypeSelector.ALL,
                SchemaAliases(propertyAliases = mapOf("Person" to mapOf("emailAddress" to setOf("email")))),
            )

            assertEquals(
                setOf("email"),
                version.entityTypeProperties["Person"]!!.single { it.name == "emailAddress" }.aliases,
            )
        }
    }

    @Nested
    inner class AliasImmutability {

        @Test
        fun `the alias collections a stamp hands back cannot be mutated`() {
            val version = MetamodelVersion.from(
                DataDictionary.fromDomainTypes(
                    "test",
                    listOf(DynamicType(name = "Person", ownProperties = listOf(ValuePropertyDefinition("age")))),
                ),
                GovernedTypeSelector.ALL,
                SchemaAliases(
                    typeAliases = mapOf("Person" to setOf("Human")),
                    propertyAliases = mapOf("Person" to mapOf("age" to setOf("years"))),
                ),
            )

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (version.entityTypeAliases as MutableMap<String, Set<String>>).remove("Person")
            }

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (version.entityTypeAliases["Person"] as MutableSet<String>).add("Sneaky")
            }

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (version.entityTypeProperties["Person"]!!.single().aliases as MutableSet<String>).add("Sneaky")
            }
        }

        @Test
        fun `mutating the alias sets the caller passed in does not change the stamp`() {
            val typeAliases = mutableSetOf("Human")
            val signatureAliases = mutableSetOf("years")
            val version = MetamodelVersion(
                schemaName = "test",
                entityTypeNames = listOf("Person"),
                entityTypeLabels = emptyMap(),
                entityTypeProperties = mapOf(
                    "Person" to setOf(
                        PropertySignature(
                            "age", PropertySignature.Kind.VALUE, "string", Cardinality.ONE, signatureAliases,
                        ),
                    ),
                ),
                relationshipNames = emptyList(),
                entityTypeAliases = mapOf("Person" to typeAliases),
            )
            val hashAtConstruction = version.contentHash

            typeAliases.add("Actor")
            signatureAliases.add("yearsOld")

            assertEquals(setOf("Human"), version.entityTypeAliases["Person"])
            assertEquals(setOf("years"), version.entityTypeProperties["Person"]!!.single().aliases)
            assertEquals(hashAtConstruction, version.contentHash)
        }

        @Test
        fun `filling in an alias set that was empty at construction does not change the stamp`() {
            // The empty case is the dangerous one. A signature built with an empty mutable set that
            // the stamp stored by reference would change its own hashCode when the caller added an
            // alias, leaving it unfindable in the hash-based set holding it and disagreeing with a
            // contentHash computed while it looked alias-free.
            val aliases = mutableSetOf<String>()
            val signature = PropertySignature(
                "age", PropertySignature.Kind.VALUE, "string", Cardinality.ONE, aliases,
            )
            val version = MetamodelVersion(
                schemaName = "test",
                entityTypeNames = listOf("Person"),
                entityTypeLabels = emptyMap(),
                entityTypeProperties = mapOf("Person" to setOf(signature)),
                relationshipNames = emptyList(),
            )
            val hashAtConstruction = version.contentHash

            aliases.add("years")

            val stored = version.entityTypeProperties["Person"]!!
            assertEquals(emptySet<String>(), stored.single().aliases)
            assertEquals(hashAtConstruction, version.contentHash)

            // The signature is still findable under the identity it was hashed with, so nothing has
            // shifted position in the set that holds it.
            assertTrue(
                stored.contains(
                    PropertySignature("age", PropertySignature.Kind.VALUE, "string", Cardinality.ONE)
                ),
            )

            // And the stamp still hashes as the alias-free schema it was built from.
            val neverAliased = MetamodelVersion(
                schemaName = "test",
                entityTypeNames = listOf("Person"),
                entityTypeLabels = emptyMap(),
                entityTypeProperties = mapOf(
                    "Person" to setOf(
                        PropertySignature("age", PropertySignature.Kind.VALUE, "string", Cardinality.ONE)
                    ),
                ),
                relationshipNames = emptyList(),
            )
            assertEquals(neverAliased.contentHash, version.contentHash)
        }

        @Test
        fun `an initially empty alias set is replaced by an immutable one`() {
            val signature = PropertySignature(
                "age", PropertySignature.Kind.VALUE, "string", Cardinality.ONE, mutableSetOf(),
            )
            val version = MetamodelVersion(
                schemaName = "test",
                entityTypeNames = listOf("Person"),
                entityTypeLabels = emptyMap(),
                entityTypeProperties = mapOf("Person" to setOf(signature)),
                relationshipNames = emptyList(),
            )

            @Suppress("UNCHECKED_CAST")
            assertThrows<UnsupportedOperationException> {
                (version.entityTypeProperties["Person"]!!.single().aliases as MutableSet<String>)
                    .add("Sneaky")
            }
        }
    }
}

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
package com.embabel.dice.storage

import com.embabel.agent.core.Cardinality
import com.embabel.dice.metamodel.MetamodelVersion
import com.embabel.dice.metamodel.PropertySignature
import com.embabel.dice.metamodel.PropertySignature.Kind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

/**
 * Unit tests for [MetamodelVersionRowMapper]: no database, just the property map it produces and
 * consumes.
 *
 * Most of these pin strict reads. A stored node missing a property the mapper wrote is corrupt, and
 * the mapper has to throw: the store wraps every read in "skip the unreadable row and warn", and
 * that guard needs unreadable rows to throw. `DrivineMetamodelVersionStoreIntegrationTest` shows
 * the guard firing end to end; these cover the mapper's half of the contract, including the cases
 * the store's own `MATCH` filters out before they can reach it.
 */
class MetamodelRowMapperTest {

    private val version = MetamodelVersion(
        schemaName = "test-schema",
        entityTypeNames = listOf("Person", "Company"),
        entityTypeLabels = mapOf("Person" to setOf("Agent"), "Company" to setOf("Org")),
        entityTypeProperties = mapOf(
            "Person" to setOf(
                PropertySignature("name", Kind.VALUE, "string", Cardinality.ONE),
                PropertySignature("age", Kind.VALUE, "integer", Cardinality.OPTIONAL),
            ),
            "Company" to setOf(PropertySignature("employs", Kind.REFERENCE, "Person", Cardinality.SET)),
        ),
        relationshipNames = listOf("WORKS_FOR"),
    )

    /** The same schema with both kinds of alias declared on it. */
    private val aliased = MetamodelVersion(
        schemaName = "aliased-schema",
        entityTypeNames = listOf("Organisation"),
        entityTypeLabels = mapOf("Organisation" to setOf("Entity")),
        entityTypeProperties = mapOf(
            "Organisation" to setOf(
                PropertySignature("legalName", Kind.VALUE, "string", Cardinality.ONE, setOf("companyName", "name")),
                PropertySignature("staff", Kind.REFERENCE, "Person", Cardinality.SET),
            ),
        ),
        relationshipNames = listOf("Organisation-[EMPLOYS]->Person"),
        entityTypeAliases = mapOf("Organisation" to setOf("Company", "Firm")),
    )

    private val savedAt = Instant.parse("2026-01-01T00:00:00.500Z")

    private fun row(savedAtInstant: Instant = savedAt): MutableMap<String, Any?> =
        MetamodelVersionRowMapper.bindMap(version, savedAtInstant).toMutableMap()

    @Test
    fun `a version round-trips through its own property map`() {
        assertEquals(version, MetamodelVersionRowMapper.fromRow(row()))
    }

    @Test
    fun `property signatures are written as explicit named fields, enums by name, in a fixed order`() {
        // The encoding on disk feeds the content hash on the way back in, so it is a persisted
        // format, and this is where its shape is pinned:
        //  - enum names, so inserting a constant into Cardinality can't re-point a stored ordinal;
        //  - map keys sorted (Company before Person, though Person was declared first);
        //  - signatures within a type sorted (age before name).
        // The sorting is why re-saving an unchanged version writes byte-identical JSON. Left
        // unsorted, the order would come from `java.util.Set.copyOf`, whose iteration order is
        // randomised per JVM, so the same stamp would encode differently after every restart.
        assertEquals(
            """{"Company":[{"name":"employs","kind":"REFERENCE","type":"Person","cardinality":"SET"}],""" +
                """"Person":[{"name":"age","kind":"VALUE","type":"integer","cardinality":"OPTIONAL"},""" +
                """{"name":"name","kind":"VALUE","type":"string","cardinality":"ONE"}]}""",
            row()["entityTypeProperties"],
        )
        assertEquals("""["Company","Person"]""", row()["entityTypeNames"])
        assertEquals("""{"Company":["Org"],"Person":["Agent"]}""", row()["entityTypeLabels"])
    }

    @Test
    fun `bindMap stamps the instant it is given, not the wall clock`() {
        assertEquals(savedAt.toString(), row()["savedAt"])
        assertEquals(savedAt.toEpochMilli(), row()["savedAtEpochMillis"])
    }

    @Test
    fun `every timestamp gets a sortable numeric twin, because the ISO string is not sortable`() {
        // Half a second apart, and the older one has no fractional part. As strings the older sorts
        // higher, because 'Z' outranks '.'; as numbers it sorts lower. Anything ordering on the
        // string hands back the wrong row.
        val older = row(Instant.parse("2026-01-01T00:00:00Z"))
        val newer = row(Instant.parse("2026-01-01T00:00:00.500Z"))

        assertTrue(older["savedAt"].toString() > newer["savedAt"].toString(), "the string order is backwards")
        assertTrue(
            (older["savedAtEpochMillis"] as Long) < (newer["savedAtEpochMillis"] as Long),
            "the numeric order is the true one",
        )
    }

    @Test
    fun `every property the mapper writes is required when reading it back`() {
        // schemaName is in the list on purpose. The store's readers all MATCH on schemaName, so a
        // node without one is filtered out upstream and never reaches the mapper; the mapper is
        // where this contract lives, so it is pinned here.
        listOf("schemaName", "contentHash", "entityTypeNames", "entityTypeLabels", "entityTypeProperties", "relationshipNames")
            .forEach { property ->
                val corrupt = row().apply { remove(property) }
                val thrown = assertThrows<IllegalArgumentException>("removing '$property' must fail the read") {
                    MetamodelVersionRowMapper.fromRow(corrupt)
                }
                assertTrue(thrown.message!!.contains(property), "the failure must name '$property': ${thrown.message}")
            }
    }

    @Test
    fun `a property signature missing a field fails the read, naming the field and the type`() {
        val corrupt = row().apply {
            put("entityTypeProperties", """{"Person":[{"name":"name","kind":"VALUE","type":"string"}]}""")
        }

        val thrown = assertThrows<IllegalArgumentException> { MetamodelVersionRowMapper.fromRow(corrupt) }
        assertTrue(thrown.message!!.contains("cardinality"), thrown.message)
        assertTrue(thrown.message!!.contains("Person"), thrown.message)
    }

    @Test
    fun `a property signature naming an enum constant this build does not have fails the read`() {
        // A node written by a build whose Cardinality had a constant ours doesn't. Substituting a
        // default would change the content and then fail the integrity check with a message about
        // hashes; failing here says what actually happened.
        val corrupt = row().apply {
            put(
                "entityTypeProperties",
                """{"Person":[{"name":"name","kind":"VALUE","type":"string","cardinality":"MANY_ISH"}]}""",
            )
        }

        val thrown = assertThrows<IllegalArgumentException> { MetamodelVersionRowMapper.fromRow(corrupt) }
        assertTrue(thrown.message!!.contains("MANY_ISH"), thrown.message)
        assertTrue(thrown.message!!.contains("Cardinality"), thrown.message)
    }

    @Test
    fun `a stored hash that disagrees with the stored fields fails the integrity check`() {
        // contentHash is derived from the structural fields, so the copy on the node is a checksum.
        // Rewriting the fields underneath it (an old hash format, a hand-edit) has to be caught.
        val corrupt = row().apply { put("relationshipNames", """["SOMETHING_ELSE"]""") }

        val thrown = assertThrows<IllegalArgumentException> { MetamodelVersionRowMapper.fromRow(corrupt) }
        assertTrue(thrown.message!!.contains("integrity check"), "the failure must say what went wrong: ${thrown.message}")
    }

    @Test
    fun `an empty schema round-trips as empty, not as null`() {
        val empty = MetamodelVersion("empty-schema", emptyList(), emptyMap(), emptyMap(), emptyList())

        assertEquals(empty, MetamodelVersionRowMapper.fromRow(MetamodelVersionRowMapper.bindMap(empty, savedAt)))
    }

    // ---- Aliases: written only when declared, absent read as none ----

    @Test
    fun `a version declaring no aliases binds neither alias field`() {
        // The four-field signature encoding and the absent alias map are what a writer from before
        // aliases existed produced. Keeping the empty case byte-identical is what lets old nodes
        // and new ones share a natural key.
        assertNull(row()["entityTypeAliases"], "an empty alias map must leave no property behind")
        assertEquals(
            """{"Company":[{"name":"employs","kind":"REFERENCE","type":"Person","cardinality":"SET"}],""" +
                """"Person":[{"name":"age","kind":"VALUE","type":"integer","cardinality":"OPTIONAL"},""" +
                """{"name":"name","kind":"VALUE","type":"string","cardinality":"ONE"}]}""",
            row()["entityTypeProperties"],
        )
    }

    @Test
    fun `both kinds of alias are written when declared, sorted, and round-trip`() {
        val bound = MetamodelVersionRowMapper.bindMap(aliased, savedAt)

        assertEquals("""{"Organisation":["Company","Firm"]}""", bound["entityTypeAliases"])
        assertEquals(
            """{"Organisation":[{"name":"legalName","kind":"VALUE","type":"string","cardinality":"ONE",""" +
                """"aliases":["companyName","name"]},""" +
                """{"name":"staff","kind":"REFERENCE","type":"Person","cardinality":"SET"}]}""",
            bound["entityTypeProperties"],
            "a signature with no former names keeps exactly four fields",
        )

        val reloaded = MetamodelVersionRowMapper.fromRow(bound)
        assertEquals(aliased, reloaded)
        assertEquals(aliased.contentHash, reloaded.contentHash)
        assertEquals(mapOf("Organisation" to setOf("Company", "Firm")), reloaded.entityTypeAliases)
        assertEquals(
            setOf("companyName", "name"),
            reloaded.entityTypeProperties["Organisation"]!!.single { it.name == "legalName" }.aliases,
        )
    }

    @Test
    fun `dropping the stored alias map fails the integrity check`() {
        // Aliases feed the content hash, so this is the failure mode a mapper that forgot to write
        // the map would produce on every read: the stamp is unreadable, not silently alias-free.
        val corrupt = MetamodelVersionRowMapper.bindMap(aliased, savedAt).toMutableMap()
            .apply { remove("entityTypeAliases") }

        val thrown = assertThrows<IllegalArgumentException> { MetamodelVersionRowMapper.fromRow(corrupt) }
        assertTrue(thrown.message!!.contains("integrity check"), thrown.message)
    }

    @Test
    fun `dropping a signature's stored aliases fails the integrity check`() {
        val corrupt = MetamodelVersionRowMapper.bindMap(aliased, savedAt).toMutableMap().apply {
            put(
                "entityTypeProperties",
                """{"Organisation":[{"name":"legalName","kind":"VALUE","type":"string","cardinality":"ONE"},""" +
                    """{"name":"staff","kind":"REFERENCE","type":"Person","cardinality":"SET"}]}""",
            )
        }

        val thrown = assertThrows<IllegalArgumentException> { MetamodelVersionRowMapper.fromRow(corrupt) }
        assertTrue(thrown.message!!.contains("integrity check"), thrown.message)
    }

    @Test
    fun `a stored aliases field that is not a list of names fails the read, naming the type`() {
        val corrupt = row().apply {
            put(
                "entityTypeProperties",
                """{"Person":[{"name":"name","kind":"VALUE","type":"string","cardinality":"ONE","aliases":"nickname"}]}""",
            )
        }

        val thrown = assertThrows<IllegalArgumentException> { MetamodelVersionRowMapper.fromRow(corrupt) }
        assertTrue(thrown.message!!.contains("aliases"), thrown.message)
        assertTrue(thrown.message!!.contains("Person"), thrown.message)
    }

    @Test
    fun `a row with no alias property at all reads back as a stamp declaring none`() {
        // A node written before aliases existed. Removing the key is the same thing the graph does
        // when a property was never set.
        val old = row().apply { remove("entityTypeAliases") }

        val reloaded = MetamodelVersionRowMapper.fromRow(old)

        assertEquals(version, reloaded)
        assertEquals(emptyMap<String, Set<String>>(), reloaded.entityTypeAliases)
    }
}

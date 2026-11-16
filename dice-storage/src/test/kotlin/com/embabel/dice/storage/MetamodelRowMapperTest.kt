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
import com.embabel.dice.metamodel.DriftReport
import com.embabel.dice.metamodel.MetamodelChange
import com.embabel.dice.metamodel.MetamodelDiff
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

    @Test
    fun `a required JSON field holding the empty string fails the read`() {
        // The writer never produces "" for one of these fields: an empty list or map is written as
        // "[]" or "{}". A bare empty string only shows up through corruption, and it has to fail
        // loudly, not read back as an empty collection that hides the problem.
        val corrupt = row().apply { put("entityTypeNames", "") }

        assertThrows<Exception> { MetamodelVersionRowMapper.fromRow(corrupt) }
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

    /** A declared diff holding one change of each kind, between two versions that declare aliases. */
    private val declaredDiff: MetamodelDiff = run {
        val ageOne = PropertySignature("age", Kind.VALUE, "integer", Cardinality.ONE)
        val ageOptional = PropertySignature("age", Kind.VALUE, "integer", Cardinality.OPTIONAL)
        val tel = PropertySignature("tel", Kind.VALUE, "string", Cardinality.ONE)
        val phone = PropertySignature("phone", Kind.VALUE, "string", Cardinality.ONE, setOf("tel"))
        val fax = PropertySignature("fax", Kind.VALUE, "string", Cardinality.OPTIONAL)
        val before = MetamodelVersion(
            schemaName = "diff-schema",
            entityTypeNames = listOf("Person", "Company", "Ledger"),
            entityTypeLabels = mapOf("Person" to setOf("Entity", "Legacy")),
            entityTypeProperties = mapOf("Person" to setOf(ageOne, tel, fax)),
            relationshipNames = listOf("Person-[worksAt]->Firm"),
        )
        val after = MetamodelVersion(
            schemaName = "diff-schema",
            entityTypeNames = listOf("Human", "Company", "Invoice"),
            entityTypeLabels = mapOf("Human" to setOf("Entity", "Agent")),
            entityTypeProperties = mapOf("Human" to setOf(ageOptional, phone)),
            relationshipNames = listOf("Human-[worksAt]->Company"),
            entityTypeAliases = mapOf("Human" to setOf("Person")),
        )
        MetamodelDiff(
            before,
            after,
            listOf(
                MetamodelChange.EntityTypeAdded("Invoice"),
                MetamodelChange.EntityTypeRemoved("Ledger"),
                MetamodelChange.EntityTypeRenamed("Person", "Human"),
                MetamodelChange.AmbiguousEntityTypeRename(setOf("Firm"), setOf("Vendor", "Company")),
                MetamodelChange.EntityTypeAliasesChanged("Human", emptySet(), setOf("Person")),
                MetamodelChange.EntityTypeModified("Human", setOf("Agent"), setOf("Legacy"), setOf(phone), setOf(fax)),
                MetamodelChange.PropertyRenamed("Human", tel, phone),
                MetamodelChange.AmbiguousPropertyRename("Human", setOf("tel"), setOf("phone", "mobile")),
                MetamodelChange.PropertySignatureChanged("Human", "age", ageOne, ageOptional),
                MetamodelChange.RelationshipAdded("Human-[worksAt]->Company"),
                MetamodelChange.RelationshipRemoved("Person-[worksAt]->Firm"),
            ),
        )
    }

    /**
     * A drift report captured against [declaredDiff]'s later version.
     *
     * @param diff The declared diff the report carries, or `null` for none.
     * @return The report.
     */
    private fun report(diff: MetamodelDiff?): DriftReport = DriftReport(
        schemaName = "diff-schema",
        versionHash = declaredDiff.toVersion.contentHash,
        driftedEntityTypes = setOf("Ghost"),
        driftedRelationshipTypes = emptySet(),
        capturedAt = savedAt,
        declaredDiff = diff,
    )

    @Test
    fun `a report's declared diff round-trips with every kind of change`() {
        val report = report(declaredDiff)

        val reloaded = DriftReportRowMapper.fromRow(DriftReportRowMapper.bindMap(report))

        assertEquals(report, reloaded)
        assertEquals(declaredDiff, reloaded.declaredDiff)
    }

    @Test
    fun `a report with no declared diff binds none and a row without one reads back with none`() {
        val report = report(null)
        val bound = DriftReportRowMapper.bindMap(report)

        assertNull(bound["declaredDiff"])
        val reloaded = DriftReportRowMapper.fromRow(bound.filterKeys { it != "declaredDiff" })
        assertEquals(report, reloaded)
        assertNull(reloaded.declaredDiff)
    }

    @Test
    fun `a stored declared diff that cannot be read fails the read, naming the property`() {
        val stored = DriftReportRowMapper.bindMap(report(declaredDiff))
        val written = stored["declaredDiff"] as String
        val unknownKind = written.replace("\"kind\":\"EntityTypeAdded\"", "\"kind\":\"SomethingNew\"")
        assertTrue(unknownKind != written, "the fixture must hold an EntityTypeAdded change to rename")

        listOf("{not json", unknownKind).forEach { declared ->
            val thrown = assertThrows<IllegalArgumentException> {
                DriftReportRowMapper.fromRow(stored + ("declaredDiff" to declared))
            }
            assertTrue(thrown.message!!.contains("declaredDiff"), thrown.message)
        }
    }

    /** The stored text of [declaredDiff]'s two versions, everything in the document before its changes. */
    private val storedDiffHead =
        """{"version":1,""" +
            """"fromVersion":{"schemaName":"diff-schema","entityTypeNames":["Company","Ledger","Person"],""" +
            """"entityTypeLabels":{"Person":["Entity","Legacy"]},"entityTypeProperties":{"Person":[""" +
            """{"name":"age","kind":"VALUE","type":"integer","cardinality":"ONE"},""" +
            """{"name":"fax","kind":"VALUE","type":"string","cardinality":"OPTIONAL"},""" +
            """{"name":"tel","kind":"VALUE","type":"string","cardinality":"ONE"}]},""" +
            """"relationshipNames":["Person-[worksAt]->Firm"]},""" +
            """"toVersion":{"schemaName":"diff-schema","entityTypeNames":["Company","Human","Invoice"],""" +
            """"entityTypeLabels":{"Human":["Agent","Entity"]},"entityTypeProperties":{"Human":[""" +
            """{"name":"age","kind":"VALUE","type":"integer","cardinality":"OPTIONAL"},""" +
            """{"name":"phone","kind":"VALUE","type":"string","cardinality":"ONE","aliases":["tel"]}]},""" +
            """"relationshipNames":["Human-[worksAt]->Company"],"entityTypeAliases":{"Human":["Person"]}},"""

    /** The stored text of each of [declaredDiff]'s changes, one per change kind, in the diff's order. */
    private val storedChanges = listOf(
        """{"kind":"EntityTypeAdded","typeName":"Invoice"}""",
        """{"kind":"EntityTypeRemoved","typeName":"Ledger"}""",
        """{"kind":"EntityTypeRenamed","before":"Person","after":"Human"}""",
        """{"kind":"AmbiguousEntityTypeRename","formerNames":["Firm"],"candidates":["Company","Vendor"]}""",
        """{"kind":"EntityTypeAliasesChanged","typeName":"Human","before":[],"after":["Person"]}""",
        """{"kind":"EntityTypeModified","typeName":"Human","addedLabels":["Agent"],"removedLabels":["Legacy"],""" +
            """"addedProperties":[{"name":"phone","kind":"VALUE","type":"string","cardinality":"ONE","aliases":["tel"]}],""" +
            """"removedProperties":[{"name":"fax","kind":"VALUE","type":"string","cardinality":"OPTIONAL"}]}""",
        """{"kind":"PropertyRenamed","typeName":"Human",""" +
            """"before":{"name":"tel","kind":"VALUE","type":"string","cardinality":"ONE"},""" +
            """"after":{"name":"phone","kind":"VALUE","type":"string","cardinality":"ONE","aliases":["tel"]}}""",
        """{"kind":"AmbiguousPropertyRename","typeName":"Human","formerNames":["tel"],"candidates":["mobile","phone"]}""",
        """{"kind":"PropertySignatureChanged","typeName":"Human","propertyName":"age",""" +
            """"before":{"name":"age","kind":"VALUE","type":"integer","cardinality":"ONE"},""" +
            """"after":{"name":"age","kind":"VALUE","type":"integer","cardinality":"OPTIONAL"}}""",
        """{"kind":"RelationshipAdded","descriptor":"Human-[worksAt]->Company"}""",
        """{"kind":"RelationshipRemoved","descriptor":"Person-[worksAt]->Firm"}""",
    )

    /** The exact `declaredDiff` value stored for [declaredDiff]. */
    private val storedDeclaredDiff = storedDiffHead + """"changes":[""" + storedChanges.joinToString(",") + "]}"

    @Test
    fun `a declared diff is stored as exactly the format 1 text, one entry per change kind`() {
        assertEquals(11, storedChanges.size)
        assertEquals(storedChanges.size, storedChanges.map { it.substringBefore("\",") }.toSet().size)

        val written = DriftReportRowMapper.bindMap(report(declaredDiff))["declaredDiff"] as String

        assertEquals(storedDeclaredDiff, written)
    }

    @Test
    fun `the format 1 text reads back as the declared diff it was written from`() {
        val stored = DriftReportRowMapper.bindMap(report(null)) + ("declaredDiff" to storedDeclaredDiff)

        assertEquals(declaredDiff, DriftReportRowMapper.fromRow(stored).declaredDiff)
    }

    @Test
    fun `fields a stored declared diff adds beyond format 1 are ignored on read`() {
        val withAddedFields = storedDeclaredDiff
            .replaceFirst("{", """{"addedLater":{"note":"top level"},""")
            .replace(""""schemaName":"diff-schema"""", """"schemaName":"diff-schema","addedLater":true""")
            .replace("""{"kind":"""", """{"addedLater":[1,2],"kind":"""")
            .replace(""""cardinality":"ONE"""", """"cardinality":"ONE","addedLater":"in a signature"""")
        assertEquals(1, Regex("\"addedLater\":\\{").findAll(withAddedFields).count())
        assertEquals(2, Regex("\"addedLater\":true").findAll(withAddedFields).count())
        assertEquals(11, Regex("\"addedLater\":\\[1,2]").findAll(withAddedFields).count())
        assertTrue(withAddedFields.contains("\"addedLater\":\"in a signature\""))
        val stored = DriftReportRowMapper.bindMap(report(null)) + ("declaredDiff" to withAddedFields)

        assertEquals(declaredDiff, DriftReportRowMapper.fromRow(stored).declaredDiff)
    }

    @Test
    fun `a stored declared diff in any format version other than 1 fails the read, naming the property`() {
        val stored = DriftReportRowMapper.bindMap(report(null))
        val unsupported = listOf(
            storedDeclaredDiff.replaceFirst("\"version\":1", "\"version\":2"),
            storedDeclaredDiff.replaceFirst("\"version\":1", "\"version\":0"),
            storedDeclaredDiff.replaceFirst("\"version\":1", "\"version\":\"1\""),
            storedDeclaredDiff.replaceFirst("\"version\":1,", ""),
        )
        assertTrue(unsupported.none { it == storedDeclaredDiff }, "every fixture must alter the version")

        unsupported.forEach { declared ->
            val thrown = assertThrows<IllegalArgumentException> {
                DriftReportRowMapper.fromRow(stored + ("declaredDiff" to declared))
            }
            assertTrue(thrown.message!!.contains("declaredDiff"), thrown.message)
            assertTrue(thrown.message!!.contains("version"), thrown.message)
        }
    }
}

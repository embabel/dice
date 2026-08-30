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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.agent.core.Cardinality
import com.embabel.dice.metamodel.MetamodelVersion
import com.embabel.dice.metamodel.PropertySignature
import com.embabel.dice.metamodel.PropertySignature.Kind
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration tests for [DrivineMetamodelVersionStore] against a Neo4j testcontainer. Each test
 * starts from an empty graph via [cleanUp].
 *
 * Runs against [Neo4jTestContainer], not Drivine's own built-in testcontainer -- see that class
 * for why.
 *
 * Note what's *absent* from every `MetamodelVersion` built here: a content hash. It's derived from
 * the structural fields, so two versions can't claim the same identity while describing different
 * schemas -- which is why the tests that need two distinct versions of one schema give them
 * genuinely different content rather than different hand-written hash strings.
 */
@SpringBootTest(classes = [TestApplication::class])
class DrivineMetamodelVersionStoreIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }

    @Autowired
    private lateinit var store: DrivineMetamodelVersionStore

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    @Autowired
    private lateinit var clock: PinnableClock

    @AfterEach
    fun cleanUp() {
        clock.unpin()
        listOf("MetamodelVersion", "MetamodelSchemaCounter").forEach { label ->
            persistenceManager.execute(QuerySpecification.withStatement("MATCH (n:$label) DETACH DELETE n"))
        }
    }

    // ---- CRUD ----

    @Test
    fun `a version persists and reads back every field`() {
        val version = MetamodelVersion(
            schemaName = "test-schema",
            entityTypeNames = listOf("Person", "Company", "Location"),
            entityTypeLabels = mapOf(
                "Person" to setOf("Agent", "Entity"),
                "Company" to setOf("Organization", "Entity"),
            ),
            entityTypeProperties = mapOf(
                "Person" to setOf(
                    PropertySignature("name", Kind.VALUE, "string", Cardinality.ONE),
                    PropertySignature("age", Kind.VALUE, "integer", Cardinality.OPTIONAL),
                ),
                "Company" to setOf(
                    PropertySignature("name", Kind.VALUE, "string", Cardinality.ONE),
                    PropertySignature("employs", Kind.REFERENCE, "Person", Cardinality.SET),
                ),
            ),
            relationshipNames = listOf("WORKS_FOR", "LOCATED_IN"),
        )

        store.saveVersion(version)

        val reloaded = store.latestVersion("test-schema")
        assertEquals(version, reloaded)
        assertEquals(version.contentHash, reloaded!!.contentHash)
    }

    @Test
    fun `version history returns empty for unknown schema`() {
        assertEquals(emptyList<MetamodelVersion>(), store.versionHistory("unknown-schema"))
        assertNull(store.latestVersion("unknown-schema"))
    }

    @Test
    fun `each schema sees only its own versions`() {
        val schemaA = MetamodelVersion("schema-a", listOf("TypeA"), emptyMap(), emptyMap(), emptyList())
        val schemaB = MetamodelVersion("schema-b", listOf("TypeB"), emptyMap(), emptyMap(), emptyList())

        store.saveVersion(schemaA)
        store.saveVersion(schemaB)

        assertEquals(schemaA, store.latestVersion("schema-a"))
        assertEquals(schemaB, store.latestVersion("schema-b"))
        assertEquals(listOf(schemaA), store.versionHistory("schema-a"))
    }

    // ---- Property signatures round-trip ----

    @Test
    fun `a property signature round-trips its kind, type and cardinality, not just its name`() {
        // The whole reason entityTypeProperties holds signatures rather than bare names: turning a
        // single `age` string into a list of integers is a real schema change. If the store dropped
        // kind/type/cardinality on the way to disk, the reloaded stamp would hash differently from
        // the one that was saved -- and the mapper's integrity check would reject its own write.
        val everyShape = setOf(
            PropertySignature("optionalString", Kind.VALUE, "string", Cardinality.OPTIONAL),
            PropertySignature("oneInteger", Kind.VALUE, "integer", Cardinality.ONE),
            PropertySignature("listOfDates", Kind.VALUE, "date", Cardinality.LIST),
            PropertySignature("setOfCompanies", Kind.REFERENCE, "Company", Cardinality.SET),
            PropertySignature("mystery", Kind.UNKNOWN, "", Cardinality.ONE),
        )
        val version = MetamodelVersion(
            schemaName = "signature-schema",
            entityTypeNames = listOf("Person"),
            entityTypeLabels = emptyMap(),
            entityTypeProperties = mapOf("Person" to everyShape),
            relationshipNames = emptyList(),
        )

        store.saveVersion(version)

        val reloaded = store.latestVersion("signature-schema")!!
        assertEquals(everyShape, reloaded.entityTypeProperties["Person"])
        assertEquals(version.contentHash, reloaded.contentHash)
    }

    @Test
    fun `two versions differing only in one property's cardinality are two stored versions`() {
        // Same type name, same property name -- the change is invisible to any encoding that stores
        // property *names*. It has to survive as two nodes with two hashes, or the store has
        // silently lost a schema change.
        val schemaName = "cardinality-change-schema"
        fun withCardinality(cardinality: Cardinality) = MetamodelVersion(
            schemaName = schemaName,
            entityTypeNames = listOf("Person"),
            entityTypeLabels = emptyMap(),
            entityTypeProperties = mapOf("Person" to setOf(PropertySignature("nickname", Kind.VALUE, "string", cardinality))),
            relationshipNames = emptyList(),
        )
        val one = withCardinality(Cardinality.ONE)
        val many = withCardinality(Cardinality.LIST)
        assertNotEquals(one.contentHash, many.contentHash, "precondition: the two stamps must differ")

        store.saveVersion(one)
        store.saveVersion(many)

        assertEquals(setOf(one, many), store.versionHistory(schemaName).toSet())
        assertEquals(Cardinality.ONE, store.findVersion(schemaName, one.contentHash)!!.entityTypeProperties["Person"]!!.single().cardinality)
        assertEquals(Cardinality.LIST, store.findVersion(schemaName, many.contentHash)!!.entityTypeProperties["Person"]!!.single().cardinality)
    }

    // ---- findVersion ----

    @Test
    fun `findVersion resolves a recorded hash back to the stamp it named`() {
        val schemaName = "find-schema"
        val v1 = MetamodelVersion(schemaName, listOf("Type1"), emptyMap(), emptyMap(), emptyList())
        val v2 = MetamodelVersion(schemaName, listOf("Type2"), emptyMap(), emptyMap(), emptyList())
        store.saveVersion(v1)
        store.saveVersion(v2)

        assertEquals(v1, store.findVersion(schemaName, v1.contentHash))
        assertEquals(v2, store.findVersion(schemaName, v2.contentHash))
    }

    @Test
    fun `findVersion is null for an unknown hash, and keyed on the schema name too`() {
        val v = MetamodelVersion("find-null-schema", listOf("Type1"), emptyMap(), emptyMap(), emptyList())
        store.saveVersion(v)

        assertNull(store.findVersion("find-null-schema", "no-such-hash"))
        // The hash is real, but it belongs to another schema: the natural key is the pair.
        assertNull(store.findVersion("some-other-schema", v.contentHash))
    }

    @Test
    fun `findVersion applies the same integrity check as the history read`() {
        val schemaName = "find-tampered-schema"
        val version = MetamodelVersion(schemaName, listOf("Original"), emptyMap(), emptyMap(), emptyList())
        store.saveVersion(version)
        tamperWithEntityTypeNames(schemaName, """["SwappedInBehindTheHash"]""")

        val (found, logged) = capturingStoreWarnings { store.findVersion(schemaName, version.contentHash) }

        assertNull(found, "a keyed lookup must not hand back a node that fails its own checksum")
        assertTrue(logged.any { it.contains("fails its integrity check") }, "warnings were: $logged")
    }

    // ---- Natural-key idempotency ----

    @Test
    fun `saving the same version twice leaves one node, not two`() {
        val version = MetamodelVersion(
            schemaName = "idempotent-schema",
            entityTypeNames = listOf("TypeA"),
            entityTypeLabels = mapOf("TypeA" to setOf("LabelA")),
            entityTypeProperties = mapOf("TypeA" to setOf(PropertySignature("prop", Kind.VALUE, "string", Cardinality.ONE))),
            relationshipNames = listOf("REL"),
        )

        store.saveVersion(version)
        store.saveVersion(version)

        val history = store.versionHistory("idempotent-schema")
        assertEquals(1, history.size)
        assertEquals(version, history.single())
    }

    @Test
    fun `re-saving an old version neither bumps the counter nor moves it in the history`() {
        val schemaName = "history-order-schema"
        val v1 = MetamodelVersion(schemaName, listOf("Type1"), emptyMap(), emptyMap(), emptyList())
        val v2 = MetamodelVersion(schemaName, listOf("Type2"), emptyMap(), emptyMap(), emptyList())

        clock.pin(Instant.parse("2026-01-01T00:00:00Z"))
        store.saveVersion(v1)
        clock.pin(Instant.parse("2026-01-02T00:00:00Z"))
        store.saveVersion(v2)
        assertEquals(v2, store.latestVersion(schemaName))
        assertEquals(1L, storedSequence(schemaName, v1))
        assertEquals(2L, storedSequence(schemaName, v2))

        // Re-stamp the old one much later. This is the idempotent path: it must refresh v1's
        // content and nothing else -- not its sequence, and not the counter, or the next genuinely
        // new version would skip a number.
        clock.pin(Instant.parse("2026-06-01T00:00:00Z"))
        store.saveVersion(v1)

        assertEquals(v2, store.latestVersion(schemaName), "re-saving v1 must not make it the latest")
        assertEquals(listOf(v2, v1), store.versionHistory(schemaName))
        assertEquals(1L, storedSequence(schemaName, v1), "v1 must keep the position it has always had")
        assertEquals(2L, counterValue(schemaName), "an idempotent re-save must not consume a sequence number")
    }

    @Test
    fun `many threads saving the identical version leave exactly one node`() {
        // The write is a MERGE, which is only race-free under a uniqueness constraint on the key it
        // merges on -- see TestApplication.metamodelSchema. Without one, concurrent MERGEs all miss,
        // all take the CREATE branch, and the "history" fills with duplicates of one version.
        val threads = 12
        val version = MetamodelVersion(
            schemaName = "concurrent-schema",
            entityTypeNames = listOf("Contended"),
            entityTypeLabels = mapOf("Contended" to setOf("LabelC")),
            entityTypeProperties = mapOf("Contended" to setOf(PropertySignature("prop", Kind.VALUE, "string", Cardinality.ONE))),
            relationshipNames = listOf("REL"),
        )

        val startTogether = CountDownLatch(1)
        val succeeded = AtomicInteger()
        val failures = mutableListOf<Throwable>()
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(threads) {
                pool.submit {
                    startTogether.await()
                    // A loser in a MERGE race can surface a constraint violation or a lock timeout.
                    // That's tolerable -- a caller retries. Two surviving nodes are not.
                    runCatching { store.saveVersion(version) }
                        .onSuccess { succeeded.incrementAndGet() }
                        .onFailure { t -> synchronized(failures) { failures += t } }
                }
            }
            startTogether.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "concurrent saves did not finish in time")
        } finally {
            pool.shutdownNow()
        }

        assertTrue(succeeded.get() > 0, "every concurrent save failed: ${failures.firstOrNull()}")
        val history = store.versionHistory("concurrent-schema")
        assertEquals(
            1,
            history.size,
            "$threads concurrent saves of one version must leave one node, not ${history.size} " +
                "(${succeeded.get()} succeeded, ${failures.size} failed)",
        )
        assertEquals(version, history.single())
        // The sequence is assigned in the same transaction as the MERGE that creates the node, and
        // only on create -- so the losing threads, which matched an existing node, took no number.
        assertEquals(1L, storedSequence("concurrent-schema", version), "the one node must hold the first sequence")
        assertEquals(1L, counterValue("concurrent-schema"), "only the creating save may consume a number")
    }

    @Test
    fun `concurrent saves of distinct versions each get their own place in the order`() {
        // The lost-update test. Every thread here creates a *different* version of one schema, so
        // all of them hit the counter at the same moment. If the increment lost an update, two
        // versions would claim one position and "newest first" would be arbitrary again for the
        // pair -- the very bug the sequence was introduced to fix.
        val schemaName = "concurrent-distinct-schema"
        val threads = 12
        val versions = (1..threads).map {
            MetamodelVersion(schemaName, listOf("Type$it"), emptyMap(), emptyMap(), emptyList())
        }

        val startTogether = CountDownLatch(1)
        val failures = mutableListOf<Throwable>()
        val pool = Executors.newFixedThreadPool(threads)
        try {
            versions.forEach { version ->
                pool.submit {
                    startTogether.await()
                    runCatching { store.saveVersion(version) }
                        .onFailure { t -> synchronized(failures) { failures += t } }
                }
            }
            startTogether.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "concurrent saves did not finish in time")
        } finally {
            pool.shutdownNow()
        }
        assertTrue(failures.isEmpty(), "distinct versions must not contend for the same node: ${failures.firstOrNull()}")

        val sequences = versions.map { storedSequence(schemaName, it) }
        assertEquals(
            (1L..threads.toLong()).toSet(),
            sequences.toSet(),
            "each version must hold its own sequence; got $sequences",
        )
        // And the order the store reports has to be a total order over all of them, not a heap with
        // ties in it.
        assertEquals(threads, store.versionHistory(schemaName).size)
        assertEquals(threads.toLong(), counterValue(schemaName))
    }

    @Test
    fun `two versions of one schema cannot be stored at the same position`() {
        // The safety net under the sequence. The counter increment appears to serialise correctly on
        // its own -- removing the lock from the save statement doesn't make the test above fail, even
        // at four times the contention -- so "the increment is atomic" is an observation, not a proof.
        // This constraint is the proof: whatever the counter does, the database will not hold two
        // versions of one schema claiming one place in the write order. A lost update becomes a
        // retryable failure rather than a silently scrambled history.
        val schemaName = "position-constraint-schema"
        val first = MetamodelVersion(schemaName, listOf("First"), emptyMap(), emptyMap(), emptyList())
        store.saveVersion(first)
        assertEquals(1L, storedSequence(schemaName, first))

        val collision = runCatching {
            persistenceManager.execute(
                QuerySpecification.withStatement(
                    """
                    CREATE (n:MetamodelVersion {
                        schemaName: ${'$'}schemaName, contentHash: 'a-different-hash', sequence: 1
                    })
                    """.trimIndent(),
                ).bind(mapOf("schemaName" to schemaName)),
            )
        }

        assertTrue(
            collision.isFailure,
            "the database must refuse a second version at position 1; a lost counter update has to be loud",
        )
        assertEquals(listOf(first), store.versionHistory(schemaName), "and the history is untouched")
    }

    // ---- Chronological ordering ----

    @Test
    fun `version history is newest-first`() {
        val schemaName = "ordering-schema"
        val v1 = MetamodelVersion(schemaName, listOf("Type1"), emptyMap(), emptyMap(), emptyList())
        val v2 = MetamodelVersion(schemaName, listOf("Type2"), emptyMap(), emptyMap(), emptyList())

        store.saveVersion(v1)
        store.saveVersion(v2)

        assertEquals(listOf(v2, v1), store.versionHistory(schemaName))
        assertEquals(v2, store.latestVersion(schemaName))
    }

    @Test
    fun `two versions saved in the very same millisecond still order by write order`() {
        // No sleep, and the clock is pinned to one instant for both saves, so every timestamp on
        // both nodes is byte-identical. A clock -- at any precision -- simply cannot separate these
        // two; only a counter can. This is the ordinary case, not an exotic one: back-to-back saves
        // land in the same millisecond routinely.
        val schemaName = "same-millisecond-schema"
        val first = MetamodelVersion(schemaName, listOf("First"), emptyMap(), emptyMap(), emptyList())
        val second = MetamodelVersion(schemaName, listOf("Second"), emptyMap(), emptyMap(), emptyList())

        clock.pin(Instant.parse("2026-01-01T00:00:00Z"))
        store.saveVersion(first)
        store.saveVersion(second)

        assertEquals(
            listOf(second, first),
            store.versionHistory(schemaName),
            "identical timestamps must not make the order arbitrary",
        )
        assertEquals(second, store.latestVersion(schemaName))
        assertEquals(listOf(1L, 2L), listOf(storedSequence(schemaName, first), storedSequence(schemaName, second)))
    }

    @Test
    fun `write order survives a clock that runs backwards`() {
        // An NTP correction, or a failover to a node with a different skew, can move the wall clock
        // *backwards* between two saves. Ordering on any timestamp then reports the older stamp as
        // the newest -- a silent wrong answer, which is worse than a slow one. The sequence is
        // monotonic regardless of what the clock is doing.
        val schemaName = "clock-skew-schema"
        val earlier = MetamodelVersion(schemaName, listOf("WrittenFirst"), emptyMap(), emptyMap(), emptyList())
        val later = MetamodelVersion(schemaName, listOf("WrittenSecond"), emptyMap(), emptyMap(), emptyList())

        clock.pin(Instant.parse("2026-01-01T12:00:00Z"))
        store.saveVersion(earlier)
        clock.pin(Instant.parse("2026-01-01T11:00:00Z")) // an hour backwards
        store.saveVersion(later)

        assertEquals(later, store.latestVersion(schemaName), "the last write must be the latest, whatever the clock says")
        assertEquals(listOf(later, earlier), store.versionHistory(schemaName))
    }

    // ---- Corrupt rows are skipped, not materialized ----

    @Test
    fun `a version node missing a required property is skipped and warned about, not read as a blank version`() {
        // Written straight through Cypher, so the node exists exactly as a partially-failed write or
        // a hand-edit would leave it: one required property simply absent. `entityTypeNames` rather
        // than `schemaName` because every read MATCHes on schemaName -- a node without one is
        // filtered out by the query and never reaches the mapper at all.
        val schemaName = "corrupt-row-schema"
        val good = MetamodelVersion(schemaName, listOf("Sound"), emptyMap(), emptyMap(), emptyList())
        store.saveVersion(good)
        // Spelled out property by property rather than copied from the good node, so what's wrong
        // with it is visible: every property the mapper writes except `entityTypeNames`.
        val brokenSavedAt = Instant.parse("2026-01-01T00:00:00Z")
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                CREATE (broken:MetamodelVersion {
                    schemaName:           ${'$'}schemaName,
                    contentHash:          ${'$'}contentHash,
                    entityTypeLabels:     '{}',
                    entityTypeProperties: '{}',
                    relationshipNames:    '[]',
                    savedAt:              ${'$'}savedAt,
                    savedAtEpochMillis:   ${'$'}savedAtEpochMillis
                })
                """.trimIndent(),
            ).bind(
                mapOf(
                    "schemaName" to schemaName,
                    // Distinct from the good node's, so the (schemaName, contentHash) uniqueness
                    // constraint lets both nodes exist.
                    "contentHash" to "a-different-hash-so-the-natural-key-does-not-collide",
                    "savedAt" to brokenSavedAt.toString(),
                    "savedAtEpochMillis" to brokenSavedAt.toEpochMilli(),
                ),
            ),
        )
        assertEquals(2, rawNodeCount(), "the corrupt node must really be in the graph")

        val (history, logged) = capturingStoreWarnings { store.versionHistory(schemaName) }

        assertEquals(listOf(good), history, "the readable version survives; the corrupt one is dropped")
        assertTrue(
            logged.any { it.contains("Skipping unreadable MetamodelVersion row") && it.contains("entityTypeNames") },
            "the skip must be warned about and name the missing property; warnings were: $logged",
        )
    }

    @Test
    fun `a stored property signature missing a field is skipped and warned about by name`() {
        // The signature encoding is a persisted format of its own, so a node can be structurally
        // fine and still hold a half-written signature -- an older writer, a hand-edit. Guessing a
        // default cardinality would change the content and surface later as a baffling hash
        // mismatch, so the mapper names the missing field instead.
        val schemaName = "corrupt-signature-schema"
        val version = MetamodelVersion(
            schemaName = schemaName,
            entityTypeNames = listOf("Person"),
            entityTypeLabels = emptyMap(),
            entityTypeProperties = mapOf("Person" to setOf(PropertySignature("age", Kind.VALUE, "integer", Cardinality.ONE))),
            relationshipNames = emptyList(),
        )
        store.saveVersion(version)
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                MATCH (n:MetamodelVersion {schemaName: ${'$'}schemaName})
                SET n.entityTypeProperties = ${'$'}halfWritten
                """.trimIndent(),
            ).bind(
                mapOf(
                    "schemaName" to schemaName,
                    "halfWritten" to """{"Person":[{"name":"age","kind":"VALUE","type":"integer"}]}""",
                ),
            ),
        )

        val (history, logged) = capturingStoreWarnings { store.versionHistory(schemaName) }

        assertEquals(emptyList<MetamodelVersion>(), history)
        assertTrue(
            logged.any { it.contains("missing its 'cardinality' field") },
            "the warning must name the missing signature field; warnings were: $logged",
        )
    }

    @Test
    fun `a version node whose stored hash disagrees with its stored fields is skipped and warned about`() {
        // The content hash is derived from the structural fields, so the copy on the node is a
        // checksum rather than data. Disagreement means the node was written by an older hash format
        // or tampered with -- either way it is not the version it claims to be.
        val schemaName = "tampered-hash-schema"
        val version = MetamodelVersion(schemaName, listOf("Original"), emptyMap(), emptyMap(), emptyList())
        store.saveVersion(version)
        tamperWithEntityTypeNames(schemaName, """["SwappedInBehindTheHash"]""")

        val (history, logged) = capturingStoreWarnings { store.versionHistory(schemaName) }

        assertEquals(emptyList<MetamodelVersion>(), history)
        assertTrue(logged.any { it.contains("fails its integrity check") }, "warnings were: $logged")
        assertNull(store.latestVersion(schemaName), "and it must not come back as the latest version either")
    }

    @Test
    fun `a corrupt newest node hides only itself, not the readable version behind it`() {
        // latestVersion deliberately sorts in Cypher but takes the first *readable* row rather than
        // pushing LIMIT 1 down. With the limit in the query, a corrupt newest node would make the
        // store answer "no versions at all" while versionHistory still returned the older one --
        // two reads disagreeing about the same graph.
        val schemaName = "corrupt-head-schema"
        val readable = MetamodelVersion(schemaName, listOf("Readable"), emptyMap(), emptyMap(), emptyList())
        val doomed = MetamodelVersion(schemaName, listOf("Doomed"), emptyMap(), emptyMap(), emptyList())

        clock.pin(Instant.parse("2026-01-01T00:00:00Z"))
        store.saveVersion(readable)
        clock.pin(Instant.parse("2026-01-02T00:00:00Z"))
        store.saveVersion(doomed)
        // Tamper with the newer node only, keyed on its own hash.
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                MATCH (n:MetamodelVersion {schemaName: ${'$'}schemaName, contentHash: ${'$'}contentHash})
                SET n.entityTypeNames = '["NotWhatTheHashSays"]'
                """.trimIndent(),
            ).bind(mapOf("schemaName" to schemaName, "contentHash" to doomed.contentHash)),
        )

        assertEquals(readable, store.latestVersion(schemaName))
        assertEquals(listOf(readable), store.versionHistory(schemaName))
    }

    // ---- Adversarial serialization: names carrying delimiter characters ----

    @Test
    fun `names containing delimiter characters survive the round-trip intact`() {
        // The old encoding joined on delimiters; this one is JSON, and these are the characters that
        // would break a joined one. Entity type names, labels, property names, property types and
        // relationship names all go through it, so all five carry a delimiter here.
        listOf("|" to "pipe", "\t" to "tab", "\n" to "newline", "\"" to "quote", "\\" to "backslash")
            .forEach { (delimiter, label) ->
                val schemaName = "delimiter-$label"
                val typeName = "Type${delimiter}WithIt"
                val version = MetamodelVersion(
                    schemaName = schemaName,
                    entityTypeNames = listOf(typeName, "Normal"),
                    entityTypeLabels = mapOf(typeName to setOf("Label${delimiter}1", "Label2")),
                    entityTypeProperties = mapOf(
                        typeName to setOf(
                            PropertySignature("prop${delimiter}1", Kind.VALUE, "string${delimiter}ish", Cardinality.ONE),
                        ),
                    ),
                    relationshipNames = listOf("REL${delimiter}WITH${delimiter}IT"),
                )

                store.saveVersion(version)

                assertEquals(version, store.latestVersion(schemaName), "'$label' did not survive the round-trip")
            }
    }

    // ---- helpers ----

    /** Rewrite a version node's serialized entity type names, leaving its stored hash untouched. */
    private fun tamperWithEntityTypeNames(schemaName: String, serializedNames: String) {
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                MATCH (n:MetamodelVersion {schemaName: ${'$'}schemaName})
                SET n.entityTypeNames = ${'$'}serializedNames
                """.trimIndent(),
            ).bind(mapOf("schemaName" to schemaName, "serializedNames" to serializedNames)),
        )
    }

    /**
     * The `sequence` a version node actually holds. Read straight out of the graph because the
     * sequence is storage bookkeeping — it is deliberately not on [MetamodelVersion], so the only
     * honest way to assert about it is to go and look.
     */
    private fun storedSequence(schemaName: String, version: MetamodelVersion): Long? =
        persistenceManager.maybeGetOne(
            QuerySpecification.withStatement(
                """
                MATCH (n:MetamodelVersion {schemaName: ${'$'}schemaName, contentHash: ${'$'}contentHash})
                RETURN n.sequence AS sequence
                """.trimIndent(),
            ).bind(mapOf("schemaName" to schemaName, "contentHash" to version.contentHash))
                .transform(Long::class.java),
        )

    /** How far the schema's counter has been advanced. */
    private fun counterValue(schemaName: String): Long? = persistenceManager.maybeGetOne(
        QuerySpecification.withStatement(
            "MATCH (c:MetamodelSchemaCounter {schemaName: ${'$'}schemaName}) RETURN c.sequence AS sequence",
        ).bind(mapOf("schemaName" to schemaName)).transform(Long::class.java),
    )

    /** Count version nodes without going through the store's mapper. */
    private fun rawNodeCount(): Int = persistenceManager.maybeGetOne(
        QuerySpecification.withStatement("MATCH (n:MetamodelVersion) RETURN count(n) AS c").transform(Long::class.java),
    )?.toInt() ?: 0

    /**
     * Run [block] with a listener attached to the store's logger, and hand back both its result and
     * every WARN message the store emitted. Needed because "skips the row" and "skips the row *and
     * says so*" are different behaviours, and only the second is any use to an operator.
     */
    private fun <T> capturingStoreWarnings(block: () -> T): Pair<T, List<String>> {
        val logger = LoggerFactory.getLogger(DrivineMetamodelVersionStore::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return try {
            block() to appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}

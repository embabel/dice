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
import com.embabel.agent.core.ContextId
import com.embabel.dice.metamodel.DriftReport
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant

/**
 * [DrivineDriftReportStore] against a Neo4j testcontainer. Each test starts from an empty drift log
 * via [cleanUp].
 *
 * These are the same questions `DriftReportStoreTest` asks of the in-memory reference, answered in
 * Cypher — plus the ones only a database can get wrong: the scope pushed into the query rather than
 * applied to a page that has already been cut, a `since` bound that stays exact below the
 * millisecond, and the sequence that keeps a limited page from moving between reads.
 */
@SpringBootTest(classes = [TestApplication::class])
class DrivineDriftReportStoreIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)
    }

    @Autowired
    private lateinit var store: DrivineDriftReportStore

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    private val schemaName = "drift-schema"
    private val contextA = ContextId("ctx-a")
    private val contextB = ContextId("ctx-b")
    private val epoch: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @AfterEach
    fun cleanUp() {
        listOf("MetamodelDriftReport", "MetamodelDriftReportCounter").forEach { label ->
            persistenceManager.execute(QuerySpecification.withStatement("MATCH (n:$label) DETACH DELETE n"))
        }
    }

    /** A report captured [minute] minutes after the epoch, optionally scoped to a context. */
    private fun save(
        minute: Long,
        contextId: ContextId? = null,
        schema: String = schemaName,
    ): DriftReport {
        val report = DriftReport(
            schemaName = schema,
            versionHash = "hash-$minute",
            driftedEntityTypes = setOf("Ghost$minute"),
            driftedRelationshipTypes = emptySet(),
            capturedAt = epoch.plusSeconds(minute * 60),
            contextId = contextId,
        )
        store.saveDriftReport(report)
        return report
    }

    // ---- Round-trip ----

    @Test
    fun `a global report persists and reads back every field`() {
        val report = DriftReport(
            schemaName = schemaName,
            versionHash = "abc123",
            driftedEntityTypes = setOf("Ghost", "Phantom"),
            driftedRelationshipTypes = setOf("HAUNTS"),
            capturedAt = epoch.plusNanos(123_456_789),
        )

        store.saveDriftReport(report)

        assertEquals(listOf(report), store.driftReports(schemaName, limit = 10))
        assertEquals(listOf(report), store.globalDriftReports(schemaName, limit = 10))
    }

    @Test
    fun `a context-scoped report keeps its context through the round-trip`() {
        val report = DriftReport(
            schemaName = schemaName,
            versionHash = "abc123",
            driftedEntityTypes = setOf("Ghost"),
            driftedRelationshipTypes = emptySet(),
            capturedAt = epoch,
            contextId = contextA,
        )

        store.saveDriftReport(report)

        assertEquals(listOf(report), store.driftReportsInContext(schemaName, contextA, limit = 10))
        assertEquals(contextA, store.driftReports(schemaName, limit = 10).single().contextId)
    }

    @Test
    fun `type names carrying delimiter characters survive the round-trip`() {
        // The sets are JSON, not a joined string, and these are the characters that would break a
        // joined one. Type names come out of LLM extraction and do contain them.
        val report = DriftReport(
            schemaName = schemaName,
            versionHash = "delimiters",
            driftedEntityTypes = setOf("Type|WithPipe", "Type\tWithTab", "Type\nWithNewline", """Type"WithQuote"""),
            driftedRelationshipTypes = setOf("""REL\WITH\BACKSLASH"""),
            capturedAt = epoch,
        )

        store.saveDriftReport(report)

        assertEquals(report, store.driftReports(schemaName, limit = 10).single())
    }

    // ---- Ordering and bounding ----

    @Test
    fun `reads come back newest first by capture instant, not by write order`() {
        // Written 1, 3, 2 -- so anything that ordered on the write sequence alone would hand back
        // 2, 3, 1. The contract orders on when the graph was looked at.
        save(1)
        save(3)
        save(2)

        assertEquals(
            listOf("hash-3", "hash-2", "hash-1"),
            store.driftReports(schemaName, limit = 10).map { it.versionHash },
        )
    }

    @Test
    fun `a limit returns the newest page, not an arbitrary one`() {
        (1L..5L).forEach { save(it) }

        val reports = store.driftReports(schemaName, limit = 2)

        assertEquals(listOf("hash-5", "hash-4"), reports.map { it.versionHash })
    }

    @Test
    fun `since bounds the window from below, inclusively`() {
        (1L..4L).forEach { save(it) }

        val reports = store.driftReports(schemaName, limit = 10, since = epoch.plusSeconds(2 * 60))

        assertEquals(listOf("hash-4", "hash-3", "hash-2"), reports.map { it.versionHash })
    }

    @Test
    fun `since stays exact below the millisecond`() {
        // The reason the sort and the bound are stored as (epochSecond, nano) rather than as epoch
        // milliseconds. These two are 500 microseconds apart, which truncates to the same
        // millisecond: a millis-based bound would sweep the earlier one in and quietly widen the
        // window the caller asked for.
        val earlier = epoch.plusNanos(200_000)
        val later = epoch.plusNanos(700_000)
        listOf(earlier to "early", later to "late").forEach { (instant, hash) ->
            store.saveDriftReport(
                DriftReport(schemaName, hash, setOf("Ghost"), emptySet(), instant),
            )
        }

        val fromLater = store.driftReports(schemaName, limit = 10, since = later)

        assertEquals(listOf("late"), fromLater.map { it.versionHash })
        assertEquals(2, store.driftReports(schemaName, limit = 10).size, "both were really stored")
        assertEquals(
            listOf("late", "early"),
            store.driftReports(schemaName, limit = 10).map { it.versionHash },
            "and sub-millisecond ordering is right too",
        )
    }

    @Test
    fun `a non-positive limit is rejected by all three reads rather than quietly meaning everything`() {
        save(1)

        listOf(0, -1).forEach { limit ->
            assertThrows(IllegalArgumentException::class.java) { store.driftReports(schemaName, limit) }
            assertThrows(IllegalArgumentException::class.java) { store.globalDriftReports(schemaName, limit) }
            assertThrows(IllegalArgumentException::class.java) {
                store.driftReportsInContext(schemaName, contextA, limit)
            }
        }
    }

    @Test
    fun `reports captured at the same instant hold a stable order across reads`() {
        // A global sweep and a context sweep can share a capture instant. The instant alone then
        // leaves their order to the database, and with a LIMIT on top the page boundary lands
        // somewhere arbitrary -- the same read can return different rows each time. The per-schema
        // sequence is what makes the order total, so a page is repeatable.
        val instant = epoch.plusSeconds(600)
        val first = DriftReport(schemaName, "tie-1", setOf("A"), emptySet(), instant, contextA)
        val second = DriftReport(schemaName, "tie-2", setOf("B"), emptySet(), instant, contextB)
        store.saveDriftReport(first)
        store.saveDriftReport(second)

        val pages = (1..5).map { store.driftReports(schemaName, limit = 1).map { r -> r.versionHash } }

        assertEquals(List(5) { listOf("tie-2") }, pages, "a tie must not make the page wobble")
        assertEquals(listOf("tie-2", "tie-1"), store.driftReports(schemaName, limit = 10).map { it.versionHash })
    }

    // ---- Scope ----

    @Test
    fun `each read sees only its own scope`() {
        val global = save(1)
        val inA = save(2, contextA)
        val inB = save(3, contextB)

        assertEquals(listOf(inB, inA, global), store.driftReports(schemaName, limit = 10))
        assertEquals(listOf(global), store.globalDriftReports(schemaName, limit = 10))
        assertEquals(listOf(inA), store.driftReportsInContext(schemaName, contextA, limit = 10))
        assertEquals(listOf(inB), store.driftReportsInContext(schemaName, contextB, limit = 10))
    }

    @Test
    fun `scoping happens before limiting, not after`() {
        // The rule the contract exists to protect. A store that read a limited page and then
        // filtered it would answer "no global drift" here: the newest three reports are all
        // context-scoped, so the one global report never survives to the filter.
        val global = save(1)
        save(2, contextA)
        save(3, contextA)
        save(4, contextA)

        assertEquals(listOf(global), store.globalDriftReports(schemaName, limit = 3))
    }

    @Test
    fun `a context read pushes its scope down too`() {
        // The mirror image: the newest reports are global and another context's, so an in-memory
        // filter over a page of 2 would report no drift in context A at all.
        val inA = save(1, contextA)
        save(2)
        save(3, contextB)

        assertEquals(listOf(inA), store.driftReportsInContext(schemaName, contextA, limit = 2))
    }

    @Test
    fun `reports for another schema are never returned`() {
        save(1, schema = "other-schema")

        assertTrue(store.driftReports(schemaName, limit = 10).isEmpty())
    }

    // ---- Natural-key identity ----

    @Test
    fun `re-saving the same observation updates it in place and keeps its position`() {
        val first = save(1)
        save(2)
        val corrected = DriftReport(
            schemaName = first.schemaName,
            versionHash = first.versionHash,
            driftedEntityTypes = setOf("GhostA", "GhostB"),
            driftedRelationshipTypes = setOf("HAUNTS"),
            capturedAt = first.capturedAt,
            contextId = first.contextId,
        )

        store.saveDriftReport(corrected)

        val reports = store.driftReports(schemaName, limit = 10)
        assertEquals(2, reports.size, "same natural key means the same record, not a third one")
        assertEquals(setOf("GhostA", "GhostB"), reports.last().driftedEntityTypes)
        assertEquals(1L, storedSequence(first), "an idempotent re-save must not move it in the order")
        assertEquals(2L, counterValue(schemaName), "nor consume a sequence number")
    }

    @Test
    fun `checks at different instants are separate records`() {
        save(1)
        save(2)

        assertEquals(2, store.driftReports(schemaName, limit = 10).size)
    }

    @Test
    fun `a global and a scoped check at the same instant are two records, not an overwrite`() {
        // The reason the natural key carries a never-null contextKey. Keying on contextId itself
        // would make the global report's key contain a null, which a Cypher MERGE can never match:
        // it would take the CREATE branch every time and duplicate on retry.
        val instant = epoch.plusSeconds(60)
        val global = DriftReport(schemaName, "same-hash", setOf("Ghost"), emptySet(), instant)
        val scoped = DriftReport(schemaName, "same-hash", setOf("Ghost"), emptySet(), instant, contextA)

        store.saveDriftReport(global)
        store.saveDriftReport(scoped)

        assertEquals(2, store.driftReports(schemaName, limit = 10).size)
        assertEquals(listOf(global), store.globalDriftReports(schemaName, limit = 10))
        assertEquals(listOf(scoped), store.driftReportsInContext(schemaName, contextA, limit = 10))
    }

    @Test
    fun `a context named after the global marker cannot collide with a global report`() {
        // ContextId accepts any non-blank string, so any encoding that stores a bare context id
        // alongside a sentinel is one `ContextId(sentinel)` away from a collision: both reports
        // would MERGE onto one node and each save would rewrite the other's scope, so a
        // context-scoped finding would surface as whole-graph drift. Prefixing every real context
        // makes that unrepresentable rather than unlikely -- including for the previous sentinel,
        // which a caller may well still be using as a context id.
        val instant = epoch.plusSeconds(60)
        listOf(
            ContextId(DriftReportRowMapper.GLOBAL_CONTEXT_KEY),
            ContextId("\u0000__global-context__\u0000"),
            ContextId("ctx:global"),
        ).forEachIndexed { index, awkward ->
            val capturedAt = instant.plusSeconds(index.toLong())
            val global = DriftReport(schemaName, "collide", setOf("G"), emptySet(), capturedAt)
            val scoped = DriftReport(schemaName, "collide", setOf("S"), emptySet(), capturedAt, awkward)

            store.saveDriftReport(global)
            store.saveDriftReport(scoped)

            assertEquals(
                listOf(global),
                store.globalDriftReports(schemaName, limit = 10, since = capturedAt),
                "the global report kept its scope against context '${awkward.value}'",
            )
            assertEquals(
                listOf(scoped),
                store.driftReportsInContext(schemaName, awkward, limit = 10, since = capturedAt),
                "and the scoped report kept its own",
            )
        }
    }

    @Test
    fun `saving one global report twice leaves one node, not two`() {
        val report = DriftReport(schemaName, "idempotent", setOf("Ghost"), emptySet(), epoch)

        store.saveDriftReport(report)
        store.saveDriftReport(report)

        assertEquals(listOf(report), store.globalDriftReports(schemaName, limit = 10))
        assertEquals(1L, counterValue(schemaName))
    }

    @Test
    fun `two reports of one schema cannot be stored at the same position`() {
        // The safety net under the sequence: whatever the counter does, the database will not hold
        // two reports of one schema claiming one place in the tie-break order, so a lost counter
        // update is a retryable failure rather than a silently wobbling page.
        val first = save(1)
        assertEquals(1L, storedSequence(first))

        val collision = runCatching {
            persistenceManager.execute(
                QuerySpecification.withStatement(
                    """
                    CREATE (n:MetamodelDriftReport {
                        schemaName: ${'$'}schemaName, versionHash: 'other', capturedAt: 'other',
                        contextKey: 'other', sequence: 1
                    })
                    """.trimIndent(),
                ).bind(mapOf("schemaName" to schemaName)),
            )
        }

        assertTrue(collision.isFailure, "the database must refuse a second report at position 1")
        assertEquals(listOf(first), store.driftReports(schemaName, limit = 10), "and the log is untouched")
    }

    // ---- Corrupt rows are skipped, not materialized ----

    @Test
    fun `a report node missing a required property is skipped and warned about by name`() {
        val readable = save(1)
        save(2)
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                MATCH (n:MetamodelDriftReport {schemaName: ${'$'}schemaName, versionHash: 'hash-2'})
                REMOVE n.driftedEntityTypes
                """.trimIndent(),
            ).bind(mapOf("schemaName" to schemaName)),
        )

        val (reports, logged) = capturingStoreWarnings { store.driftReports(schemaName, limit = 10) }

        assertEquals(listOf(readable), reports, "the readable report survives; the corrupt one is dropped")
        assertTrue(
            logged.any {
                it.contains("Skipping unreadable MetamodelDriftReport row") && it.contains("driftedEntityTypes")
            },
            "the skip must be warned about and name the missing property; warnings were: $logged",
        )
    }

    @Test
    fun `a report whose two halves of scope disagree is refused`() {
        // contextKey is what a save MERGEs on; contextId is what a scoped read matches. A node
        // where they disagree answers to one scope when read and a different one when re-saved.
        save(1)
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                MATCH (n:MetamodelDriftReport {schemaName: ${'$'}schemaName})
                SET n.contextKey = 'not-the-global-sentinel'
                """.trimIndent(),
            ).bind(mapOf("schemaName" to schemaName)),
        )

        val (reports, logged) = capturingStoreWarnings { store.driftReports(schemaName, limit = 10) }

        assertTrue(reports.isEmpty())
        assertTrue(logged.any { it.contains("fails its scope check") }, "warnings were: $logged")
    }

    @Test
    fun `a node with no sort key is excluded in the database, not after the page is cut`() {
        // Neo4j sorts null largest, so a node missing the sort key would sort to the front of a DESC
        // order, spend one of the caller's limit slots, and then be dropped by the mapper -- hiding
        // a perfectly good report behind a broken one.
        val readable = save(1)
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                CREATE (n:MetamodelDriftReport {
                    schemaName: ${'$'}schemaName, versionHash: 'no-sort-key', capturedAt: 'never',
                    contextKey: ${'$'}globalKey, driftedEntityTypes: '[]', driftedRelationshipTypes: '[]'
                })
                """.trimIndent(),
            ).bind(
                mapOf(
                    "schemaName" to schemaName,
                    "globalKey" to DriftReportRowMapper.GLOBAL_CONTEXT_KEY,
                ),
            ),
        )

        assertEquals(listOf(readable), store.driftReports(schemaName, limit = 1))
    }

    // ---- helpers ----

    /** The `sequence` a report node actually holds — storage bookkeeping, so read straight out. */
    private fun storedSequence(report: DriftReport): Long? = persistenceManager.maybeGetOne(
        QuerySpecification.withStatement(
            """
            MATCH (n:MetamodelDriftReport {schemaName: ${'$'}schemaName, versionHash: ${'$'}versionHash})
            RETURN n.sequence AS sequence
            """.trimIndent(),
        ).bind(mapOf("schemaName" to report.schemaName, "versionHash" to report.versionHash))
            .transform(Long::class.java),
    )

    /** How far the schema's report counter has been advanced. */
    private fun counterValue(schemaName: String): Long? = persistenceManager.maybeGetOne(
        QuerySpecification.withStatement(
            "MATCH (c:MetamodelDriftReportCounter {schemaName: ${'$'}schemaName}) RETURN c.sequence AS sequence",
        ).bind(mapOf("schemaName" to schemaName)).transform(Long::class.java),
    )

    /**
     * Run [block] with a listener on the store's logger, and hand back both its result and every
     * WARN it emitted. "Skips the row" and "skips the row *and says so*" are different behaviours,
     * and only the second is any use to an operator.
     */
    private fun <T> capturingStoreWarnings(block: () -> T): Pair<T, List<String>> {
        val logger = LoggerFactory.getLogger(DrivineDriftReportStore::class.java) as Logger
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

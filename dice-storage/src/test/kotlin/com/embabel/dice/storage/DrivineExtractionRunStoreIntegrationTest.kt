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

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.extraction.ExtractionActorRef
import com.embabel.dice.proposition.extraction.ExtractionCohortRef
import com.embabel.dice.proposition.extraction.ExtractionContentProfileRef
import com.embabel.dice.proposition.extraction.ExtractionDeploymentRef
import com.embabel.dice.proposition.extraction.ExtractionExperimentRef
import com.embabel.dice.proposition.extraction.ExtractionFailure
import com.embabel.dice.proposition.extraction.ExtractionFailureCode
import com.embabel.dice.proposition.extraction.ExtractionInvocationId
import com.embabel.dice.proposition.extraction.ExtractionInvocationOutcome
import com.embabel.dice.proposition.extraction.ExtractionInvocationRecord
import com.embabel.dice.proposition.extraction.ExtractionModelUsage
import com.embabel.dice.proposition.extraction.ExtractionPersonalizationRef
import com.embabel.dice.proposition.extraction.ExtractionProviderResponseFacts
import com.embabel.dice.proposition.extraction.ExtractionReplayFidelity
import com.embabel.dice.proposition.extraction.ExtractionRequestRef
import com.embabel.dice.proposition.extraction.ExtractionRequestedModelConfig
import com.embabel.dice.proposition.extraction.ExtractionRun
import com.embabel.dice.proposition.extraction.ExtractionRunConflictException
import com.embabel.dice.proposition.extraction.ExtractionRunCounts
import com.embabel.dice.proposition.extraction.ExtractionRunFingerprints
import com.embabel.dice.proposition.extraction.ExtractionRunKey
import com.embabel.dice.proposition.extraction.ExtractionRunLineage
import com.embabel.dice.proposition.extraction.ExtractionRunRef
import com.embabel.dice.proposition.extraction.ExtractionRunStatus
import com.embabel.dice.proposition.extraction.ExtractionRunSubjectRefs
import com.embabel.dice.proposition.extraction.ExtractionRunTransition
import com.embabel.dice.proposition.extraction.ExtractionRunTransitionOutcome
import com.embabel.dice.proposition.extraction.ExtractionRuntimeIdentity
import com.embabel.dice.proposition.extraction.ExtractionSessionRef
import com.embabel.dice.provenance.SourceRevisionRef
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * What the cross-backend contract suite cannot ask, because it holds every backend to the same
 * calls: whether the graph this store writes is the graph it says it writes, and whether the
 * compare-and-set survives writers that share nothing but the database.
 *
 * Four groups:
 * - **Compare-and-set across independent writers.** The contract's race case runs two threads
 *   through one store object; a durable backend has to hold when the writers are not one object,
 *   and its guarantee has to be visible in the graph rather than inferred from a return value.
 * - **Cross-tenant fail-closed against a real index.** The contract asserts this over a store with
 *   one tenant's runs; here two tenants hold the *same run ids*, which is the arrangement that
 *   catches a query that scopes on `runId` and forgets `contextId`.
 * - **Row fidelity.** The contract's runs are nearly empty, so every optional field, every JSON
 *   encoding, and every "absent means none" rule is untested by it. These runs carry everything.
 * - **What a corrupt or oversized node does.** A skipped row, and a tenant the store refuses to key
 *   on.
 */
@SpringBootTest(classes = [TestApplication::class])
class DrivineExtractionRunStoreIntegrationTest {

    @Autowired
    private lateinit var store: DrivineExtractionRunStore

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private val tenant = ContextId("graph-tenant")
    private val neighbour = ContextId("graph-neighbour")
    private val startedAt: Instant = Instant.parse("2026-08-31T10:15:30.123456789Z")
    private val finishedAt: Instant = Instant.parse("2026-08-31T10:16:00Z")

    @AfterEach
    fun cleanUp() {
        ExtractionRunSchema.LABELS.forEach { label ->
            persistenceManager.execute(QuerySpecification.withStatement("MATCH (n:$label) DETACH DELETE n"))
        }
    }

    // ---- compare-and-set across independent writers ----

    @Test
    fun `writers sharing only the database produce exactly one applied transition`() {
        // The multi-process shape, as close as one JVM gets to it. Each racing store is constructed
        // directly rather than autowired, so there is no Spring proxy and no shared object between
        // them; this class holds no mutable state at all, so nothing in the JVM can be serialising
        // these writers. Whatever decides the race is the database.
        repeat(8) { round ->
            val run = running("graph-race-$round")
            store.save(run)
            val transition = ExtractionRunTransition.completed(finishedAt)
            val writers = (0 until 6).map {
                DrivineExtractionRunStore(persistenceManager, transactionManager)
            }

            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(writers.size)
            val results = try {
                val futures = writers.map { writer ->
                    pool.submit<Any> {
                        start.await()
                        runCatching { writer.transition(run.key(), transition).outcome }.getOrElse { it }
                    }
                }
                start.countDown()
                futures.map { it.get(60, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

            assertEquals(
                1,
                results.count { it == ExtractionRunTransitionOutcome.APPLIED },
                "exactly one writer ends the run: $results",
            )
            assertEquals(
                writers.size - 1,
                results.count { it == ExtractionRunTransitionOutcome.REPLAYED },
                "every loser replays rather than conflicting: $results",
            )
            // The invariant in the graph rather than in the return values. A backend that answered
            // correctly while recording two terminal writes would still have lost the audit.
            assertEquals(1, terminalWriteCount(run.key()))
            assertEquals(ExtractionRunStatus.COMPLETED, store.findRun(run.key())?.status)
        }
    }

    @Test
    fun `racing writers disagreeing about how the run ended leave one winner and one recorded write`() {
        val run = running("graph-race-disagree")
        store.save(run)
        val transitions = listOf(
            ExtractionRunTransition.completed(finishedAt),
            ExtractionRunTransition.failed(finishedAt),
            ExtractionRunTransition.cancelled(finishedAt),
        )

        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(transitions.size)
        val results = try {
            val futures = transitions.map { transition ->
                val writer = DrivineExtractionRunStore(persistenceManager, transactionManager)
                pool.submit<Any> {
                    start.await()
                    runCatching { writer.transition(run.key(), transition).outcome }.getOrElse { it }
                }
            }
            start.countDown()
            futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(
            1,
            results.count { it == ExtractionRunTransitionOutcome.APPLIED },
            "exactly one writer ends the run: $results",
        )
        assertEquals(1, terminalWriteCount(run.key()))
        assertTrue(store.findRun(run.key())!!.status.isTerminal)
    }

    @Test
    fun `a second terminal write for one run cannot be stored at all`() {
        // The compare-and-set rests on this constraint, so the constraint gets its own test. Without
        // it the store's locking would be the only thing standing between two writers and two
        // recorded endings, and a lock is a claim about behaviour rather than a schema fact.
        val run = running("graph-constraint")
        store.save(run)
        store.transition(run.key(), ExtractionRunTransition.completed(finishedAt))

        val second = assertThrows(RuntimeException::class.java) {
            persistenceManager.execute(
                QuerySpecification.withStatement(
                    """
                    CREATE (:ExtractionRunTerminalWrite {
                        contextId: ${'$'}contextId, runId: ${'$'}runId, fingerprint: 'forged', status: 'FAILED'
                    })
                    """.trimIndent(),
                ).bind(mapOf("contextId" to tenant.value, "runId" to "graph-constraint")),
            )
        }

        assertTrue(
            generateSequence(second as Throwable) { it.cause }.any {
                (it.message ?: "").contains("already exists", ignoreCase = true) ||
                    (it.message ?: "").contains("ConstraintValidationFailed", ignoreCase = true)
            },
            "the second terminal write is refused by the uniqueness constraint, not by chance: $second",
        )
        assertEquals(1, terminalWriteCount(run.key()))
    }

    @Test
    fun `the stored fingerprint is the string the transition computed`() {
        // Gap 8, asserted against the graph. A backend that re-derived the digest would still pass
        // the contract's replay cases on a run nothing else touched.
        val run = running("graph-fingerprint")
        store.save(run)
        val transition = ExtractionRunTransition.completed(
            finishedAt = finishedAt,
            counts = ExtractionRunCounts(propositionsPersisted = 3),
        )
        store.transition(run.key(), transition)

        assertEquals(transition.fingerprint, storedFingerprint(run.key()))

        // And it stays that string while the run around it changes underneath it.
        store.transition(run.key(), transition)
        assertEquals(transition.fingerprint, storedFingerprint(run.key()))
    }

    // ---- ambient transactions: validation precedes mutation, retries own a clean boundary ----

    @Test
    fun `a rejected recordInvocation leaves no orphan node even when the ambient transaction that catches it commits`() {
        // The write-then-throw shape the reviewer named: RECORD_INVOCATION used to MERGE-create the
        // child node before Kotlin found out the run had already ended, so a caller that catches the
        // resulting exception without rolling back its own transaction would commit an empty stub
        // nothing ever populated. Validation now happens before the MERGE runs at all.
        val run = running("graph-orphan-on-terminal")
        store.save(run)
        store.transition(run.key(), ExtractionRunTransition.completed(finishedAt))
        val neverRecorded = ExtractionInvocationRecord.planned(0)

        val ambient = TransactionTemplate(transactionManager)
        ambient.execute {
            runCatching { store.recordInvocation(run.key(), neverRecorded) }
                .onFailure { assertTrue(it is ExtractionRunConflictException) }
        }

        assertEquals(0, childNodeCount(run.key()))
        assertTrue(store.invocationsOf(run.key()).isEmpty())
    }

    @Test
    fun `a mixed batch of recordInvocation calls in one ambient transaction commits the accepted writes and leaves the conflicting attempt untouched`() {
        // Not a single Cypher statement's batch — the store never had one for recordInvocation, and
        // save no longer writes invocations at all — but the same shape one call sequence up: several
        // recordInvocation calls sharing one caller-owned transaction, one of which conflicts. The
        // accepted writes are genuine and correctly committed; the conflicting write must still leave
        // the row it targeted exactly where it was: untouched, whole, and unwritten.
        val run = running("graph-mixed-batch")
        store.save(run)
        val lockedId = ExtractionInvocationId.planned(0)
        val locked = ExtractionInvocationRecord(
            id = lockedId,
            outcome = ExtractionInvocationOutcome.SUCCEEDED,
            configuredService = "service-locked",
        )
        store.recordInvocation(run.key(), locked)
        val acceptedA = ExtractionInvocationId(1, 1)
        val acceptedB = ExtractionInvocationId(2, 1)

        val ambient = TransactionTemplate(transactionManager)
        ambient.execute {
            store.recordInvocation(run.key(), ExtractionInvocationRecord(id = acceptedA))
            store.recordInvocation(run.key(), ExtractionInvocationRecord(id = acceptedB))
            runCatching {
                store.recordInvocation(
                    run.key(),
                    locked.copy(configuredService = "service-conflicting"),
                )
            }.onFailure { assertTrue(it is ExtractionRunConflictException) }
        }

        val stored = store.invocationsOf(run.key()).associateBy { it.id }
        assertEquals(3, stored.size)
        assertTrue(acceptedA in stored)
        assertTrue(acceptedB in stored)
        assertEquals(locked, stored.getValue(lockedId))
    }

    @Test
    fun `two writers racing to record the same brand-new attempt both land, and neither retries inside the other's failed transaction`() {
        // Two concurrent MERGE-creates of the same not-yet-existing invocation node can trip Neo4j's
        // own deadlock detector, which aborts one transaction outright. Retrying that statement inside
        // the transaction the database already discarded is not a retry against anything real; a
        // recovered writer has to be reading and writing in a transaction the failure never touched.
        // If it were retrying inside the dead one, this would hang, throw, or leave two disagreeing
        // writes behind; a genuine retry converges on one.
        repeat(5) { round ->
            val run = running("graph-invocation-race-$round")
            store.save(run)
            val id = ExtractionInvocationId.planned(0)
            val writers = (0 until 4).map { DrivineExtractionRunStore(persistenceManager, transactionManager) }

            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(writers.size)
            val results = try {
                val futures = writers.mapIndexed { index, writer ->
                    pool.submit<Any> {
                        start.await()
                        runCatching {
                            writer.recordInvocation(
                                run.key(),
                                ExtractionInvocationRecord(id = id, configuredService = "writer-$index"),
                            )
                        }.getOrElse { it }
                    }
                }
                start.countDown()
                futures.map { it.get(60, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

            assertTrue(
                results.all { it is ExtractionRun },
                "every writer recovers; a transient conflict never reaches the caller: $results",
            )
            assertEquals(1, childNodeCount(run.key()))
        }
    }

    // ---- cross-tenant fail-closed, with identical run ids on both sides ----

    @Test
    fun `identical run ids in two tenants never collide on any read`() {
        val ids = listOf("graph-root", "graph-child", "graph-grandchild")
        listOf(tenant, neighbour).forEach { context ->
            val root = ExtractionRunLineage.root(ExtractionRunRef(ids[0]))
            val child = ExtractionRunLineage.childOf(ExtractionRunRef(ids[1]), root)
            val grandchild = ExtractionRunLineage.childOf(ExtractionRunRef(ids[2]), child)
            listOf(root, child, grandchild).forEachIndexed { index, lineage ->
                store.save(
                    running(
                        lineage.runRef.runId,
                        contextId = context,
                        // The neighbour's runs are newer, so a page that limited before it scoped
                        // would return the neighbour's rows or none.
                        startedAt = startedAt.plusSeconds(if (context == neighbour) 1_000L + index else index.toLong()),
                        lineage = lineage,
                    ),
                )
            }
        }
        store.recordInvocation(key(ids[1], neighbour), ExtractionInvocationRecord.planned(0))

        // Keyed lookups.
        assertEquals(
            startedAt.plusSeconds(1),
            store.findRun(key(ids[1], tenant))?.startedAt,
        )
        assertEquals(
            startedAt.plusSeconds(1_001),
            store.findRun(key(ids[1], neighbour))?.startedAt,
        )
        // The attempt belongs to the neighbour's run and is invisible from this tenant's.
        assertTrue(store.invocationsOf(key(ids[1], tenant)).isEmpty())
        assertEquals(1, store.invocationsOf(key(ids[1], neighbour)).size)

        // Pages.
        assertEquals(
            listOf("graph-grandchild", "graph-child", "graph-root"),
            store.runsInContext(tenant, 10, null).map { it.ref.runId },
        )
        assertEquals(3, store.runsInContext(neighbour, 10, null).size)

        // The inverse read down the parent axis, and the whole-lineage read.
        assertEquals(
            listOf(startedAt.plusSeconds(1)),
            store.childrenOf(tenant, ExtractionRunRef(ids[0]), 10).map { it.startedAt },
        )
        assertEquals(
            listOf(startedAt.plusSeconds(1_001)),
            store.childrenOf(neighbour, ExtractionRunRef(ids[0]), 10).map { it.startedAt },
        )
        assertEquals(3, store.runsOfRoot(tenant, ExtractionRunRef(ids[0]), 10, null).size)
        assertTrue(
            store.runsOfRoot(tenant, ExtractionRunRef(ids[0]), 10, null)
                .all { it.contextId == tenant },
        )

        // The chain walk.
        assertEquals(
            listOf(startedAt.plusSeconds(1), startedAt),
            store.ancestorsOf(key(ids[2], tenant), 10).map { it.startedAt },
        )
    }

    @Test
    fun `ending one tenant's run leaves the other tenant's run of the same id running`() {
        val mine = running("graph-shared")
        val theirs = running("graph-shared", contextId = neighbour)
        store.save(mine)
        store.save(theirs)

        store.transition(mine.key(), ExtractionRunTransition.completed(finishedAt))

        assertEquals(ExtractionRunStatus.COMPLETED, store.findRun(mine.key())?.status)
        assertEquals(ExtractionRunStatus.RUNNING, store.findRun(theirs.key())?.status)
        assertEquals(1, terminalWriteCount(mine.key()))
        assertEquals(0, terminalWriteCount(theirs.key()))
    }

    // ---- row fidelity ----

    @Test
    fun `a run carrying every field round-trips through the graph`() {
        // save writes header fields only, so the invocations fullyPopulated built into the run
        // object land through recordInvocation instead — the door the contract gives them. The
        // failure that names one of them cannot ride the same save that inserts the header, because
        // nothing has recorded that attempt yet at that point; it lands on a second save once
        // recordInvocation has, which is also why the object this test compares against holds the
        // version that second save produces, distinct from the version the first one left behind.
        val bootstrap = fullyPopulated("graph-fidelity", failures = emptyList())
        store.save(bootstrap)
        bootstrap.invocations.forEach { store.recordInvocation(bootstrap.key(), it) }
        val run = fullyPopulated("graph-fidelity")
        store.save(run)
        val expected = fullyPopulated("graph-fidelity", version = 1)

        assertEquals(expected, store.findRun(run.key()))
        assertEquals(expected, store.runsInContext(tenant, 10, null).single())
    }

    @Test
    fun `a terminal run carrying every field round-trips through the graph`() {
        val bootstrap = fullyPopulated("graph-fidelity-terminal", failures = emptyList())
        store.save(bootstrap)
        bootstrap.invocations.forEach { store.recordInvocation(bootstrap.key(), it) }
        val run = fullyPopulated("graph-fidelity-terminal")
        store.save(run)
        val expected = fullyPopulated("graph-fidelity-terminal", version = 1)
        val transition = ExtractionRunTransition.completed(
            finishedAt = finishedAt,
            counts = ExtractionRunCounts(propositionsPersisted = 99),
            failures = listOf(ExtractionFailure(ExtractionFailureCode.RATE_LIMITED, "slow down", finishedAt)),
        )

        val ended = store.transition(run.key(), transition)

        assertEquals(transition.applyTo(expected), ended.run)
        assertEquals(transition.applyTo(expected), store.findRun(run.key()))
    }

    @Test
    fun `an absent optional field leaves no property on the node`() {
        // "Absent means none" is an encoding, not a convention: a run with no profile has to be
        // stored the way a run written before profiles existed was stored, or the two are different
        // nodes on the same key.
        store.save(running("graph-sparse"))

        val properties = nodeProperties("ExtractionRun", "graph-sparse")

        listOf(
            "parentRunId", "supersedesRunId", "finishedAt", "finishedAtEpochSecond", "profileName",
            "profileVersion", "promptTemplateFingerprint", "requestedModel", "actorRef", "cohortRef",
        ).forEach { absent ->
            assertTrue(absent !in properties, "expected no '$absent' property, found ${properties[absent]}")
        }
        // And the required ones are there, so the assertion above is not passing vacuously.
        listOf("contextId", "runId", "rootRunId", "status", "startedAt", "lineageKey", "failures")
            .forEach { present -> assertTrue(present in properties, "expected a '$present' property") }
    }

    @Test
    fun `invocation records live on their own nodes and a header save leaves them alone`() {
        val run = running("graph-children")
        store.save(run)
        listOf(
            ExtractionInvocationRecord.planned(0),
            ExtractionInvocationRecord.planned(1),
            ExtractionInvocationRecord(id = ExtractionInvocationId(1, 2)),
        ).forEach { store.recordInvocation(run.key(), it) }

        // A caller that loaded the run before any attempt was recorded, then saved its own counts.
        store.save(
            ExtractionRun(
                contextId = tenant,
                lineage = run.lineage,
                status = ExtractionRunStatus.RUNNING,
                startedAt = startedAt,
                counts = ExtractionRunCounts(chunksProcessed = 12),
            ),
        )

        assertEquals(3, childNodeCount(run.key()))
        assertEquals(
            listOf(ExtractionInvocationId(0, 1), ExtractionInvocationId(1, 1), ExtractionInvocationId(1, 2)),
            store.invocationsOf(run.key()).map { it.id },
        )
        assertEquals(12, store.findRun(run.key())?.counts?.chunksProcessed)
        // The header holds no attempt list of its own, which is why it cannot delete one.
        assertTrue("invocations" !in nodeProperties("ExtractionRun", "graph-children"))
    }

    @Test
    fun `a run reads back with its attempts in plan order, whatever order they were recorded in`() {
        // A durable store keeps identified rows, not the order a caller happened to list them in, so
        // plan order is the only order it can offer — and it is the order the run model defines.
        // `ExtractionRun.equals` compares the list element by element, so an unordered collect would
        // make a stored run unequal to itself at random.
        val run = running("graph-plan-order")
        store.save(run)
        listOf(
            ExtractionInvocationId(2, 1),
            ExtractionInvocationId(0, 2),
            ExtractionInvocationId(1, 1),
            ExtractionInvocationId(0, 1),
        ).forEach { store.recordInvocation(run.key(), ExtractionInvocationRecord(id = it)) }

        val planOrder = listOf(
            ExtractionInvocationId(0, 1),
            ExtractionInvocationId(0, 2),
            ExtractionInvocationId(1, 1),
            ExtractionInvocationId(2, 1),
        )
        assertEquals(planOrder, store.findRun(run.key())!!.invocations.map { it.id })
        assertEquals(planOrder, store.invocationsOf(run.key()).map { it.id })
        assertEquals(
            planOrder,
            store.runsInContext(tenant, 10, null).single().invocations.map { it.id },
        )
    }

    @Test
    fun `nothing a run was never given reaches the stored bytes`() {
        // The privacy contract, asserted over the properties actually written rather than over a
        // test-local dumper. Everything DICE holds about a run is a token, a digest, or a
        // classified code, so a secret the host never handed over cannot be in the row — and the
        // one place it could leak in is a failure built from an exception whose message quotes it.
        val secret = "patient-9f2a-had-a-consultation-on-tuesday"
        val run = ExtractionRun(
            contextId = tenant,
            lineage = ExtractionRunLineage.root(ExtractionRunRef("graph-privacy")),
            status = ExtractionRunStatus.RUNNING,
            startedAt = startedAt,
            subjectRefs = ExtractionRunSubjectRefs(
                actor = ExtractionActorRef("actor-token-1"),
                session = ExtractionSessionRef("session-token-1"),
            ),
            failures = listOf(
                ExtractionFailure.fromThrowable(
                    ExtractionFailureCode.DECODE_FAILED,
                    IllegalStateException("could not decode: $secret"),
                    startedAt,
                ),
            ),
        )

        store.save(run)

        val written = nodeProperties("ExtractionRun", "graph-privacy").values.joinToString(" ") { it.toString() }
        assertTrue(secret !in written, "a source-text substring reached the stored row")
        assertTrue("IllegalStateException" in written, "the classified cause chain is what is stored instead")
    }

    // ---- corrupt and oversized ----

    @Test
    fun `a corrupt row is skipped and the readable runs still come back`() {
        store.save(running("graph-good-1", startedAt = startedAt))
        store.save(running("graph-good-2", startedAt = startedAt.plusSeconds(10)))
        persistenceManager.execute(
            QuerySpecification.withStatement(
                """
                CREATE (:ExtractionRun {
                    contextId: ${'$'}contextId,
                    runId: 'graph-corrupt',
                    startedAtEpochSecond: ${'$'}epochSecond,
                    startedAtNano: 0
                })
                """.trimIndent(),
            ).bind(
                mapOf(
                    "contextId" to tenant.value,
                    // Newest, so it sorts to the front and a backend that failed the whole read
                    // rather than skipping the row would return nothing at all.
                    "epochSecond" to startedAt.plusSeconds(100).epochSecond,
                ),
            ),
        )

        assertEquals(
            listOf("graph-good-2", "graph-good-1"),
            store.runsInContext(tenant, 10, null).map { it.ref.runId },
        )
        assertNull(store.findRun(key("graph-corrupt")))
    }

    @Test
    fun `a row with no sort key is kept out of the order rather than filling a page slot`() {
        store.save(running("graph-ordered"))
        persistenceManager.execute(
            QuerySpecification.withStatement(
                "CREATE (:ExtractionRun {contextId: \$contextId, runId: 'graph-no-sort-key'})",
            ).bind(mapOf("contextId" to tenant.value)),
        )

        // A limit of one: if the sort-key-less node reached the order it would sort first, spend the
        // only slot, and then be dropped, so this would come back empty.
        assertEquals(
            listOf("graph-ordered"),
            store.runsInContext(tenant, 1, null).map { it.ref.runId },
        )
    }

    @Test
    fun `a tenant too long to key on is refused at the write, and reads for it are empty`() {
        val oversized = ContextId("t".repeat(ExtractionRunSchema.MAX_CONTEXT_ID_LENGTH + 1))
        val run = running("graph-oversized", contextId = oversized)

        val rejected = assertThrows(IllegalArgumentException::class.java) { store.save(run) }

        assertTrue(
            "${ExtractionRunSchema.MAX_CONTEXT_ID_LENGTH}" in rejected.message.orEmpty(),
            "the message names the cap: ${rejected.message}",
        )
        // The value itself is a host identifier and stays out of the message.
        assertTrue(oversized.value !in rejected.message.orEmpty())
        assertTrue(store.runsInContext(oversized, 10, null).isEmpty())
        // The largest tenant this store will key on is stored without complaint.
        val atCap = ContextId("t".repeat(ExtractionRunSchema.MAX_CONTEXT_ID_LENGTH))
        store.save(running("graph-at-cap", contextId = atCap))
        assertEquals(1, store.runsInContext(atCap, 10, null).size)
    }

    // ---- fixtures ----

    private fun running(
        runId: String,
        contextId: ContextId = tenant,
        startedAt: Instant = this.startedAt,
        lineage: ExtractionRunLineage = ExtractionRunLineage.root(ExtractionRunRef(runId)),
    ): ExtractionRun = ExtractionRun(
        contextId = contextId,
        lineage = lineage,
        status = ExtractionRunStatus.RUNNING,
        startedAt = startedAt,
    )

    /**
     * A run with every optional field set, so the row mapper has something to get wrong.
     *
     * [failures] defaults to a failure naming an invocation this fixture also carries, and
     * [version] defaults to `0` — right for a first save. A caller building the two-phase sequence
     * `save` (invocations not yet recorded) → `recordInvocation` → `save` (with the failure) needs
     * the header to hold no such failure on the first save, since the store's own read-back inside
     * that call would otherwise reject a failure naming an attempt nothing has recorded yet — and
     * needs `version = 1` on the object it compares the second save's result against, since that
     * second save is what moves the header on from the version the first one left it at.
     */
    private fun fullyPopulated(
        runId: String,
        failures: List<ExtractionFailure> = listOf(
            ExtractionFailure(
                code = ExtractionFailureCode.MODEL_TIMEOUT,
                detail = "timed out after 90s",
                at = startedAt.plusSeconds(90),
                invocation = ExtractionInvocationId(0, 1),
            ),
        ),
        version: Long = 0,
    ): ExtractionRun {
        val parent = ExtractionRunLineage.root(ExtractionRunRef("$runId-parent"))
        return ExtractionRun(
            contextId = tenant,
            lineage = ExtractionRunLineage.childOf(
                runRef = ExtractionRunRef(runId),
                parent = parent,
                supersedesRunRef = ExtractionRunRef("$runId-superseded"),
                passIndex = 3,
            ),
            status = ExtractionRunStatus.RUNNING,
            startedAt = startedAt,
            profile = ExtractionContentProfileRef("meeting-notes", "2.1"),
            sourceRevisions = listOf(
                SourceRevisionRef("doc://one", "rev-1"),
                SourceRevisionRef("doc://two|with|pipes", "rev-2"),
            ),
            fingerprints = ExtractionRunFingerprints("prompt-abc", "schema-def", "metamodel-ghi"),
            runtime = ExtractionRuntimeIdentity("llm-extractor", "1.4.0", "assistant", "dice", "0.2.0"),
            requestedModel = ExtractionRequestedModelConfig(
                modelRole = "extraction",
                requestedModel = "some-model",
                temperature = 0.25,
                topP = 0.9,
                topK = 40,
                maxTokens = 4096,
                presencePenalty = -0.5,
                frequencyPenalty = 0.5,
                thinkingFingerprint = "think-1",
                selectionFingerprint = "select-1",
                timeout = Duration.ofSeconds(90),
            ),
            subjectRefs = ExtractionRunSubjectRefs(
                actor = ExtractionActorRef("actor-1"),
                request = ExtractionRequestRef("request-1"),
                session = ExtractionSessionRef("session-1"),
                personalization = ExtractionPersonalizationRef("personalization-1"),
                deployment = ExtractionDeploymentRef("deployment-1"),
            ),
            experimentRef = ExtractionExperimentRef("experiment-1"),
            cohortRef = ExtractionCohortRef("cohort-b"),
            replayFidelity = ExtractionReplayFidelity.APPROXIMATE,
            counts = ExtractionRunCounts(1, 2, 3, 4, 5, 6),
            invocations = listOf(
                ExtractionInvocationRecord(
                    id = ExtractionInvocationId(0, 1),
                    outcome = ExtractionInvocationOutcome.FAILED,
                    configuredService = "openai",
                    startedAt = startedAt,
                    finishedAt = startedAt.plusMillis(1_500),
                    usage = ExtractionModelUsage(11, 22, 33, 44, 55),
                    providerResponse = ExtractionProviderResponseFacts("m-1", "resp-1", "length", "fp-1"),
                ),
                ExtractionInvocationRecord(id = ExtractionInvocationId(0, 2)),
            ),
            failures = failures,
            version = version,
        )
    }

    private fun key(runId: String, contextId: ContextId = tenant) =
        ExtractionRunKey(contextId, ExtractionRunRef(runId))

    // ---- graph probes ----

    private fun terminalWriteCount(key: ExtractionRunKey): Int = countOf(
        """
        MATCH (t:ExtractionRunTerminalWrite {contextId: ${'$'}contextId, runId: ${'$'}runId})
        RETURN {count: count(t)} AS row
        """.trimIndent(),
        key,
    )

    private fun childNodeCount(key: ExtractionRunKey): Int = countOf(
        """
        MATCH (:ExtractionRun {contextId: ${'$'}contextId, runId: ${'$'}runId})
              -[:RECORDED]->(i:ExtractionRunInvocation)
        RETURN {count: count(i)} AS row
        """.trimIndent(),
        key,
    )

    private fun countOf(statement: String, key: ExtractionRunKey): Int =
        ((rows(statement, mapOf("contextId" to key.contextId.value, "runId" to key.runRef.runId))
            .single()["count"]) as Number).toInt()

    private fun storedFingerprint(key: ExtractionRunKey): String? = rows(
        """
        MATCH (t:ExtractionRunTerminalWrite {contextId: ${'$'}contextId, runId: ${'$'}runId})
        RETURN {fingerprint: t.fingerprint} AS row
        """.trimIndent(),
        mapOf("contextId" to key.contextId.value, "runId" to key.runRef.runId),
    ).single()["fingerprint"]?.toString()

    /** Every property actually written on one node, read back raw rather than through the mapper. */
    private fun nodeProperties(label: String, runId: String): Map<String, Any?> = rows(
        "MATCH (n:$label {contextId: \$contextId, runId: \$runId}) RETURN properties(n) AS row",
        mapOf("contextId" to tenant.value, "runId" to runId),
    ).single().entries.associate { (k, v) -> k.toString() to v }

    private fun rows(statement: String, bindings: Map<String, Any?>): List<Map<*, *>> {
        @Suppress("UNCHECKED_CAST")
        val spec = QuerySpecification.withStatement(statement).bind(bindings) as QuerySpecification<Any>
        return persistenceManager.query(spec).filterIsInstance<Map<*, *>>()
    }
}

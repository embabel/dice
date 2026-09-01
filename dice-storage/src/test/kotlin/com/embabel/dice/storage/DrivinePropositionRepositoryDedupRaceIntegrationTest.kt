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
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.Proposition
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Delegates every call to [delegate] unchanged, except that the first [maybeGetOne] call after
 * [arm] records the calling thread as the writer and registers a [TransactionSynchronization] on
 * whichever physical transaction is current — the dedup transaction's own, whether that is
 * `save`'s [DrivinePropositionRepository.txTemplate] committing independently or the class-level
 * ambient one it has joined. That callback fires right before the real commit, pausing there until
 * [proceed] is signaled, at exactly the moment the test needs to pin.
 *
 * The first later [maybeGetOne] call from a *different* thread — the sibling's own existence check
 * — is let through to [delegate] first, and only *after it returns* does this record
 * [siblingReadResult] and count down [siblingReadCompleted]. That ordering is the whole point: the
 * signal must mean "the read finished", not "the read started", or a test racing to react to it
 * could release the writer before the sibling's statement has actually reached the database — the
 * exact gap that let an earlier version of this class's test pass against the regression by luck.
 * [siblingReadResult] then says what the sibling actually saw: null is the pre-commit read this
 * test exists to force; non-null this early would mean the writer's pause did not hold.
 *
 * Single-shot by design (guarded by [armed]): only the first thread through the dedup path arms
 * the hook. The writer's own second `maybeGetOne` call (`existsById`, right after `findDuplicateId`
 * in [DrivinePropositionRepository.findOrPersist]) runs on the same thread that armed it, so it
 * does not get mistaken for the sibling's.
 */
class RaceWindowPersistenceManager(private val delegate: PersistenceManager) : PersistenceManager by delegate {

    private val armed = AtomicBoolean(false)
    private val siblingReadCaptured = AtomicBoolean(false)
    private val writerThread = AtomicReference<Thread?>(null)
    private var writerPaused: CountDownLatch? = null
    private var proceed: CountDownLatch? = null
    private var siblingReadCompleted: CountDownLatch? = null

    /** What the sibling's first read actually returned, set once [siblingReadCompleted] fires. */
    @Volatile
    var siblingReadResult: Any? = null
        private set

    /**
     * [writerPaused] fires the instant the writer is genuinely paused pre-commit, still inside
     * `save`'s `synchronized` block — the test must wait for it before it submits the sibling task
     * at all, or which of the two callers actually reaches the stripe lock first is a race in its
     * own right, undoing the rest of this class's determinism.
     */
    fun arm(writerPaused: CountDownLatch, proceed: CountDownLatch, siblingReadCompleted: CountDownLatch) {
        armed.set(false)
        siblingReadCaptured.set(false)
        writerThread.set(null)
        siblingReadResult = null
        this.writerPaused = writerPaused
        this.proceed = proceed
        this.siblingReadCompleted = siblingReadCompleted
    }

    override fun <T : Any> maybeGetOne(spec: QuerySpecification<T>): T? {
        if (armed.compareAndSet(false, true)) {
            writerThread.set(Thread.currentThread())
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun beforeCommit(readOnly: Boolean) {
                    writerPaused?.countDown()
                    // The 30s bound here is a last-resort anti-hang guard only, well past the ~10s
                    // window the test polls on its own — under normal execution the test's explicit
                    // proceed.countDown() always fires first, driven by a directly observed fact.
                    // See the test class KDoc for why that distinction matters.
                    proceed?.await(30, TimeUnit.SECONDS)
                }
            })
            return delegate.maybeGetOne(spec)
        }
        val isSibling = Thread.currentThread() !== writerThread.get()
        val result = delegate.maybeGetOne(spec)
        if (isSibling && siblingReadCaptured.compareAndSet(false, true)) {
            siblingReadResult = result
            siblingReadCompleted?.countDown()
        }
        return result
    }
}

/**
 * Marks [RaceWindowPersistenceManager] as the [Primary] `PersistenceManager` in this test class's
 * own Spring context, wrapping the real one [TestApplication] supplies — the same substitution
 * technique [DrivineObservedSchemaSourceTransactionIntegrationTest] uses, in a distinct
 * `@SpringBootTest` combination so it cannot leak into the other Drivine integration tests.
 */
@TestConfiguration
open class RaceWindowPersistenceManagerConfig {

    @Bean
    @Primary
    open fun raceWindowPersistenceManager(
        @Qualifier("persistenceManager") real: PersistenceManager,
    ): RaceWindowPersistenceManager = RaceWindowPersistenceManager(real)
}

/**
 * The concurrent counterpart to `DrivinePropositionStoreIntegrationTest`'s sequential dedup test
 * and its multi-waiter throughput test. `save`'s own KDoc says the stripe lock is held across the
 * transaction commit, so a same-stripe sibling can never read pre-commit and slip a duplicate past
 * the existence check. Neither of those other two tests can pin that specific claim: a free-running
 * pool of callers proves dedup holds under load, but nothing forces any one of them to actually
 * attempt a read during the narrow window between lock release and commit, so a regression there
 * can pass by favorable scheduling as easily as it can be caught.
 *
 * This test removes the luck with an explicit handshake built on positive facts. [RaceWindowPersistenceManager]
 * pins the writer inside [TransactionSynchronization.beforeCommit] — after its own dedup
 * transaction's real commit is ready to run but before it actually runs. Once the sibling is
 * submitted, the test polls for one of two *directly observed* facts, always something that
 * actually happened: either the sibling's own `maybeGetOne` call *completes* (proof its read ran to
 * completion, statement and all, while the writer's commit was still paused — the regression), or a
 * JMX thread dump shows the sibling genuinely blocked entering the identical stripe-lock monitor
 * `save` itself would use for this `(contextId, text)` pair, and that specific lock alone (proof
 * the fix's mutual exclusion is what's holding it back). Only once one of those two facts is
 * confirmed does the test release the writer — reacting to the read merely *starting* would still
 * leave the actual database round trip racing the writer's release, so the signal fires only once
 * [RaceWindowPersistenceManager.siblingReadResult] is already captured.
 *
 * The thread-dump check is deliberately narrower than a bare `Thread.State.BLOCKED` read: a thread
 * can report `BLOCKED` for reasons that have nothing to do with this test (a connection-pool
 * checkout, for instance), and treating any such incidental block as proof of the *specific*
 * stripe-lock contention this test is pinning would reopen exactly the ambiguity this rewrite
 * exists to close. Comparing the JMX lock owner's identity against the actual monitor
 * [DrivinePropositionRepository.lockFor] would hand the sibling (read via reflection, since the
 * field is private and this is the only way to name that specific object from a test) is what
 * turns "blocked on our lock, specifically" into a verified claim. A bare `Future` timeout — the
 * previous version of this test — cannot tell "the sibling hasn't been scheduled yet" apart from
 * "the sibling is intentionally blocked": both look like nothing happening yet, which is exactly
 * what let the regression's mutation test pass by favorable scheduling in that earlier version.
 *
 * That handshake deterministically distinguishes both outcomes: with `txTemplate`'s commit boundary
 * independent of the ambient transaction (the shipped fix), the sibling blocks entering the stripe
 * lock's monitor and cannot even begin its read until the writer is released — the test confirms
 * that specific lock ownership, releases the writer, and the sibling then reads the now-committed
 * proposition. With the boundary joined to the ambient transaction (the regression), the lock is
 * already free by the time the hook fires, so the sibling's read runs to completion immediately,
 * finds nothing, and is observed directly with its empty result captured — the test releases the
 * writer only after that read has already finished, so the sibling genuinely saw nothing committed
 * before it went on to write its own duplicate. This is one deterministic run with no survival
 * count to justify.
 *
 * What each arm of the wait proves, stated plainly: a captured [RaceWindowPersistenceManager.siblingReadResult]
 * of `null` proves the sibling's existence check ran to completion, over the network, and found
 * nothing while the writer's commit was still pending — the regression, reproduced. A JMX-confirmed
 * block on the exact stripe-lock monitor proves the sibling cannot even begin that same check yet —
 * the fix, holding. Neither is inferred from an elapsed timeout with nothing else to show for it;
 * the test fails outright, loudly, if it observes neither within its wait window, or if it observes
 * a completed sibling read that is *not* empty (a sign the writer's pause did not hold).
 */
@SpringBootTest(classes = [TestApplication::class, RaceWindowPersistenceManagerConfig::class])
class DrivinePropositionRepositoryDedupRaceIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun neo4jProperties(registry: DynamicPropertyRegistry) = Neo4jTestContainer.registerProperties(registry)

        /**
         * The private lock [DrivinePropositionRepository.save] itself would take for this pair, read via
         * reflection on the real target — not the CGLIB proxy `repository` autowires. The proxy shell
         * never runs its own field initializers (Spring builds it without calling the target's
         * constructor), so [DrivinePropositionRepository.dedupLocks] is null there; only the actual
         * target instance the proxy delegates to has the array `save` itself indexes into.
         */
        private fun stripeLockFor(repository: DrivinePropositionRepository, contextId: String, text: String): Any {
            val target = (repository as org.springframework.aop.framework.Advised)
                .targetSource.target as DrivinePropositionRepository
            val method = DrivinePropositionRepository::class.java.getDeclaredMethod(
                "lockFor", String::class.java, String::class.java,
            )
            method.isAccessible = true
            return method.invoke(target, contextId, text)
        }

        /** True only if [thread] is JMX-reported as `BLOCKED` entering [monitor] specifically — not any lock. */
        private fun isBlockedOn(thread: Thread, monitor: Any): Boolean {
            val info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId(), 1) ?: return false
            if (info.threadState != Thread.State.BLOCKED) return false
            val lockInfo = info.lockInfo ?: return false
            return lockInfo.identityHashCode == System.identityHashCode(monitor) &&
                lockInfo.className == monitor.javaClass.name
        }
    }

    @Autowired
    private lateinit var repository: DrivinePropositionRepository

    @Autowired
    private lateinit var racePersistenceManager: RaceWindowPersistenceManager

    @Autowired
    private lateinit var persistenceManager: PersistenceManager

    @AfterEach
    fun cleanUp() {
        repository.clearAll()
        persistenceManager.execute(QuerySpecification.withStatement("MATCH (s:Source) DETACH DELETE s"))
    }

    private fun prop(text: String): Proposition = Proposition(
        contextId = ContextId("ctx"),
        text = text,
        mentions = emptyList<EntityMention>(),
        confidence = 0.9,
    )

    @Test
    fun `a sibling forced to read while the writer sits between lock release and commit still dedups to one proposition`() {
        val text = "Rod visited Sydney"
        val contextId = "ctx"
        val stripeLock = stripeLockFor(repository, contextId, text)

        val writerPaused = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val siblingReadCompleted = CountDownLatch(1)
        racePersistenceManager.arm(writerPaused, proceed, siblingReadCompleted)

        val pool = Executors.newFixedThreadPool(2)
        val siblingThread = AtomicReference<Thread?>(null)
        try {
            val writer = pool.submit<Proposition> { repository.save(prop(text)) }

            // Must confirm the writer is genuinely paused, holding the stripe lock, before the sibling
            // is even submitted — otherwise which of the two reaches the lock first is its own
            // unforced race, and the rest of this handshake would be pinned to the wrong thread.
            assertTrue(
                writerPaused.await(10, TimeUnit.SECONDS),
                "writer never reached its pre-commit pause — the dedup path no longer goes through maybeGetOne",
            )

            val sibling = pool.submit<Proposition> {
                siblingThread.set(Thread.currentThread())
                repository.save(prop(text))
            }

            // Poll for one of two directly observed facts — never inferred from elapsed time alone.
            // The read-completed arm only counts if the captured result is empty: a completed read
            // that already found a row would mean the writer's pause did not actually hold, which is
            // neither this test's regression case nor its fix case, and must not be read as either.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var siblingPreCommitReadObserved = false
            var siblingBlockedOnStripeLock = false
            while (System.nanoTime() < deadline) {
                if (siblingReadCompleted.await(0, TimeUnit.MILLISECONDS)) {
                    val seenByReader = racePersistenceManager.siblingReadResult
                    if (seenByReader != null) {
                        fail<Unit>(
                            "the sibling's read completed and already found a row ($seenByReader) while " +
                                "the writer was supposed to still be paused — the writer's pause did not hold",
                        )
                    }
                    siblingPreCommitReadObserved = true
                    break
                }
                val t = siblingThread.get()
                if (t != null && isBlockedOn(t, stripeLock)) {
                    siblingBlockedOnStripeLock = true
                    break
                }
                Thread.sleep(2)
            }
            if (!siblingPreCommitReadObserved && !siblingBlockedOnStripeLock) {
                fail<Unit>(
                    "the sibling neither completed a pre-commit read nor was confirmed blocked on the " +
                        "stripe lock within 10s — cannot tell whether the race window was actually exercised",
                )
            }

            proceed.countDown()

            val writerResult = writer.get(15, TimeUnit.SECONDS)
            val siblingResult = sibling.get(15, TimeUnit.SECONDS)

            assertEquals(1, repository.count(), "the forced-overlap sibling must still dedup to one proposition")
            assertTrue(
                writerResult.text == text && siblingResult.text == text,
                "both saves must resolve to the deduped proposition's text",
            )
        } finally {
            pool.shutdownNow()
        }
    }
}

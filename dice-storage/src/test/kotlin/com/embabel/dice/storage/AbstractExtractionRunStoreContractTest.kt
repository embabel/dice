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
import com.embabel.dice.common.DiceEvent
import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.common.ExtractionRunTransitioned
import com.embabel.dice.proposition.extraction.ExtractionActorRef
import com.embabel.dice.proposition.extraction.ExtractionCohortRef
import com.embabel.dice.proposition.extraction.ExtractionContentProfileRef
import com.embabel.dice.proposition.extraction.ExtractionExperimentRef
import com.embabel.dice.proposition.extraction.ExtractionFailure
import com.embabel.dice.proposition.extraction.ExtractionFailureCode
import com.embabel.dice.proposition.extraction.ExtractionFailureStage
import com.embabel.dice.proposition.extraction.ExtractionInvocationId
import com.embabel.dice.proposition.extraction.ExtractionInvocationOutcome
import com.embabel.dice.proposition.extraction.ExtractionInvocationRecord
import com.embabel.dice.proposition.extraction.ExtractionModelUsage
import com.embabel.dice.proposition.extraction.ExtractionProviderResponseFacts
import com.embabel.dice.proposition.extraction.ExtractionReplayFidelity
import com.embabel.dice.proposition.extraction.ExtractionRequestedModelConfig
import com.embabel.dice.proposition.extraction.ExtractionRun
import com.embabel.dice.proposition.extraction.ExtractionRunConflictException
import com.embabel.dice.proposition.extraction.ExtractionRunCounts
import com.embabel.dice.proposition.extraction.ExtractionRunFingerprints
import com.embabel.dice.proposition.extraction.ExtractionRunKey
import com.embabel.dice.proposition.extraction.ExtractionRunLineage
import com.embabel.dice.proposition.extraction.ExtractionRunNotFoundException
import com.embabel.dice.proposition.extraction.ExtractionRunRef
import com.embabel.dice.proposition.extraction.ExtractionRunStatus
import com.embabel.dice.proposition.extraction.ExtractionRunStore
import com.embabel.dice.proposition.extraction.ExtractionRunSubjectRefs
import com.embabel.dice.proposition.extraction.ExtractionRunTransition
import com.embabel.dice.proposition.extraction.ExtractionRunTransitionOutcome
import com.embabel.dice.proposition.extraction.ExtractionRuntimeIdentity
import com.embabel.dice.provenance.SourceRevisionRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Cross-backend contract for [ExtractionRunStore]: the lifecycle state machine, terminal-write
 * idempotency, and the scoping and bounding rules on every read. Each subclass supplies a store and
 * inherits the whole suite, so a backend that disagrees with the in-memory reference fails at
 * authoring time instead of in production.
 *
 * The cases here are the ones a durable backend gets wrong in a way a single-backend test would
 * miss. A `MERGE … SET` upsert passes "a terminal write is recorded" and fails "an incompatible
 * terminal rewrite is rejected", because overwriting is what MERGE does. A finder that filters in
 * memory passes every single-tenant read and fails "a page scopes before it limits". A chain walk
 * written as a recursive Cypher pattern passes on a healthy graph and hangs on a cycle.
 */
abstract class AbstractExtractionRunStoreContractTest {

    /**
     * A store holding nothing for the tenants below, announcing what it does to [listener].
     *
     * The listener is the one construction argument every backend has to accept, because "a run
     * that ends announces itself once" is part of the contract and a suite that could not observe
     * the announcement could not hold a backend to it.
     */
    protected abstract fun store(listener: DiceEventListener): ExtractionRunStore

    /** A store holding nothing for the tenants below, with nothing listening. */
    protected fun store(): ExtractionRunStore = store(DiceEventListener.DEV_NULL)

    /** Keeps every event a store hands it, so a test can count them. */
    private class RecordingListener : DiceEventListener {

        private val received = mutableListOf<DiceEvent>()

        override fun onEvent(event: DiceEvent) {
            synchronized(received) { received += event }
        }

        /** Everything received so far, as a snapshot the caller can read at its leisure. */
        fun events(): List<DiceEvent> = synchronized(received) { received.toList() }

        /** The runs announced as ended, in the order they were announced. */
        fun transitioned(): List<ExtractionRun> =
            events().filterIsInstance<ExtractionRunTransitioned>().map { it.run }
    }

    private val tenant = ContextId("contract-tenant")
    private val neighbour = ContextId("contract-neighbour")
    private val startedAt: Instant = Instant.parse("2026-08-31T10:15:30Z")
    private val finishedAt: Instant = Instant.parse("2026-08-31T10:15:47Z")

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

    private fun key(runId: String, contextId: ContextId = tenant) =
        ExtractionRunKey(contextId, ExtractionRunRef(runId))

    // ---- the state machine ----

    @Test
    fun `a saved run starts running and is readable by its key`() {
        val store = store()
        val run = running("contract-start")

        store.save(run)

        assertEquals(run, store.findRun(run.key()))
        assertEquals(ExtractionRunStatus.RUNNING, store.findRun(run.key())?.status)
    }

    @Test
    fun `a first save must name version 0`() {
        val store = store()
        val run = ExtractionRun(
            contextId = tenant,
            lineage = ExtractionRunLineage.root(ExtractionRunRef("contract-first-save-version")),
            status = ExtractionRunStatus.RUNNING,
            startedAt = startedAt,
            version = 7,
        )

        val thrown = assertThrows(IllegalArgumentException::class.java) { store.save(run) }
        assertTrue(thrown.message.orEmpty().contains("version 0"))
        assertNull(store.findRun(run.key()))
    }

    @Test
    fun `each terminal edge is reachable from running`() {
        listOf(
            ExtractionRunStatus.COMPLETED to ExtractionRunTransition.completed(finishedAt),
            ExtractionRunStatus.FAILED to ExtractionRunTransition.failed(finishedAt),
            ExtractionRunStatus.CANCELLED to ExtractionRunTransition.cancelled(finishedAt),
        ).forEach { (expected, transition) ->
            val store = store()
            val run = running("contract-edge-$expected")
            store.save(run)

            val result = store.transition(run.key(), transition)

            assertEquals(ExtractionRunTransitionOutcome.APPLIED, result.outcome)
            assertEquals(expected, result.run.status)
            assertEquals(finishedAt, result.run.finishedAt)
            assertEquals(expected, store.findRun(run.key())?.status)
        }
    }

    @Test
    fun `a terminal status cannot enter through save`() {
        val store = store()
        val completed = ExtractionRun(
            contextId = tenant,
            lineage = ExtractionRunLineage.root(ExtractionRunRef("contract-direct")),
            status = ExtractionRunStatus.COMPLETED,
            startedAt = startedAt,
            finishedAt = finishedAt,
        )

        assertThrows(IllegalArgumentException::class.java) { store.save(completed) }
        assertNull(store.findRun(completed.key()))
    }

    @Test
    fun `no edge leaves a terminal state`() {
        val terminals = listOf(
            ExtractionRunTransition.completed(finishedAt),
            ExtractionRunTransition.failed(finishedAt),
            ExtractionRunTransition.cancelled(finishedAt),
        )

        terminals.forEach { first ->
            val store = store()
            val run = running("contract-matrix-${first.status}")
            store.save(run)
            store.transition(run.key(), first)

            terminals.filter { it.status != first.status }.forEach { second ->
                assertThrows(ExtractionRunConflictException::class.java) {
                    store.transition(run.key(), second)
                }
            }
            assertEquals(first.status, store.findRun(run.key())?.status)
        }
    }

    @Test
    fun `a terminal run is not re-openable and takes no more records`() {
        val store = store()
        val run = running("contract-closed")
        store.save(run)
        store.transition(run.key(), ExtractionRunTransition.completed(finishedAt))

        assertThrows(ExtractionRunConflictException::class.java) { store.save(run) }
        assertThrows(ExtractionRunConflictException::class.java) {
            store.recordInvocation(run.key(), ExtractionInvocationRecord.planned(0))
        }
        assertEquals(ExtractionRunStatus.COMPLETED, store.findRun(run.key())?.status)
    }

    @Test
    fun `a terminal write against a run nobody started is rejected`() {
        val store = store()

        assertThrows(ExtractionRunNotFoundException::class.java) {
            store.transition(key("contract-absent"), ExtractionRunTransition.completed(finishedAt))
        }
    }

    @Test
    fun `an empty run completes`() {
        val store = store()
        val run = running("contract-empty")
        store.save(run)

        val result = store.transition(run.key(), ExtractionRunTransition.completed(finishedAt))

        assertEquals(ExtractionRunStatus.COMPLETED, result.run.status)
        assertTrue(result.run.invocations.isEmpty())
        assertTrue(store.invocationsOf(run.key()).isEmpty())
    }

    // ---- idempotency ----

    @Test
    fun `the same terminal write replays as success`() {
        val store = store()
        val run = running("contract-replay")
        store.save(run)
        val transition = ExtractionRunTransition.completed(
            finishedAt = finishedAt,
            counts = ExtractionRunCounts(propositionsPersisted = 7),
        )

        val first = store.transition(run.key(), transition)
        val second = store.transition(run.key(), transition)

        assertEquals(ExtractionRunTransitionOutcome.APPLIED, first.outcome)
        assertEquals(ExtractionRunTransitionOutcome.REPLAYED, second.outcome)
        assertEquals(first.run, second.run)
        // Both calls could return matching objects while the replay quietly rewrote the stored row.
        assertEquals(first.run, store.findRun(run.key()))
    }

    @Test
    fun `an incompatible terminal rewrite is rejected and changes nothing`() {
        // Incompatible means a different transition identity: another terminal status, or another
        // finish time. Both are checked here; the finish-time half gets its own per-status test
        // below, because this one varies status while holding finish time fixed.
        val store = store()
        val run = running("contract-rewrite")
        store.save(run)
        val recorded = ExtractionRunTransition.completed(
            finishedAt = finishedAt,
            counts = ExtractionRunCounts(propositionsPersisted = 7),
        )
        val applied = store.transition(run.key(), recorded).run

        assertThrows(ExtractionRunConflictException::class.java) {
            store.transition(run.key(), ExtractionRunTransition.failed(finishedAt))
        }
        assertThrows(ExtractionRunConflictException::class.java) {
            store.transition(
                run.key(),
                ExtractionRunTransition.completed(finishedAt.plusSeconds(1)),
            )
        }
        assertEquals(applied, store.findRun(run.key()))
    }

    @Test
    fun `a retry carrying different counts and failures replays, and the first write's outcome stands`() {
        // The fingerprint covers the transition's identity — the terminal status and the finish
        // time — so a second write agreeing on both is the same terminal write however different
        // the numbers it carries. It replays, and the run keeps what the write that landed first
        // delivered: a run's outcome is written once. A backend folding counts or failures into its
        // own digest would reject this as an incompatible rewrite, and one that took the retry's
        // payload would overwrite an outcome that had already been announced.
        val store = store()
        val run = running("contract-outcome-once")
        store.save(run)
        val landed = store.transition(
            run.key(),
            ExtractionRunTransition.completed(
                finishedAt,
                counts = ExtractionRunCounts(propositionsPersisted = 7),
                failures = emptyList(),
            ),
        )

        val retry = store.transition(
            run.key(),
            ExtractionRunTransition.completed(
                finishedAt,
                counts = ExtractionRunCounts(propositionsPersisted = 99, entitiesResolved = 4),
                failures = listOf(
                    ExtractionFailure.of(ExtractionFailureCode.INTERNAL, ExtractionFailureStage.PERSISTENCE),
                ),
            ),
        )

        assertEquals(ExtractionRunTransitionOutcome.APPLIED, landed.outcome)
        assertEquals(ExtractionRunTransitionOutcome.REPLAYED, retry.outcome)
        assertEquals(landed.run, retry.run)
        assertEquals(landed.run, store.findRun(run.key()))
        assertEquals(7, store.findRun(run.key())?.counts?.propositionsPersisted)
        assertEquals(emptyList<ExtractionFailure>(), store.findRun(run.key())?.failures)
    }

    // ---- announcing a run that ended ----

    @Test
    fun `an applied transition announces the run once, and a replay announces nothing`() {
        val listener = RecordingListener()
        val store = store(listener)
        val run = running("contract-announce-once")
        store.save(run)
        val transition = ExtractionRunTransition.completed(
            finishedAt,
            counts = ExtractionRunCounts(propositionsPersisted = 7),
        )

        val applied = store.transition(run.key(), transition)
        assertEquals(ExtractionRunTransitionOutcome.APPLIED, applied.outcome)
        assertEquals(
            listOf(applied.run),
            listener.transitioned(),
            "the call that ended the run must announce it exactly once, carrying the terminal run",
        )

        store.transition(run.key(), transition)
        store.transition(run.key(), transition)
        assertEquals(
            listOf(applied.run),
            listener.transitioned(),
            "a replay changed nothing and must announce nothing; a run that ended once is announced once",
        )
    }

    @Test
    fun `every terminal status announces the run it ended`() {
        listOf(
            ExtractionRunStatus.COMPLETED to ExtractionRunTransition.completed(finishedAt),
            ExtractionRunStatus.FAILED to ExtractionRunTransition.failed(finishedAt),
            ExtractionRunStatus.CANCELLED to ExtractionRunTransition.cancelled(finishedAt),
        ).forEach { (expected, transition) ->
            val listener = RecordingListener()
            val store = store(listener)
            val run = running("contract-announce-$expected")
            store.save(run)

            store.transition(run.key(), transition)

            assertEquals(
                listOf(expected),
                listener.transitioned().map { it.status },
                "$expected must be announced like every other terminal status",
            )
            assertEquals(
                listOf(run.key()),
                listener.transitioned().map { it.key() },
                "the announced run must be the one that ended, tenant included",
            )
        }
    }

    @Test
    fun `writes that do not end a run announce nothing`() {
        // A backend announcing on every write would pass the exactly-once test above and still
        // notify a downstream consumer about a run that has not finished.
        val listener = RecordingListener()
        val store = store(listener)
        val run = running("contract-announce-silence")

        store.save(run)
        store.recordInvocation(run.key(), ExtractionInvocationRecord.planned(0))
        store.save(store.findRun(run.key())!!.let { stored -> headerVariant(stored, counts = ExtractionRunCounts(sourcesRead = 1)) })
        store.findRun(run.key())
        store.runsInContext(tenant, 10, null)

        assertEquals(emptyList<DiceEvent>(), listener.events())
    }

    @Test
    fun `a rejected terminal write announces nothing`() {
        val listener = RecordingListener()
        val store = store(listener)
        val run = running("contract-announce-rejected")
        store.save(run)
        val applied = store.transition(run.key(), ExtractionRunTransition.completed(finishedAt)).run

        assertThrows(ExtractionRunConflictException::class.java) {
            store.transition(run.key(), ExtractionRunTransition.failed(finishedAt))
        }

        assertEquals(
            listOf(applied),
            listener.transitioned(),
            "the rejected write announced nothing, so the applied one's announcement stands alone",
        )
    }

    @Test
    fun `concurrent terminal writers announce the run exactly once between them`() {
        // The race that makes exactly-once worth pinning: many threads send the same terminal
        // write, one applies it and the rest replay. A backend announcing outside its own
        // compare-and-set, or on the replay path, notifies a consumer once per caller.
        val listener = RecordingListener()
        val store = store(listener)
        val run = running("contract-announce-race")
        store.save(run)
        val transition = ExtractionRunTransition.completed(finishedAt)
        val writers = 8
        val ready = java.util.concurrent.CountDownLatch(writers)
        val go = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(writers)
        try {
            val outcomes = (1..writers).map {
                pool.submit<ExtractionRunTransitionOutcome> {
                    ready.countDown()
                    go.await()
                    store.transition(run.key(), transition).outcome
                }
            }
            ready.await()
            go.countDown()
            val results = outcomes.map { it.get() }

            assertEquals(1, results.count { it == ExtractionRunTransitionOutcome.APPLIED })
            assertEquals(writers - 1, results.count { it == ExtractionRunTransitionOutcome.REPLAYED })
        } finally {
            pool.shutdownNow()
        }

        assertEquals(1, listener.transitioned().size, "$writers writers, one run that ended, one announcement")
    }

    @Test
    fun `a retry differing only in finish time is an incompatible rewrite, for every terminal status`() {
        // docs/design/extraction-runs.md:432 promises finish time participates in the fingerprint
        // like every other terminal field: minting a fresh finishedAt on a retry produces an
        // incompatible rewrite that gets rejected. Every other terminal test either holds finish
        // time fixed while varying status or counts, or varies finish time alongside counts, so
        // none of them would notice a comparator that ignores finish time. Holding status and
        // counts fixed here and varying only finishedAt is what isolates the promise, for each
        // terminal status in turn.
        val terminalFactories: List<Pair<String, (Instant, ExtractionRunCounts?) -> ExtractionRunTransition>> = listOf(
            "COMPLETED" to { at, counts -> ExtractionRunTransition.completed(at, counts = counts) },
            "FAILED" to { at, counts -> ExtractionRunTransition.failed(at, counts = counts) },
            "CANCELLED" to { at, counts -> ExtractionRunTransition.cancelled(at, counts = counts) },
        )
        terminalFactories.forEach { (statusName, transitionOf) ->
            val store = store()
            val run = running("contract-finish-only-$statusName")
            store.save(run)
            val counts = ExtractionRunCounts(propositionsPersisted = 7)
            val applied = store.transition(run.key(), transitionOf(finishedAt, counts)).run

            assertThrows(
                ExtractionRunConflictException::class.java,
                { store.transition(run.key(), transitionOf(finishedAt.plusSeconds(1), counts)) },
                "a $statusName retry naming a different finish time and nothing else must be rejected",
            )
            assertEquals(
                applied,
                store.findRun(run.key()),
                "the rejected finish-only $statusName retry must not have changed the persisted run, finish time included",
            )
        }
    }

    @Test
    fun `a replay after an interleaved invocation record is still a replay`() {
        // The discriminating case for what the fingerprint covers. A backend that re-derived the
        // digest from the stored run rather than recording the string the transition computed sees
        // a run whose invocation list has grown, produces a different digest, and rejects a correct
        // retry as an incompatible rewrite. It passes every other case in this suite.
        val store = store()
        val run = running("contract-interleaved")
        store.save(run)
        store.recordInvocation(run.key(), ExtractionInvocationRecord.planned(0))
        val transition = ExtractionRunTransition.completed(finishedAt)
        val applied = store.transition(run.key(), transition)

        val replayed = store.transition(run.key(), transition)
        assertEquals(ExtractionRunTransitionOutcome.REPLAYED, replayed.outcome)
        // The outcome alone does not prove the replay left storage untouched.
        assertEquals(applied.run, store.findRun(run.key()))
    }

    @Test
    fun `a rebuilt terminal write with the same payload replays`() {
        // A retry after a crash rebuilds the payload rather than keeping the object. The comparison
        // is on the fingerprint, so it still replays — and a backend comparing object identity or a
        // stored timestamp of the write would fail here.
        val store = store()
        val run = running("contract-rebuilt")
        store.save(run)
        val counts = ExtractionRunCounts(propositionsPersisted = 7)
        val applied = store.transition(run.key(), ExtractionRunTransition.completed(finishedAt, counts = counts))

        val replayed = store.transition(
            run.key(),
            ExtractionRunTransition.completed(
                finishedAt,
                counts = ExtractionRunCounts(propositionsPersisted = 7),
            ),
        )
        assertEquals(ExtractionRunTransitionOutcome.REPLAYED, replayed.outcome)
        assertEquals(applied.run, store.findRun(run.key()))
    }

    @Test
    fun `keeping counts and replacing them are different claims on the run that lands, and the digest sees neither`() {
        // Null means keep and a value means replace, and the difference shows in what the terminal
        // run holds. The digest sees neither: both writes name the same status and finish time, so
        // whichever arrives second replays. A backend has to get both halves right — apply the
        // distinction to the row it writes, and keep it out of the string it compares retries by.
        val keepStore = store()
        val keepRun = ExtractionRun(
            contextId = tenant,
            lineage = ExtractionRunLineage.root(ExtractionRunRef("contract-kept")),
            status = ExtractionRunStatus.RUNNING,
            startedAt = startedAt,
            counts = ExtractionRunCounts(propositionsPersisted = 4),
        )
        keepStore.save(keepRun)
        val keptTerminal = keepStore.transition(
            keepRun.key(),
            ExtractionRunTransition.completed(finishedAt, counts = null),
        ).run
        assertEquals(4, keptTerminal.counts.propositionsPersisted)

        val replaceStore = store()
        val replaceRun = ExtractionRun(
            contextId = tenant,
            lineage = ExtractionRunLineage.root(ExtractionRunRef("contract-replaced")),
            status = ExtractionRunStatus.RUNNING,
            startedAt = startedAt,
            counts = ExtractionRunCounts(propositionsPersisted = 4),
        )
        replaceStore.save(replaceRun)
        val replacedTerminal = replaceStore.transition(
            replaceRun.key(),
            ExtractionRunTransition.completed(
                finishedAt,
                counts = ExtractionRunCounts(entitiesResolved = 9),
            ),
        ).run
        assertEquals(0, replacedTerminal.counts.propositionsPersisted)
        assertEquals(9, replacedTerminal.counts.entitiesResolved)
        assertEquals(replacedTerminal, replaceStore.findRun(replaceRun.key()))

        // The digest half: against the run that kept its counts, a write naming the same status and
        // finish time replays, whether it repeats "keep" or asks to replace.
        assertEquals(
            ExtractionRunTransitionOutcome.REPLAYED,
            keepStore.transition(
                keepRun.key(),
                ExtractionRunTransition.completed(finishedAt, counts = null),
            ).outcome,
        )
        assertEquals(
            ExtractionRunTransitionOutcome.REPLAYED,
            keepStore.transition(
                keepRun.key(),
                ExtractionRunTransition.completed(
                    finishedAt,
                    counts = ExtractionRunCounts(propositionsPersisted = 4),
                ),
            ).outcome,
        )
        assertEquals(keptTerminal, keepStore.findRun(keepRun.key()))
    }

    @Test
    fun `a terminal write through the store preserves every field it does not own, header version included`() {
        // ExtractionRunTransition.applyTo is pinned field-by-field in dice's own
        // ExtractionRunLifecycleTest; this pins the same promise through a store's transition(),
        // which is what a backend that maps rows in and out actually has to get right.
        val store = store()
        val run = ExtractionRun(
            contextId = tenant,
            lineage = ExtractionRunLineage.root(ExtractionRunRef("contract-transition-carries")),
            status = ExtractionRunStatus.RUNNING,
            startedAt = startedAt,
            profile = ExtractionContentProfileRef("house-style", "v3"),
            sourceRevisions = listOf(SourceRevisionRef("uri:doc-a", "rev-11")),
            fingerprints = ExtractionRunFingerprints(
                promptTemplateFingerprint = "sha256:6d1f0a2b",
                schemaFingerprint = "sha256:a7c40e19",
                metamodelFingerprint = "sha256:0b93cc55",
            ),
            runtime = ExtractionRuntimeIdentity(extractor = "LlmPropositionExtractor", extractorVersion = "0.2.0"),
            requestedModel = ExtractionRequestedModelConfig(requestedModel = "model-large", temperature = 0.2),
            subjectRefs = ExtractionRunSubjectRefs(actor = ExtractionActorRef("actor:7f19aa02")),
            experimentRef = ExtractionExperimentRef("exp:prompt-v3"),
            cohortRef = ExtractionCohortRef("cohort:treatment"),
            replayFidelity = ExtractionReplayFidelity.strongest(),
            counts = ExtractionRunCounts(propositionsPersisted = 4),
        )
        store.save(run)
        // A genuine header change, accepted at the version the first save left, so the run this
        // test transitions is not left at the version-0 default by coincidence.
        val bumped = store.save(
            ExtractionRun(
                contextId = run.contextId,
                lineage = run.lineage,
                status = run.status,
                startedAt = run.startedAt,
                profile = run.profile,
                sourceRevisions = run.sourceRevisions,
                fingerprints = run.fingerprints,
                runtime = run.runtime,
                requestedModel = run.requestedModel,
                subjectRefs = run.subjectRefs,
                experimentRef = run.experimentRef,
                cohortRef = run.cohortRef,
                replayFidelity = run.replayFidelity,
                counts = ExtractionRunCounts(propositionsPersisted = 9),
                version = run.version,
            ),
        )
        // A bare planned() record has every observation field at its default, so a terminal mapper
        // that erases outcome, service, timing, usage and provider response would satisfy the
        // assertions below by coincidence. This one carries a value in every field transition()
        // does not own, so the same mapper has something real to lose.
        val recorded = store.recordInvocation(
            run.key(),
            ExtractionInvocationRecord(
                id = ExtractionInvocationId.planned(0),
                outcome = ExtractionInvocationOutcome.SUCCEEDED,
                configuredService = "service-carried",
                startedAt = startedAt,
                finishedAt = finishedAt,
                usage = ExtractionModelUsage(inputTokens = 42, outputTokens = 7),
                providerResponse = ExtractionProviderResponseFacts(responseModel = "model-carried"),
            ),
        )

        val terminal = store.transition(
            run.key(),
            ExtractionRunTransition.completed(finishedAt, counts = null, failures = null),
        ).run

        assertEquals(ExtractionRunStatus.COMPLETED, terminal.status)
        assertEquals(finishedAt, terminal.finishedAt)
        assertEquals(run.lineage, terminal.lineage)
        assertEquals(run.contextId, terminal.contextId)
        assertEquals(run.startedAt, terminal.startedAt)
        assertEquals(run.profile, terminal.profile)
        assertEquals(run.sourceRevisions, terminal.sourceRevisions)
        assertEquals(run.fingerprints, terminal.fingerprints)
        assertEquals(run.runtime, terminal.runtime)
        assertEquals(run.requestedModel, terminal.requestedModel)
        assertEquals(run.subjectRefs, terminal.subjectRefs)
        assertEquals(run.experimentRef, terminal.experimentRef)
        assertEquals(run.cohortRef, terminal.cohortRef)
        assertEquals(run.replayFidelity, terminal.replayFidelity)
        assertEquals(bumped.counts, terminal.counts)
        assertEquals(recorded.invocations, terminal.invocations)
        assertEquals(bumped.version, terminal.version)

        // What transition() returns can diverge from what a durable backend actually wrote, so
        // every field is checked again against an independent read.
        val persisted = store.findRun(run.key())!!
        assertEquals(ExtractionRunStatus.COMPLETED, persisted.status)
        assertEquals(finishedAt, persisted.finishedAt)
        assertEquals(run.lineage, persisted.lineage)
        assertEquals(run.contextId, persisted.contextId)
        assertEquals(run.startedAt, persisted.startedAt)
        assertEquals(run.profile, persisted.profile)
        assertEquals(run.sourceRevisions, persisted.sourceRevisions)
        assertEquals(run.fingerprints, persisted.fingerprints)
        assertEquals(run.runtime, persisted.runtime)
        assertEquals(run.requestedModel, persisted.requestedModel)
        assertEquals(run.subjectRefs, persisted.subjectRefs)
        assertEquals(run.experimentRef, persisted.experimentRef)
        assertEquals(run.cohortRef, persisted.cohortRef)
        assertEquals(run.replayFidelity, persisted.replayFidelity)
        assertEquals(bumped.counts, persisted.counts)
        assertEquals(recorded.invocations, persisted.invocations)
        assertEquals(bumped.version, persisted.version)
    }

    @Test
    fun `null counts and failures on a transition keep what the stored run held, and values replace them`() {
        // The existing fingerprint-distinction test proves null and a value are different claims;
        // this one proves what each claim actually leaves behind in the store, checked by reading
        // back what is persisted, alongside what is returned. Both counts objects below set disjoint
        // fields on purpose: a field-by-field merge
        // would land on the same numbers a genuine keep produces for the "kept" case, but would
        // splice old and new fields together for the "replaced" case, where only whole-value
        // replacement clears the old fields entirely.
        val originalCounts = ExtractionRunCounts(sourcesRead = 2, chunksProcessed = 5, propositionsExtracted = 3)
        val accumulated = listOf(ExtractionFailure(ExtractionFailureCode.RATE_LIMITED, ExtractionFailureStage.MODEL_CALL))
        val kept = running("contract-transition-keeps").let {
            ExtractionRun(
                contextId = it.contextId,
                lineage = it.lineage,
                status = it.status,
                startedAt = it.startedAt,
                counts = originalCounts,
                failures = accumulated,
            )
        }
        val storeA = store()
        storeA.save(kept)
        val terminalKept = storeA.transition(
            kept.key(),
            ExtractionRunTransition.completed(finishedAt, counts = null, failures = null),
        ).run
        assertEquals(originalCounts, terminalKept.counts)
        assertEquals(accumulated, terminalKept.failures)
        val persistedKept = storeA.findRun(kept.key())!!
        assertEquals(originalCounts, persistedKept.counts)
        assertEquals(accumulated, persistedKept.failures)

        val replaced = running("contract-transition-replaces").let {
            ExtractionRun(
                contextId = it.contextId,
                lineage = it.lineage,
                status = it.status,
                startedAt = it.startedAt,
                counts = originalCounts,
                failures = accumulated,
            )
        }
        val replacementCounts = ExtractionRunCounts(propositionsPersisted = 7, entitiesResolved = 4)
        val replacementFailures = listOf(ExtractionFailure(ExtractionFailureCode.DECODE_FAILED, ExtractionFailureStage.RESPONSE_DECODE))
        val storeB = store()
        storeB.save(replaced)
        val terminalReplaced = storeB.transition(
            replaced.key(),
            ExtractionRunTransition.completed(
                finishedAt,
                counts = replacementCounts,
                failures = replacementFailures,
            ),
        ).run
        assertEquals(replacementCounts, terminalReplaced.counts)
        assertEquals(replacementFailures, terminalReplaced.failures)
        val persistedReplaced = storeB.findRun(replaced.key())!!
        assertEquals(replacementCounts, persistedReplaced.counts)
        assertEquals(replacementFailures, persistedReplaced.failures)
    }

    @Test
    fun `a save that disagrees with the stored run's start time is rejected`() {
        val store = store()
        val run = running("contract-identity")
        store.save(run)

        assertThrows(ExtractionRunConflictException::class.java) {
            store.save(running("contract-identity", startedAt = startedAt.plusSeconds(30)))
        }
        assertEquals(run, store.findRun(run.key()))
    }

    @Test
    fun `a save that disagrees with the stored run's lineage is rejected`() {
        // Separate from the start-time case on purpose: a backend guarding one and not the other
        // passes a combined test that only varies the start time.
        val store = store()
        val run = running("contract-lineage")
        store.save(run)

        assertThrows(ExtractionRunConflictException::class.java) {
            store.save(
                running(
                    "contract-lineage",
                    lineage = ExtractionRunLineage.root(
                        runRef = ExtractionRunRef("contract-lineage"),
                        supersedesRunRef = ExtractionRunRef("contract-something-else"),
                    ),
                ),
            )
        }
        assertEquals(run, store.findRun(run.key()))
    }

    @Test
    fun `a running run carrying a finish time is rejected`() {
        val store = store()
        val finishedWhileRunning = ExtractionRun(
            contextId = tenant,
            lineage = ExtractionRunLineage.root(ExtractionRunRef("contract-finished-running")),
            status = ExtractionRunStatus.RUNNING,
            startedAt = startedAt,
            finishedAt = finishedAt,
        )

        assertThrows(IllegalArgumentException::class.java) { store.save(finishedWhileRunning) }
        assertNull(store.findRun(finishedWhileRunning.key()))
    }

    @Test
    fun `a header save embedding a brand-new invocation never creates the row`() {
        // recordInvocation is the sole door onto invocation state. An id a save's payload names,
        // that recordInvocation never wrote, must not appear afterward — a save cannot originate a
        // row any more than it can update or delete one.
        val store = store()
        val run = running("contract-save-never-creates")
        val saved = store.save(run)
        val neverRecorded = ExtractionInvocationRecord(id = ExtractionInvocationId.planned(0))

        store.save(
            ExtractionRun(
                contextId = tenant,
                lineage = run.lineage,
                status = ExtractionRunStatus.RUNNING,
                startedAt = startedAt,
                invocations = listOf(neverRecorded),
                version = saved.version,
            ),
        )

        assertTrue(store.invocationsOf(run.key()).isEmpty())
        assertTrue(store.findRun(run.key())!!.invocations.isEmpty())
    }

    @Test
    fun `a header save embedding a changed invocation never updates the stored row`() {
        val store = store()
        val run = running("contract-save-never-updates")
        val saved = store.save(run)
        val id = ExtractionInvocationId.planned(0)
        val original = ExtractionInvocationRecord(id = id, configuredService = "service-original")
        store.recordInvocation(run.key(), original)

        store.save(
            ExtractionRun(
                contextId = tenant,
                lineage = run.lineage,
                status = ExtractionRunStatus.RUNNING,
                startedAt = startedAt,
                invocations = listOf(original.copy(configuredService = "service-changed-by-save")),
                version = saved.version,
            ),
        )

        val record = store.invocationsOf(run.key()).single { it.id == id }
        assertEquals("service-original", record.configuredService)
    }

    @Test
    fun `a header save embedding an empty invocation list never deletes a stored row`() {
        // A durable backend gets this for free — a header write never touches a child row — and an
        // in-memory one that replaced the stored run wholesale would silently undo recordInvocation.
        // The two have to agree, so the rule is asserted here rather than in one backend's own test.
        val store = store()
        val run = running("contract-child-rows")
        store.save(run)
        store.recordInvocation(run.key(), ExtractionInvocationRecord.planned(0))

        val stale = ExtractionRun(
            contextId = tenant,
            lineage = run.lineage,
            status = ExtractionRunStatus.RUNNING,
            startedAt = startedAt,
            counts = ExtractionRunCounts(propositionsExtracted = 5),
        )
        val saved = store.save(stale)

        assertEquals(5, saved.counts.propositionsExtracted)
        assertEquals(
            listOf(ExtractionInvocationId(0, 1)),
            store.invocationsOf(run.key()).map { it.id },
        )
    }

    @Test
    fun `a stale header save carrying an old invocation snapshot leaves the newer stored invocation intact`() {
        // The defect this contract closes: recordInvocation never moves the header's version, so a
        // header save built on a run read before a later recordInvocation call landed can still name
        // the version currently stored and be accepted. A merged write let that stale, non-empty
        // invocation snapshot silently replace what the later call wrote; this contract's save never
        // looks at the snapshot at all, so the newer row survives regardless.
        val store = store()
        val run = running("contract-stale-invocation-snapshot")
        val saved = store.save(run)
        val id = ExtractionInvocationId.planned(0)
        store.recordInvocation(
            run.key(),
            ExtractionInvocationRecord(id = id, configuredService = "service-early"),
        )
        val staleSnapshot = store.findRun(run.key())!!

        store.recordInvocation(
            run.key(),
            ExtractionInvocationRecord(id = id, configuredService = "service-current"),
        )

        // A caller holding the earlier snapshot saves a genuine header change, still naming the
        // current version and still carrying the old invocation list.
        store.save(
            ExtractionRun(
                contextId = tenant,
                lineage = run.lineage,
                status = ExtractionRunStatus.RUNNING,
                startedAt = startedAt,
                counts = ExtractionRunCounts(propositionsExtracted = 3),
                invocations = staleSnapshot.invocations,
                version = saved.version,
            ),
        )

        val record = store.invocationsOf(run.key()).single { it.id == id }
        assertEquals("service-current", record.configuredService)
    }

    @Test
    fun `a first save lands at version 0, an accepted update raises it by one, and a replay leaves it alone`() {
        // The version a save returns is the store's word on what is now stored: a first save names 0
        // and gets 0 back, a header change accepted at the stored version gets that version plus
        // one, and a save whose content already matches keeps whatever is stored.
        val store = store()
        val run = running("contract-version-progression")
        val inserted = store.save(run)
        assertEquals(0L, inserted.version)
        assertEquals(0L, store.findRun(run.key())?.version)

        val changed = ExtractionRun(
            contextId = tenant,
            lineage = run.lineage,
            status = ExtractionRunStatus.RUNNING,
            startedAt = startedAt,
            counts = ExtractionRunCounts(propositionsPersisted = 3),
            version = 0,
        )
        val updated = store.save(changed)
        assertEquals(1L, updated.version)
        assertEquals(1L, store.findRun(run.key())?.version)

        val replay = ExtractionRun(
            contextId = tenant,
            lineage = run.lineage,
            status = ExtractionRunStatus.RUNNING,
            startedAt = startedAt,
            counts = ExtractionRunCounts(propositionsPersisted = 3),
            version = 1,
        )
        val replayed = store.save(replay)
        assertEquals(1L, replayed.version)
        assertEquals(1L, store.findRun(run.key())?.version)
    }

    @Test
    fun `a stale header save is rejected, and the header it read is left in place`() {
        // Writer A reads the run at version 0, does real work, and saves what it found. That save
        // is accepted and the header moves to version 1. Writer B read the run before any of that
        // landed, so its own save still names version 0 — the version the store has already moved
        // past.
        val store = store()
        val run = running("contract-stale-header")
        val inserted = store.save(run)
        assertEquals(0L, inserted.version)

        val profile = ExtractionContentProfileRef("profile-a", "v1")
        val revision = SourceRevisionRef("source-a", "rev-1")
        val failure = ExtractionFailure.of(ExtractionFailureCode.MODEL_TIMEOUT, ExtractionFailureStage.MODEL_CALL)
        val advanced = ExtractionRun(
            contextId = tenant,
            lineage = run.lineage,
            status = ExtractionRunStatus.RUNNING,
            startedAt = startedAt,
            profile = profile,
            sourceRevisions = listOf(revision),
            fingerprints = ExtractionRunFingerprints(promptTemplateFingerprint = "prompt-digest"),
            counts = ExtractionRunCounts(propositionsPersisted = 12),
            failures = listOf(failure),
            version = 0,
        )
        val savedByA = store.save(advanced)
        assertEquals(1L, savedByA.version)

        val stale = running("contract-stale-header") // version defaults to 0, the read A made stale
        assertThrows(ExtractionRunConflictException::class.java) {
            store.save(stale)
        }

        val current = store.findRun(run.key())
        assertEquals(profile, current?.profile)
        assertEquals(listOf(revision), current?.sourceRevisions)
        assertEquals("prompt-digest", current?.fingerprints?.promptTemplateFingerprint)
        assertEquals(12, current?.counts?.propositionsPersisted)
        assertEquals(listOf(failure), current?.failures)
        assertEquals(1L, current?.version)
    }

    @Test
    fun `an accepted header save replaces the whole header at once`() {
        // A field-by-field merge would keep the first profile, union the source revisions, and take
        // the larger count. A whole-header replace does none of that: the second save's values win
        // outright, proving the accepted write is not quietly combined with what came before it.
        // Every field a save owns is varied here, covering the full set beyond the four most
        // obvious ones, and the second save clears four nullable fields back to null and two
        // non-nullable ones back to their empty default — the shape a field-by-field merge is
        // most likely to get wrong, since "the new value is absent" and "keep what I don't
        // mention" look the same to it.
        val store = store()
        val run = running("contract-header-replace")
        val first = store.save(run)

        val firstUpdate = ExtractionRun(
            contextId = tenant,
            lineage = run.lineage,
            status = ExtractionRunStatus.RUNNING,
            startedAt = startedAt,
            profile = ExtractionContentProfileRef("profile-old", "v1"),
            sourceRevisions = listOf(SourceRevisionRef("source-a", "rev-1")),
            fingerprints = ExtractionRunFingerprints(promptTemplateFingerprint = "prompt-old"),
            runtime = ExtractionRuntimeIdentity(extractor = "extractor-old"),
            requestedModel = ExtractionRequestedModelConfig(requestedModel = "model-old"),
            subjectRefs = ExtractionRunSubjectRefs(actor = ExtractionActorRef("actor-old")),
            experimentRef = ExtractionExperimentRef("exp-old"),
            cohortRef = ExtractionCohortRef("cohort-old"),
            replayFidelity = ExtractionReplayFidelity.APPROXIMATE,
            counts = ExtractionRunCounts(propositionsPersisted = 12),
            failures = listOf(ExtractionFailure.of(ExtractionFailureCode.MODEL_TIMEOUT, ExtractionFailureStage.MODEL_CALL)),
            version = first.version,
        )
        val second = store.save(firstUpdate)
        assertEquals(1L, second.version)

        val secondUpdate = ExtractionRun(
            contextId = tenant,
            lineage = run.lineage,
            status = ExtractionRunStatus.RUNNING,
            startedAt = startedAt,
            profile = null,
            sourceRevisions = listOf(SourceRevisionRef("source-b", "rev-1")),
            fingerprints = ExtractionRunFingerprints(promptTemplateFingerprint = "prompt-new"),
            runtime = ExtractionRuntimeIdentity(),
            requestedModel = null,
            subjectRefs = ExtractionRunSubjectRefs(),
            experimentRef = null,
            cohortRef = null,
            replayFidelity = ExtractionReplayFidelity.NONE,
            counts = ExtractionRunCounts(propositionsPersisted = 3),
            failures = emptyList(),
            version = second.version,
        )
        val third = store.save(secondUpdate)

        assertNull(third.profile)
        assertEquals(listOf(SourceRevisionRef("source-b", "rev-1")), third.sourceRevisions)
        assertEquals("prompt-new", third.fingerprints.promptTemplateFingerprint)
        assertEquals(ExtractionRuntimeIdentity(), third.runtime)
        assertNull(third.requestedModel)
        assertEquals(ExtractionRunSubjectRefs(), third.subjectRefs)
        assertNull(third.experimentRef)
        assertNull(third.cohortRef)
        assertEquals(ExtractionReplayFidelity.NONE, third.replayFidelity)
        assertEquals(3, third.counts.propositionsPersisted)
        assertEquals(emptyList<ExtractionFailure>(), third.failures)
        assertEquals(2L, third.version)

        // save() could return the correctly assembled replacement while persisting a field-merged
        // or under-versioned row, so what is actually stored is checked field for field too,
        // including the ones that were cleared this time around.
        val persisted = store.findRun(run.key())!!
        assertEquals(third, persisted)
        assertNull(persisted.profile)
        assertEquals(ExtractionRuntimeIdentity(), persisted.runtime)
        assertNull(persisted.requestedModel)
        assertEquals(ExtractionRunSubjectRefs(), persisted.subjectRefs)
        assertNull(persisted.experimentRef)
        assertNull(persisted.cohortRef)
        assertEquals(ExtractionReplayFidelity.NONE, persisted.replayFidelity)
    }

    @Test
    fun `a save whose content already matches what is stored replays as a no-op at a stale version`() {
        // The promise: a byte-identical resend is a no-op regardless of the version it names. A
        // caller retrying a save it never learned had landed must not be told it conflicted.
        val store = store()
        val run = running("contract-replay-stale-version")
        val inserted = store.save(run)

        val advanced = ExtractionRun(
            contextId = tenant,
            lineage = run.lineage,
            status = ExtractionRunStatus.RUNNING,
            startedAt = startedAt,
            counts = ExtractionRunCounts(propositionsPersisted = 1),
            version = inserted.version,
        )
        val afterAdvance = store.save(advanced)
        assertEquals(1L, afterAdvance.version)

        // The caller's own copy still names the version it read the run at before the update
        // above landed, but the content it holds is exactly what that update produced.
        val resend = ExtractionRun(
            contextId = tenant,
            lineage = run.lineage,
            status = ExtractionRunStatus.RUNNING,
            startedAt = startedAt,
            counts = ExtractionRunCounts(propositionsPersisted = 1),
            version = inserted.version,
        )
        val saved = store.save(resend)

        assertEquals(afterAdvance.version, saved.version)
        assertEquals(afterAdvance, saved)
        // save() could hand back the correct no-op value while a check-then-write backend actually
        // re-persisted the row, moving its generation.
        assertEquals(afterAdvance, store.findRun(run.key()))
    }

    @Test
    fun `a stale-version resend replays as a no-op even with a terminal invocation recorded since`() {
        // A stale-version header resend still has to be recognised as a no-op on its header content
        // alone. What a save's own invocations field carries is not part of that comparison at all
        // now, so a terminal child record recorded since the resend was built plays no part in the
        // decision either way.
        val store = store()
        val run = running("contract-replay-stale-version-with-terminal-child")
        store.save(run)
        val invocationId = ExtractionInvocationId.planned(0)
        val terminal = ExtractionInvocationRecord(
            id = invocationId,
            outcome = ExtractionInvocationOutcome.SUCCEEDED,
            configuredService = "service-alpha",
        )
        store.recordInvocation(run.key(), terminal)

        val advanced = store.save(
            ExtractionRun(
                contextId = tenant,
                lineage = run.lineage,
                status = ExtractionRunStatus.RUNNING,
                startedAt = startedAt,
                counts = ExtractionRunCounts(propositionsPersisted = 1),
                version = 0L,
            ),
        )
        assertEquals(1L, advanced.version)
        assertEquals(listOf(terminal), advanced.invocationsInPlanOrder())

        // The stale resend: version 0, header content identical to what is stored, and an
        // invocations field the store will not even look at.
        val resend = ExtractionRun(
            contextId = tenant,
            lineage = run.lineage,
            status = ExtractionRunStatus.RUNNING,
            startedAt = startedAt,
            counts = ExtractionRunCounts(propositionsPersisted = 1),
            version = 0L,
        )
        val saved = store.save(resend)

        assertEquals(advanced.version, saved.version)
        assertEquals(advanced, saved)
        assertEquals(advanced, store.findRun(run.key()))
    }

    @Test
    fun `recording an invocation does not move the header version`() {
        val store = store()
        val run = running("contract-version-invocation")
        val saved = store.save(run)

        store.recordInvocation(run.key(), ExtractionInvocationRecord.planned(0))

        assertEquals(saved.version, store.findRun(run.key())?.version)

        // A save built on the header as it stood before that recording still names the current
        // version and is accepted; the recorded attempt survives because save never touches
        // invocation rows at all.
        val update = ExtractionRun(
            contextId = tenant,
            lineage = run.lineage,
            status = ExtractionRunStatus.RUNNING,
            startedAt = startedAt,
            counts = ExtractionRunCounts(propositionsExtracted = 5),
            version = saved.version,
        )
        val afterUpdate = store.save(update)

        assertEquals(5, afterUpdate.counts.propositionsExtracted)
        assertEquals(listOf(ExtractionInvocationId(0, 1)), afterUpdate.invocations.map { it.id })
    }

    // ---- concurrency ----

    @Test
    fun `two threads racing to end one run produce exactly one applied transition`() {
        // Compare-and-set is what the contract is named for, and a backend delivers it from its
        // transaction rather than from anything in this suite. A read-then-write with no isolation
        // passes every sequential case above and produces two APPLIED here, or one APPLIED and one
        // spurious conflict.
        repeat(20) { attempt ->
            val store = store()
            val run = running("contract-race-$attempt")
            store.save(run)
            val transition = ExtractionRunTransition.completed(finishedAt)

            val start = java.util.concurrent.CountDownLatch(1)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val outcomes = (0 until 2).map {
                    pool.submit<Any> {
                        start.await()
                        runCatching { store.transition(run.key(), transition).outcome }
                            .getOrElse { it }
                    }
                }
                start.countDown()
                val results = outcomes.map { it.get() }

                assertEquals(
                    1,
                    results.count { it == ExtractionRunTransitionOutcome.APPLIED },
                    "exactly one thread ends the run: $results",
                )
                assertEquals(
                    1,
                    results.count { it == ExtractionRunTransitionOutcome.REPLAYED },
                    "the loser replays rather than conflicting or applying: $results",
                )
            } finally {
                pool.shutdownNow()
            }
            // Counting outcomes proves one thread applied and one replayed; it does not prove the
            // persisted row is what the transition actually derives. The comparison is against
            // applyTo's own pure computation, independent of any store, so a race that corrupts the
            // write under contention — landing a status without its matching finishedAt, say — is
            // caught even though the outcome counts above still look right.
            assertEquals(transition.applyTo(run), store.findRun(run.key()))
        }
    }

    @Test
    fun `two threads racing to save the same header produce exactly one accepted write`() {
        // The version compare has to be atomic. A check-then-write backend with no isolation lets
        // both threads read version 0, both pass the comparison, and both land.
        repeat(20) { attempt ->
            val store = store()
            val run = running("contract-race-save-$attempt")
            val inserted = store.save(run)

            val start = java.util.concurrent.CountDownLatch(1)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val submitted = (0 until 2).map { thread ->
                    pool.submit<Result<ExtractionRun>> {
                        start.await()
                        val update = ExtractionRun(
                            contextId = tenant,
                            lineage = run.lineage,
                            status = ExtractionRunStatus.RUNNING,
                            startedAt = startedAt,
                            counts = ExtractionRunCounts(propositionsPersisted = thread + 1),
                            version = inserted.version,
                        )
                        runCatching { store.save(update) }
                    }
                }
                start.countDown()
                val results = submitted.map { it.get() }

                assertEquals(1, results.count { it.isSuccess }, "one save is accepted: $results")
                assertEquals(
                    1,
                    results.count { it.exceptionOrNull() is ExtractionRunConflictException },
                    "the other names a version the accepted save already moved past: $results",
                )
                // Counting successes proves one save landed; it does not prove which content is the
                // one actually stored. A backend that silently applied the losing thread's counts and
                // reported the winner's version would still pass every assertion above.
                val winner = results.single { it.isSuccess }.getOrThrow()
                assertEquals(winner, store.findRun(run.key()))
            } finally {
                pool.shutdownNow()
            }
            assertEquals(1L, store.findRun(run.key())?.version)
        }
    }

    @Test
    fun `two threads racing to record conflicting terminal outcomes for one attempt produce exactly one accepted write`() {
        repeat(20) { attempt ->
            val store = store()
            val run = running("contract-race-record-$attempt")
            store.save(run)
            val id = ExtractionInvocationId.planned(0)

            val start = java.util.concurrent.CountDownLatch(1)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val outcomes = listOf(
                    ExtractionInvocationOutcome.SUCCEEDED,
                    ExtractionInvocationOutcome.FAILED,
                )
                val submitted = outcomes.map { outcome ->
                    pool.submit<Result<ExtractionRun>> {
                        start.await()
                        val record = ExtractionInvocationRecord(
                            id = id,
                            outcome = outcome,
                            startedAt = startedAt,
                            finishedAt = finishedAt,
                        )
                        runCatching { store.recordInvocation(run.key(), record) }
                    }
                }
                start.countDown()
                val results = submitted.map { it.get() }

                assertEquals(1, results.count { it.isSuccess }, "one terminal write lands: $results")
                assertEquals(
                    1,
                    results.count { it.exceptionOrNull() is ExtractionRunConflictException },
                    "the other meets an attempt already terminal under a different outcome: $results",
                )
                // Counting a success and a conflict proves one write landed; it does not prove which
                // outcome, SUCCEEDED or FAILED, is the one actually stored. A backend that applied
                // the losing outcome and reported conflict for the winner would still pass both
                // assertions above.
                val winner = results.single { it.isSuccess }.getOrThrow()
                assertEquals(
                    winner.invocationsInPlanOrder().single { it.id == id },
                    store.invocationsOf(run.key()).single { it.id == id },
                )
            } finally {
                pool.shutdownNow()
            }
            assertEquals(1, store.invocationsOf(run.key()).size)
        }
    }

    @Test
    fun `a header save racing with a recordInvocation write settles independently and neither is lost`() {
        // save no longer contends for invocation state at all, so a header save and a
        // recordInvocation write racing on the same run never conflict with each other — each
        // settles on its own key. The header change and the invocation write both land.
        repeat(20) { attempt ->
            val store = store()
            val run = running("contract-race-save-record-$attempt")
            val inserted = store.save(run)
            val id = ExtractionInvocationId.planned(0)

            val start = java.util.concurrent.CountDownLatch(1)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val viaSave = pool.submit<ExtractionRun> {
                    start.await()
                    val header = ExtractionRun(
                        contextId = tenant,
                        lineage = run.lineage,
                        status = ExtractionRunStatus.RUNNING,
                        startedAt = startedAt,
                        counts = ExtractionRunCounts(propositionsPersisted = 1),
                        version = inserted.version,
                    )
                    store.save(header)
                }
                val viaRecord = pool.submit<ExtractionInvocationRecord> {
                    start.await()
                    val record = ExtractionInvocationRecord(
                        id = id,
                        outcome = ExtractionInvocationOutcome.FAILED,
                        startedAt = startedAt,
                        finishedAt = finishedAt,
                    )
                    store.recordInvocation(run.key(), record)
                    record
                }
                start.countDown()
                val savedHeader = viaSave.get()
                val recordedInvocation = viaRecord.get()

                assertEquals(1, savedHeader.counts.propositionsPersisted)
                assertEquals(
                    recordedInvocation,
                    store.invocationsOf(run.key()).single { it.id == id },
                )
                assertEquals(1, store.findRun(run.key())?.counts?.propositionsPersisted)
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `two concurrent IN_FLIGHT writers on different attempts both land, losing neither`() {
        // Each invocation row is decided on its own (invocationIndex, attempt) key, so two writers
        // recording different attempts contend for nothing shared: each is decided purely against
        // its own row, on a key entirely independent of the header generation. Both writes have to
        // survive.
        repeat(20) { attempt ->
            val store = store()
            val run = running("contract-race-disjoint-attempts-$attempt")
            store.save(run)

            val start = java.util.concurrent.CountDownLatch(1)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val first = pool.submit<ExtractionInvocationRecord> {
                    start.await()
                    val record = ExtractionInvocationRecord(
                        id = ExtractionInvocationId.planned(0),
                        configuredService = "service-zero",
                        startedAt = startedAt,
                    )
                    store.recordInvocation(run.key(), record)
                    record
                }
                val second = pool.submit<ExtractionInvocationRecord> {
                    start.await()
                    val record = ExtractionInvocationRecord(
                        id = ExtractionInvocationId.planned(1),
                        configuredService = "service-one",
                        startedAt = startedAt,
                    )
                    store.recordInvocation(run.key(), record)
                    record
                }
                start.countDown()
                val expected = listOf(first.get(), second.get()).sortedBy { it.id.invocationIndex }

                assertEquals(
                    expected,
                    store.invocationsOf(run.key()),
                    "both attempts must land, and neither writer's facts may be lost",
                )
            } finally {
                pool.shutdownNow()
            }
        }
    }

    // ---- invocation records ----

    @Test
    fun `records are keyed by invocation and attempt and read back in plan order`() {
        val store = store()
        val run = running("contract-records")
        store.save(run)
        val first = ExtractionInvocationRecord(id = ExtractionInvocationId.planned(1))

        listOf(2, 0).forEach { store.recordInvocation(run.key(), ExtractionInvocationRecord.planned(it)) }
        store.recordInvocation(run.key(), first)
        store.recordInvocation(run.key(), first)
        store.recordInvocation(run.key(), first.retry())

        assertEquals(
            listOf(
                ExtractionInvocationId(0, 1),
                ExtractionInvocationId(1, 1),
                ExtractionInvocationId(1, 2),
                ExtractionInvocationId(2, 1),
            ),
            store.invocationsOf(run.key()).map { it.id },
        )
    }

    @Test
    fun `a changed IN_FLIGHT record updates in place through recordInvocation, clearing an omitted fact`() {
        // The existing plan-order test replays the identical object twice, and adding a new fact on
        // top of an old one cannot tell field-merging apart from whole-record replacement — both
        // land on the same value for a field present in both writes. Omitting startedAt, usage and
        // providerResponse on the second write is the case that discriminates: only whole-record
        // replacement clears them: a backend merging fields would keep the first write's values
        // because the second write never mentioned them. Read back through invocationsOf — the
        // read path a durable backend actually has to get right — independent of the object
        // recordInvocation happens to return.
        val store = store()
        val run = running("contract-inflight-update")
        store.save(run)
        val id = ExtractionInvocationId.planned(0)
        store.recordInvocation(
            run.key(),
            ExtractionInvocationRecord(
                id = id,
                configuredService = "service-alpha",
                startedAt = startedAt,
                usage = ExtractionModelUsage(inputTokens = 10, outputTokens = 5),
                providerResponse = ExtractionProviderResponseFacts(responseModel = "model-alpha"),
            ),
        )

        store.recordInvocation(run.key(), ExtractionInvocationRecord(id = id, configuredService = "service-beta"))

        val record = store.invocationsOf(run.key()).single { it.id == id }
        assertEquals("service-beta", record.configuredService)
        assertNull(record.startedAt)
        assertNull(record.usage)
        assertNull(record.providerResponse)
        assertEquals(1, store.invocationsOf(run.key()).count { it.id == id })
    }

    @Test
    fun `a run with several attempts reads back equal to the run that was saved`() {
        // The case that catches a backend disagreeing with the reference on invocation order. A
        // durable store keeps identified rows and returns them in plan order; an in-memory one used
        // to return the order the caller listed. Since `equals` compares the list, a run whose
        // attempts arrived out of plan order came back unequal from one backend and equal from the
        // other. `ExtractionRun` normalizes to plan order at construction, so both agree — and one
        // attempt per call, which is all the rest of this suite uses, could never show it.
        //
        // The attempts arrive through recordInvocation, the door that owns invocation state. A
        // header save carries none of them, so arrival order here is the order of the calls.
        val store = store()
        val arrivalOrder = listOf(
            ExtractionInvocationRecord(id = ExtractionInvocationId(1, 2)),
            ExtractionInvocationRecord(id = ExtractionInvocationId(0, 1)),
            ExtractionInvocationRecord(id = ExtractionInvocationId(2, 1)),
            ExtractionInvocationRecord(id = ExtractionInvocationId(1, 1)),
        )
        val run = ExtractionRun(
            contextId = tenant,
            lineage = ExtractionRunLineage.root(ExtractionRunRef("contract-multi-attempt")),
            status = ExtractionRunStatus.RUNNING,
            startedAt = startedAt,
            invocations = arrivalOrder,
        )

        store.save(run)
        arrivalOrder.forEach { store.recordInvocation(run.key(), it) }
        val read = store.findRun(run.key())

        assertEquals(run, read)
        assertEquals(
            listOf(
                ExtractionInvocationId(0, 1),
                ExtractionInvocationId(1, 1),
                ExtractionInvocationId(1, 2),
                ExtractionInvocationId(2, 1),
            ),
            read?.invocations?.map { it.id },
        )
        assertEquals(read?.invocations, store.invocationsOf(run.key()))
    }

    @Test
    fun `recording against a run nobody started is rejected`() {
        val store = store()

        assertThrows(ExtractionRunNotFoundException::class.java) {
            store.recordInvocation(key("contract-absent"), ExtractionInvocationRecord.planned(0))
        }
    }

    @Test
    fun `a delayed IN_FLIGHT write does not replace a terminal record for the same attempt`() {
        val store = store()
        val run = running("contract-invocation-terminal")
        store.save(run)
        val id = ExtractionInvocationId.planned(0)
        val terminal = ExtractionInvocationRecord(
            id = id,
            outcome = ExtractionInvocationOutcome.SUCCEEDED,
            startedAt = startedAt,
            finishedAt = finishedAt,
        )
        store.recordInvocation(run.key(), terminal)

        // The call that raced ahead of the terminal write finally lands. It must not put the
        // attempt back to IN_FLIGHT and erase what the terminal write recorded.
        val delayed = ExtractionInvocationRecord(id = id)

        assertThrows(ExtractionRunConflictException::class.java) {
            store.recordInvocation(run.key(), delayed)
        }
        assertEquals(listOf(terminal), store.invocationsOf(run.key()))
    }

    @Test
    fun `repeating the same terminal invocation write is idempotent`() {
        // Carries a full providerResponse, so the replay round-trips that field through
        // recordInvocation unchanged too, alongside the other fields the invocation tests touch.
        val store = store()
        val run = running("contract-invocation-replay")
        store.save(run)
        val id = ExtractionInvocationId.planned(0)
        val terminal = ExtractionInvocationRecord(
            id = id,
            outcome = ExtractionInvocationOutcome.FAILED,
            startedAt = startedAt,
            finishedAt = finishedAt,
            providerResponse = ExtractionProviderResponseFacts(
                responseModel = "model-a",
                responseId = "resp-1",
                finishReason = "stop",
                systemFingerprint = "fp-1",
            ),
        )
        store.recordInvocation(run.key(), terminal)

        val replayed = store.recordInvocation(run.key(), terminal)

        assertEquals(listOf(terminal), replayed.invocationsInPlanOrder())
        assertEquals(listOf(terminal), store.invocationsOf(run.key()))
        assertEquals(terminal.providerResponse, store.invocationsOf(run.key()).single().providerResponse)
    }

    @Test
    fun `a terminal record's identical replay through recordInvocation lands back at its stored position`() {
        // docs/design/extraction-runs.md:302 promises an identical replay lands back at the
        // position the stored record already held. The idempotent-replay test above holds only
        // one record, where position is unobservable: replaying the only entry in a one-element
        // list cannot tell "stayed in place" apart from "removed and appended", since both produce
        // the same list. A backend that gets save's multi-record case right could still implement
        // recordInvocation's own replay as a remove-and-append. Three records, replaying the
        // first one, is what makes that reorder visible.
        val store = store()
        val run = running("contract-record-replay-position")
        store.save(run)
        val first = ExtractionInvocationRecord(
            id = ExtractionInvocationId.planned(0),
            outcome = ExtractionInvocationOutcome.SUCCEEDED,
            startedAt = startedAt,
            finishedAt = finishedAt,
        )
        val second = ExtractionInvocationRecord(id = ExtractionInvocationId.planned(1))
        val third = ExtractionInvocationRecord(id = ExtractionInvocationId.planned(2))
        store.recordInvocation(run.key(), first)
        store.recordInvocation(run.key(), second)
        store.recordInvocation(run.key(), third)

        store.recordInvocation(run.key(), first)

        val persisted = store.findRun(run.key())!!
        assertEquals(listOf(first, second, third), persisted.invocations)
    }

    @Test
    fun `a same-outcome write that differs from a terminal record is rejected too`() {
        // A different outcome is one way to erase what a terminal record holds. A delayed duplicate
        // claiming the same outcome, missing the usage and provider facts the first write actually
        // carried, would erase them just as surely if it were allowed to update in place.
        val store = store()
        val run = running("contract-invocation-sparse")
        store.save(run)
        val id = ExtractionInvocationId.planned(0)
        val terminal = ExtractionInvocationRecord(
            id = id,
            outcome = ExtractionInvocationOutcome.SUCCEEDED,
            configuredService = "service-alpha",
            startedAt = startedAt,
            finishedAt = finishedAt,
            usage = ExtractionModelUsage(inputTokens = 100, outputTokens = 20),
        )
        store.recordInvocation(run.key(), terminal)

        val sparse = ExtractionInvocationRecord(id = id, outcome = ExtractionInvocationOutcome.SUCCEEDED)

        assertThrows(ExtractionRunConflictException::class.java) {
            store.recordInvocation(run.key(), sparse)
        }
        assertEquals(listOf(terminal), store.invocationsOf(run.key()))
    }

    @Test
    fun `a CANCELLED terminal record is locked the same as SUCCEEDED and FAILED, through recordInvocation`() {
        // ExtractionRunStore.kt:204 locks SUCCEEDED, FAILED and CANCELLED alike, but every other
        // terminal-lock test in this suite happens to store a SUCCEEDED or FAILED record. A backend
        // that locks only those two outcomes and leaves CANCELLED writable would pass every one of
        // them.
        val store = store()
        val run = running("contract-invocation-cancelled-record")
        store.save(run)
        val id = ExtractionInvocationId.planned(0)
        val terminal = ExtractionInvocationRecord(
            id = id,
            outcome = ExtractionInvocationOutcome.CANCELLED,
            configuredService = "service-alpha",
            startedAt = startedAt,
            finishedAt = finishedAt,
        )
        store.recordInvocation(run.key(), terminal)

        assertThrows(ExtractionRunConflictException::class.java) {
            store.recordInvocation(run.key(), terminal.copy(configuredService = "service-beta"))
        }
        assertEquals(listOf(terminal), store.invocationsOf(run.key()))
    }

    // ---- per-field delta matrix: a field being present in a record is not the same as a test
    // discriminating on it. Every case below changes exactly one field from a fully-populated stored
    // record and asserts the one outcome that field's difference must produce, through every door
    // that can write it. A backend comparing or copying only some fields still passes a test that
    // varies several fields together; it fails here, because nothing except the field under test
    // differs from what is already stored. ----

    private fun richTerminalInvocation(id: ExtractionInvocationId) = ExtractionInvocationRecord(
        id = id,
        outcome = ExtractionInvocationOutcome.SUCCEEDED,
        configuredService = "service-base",
        startedAt = startedAt,
        finishedAt = finishedAt,
        usage = ExtractionModelUsage(inputTokens = 10, outputTokens = 5),
        providerResponse = ExtractionProviderResponseFacts(responseModel = "model-base"),
    )

    private fun richInFlightInvocation(id: ExtractionInvocationId) = ExtractionInvocationRecord(
        id = id,
        outcome = ExtractionInvocationOutcome.IN_FLIGHT,
        configuredService = "service-base",
        startedAt = startedAt,
        usage = ExtractionModelUsage(inputTokens = 10, outputTokens = 5),
        providerResponse = ExtractionProviderResponseFacts(responseModel = "model-base"),
    )

    /** Every field a terminal write's equality check must compare, one at a time — each as a
     *  non-null-to-non-null change and, where constructible, as a non-null-to-null clearing, since
     *  an `incoming ?: stored` implementation passes the first shape and only the second exposes it:
     *  `?:` only substitutes when the incoming side is null, so a non-null incoming value reaches
     *  the comparison either way. `finishedAt` differing alone is a legal record, since nothing but
     *  the terminal-lock check itself ties it to `outcome`, and it may clear to null even on a
     *  terminal record — nothing requires a terminal write to have finished at a known time.
     *  `startedAt` has no clearing entry in this table, but it is not exempt: this fixture's
     *  `finishedAt` is non-null, and `ExtractionInvocationRecord`'s own init check requires a
     *  non-null `startedAt` alongside a non-null `finishedAt`, so it is only this particular
     *  fixture that blocks the clearing entry here. `ExtractionInvocationRecord.kt` documents
     *  timing as an observation that a terminal record may lack entirely, so `startedAt` alone
     *  clearing against a terminal record whose `finishedAt` is already absent is a genuine,
     *  separately-covered case — see `a terminal record with absent timing rejects a resend that
     *  clears its start time` below, through both doors. `outcome` has no clearing entry and is
     *  the only field genuinely exempt from one: `val outcome: ExtractionInvocationOutcome` carries
     *  no `?`, so the type itself admits no null value to clear to. */
    private val terminalInvocationDeltas: List<Pair<String, (ExtractionInvocationRecord) -> ExtractionInvocationRecord>> = listOf(
        "outcome" to { r -> r.copy(outcome = ExtractionInvocationOutcome.FAILED) },
        "configuredService" to { r -> r.copy(configuredService = "service-changed") },
        "configuredService-cleared" to { r -> r.copy(configuredService = null) },
        "startedAt" to { r -> r.copy(startedAt = startedAt.plusSeconds(1)) },
        "finishedAt" to { r -> r.copy(finishedAt = finishedAt.plusSeconds(1)) },
        "finishedAt-cleared" to { r -> r.copy(finishedAt = null) },
        "usage" to { r -> r.copy(usage = ExtractionModelUsage(inputTokens = 99, outputTokens = 1)) },
        "usage-cleared" to { r -> r.copy(usage = null) },
        "providerResponse" to { r -> r.copy(providerResponse = ExtractionProviderResponseFacts(responseModel = "model-changed")) },
        "providerResponse-cleared" to { r -> r.copy(providerResponse = null) },
    )

    /** The same shape for an IN_FLIGHT update, non-null-to-null clearing entries included for the
     *  same `incoming ?: stored` reason. `finishedAt` is absent here, and the reason holds up as a
     *  genuine exemption: `ExtractionInvocationRecord`'s own init block requires
     *  `outcome != IN_FLIGHT || finishedAt == null`, so as long as a record's `outcome` stays
     *  `IN_FLIGHT` its `finishedAt` cannot be anything but `null` — there is no non-null value here
     *  to clear, for any mutator that leaves `outcome` alone. Every other field, including terminal
     *  `finishedAt` and terminal `startedAt`, now has a discriminating test somewhere in this suite.
     *  `startedAt` clears here even though it could not in the terminal table above, since
     *  IN_FLIGHT's `finishedAt` is always null and imposes no constraint on it. `outcome` has no
     *  clearing entry, the other genuine exemption: its declared type carries no `?`, so the
     *  language itself admits no null value to construct it with. `ExtractionRun.contextId` is the
     *  one header field with the same shape of exemption: `key()` derives `ExtractionRunKey` from
     *  `contextId` directly, and the store's map is keyed by that same `ExtractionRunKey`, so a
     *  `stored` run retrieved via `runs[run.key()]` always shares `run`'s `contextId` by
     *  construction — there is no way to look up a `stored` whose `contextId` differs from the
     *  `run` used to find it. */
    private val inFlightInvocationDeltas: List<Pair<String, (ExtractionInvocationRecord) -> ExtractionInvocationRecord>> = listOf(
        "outcome" to { r -> r.copy(outcome = ExtractionInvocationOutcome.SUCCEEDED) },
        "configuredService" to { r -> r.copy(configuredService = "service-changed") },
        "configuredService-cleared" to { r -> r.copy(configuredService = null) },
        "startedAt" to { r -> r.copy(startedAt = startedAt.plusSeconds(1)) },
        "startedAt-cleared" to { r -> r.copy(startedAt = null) },
        "usage" to { r -> r.copy(usage = ExtractionModelUsage(inputTokens = 99, outputTokens = 1)) },
        "usage-cleared" to { r -> r.copy(usage = null) },
        "providerResponse" to { r -> r.copy(providerResponse = ExtractionProviderResponseFacts(responseModel = "model-changed")) },
        "providerResponse-cleared" to { r -> r.copy(providerResponse = null) },
    )

    @Test
    fun `a terminal record rejects a recordInvocation resend that differs in exactly one field`() {
        terminalInvocationDeltas.forEach { (fieldName, mutate) ->
            val store = store()
            val run = running("contract-terminal-delta-record-$fieldName")
            store.save(run)
            val id = ExtractionInvocationId.planned(0)
            val stored = richTerminalInvocation(id)
            store.recordInvocation(run.key(), stored)

            assertThrows(
                ExtractionRunConflictException::class.java,
                { store.recordInvocation(run.key(), mutate(stored)) },
                "a resend differing only in $fieldName must be rejected through recordInvocation",
            )
            assertEquals(
                listOf(stored),
                store.invocationsOf(run.key()),
                "the rejected $fieldName-only resend must not have changed storage",
            )
        }
    }

    @Test
    fun `a terminal record with absent timing rejects a recordInvocation resend that clears its start time`() {
        // ExtractionInvocationRecord.kt:219 permits a terminal record with no timing at all — a
        // SUCCEEDED attempt with no startedAt is constructible, because timing here is only ever
        // an observation. The shared terminal-delta table above cannot reach this case: its
        // fixture's finishedAt is set, and finishedAt requires startedAt whenever finishedAt is
        // non-null, so nulling startedAt there would build a record the type itself refuses to
        // construct. A record whose finishedAt is already absent carries no such requirement, and
        // clearing its startedAt is exactly the resend an incoming.startedAt ?: stored.startedAt
        // implementation reads as identical.
        val store = store()
        val run = running("contract-terminal-startedAt-clear-record")
        store.save(run)
        val id = ExtractionInvocationId.planned(0)
        val stored = ExtractionInvocationRecord(
            id = id,
            outcome = ExtractionInvocationOutcome.SUCCEEDED,
            startedAt = startedAt,
        )
        store.recordInvocation(run.key(), stored)

        assertThrows(ExtractionRunConflictException::class.java) {
            store.recordInvocation(run.key(), stored.copy(startedAt = null))
        }
        assertEquals(listOf(stored), store.invocationsOf(run.key()))
    }

    @Test
    fun `an IN_FLIGHT record accepts a recordInvocation update that differs in exactly one field`() {
        inFlightInvocationDeltas.forEach { (fieldName, mutate) ->
            val store = store()
            val run = running("contract-inflight-delta-record-$fieldName")
            store.save(run)
            val id = ExtractionInvocationId.planned(0)
            val stored = richInFlightInvocation(id)
            store.recordInvocation(run.key(), stored)

            val updated = mutate(stored)
            store.recordInvocation(run.key(), updated)

            assertEquals(
                listOf(updated),
                store.invocationsOf(run.key()),
                "a $fieldName-only update must fully replace the stored record through recordInvocation",
            )
        }
    }

    /** Builds a variant of [base] with exactly the named header fields overridden, carrying every
     *  other owned field, `invocations`, and identity/lifecycle field straight through — the same
     *  shape as [InMemoryExtractionRunStore]'s own `rebuild`, since [ExtractionRun] publishes no
     *  `copy`. */
    private fun headerVariant(
        base: ExtractionRun,
        profile: ExtractionContentProfileRef? = base.profile,
        sourceRevisions: List<SourceRevisionRef> = base.sourceRevisions,
        fingerprints: ExtractionRunFingerprints = base.fingerprints,
        runtime: ExtractionRuntimeIdentity = base.runtime,
        requestedModel: ExtractionRequestedModelConfig? = base.requestedModel,
        subjectRefs: ExtractionRunSubjectRefs = base.subjectRefs,
        experimentRef: ExtractionExperimentRef? = base.experimentRef,
        cohortRef: ExtractionCohortRef? = base.cohortRef,
        replayFidelity: ExtractionReplayFidelity = base.replayFidelity,
        counts: ExtractionRunCounts = base.counts,
        failures: List<ExtractionFailure> = base.failures,
    ): ExtractionRun = ExtractionRun(
        contextId = base.contextId,
        lineage = base.lineage,
        status = base.status,
        startedAt = base.startedAt,
        profile = profile,
        sourceRevisions = sourceRevisions,
        fingerprints = fingerprints,
        runtime = runtime,
        requestedModel = requestedModel,
        subjectRefs = subjectRefs,
        experimentRef = experimentRef,
        cohortRef = cohortRef,
        replayFidelity = replayFidelity,
        counts = counts,
        invocations = base.invocations,
        failures = failures,
        version = base.version,
    )

    private fun richHeader(runId: String): ExtractionRun = ExtractionRun(
        contextId = tenant,
        lineage = ExtractionRunLineage.root(ExtractionRunRef(runId)),
        status = ExtractionRunStatus.RUNNING,
        startedAt = startedAt,
        profile = ExtractionContentProfileRef("profile-base", "v1"),
        sourceRevisions = listOf(SourceRevisionRef("source-base", "rev-1")),
        fingerprints = ExtractionRunFingerprints(promptTemplateFingerprint = "prompt-base"),
        runtime = ExtractionRuntimeIdentity(extractor = "extractor-base"),
        requestedModel = ExtractionRequestedModelConfig(requestedModel = "model-base"),
        subjectRefs = ExtractionRunSubjectRefs(actor = ExtractionActorRef("actor-base")),
        experimentRef = ExtractionExperimentRef("exp-base"),
        cohortRef = ExtractionCohortRef("cohort-base"),
        replayFidelity = ExtractionReplayFidelity.APPROXIMATE,
        counts = ExtractionRunCounts(propositionsPersisted = 5),
        failures = listOf(ExtractionFailure.of(ExtractionFailureCode.MODEL_TIMEOUT, ExtractionFailureStage.MODEL_CALL)),
    )

    /** Every field an accepted save owns, one at a time. `lineage`, `startedAt` and `version` sit
     *  outside save's ownership, each already has its own dedicated mismatch test, and a one-field
     *  delta on any of them throws a conflict — that case belongs with the terminal-conflict tests
     *  above this matrix, which covers accepted replacements. */
    private val headerFieldDeltas: List<Pair<String, (ExtractionRun) -> ExtractionRun>> = listOf(
        "profile" to { r -> headerVariant(r, profile = ExtractionContentProfileRef("profile-changed", "v2")) },
        "sourceRevisions" to { r -> headerVariant(r, sourceRevisions = listOf(SourceRevisionRef("source-changed", "rev-1"))) },
        "fingerprints" to { r -> headerVariant(r, fingerprints = ExtractionRunFingerprints(promptTemplateFingerprint = "prompt-changed")) },
        "runtime" to { r -> headerVariant(r, runtime = ExtractionRuntimeIdentity(extractor = "extractor-changed")) },
        "requestedModel" to { r -> headerVariant(r, requestedModel = ExtractionRequestedModelConfig(requestedModel = "model-changed")) },
        "subjectRefs" to { r -> headerVariant(r, subjectRefs = ExtractionRunSubjectRefs(actor = ExtractionActorRef("actor-changed"))) },
        "experimentRef" to { r -> headerVariant(r, experimentRef = ExtractionExperimentRef("exp-changed")) },
        "cohortRef" to { r -> headerVariant(r, cohortRef = ExtractionCohortRef("cohort-changed")) },
        "replayFidelity" to { r -> headerVariant(r, replayFidelity = ExtractionReplayFidelity.NONE) },
        "counts" to { r -> headerVariant(r, counts = ExtractionRunCounts(propositionsPersisted = 6)) },
        "failures" to { r -> headerVariant(r, failures = listOf(ExtractionFailure.of(ExtractionFailureCode.MODEL_TIMEOUT, ExtractionFailureStage.RESPONSE_DECODE))) },
    )

    @Test
    fun `a save changing exactly one owned header field bumps the version as a genuinely accepted write`() {
        // The bundled header-replace test above changes every owned field at once, so a backend
        // whose no-op comparison quietly skips one particular field still looks correct there —
        // every other field's difference is enough to trigger the version bump regardless of that
        // one field. Isolating a single field is what exercises its own contribution to the no-op
        // decision.
        headerFieldDeltas.forEach { (fieldName, mutate) ->
            val store = store()
            val stored = store.save(richHeader("contract-header-delta-$fieldName"))
            val changed = mutate(stored)

            val result = store.save(changed)

            assertEquals(
                stored.version + 1,
                result.version,
                "a $fieldName-only change must bump the version as a genuinely accepted write",
            )
            val persisted = store.findRun(stored.key())!!
            assertEquals(result, persisted, "the $fieldName-only change must be exactly what is persisted")
        }
    }

    // ---- closed value sets: a field being present with one value is not the same as a test
    // discriminating on every value it can hold. replayFidelity and the failure code are both
    // carried through the store without it ever branching on which value they hold, and that
    // makes an unexercised value more exposed to a drop-or-substitute bug, since nothing else in
    // the store would notice either. Every declared value gets its own row below, through every
    // door that can write it. ----

    @Test
    fun `every replay fidelity value comes back from save unchanged and outlives a terminal write`() {
        // replayFidelity has one entry door: ExtractionRunTransition.applyTo's own KDoc says
        // transition() always carries forward whatever value the run already holds.
        ExtractionReplayFidelity.entries.forEach { fidelity ->
            val store = store()
            val run = running("contract-replay-fidelity-${fidelity.name}").let {
                ExtractionRun(
                    contextId = it.contextId,
                    lineage = it.lineage,
                    status = it.status,
                    startedAt = it.startedAt,
                    replayFidelity = fidelity,
                )
            }
            val saved = store.save(run)
            assertEquals(fidelity, saved.replayFidelity, "$fidelity must come back from save unchanged")
            assertEquals(
                fidelity,
                store.findRun(run.key())?.replayFidelity,
                "$fidelity must read back unchanged through findRun",
            )

            val terminal = store.transition(run.key(), ExtractionRunTransition.completed(finishedAt)).run
            assertEquals(fidelity, terminal.replayFidelity, "$fidelity must survive the terminal write")
            assertEquals(
                fidelity,
                store.findRun(run.key())?.replayFidelity,
                "$fidelity must still read back after the terminal write",
            )
        }
    }

    @Test
    fun `every failure code comes back from save unchanged and outlives a terminal write that keeps it`() {
        ExtractionFailureCode.entries.forEach { code ->
            val store = store()
            val run = running("contract-failure-code-save-${code.name}").let {
                ExtractionRun(
                    contextId = it.contextId,
                    lineage = it.lineage,
                    status = it.status,
                    startedAt = it.startedAt,
                    failures = listOf(ExtractionFailure.of(code)),
                )
            }
            val saved = store.save(run)
            assertEquals(
                listOf(code),
                saved.failures.map { it.code },
                "$code must come back from save unchanged",
            )
            assertEquals(
                listOf(code),
                store.findRun(run.key())?.failures?.map { it.code },
                "$code must read back unchanged through findRun",
            )

            val terminal = store.transition(
                run.key(),
                ExtractionRunTransition.completed(finishedAt, counts = null, failures = null),
            ).run
            assertEquals(
                listOf(code),
                terminal.failures.map { it.code },
                "$code must survive a terminal write that keeps the run's failures",
            )
            assertEquals(
                listOf(code),
                store.findRun(run.key())?.failures?.map { it.code },
                "$code must still read back after the terminal write",
            )
        }
    }

    @Test
    fun `every failure code comes back from transition unchanged and survives its own replay`() {
        // The transition door's own write is the terminal write, so there is no later terminal
        // write left to check the value against; a replay of the same transition is the store's
        // own definition of "untouched" for a run that has already ended.
        ExtractionFailureCode.entries.forEach { code ->
            val store = store()
            val run = running("contract-failure-code-transition-${code.name}")
            store.save(run)
            val transition = ExtractionRunTransition.completed(
                finishedAt,
                failures = listOf(ExtractionFailure.of(code)),
            )

            val applied = store.transition(run.key(), transition).run
            assertEquals(
                listOf(code),
                applied.failures.map { it.code },
                "$code must come back from transition unchanged",
            )
            assertEquals(
                listOf(code),
                store.findRun(run.key())?.failures?.map { it.code },
                "$code must read back unchanged through findRun",
            )

            val replayed = store.transition(run.key(), transition).run
            assertEquals(
                listOf(code),
                replayed.failures.map { it.code },
                "$code must survive the transition's own replay",
            )
            assertEquals(
                listOf(code),
                store.findRun(run.key())?.failures?.map { it.code },
                "$code must still read back after the replay",
            )
        }
    }

    // ---- bounded, scoped reads ----

    @Test
    fun `a page scopes before it limits`() {
        val store = store()
        (1..5).forEach {
            store.save(running("contract-neighbour-$it", neighbour, startedAt.plusSeconds(100L + it)))
        }
        (1..3).forEach { store.save(running("contract-mine-$it", tenant, startedAt.plusSeconds(it.toLong()))) }

        val page = store.runsInContext(tenant, limit = 2, since = null)

        assertEquals(listOf("contract-mine-3", "contract-mine-2"), page.map { it.ref.runId })
    }

    @Test
    fun `a page is newest first and tie-broken by run id`() {
        val store = store()
        listOf("contract-c", "contract-a", "contract-b").forEach { store.save(running(it)) }

        assertEquals(
            listOf("contract-a", "contract-b", "contract-c"),
            store.runsInContext(tenant, limit = 10, since = null).map { it.ref.runId },
        )
    }

    @Test
    fun `since bounds the window at or after the instant given`() {
        val store = store()
        (0..3).forEach { store.save(running("contract-since-$it", startedAt = startedAt.plusSeconds(it * 10L))) }

        val windowed = store.runsInContext(tenant, limit = 10, since = startedAt.plusSeconds(20))

        assertEquals(
            listOf("contract-since-3", "contract-since-2"),
            windowed.map { it.ref.runId },
        )
    }

    @Test
    fun `every page rejects a limit that is not positive`() {
        val store = store()
        val root = ExtractionRunRef("contract-root")

        listOf(0, -1).forEach { limit ->
            assertThrows(IllegalArgumentException::class.java) {
                store.runsInContext(tenant, limit, null)
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.childrenOf(tenant, root, limit)
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.runsOfRoot(tenant, root, limit, null)
            }
            assertThrows(IllegalArgumentException::class.java) {
                store.ancestorsOf(key("contract-root"), limit)
            }
        }
    }

    // ---- lineage ----

    @Test
    fun `children are one hop down the parent axis`() {
        val store = store()
        val parent = ExtractionRunLineage.root(ExtractionRunRef("contract-parent"))
        val child = ExtractionRunLineage.childOf(ExtractionRunRef("contract-child"), parent)
        val grandchild = ExtractionRunLineage.childOf(ExtractionRunRef("contract-grandchild"), child)
        listOf(parent, child, grandchild).forEach {
            store.save(running(it.runRef.runId, lineage = it))
        }

        assertEquals(
            listOf("contract-child"),
            store.childrenOf(tenant, parent.runRef, limit = 10).map { it.ref.runId },
        )
    }

    @Test
    fun `a whole lineage comes back from its root reference`() {
        val store = store()
        val root = ExtractionRunLineage.root(ExtractionRunRef("contract-root"))
        val one = ExtractionRunLineage.childOf(ExtractionRunRef("contract-pass-1"), root)
        val two = ExtractionRunLineage.childOf(ExtractionRunRef("contract-pass-2"), one)
        val unrelated = ExtractionRunLineage.root(ExtractionRunRef("contract-unrelated"))
        listOf(root, one, two, unrelated).forEachIndexed { index, lineage ->
            store.save(running(lineage.runRef.runId, startedAt = startedAt.plusSeconds(index.toLong()), lineage = lineage))
        }

        assertEquals(
            listOf("contract-pass-2", "contract-pass-1", "contract-root"),
            store.runsOfRoot(tenant, root.runRef, limit = 10, since = null).map { it.ref.runId },
        )
    }

    @Test
    fun `the chain walk is bounded, excludes the run, and stops at an unresolvable parent`() {
        val store = store()
        val root = ExtractionRunLineage.root(ExtractionRunRef("contract-root"))
        val one = ExtractionRunLineage.childOf(ExtractionRunRef("contract-1"), root)
        val two = ExtractionRunLineage.childOf(ExtractionRunRef("contract-2"), one)
        listOf(root, one, two).forEach { store.save(running(it.runRef.runId, lineage = it)) }

        assertEquals(
            listOf("contract-1", "contract-root"),
            store.ancestorsOf(key("contract-2"), limit = 10).map { it.ref.runId },
        )
        assertEquals(
            listOf("contract-1"),
            store.ancestorsOf(key("contract-2"), limit = 1).map { it.ref.runId },
        )
        assertTrue(store.ancestorsOf(key("contract-root"), limit = 10).isEmpty())

        val orphan = ExtractionRunLineage.childOf(
            ExtractionRunRef("contract-orphan"),
            ExtractionRunLineage.root(ExtractionRunRef("contract-missing")),
        )
        store.save(running("contract-orphan", lineage = orphan))
        assertTrue(store.ancestorsOf(key("contract-orphan"), limit = 10).isEmpty())
    }

    @Test
    fun `the chain walk terminates on a cycle`() {
        val store = store()
        val root = ExtractionRunRef("contract-cycle-root")
        val a = ExtractionRunLineage.fromStoredFields(
            runRef = ExtractionRunRef("contract-a"),
            rootRunRef = root,
            parentRunRef = ExtractionRunRef("contract-b"),
        )
        val b = ExtractionRunLineage.fromStoredFields(
            runRef = ExtractionRunRef("contract-b"),
            rootRunRef = root,
            parentRunRef = ExtractionRunRef("contract-a"),
        )
        store.save(running("contract-a", lineage = a))
        store.save(running("contract-b", lineage = b))

        assertEquals(
            listOf("contract-b"),
            store.ancestorsOf(key("contract-a"), limit = 1_000).map { it.ref.runId },
        )
    }

    // ---- tenants ----

    @Test
    fun `two tenants holding the same run id never collide`() {
        val store = store()
        val mine = running("contract-shared", tenant, startedAt)
        val theirs = running("contract-shared", neighbour, startedAt.plusSeconds(60))
        store.save(mine)
        store.save(theirs)

        assertEquals(startedAt, store.findRun(mine.key())?.startedAt)
        assertEquals(startedAt.plusSeconds(60), store.findRun(theirs.key())?.startedAt)

        store.transition(mine.key(), ExtractionRunTransition.completed(finishedAt))

        assertEquals(ExtractionRunStatus.COMPLETED, store.findRun(mine.key())?.status)
        assertEquals(ExtractionRunStatus.RUNNING, store.findRun(theirs.key())?.status)
    }

    @Test
    fun `every read fails closed across tenants`() {
        val store = store()
        val root = ExtractionRunLineage.root(ExtractionRunRef("contract-root"))
        val child = ExtractionRunLineage.childOf(ExtractionRunRef("contract-child"), root)
        listOf(root, child).forEach {
            store.save(running(it.runRef.runId, neighbour, lineage = it))
        }

        assertNull(store.findRun(key("contract-child", tenant)))
        assertTrue(store.invocationsOf(key("contract-child", tenant)).isEmpty())
        assertTrue(store.runsInContext(tenant, 10, null).isEmpty())
        assertTrue(store.childrenOf(tenant, root.runRef, 10).isEmpty())
        assertTrue(store.runsOfRoot(tenant, root.runRef, 10, null).isEmpty())
        assertTrue(store.ancestorsOf(key("contract-child", tenant), 10).isEmpty())
    }

    @Test
    fun `a chain walk stops rather than crossing into another tenant`() {
        val store = store()
        val parent = ExtractionRunLineage.root(ExtractionRunRef("contract-parent"))
        val child = ExtractionRunLineage.childOf(ExtractionRunRef("contract-child"), parent)
        store.save(running("contract-parent", neighbour, lineage = parent))
        store.save(running("contract-child", tenant, lineage = child))

        assertTrue(store.ancestorsOf(key("contract-child", tenant), 10).isEmpty())
    }
}

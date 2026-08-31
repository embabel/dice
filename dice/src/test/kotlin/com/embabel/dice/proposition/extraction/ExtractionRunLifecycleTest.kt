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
package com.embabel.dice.proposition.extraction

import com.embabel.dice.proposition.extraction.ExtractionRunFixtures.CONTEXT
import com.embabel.dice.proposition.extraction.ExtractionRunFixtures.FINISHED_AT
import com.embabel.dice.proposition.extraction.ExtractionRunFixtures.OTHER_CONTEXT
import com.embabel.dice.proposition.extraction.ExtractionRunFixtures.STARTED_AT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Every edge of the run lifecycle, and the two rules that make the terminal ones safe: only
 * compare-and-set writes them, and a repeat of one is decided by comparing fingerprints rather than
 * by overwriting.
 */
class ExtractionRunLifecycleTest {

    private val store = InMemoryExtractionRunStore()

    private fun started(runId: String = "run-1") = ExtractionRunFixtures.runningRun(runId)

    // ---- the three legal edges ----

    @Test
    fun `a running run may end completed, failed or cancelled`() {
        val edges = listOf(
            ExtractionRunStatus.COMPLETED to ExtractionRunTransition.completed(FINISHED_AT),
            ExtractionRunStatus.FAILED to ExtractionRunTransition.failed(FINISHED_AT),
            ExtractionRunStatus.CANCELLED to ExtractionRunTransition.cancelled(FINISHED_AT),
        )

        edges.forEachIndexed { index, (expected, transition) ->
            val run = started("run-edge-$index")
            store.save(run)

            val result = store.transition(run.key(), transition)

            assertThat(result.outcome)
                .describedAs("RUNNING to %s", expected)
                .isEqualTo(ExtractionRunTransitionOutcome.APPLIED)
            assertThat(result.isApplied).isTrue()
            assertThat(result.run.status).isEqualTo(expected)
            assertThat(result.run.finishedAt).isEqualTo(FINISHED_AT)
            assertThat(store.findRun(run.key())?.status).isEqualTo(expected)
        }
    }

    @Test
    fun `a transition must be terminal`() {
        // RUNNING to RUNNING is not an edge, and the transition type will not hold the value.
        assertThatIllegalArgumentException()
            .isThrownBy { ExtractionRunTransition(ExtractionRunStatus.RUNNING, FINISHED_AT) }
            .withMessageContaining("must be terminal")
    }

    // ---- no edge leaves a terminal state ----

    @Test
    fun `a run that has ended cannot end again as something else`() {
        val terminals = listOf(
            ExtractionRunTransition.completed(FINISHED_AT),
            ExtractionRunTransition.failed(FINISHED_AT),
            ExtractionRunTransition.cancelled(FINISHED_AT),
        )

        terminals.forEachIndexed { index, first ->
            val run = started("run-terminal-$index")
            store.save(run)
            store.transition(run.key(), first)

            terminals.filter { it.status != first.status }.forEach { second ->
                assertThatThrownBy { store.transition(run.key(), second) }
                    .describedAs("%s then %s", first.status, second.status)
                    .isInstanceOf(ExtractionRunConflictException::class.java)
                    .hasMessageContaining("already ended as ${first.status}")
            }

            assertThat(store.findRun(run.key())?.status).isEqualTo(first.status)
        }
    }

    @Test
    fun `a terminal run cannot be re-opened by a save`() {
        val run = started()
        store.save(run)
        store.transition(run.key(), ExtractionRunTransition.completed(FINISHED_AT))

        // The same running header that was legal a moment ago is now a rewrite of a finished
        // record, which is the write the design forbids MERGE from doing quietly.
        assertThatThrownBy { store.save(run) }
            .isInstanceOf(ExtractionRunConflictException::class.java)
            .hasMessageContaining("cannot be re-opened")

        assertThat(store.findRun(run.key())?.status).isEqualTo(ExtractionRunStatus.COMPLETED)
    }

    @Test
    fun `a terminal run takes no more invocation records`() {
        val run = started()
        store.save(run)
        store.transition(run.key(), ExtractionRunTransition.failed(FINISHED_AT))

        assertThatThrownBy {
            store.recordInvocation(run.key(), ExtractionInvocationRecord.planned(0))
        }
            .isInstanceOf(ExtractionRunConflictException::class.java)
            .hasMessageContaining("part of how it ended")
    }

    // ---- idempotent replay ----

    @Test
    fun `the same terminal write replays as success and changes nothing`() {
        val run = started()
        store.save(run)
        val transition = ExtractionRunTransition.completed(
            finishedAt = FINISHED_AT,
            counts = ExtractionRunCounts(propositionsPersisted = 11),
        )

        val first = store.transition(run.key(), transition)
        val second = store.transition(run.key(), transition)
        // A retry that rebuilt the payload rather than keeping the object still replays: the
        // comparison is on the fingerprint, not on identity.
        val third = store.transition(
            run.key(),
            ExtractionRunTransition.completed(
                finishedAt = FINISHED_AT,
                counts = ExtractionRunCounts(propositionsPersisted = 11),
            ),
        )

        assertThat(first.outcome).isEqualTo(ExtractionRunTransitionOutcome.APPLIED)
        assertThat(second.outcome).isEqualTo(ExtractionRunTransitionOutcome.REPLAYED)
        assertThat(third.outcome).isEqualTo(ExtractionRunTransitionOutcome.REPLAYED)
        assertThat(second.run).isEqualTo(first.run)
        assertThat(third.run).isEqualTo(first.run)
    }

    @Test
    fun `a replay after another invocation was recorded is still a replay`() {
        val run = started()
        store.save(run)
        store.recordInvocation(run.key(), ExtractionInvocationRecord.planned(0))
        val transition = ExtractionRunTransition.completed(FINISHED_AT)
        store.transition(run.key(), transition)

        // The fingerprint covers the terminal write, not the run. A coordinator that recorded
        // another attempt between a terminal write it never saw the answer to and its retry made
        // the same terminal write both times.
        assertThat(store.transition(run.key(), transition).outcome)
            .isEqualTo(ExtractionRunTransitionOutcome.REPLAYED)
    }

    // ---- incompatible rewrite ----

    @Test
    fun `an incompatible terminal rewrite is rejected rather than overwriting`() {
        val incompatible = listOf(
            "a different status" to ExtractionRunTransition.failed(FINISHED_AT),
            "a different finish time" to ExtractionRunTransition.completed(FINISHED_AT.plusSeconds(1)),
            "different counts" to ExtractionRunTransition.completed(
                finishedAt = FINISHED_AT,
                counts = ExtractionRunCounts(propositionsPersisted = 12),
            ),
            "a different failure list" to ExtractionRunTransition.completed(
                finishedAt = FINISHED_AT,
                failures = listOf(ExtractionFailure(ExtractionFailureCode.RATE_LIMITED)),
            ),
        )
        val recorded = ExtractionRunTransition.completed(
            finishedAt = FINISHED_AT,
            counts = ExtractionRunCounts(propositionsPersisted = 11),
        )

        incompatible.forEachIndexed { index, (name, rewrite) ->
            val run = started("run-rewrite-$index")
            store.save(run)
            store.transition(run.key(), recorded)

            assertThatThrownBy { store.transition(run.key(), rewrite) }
                .describedAs(name)
                .isInstanceOf(ExtractionRunConflictException::class.java)

            assertThat(store.findRun(run.key()))
                .describedAs("%s left the recorded terminal write in place", name)
                .isEqualTo(recorded.applyTo(started("run-rewrite-$index")))
        }
    }

    @Test
    fun `keeping counts and replacing them with the same values are the same terminal write`() {
        val run = ExtractionRunFixtures.runningRun(
            lineage = ExtractionRunLineage.root(ExtractionRunRef("run-counts")),
            counts = ExtractionRunCounts(propositionsPersisted = 4),
        )
        store.save(run)
        store.transition(run.key(), ExtractionRunTransition.completed(FINISHED_AT, counts = null))

        // Null means keep and a value means replace, so they are different claims even when they
        // land on the same numbers. Rejecting the second is the honest answer: the store cannot
        // tell a caller who meant "leave them" from one who meant "these are final".
        assertThatThrownBy {
            store.transition(
                run.key(),
                ExtractionRunTransition.completed(
                    FINISHED_AT,
                    counts = ExtractionRunCounts(propositionsPersisted = 4),
                ),
            )
        }.isInstanceOf(ExtractionRunConflictException::class.java)
    }

    // ---- nothing reaches COMPLETED except through the transition ----

    @Test
    fun `a terminal run cannot be saved`() {
        ExtractionRunStatus.entries.filter { it.isTerminal }.forEach { status ->
            val terminal = ExtractionRun(
                contextId = CONTEXT,
                lineage = ExtractionRunLineage.root(ExtractionRunRef("run-direct-$status")),
                status = status,
                startedAt = STARTED_AT,
                finishedAt = FINISHED_AT,
            )

            assertThatIllegalArgumentException()
                .describedAs("saving a %s run", status)
                .isThrownBy { store.save(terminal) }
                .withMessageContaining("belongs to transition()")
        }
    }

    @Test
    fun `nothing can complete a run the store has never seen`() {
        // COMPLETED asserts every requested product is persisted or disposed. A run nobody started
        // has no request behind it, so the claim has nothing to be true of.
        assertThatThrownBy {
            store.transition(
                ExtractionRunFixtures.keyOf("run-never-started"),
                ExtractionRunTransition.completed(FINISHED_AT),
            )
        }.isInstanceOf(ExtractionRunNotFoundException::class.java)

        // Read back through the contract, which is the only way in: the store publishes no
        // unscoped "everything" method, because one instance holds every tenant's runs.
        assertThat(store.findRun(ExtractionRunFixtures.keyOf("run-never-started"))).isNull()
        assertThat(store.runsInContext(CONTEXT, 10, null)).isEmpty()
        assertThat(store.runsInContext(OTHER_CONTEXT, 10, null)).isEmpty()
    }

    @Test
    fun `the store publishes no unscoped read`() {
        // One instance holds every tenant's runs, so an "everything in the store" method would be a
        // cross-tenant unbounded read on a contract that is neither — and a host running the
        // shipped in-memory backend would have one.
        val unscoped = InMemoryExtractionRunStore::class.java.methods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .filter { List::class.java.isAssignableFrom(it.returnType) }
            .filter { method -> method.parameterCount == 0 }
            .map { it.name }

        assertThat(unscoped).isEmpty()
    }

    @Test
    fun `the only writer of a terminal status is the transition`() {
        // Structural, not narrative: save rejects every terminal status, so the sole path from
        // RUNNING to COMPLETED is transition(), whose precondition is written on the contract.
        val writers = ExtractionRunStore::class.java.methods
            .filter { it.name == "save" || it.name == "recordInvocation" || it.name == "transition" }
            .map { it.name }
            .toSet()

        assertThat(writers).containsExactlyInAnyOrder("save", "recordInvocation", "transition")

        val run = started("run-sole-writer")
        store.save(run)
        assertThat(store.findRun(run.key())?.status).isEqualTo(ExtractionRunStatus.RUNNING)
        store.recordInvocation(run.key(), ExtractionInvocationRecord.planned(0))
        assertThat(store.findRun(run.key())?.status).isEqualTo(ExtractionRunStatus.RUNNING)
        store.transition(run.key(), ExtractionRunTransition.completed(FINISHED_AT))
        assertThat(store.findRun(run.key())?.status).isEqualTo(ExtractionRunStatus.COMPLETED)
    }

    // ---- the empty run ----

    @Test
    fun `an empty run completes vacuously`() {
        val run = started("run-empty")
        store.save(run)

        val result = store.transition(run.key(), ExtractionRunTransition.completed(FINISHED_AT))

        assertThat(result.run.status).isEqualTo(ExtractionRunStatus.COMPLETED)
        assertThat(result.run.invocations).isEmpty()
        assertThat(result.run.failures).isEmpty()
        assertThat(result.run.counts).isEqualTo(ExtractionRunCounts())
        assertThat(store.invocationsOf(run.key())).isEmpty()
    }

    // ---- the running state stays retryable ----

    @Test
    fun `a run stays running and re-savable until something ends it`() {
        val run = started("run-partial")
        store.save(run)

        // Partial success: products persisted, more outstanding. The header is updated and the run
        // is still RUNNING, so the commit that completes coverage can still terminalize it.
        val progressed = ExtractionRunFixtures.runningRun(
            lineage = run.lineage,
            counts = ExtractionRunCounts(propositionsExtracted = 9, propositionsPersisted = 4),
        )
        store.save(progressed)

        assertThat(store.findRun(run.key())?.status).isEqualTo(ExtractionRunStatus.RUNNING)
        assertThat(store.findRun(run.key())?.counts?.propositionsPersisted).isEqualTo(4)

        val result = store.transition(
            run.key(),
            ExtractionRunTransition.completed(
                FINISHED_AT,
                counts = ExtractionRunCounts(propositionsExtracted = 9, propositionsPersisted = 9),
            ),
        )
        assertThat(result.outcome).isEqualTo(ExtractionRunTransitionOutcome.APPLIED)
        assertThat(result.run.counts.propositionsPersisted).isEqualTo(9)
    }

    @Test
    fun `a save cannot change what fixes a run's identity`() {
        val run = started("run-identity")
        store.save(run)

        val differentStart = ExtractionRunFixtures.runningRun(
            lineage = run.lineage,
            startedAt = STARTED_AT.plusSeconds(30),
        )
        assertThatThrownBy { store.save(differentStart) }
            .isInstanceOf(ExtractionRunConflictException::class.java)
            .hasMessageContaining("a run starts once")

        val differentLineage = ExtractionRunFixtures.runningRun(
            lineage = ExtractionRunLineage.root(
                runRef = ExtractionRunRef("run-identity"),
                supersedesRunRef = ExtractionRunRef("run-something-else"),
            ),
        )
        assertThatThrownBy { store.save(differentLineage) }
            .isInstanceOf(ExtractionRunConflictException::class.java)
            .hasMessageContaining("lineage is fixed at insert")

        assertThat(store.findRun(run.key())).isEqualTo(run)
    }

    @Test
    fun `re-saving the same running run is idempotent`() {
        val run = started("run-resave")
        store.save(run)
        store.save(run)

        assertThat(store.runsInContext(CONTEXT, 10, null)).containsExactly(run)
    }

    @Test
    fun `a running run carrying a finish time is rejected`() {
        // ExtractionRun leaves status-and-timing pairing to the state machine, which is here. A
        // record that reads as running and as finished at once makes every page and audit meeting
        // it guess which.
        val finishedWhileRunning = ExtractionRun(
            contextId = CONTEXT,
            lineage = ExtractionRunLineage.root(ExtractionRunRef("run-finished-running")),
            status = ExtractionRunStatus.RUNNING,
            startedAt = STARTED_AT,
            finishedAt = FINISHED_AT,
        )

        assertThatIllegalArgumentException()
            .isThrownBy { store.save(finishedWhileRunning) }
            .withMessageContaining("carries no finishedAt")

        assertThat(store.findRun(finishedWhileRunning.key())).isNull()
    }

    @Test
    fun `a header save never deletes a recorded invocation`() {
        val run = started("run-child-rows")
        store.save(run)
        // A caller loads the run, an attempt is recorded, and the caller then saves its own copy
        // with updated counts. The attempt it never saw must survive — save owns header fields
        // only and never reaches into invocation rows at all.
        store.recordInvocation(run.key(), ExtractionInvocationRecord.planned(0))
        val staleWithCounts = ExtractionRunFixtures.runningRun(
            lineage = run.lineage,
            counts = ExtractionRunCounts(propositionsExtracted = 5),
        )

        val saved = store.save(staleWithCounts)

        assertThat(saved.counts.propositionsExtracted).isEqualTo(5)
        assertThat(store.invocationsOf(run.key()).map { it.id })
            .containsExactly(ExtractionInvocationId(0, 1))
    }

    @Test
    fun `a header save embedding a record for an identity already stored never touches it`() {
        // recordInvocation is the only door onto invocation rows. A save's own invocations field is
        // not written anywhere, whether it names an id already stored, a brand-new one, or both —
        // the row recordInvocation wrote stays exactly as recordInvocation left it.
        val run = started("run-child-save-ignored")
        store.save(run)
        store.recordInvocation(
            run.key(),
            ExtractionInvocationRecord(
                id = ExtractionInvocationId.planned(0),
                configuredService = "service-alpha",
            ),
        )

        store.save(
            ExtractionRunFixtures.runningRun(
                lineage = run.lineage,
                invocations = listOf(
                    ExtractionInvocationRecord(
                        id = ExtractionInvocationId.planned(0),
                        outcome = ExtractionInvocationOutcome.SUCCEEDED,
                        configuredService = "service-beta",
                    ),
                    ExtractionInvocationRecord.planned(1),
                ),
            ),
        )

        val stored = store.invocationsOf(run.key())
        assertThat(stored.map { it.id }).containsExactly(ExtractionInvocationId(0, 1))
        assertThat(stored.first().configuredService).isEqualTo("service-alpha")
    }

    // ---- what the transition derives, and what it carries across ----

    @Test
    fun `a transition carries every component it does not own across unchanged`() {
        val populated = ExtractionRunFixtures.populatedRun()
        // populatedRun() is FAILED; rebuild it as the running run it was a moment before.
        val running = ExtractionRun(
            contextId = populated.contextId,
            lineage = populated.lineage,
            status = ExtractionRunStatus.RUNNING,
            startedAt = populated.startedAt,
            profile = populated.profile,
            sourceRevisions = populated.sourceRevisions,
            fingerprints = populated.fingerprints,
            runtime = populated.runtime,
            requestedModel = populated.requestedModel,
            subjectRefs = populated.subjectRefs,
            experimentRef = populated.experimentRef,
            cohortRef = populated.cohortRef,
            replayFidelity = populated.replayFidelity,
            counts = populated.counts,
            invocations = populated.invocations,
            failures = populated.failures,
            version = 7,
        )

        val terminal = ExtractionRunTransition.cancelled(FINISHED_AT).applyTo(running)

        assertThat(terminal.status).isEqualTo(ExtractionRunStatus.CANCELLED)
        assertThat(terminal.finishedAt).isEqualTo(FINISHED_AT)
        // Everything else is the run it was applied to, component by component.
        assertThat(terminal.contextId).isEqualTo(running.contextId)
        assertThat(terminal.lineage).isEqualTo(running.lineage)
        assertThat(terminal.startedAt).isEqualTo(running.startedAt)
        assertThat(terminal.profile).isEqualTo(running.profile)
        assertThat(terminal.sourceRevisions).isEqualTo(running.sourceRevisions)
        assertThat(terminal.fingerprints).isEqualTo(running.fingerprints)
        assertThat(terminal.runtime).isEqualTo(running.runtime)
        assertThat(terminal.requestedModel).isEqualTo(running.requestedModel)
        assertThat(terminal.subjectRefs).isEqualTo(running.subjectRefs)
        assertThat(terminal.experimentRef).isEqualTo(running.experimentRef)
        assertThat(terminal.cohortRef).isEqualTo(running.cohortRef)
        assertThat(terminal.replayFidelity).isEqualTo(running.replayFidelity)
        assertThat(terminal.counts).isEqualTo(running.counts)
        assertThat(terminal.invocations).isEqualTo(running.invocations)
        assertThat(terminal.failures).isEqualTo(running.failures)
        assertThat(terminal.version).isEqualTo(running.version)
    }

    @Test
    fun `null keeps what the run recorded and a value replaces it`() {
        val accumulated = listOf(
            ExtractionFailure(ExtractionFailureCode.RATE_LIMITED, ExtractionFailureStage.MODEL_CALL),
        )
        val running = ExtractionRun(
            contextId = CONTEXT,
            lineage = ExtractionRunLineage.root(ExtractionRunRef("run-keep")),
            status = ExtractionRunStatus.RUNNING,
            startedAt = STARTED_AT,
            counts = ExtractionRunCounts(propositionsExtracted = 3),
            failures = accumulated,
        )

        val kept = ExtractionRunTransition.completed(FINISHED_AT).applyTo(running)
        assertThat(kept.counts.propositionsExtracted).isEqualTo(3)
        assertThat(kept.failures).isEqualTo(accumulated)

        val replaced = ExtractionRunTransition.completed(
            finishedAt = FINISHED_AT,
            counts = ExtractionRunCounts(propositionsExtracted = 7),
            failures = emptyList(),
        ).applyTo(running)
        assertThat(replaced.counts.propositionsExtracted).isEqualTo(7)
        assertThat(replaced.failures).isEmpty()
    }

    @Test
    fun `a transition refuses a run that has already ended and a finish before its start`() {
        val running = ExtractionRunFixtures.startedRun()

        assertThatIllegalArgumentException()
            .isThrownBy {
                ExtractionRunTransition.completed(STARTED_AT.minusSeconds(1)).applyTo(running)
            }
            .withMessageContaining("must not be before")

        val terminal = ExtractionRunTransition.failed(FINISHED_AT).applyTo(running)
        assertThatIllegalArgumentException()
            .isThrownBy { ExtractionRunTransition.cancelled(FINISHED_AT).applyTo(terminal) }
            .withMessageContaining("has already ended")
    }

    @Test
    fun `a transition is bounded by the same failure cap as the run`() {
        val over = (0..ExtractionRunLimits.MAX_FAILURES)
            .map { ExtractionFailure(ExtractionFailureCode.INTERNAL) }

        assertThatIllegalArgumentException()
            .isThrownBy { ExtractionRunTransition.failed(FINISHED_AT, failures = over) }
            .withMessageContaining("at most ${ExtractionRunLimits.MAX_FAILURES} failures")
    }

    @Test
    fun `a failed run need not say why, and a completed run may still carry failures`() {
        val silent = started("run-silent")
        store.save(silent)
        val silentResult = store.transition(silent.key(), ExtractionRunTransition.failed(FINISHED_AT))
        assertThat(silentResult.run.failures).isEmpty()

        val recovered = started("run-recovered")
        store.save(recovered)
        val recoveredResult = store.transition(
            recovered.key(),
            ExtractionRunTransition.completed(
                finishedAt = FINISHED_AT,
                failures = listOf(
                    ExtractionFailure(ExtractionFailureCode.RATE_LIMITED, ExtractionFailureStage.MODEL_CALL),
                ),
            ),
        )
        assertThat(recoveredResult.run.status).isEqualTo(ExtractionRunStatus.COMPLETED)
        assertThat(recoveredResult.run.failures).hasSize(1)
    }

    // ---- tenants ----

    @Test
    fun `two tenants running the same run id transition independently`() {
        val mine = ExtractionRunFixtures.runningRun("run-shared", CONTEXT)
        val theirs = ExtractionRunFixtures.runningRun("run-shared", OTHER_CONTEXT)
        store.save(mine)
        store.save(theirs)

        store.transition(mine.key(), ExtractionRunTransition.completed(FINISHED_AT))

        assertThat(store.findRun(mine.key())?.status).isEqualTo(ExtractionRunStatus.COMPLETED)
        assertThat(store.findRun(theirs.key())?.status).isEqualTo(ExtractionRunStatus.RUNNING)

        // The neighbour's run is still running, so its own terminal write applies rather than
        // colliding with the one already recorded under the same run id.
        assertThat(store.transition(theirs.key(), ExtractionRunTransition.failed(FINISHED_AT)).outcome)
            .isEqualTo(ExtractionRunTransitionOutcome.APPLIED)
    }
}

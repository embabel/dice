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

import org.jetbrains.annotations.ApiStatus
import java.time.Instant
import java.util.Collections

/**
 * One terminal write against a run: the state it ends in, when it ended, and the counts and
 * failures that go with it.
 *
 * This type is how a run becomes a different run. [ExtractionRun] publishes no `withStatus`, no
 * `finished()` and no `copy`, so [applyTo] is the only place a terminal run is derived. A store
 * applies it under compare-and-set; nothing else may.
 *
 * **Null means keep, a value means replace.** [counts] and [failures] are both nullable and both
 * follow the same rule, so a coordinator that only knows the run stopped can say that and nothing
 * more. An empty [failures] list is a value: it replaces whatever the run accumulated with none.
 *
 * **A `FAILED` transition need not carry a failure.** A run can stop on something the coordinator
 * classifies at the run level with no per-attempt detail, and requiring the pairing would make an
 * honest "we know it failed and not why" unrecordable. A `COMPLETED` transition may carry failures
 * for the same reason from the other side: a run that retried past a failed attempt and finished
 * still happened.
 *
 * **[fingerprint] names the write, and the counts and failures ride beside it.** A store compares a
 * repeated terminal write against the digest of [status] and [finishedAt] alone. Two transitions
 * that agree on those are the same write, so the second replays and the run keeps the counts and
 * failures the first one delivered — a run's outcome is written once. See
 * [ExtractionRunFingerprint] for the encoding, and for why a payload that grows leaves the digest
 * where it is.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property status The terminal state the run ends in
 * @property finishedAt When the run reached it
 * @property counts The run's final counts, or null to keep what the run already recorded
 * @property failures The run's final failures, or null to keep what the run already recorded
 */
@ApiStatus.Experimental
class ExtractionRunTransition @JvmOverloads constructor(
    val status: ExtractionRunStatus,
    val finishedAt: Instant,
    val counts: ExtractionRunCounts? = null,
    failures: List<ExtractionFailure>? = null,
) {

    /** The run's final failures, or null to keep what the run already recorded. */
    val failures: List<ExtractionFailure>? =
        failures?.let { Collections.unmodifiableList(ArrayList(it)) }

    init {
        require(status.isTerminal) {
            "a transition ends a run, so its status must be terminal; $status is not"
        }
        val declared = this.failures
        if (declared != null) {
            require(declared.size <= ExtractionRunLimits.MAX_FAILURES) {
                "a run may record at most ${ExtractionRunLimits.MAX_FAILURES} failures, " +
                    "was ${declared.size}"
            }
        }
    }

    /**
     * The digest a store compares a repeated terminal write against, computed once at construction.
     *
     * It covers this transition's identity — [status] and [finishedAt] — and nothing else. See
     * [ExtractionRunFingerprint] for why [counts] and [failures] travel as data beside it.
     */
    val fingerprint: String = ExtractionRunFingerprint.ofTerminal(status, finishedAt)

    /**
     * Derives the terminal run this transition produces from the running one it is applied to.
     *
     * Everything the transition does not own is carried across unchanged: lineage, tenant, start
     * time, profile, source revisions, digests, runtime identity, requested configuration, subject
     * references, experiment and cohort labels, replay fidelity, the invocation records the run
     * accumulated, and its header version.
     *
     * @param run The run to terminalize. Must be [ExtractionRunStatus.RUNNING].
     * @return The same run in its terminal state.
     * @throws IllegalArgumentException if [run] is already terminal, or if [finishedAt] precedes
     *   its start, or if the resulting run breaks one of [ExtractionRun]'s own invariants — a
     *   failure naming an invocation the run has no record of, most often.
     */
    fun applyTo(run: ExtractionRun): ExtractionRun {
        require(run.status == ExtractionRunStatus.RUNNING) {
            "a run in ${run.status} has already ended and cannot be transitioned to $status"
        }
        require(!finishedAt.isBefore(run.startedAt)) {
            "finishedAt must not be before the run's startedAt"
        }
        return ExtractionRun(
            contextId = run.contextId,
            lineage = run.lineage,
            status = status,
            startedAt = run.startedAt,
            finishedAt = finishedAt,
            profile = run.profile,
            sourceRevisions = run.sourceRevisions,
            fingerprints = run.fingerprints,
            runtime = run.runtime,
            requestedModel = run.requestedModel,
            subjectRefs = run.subjectRefs,
            experimentRef = run.experimentRef,
            cohortRef = run.cohortRef,
            replayFidelity = run.replayFidelity,
            counts = counts ?: run.counts,
            invocations = run.invocations,
            failures = failures ?: run.failures,
            version = run.version,
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExtractionRunTransition) return false
        return status == other.status &&
            finishedAt == other.finishedAt &&
            counts == other.counts &&
            failures == other.failures
    }

    override fun hashCode(): Int {
        var result = status.hashCode()
        result = 31 * result + finishedAt.hashCode()
        result = 31 * result + (counts?.hashCode() ?: 0)
        result = 31 * result + (failures?.hashCode() ?: 0)
        return result
    }

    /** Identity and sizes. Failure details stay out of it, as they do on the run. */
    override fun toString(): String =
        "ExtractionRunTransition(status=$status, finishedAt=$finishedAt, " +
            "counts=${if (counts == null) "kept" else "replaced"}, " +
            "failures=${failures?.size ?: "kept"}, fingerprint=${fingerprint.take(12)}…)"

    companion object {

        /**
         * The run finished everything it was asked for: every product its request called for is
         * durably persisted or terminally disposed.
         *
         * Only call this after persistence has returned. On the legacy path that is the coordinator
         * once `persistAndProject` returns; on the #68 path it is inside the commit transaction. A
         * run whose persistence never finished stays running and retryable, which is the whole
         * reason the state exists.
         *
         * A run with zero products takes this too: there was nothing to persist, so the coverage
         * claim holds vacuously.
         */
        @JvmStatic
        @JvmOverloads
        fun completed(
            finishedAt: Instant,
            counts: ExtractionRunCounts? = null,
            failures: List<ExtractionFailure>? = null,
        ): ExtractionRunTransition =
            ExtractionRunTransition(ExtractionRunStatus.COMPLETED, finishedAt, counts, failures)

        /** The run stopped on an error it could not get past. */
        @JvmStatic
        @JvmOverloads
        fun failed(
            finishedAt: Instant,
            counts: ExtractionRunCounts? = null,
            failures: List<ExtractionFailure>? = null,
        ): ExtractionRunTransition =
            ExtractionRunTransition(ExtractionRunStatus.FAILED, finishedAt, counts, failures)

        /**
         * The run stopped before finishing, by request or by external termination.
         *
         * This is also the abandonment path for a partially successful run nobody intends to
         * finish. Its outstanding products stay outstanding behind it and recovery goes through a
         * new run linked by parent or superseded reference.
         */
        @JvmStatic
        @JvmOverloads
        fun cancelled(
            finishedAt: Instant,
            counts: ExtractionRunCounts? = null,
            failures: List<ExtractionFailure>? = null,
        ): ExtractionRunTransition =
            ExtractionRunTransition(ExtractionRunStatus.CANCELLED, finishedAt, counts, failures)
    }
}

/**
 * Whether a terminal write did the work or found it already done.
 *
 * Both are success. A caller retrying after a timeout it never saw the answer to gets
 * [REPLAYED] and can carry on; the distinction is there for metrics and for a coordinator that
 * wants to log the difference.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 */
@ApiStatus.Experimental
enum class ExtractionRunTransitionOutcome {

    /** The run was running, and this call ended it. */
    APPLIED,

    /**
     * The run had already ended under a terminal write with the same fingerprint, and this call
     * changed nothing.
     */
    REPLAYED,
}

/**
 * What a terminal write returned: the run in its terminal state, and whether this call is what put
 * it there.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property run The run, terminal
 * @property outcome Whether this call applied the transition or replayed one already recorded
 */
@ApiStatus.Experimental
data class ExtractionRunTransitionResult(
    val run: ExtractionRun,
    val outcome: ExtractionRunTransitionOutcome,
) {

    /** True when this call ended the run rather than finding it already ended. */
    val isApplied: Boolean
        get() = outcome == ExtractionRunTransitionOutcome.APPLIED
}

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

import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.common.ExtractionRunTransitioned
import org.jetbrains.annotations.ApiStatus
import java.time.Instant

/**
 * Reference [ExtractionRunStore] that keeps runs in a map.
 *
 * It is the executable statement of what the contract means, so a durable backend can be held to
 * the same suite of tests. It also lets a host record and read runs before it has a database, which
 * is most of what the first tier of run lineage is for.
 *
 * **Compare-and-set is real here, not simulated.** Every write and every read runs inside one
 * monitor, so the read of a run's status and the write that changes it cannot interleave with
 * another thread's. A durable store gets the same guarantee from its transaction. A reference
 * implementation that read and then wrote without holding a lock would pass every single-threaded
 * test and lie about the property the contract is named for.
 *
 * **A header save is itself compare-and-set, on [ExtractionRun.version] — and it owns header
 * fields only.** Two writers can hold the same running header at once, and whichever saves second
 * must not silently put the other's write back the way it looked before. `save` accepts a write
 * only when the version it names matches what is stored; a stale writer is told so, in an
 * [ExtractionRunConflictException], and has to read the run again and rebuild its save on what it
 * holds now. See `save`'s own KDoc for why this replaced an earlier, field-by-field merge of the
 * running header. Whatever [run]'s `invocations` field carries plays no part in that comparison or
 * in what gets stored — see `save`'s own KDoc for why.
 *
 * **`recordInvocation` is the only door onto invocation rows, and each row keeps its own
 * concurrency control.** Two attempts never contend on the header's version, because they are not
 * writing the header; each is decided against the row already stored under its own
 * `(invocationIndex, attempt)` key.
 *
 * **Scope is applied before the limit.** Each page filters to the tenant, then orders, then takes
 * the limit. That order is the whole point of the contract's rule, so the reference implementation
 * does it in the order a query would rather than filtering a truncated list.
 *
 * **There is no unscoped read, not even for tests.** One instance holds every tenant's runs, so a
 * public "everything in the store" method would be a cross-tenant, unbounded read on a store whose
 * contract is neither — and a host running the shipped in-memory backend would have one. The tests
 * read through the contract like any other caller.
 *
 * **A run that ends announces itself once.** `transition` hands an [ExtractionRunTransitioned] to
 * [listener] for the call that ended the run, after the write has landed and outside the monitor,
 * so a slow listener holds up no other writer. A replay and a rejected write announce nothing.
 *
 * Nothing here survives the JVM, and two instances know nothing about each other.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property listener Notified when a run ends. Defaults to [DiceEventListener.DEV_NULL], so a host
 *   that has nothing listening constructs the store the same way it always did. Handlers run inline
 *   on the calling thread, and throw isolation belongs to the listener — wrap it in
 *   `SafeDiceEventListener` for graceful degradation.
 */
@ApiStatus.Experimental
class InMemoryExtractionRunStore @JvmOverloads constructor(
    private val listener: DiceEventListener = DiceEventListener.DEV_NULL,
) : ExtractionRunStore {

    private val lock = Any()

    private val runs = LinkedHashMap<ExtractionRunKey, ExtractionRun>()

    /** The fingerprint of the terminal write that ended each run, for comparing a retry against. */
    private val terminalWrites = HashMap<ExtractionRunKey, String>()

    override fun save(run: ExtractionRun): ExtractionRun {
        require(run.status == ExtractionRunStatus.RUNNING) {
            "save records a running run; ${run.status} is terminal and belongs to transition()"
        }
        // ExtractionRun leaves status-and-timing pairing to the state machine, which is here. A
        // running run with a finish time is a record that reads as running and as finished at once,
        // and every page and audit that meets it has to guess which.
        require(run.finishedAt == null) {
            "a running run has not finished, so it carries no finishedAt"
        }
        val key = run.key()
        synchronized(lock) {
            val stored = runs[key]
            if (stored == null) {
                require(run.version == 0L) {
                    "a run's first save must name version 0, the version a run nobody has saved " +
                        "yet carries; this one names ${run.version}"
                }
                // The insert is header-only, the same as every later save: whatever `run.invocations`
                // carries is not this door's to write. A run has no invocation rows before it exists,
                // so there is nothing to preserve here, but the rule reads the same either way — this
                // door never originates a row.
                val inserted = rebuild(run, invocations = emptyList(), version = 0L)
                runs[key] = inserted
                return inserted
            }
            if (stored.status.isTerminal) {
                throw ExtractionRunConflictException(
                    key,
                    "already ended as ${stored.status} and cannot be re-opened by a save",
                )
            }
            if (stored.lineage != run.lineage) {
                throw ExtractionRunConflictException(
                    key,
                    "stored lineage differs from the one being saved; lineage is fixed at insert",
                )
            }
            if (stored.startedAt != run.startedAt) {
                throw ExtractionRunConflictException(
                    key,
                    "stored startedAt differs from the one being saved; a run starts once",
                )
            }
            // The stored invocation rows are carried through untouched, regardless of what
            // `run.invocations` holds — recordInvocation is the only door onto that state.
            // Building the no-op candidate at the stored invocations and the stored version turns a
            // byte-identical header resend into a check against itself, so a retry that never
            // learned its first attempt landed does not get an avoidable conflict.
            val candidate = rebuild(run, invocations = stored.invocations, version = stored.version)
            if (candidate == stored) {
                return stored
            }
            if (run.version != stored.version) {
                throw ExtractionRunConflictException(
                    key,
                    "the header was read at version ${run.version}; the store is now at " +
                        "${stored.version}. Read the run again with findRun and rebuild this save " +
                        "on what it holds now",
                )
            }
            val updated = rebuild(run, invocations = stored.invocations, version = stored.version + 1)
            runs[key] = updated
            return updated
        }
    }

    override fun recordInvocation(
        key: ExtractionRunKey,
        record: ExtractionInvocationRecord,
    ): ExtractionRun {
        synchronized(lock) {
            val stored = runs[key] ?: throw ExtractionRunNotFoundException(key)
            if (stored.status.isTerminal) {
                throw ExtractionRunConflictException(
                    key,
                    "already ended as ${stored.status}; its invocation records are part of how it ended",
                )
            }
            val invocations = applyInvocationWrite(key, stored.invocations, record)
            // The version tracks header writes exclusively, so recording an attempt leaves it
            // exactly where it stood: a save built on the header this call started from still names
            // the current version and is accepted, and it carries the recorded attempt forward
            // because save no longer touches invocation rows at all.
            val updated = rebuild(stored, invocations = invocations, version = stored.version)
            runs[key] = updated
            return updated
        }
    }

    override fun transition(
        key: ExtractionRunKey,
        transition: ExtractionRunTransition,
    ): ExtractionRunTransitionResult {
        val result = synchronized(lock) {
            val stored = runs[key] ?: throw ExtractionRunNotFoundException(key)
            if (stored.status.isTerminal) {
                val recorded = terminalWrites[key]
                if (recorded != transition.fingerprint) {
                    throw ExtractionRunConflictException(
                        key,
                        "already ended as ${stored.status} under a different terminal write; " +
                            "this one claims ${transition.status}",
                    )
                }
                ExtractionRunTransitionResult(stored, ExtractionRunTransitionOutcome.REPLAYED)
            } else {
                val terminal = transition.applyTo(stored)
                runs[key] = terminal
                terminalWrites[key] = transition.fingerprint
                ExtractionRunTransitionResult(terminal, ExtractionRunTransitionOutcome.APPLIED)
            }
        }
        // Announced outside the monitor, so a listener that blocks or reads the store back holds up
        // no other writer and sees the terminal run already committed. Exactly one call per run
        // reaches the applied branch above; a replay reports the run and stays silent.
        if (result.isApplied) {
            listener.onEvent(ExtractionRunTransitioned(result.run))
        }
        return result
    }

    override fun findRun(key: ExtractionRunKey): ExtractionRun? = synchronized(lock) { runs[key] }

    override fun invocationsOf(key: ExtractionRunKey): List<ExtractionInvocationRecord> =
        synchronized(lock) { runs[key]?.invocationsInPlanOrder().orEmpty() }

    override fun runsInContext(
        contextIdValue: String,
        limit: Int,
        since: Instant?,
    ): List<ExtractionRun> = page(limit) { run ->
        run.contextId.value == contextIdValue && startedAtOrAfter(run, since)
    }

    override fun childrenOf(
        contextIdValue: String,
        parentRunId: String,
        limit: Int,
    ): List<ExtractionRun> = page(limit) { run ->
        run.contextId.value == contextIdValue && run.parentRef?.runId == parentRunId
    }

    override fun runsOfRoot(
        contextIdValue: String,
        rootRunId: String,
        limit: Int,
        since: Instant?,
    ): List<ExtractionRun> = page(limit) { run ->
        run.contextId.value == contextIdValue &&
            run.rootRef.runId == rootRunId &&
            startedAtOrAfter(run, since)
    }

    override fun ancestorsOf(key: ExtractionRunKey, limit: Int): List<ExtractionRun> {
        requirePositiveLimit(limit)
        synchronized(lock) {
            val start = runs[key] ?: return emptyList()
            val walked = mutableListOf<ExtractionRun>()
            val seen = mutableSetOf(start.ref)
            var parentRef = start.parentRef
            while (parentRef != null && walked.size < limit && seen.add(parentRef)) {
                // Scoped to the starting run's tenant, so a parent id that exists only in another
                // tenant resolves to nothing and the walk stops here.
                val parent = runs[ExtractionRunKey(key.contextId, parentRef)] ?: break
                walked += parent
                parentRef = parent.parentRef
            }
            return walked
        }
    }

    /**
     * Filter, then order, then limit — in that order, because a page that limited first would drop
     * a tenant's runs behind a busier neighbour's and report the shortfall as an empty tenant.
     */
    private fun page(limit: Int, matches: (ExtractionRun) -> Boolean): List<ExtractionRun> {
        requirePositiveLimit(limit)
        return synchronized(lock) {
            runs.values
                .filter(matches)
                .sortedWith(NEWEST_FIRST)
                .take(limit)
        }
    }

    private fun startedAtOrAfter(run: ExtractionRun, since: Instant?): Boolean =
        since == null || !run.startedAt.isBefore(since)

    private fun requirePositiveLimit(limit: Int) {
        require(limit > 0) { "limit must be positive, was $limit" }
    }

    /** Re-lists the run with its invocations and version replaced, since [ExtractionRun] publishes
     *  no `copy`. */
    private fun rebuild(
        run: ExtractionRun,
        invocations: List<ExtractionInvocationRecord>,
        version: Long,
    ): ExtractionRun = ExtractionRun(
        contextId = run.contextId,
        lineage = run.lineage,
        status = run.status,
        startedAt = run.startedAt,
        finishedAt = run.finishedAt,
        profile = run.profile,
        sourceRevisions = run.sourceRevisions,
        fingerprints = run.fingerprints,
        runtime = run.runtime,
        requestedModel = run.requestedModel,
        subjectRefs = run.subjectRefs,
        experimentRef = run.experimentRef,
        cohortRef = run.cohortRef,
        replayFidelity = run.replayFidelity,
        counts = run.counts,
        invocations = invocations,
        failures = run.failures,
        version = version,
    )

    /**
     * Inserts or compares one invocation record against the rows already stored — the whole of
     * [recordInvocation]'s write, on the row's own `(invocationIndex, attempt)` key.
     *
     * A record for an id not yet stored is inserted. A record for an id stored with an
     * [ExtractionInvocationOutcome.IN_FLIGHT] outcome replaces it in place: that is how a call's
     * observed facts fill in while it runs. Once an id's stored record is terminal it is locked — an
     * incoming record for that id is accepted only when it equals the stored one exactly, an
     * idempotent replay, and every other write for that id is rejected: a different outcome, or the
     * same outcome carrying different timing, usage or provider facts, both count as rewriting how
     * the attempt ended.
     *
     * **An existing id is replaced in place, at its stored position; only a genuinely new id is
     * appended.** [ExtractionRun.equals] compares this list by position, so a resend of a record
     * already stored has to land back where it already was. An identical replay landing anywhere
     * else would read as a change: it could move the header's version on what should be a no-op, or
     * turn a stale but otherwise identical resend into a rejection, breaking the no-op the store
     * promises everywhere else. `save` no longer calls this — it does not touch invocation rows at
     * all — so each call inserts or compares exactly one row, always a single record.
     */
    private fun applyInvocationWrite(
        key: ExtractionRunKey,
        stored: List<ExtractionInvocationRecord>,
        record: ExtractionInvocationRecord,
    ): List<ExtractionInvocationRecord> {
        val existing = stored.find { it.id == record.id }
        if (existing == null) {
            return stored + record
        }
        if (existing.outcome.isTerminal && record != existing) {
            throw ExtractionRunConflictException(
                key,
                "${existing.id} already ended as ${existing.outcome}; once an attempt is " +
                    "terminal only an identical write replays, and this one differs",
            )
        }
        return stored.map { if (it.id == record.id) record else it }
    }

    private companion object {

        /** Newest start first, then run id ascending so a page is repeatable. */
        private val NEWEST_FIRST: Comparator<ExtractionRun> =
            compareByDescending<ExtractionRun> { it.startedAt }.thenBy { it.ref.runId }
    }
}

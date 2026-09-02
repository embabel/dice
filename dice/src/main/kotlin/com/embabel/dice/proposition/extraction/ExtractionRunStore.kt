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

import com.embabel.agent.core.ContextId
import org.jetbrains.annotations.ApiStatus
import java.time.Instant

/**
 * Durable store of extraction runs, and the lifecycle state machine that governs them.
 *
 * ## The state machine
 *
 * A run starts `RUNNING` and ends in one of `COMPLETED`, `FAILED` or `CANCELLED`. There are no
 * other edges: a terminal run never re-opens, and a run never moves from one terminal state to
 * another.
 *
 * ```
 * RUNNING ──▶ COMPLETED
 *         ──▶ FAILED
 *         ──▶ CANCELLED
 * ```
 *
 * The two write methods split along that line. [save] records a run and rejects anything that is
 * not `RUNNING`, so a terminal status cannot enter the store through the door that also accepts
 * new keys. [transition] is the only writer of a terminal status, and it is compare-and-set: it
 * moves a run out of `RUNNING` or it does nothing.
 *
 * ## Three writes, three kinds of state, one owner each
 *
 * [save] owns the run header: the profile, the source revisions, the digests, the requested model,
 * the counts, the failures — everything on [ExtractionRun] except its invocation list. It is
 * compare-and-set on [ExtractionRun.version]. [recordInvocation] owns invocation rows: it is the
 * only method that creates, updates or locks one, keyed by [ExtractionInvocationRecord.id], and it
 * is insert-or-compare on that key alone — a write to one attempt's row never touches another's, and
 * never touches the header's version. [transition] owns the terminal write.
 *
 * **A header save ignores whatever invocation snapshot it is handed.** [save] never creates,
 * updates or deletes an invocation row, however non-empty [ExtractionRun.invocations] is on the run
 * it is given. This was not always true — see [save]'s own KDoc for the shared-generation defect
 * that a merged write produced, and why the fix gives invocation rows their own door and their
 * own key, with compare-and-set scoped to that key alone.
 *
 * ## What `COMPLETED` asserts, and who may write it
 *
 * `COMPLETED` means every product the run's request called for is either durably persisted or
 * terminally disposed. The store cannot check that — it holds run headers, not products — so it
 * does the next best thing and makes the claim reachable through exactly one narrow call whose
 * precondition is written down:
 *
 * - on the legacy path, the coordinator calls it once `persistAndProject` has returned;
 * - on the DICE #68 commit path, the commit transaction calls it, and only the commit whose
 *   cumulative outcomes bring every requested product to persisted or terminally disposed.
 *
 * A run whose persistence never finished stays `RUNNING` and is retryable under compare-and-set. A
 * commit that persists some products and leaves others outstanding leaves the run `RUNNING` too, so
 * a terminal run never has re-committable products behind it. A run with zero products completes
 * vacuously: there was nothing to persist, so the coverage claim holds.
 *
 * `FAILED` and `CANCELLED` carry no such precondition. A run that could not finish, or that was
 * stopped, terminalizes independently of whether anything was persisted.
 *
 * ## Idempotency
 *
 * Every terminal write carries a fingerprint of its payload ([ExtractionRunTransition.fingerprint]).
 * A store records the fingerprint of the write that terminalized a run, and a second write against
 * that run is decided by comparison, never by overwrite:
 *
 * - same fingerprint — the same terminal write, retried. It replays as success
 *   ([ExtractionRunTransitionOutcome.REPLAYED]) and changes nothing.
 * - different fingerprint — a second, incompatible claim about how the run ended. Rejected with
 *   [ExtractionRunConflictException].
 *
 * That is insert-or-compare. DICE's existing `MERGE … SET` stores upsert by overwriting, which is
 * safe for a record that is still being written and wrong for one that is finished: it would let a
 * late or duplicated writer silently rewrite how a run ended, and the audit would carry the last
 * write rather than the true one. No method here overwrites a terminal run.
 *
 * Two mechanisms from the idempotency prior art are deliberately not adopted. There is no
 * epoch or writer generation of the Kafka kind: epochs fence a zombie writer across systems, and
 * the concurrency this contract has to survive is two writers racing on one row, which the
 * compare-and-set inside a single store transaction already decides. And there is no key expiry of
 * the Stripe kind: a run header is a permanent audit row, and pruning idempotency records after a
 * day would delete the evidence rather than the bookkeeping.
 *
 * ## Every read is tenant-scoped and bounded
 *
 * A run store grows once per extraction forever, so there is no unbounded read here. Every page
 * takes a positive `limit`, and the reads that can span a long history also take an optional
 * `since` window.
 *
 * **Scope is pushed down, never applied afterwards.** An implementation must restrict to the tenant
 * inside the query and then limit. Fetching `limit` rows and filtering them by tenant afterwards
 * would return fewer rows than asked for — or none — whenever a busy neighbouring tenant occupies
 * the head of the index, and the caller cannot tell that from an empty tenant. This is why none of
 * the scoped reads has a default body: a default that filtered in memory would be inherited
 * silently by every backend that forgot to override it.
 *
 * The `ContextId`-typed overloads do have default bodies, and they are a different thing: they
 * forward to the `String`-typed method that is the override point. They cannot return the wrong
 * rows, because they do not filter. The split exists because `ContextId` is a Kotlin value class,
 * so any method taking one compiles to a mangled JVM name that Java callers cannot reach.
 *
 * **Cross-tenant reads fail closed.** Every lookup, page, chain walk and aggregate is scoped to the
 * tenant it was asked about. A run id that exists in two tenants is two runs, and a read against
 * one never returns the other's. The chain walk stops rather than crossing: a parent reference that
 * resolves only in another tenant is treated as unresolved. Slice 8 proves this against a real
 * graph; here it is the contract every implementation is held to.
 *
 * ## Ordering
 *
 * Pages come back newest first by [ExtractionRun.startedAt], tie-broken by run id ascending. The
 * tie-break is what makes a page repeatable: two runs started in the same millisecond would
 * otherwise come back in whatever order the backend felt like, and a caller paging through would
 * see one of them twice or neither.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 */
@ApiStatus.Experimental
interface ExtractionRunStore {

    // ---- writes ----

    /**
     * Records a running run's header, inserting it under [ExtractionRun.key] or updating the one
     * already there. **This writes header fields only — it is not a door onto invocation state.**
     * [recordInvocation] is the only method that creates, updates or locks an invocation row;
     * whatever [ExtractionRun.invocations] holds on [run] is not written anywhere and does not
     * affect what a save accepts, rejects or replays as a no-op. A caller building [run] from a
     * previous read does not need to strip that field, but nothing is lost either way if it does.
     *
     * The lineage and the start time are fixed at insert. A later save that disagrees with either
     * names a different run wearing the same id and is rejected. Everything else the header owns —
     * the profile, the source revisions the run has read so far, the digests, the counts, the
     * failures it has accumulated — may be filled in as the run proceeds, and an accepted update
     * replaces the previous value for every one of those fields at once.
     *
     * **A header update is compare-and-set on [ExtractionRun.version].** Two callers can hold a run
     * at once — one updating counts, one recording a source revision it just read — and whichever
     * saves second must not silently put the header back the way it looked before the first save.
     * `save` accepts an update only when [run]'s version names the version currently stored; a first
     * save must name `0`, the version a run nobody has saved yet carries. When the named version does
     * not match what is stored, the store has moved since [run] was read, and the write is rejected.
     * A caller meeting that rejection reads the run again with [findRun] and rebuilds its update on
     * the version that read returns. A save whose header content is already exactly what is stored
     * replays as a no-op regardless of the version it names, so a retry that never learned its first
     * attempt landed is never told it conflicted.
     *
     * This was tried first as a field-by-field merge that let two saves each keep the parts of the
     * header the other did not touch. A review round found the merge could not be made both correct
     * and simple: independent count contributions from two writers cannot be recovered by keeping
     * the larger number, because each may have counted disjoint work the other did not see; a union
     * of source revisions loses the order they were read in, which is the field's own documented
     * meaning; and a merge that combines fields from two different writers' saves can produce a
     * header no writer ever actually held — a fingerprint from one save paired with a replay
     * fidelity from another that the fingerprint does not support. A rejected stale write, retried
     * against fresh state by a caller who has the domain knowledge to combine its own new work with
     * what it reads, avoids all three.
     *
     * **Why invocation state moved to its own door entirely.** An earlier version of this contract
     * had `save` merge the invocation records [run] carried into the ones already stored, so a header
     * update built on a run read before an attempt was recorded would not silently drop that
     * attempt. That merge shared the header's version fence for its own conflict checks but not for
     * its own staleness: recording an attempt never moved the header's version, so a save built on a
     * header read well before a [recordInvocation] call landed could still name the current version
     * and be accepted — carrying a stale, non-empty invocation snapshot in on the same write, silently
     * replacing dispatch details that call had already filled in. Two independent writers each
     * updating counts on a header read before the other's attempt was recorded could race the same
     * way against each other. Versioning the header does not cover invocation rows, so the lost
     * update survived the version check — which was the defect. Run state here follows the model
     * lineage systems such as OpenLineage use: independent writers contribute rows, and no write
     * rewrites a row another writer owns. `recordInvocation` insert-or-compares on the invocation's
     * own key, so two attempts never contend on the header's version because neither is writing the
     * header, and a header save can never be the stale write that erases one.
     *
     * @param run The run to record. Must be [ExtractionRunStatus.RUNNING] and carry no
     *   [ExtractionRun.finishedAt].
     * @return The stored run, including any invocation records already recorded against it through
     *   [recordInvocation]. Its [ExtractionRun.version] is `0` after a first save (the version the
     *   caller named, now confirmed by the store), the stored version plus one after an accepted
     *   update, and the stored version unchanged when the save replayed as a no-op.
     * @throws IllegalArgumentException if [run] is not running, if it carries a finish time, or if
     *   it is a first save naming a version other than `0`. A terminal status reaches the store only
     *   through [transition], which is what keeps `COMPLETED` behind its precondition; a running run
     *   that has already finished is a record every page and audit meeting it has to guess about;
     *   [ExtractionRun] leaves status-and-timing pairing to this state machine, so this is where
     *   both checks live, alongside the version a run's first save must carry.
     * @throws ExtractionRunConflictException if a run is already stored under the same key and has
     *   ended, if it disagrees on lineage or start time (the tenant cannot disagree — it is half of
     *   [ExtractionRun.key]), or if [run]'s version does not name the version currently stored.
     */
    fun save(run: ExtractionRun): ExtractionRun

    /**
     * Records one attempt at one model call against a running run. **This is the only door onto
     * invocation state** — `save` writes header fields only and never creates, updates or deletes an
     * invocation row, however non-empty the invocation list on the run it is handed.
     *
     * The record's [ExtractionInvocationRecord.id] is its key within the run, and every write here
     * is insert-or-compare on that key alone: a row for an id not yet stored is inserted, and a
     * write against a row another writer owns never overwrites it wholesale — see the terminal lock
     * below for what "owns" means before an id's outcome settles. A caller does not have to hold the
     * whole run header to add one, and two calls recording different ids never contend with each
     * other or with a concurrent [save].
     *
     * While the attempt is [ExtractionInvocationOutcome.IN_FLIGHT], a repeated write for the same id
     * updates in place — that is how dispatch details and, eventually, the terminal outcome fill in
     * as the attempt runs — and the next attempt lands on its own row. That in-place update replaces
     * the whole record with whatever the latest write carries; it does not merge fields from the
     * write it displaces. Two writers racing on the same id while it is still
     * [ExtractionInvocationOutcome.IN_FLIGHT], each carrying disjoint dispatch facts the other does
     * not have, leave only the facts the later write named — the earlier write's facts are gone,
     * with no conflict raised, because neither write disagrees about the outcome and the lock in the
     * next paragraph applies only once the record is terminal. A caller that needs every writer's
     * facts preserved has to carry the full accumulated record on each write itself; the store does
     * not accumulate one for it.
     *
     * **Once an attempt is terminal, its record is locked.** A write for an id already stored as
     * [ExtractionInvocationOutcome.SUCCEEDED], [ExtractionInvocationOutcome.FAILED] or
     * [ExtractionInvocationOutcome.CANCELLED] is accepted only when it equals the stored record
     * exactly — an identical retry replays as a no-op — and every other write for that id is
     * rejected, whether it claims a different outcome or the same outcome with different timing,
     * usage or provider facts. The case that motivates the rule is a dispatcher's own retry timer
     * firing late and delivering an [ExtractionInvocationOutcome.IN_FLIGHT] write for an attempt
     * that had already succeeded or failed, which would otherwise put the attempt back to
     * outstanding and erase the record of how it actually ended. Locking the whole record closes a
     * narrower version of the same problem too — a delayed write that repeats the correct outcome
     * and omits the timing, usage or provider facts the terminal write actually carried. A rejection
     * here matches [transition]'s own choice for the run as a whole: a caller finding out that its
     * message arrived too late is safer than a caller that cannot tell whether it did. Because `save`
     * never touches this state, a stale header snapshot — however old, however different its
     * invocation list — cannot be the write that puts a terminal record back to outstanding or
     * erases the facts it carries; only another call here can.
     *
     * @param key The run to record against.
     * @param record The attempt.
     * @return The run, with the record in place.
     * @throws ExtractionRunNotFoundException if no run is stored under [key].
     * @throws ExtractionRunConflictException if the run has already ended (a finished run's
     *   invocation list is part of how it finished), or if [record] differs from an attempt the store
     *   already holds as terminal.
     */
    fun recordInvocation(key: ExtractionRunKey, record: ExtractionInvocationRecord): ExtractionRun

    /**
     * Ends a run: compare-and-set from `RUNNING` to the transition's terminal status.
     *
     * **A replay needs the identical finish time.** A coordinator retrying after a crash it never
     * saw the answer to must reuse the transition it built the first time, or read the run back
     * with [findRun] and stop if it has already ended. Minting a fresh `finishedAt` on the retry
     * produces a different fingerprint, which is an incompatible rewrite and is rejected — safe,
     * and the opposite of what a caller expecting idempotency would predict. It is the
     * transition-side twin of the rule [save] states for start times.
     *
     * **A run's outcome is written once.** The fingerprint covers the terminal status and the
     * finish time; the counts and failures a transition carries stay outside it. A retry that
     * agrees on status and finish time replays whatever numbers it names, and the run keeps what
     * the first accepted terminal write delivered. A coordinator with better numbers than the ones
     * that landed has to record them before it ends the run.
     *
     * **An applied transition emits one [com.embabel.dice.common.ExtractionRunTransitioned], a
     * replay emits none.** A store notifies its listener after the write it just made, once, for
     * the call that ended the run. A replay changed nothing, so it announces nothing: a coordinator
     * retrying a call whose answer it never saw would otherwise notify every downstream consumer a
     * second time for a run that ended once. A rejected write announces nothing either.
     *
     * @param key The run to end.
     * @param transition What the run ended as.
     * @return The terminal run, and whether this call ended it or replayed a write already
     *   recorded.
     * @throws ExtractionRunNotFoundException if no run is stored under [key]. A terminal write
     *   against a run nobody started is a bug in the caller, not a run to invent.
     * @throws ExtractionRunConflictException if the run has already ended under a terminal write
     *   with a different fingerprint.
     */
    fun transition(
        key: ExtractionRunKey,
        transition: ExtractionRunTransition,
    ): ExtractionRunTransitionResult

    // ---- reads ----

    /**
     * The run stored under [key], or null.
     *
     * @param key The tenant-qualified run identity.
     * @return The run, or null if this tenant has no run under that id.
     */
    fun findRun(key: ExtractionRunKey): ExtractionRun?

    /**
     * Every attempt recorded against the run, in plan order: call 0 before call 1, and within a
     * call, first attempt before second.
     *
     * @param key The tenant-qualified run identity.
     * @return The records, or empty if the run has none or does not exist.
     */
    fun invocationsOf(key: ExtractionRunKey): List<ExtractionInvocationRecord>

    /**
     * One tenant's runs, newest first.
     *
     * @param contextIdValue The tenant.
     * @param limit The most runs to return. Must be positive.
     * @param since When non-null, only runs started at or after this instant.
     * @return At most [limit] runs, newest first by start time and then by run id.
     * @throws IllegalArgumentException if [limit] is not positive.
     */
    fun runsInContext(contextIdValue: String, limit: Int, since: Instant?): List<ExtractionRun>

    /**
     * [runsInContext] for Kotlin callers holding a typed tenant.
     *
     * @param contextId The tenant.
     * @param limit The most runs to return. Must be positive.
     * @param since When non-null, only runs started at or after this instant.
     * @return At most [limit] runs, newest first.
     */
    fun runsInContext(contextId: ContextId, limit: Int, since: Instant?): List<ExtractionRun> =
        runsInContext(contextId.value, limit, since)

    /**
     * The runs whose immediate parent is [parentRunId] — one hop down the parent axis, in one
     * tenant.
     *
     * Supersession is a separate axis and is not walked here. A run that replaces another without
     * continuing it is not its child.
     *
     * @param contextIdValue The tenant.
     * @param parentRunId The parent run's id.
     * @param limit The most runs to return. Must be positive.
     * @return At most [limit] children, newest first.
     * @throws IllegalArgumentException if [limit] is not positive.
     */
    fun childrenOf(contextIdValue: String, parentRunId: String, limit: Int): List<ExtractionRun>

    /**
     * [childrenOf] for Kotlin callers holding typed references.
     *
     * @param contextId The tenant.
     * @param parent The parent run.
     * @param limit The most runs to return. Must be positive.
     * @return At most [limit] children, newest first.
     */
    fun childrenOf(contextId: ContextId, parent: ExtractionRunRef, limit: Int): List<ExtractionRun> =
        childrenOf(contextId.value, parent.runId, limit)

    /**
     * Every run in one lineage: those whose [ExtractionRunLineage.rootRunRef] is [rootRunId],
     * including the root itself.
     *
     * This is the read the denormalized root reference exists for. The root is fixed when a lineage
     * is minted and can never drift, so a whole lineage is one indexed read on one property instead
     * of a chain walk a hop at a time.
     *
     * @param contextIdValue The tenant.
     * @param rootRunId The root run's id.
     * @param limit The most runs to return. Must be positive.
     * @param since When non-null, only runs started at or after this instant.
     * @return At most [limit] runs of that lineage, newest first.
     * @throws IllegalArgumentException if [limit] is not positive.
     */
    fun runsOfRoot(
        contextIdValue: String,
        rootRunId: String,
        limit: Int,
        since: Instant?,
    ): List<ExtractionRun>

    /**
     * [runsOfRoot] for Kotlin callers holding typed references.
     *
     * @param contextId The tenant.
     * @param root The root run.
     * @param limit The most runs to return. Must be positive.
     * @param since When non-null, only runs started at or after this instant.
     * @return At most [limit] runs of that lineage, newest first.
     */
    fun runsOfRoot(
        contextId: ContextId,
        root: ExtractionRunRef,
        limit: Int,
        since: Instant?,
    ): List<ExtractionRun> = runsOfRoot(contextId.value, root.runId, limit, since)

    /**
     * Walks the parent chain up from the run at [key], nearest ancestor first. The run itself is
     * not in the result.
     *
     * The walk is bounded and cycle-safe, and it needs to be both. [limit] stops it in a lineage
     * deeper than the caller wants to read. A run already visited stops it outright: a value type
     * can reject a run that is its own parent, but a two-hop cycle needs the other runs to see, so
     * detecting one is the store's job. A store holding a cycle is corrupt; a store that hangs on
     * one is worse.
     *
     * The walk also stops at the tenant boundary. A parent reference that resolves only in another
     * tenant resolves to nothing here, so a chain read can never leak a neighbour's run.
     *
     * @param key The run to walk up from.
     * @param limit The most ancestors to return. Must be positive.
     * @return At most [limit] ancestors, parent first, ending early at an unresolvable parent or a
     *   run already seen. Empty if the run is a root, or is not stored.
     * @throws IllegalArgumentException if [limit] is not positive.
     */
    fun ancestorsOf(key: ExtractionRunKey, limit: Int): List<ExtractionRun>
}

/**
 * A write named a run the store does not hold.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property key The run that was not found
 */
@ApiStatus.Experimental
class ExtractionRunNotFoundException(val key: ExtractionRunKey) : RuntimeException(
    "no run ${key.runRef.runId} in context ${key.contextId.value}",
)

/**
 * A write disagreed with what the store already holds.
 *
 * Five cases, one per door: a terminal write whose fingerprint differs from the one that ended the
 * run; a write, through [ExtractionRunStore.save] or [ExtractionRunStore.recordInvocation], against
 * a run that has already ended; a save that disagrees with the stored run's lineage or start time
 * (tenant cannot disagree — it is half of [ExtractionRunKey], so a different tenant always
 * addresses an entirely separate run); a save whose [ExtractionRun.version] does not name the
 * version currently stored; and an invocation record, through [ExtractionRunStore.recordInvocation]
 * — the only door onto that state — that differs from an attempt the store already holds as
 * terminal. `save` cannot raise that last case: it does not read or write invocation rows, so
 * nothing it carries there is ever compared against one.
 *
 * The message never quotes a fingerprint in full or any part of a payload. It says which run and
 * which rule, which is what an operator needs, and leaves the values to a deliberate read.
 *
 * EXPERIMENTAL. The shape may still change while extraction runs (DICE #67) land.
 *
 * @property key The run the write was against
 */
@ApiStatus.Experimental
class ExtractionRunConflictException(
    val key: ExtractionRunKey,
    message: String,
) : RuntimeException("run ${key.runRef.runId} in context ${key.contextId.value}: $message")

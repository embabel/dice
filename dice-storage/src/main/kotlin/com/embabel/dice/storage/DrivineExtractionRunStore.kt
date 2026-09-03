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

import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.common.ExtractionRunTransitioned
import com.embabel.dice.proposition.extraction.ExtractionInvocationOutcome
import com.embabel.dice.proposition.extraction.ExtractionInvocationRecord
import com.embabel.dice.proposition.extraction.ExtractionRun
import com.embabel.dice.proposition.extraction.ExtractionRunConflictException
import com.embabel.dice.proposition.extraction.ExtractionRunKey
import com.embabel.dice.proposition.extraction.ExtractionRunNotFoundException
import com.embabel.dice.proposition.extraction.ExtractionRunRef
import com.embabel.dice.proposition.extraction.ExtractionRunStatus
import com.embabel.dice.proposition.extraction.ExtractionRunStore
import com.embabel.dice.proposition.extraction.ExtractionRunTransition
import com.embabel.dice.proposition.extraction.ExtractionRunTransitionOutcome
import com.embabel.dice.proposition.extraction.ExtractionRunTransitionResult
import org.drivine.manager.PersistenceManager
import org.drivine.query.QuerySpecification
import org.slf4j.LoggerFactory
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Drivine / Neo4j implementation of [ExtractionRunStore].
 *
 * ## The graph
 *
 * - `(:ExtractionRun {contextId, runId, ...})` — the run header, one node per run per tenant.
 * - `(:ExtractionRun)-[:RECORDED]->(:ExtractionRunInvocation {contextId, runId, invocationIndex, attempt, ...})`
 *   — one child node per attempt at one planned model call.
 * - `(:ExtractionRun)-[:ENDED_BY]->(:ExtractionRunTerminalWrite {contextId, runId, fingerprint, ...})`
 *   — at most one per run, ever, and the thing that makes that "at most one" true.
 *
 * Every key is tenant-qualified because a run id is host-minted and DICE never assumes it is
 * globally unique. Unlike `DrivineDriftReportStore`, the tenant needs no `ctx:`-prefixed stand-in:
 * that store's scope is nullable, and a Cypher MERGE cannot key on a null, so a global report needed
 * a non-null encoding that no real context id could collide with. A run's tenant is never null, so
 * the plain value is already an injective key.
 *
 * Every statement is parameterized; nothing caller-derived is interpolated into Cypher. The
 * statements are assembled only from this class's own literals.
 *
 * See [ExtractionRunSchema] for the constraints and indexes this depends on. They are not tuning:
 * a MERGE on a natural key is race-free only under a uniqueness constraint on that key.
 *
 * ## Compare-and-set: why a terminal write cannot be applied twice
 *
 * The whole of [transition] is one Cypher statement, so it is one transaction, and it works two
 * ways at once — a lock that makes the race rare and a constraint that makes the wrong answer
 * impossible.
 *
 * **The lock.** The statement's first act after matching the run is `SET n.casLock = $lockToken`,
 * before it reads anything. A property write takes an exclusive lock on the node and holds it until
 * commit, so a second transaction reaching that line blocks until the first commits. Neo4j reads at
 * read-committed, and the read of `n.status` is downstream of the `SET` in the same statement, so
 * the second transaction reads the status *after* it acquired the lock and therefore *after* the
 * first transaction committed. It sees a terminal run and takes the no-op branch. This is the same
 * write-lock-before-read idiom `DrivineMetamodelVersionStore` uses on its counter.
 *
 * Two details make that argument hold rather than nearly hold. The lock token is a fresh UUID on
 * every call, so the write is always a real change and never a no-op a future Neo4j might optimize
 * away before taking the lock. And no index carries `status` or the terminal fingerprint — see
 * [ExtractionRunSchema] — so the planner cannot serve the post-lock read from an index entry it
 * read at MATCH time, which is the one way a lock-then-read can quietly read stale.
 *
 * **The constraint.** The argument above is about Neo4j's behaviour, and behaviour is a thing to be
 * wrong about. So the terminal write is also a `CREATE` of an `(:ExtractionRunTerminalWrite)` node
 * on the run's own key, under a uniqueness constraint on `(contextId, runId)`. If two transactions
 * ever did both read a run as `RUNNING` — a Neo4j build that locks differently, a cluster, a
 * planner that reorders in a way this KDoc did not anticipate — they would both try to create that
 * node and the database would refuse the second one at commit. The loser's whole transaction rolls
 * back, header included, and this store catches the violation, re-reads the recorded fingerprint in
 * a fresh transaction, and returns the replay-or-conflict answer the contract asks for. Exactly one
 * terminal write per run is a schema fact, not an inference.
 *
 * That is the multi-process half of the guarantee. Threads in one JVM could be serialized by a
 * monitor; two processes cannot be, and nothing in this class holds mutable state to serialize on.
 * Both halves come entirely from the database.
 *
 * **The fingerprint is stored, never re-derived.** The node carries the exact string
 * [ExtractionRunTransition.fingerprint] computed, and a repeated terminal write is decided by
 * comparing its fingerprint against that string. Re-deriving a digest from the stored run would
 * reject a correct retry whenever an attempt had been recorded in between, because the run it
 * derived from would have changed while the terminal write did not.
 *
 * ## A header write never touches a child row
 *
 * Invocation records are their own nodes on their own key, and [save]'s Cypher names none of them:
 * it reads and writes `(:ExtractionRun)` alone. [recordInvocation] is the only method that ever
 * names an `(:ExtractionRunInvocation)` node, so it is the only one that can create, update, or
 * lock one. Whatever `run.invocations` a caller hands [save] plays no part in what it accepts,
 * rejects, or persists.
 *
 * ## Validation happens before any node is created
 *
 * A `MERGE` on a node pattern creates the node the moment it runs, whether or not the write that
 * follows turns out to be valid — a naive `MERGE` immediately followed by a Kotlin check that
 * throws leaves the created node behind if whatever catches that exception does not also abort the
 * transaction. `SAVE_RUN` and `RECORD_INVOCATION` both avoid this: an `OPTIONAL MATCH` reads
 * whatever is already there, a `CASE` decides in Cypher whether the write should happen, and the
 * `MERGE` that can create a node sits inside a `FOREACH` gated on that decision. A save naming
 * version `0` for a run already stored at a later version, or a [recordInvocation] call against a
 * run that has already ended, stops before reaching any `MERGE` at all, so a caller that catches
 * the resulting [ExtractionRunConflictException] without rolling back its own transaction finds
 * the graph exactly as it was.
 *
 * ## Scope is applied in the query, ahead of the limit
 *
 * Each page puts its tenant in the MATCH pattern and its `LIMIT` after the `ORDER BY`. Reading a
 * limited page and filtering it in Kotlin would apply the limit first, so a tenant whose neighbour
 * owns the head of the index would report no runs while the store held plenty. This is
 * `DrivineDriftReportStore`'s rule carried over, and the cross-backend suite pins it.
 *
 * Every read returns each run with its invocation records attached, pages included, because the
 * in-memory reference does and the two are held to one suite. The cost is bounded — at most `limit`
 * runs times the run model's cap of 1024 attempts — but it is a real cost on a wide page.
 *
 * ## A run that ends announces itself once
 *
 * [transition] hands an [ExtractionRunTransitioned] to [listener] for the call that ended the run,
 * and for no other call: a replay, a rejected write, a [save] and a [recordInvocation] all announce
 * nothing. Exactly one call per run reaches the applied branch, because reaching it means having
 * created the run's terminal-write node, and the uniqueness constraint lets one transaction do that.
 *
 * The announcement waits for the write to be durable. When this store owns the transaction, that
 * point is where the template returns, and the listener runs there. When a caller's transaction is
 * active the write is durable when that caller commits, so the announcement is registered against
 * the commit and never happens at all if the caller rolls back — a listener told a run ended by a
 * transaction that was thrown away would be reporting a run nothing can read.
 *
 * @param persistenceManager Drivine's handle on the `neo` datasource.
 * @param transactionManager used to own a transaction when no caller has one, so a lost
 *   compare-and-set race can be recovered in a transaction the race did not already poison.
 * @param clock supplies the instant a terminal write is recorded at. Informational, and injectable
 *   so a test can pin it; nothing sorts or compares on it.
 * @param listener Notified when a run ends. Defaults to [DiceEventListener.DEV_NULL], so a host
 *   that has nothing listening constructs the store the way it always did. Handlers run inline on
 *   whichever thread the announcement happens on, and throw isolation belongs to the listener —
 *   wrap it in `SafeDiceEventListener` for graceful degradation.
 */
@Transactional
open class DrivineExtractionRunStore @JvmOverloads constructor(
    private val persistenceManager: PersistenceManager,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock = Clock.systemUTC(),
    private val listener: DiceEventListener = DiceEventListener.DEV_NULL,
) : ExtractionRunStore {

    private val logger = LoggerFactory.getLogger(DrivineExtractionRunStore::class.java)

    private val txTemplate = TransactionTemplate(transactionManager)

    // ---- writes ----

    /**
     * Records a running run, inserting it or updating the one already there — in one statement, so
     * the check against the stored run and the write that follows it cannot be interleaved by a
     * concurrent terminal write, and the header's compare-and-set on
     * [com.embabel.dice.proposition.extraction.ExtractionRun.version] cannot be interleaved by a
     * concurrent header save either.
     *
     * The statement returns what it found so this method can name which rule was broken. Cypher
     * decides whether to write and Kotlin decides what to say about it, and the conditions are the
     * same condition, written twice: a save writes when the run is new and names version 0; when it
     * is still running, agrees with the stored lineage and start time, and its header content
     * already matches what is stored — a no-op that leaves the version untouched, whatever version
     * the save named; or when it is still running, agrees with lineage and start time, its content
     * genuinely differs, and it names the version currently stored. Every other running-and-agreeing
     * case is a stale write and is rejected without touching the row — see this class's KDoc for why
     * that rejection never leaves a node behind. `run.invocations` is not part of the statement at
     * all: this method's Cypher has no clause that reads or writes an `(:ExtractionRunInvocation)`.
     *
     * Runs and retries under [ownedTransaction] — see that method for the two shapes.
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    override fun save(run: ExtractionRun): ExtractionRun {
        require(run.status == ExtractionRunStatus.RUNNING) {
            "save records a running run; ${run.status} is terminal and belongs to transition()"
        }
        require(run.finishedAt == null) {
            "a running run has not finished, so it carries no finishedAt"
        }
        val key = run.key()
        ExtractionRunSchema.requireStorableTenant(key.contextId)

        val lineageKey = ExtractionRunRowMapper.lineageKeyOf(run.lineage)
        val startedAt = run.startedAt.toString()
        val header = ExtractionRunRowMapper.headerBindMap(run)
        val headerFingerprint = ExtractionRunRowMapper.headerFingerprint(header)

        return ownedTransaction {
            val row = singleRow(
                SAVE_RUN,
                keyBindings(key) + mapOf(
                    "lockToken" to newLockToken(),
                    "running" to RUNNING,
                    "lineageKey" to lineageKey,
                    "startedAt" to startedAt,
                    "header" to header,
                    "headerFingerprint" to headerFingerprint,
                    "version" to run.version,
                ),
            ) ?: throw IllegalStateException("SAVE_RUN returned no row for ${key.runRef.runId}")

            when (val priorStatus = row["priorStatus"]?.toString()) {
                null -> require(run.version == 0L) {
                    "a run's first save must name version 0, the version a run nobody has saved " +
                        "yet carries; this one names ${run.version}"
                }

                RUNNING -> {
                    if (row["priorLineageKey"]?.toString() != lineageKey) {
                        throw ExtractionRunConflictException(
                            key,
                            "stored lineage differs from the one being saved; lineage is fixed at insert",
                        )
                    }
                    if (row["priorStartedAt"]?.toString() != startedAt) {
                        throw ExtractionRunConflictException(
                            key,
                            "stored startedAt differs from the one being saved; a run starts once",
                        )
                    }
                    if (row["priorHeaderFingerprint"]?.toString() != headerFingerprint) {
                        val priorVersion = (row["priorVersion"] as? Number)?.toLong()
                            ?: throw IllegalStateException(
                                "run ${key.runRef.runId} in context ${key.contextId.value} is RUNNING " +
                                    "with no stored version",
                            )
                        if (run.version != priorVersion) {
                            throw ExtractionRunConflictException(
                                key,
                                "the header was read at version ${run.version}; the store is now at " +
                                    "$priorVersion. Read the run again with findRun and rebuild this " +
                                    "save on what it holds now",
                            )
                        }
                    }
                    // else: content already matches what is stored, so the statement above left the
                    // row exactly as it was — a no-op, whatever version this save named.
                }

                else -> throw ExtractionRunConflictException(
                    key,
                    "already ended as $priorStatus and cannot be re-opened by a save",
                )
            }

            requireStoredRun(key)
        }
    }

    /**
     * Records one attempt against a running run, on its own child node.
     *
     * One statement again, and the same shape as [save]: read the run's status and the attempt's
     * prior outcome and fingerprint, write only if the run is still running and the attempt is not
     * locked as terminal under a different payload — and, per this class's KDoc, only create the
     * node at all once that decision comes back yes. A caller that holds only the attempt never has
     * to hold the header, and this can never overwrite one.
     *
     * **Once the attempt is terminal, only an identical write reaches the row.** The incoming
     * record's fingerprint — [ExtractionInvocationRowMapper.fingerprint] — is compared straight
     * against the string already stored on the node, always computed fresh from the record a
     * caller handed this method; the run's own terminal write follows the identical stored-string,
     * compared-verbatim rule, one level down.
     *
     * Runs and retries under [ownedTransaction] — see that method for the two shapes.
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    override fun recordInvocation(
        key: ExtractionRunKey,
        record: ExtractionInvocationRecord,
    ): ExtractionRun {
        ExtractionRunSchema.requireStorableTenant(key.contextId)
        val fingerprint = ExtractionInvocationRowMapper.fingerprint(record)

        return ownedTransaction {
            val row = singleRow(
                RECORD_INVOCATION,
                keyBindings(key) + mapOf(
                    "lockToken" to newLockToken(),
                    "running" to RUNNING,
                    "inFlight" to IN_FLIGHT,
                    "invocationIndex" to record.invocationIndex,
                    "attempt" to record.attempt,
                    "recordFingerprint" to fingerprint,
                    "record" to (ExtractionInvocationRowMapper.bindMap(record) + mapOf("recordFingerprint" to fingerprint)),
                ),
            ) ?: throw ExtractionRunNotFoundException(key)

            val priorStatus = row["priorStatus"]?.toString()
            if (priorStatus != RUNNING) {
                throw ExtractionRunConflictException(
                    key,
                    "already ended as $priorStatus; its invocation records are part of how it ended",
                )
            }
            if (row["locked"] == true) {
                throw ExtractionRunConflictException(
                    key,
                    "${record.id} already ended as ${row["priorOutcome"]}; once an attempt is terminal " +
                        "only an identical write replays, and this one differs",
                )
            }
            requireStoredRun(key)
        }
    }

    /**
     * Ends a run under compare-and-set. See this class's KDoc for why the answer is a schema fact
     * rather than a claim about timing.
     *
     * Two transaction shapes, for the same reason `DrivinePropositionRepository.save` has two:
     * - **A caller's transaction is active.** This joins it. A lost race has already ended that
     *   transaction by the time this method could react, so no recovery of ours could commit in it;
     *   the violation propagates and retrying is the caller's. Opening a nested transaction to sneak
     *   a read out would break the atomicity the caller asked for.
     * - **No caller transaction.** `SUPPORTS` keeps the proxy from opening one, so the template owns
     *   the attempt, and the recovery read runs in a fresh transaction the failed attempt cannot
     *   roll back.
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    override fun transition(
        key: ExtractionRunKey,
        transition: ExtractionRunTransition,
    ): ExtractionRunTransitionResult {
        ExtractionRunSchema.requireStorableTenant(key.contextId)
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return announce(endRun(key, transition))
        }

        var lostRace: RuntimeException? = null
        val result = try {
            txTemplate.execute { endRun(key, transition) }
        } catch (e: RuntimeException) {
            if (!isUniquenessViolation(e)) throw e
            lostRace = e
            null
        }
        if (result != null) return announce(result)

        logger.debug(
            "Terminal write for run {} in context {} lost the race; re-reading the recorded write",
            key.runRef.runId,
            key.contextId.value,
        )
        return txTemplate.execute { resolveLostRace(key, transition, lostRace!!) }!!
    }

    /**
     * Runs the compare-and-set statement and turns what it found into the contract's answer.
     *
     * `priorStatus` is what the run was when this transaction took its lock. `RUNNING` means the
     * statement applied the terminal fields and created the terminal-write node; anything else means
     * it wrote nothing, and the decision is a comparison of the stored fingerprint against this one.
     */
    private fun endRun(
        key: ExtractionRunKey,
        transition: ExtractionRunTransition,
    ): ExtractionRunTransitionResult {
        val row = singleRow(
            END_RUN,
            keyBindings(key) + mapOf(
                "lockToken" to newLockToken(),
                "running" to RUNNING,
                "status" to transition.status.name,
                // The string the transition computed, stored verbatim. Nothing here derives it.
                "fingerprint" to transition.fingerprint,
                "recordedAt" to clock.instant().toString(),
                "terminal" to ExtractionRunRowMapper.terminalBindMap(transition),
            ),
        ) ?: throw ExtractionRunNotFoundException(key)

        val priorStatus = row["priorStatus"]?.toString()
        if (priorStatus == RUNNING) {
            logger.debug(
                "Ended run {} in context {} as {}",
                key.runRef.runId,
                key.contextId.value,
                transition.status,
            )
            return ExtractionRunTransitionResult(
                requireStoredRun(key),
                ExtractionRunTransitionOutcome.APPLIED,
            )
        }
        return replayOrConflict(key, transition, row["priorFingerprint"]?.toString(), priorStatus)
    }

    /**
     * Decides what a terminal write against an already-ended run means, by comparing fingerprints.
     *
     * A missing recorded fingerprint is a conflict, not a replay: a terminal run this store cannot
     * show a terminal write for is one it cannot prove agrees with the incoming one, and agreeing is
     * the only thing that makes overwriting safe.
     */
    private fun replayOrConflict(
        key: ExtractionRunKey,
        transition: ExtractionRunTransition,
        recordedFingerprint: String?,
        priorStatus: String?,
    ): ExtractionRunTransitionResult {
        if (recordedFingerprint != null && recordedFingerprint == transition.fingerprint) {
            return ExtractionRunTransitionResult(
                requireStoredRun(key),
                ExtractionRunTransitionOutcome.REPLAYED,
            )
        }
        throw ExtractionRunConflictException(
            key,
            "already ended as $priorStatus under a different terminal write; " +
                "this one claims ${transition.status}",
        )
    }

    /**
     * Recovers from losing the create of the terminal-write node: read what the winner recorded and
     * decide against it.
     *
     * If there is no terminal write to find, the violation was not the one this method knows how to
     * interpret, so the original exception is rethrown rather than guessed about.
     */
    private fun resolveLostRace(
        key: ExtractionRunKey,
        transition: ExtractionRunTransition,
        violation: RuntimeException,
    ): ExtractionRunTransitionResult {
        val row = singleRow(TERMINAL_WRITE_BY_KEY, keyBindings(key)) ?: throw violation
        // Nothing announced here: the writer that lost the race created no terminal-write node, so
        // this path answers replayed or conflict and never applied.
        return replayOrConflict(
            key,
            transition,
            row["fingerprint"]?.toString(),
            row["status"]?.toString(),
        )
    }

    /**
     * Tells [listener] about a run this call ended, once the write is durable, and returns [result]
     * so a caller can announce and return in one line.
     *
     * A replay and a rejected write reach neither branch below — a rejected write throws before
     * getting here, and a replay is not applied. Inside a caller's transaction the write becomes
     * durable at that caller's commit, so the announcement rides on it and is dropped with the
     * transaction if the caller rolls back. Otherwise the commit has already happened by the time
     * this runs, and the listener is called on the spot.
     */
    private fun announce(result: ExtractionRunTransitionResult): ExtractionRunTransitionResult {
        if (!result.isApplied) return result
        val event = ExtractionRunTransitioned(result.run)
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        listener.onEvent(event)
                    }
                },
            )
            return result
        }
        listener.onEvent(event)
        return result
    }

    // ---- reads ----

    @Transactional(readOnly = true)
    override fun findRun(key: ExtractionRunKey): ExtractionRun? =
        singleRow(RUN_BY_KEY, keyBindings(key))?.let(::mapRun)

    @Transactional(readOnly = true)
    override fun invocationsOf(key: ExtractionRunKey): List<ExtractionInvocationRecord> =
        queryRows(INVOCATIONS_OF, keyBindings(key)).mapNotNull(::mapInvocation)

    @Transactional(readOnly = true)
    override fun runsInContext(
        contextIdValue: String,
        limit: Int,
        since: Instant?,
    ): List<ExtractionRun> = readPage(
        scope = null,
        contextIdValue = contextIdValue,
        limit = limit,
        since = since,
    )

    @Transactional(readOnly = true)
    override fun childrenOf(
        contextIdValue: String,
        parentRunId: String,
        limit: Int,
    ): List<ExtractionRun> = readPage(
        scope = ONLY_PARENT,
        contextIdValue = contextIdValue,
        limit = limit,
        since = null,
        extraBindings = mapOf("parentRunId" to parentRunId),
    )

    @Transactional(readOnly = true)
    override fun runsOfRoot(
        contextIdValue: String,
        rootRunId: String,
        limit: Int,
        since: Instant?,
    ): List<ExtractionRun> = readPage(
        scope = ONLY_ROOT,
        contextIdValue = contextIdValue,
        limit = limit,
        since = since,
        extraBindings = mapOf("rootRunId" to rootRunId),
    )

    /**
     * Walks the parent chain upward, one keyed lookup per hop, at most [limit] hops.
     *
     * **Why the walk is here and not in Cypher.** A parent is a property, not a relationship: a run
     * can name a parent that has not been stored yet, and an edge cannot point at a node that does
     * not exist. Materializing the edge later would mean a second write nothing triggers. Without an
     * edge there is no variable-length pattern to walk, and the APOC procedures that would do it in
     * one round trip are not a dependency this module takes.
     *
     * So the walk is client-side and bounded by construction: at most `limit` hops, each a lookup on
     * the uniqueness-constraint index, all inside one read transaction. It stops on a run it has
     * already seen, which is what makes it safe on a corrupt store holding a cycle — [ExtractionRunLineage]
     * can reject a run that is its own parent, but a two-hop cycle needs the other runs to see. And
     * it resolves every hop inside the starting run's tenant, so a parent id that exists only in a
     * neighbour's tenant resolves to nothing and the walk ends.
     */
    @Transactional(readOnly = true)
    override fun ancestorsOf(key: ExtractionRunKey, limit: Int): List<ExtractionRun> {
        requirePositiveLimit(limit)
        val start = findRun(key) ?: return emptyList()
        val walked = mutableListOf<ExtractionRun>()
        val seen = mutableSetOf(start.ref)
        var parentRef: ExtractionRunRef? = start.parentRef
        while (parentRef != null && walked.size < limit && seen.add(parentRef)) {
            val parent = findRun(ExtractionRunKey(key.contextId, parentRef)) ?: break
            walked += parent
            parentRef = parent.parentRef
        }
        return walked
    }

    /**
     * Assembles and runs one of the three pages.
     *
     * The statement is built from this class's own literals and nothing else; every value travels as
     * a bound parameter. `scope` narrows inside the `WHERE`, ahead of the `LIMIT`, which is the whole
     * point.
     *
     * A row that will not map has already spent one of the caller's `limit` slots, so a page can come
     * back shorter than asked for. Reading further to backfill would break the bound the contract
     * keeps.
     */
    private fun readPage(
        scope: String?,
        contextIdValue: String,
        limit: Int,
        since: Instant?,
        extraBindings: Map<String, Any?> = emptyMap(),
    ): List<ExtractionRun> {
        requirePositiveLimit(limit)
        val statement = buildString {
            append(PAGE_MATCH)
            scope?.let { append("\n").append(it) }
            if (since != null) append("\n").append(SINCE_BOUND)
            append("\n").append(NEWEST_FIRST_PAGE)
        }
        val bindings = buildMap {
            put("contextId", contextIdValue)
            put("limit", limit)
            putAll(extraBindings)
            if (since != null) {
                put("sinceEpochSecond", since.epochSecond)
                put("sinceNano", since.nano)
            }
        }
        return queryRows(statement, bindings).mapNotNull(::mapRun)
    }

    /**
     * Turns one `{run, invocations}` row into a run, or logs it and returns null.
     *
     * One corrupt node should not fail a whole audit read, and the row mappers throw rather than
     * inventing defaults so that this can happen. The warning names the property that was missing or
     * the check that failed, which is what an operator needs to go find the node. A corrupt child row
     * is dropped on its own, so a run with one unreadable attempt still reads.
     */
    private fun mapRun(row: Map<*, *>): ExtractionRun? {
        val header = row["run"] as? Map<*, *> ?: run {
            logger.warn("Skipping ExtractionRun row with no header properties")
            return null
        }
        val invocations = (row["invocations"] as? List<*>).orEmpty()
            .filterIsInstance<Map<*, *>>()
            .mapNotNull(::mapInvocation)
        return runCatching { ExtractionRunRowMapper.fromRow(header, invocations) }
            .onFailure { logger.warn("Skipping unreadable ExtractionRun row: {}", it.message) }
            .getOrNull()
    }

    private fun mapInvocation(row: Map<*, *>): ExtractionInvocationRecord? =
        runCatching { ExtractionInvocationRowMapper.fromRow(row) }
            .onFailure { logger.warn("Skipping unreadable ExtractionRunInvocation row: {}", it.message) }
            .getOrNull()

    private fun requireStoredRun(key: ExtractionRunKey): ExtractionRun =
        findRun(key) ?: throw IllegalStateException(
            "run ${key.runRef.runId} in context ${key.contextId.value} was written but does not read back",
        )

    // ---- plumbing ----

    private fun keyBindings(key: ExtractionRunKey): Map<String, Any?> = mapOf(
        "contextId" to key.contextId.value,
        "runId" to key.runRef.runId,
    )

    private fun execute(statement: String, bindings: Map<String, Any?>) {
        persistenceManager.execute(QuerySpecification.withStatement(statement).bind(bindings))
    }

    private fun queryRows(statement: String, bindings: Map<String, Any?>): List<Map<*, *>> {
        @Suppress("UNCHECKED_CAST")
        val spec = QuerySpecification.withStatement(statement).bind(bindings) as QuerySpecification<Any>
        return persistenceManager.query(spec).filterIsInstance<Map<*, *>>()
    }

    private fun singleRow(statement: String, bindings: Map<String, Any?>): Map<*, *>? =
        queryRows(statement, bindings).firstOrNull()

    private fun requirePositiveLimit(limit: Int) {
        require(limit > 0) { "limit must be positive, was $limit" }
    }

    /**
     * A fresh token for the lock write, on every call.
     *
     * The point is that the write is always a real change. A `SET` of a property to the value it
     * already holds is a no-op a database is free to optimize away, and an optimized-away write is
     * one that took no lock — which would quietly turn the compare-and-set into a read-then-write.
     */
    private fun newLockToken(): String = UUID.randomUUID().toString()

    /**
     * Runs [block] — [save] or [recordInvocation]'s whole write-then-read — under a transaction
     * boundary this store owns whenever it can, and retries wholesale on Neo4j's own deadlock
     * detector, giving every attempt its own fresh transaction.
     *
     * **A caller's transaction is active.** This joins it, once, and does not retry — the same
     * reasoning [transition] documents for a lost compare-and-set race. A deadlock abort inside a
     * shared transaction has already ended that transaction by the time this method could react, so
     * a second attempt run inside it would be a statement rerun against a resource the database has
     * already discarded — a retry needs a boundary it owns, and only the caller holds this one, so
     * the retry is theirs to make. The recovery is theirs too, for the same reason.
     *
     * **No caller transaction.** [txTemplate] opens a genuinely new transaction for every attempt,
     * untouched by whatever a previous attempt did to the one before it; each retry reads whatever
     * the database actually holds at that moment, independent of what this store's own last attempt
     * found before it aborted.
     *
     * Two writers `MERGE`-creating the same not-yet-existing key — a run's first save, or an
     * invocation's first write — can each be granted half of the unique index insert before either
     * commits, which Neo4j resolves by aborting one transaction outright to avoid blocking forever.
     * That abort is transient: [block] carries no state of its own between attempts, so running it
     * again is safe, and is what Neo4j's own documentation recommends for this exact conflict.
     * [isTransientConflict] limits retrying to that one failure shape; anything else — including a
     * genuine [ExtractionRunConflictException] this store raised on purpose — propagates on the
     * first attempt, from whichever shape ran it.
     */
    private fun <T> ownedTransaction(attempts: Int = 5, block: () -> T): T {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return block()
        }
        var lastConflict: RuntimeException? = null
        repeat(attempts) {
            try {
                return txTemplate.execute { block() }!!
            } catch (e: RuntimeException) {
                if (!isTransientConflict(e)) throw e
                lastConflict = e
            }
        }
        throw lastConflict!!
    }

    private companion object {

        private val RUNNING: String = ExtractionRunStatus.RUNNING.name

        /** An invocation record's not-yet-terminal outcome — the one outcome a later write may still
         *  replace in place, on either write door. */
        private val IN_FLIGHT: String = ExtractionInvocationOutcome.IN_FLIGHT.name

        /**
         * Insert-or-update a running header, deciding inside the statement whether to write and,
         * when it does, what version to leave the row at — and never creating the node at all when
         * the decision comes back no.
         *
         * `OPTIONAL MATCH` takes the read, so a run that does not yet exist reaches the rest of the
         * statement as `n IS NULL`, with no node created on its behalf. The first `FOREACH` then
         * takes the exclusive lock — `SET n.casLock`, before any of the properties
         * below are read — but only when `n` exists; a node that is not there yet has nothing to
         * lock, and there is no concurrent reader of a row nobody has written. Without the lock a
         * concurrent write could land between the read below and the write that follows it and be
         * silently overwritten — the `MERGE … SET` failure this store exists to avoid. Everything
         * this statement decides, it decides in this one round trip: a lock taken here and a write
         * issued from a later, separate statement would leave a window between them for another
         * writer to move the row, which is exactly the race this design avoids by never splitting
         * the two.
         *
         * `shouldWrite` is the whole compare-and-set decision, over a `CASE` written once: new
         * (naming version 0); or running, agreeing on lineage and start time, and either the
         * header's content already matches what is stored — a no-op — or it genuinely differs and
         * `$version` names the version currently stored, the accepted case that raises it by one.
         * The caller repeats the same condition in Kotlin, over the same properties this statement
         * returns, to say which half failed when it did not write.
         *
         * The two `FOREACH` clauses that can write are mutually exclusive on `priorStatus`, and each
         * is gated on `shouldWrite` besides: the first only ever runs when there was no row to find,
         * and only creates one when the insert itself is valid, so a first save naming the wrong
         * version leaves no row for a caller catching the resulting exception to find. The second
         * only ever runs against a row this statement already matched, so it can update but it can
         * never originate one. Neither `FOREACH` ever names an `(:ExtractionRunInvocation)`.
         *
         * `priorStartedAt` is compared as the ISO string, which is what round-trips: `Instant.parse`
         * inverts `Instant.toString` exactly, so two equal instants compare equal here.
         */
        private val SAVE_RUN = """
            OPTIONAL MATCH (n:ExtractionRun {contextId: ${'$'}contextId, runId: ${'$'}runId})
            FOREACH (ignored IN CASE WHEN n IS NOT NULL THEN [1] ELSE [] END |
                SET n.casLock = ${'$'}lockToken
            )
            WITH n,
                 n.status AS priorStatus,
                 n.lineageKey AS priorLineageKey,
                 n.startedAt AS priorStartedAt,
                 n.version AS priorVersion,
                 n.headerFingerprint AS priorHeaderFingerprint
            WITH n, priorStatus, priorLineageKey, priorStartedAt, priorVersion, priorHeaderFingerprint,
                 CASE
                     WHEN priorStatus IS NULL THEN ${'$'}version = 0
                     WHEN priorStatus <> ${'$'}running THEN false
                     WHEN priorLineageKey <> ${'$'}lineageKey THEN false
                     WHEN priorStartedAt <> ${'$'}startedAt THEN false
                     WHEN priorHeaderFingerprint = ${'$'}headerFingerprint THEN false
                     ELSE priorVersion = ${'$'}version
                 END AS shouldWrite
            FOREACH (ignored IN CASE WHEN shouldWrite AND priorStatus IS NULL THEN [1] ELSE [] END |
                MERGE (created:ExtractionRun {contextId: ${'$'}contextId, runId: ${'$'}runId})
                SET created += ${'$'}header,
                    created.version = 0,
                    created.headerFingerprint = ${'$'}headerFingerprint,
                    created.casLock = ${'$'}lockToken
            )
            FOREACH (ignored IN CASE WHEN shouldWrite AND priorStatus IS NOT NULL THEN [1] ELSE [] END |
                SET n += ${'$'}header,
                    n.version = priorVersion + 1,
                    n.headerFingerprint = ${'$'}headerFingerprint
            )
            RETURN {
                priorStatus: priorStatus,
                priorLineageKey: priorLineageKey,
                priorStartedAt: priorStartedAt,
                priorVersion: priorVersion,
                priorHeaderFingerprint: priorHeaderFingerprint
            } AS row
        """.trimIndent()

        /**
         * Record one attempt directly, while the run is still running and while the attempt is free
         * of a terminal lock under a different payload — and, the same as [SAVE_RUN], creating the
         * child node only once that decision comes back yes.
         *
         * `MATCH` on the header requires it to exist: a run that does not exist yields no row at
         * all, so the statement writes nothing and the caller raises not-found. `OPTIONAL MATCH` on
         * the invocation takes its read without creating it, the same reason [SAVE_RUN] reads the
         * header that way — a locked write, or a write against a run that has already ended, has to
         * reach no `MERGE` at all, so the rejection it raises finds the graph exactly as this
         * statement found it.
         * `locked` follows the rule [recordInvocation] documents: an id already stored with a
         * terminal `outcome` accepts only a write whose `recordFingerprint` matches what is stored,
         * and every other write for that id is refused, whether it claims a different outcome or the
         * same one under different facts.
         */
        private val RECORD_INVOCATION = """
            MATCH (n:ExtractionRun {contextId: ${'$'}contextId, runId: ${'$'}runId})
            SET n.casLock = ${'$'}lockToken
            WITH n, n.status AS priorStatus
            OPTIONAL MATCH (i:ExtractionRunInvocation {
                contextId: ${'$'}contextId,
                runId: ${'$'}runId,
                invocationIndex: ${'$'}invocationIndex,
                attempt: ${'$'}attempt
            })
            WITH n, priorStatus, i.outcome AS priorOutcome, i.recordFingerprint AS priorFingerprint
            WITH n, priorStatus, priorOutcome,
                 (priorOutcome IS NOT NULL
                    AND priorOutcome <> ${'$'}inFlight
                    AND priorFingerprint <> ${'$'}recordFingerprint) AS locked
            FOREACH (ignored IN CASE WHEN priorStatus = ${'$'}running AND locked = false THEN [1] ELSE [] END |
                MERGE (created:ExtractionRunInvocation {
                    contextId: ${'$'}contextId,
                    runId: ${'$'}runId,
                    invocationIndex: ${'$'}invocationIndex,
                    attempt: ${'$'}attempt
                })
                SET created += ${'$'}record
                MERGE (n)-[:RECORDED]->(created)
            )
            RETURN {priorStatus: priorStatus, priorOutcome: priorOutcome, locked: locked} AS row
        """.trimIndent()

        /**
         * The compare-and-set. One statement, one transaction, and the only writer of a terminal
         * status.
         *
         * Reading in order:
         * 1. `MATCH` — no row means no such run in this tenant, and the caller raises not-found.
         * 2. `SET n.casLock` — takes the exclusive node lock, before any read. See the class KDoc.
         * 3. `OPTIONAL MATCH … ENDED_BY` — the terminal write already recorded, if there is one.
         *    It runs before the `FOREACH`, so it never sees the node this statement is about to
         *    create.
         * 4. `FOREACH` — applies the terminal fields and creates the terminal-write node, but only
         *    when the run was still running when the lock was taken.
         * 5. `RETURN` — the status and fingerprint as they were, which is everything the caller needs
         *    to answer applied, replayed, or conflict.
         *
         * `SET n += $terminal` carries counts and failures only when the transition replaces them. A
         * transition that keeps them binds no such keys, so the stored values stand — which is
         * exactly what `applyTo`'s `counts ?: run.counts` does. Binding null instead would remove the
         * properties.
         *
         * The `CREATE` is the backstop the class KDoc describes: under the uniqueness constraint on
         * `ExtractionRunTerminalWrite(contextId, runId)`, a second terminal write cannot commit even
         * if it somehow read the run as running.
         */
        private val END_RUN = """
            MATCH (n:ExtractionRun {contextId: ${'$'}contextId, runId: ${'$'}runId})
            SET n.casLock = ${'$'}lockToken
            WITH n
            OPTIONAL MATCH (n)-[:ENDED_BY]->(prior:ExtractionRunTerminalWrite)
            WITH n, n.status AS priorStatus, prior.fingerprint AS priorFingerprint
            FOREACH (ignored IN CASE WHEN priorStatus = ${'$'}running THEN [1] ELSE [] END |
                CREATE (n)-[:ENDED_BY]->(:ExtractionRunTerminalWrite {
                    contextId: ${'$'}contextId,
                    runId: ${'$'}runId,
                    fingerprint: ${'$'}fingerprint,
                    status: ${'$'}status,
                    recordedAt: ${'$'}recordedAt
                })
                SET n += ${'$'}terminal
            )
            RETURN {priorStatus: priorStatus, priorFingerprint: priorFingerprint} AS row
        """.trimIndent()

        /** What the winner of a race recorded, read after this transaction's attempt rolled back. */
        private val TERMINAL_WRITE_BY_KEY = """
            MATCH (t:ExtractionRunTerminalWrite {contextId: ${'$'}contextId, runId: ${'$'}runId})
            RETURN {fingerprint: t.fingerprint, status: t.status} AS row
        """.trimIndent()

        /**
         * One run and its attempts, in one round trip.
         *
         * `collect` drops the nulls an `OPTIONAL MATCH` with no match produces, so a run with no
         * attempts comes back with an empty list rather than a list holding a null. The `ORDER BY`
         * ahead of it is what the collected list inherits.
         *
         * **The attempts come back in plan order**, which is the order `invocationsOf` promises. A
         * durable store keeps identified rows, not the order a caller happened to list them in, so
         * plan order is the only order it can offer — and it is the one the run model defines.
         */
        private val RUN_BY_KEY = """
            MATCH (n:ExtractionRun {contextId: ${'$'}contextId, runId: ${'$'}runId})
            OPTIONAL MATCH (n)-[:RECORDED]->(i:ExtractionRunInvocation)
            WITH n, i ORDER BY i.invocationIndex ASC, i.attempt ASC
            WITH n, collect(properties(i)) AS invocations
            RETURN {run: properties(n), invocations: invocations} AS row
        """.trimIndent()

        /**
         * One run's attempts in plan order: call 0 before call 1, and within a call, first attempt
         * before second.
         *
         * The order is the plan's, not the order the calls came back in, which is why it sorts on the
         * identity that was allocated up front. Sorting in the database rather than in Kotlin keeps
         * one definition of plan order for this backend.
         */
        private val INVOCATIONS_OF = """
            MATCH (:ExtractionRun {contextId: ${'$'}contextId, runId: ${'$'}runId})
                  -[:RECORDED]->(i:ExtractionRunInvocation)
            WITH i ORDER BY i.invocationIndex ASC, i.attempt ASC
            RETURN properties(i) AS row
        """.trimIndent()

        /**
         * Every page starts here, with the tenant in the pattern.
         *
         * `startedAtEpochSecond IS NOT NULL` is load-bearing. Neo4j sorts null as the largest value,
         * so a node missing the sort key would sort to the front of a `DESC` order, spend a slot of
         * the caller's `limit`, and then be dropped by the mapper — hiding a good run behind a broken
         * one. Excluding it in the database keeps it out of the order entirely.
         */
        private val PAGE_MATCH = """
            MATCH (n:ExtractionRun {contextId: ${'$'}contextId})
            WHERE n.startedAtEpochSecond IS NOT NULL
        """.trimIndent()

        /** One lineage, from the denormalized root. Supersession is a separate axis and is not walked. */
        private val ONLY_ROOT = "AND n.rootRunId = ${'$'}rootRunId"

        /** One hop down the parent axis. A run with no parent has no such property and cannot match. */
        private val ONLY_PARENT = "AND n.parentRunId = ${'$'}parentRunId"

        /**
         * The `since` bound, inclusive, compared second-then-nanosecond so it is exact.
         *
         * A single truncated millisecond would sweep in runs started just before a bound that falls
         * part-way through one.
         */
        private val SINCE_BOUND = """
            AND (n.startedAtEpochSecond > ${'$'}sinceEpochSecond
                 OR (n.startedAtEpochSecond = ${'$'}sinceEpochSecond
                     AND n.startedAtNano >= ${'$'}sinceNano))
        """.trimIndent()

        /**
         * Newest first by start instant, tie-broken by run id ascending, then limited — in that
         * order, which is the order that makes a page repeatable and correctly scoped.
         *
         * The run order is written twice on purpose. The first `ORDER BY` is the one that matters:
         * it decides which rows the `LIMIT` keeps. The `collect` after it groups by run and does not
         * promise to preserve the incoming order, so the last `ORDER BY` is what the caller actually
         * receives. Dropping either one leaves a page that is right about the wrong thing.
         *
         * The middle `ORDER BY` is the one the collected attempts inherit, so each run's attempts
         * come back in plan order, as they do from [RUN_BY_KEY] and `invocationsOf`.
         */
        private val NEWEST_FIRST_PAGE = """
            WITH n ORDER BY n.startedAtEpochSecond DESC, n.startedAtNano DESC, n.runId ASC
            LIMIT ${'$'}limit
            OPTIONAL MATCH (n)-[:RECORDED]->(i:ExtractionRunInvocation)
            WITH n, i ORDER BY i.invocationIndex ASC, i.attempt ASC
            WITH n, collect(properties(i)) AS invocations
            ORDER BY n.startedAtEpochSecond DESC, n.startedAtNano DESC, n.runId ASC
            RETURN {run: properties(n), invocations: invocations} AS row
        """.trimIndent()
    }
}

/**
 * Best-effort detection of a Neo4j uniqueness-constraint violation anywhere in the cause chain.
 *
 * Matches on message substrings, because which form — the error code or the prose — reaches
 * `getMessage()` is not guaranteed across driver versions. `DrivinePropositionRepository` carries the
 * same check for the same reason; the two are not shared yet because neither module has a home for a
 * Drivine error-mapping helper, and inventing one is a separate change.
 *
 * A false positive is bounded here: the caller only ever treats it as "someone else recorded the
 * terminal write", and re-reads to find out. If there is no terminal write, the original exception is
 * rethrown.
 */
private fun isUniquenessViolation(error: Throwable?): Boolean {
    var current: Throwable? = error
    val seen = mutableSetOf<Throwable>()
    while (current != null && seen.add(current)) {
        val message = current.message ?: ""
        if (message.contains("ConstraintValidationFailed", ignoreCase = true) ||
            message.contains("already exists", ignoreCase = true)
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

/**
 * Best-effort detection of Neo4j's own deadlock detector aborting a transaction, anywhere in the
 * cause chain — the failure [DrivineExtractionRunStore.retryingTransientConflict] retries.
 *
 * Matches on message substrings for the same reason [isUniquenessViolation] does: which form of the
 * message reaches `getMessage()` is not guaranteed across driver versions. Two writers `MERGE`-ing
 * the same not-yet-existing key concurrently is the shape [DrivineExtractionRunStore.ownedTransaction]
 * retries, and Neo4j names it
 * with the transaction lock manager's own vocabulary, a looser target than one stable exception
 * type: `TransientException` is the driver's own class name, and `Deadlock` and `can't acquire` are
 * the wording its lock manager uses to describe the same event. The check is deliberately loose to
 * match all three.
 */
private fun isTransientConflict(error: Throwable?): Boolean {
    var current: Throwable? = error
    val seen = mutableSetOf<Throwable>()
    while (current != null && seen.add(current)) {
        val message = current.message ?: ""
        if (message.contains("TransientException", ignoreCase = true) ||
            message.contains("Deadlock", ignoreCase = true) ||
            message.contains("can't acquire", ignoreCase = true)
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

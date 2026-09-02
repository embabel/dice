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
package com.embabel.dice.spi

import com.embabel.agent.core.ContextId
import com.embabel.dice.projection.lineage.CollectorOutcome
import com.embabel.dice.projection.lineage.CollectorRecord
import com.embabel.dice.projection.lineage.CollectorRecordStore
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.proposition.PropositionStore
import com.embabel.dice.proposition.ProvenanceSubtractionCapable
import com.fasterxml.jackson.annotation.JsonIgnore
import org.slf4j.LoggerFactory
import java.time.Instant

/** Why an undo declined to act — the decision is silent otherwise, since it returns null. */
private val undoLogger = LoggerFactory.getLogger("com.embabel.dice.spi.CollapseUndo")

/**
 * One proposed pair of propositions worth scoring.
 *
 * @property proposalScore An optional score the pair source already computed when proposing
 *   this pair (e.g. vector cosine); a matching scorer may reuse it instead of recomputing.
 *   Null when the source has no score to offer.
 */
data class CandidatePair(
    val anchor: Proposition,
    val member: Proposition,
    val proposalScore: Double? = null,
)

/**
 * Proposes candidate pairs worth scoring, e.g. by vector clustering.
 */
fun interface CandidatePairSource {
    fun propose(candidates: List<Proposition>, contextId: ContextId): List<CandidatePair>
}

/**
 * One signal's score for one pair.
 *
 * @property score The signal's score for this pair.
 * @property veto When true, this signal rejects the merge outright regardless of other signals.
 */
data class CollectorSignalScore(
    val signal: String,
    val score: Double,
    val weight: Double = 1.0,
    val veto: Boolean = false,
    val explanation: String? = null,
    val evidenceRef: String? = null,
)

/**
 * Scores one proposed pair on a single signal. Returning null means abstain — the signal is
 * left out of the blend. Inject dependencies via the constructor, not per call.
 */
fun interface CollectorSignalScorer {
    fun score(pair: CandidatePair, contextId: ContextId): CollectorSignalScore?
}

/**
 * A scored, aggregated edge between two propositions across all signals.
 */
data class CollectorCandidateEdge(
    val anchorId: String,
    val memberId: String,
    val aggregateScore: Double,
    val vetoed: Boolean,
    val signals: List<CollectorSignalScore>,
)

/**
 * A connected component of propositions the collector formed from surviving edges.
 */
data class CollectorComponent(
    val componentId: String,
    val memberIds: List<String>,
)

/**
 * Records everything needed to reverse a collapse: the run it belongs to, the chosen survivor,
 * and for each retired proposition its prior status plus the grounding, provenance and source ids
 * that a merging sweep folded onto the survivor. The [runId] lets a caller find the candidate edges
 * (and their per-signal scores) behind this decision via [CollectorTraceQuery.findEdgesByRun].
 */
data class CollectorDecision(
    val runId: String,
    val componentId: String,
    val survivorId: String,
    val action: String,
    val retired: List<RetiredProposition>,
)

/**
 * One proposition that was folded into a survivor, and what a merging sweep would carry over
 * from it (grounding, provenance and source ids) so the fold can be undone.
 *
 * @property foldedProvenanceRefs Locator keys for the sources this proposition brought that the
 *   survivor was not already citing. A locator key names a source, not a version of it, so this
 *   list can be empty while [foldedProvenanceEvidenceKeys] is not — a loser carrying `r2` of a
 *   document the survivor already cites at `r1` adds no new source. Trace readers display these,
 *   and undo falls back to them when a trace predates evidence keys.
 * @property foldedProvenanceEvidenceKeys One evidence key per entry the fold actually added,
 *   minted by `ProvenanceEvidenceKey`. This is what undo subtracts, and it is what makes an undo
 *   of revisioned evidence exact. Empty on traces recorded before this field existed. Kept out of
 *   JSON, so a trace serialized and read back undoes at locator granularity — JSON is not a round
 *   trip for a trace you intend to undo from.
 */
data class RetiredProposition @JvmOverloads constructor(
    val propositionId: String,
    val priorStatus: PropositionStatus,
    val foldedGrounding: List<String> = emptyList(),
    val foldedProvenanceRefs: List<String> = emptyList(),
    val foldedSourceIds: List<String> = emptyList(),
    @get:JsonIgnore
    val foldedProvenanceEvidenceKeys: List<String> = emptyList(),
)

/**
 * Persists the collector's inspectable decision trace under a run id. In-memory and graph-backed
 * implementations both satisfy this.
 */
interface CollectorTraceStore {
    /**
     * Registers which context a run belongs to, so [deleteTracesForContext] can find and clear
     * that run's rows later. Call this once per run, e.g. before recording its first edge.
     */
    fun recordRunContext(runId: String, contextId: ContextId)

    fun recordCandidateEdges(runId: String, edges: List<CollectorCandidateEdge>)
    fun recordComponents(runId: String, components: List<CollectorComponent>)
    fun recordDecision(runId: String, decision: CollectorDecision)

    /** Erasure hook: deleting a context's data must cascade to its trace rows. */
    fun deleteTracesForContext(contextId: ContextId)
}

/**
 * The read side of the collector trace: look up what a run decided, or explain why one
 * proposition was collapsed. Callers that only need to inspect trace data (e.g. an admin API)
 * should depend on this instead of the concrete storage implementation.
 */
interface CollectorTraceQuery {
    fun findEdgesByRun(runId: String): List<CollectorCandidateEdge>
    fun findDecisionsByRun(runId: String): List<CollectorDecision>
    fun findDecisionForProposition(propositionId: String): CollectorDecision?

    /**
     * The undo-record for one retired member of a collapse: its prior status and exactly the
     * grounding/provenance/source ids a merging sweep folded onto its survivor from it. Looks up
     * the [CollectorDecision] that retired [retiredId] and picks out its entry — a targeted
     * alternative to [findDecisionForProposition] for callers that only want this one member's
     * record, e.g. before undoing a single collapse rather than a whole run.
     *
     * @return null if no decision retired [retiredId] (nothing recorded, or it's a survivor id).
     */
    fun findRetirement(retiredId: String): RetiredProposition? =
        findDecisionForProposition(retiredId)?.retired?.firstOrNull { it.propositionId == retiredId }
}

/**
 * The result of undoing one collapse: the survivor with that member's exclusive evidence
 * subtracted, and the member restored to its prior status.
 */
data class CollapseUndoResult(
    val survivor: Proposition,
    val restored: Proposition,
)

/**
 * Names one collapse to undo, inside the context that owns it.
 *
 * The context is required, and it is the reason this type exists. Undo used to take a survivor id
 * and a retired id and nothing more, so ids picked up anywhere at all could reverse a collapse in a
 * context the caller has no business writing to, and every deployment that cared about that had to
 * bolt its own ownership check on in front. [undoSingleCollapse] now owns the check: both
 * propositions have to live in [contextId], and an id from elsewhere is refused with
 * [CollapseUndoContextMismatchException] before anything is written.
 *
 * @property contextId the context both propositions must belong to
 * @property survivorId the collapse's survivor — must match the decision that retired [retiredId]
 * @property retiredId the one retired member to restore
 */
data class CollapseUndoCommand(
    val contextId: ContextId,
    val survivorId: String,
    val retiredId: String,
) {

    init {
        require(survivorId.isNotBlank()) { "survivorId must not be blank" }
        require(retiredId.isNotBlank()) { "retiredId must not be blank" }
    }
}

/**
 * A proposition the undo was told to touch lives in a different context. Thrown before any write,
 * so the other context's graph is left exactly as it was.
 *
 * @property commandedContextId the context the undo was issued for
 * @property propositionId the proposition that failed the check
 * @property actualContextId the context that proposition really belongs to
 */
class CollapseUndoContextMismatchException(
    val commandedContextId: ContextId,
    val propositionId: String,
    val actualContextId: ContextId,
) : IllegalArgumentException(
    "Proposition $propositionId belongs to context ${actualContextId.value}, and the undo was " +
        "issued for context ${commandedContextId.value}; refusing to touch it",
)

/**
 * The undo was wired with something it cannot work from: no audit records to authorize it, or a
 * proposition store that cannot subtract evidence atomically. Both are configuration mistakes, and
 * both are thrown before the undo reads or writes anything.
 */
class CollapseUndoConfigurationException(message: String) : IllegalStateException(message)

/**
 * Undoes ONE collapse — a single survivor/retired-member pair — without disturbing the run's
 * other collapses. This is the targeted counterpart to a run-level undo: restore exactly the
 * member named by [CollapseUndoCommand.retiredId] to its prior status, and subtract only what it
 * (no other still-retired member of the same collapse) contributed to the survivor.
 *
 * **Undo is destructive, so it fails closed.** Three things must be in place before it reads or
 * writes anything, and a missing one refuses:
 *
 * 1. *The context that owns the collapse.* [command] carries it, and both propositions must live
 *    in it. An id belonging to another context throws [CollapseUndoContextMismatchException] with
 *    that context's graph untouched. The ids reach this function from wherever the caller found
 *    them — a URL path, a UI card — so this is the boundary that keeps one tenant's ids off
 *    another tenant's propositions.
 * 2. *The run's audit records.* [collectorRecords] settles whether the collector really applied
 *    this merge; a null one throws [CollapseUndoConfigurationException]. The trace store cannot
 *    settle it, because it records that a collapse was *proposed*: the strategy writes the
 *    decision during the mark phase, before the runner has decided anything. A dry run, a skipped
 *    merge, and a merge the runner declined all leave a trace that reads exactly like an applied
 *    fold, and reversing one of those strips evidence the survivor holds for its own reasons.
 * 3. *A store that can subtract evidence atomically.* [propositions] must implement
 *    [ProvenanceSubtractionCapable] and answer true to its
 *    [ProvenanceSubtractionCapable.supportsProvenanceSubtraction]; a store that cannot throws
 *    [CollapseUndoConfigurationException]. That interface carries the promise this depends on.
 *
 * Overlap-safe: if another member of the same [CollectorDecision] has not yet been undone and also
 * folded the same grounding/provenance/source id, that ref is left on the survivor — subtracting
 * this member's copy would otherwise strip evidence a sibling merge still needs. Once that sibling
 * is itself undone it holds its own copy again, so the last member to be undone takes the shared
 * ref with it and the survivor lands back on what it had before the collapse. This is why each
 * [RetiredProposition] carries its own folded set rather than the decision carrying one shared
 * union.
 *
 * Evidence comes off the survivor through [ProvenanceSubtractionCapable.subtractProvenance], which
 * names the refs to delete. A persistent backend's ordinary `save` appends provenance and never
 * removes it, so an undo that only saved the reduced proposition would leave the folded evidence on
 * the graph. The subtraction runs first and the survivor's `save` last; the write order below says
 * why. Because the subtraction names what goes and lands in one step, evidence another extraction
 * adds to the survivor while this undo is running survives it.
 *
 * Both propositions are read before anything is written. A missing participant ends the undo with
 * nothing changed, leaving no survivor whose evidence has been subtracted and no member stranded
 * unrestored. So does a collapse the collector never applied.
 *
 * A survivor deleted *between* that read and the subtraction is caught when the subtraction answers
 * null for a proposition the store no longer has. The undo ends there, writing nothing — saving the
 * copy it read would recreate a proposition another writer deleted, with the folded evidence still
 * on it. Nothing is stamped, the member stays retired, and the null return says no restore happened.
 * A caller left with a member retired into a survivor that no longer exists has to decide what that
 * member should be; this function will not guess.
 *
 * Every deletion up to the subtraction's own last look is caught this way, because
 * [ProvenanceSubtractionCapable] promises a store never recreates a proposition it is subtracting
 * from. The window that stays open is the gap between that answer and the survivor's `save` below,
 * which upserts. Closing it needs a conditional write the store contract does not have, and it
 * would have to reach every save in the chain. The residual is written up in
 * `docs/design/source-revisions.md`.
 *
 * **Authorization is three conditions, and all must hold.**
 *
 * 1. *The collector applied this merge, into this survivor.* Settled by [collectorRecords]. They
 *    carry `CollectorRun.dryRun` on the run header and, on each member's record,
 *    `CollectorRecord.mergedIntoId`: the survivor the sweep really folded that member into, written
 *    after the merge was saved. A plain status transition, a skipped merge, and the fallback
 *    retirement a runner performs when a merge target has vanished all leave it null, so none of
 *    them can authorize an undo. A dry run's records name the target it *would* have used, and the
 *    header's `dryRun` flag is what says nothing happened — so a preview never authorizes either.
 * 2. *The undo has not already run.* Settled by [collectorRecords] too: finishing an undo stamps
 *    `CollectorRecord.undoneAt` on the run's record for that member, and a stamped record never
 *    authorizes again. This is what makes a repeat undo a no-op even when the member has been
 *    retired again in the meantime — by a decay sweep, or by a later collector run folding it into
 *    somewhere else. Without the stamp, immortal records plus any new retirement would re-arm the
 *    original undo and let it subtract that run's evidence a second time, taking evidence the
 *    survivor had since re-gained and clobbering the newer retirement.
 * 3. *The merge is still in force.* Settled by the member's status. See [isCurrentlyRetired].
 *
 * Conditions 2 and 3 together make a second undo of the same collapse a no-op, however the member's
 * status has moved since.
 *
 * Three shapes of collapse are refused, all of them correctly, and all of them
 * silent apart from a log line:
 *
 * - *A chain-resolved merge.* With stacked strategies, a member marked into B while B is itself
 *   merged into C is folded onto the terminal survivor C, and the record names C — while the trace
 *   decision still names B. Undoing against B fails the record check, and undoing against C fails
 *   the `require` below as a caller error. Refusing is right either way: the folded evidence keys
 *   were computed against B's evidence, so subtracting them from C would remove the wrong set.
 * - *A member revived without a retry.* Covered below under [isCurrentlyRetired].
 * - *A shadowed decision.* [CollectorTraceQuery.findDecisionForProposition] returns an arbitrary
 *   first match on both shipped stores, so a dry-run preview of a collapse recorded before the real
 *   one can shadow the applied decision. The undo then evaluates the preview's run, finds a dry-run
 *   header, and declines. Conservative, and worth knowing if an undo refuses for no visible reason.
 *
 * Two things worth knowing before relying on this:
 *
 * - **Evidence the survivor re-gained on its own is still subtracted.** A collapse folds an entry,
 *   and afterwards the survivor is independently extracted with the identical entry. Structural
 *   dedup keeps one copy, so nothing distinguishes the re-gained entry from the folded one, and the
 *   undo removes it. Grounding refs and source ids have always behaved this way; evidence now
 *   matches them. An entry differing in any of the six fields — a different revision of the same
 *   source, say — is a different key and is untouched.
 * - **No transaction spans the writes**, so the order is chosen to make every interruption
 *   recoverable. It runs: (1) `subtractProvenance` takes the evidence off, (2) `save` carries
 *   grounding and source ids and fires the persistence event, (3) the `undoneAt` stamp, (4) the
 *   member's restore. The stamp sits between the survivor's writes and the restore deliberately — after the
 *   restore, losing it would leave a finished undo still authorized, and a retry could not repair
 *   that because the member would already be back at its prior status.
 *
 *   | Interrupted after | State | What a retry does |
 *   | --- | --- | --- |
 *   | nothing | untouched | the whole undo |
 *   | (1) | evidence off, grounding on | re-derives against current evidence, so the subtraction is a no-op, then finishes |
 *   | (2) | survivor final, member retired, no stamp | same — re-subtracts nothing, stamps, restores |
 *   | (3) | stamped, member still retired | restores only, touching no evidence |
 *   | (4) | complete | nothing; the stamp refuses |
 *
 *   Steps 1 and 2 are safe to repeat because the subtraction is recomputed from the survivor's
 *   *current* evidence by key, so a ref that is already gone removes nothing.
 *
 * @param command the context, the survivor and the retired member this undo is for
 * @param traceQuery where the collapse decision (and its retired members) is looked up
 * @param propositions where the survivor and retired proposition are read and saved, where each
 *   sibling's current status is checked, and where the folded evidence is subtracted from the
 *   survivor by name. Must be [ProvenanceSubtractionCapable].
 * @param collectorRecords the run's audit records. Required: they are what makes "was this collapse
 *   applied, and has it been reversed already" a recorded fact.
 * @return the updated survivor and restored proposition, or null if nothing was retired under
 *   [CollapseUndoCommand.retiredId] (no trace of this collapse), if either proposition was already
 *   gone when this function read it, if the subtraction reports the survivor gone, or if the
 *   collapse cannot be shown to have been applied. A deletion landing after the subtraction's own
 *   answer goes undetected and the survivor is recreated; see the paragraphs above.
 * @throws CollapseUndoConfigurationException if [collectorRecords] is null, or if [propositions]
 *   cannot subtract evidence atomically — wiring mistakes, caught before anything is read
 * @throws CollapseUndoContextMismatchException if either proposition lives in some context other
 *   than [CollapseUndoCommand.contextId] — caught before anything is written, and ahead of the
 *   survivor-mismatch check, so a caller from another context learns only that it was refused
 * @throws IllegalArgumentException if the retired member was retired into a different survivor from
 *   the one [command] names — a caller error, and no missing-data case. Reached only once both
 *   propositions have been shown to belong to the commanded context, since its message quotes the
 *   survivor the decision names.
 */
fun undoSingleCollapse(
    command: CollapseUndoCommand,
    traceQuery: CollectorTraceQuery,
    propositions: PropositionStore,
    collectorRecords: CollectorRecordStore?,
): CollapseUndoResult? {
    val survivorId = command.survivorId
    val retiredId = command.retiredId
    // Both configuration refusals come first, ahead of every read, so a misconfigured undo cannot
    // get far enough to write. A caller with no records has no way to tell an applied merge from a
    // preview of one, and a store that cannot subtract by name would have to reach for a wholesale
    // replacement of the survivor's evidence, losing whatever arrived since it read.
    val records = collectorRecords ?: throw CollapseUndoConfigurationException(
        "Undoing the collapse of $retiredId into $survivorId needs a CollectorRecordStore to " +
            "authorize it, and none was supplied",
    )
    val subtraction = (propositions as? ProvenanceSubtractionCapable)
        ?.takeIf { it.supportsProvenanceSubtraction }
        ?: throw CollapseUndoConfigurationException(
            "Undoing the collapse of $retiredId into $survivorId needs a store implementing " +
                "ProvenanceSubtractionCapable; ${propositions.javaClass.name} cannot subtract " +
                "evidence atomically",
        )

    val decision = traceQuery.findDecisionForProposition(retiredId) ?: return null
    val retirement = decision.retired.firstOrNull { it.propositionId == retiredId } ?: return null

    // Ownership is settled first, on both propositions this undo writes, and ahead of the
    // survivor-mismatch check below. Order matters for what a foreign caller is told: the mismatch
    // message quotes the survivor the decision really names, so running it first would hand
    // somebody probing with a borrowed member id the id of the survivor in the context that owns
    // it. Checking ownership first means a caller from elsewhere gets the mismatch refusal and
    // nothing more.
    val retiredProposition = propositions.findById(retiredId) ?: return null
    retiredProposition.requireOwnedBy(command)
    val survivor = propositions.findById(survivorId) ?: return null
    survivor.requireOwnedBy(command)

    require(decision.survivorId == survivorId) {
        "Proposition $retiredId was retired into survivor ${decision.survivorId}, not $survivorId"
    }

    // The member must still be retired, whatever else is true: nothing to reverse otherwise.
    if (!retirement.isCurrentlyRetired(retiredProposition)) return null
    val authorization = records.authorize(decision.runId, survivorId, retiredId, retiredProposition)
    if (authorization is UndoAuthorization.Refuse) return null

    // Resuming an undo that was interrupted between its stamp and its restore: the evidence is
    // already off, and re-deriving it here would subtract against a survivor that no longer holds
    // it. Only the restore is outstanding.
    if (authorization is UndoAuthorization.ResumeRestore) {
        val resumed = propositions.save(retiredProposition.withStatus(retirement.priorStatus))
        return CollapseUndoResult(survivor = survivor, restored = resumed)
    }
    val undoMarker = (authorization as UndoAuthorization.Proceed).marker

    // Other members of this same collapse: whatever they also folded must stay on the survivor
    // even though we're subtracting retirement's copy of it — but only while their fold still
    // stands. A sibling whose own undo has run holds its own copy again and has no claim on the
    // survivor's, so counting it would pin the shared evidence to the survivor forever. Both
    // reference forms count, because a sibling recorded before evidence keys existed names its
    // evidence by locator key alone.
    val others = decision.retired.filter {
        it.propositionId != retiredId && it.stillHoldsAClaim(propositions, records, decision.runId)
    }
    val stillNeededGrounding = others.flatMap { it.foldedGrounding }.toSet()
    val stillNeededProvenanceRefs = others
        .flatMap { it.foldedProvenanceEvidenceKeys + it.foldedProvenanceRefs }
        .toSet()
    val stillNeededSourceIds = others.flatMap { it.foldedSourceIds }.toSet()

    val refsToSubtract = retirement.provenanceRefsForUndo().filterNot { it in stillNeededProvenanceRefs }

    // Evidence goes first and by name. The capability deletes exactly these refs in one atomic
    // step, so evidence another writer adds while this undo is running survives it. Naming what
    // stays would replace it away, which is why the store has to promise this before undo will run
    // at all. The save then carries grounding and source ids over the survivor as the subtraction
    // left it, and goes last because save is the write a decorator instruments: the event a
    // listener receives describes the final state.
    //
    // A null answer means the store has no such proposition, so the survivor went away after this
    // function read it. The copy read then is all that is left, and saving it back would recreate
    // what the other writer deleted, folded evidence and all. The deletion wins.
    val subtracted = subtraction.subtractProvenance(survivorId, refsToSubtract)
    if (subtracted == null) {
        undoLogger.warn(
            "Abandoning the undo of {} in run {}: survivor {} was already gone when the subtraction " +
                "ran, so nothing is written and the member stays retired",
            retiredId, decision.runId, survivorId,
        )
        return null
    }
    val updatedSurvivor = propositions.save(
        subtracted.withoutFoldedEvidence(
            groundingToRemove = retirement.foldedGrounding.filterNot { it in stillNeededGrounding },
            provenanceRefsToRemove = emptyList(),
            sourceIdsToRemove = retirement.foldedSourceIds.filterNot { it in stillNeededSourceIds },
        ),
    )

    // The stamp goes between the evidence writes and the restore, which is what makes every crash
    // window recoverable — see the KDoc's crash matrix. Losing it after the restore would leave a
    // finished undo still authorized, and an immediate retry could not repair that because the
    // member would already be back at its prior status.
    records.record(undoMarker.copy(undoneAt = Instant.now()))

    val restored = propositions.save(retiredProposition.withStatus(retirement.priorStatus))

    return CollapseUndoResult(survivor = updatedSurvivor, restored = restored)
}

/**
 * What the run's audit records permit for this collapse: run it, finish an interrupted one, or
 * nothing.
 *
 * The run header must exist and say the run was not a dry run — a missing header refuses, since the
 * caller asked for verification and the store cannot give it. The member must have a record under
 * that run whose outcome is a real transition, and that record's `mergedIntoId` must name this
 * survivor.
 *
 * A record already carrying `undoneAt` normally refuses: that is what stops the same collapse being
 * reversed twice. The exception is the crash window. Undo stamps *before* it restores the member, so
 * a stamped record whose member is still retired means the undo died in between, and the restore is
 * all that is outstanding — see the crash matrix on [undoSingleCollapse].
 *
 * Two signals separate that from "undone long ago, member retired again since". The member must sit
 * exactly where this collapse left it, per the record's `newStatus`; a record without one predates
 * the mechanism and fails the signal rather than satisfying it vacuously. And no other run may have
 * *written* the member at or after the stamp — counted over `TRANSITIONED` and `HARD_DELETED`
 * records from non-dry runs only, since a `SKIPPED` record states that a run deliberately left the
 * member alone and a dry run changes nothing, and treating either as action would strand a member
 * whose undo really is half-finished.
 *
 * Both timestamps are written by whichever host produced them, so the comparison assumes roughly
 * synchronized clocks. On several hosts sharing one store, a re-retirement whose clock runs behind
 * could stamp `at` before `undoneAt` and slip past this check.
 *
 * `mergedIntoId` is the whole point, and nothing here infers anything from marks. `MergingSweepPolicy`
 * merges into the first duplicate mark naming a usable survivor while a proposition may carry marks
 * naming several, so the mark a reader happens to see says nothing about what ran; a
 * `StatusTransitionSweepPolicy` retirement and the fallback retirement the runner performs when a
 * merge target has vanished both transition the member without folding anything, and both leave
 * `mergedIntoId` null. `DefaultCollectorRunner` writes the field on the member's records only after
 * the merge has been saved, and writes the same value on every one of them — which is what makes
 * this answer identical on a store that keeps one row per (proposition, run), as the Drivine store
 * does, and one that keeps a row per mark, as the in-memory store does.
 *
 * The distinct-value check below is therefore defensive rather than load-bearing: a run cannot
 * currently produce two different applied targets for one member, because it retires each member
 * once. It stays because the alternative on a broken invariant is silently picking one, and it logs
 * what it saw.
 */
/** Outcomes that mean a run actually wrote to the proposition, rather than previewing or passing. */
private val WRITING_OUTCOMES = setOf(CollectorOutcome.TRANSITIONED, CollectorOutcome.HARD_DELETED)

private sealed interface UndoAuthorization {

    /** Run the whole undo. [marker] is the live record that authorized it, and the one to stamp. */
    data class Proceed(val marker: CollectorRecord) : UndoAuthorization

    /** An interrupted undo: the evidence is already off, only the member's restore is outstanding. */
    data object ResumeRestore : UndoAuthorization

    data object Refuse : UndoAuthorization
}

private fun CollectorRecordStore.authorize(
    runId: String,
    survivorId: String,
    retiredId: String,
    retiredProposition: Proposition,
): UndoAuthorization {
    val run = findRun(runId) ?: return UndoAuthorization.Refuse
    if (run.dryRun) return UndoAuthorization.Refuse
    val forThisRun = findByProposition(retiredId).filter { it.runId == runId }
    val transitioned = forThisRun.filter { it.outcome == CollectorOutcome.TRANSITIONED }
    val appliedTargets = transitioned.mapNotNull { it.mergedIntoId }.distinct()
    if (appliedTargets.size > 1) {
        undoLogger.warn(
            "Refusing to undo collapse of {} in run {}: its records disagree about which survivor " +
                "the sweep merged it into ({})",
            retiredId, runId, appliedTargets,
        )
        return UndoAuthorization.Refuse
    }
    if (appliedTargets.singleOrNull() != survivorId) return UndoAuthorization.Refuse

    // A store that appends leaves the original record beside the stamped one, so one stamp anywhere
    // in the run's records settles it for the whole collapse.
    val stamped = forThisRun.firstOrNull { it.undoneAt != null }
        ?: return UndoAuthorization.Proceed(transitioned.first { it.mergedIntoId == survivorId })

    // Stamped, and the caller has already established the member is still retired. Either this undo
    // died between its stamp and its restore, or it finished long ago and something retired the
    // member again afterwards. Two things have to hold for the first reading.
    //
    // The member must sit exactly where this collapse left it. A record with no newStatus predates
    // this mechanism and cannot say where that was, so it fails the signal rather than passing it
    // vacuously — refusing costs a stranded member, accepting would resume against a re-retirement
    // to any status at all.
    val undoneAt = stamped.undoneAt
    val leftHere = stamped.newStatus != null && retiredProposition.status == stamped.newStatus

    // And no other run may have written the member since the stamp. Only records evidencing a real
    // write count: a SKIPPED record is the literal statement that a run left the member alone, and
    // a dry run changes nothing by definition, so counting either would strand a member whose undo
    // is genuinely half-finished. Both timestamps come from whichever host wrote them, so this
    // assumes roughly synchronized clocks across hosts sharing one store.
    val actedOnSince = undoneAt != null && findByProposition(retiredId).any { record ->
        record.runId != runId &&
            !record.at.isBefore(undoneAt) &&
            record.outcome in WRITING_OUTCOMES &&
            findRun(record.runId)?.dryRun == false
    }
    if (!leftHere || actedOnSince) {
        undoLogger.debug(
            "Collapse of {} in run {} was already undone; its member has since been retired again",
            retiredId, runId,
        )
        return UndoAuthorization.Refuse
    }
    undoLogger.info(
        "Completing an interrupted undo of {} in run {}: evidence already subtracted, restoring the member",
        retiredId, runId,
    )
    return UndoAuthorization.ResumeRestore
}

/**
 * Whether [proposition] is folded into its survivor right now, judged by status alone.
 *
 * Retiring a member moves it off the status its decision wrote down, and restoring it puts that
 * status back, so a member still carrying [RetiredProposition.priorStatus] is holding nothing for
 * the survivor — either the collapse was never applied, or it has already been undone.
 *
 * Note what it does *not* say: "still retired *by this collapse*". A member retired again later by
 * anything at all — a decay sweep, a second collector run folding it somewhere else — satisfies it
 * just as well. That is why the completion signal is the `undoneAt` stamp, which says so directly.
 *
 * The cost of leaning on it is one false refusal. A member that was genuinely retired, whose undo
 * has not run, and which has since revived to its prior status reads as never retired. Refusing
 * loses a legitimate undo and leaves the survivor's evidence alone; accepting would subtract
 * evidence for a collapse that may never have been applied. Silent data loss is the worse outcome,
 * so refusal wins the tie. A caller that hits this can reinstate the fold and undo it again, or
 * remove the evidence directly.
 *
 * The same predicate decides whether a *sibling* still holds a claim on shared evidence, and there
 * it is the only signal available: the records say nothing about which siblings have been undone.
 */
private fun RetiredProposition.isCurrentlyRetired(proposition: Proposition): Boolean =
    proposition.status != priorStatus

/**
 * Whether this sibling's fold still stands, so the survivor must keep holding what it contributed.
 *
 * The first answer is the sibling's own `undoneAt` for *this* run: once its undo has run it holds
 * its own copy again, and nothing that happens to it afterwards changes that. Status alone gets this
 * wrong in a way that pins evidence permanently — a sibling undone and then re-retired by a later
 * run reads as still participating, so the shared evidence is retained even though both original
 * folds are reversed and the survivor never returns to its pre-collapse state. Where the records say
 * nothing about the sibling, status is the fallback: the claim holds while the sibling sits off its
 * prior status, for whatever reason.
 *
 * A sibling that has been deleted counts as still holding its claim either way: the survivor's copy
 * of its evidence is then the only one left, and dropping it would erase the evidence with nobody
 * left to hand it back to.
 */
private fun RetiredProposition.stillHoldsAClaim(
    propositions: PropositionStore,
    collectorRecords: CollectorRecordStore,
    runId: String,
): Boolean {
    if (collectorRecords.undoneInRun(propositionId, runId)) return false
    val current = propositions.findById(propositionId) ?: return true
    return isCurrentlyRetired(current)
}

/**
 * Stops the undo unless this proposition lives in the context [command] names.
 *
 * Undo takes ids from whatever handed them to it, so without this a caller could name a survivor in
 * one context and reverse a collapse in another. The check runs on both propositions before either
 * is written, so a refusal leaves the other context exactly as it was.
 */
private fun Proposition.requireOwnedBy(command: CollapseUndoCommand) {
    if (contextId != command.contextId) {
        undoLogger.warn(
            "Refusing to undo the collapse of {} into {}: proposition {} belongs to context {}, " +
                "and the undo was issued for context {}",
            command.retiredId, command.survivorId, id, contextId.value, command.contextId.value,
        )
        throw CollapseUndoContextMismatchException(
            commandedContextId = command.contextId,
            propositionId = id,
            actualContextId = contextId,
        )
    }
}

/** Whether this run's fold of [propositionId] has already been reversed. */
private fun CollectorRecordStore.undoneInRun(propositionId: String, runId: String): Boolean =
    findByProposition(propositionId).any { it.runId == runId && it.undoneAt != null }

/**
 * The references undo subtracts from the survivor. Evidence keys, when the trace has them, name
 * every entry the fold added; the locator keys beside them would only repeat that less precisely.
 * A trace with no evidence keys was written before they existed, and its locator keys are all
 * there is — those match revisionless evidence only, so an old trace never reaches a revision it
 * never saw.
 */
private fun RetiredProposition.provenanceRefsForUndo(): List<String> =
    foldedProvenanceEvidenceKeys.ifEmpty { foldedProvenanceRefs }

/**
 * Groups proposition ids into connected components from scored, non-vetoed edges.
 */
interface ConnectedComponentsFinder {
    /**
     * @return propositionId -> componentId, one entry per id in [propositionIds].
     */
    fun findComponents(
        runId: String,
        propositionIds: Set<String>,
        edges: List<CollectorCandidateEdge>,
    ): Map<String, String>
}

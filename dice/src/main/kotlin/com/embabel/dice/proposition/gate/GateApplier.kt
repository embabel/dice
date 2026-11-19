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
package com.embabel.dice.proposition.gate

import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.common.PropositionRejected
import com.embabel.dice.common.PropositionRoutedToReview
import com.embabel.dice.pipeline.ChunkPropositionResult
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.revision.RevisionResult
import org.jetbrains.annotations.ApiStatus
import java.util.Collections

/**
 * Where the host sends a result its gates routed to review.
 *
 * The applier calls it while [GateApplier.apply] runs, once per reviewed result, in the order the
 * results appear in the chunk. Whatever it throws reaches the caller of `apply`.
 *
 * EXPERIMENTAL. The API may change before 1.0.
 */
@ApiStatus.Experimental
fun interface ReviewSink {

    /**
     * Takes one reviewed result.
     *
     * @param verdict The whole result, with every proposition it would have written.
     */
    fun review(verdict: ResultVerdict)
}

/**
 * Builds the context the gates see for one result.
 *
 * It is handed the claim the gates are about to judge, so a host can read that claim's own
 * metadata, such as a trust score, even when no revision ran. The default gives
 * `GateContext(revisionResult = result)`. A host that wants a trust score or its own metadata in
 * front of the gates supplies its own factory.
 *
 * EXPERIMENTAL. The API may change before 1.0.
 */
@ApiStatus.Experimental
fun interface GateContextFactory {

    /**
     * Builds the gate context for one result.
     *
     * @param claim The proposition the gates are about to judge for this result.
     * @param result The revision result being gated, or null when the chunk had no revision.
     * @return The context handed to every gate for that result.
     */
    fun contextFor(claim: Proposition, result: RevisionResult?): GateContext
}

/**
 * What the gates decided about one whole result in a chunk.
 *
 * A result is one revision result, or one proposition when the chunk had no revision. The
 * decision covers every proposition the result would write, so a rejected contradiction drops
 * both the demoted original and the new claim.
 *
 * EXPERIMENTAL. The API may change before 1.0.
 *
 * @property revisionResult The revision result the verdict is for, or null when no revision ran.
 * @property propositions Every proposition the result would write.
 * @property gated What the pipeline said about the claim it was asked about. A result held back
 *   because another result for the same stored proposition was withheld keeps the gates' own answer
 *   here, so it can be milder than [decision].
 * @property decision The decision that applies to the whole result.
 */
@ApiStatus.Experimental
class ResultVerdict(
    val revisionResult: RevisionResult?,
    propositions: List<Proposition>,
    gated: List<GatedPropositionResult>,
    val decision: GateDecision,
) {

    val propositions: List<Proposition> = Collections.unmodifiableList(ArrayList(propositions))

    val gated: List<GatedPropositionResult> = Collections.unmodifiableList(ArrayList(gated))

    /** True unless the result was rejected or routed to review; demoted and skipped results are admitted. */
    val isAdmitted: Boolean
        get() = decision !is GateDecision.Reject && decision !is GateDecision.RouteToReview

    override fun toString(): String =
        "ResultVerdict(revisionResult=${revisionResult?.let { it::class.simpleName }}, " +
            "propositions=${propositions.map { it.id }}, decision=$decision)"
}

/**
 * A chunk after its gates ran: the part to persist, plus a verdict for each result that was gated.
 *
 * EXPERIMENTAL. The API may change before 1.0.
 *
 * @property admitted The chunk to persist. It holds only admitted results and the replays that
 *   followed them; a failed chunk comes back as it was.
 * @property verdicts One verdict per gated result, in chunk order. Replays get none.
 */
@ApiStatus.Experimental
class GatedChunk(
    val admitted: ChunkPropositionResult,
    verdicts: List<ResultVerdict>,
) {

    val verdicts: List<ResultVerdict> = Collections.unmodifiableList(ArrayList(verdicts))

    /**
     * The results the gates rejected.
     *
     * @return The rejected verdicts, in chunk order.
     */
    fun rejected(): List<ResultVerdict> = verdicts.filter { it.decision is GateDecision.Reject }

    /**
     * The results the gates routed to review.
     *
     * @return The reviewed verdicts, in chunk order.
     */
    fun reviewed(): List<ResultVerdict> = verdicts.filter { it.decision is GateDecision.RouteToReview }

    override fun toString(): String =
        "GatedChunk(chunk=${admitted.chunkId}, verdicts=${verdicts.size}, " +
            "rejected=${rejected().size}, reviewed=${reviewed().size})"
}

/**
 * Runs a gate pipeline over one extraction chunk and hands back the chunk to persist.
 *
 * Gates are opt-in: nothing in extraction calls this. A host that wants its gates to decide what
 * gets written calls [apply] on each chunk result before persisting it, and persists
 * [GatedChunk.admitted] instead of the original. Apply it to each chunk on its own; it does not
 * look across chunks.
 *
 * A verdict covers a whole revision result. The pipeline is asked about the one claim the
 * extraction brought in: the new proposition, the revised copy of a merge or reinforcement, the
 * new side of a contradiction, or the generalization. The demoted original of a contradiction is
 * never gated itself, so its lowered confidence cannot sink the result. Whatever the pipeline
 * decides for that claim applies to everything the result would write. Reject and review drop
 * the whole result; Demote and SkipProjection keep it and leave the decision on the verdict for
 * the host to act on. When the chunk had no revision, each proposition is its own result.
 *
 * Results that write a copy of the same stored proposition stand or fall together. The reviser
 * builds each result on the copies other results in the chunk produced, and the order it returns
 * them in does not show which came first, so the applier groups every merge, reinforcement and
 * contradiction by the stored proposition it writes, wherever it sits in the chunk. Each result is
 * still gated on its own. If any of them is rejected or reviewed, the rest of the group would
 * write evidence the gates held back, so they are withheld too: one that passed its own gates is
 * routed to review with a reason naming the stored proposition and what happened to the other
 * result, never rejected, because its claim may be fine on its own. It gets its own verdict, goes
 * to the review sink and sends events like any other reviewed result. Results that add a new
 * proposition, such as a generalization, belong to no group.
 *
 * A contradiction against a pinned proposition writes the original back unchanged, so it only
 * joins the group when that original already differs, in provenance or confidence, from the
 * earliest copy of the stored proposition in the chunk. Otherwise it carries nobody else's
 * evidence: only its new claim is gated, and it neither withholds nor is withheld by the others.
 *
 * Replays (a merge or reinforcement that changed nothing) are never gated themselves and get no
 * verdict, sink call or event. A replay can start from the copy another result in the same chunk
 * just produced, so it follows its group: when any result in it is rejected or reviewed, the
 * replay is dropped silently, or it would write the evidence the gates held back. A replay with
 * no such result in the chunk is kept.
 *
 * With a listener set, each proposition of a rejected result gets a [PropositionRejected] event
 * and each proposition of a reviewed one a [PropositionRoutedToReview], carrying the decision's
 * reason. If the same gates are also wrapped in [ObservableGate], those wrappers send their own
 * per-gate events too, so a listener on both sees each decision twice.
 *
 * Gating happens after extraction, so it cannot take back anything that already happened: events
 * sent before persisting, and what later chunks in the same run already saw of these results, stay
 * as they are.
 *
 * ```kotlin
 * val applier = GateApplier.of(ExtractionGatePipeline(listOf(ConfidenceGate(0.5))))
 *     .withReviewSink { verdict -> inbox.add(verdict) }
 *     .withListener(listener)
 * for (chunk in results.chunkResults) {
 *     applier.apply(chunk).admitted.persist(propositionRepository, entityRepository)
 * }
 * ```
 *
 * EXPERIMENTAL. The API may change before 1.0.
 */
@ApiStatus.Experimental
class GateApplier private constructor(
    private val pipeline: ExtractionGatePipeline,
    private val reviewSink: ReviewSink?,
    private val listener: DiceEventListener?,
    private val contextFactory: GateContextFactory,
) {

    /**
     * Returns an applier that sends reviewed results to [sink].
     *
     * @param sink Called once per reviewed result while [apply] runs.
     * @return A new applier; this one is unchanged.
     */
    fun withReviewSink(sink: ReviewSink): GateApplier = GateApplier(pipeline, sink, listener, contextFactory)

    /**
     * Returns an applier that tells [listener] about rejected and reviewed propositions.
     *
     * @param listener Receives the events.
     * @return A new applier; this one is unchanged.
     */
    fun withListener(listener: DiceEventListener): GateApplier = GateApplier(pipeline, reviewSink, listener, contextFactory)

    /**
     * Returns an applier that builds each gate context with [factory].
     *
     * @param factory Builds the context for each result from the claim being gated and its revision result.
     * @return A new applier; this one is unchanged.
     */
    fun withContext(factory: GateContextFactory): GateApplier = GateApplier(pipeline, reviewSink, listener, factory)

    /**
     * Gates every result in [result] and returns what to persist plus a verdict for each.
     *
     * Every result is gated first, in chunk order; then verdicts are settled, so a result that shares
     * a stored proposition with a withheld one is withheld too, and the review sink and listener are
     * called in chunk order. Exceptions from a gate or from the review sink are not caught.
     *
     * @param result One chunk's extraction result.
     * @return The chunk to persist and the verdicts; a failed chunk comes back unchanged with none.
     */
    fun apply(result: ChunkPropositionResult): GatedChunk {
        val success = result as? ChunkPropositionResult.Success ?: return GatedChunk(result, emptyList())
        return if (success.revisionResults.isEmpty()) applyWithoutRevision(success) else applyToRevisions(success)
    }

    private fun applyWithoutRevision(chunk: ChunkPropositionResult.Success): GatedChunk {
        val verdicts = chunk.propositions.map { judge(null, it, listOf(it)) }
        val kept = verdicts.filter { it.isAdmitted }.flatMap { it.propositions }
        return GatedChunk(chunk.copy(propositions = kept), verdicts)
    }

    private fun applyToRevisions(chunk: ChunkPropositionResult.Success): GatedChunk {
        val results = chunk.revisionResults
        // Order says nothing about which result built on which: the reviser settles its fast-path
        // merges first and hands the results back in input order. So every result that writes a
        // copy of the same stored proposition is grouped before anything is decided.
        val groups = groupIds(results)
        val gated = results.map { revision ->
            if (revision.isReplay) null else gatedClaim(revision).let { pipeline.evaluate(it, contextFactory.contextFor(it, revision)) }
        }
        // The first result each group was withheld for, in chunk order.
        val withheldGroups = mutableMapOf<String, GateDecision>()
        results.indices.forEach { i ->
            val decision = gated[i]?.finalDecision ?: return@forEach
            val group = groups[i] ?: return@forEach
            if (withholds(decision)) withheldGroups.putIfAbsent(group, decision)
        }
        val verdicts = mutableListOf<ResultVerdict>()
        val kept = mutableListOf<RevisionResult>()
        val droppedIds = mutableSetOf<String>()
        results.forEachIndexed { i, revision ->
            val cause = groups[i]?.let { withheldGroups[it] }
            val ownGate = gated[i]
            if (ownGate == null) {
                // A replay follows its group silently.
                if (cause == null) kept += revision
                return@forEachIndexed
            }
            val decision =
                if (cause != null && !withholds(ownGate.finalDecision)) dependentDecision(groups[i]!!, cause) else ownGate.finalDecision
            val verdict = route(ResultVerdict(revision, written(revision), listOf(ownGate), decision))
            verdicts += verdict
            if (verdict.isAdmitted) kept += revision else extractedId(revision)?.let { droppedIds += it }
        }
        // With no revision results left, propositionsToPersist() would fall back to every
        // extracted proposition, so the list has to be emptied as well.
        val keptPropositions = if (kept.isEmpty()) emptyList() else chunk.propositions.filterNot { it.id in droppedIds }
        return GatedChunk(chunk.copy(propositions = keptPropositions, revisionResults = kept), verdicts)
    }

    /** Asks the pipeline about [claim], then reports and routes the result by the decision. */
    private fun judge(revision: RevisionResult?, claim: Proposition, written: List<Proposition>): ResultVerdict {
        val gated = pipeline.evaluate(claim, contextFactory.contextFor(claim, revision))
        return route(ResultVerdict(revision, written, listOf(gated), gated.finalDecision))
    }

    /** Sends the events and the review sink call a verdict calls for. */
    private fun route(verdict: ResultVerdict): ResultVerdict {
        val written = verdict.propositions
        when (val decision = verdict.decision) {
            is GateDecision.Reject -> {
                listener?.let { l -> written.forEach { l.onEvent(PropositionRejected(it, decision.reason)) } }
            }
            is GateDecision.RouteToReview -> {
                listener?.let { l -> written.forEach { l.onEvent(PropositionRoutedToReview(it, decision.reason)) } }
                reviewSink?.review(verdict)
            }
            else -> Unit
        }
        return verdict
    }

    companion object {

        private val DEFAULT_CONTEXT = GateContextFactory { _, result -> GateContext(revisionResult = result) }

        /**
         * An applier for [pipeline] with no review sink, no listener and the default gate context.
         *
         * @param pipeline The gates to run.
         * @return The applier.
         */
        @JvmStatic
        fun of(pipeline: ExtractionGatePipeline): GateApplier = GateApplier(pipeline, null, null, DEFAULT_CONTEXT)

        /** The one claim of a result the pipeline is asked about. */
        private fun gatedClaim(revision: RevisionResult): Proposition = when (revision) {
            is RevisionResult.New -> revision.proposition
            is RevisionResult.Merged -> revision.revised
            is RevisionResult.Reinforced -> revision.revised
            is RevisionResult.Contradicted -> revision.new
            is RevisionResult.Generalized -> revision.proposition
        }

        /** Everything a result writes, the same way propositionsToPersist() reads it. */
        private fun written(revision: RevisionResult): List<Proposition> = when (revision) {
            is RevisionResult.New -> listOf(revision.proposition)
            is RevisionResult.Merged -> listOf(revision.revised)
            is RevisionResult.Reinforced -> listOf(revision.revised)
            is RevisionResult.Contradicted -> listOf(revision.original, revision.new)
            is RevisionResult.Generalized -> listOf(revision.proposition)
        }

        private fun withholds(decision: GateDecision): Boolean =
            decision is GateDecision.Reject || decision is GateDecision.RouteToReview

        /**
         * What a result that passed its own gates gets when another result for the same stored
         * proposition was withheld: review, never rejection, so a claim that may stand on its own
         * is not lost.
         */
        private fun dependentDecision(storedId: String, cause: GateDecision): GateDecision {
            val because = when (cause) {
                is GateDecision.Reject -> "rejected: ${cause.reason}"
                is GateDecision.RouteToReview -> "routed to review: ${cause.reason}"
                else -> cause.toString()
            }
            return GateDecision.RouteToReview("writes stored fact $storedId, which another result in this chunk was withheld for ($because)")
        }

        /**
         * The stored proposition each result writes a copy of, or null when it stands alone.
         *
         * Merges and reinforcements, replays included, belong to the group of the stored
         * proposition they started from, and so does a contradiction that demoted its original. A
         * contradiction against a pinned proposition leaves the original as it was, so it only
         * joins when that original already carries evidence from another result in the chunk,
         * that is, when it differs from the earliest copy of the stored proposition the group saw.
         */
        private fun groupIds(results: List<RevisionResult>): List<String?> {
            val earliest = earliestCopies(results)
            return results.map { revision ->
                when (revision) {
                    is RevisionResult.Merged -> revision.original.id
                    is RevisionResult.Reinforced -> revision.original.id
                    is RevisionResult.Contradicted -> {
                        val original = revision.original
                        val first = earliest[original.id]
                        if (original.pinned && (first == null || evidenceOf(first) == evidenceOf(original))) null else original.id
                    }
                    is RevisionResult.New, is RevisionResult.Generalized -> null
                }
            }
        }

        /**
         * For each stored proposition a merge or reinforcement started from, the copy no other
         * result in the chunk produced: the one the chunk began from.
         */
        private fun earliestCopies(results: List<RevisionResult>): Map<String, Proposition> {
            val steps = results.mapNotNull { revision ->
                when (revision) {
                    is RevisionResult.Merged -> revision.original to revision.revised
                    is RevisionResult.Reinforced -> revision.original to revision.revised
                    else -> null
                }
            }
            val produced = steps.filter { (original, revised) -> original != revised }.map { evidenceOf(it.second) }.toSet()
            val earliest = LinkedHashMap<String, Proposition>()
            steps.forEach { (original, _) ->
                if (evidenceOf(original) !in produced) earliest.putIfAbsent(original.id, original)
            }
            // Should every copy be one another result produced, fall back to the first one seen.
            steps.forEach { (original, _) -> earliest.putIfAbsent(original.id, original) }
            return earliest
        }

        /** What tells two copies of one stored proposition apart: its provenance and its confidence. */
        private fun evidenceOf(proposition: Proposition): List<Any> = listOf(
            proposition.id,
            proposition.confidence,
            proposition.grounding,
            proposition.sourceIds,
            proposition.provenanceEntries,
        )

        /**
         * The id an extracted proposition shares with the result, when there is one. A merge or
         * reinforcement keeps the stored id, so its extracted claim cannot be found by id.
         */
        private fun extractedId(revision: RevisionResult): String? = when (revision) {
            is RevisionResult.New -> revision.proposition.id
            is RevisionResult.Contradicted -> revision.new.id
            is RevisionResult.Generalized -> revision.proposition.id
            is RevisionResult.Merged, is RevisionResult.Reinforced -> null
        }
    }
}

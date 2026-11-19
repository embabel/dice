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
 * The default gives `GateContext(revisionResult = result)`. A host that also wants a trust
 * score or its own metadata in front of the gates supplies its own factory.
 *
 * EXPERIMENTAL. The API may change before 1.0.
 */
@ApiStatus.Experimental
fun interface GateContextFactory {

    /**
     * Builds the gate context for one result.
     *
     * @param result The revision result being gated, or null when the chunk had no revision.
     * @return The context handed to every gate for that result.
     */
    fun contextFor(result: RevisionResult?): GateContext
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
 * @property gated What the pipeline said about the claim it was asked about.
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
 * @property admitted The chunk to persist. It holds only admitted results and replays; a failed
 *   chunk comes back as it was.
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
 * the host to act on. Replays (a merge or reinforcement that changed nothing) are kept as they
 * are, with no verdict. When the chunk had no revision, each proposition is its own result.
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
     * @param factory Builds the context for each result.
     * @return A new applier; this one is unchanged.
     */
    fun withContext(factory: GateContextFactory): GateApplier = GateApplier(pipeline, reviewSink, listener, factory)

    /**
     * Gates every result in [result] and returns what to persist plus a verdict for each.
     *
     * Results are gated in order. Exceptions from a gate or from the review sink are not caught.
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
        val verdicts = mutableListOf<ResultVerdict>()
        val keptResults = mutableListOf<RevisionResult>()
        val droppedIds = mutableSetOf<String>()
        for (revision in chunk.revisionResults) {
            if (revision.isReplay) {
                keptResults += revision
                continue
            }
            val verdict = judge(revision, gatedClaim(revision), written(revision))
            verdicts += verdict
            if (verdict.isAdmitted) {
                keptResults += revision
            } else {
                extractedId(revision)?.let { droppedIds += it }
            }
        }
        // With no revision results left, propositionsToPersist() would fall back to every
        // extracted proposition, so the list has to be emptied as well.
        val keptPropositions =
            if (keptResults.isEmpty()) emptyList() else chunk.propositions.filterNot { it.id in droppedIds }
        return GatedChunk(chunk.copy(propositions = keptPropositions, revisionResults = keptResults), verdicts)
    }

    /** Asks the pipeline about [claim], then reports and routes the result by the decision. */
    private fun judge(revision: RevisionResult?, claim: Proposition, written: List<Proposition>): ResultVerdict {
        val gated = pipeline.evaluate(claim, contextFactory.contextFor(revision))
        val verdict = ResultVerdict(revision, written, listOf(gated), gated.finalDecision)
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

        private val DEFAULT_CONTEXT = GateContextFactory { GateContext(revisionResult = it) }

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

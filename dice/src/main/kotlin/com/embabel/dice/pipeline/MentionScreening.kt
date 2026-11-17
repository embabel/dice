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
package com.embabel.dice.pipeline

import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.filter.MentionFilter
import com.embabel.dice.common.filter.checkedVerdicts
import com.embabel.dice.common.validation.ValidationResult
import com.embabel.dice.proposition.MentionKey
import com.embabel.dice.proposition.SuggestedProposition
import com.embabel.dice.proposition.SuggestedPropositions

/**
 * The result of screening one chunk's mentions with a grounding check and an optional filter.
 *
 * @property result the propositions with the dropped mentions taken off, which is what the chunk keeps
 * @property candidates the propositions whose mentions are offered to the entity resolver
 * @property rejected every rejection, the grounding check's first, then the filter's
 * @property unresolved for each proposition of [result], the positions of the mentions that must
 * stay unresolved, because a check rejected them with [RejectedMentionHandling.LEAVE_UNRESOLVED]
 */
internal data class ScreeningOutcome(
    val result: SuggestedPropositions,
    val candidates: SuggestedPropositions,
    val rejected: List<RejectedMention>,
    val unresolved: List<Set<Int>> = emptyList(),
) {
    /** The grounding rejections in the form the UngroundedMentionsFound event carries. */
    val ungrounded: List<UngroundedMention>
        get() = rejected.filter { it.check == RejectedMention.Check.GROUNDING }.map {
            UngroundedMention(it.propositionText, it.mention, eventReason(it))
        }

    /**
     * Finds the event reason for a grounding rejection. A reason the check gave that is not an
     * event reason becomes BLANK_SPAN for a blank span and SPAN_NOT_IN_PROPOSITION for any other.
     *
     * @param rejection a rejection by the grounding check
     * @return the reason the event reports
     */
    private fun eventReason(rejection: RejectedMention): UngroundedMention.Reason = when {
        rejection.reason == UngroundedMention.Reason.BLANK_SPAN.name -> UngroundedMention.Reason.BLANK_SPAN
        rejection.reason == UngroundedMention.Reason.SPAN_NOT_IN_PROPOSITION.name -> UngroundedMention.Reason.SPAN_NOT_IN_PROPOSITION
        MentionGrounding.isBlank(rejection.mention.span) -> UngroundedMention.Reason.BLANK_SPAN
        else -> UngroundedMention.Reason.SPAN_NOT_IN_PROPOSITION
    }
}

/**
 * Runs a grounding check and an optional filter over one chunk's propositions and works out what
 * stays on the propositions, what goes to the entity resolver, and what was rejected.
 *
 * Mentions are removed by their position in a proposition, so two equal mentions are judged and
 * removed separately. Nothing is copied that does not change.
 */
internal object MentionScreening {

    /**
     * The verdicts on one proposition's mentions, and what they mean for each mention.
     *
     * @property proposition the proposition as extracted
     * @property surviving the positions grounding left in place, which are the mentions the filter saw
     * @property grounding the reasons the grounding check gave, keyed by position in the extracted mentions
     * @property filter the reasons the filter gave, keyed by position in the extracted mentions
     * @property kept the positions that stay on the proposition
     * @property resolvable the positions offered to entity resolution
     * @property unresolved the positions, counted within [kept], that stay on the proposition unresolved
     */
    private class Verdicts(
        val proposition: SuggestedProposition,
        val surviving: List<Int>,
        val grounding: Map<Int, String>,
        val filter: Map<Int, String>,
        groundingHandling: RejectedMentionHandling,
        filterHandling: RejectedMentionHandling,
    ) {
        val kept: List<Int>
        val resolvable: List<Int>
        val unresolved: Set<Int>

        init {
            val dropped = positions(RejectedMentionHandling.DROP, groundingHandling, filterHandling)
            val left = positions(RejectedMentionHandling.LEAVE_UNRESOLVED, groundingHandling, filterHandling) - dropped
            kept = proposition.mentions.indices.filterNot { it in dropped }
            resolvable = kept.filterNot { it in left }
            unresolved = if (left.isEmpty()) emptySet() else kept.withIndex().filter { it.value in left }.map { it.index }.toSet()
        }

        /**
         * Collects the positions that either check rejected under one handling.
         *
         * @param handling the handling to collect
         * @param groundingHandling the grounding check's handling
         * @param filterHandling the filter's handling
         * @return the positions in the extracted mentions
         */
        private fun positions(
            handling: RejectedMentionHandling,
            groundingHandling: RejectedMentionHandling,
            filterHandling: RejectedMentionHandling,
        ): Set<Int> =
            (if (groundingHandling == handling) grounding.keys else emptySet()) +
                (if (filterHandling == handling) filter.keys else emptySet())
    }

    /**
     * Screens the mentions of one chunk's propositions.
     *
     * The filter judges each proposition with the mentions the grounding check dropped already
     * taken off, as the model wrote them. Under DROP, the mentions grounding dropped can pass a
     * suggested id on to the resolver candidates with the same span and type. That is decided once
     * per span and type for the whole chunk, the way resolution works: see [hints].
     *
     * @param chunkId the id of the chunk the pipeline processed, which every rejection carries
     * @param extracted the propositions the extractor returned for the chunk
     * @param context the analysis context handed to both checks
     * @param grounding the check that decides whether a proposition names each mention
     * @param groundingHandling what happens to a mention the grounding check rejects
     * @param filter the mention filter run after grounding, or null for none
     * @param filterHandling what happens to a mention the filter rejects
     * @return the propositions to keep, the candidates for resolution and every rejection
     * @throws IllegalStateException when a check returns a different number of results than mentions
     */
    fun screen(
        chunkId: String,
        extracted: SuggestedPropositions,
        context: SourceAnalysisContext,
        grounding: MentionFilter,
        groundingHandling: RejectedMentionHandling,
        filter: MentionFilter?,
        filterHandling: RejectedMentionHandling,
    ): ScreeningOutcome {
        val dropUngrounded = groundingHandling == RejectedMentionHandling.DROP
        val verdicts = extracted.propositions.map { proposition ->
            val all = proposition.mentions.indices.toList()
            val groundingRejected = rejections(grounding, proposition, all, context)
            val surviving = if (dropUngrounded) all.filterNot { it in groundingRejected } else all
            val filterRejected = filter?.let { rejections(it, keep(proposition, surviving), surviving, context) }.orEmpty()
            Verdicts(proposition, surviving, groundingRejected, filterRejected, groundingHandling, filterHandling)
        }
        val rejected = verdicts.flatMapIndexed { index, verdict ->
            rejectedMentions(chunkId, index, verdict.proposition, verdict.grounding, RejectedMention.Check.GROUNDING, groundingHandling)
        } + verdicts.flatMapIndexed { index, verdict ->
            rejectedMentions(chunkId, index, verdict.proposition, verdict.filter, RejectedMention.Check.FILTER, filterHandling)
        }
        val resultPropositions = verdicts.map { keep(it.proposition, it.kept) }
        val hints = if (dropUngrounded) hints(verdicts, context, filter, filterHandling) else emptyMap()
        val candidatePropositions = verdicts.zip(resultPropositions) { verdict, kept ->
            val resolvable = if (verdict.resolvable.size == verdict.kept.size) kept else keep(verdict.proposition, verdict.resolvable)
            withHints(resolvable, hints)
        }
        val result = rebuild(extracted, resultPropositions)
        val candidates = rebuild(result, candidatePropositions)
        return ScreeningOutcome(result, candidates, rejected, verdicts.map { it.unresolved })
    }

    /**
     * Asks a check about one proposition's mentions and collects the ones it rejects.
     *
     * @param judge the check to ask
     * @param given the proposition handed to the check
     * @param positions the position in the extracted mentions of each mention of [given]
     * @param context the analysis context
     * @return the reason for each rejected mention, keyed by its position in the extracted mentions
     * @throws IllegalStateException when the check returns a different number of results than mentions
     */
    private fun rejections(
        judge: MentionFilter,
        given: SuggestedProposition,
        positions: List<Int>,
        context: SourceAnalysisContext,
    ): Map<Int, String> {
        if (given.mentions.isEmpty()) return emptyMap()
        return judge.checkedVerdicts(given, context).withIndex()
            .mapNotNull { (index, result) -> (result as? ValidationResult.Invalid)?.let { positions[index] to it.reason } }
            .toMap()
    }

    /**
     * Builds the rejection entries for one check on one proposition.
     *
     * @param chunkId the id of the chunk
     * @param propositionIndex the position of the proposition in the extracted list
     * @param proposition the proposition as the check judged it
     * @param reasons the check's reasons, keyed by position in the extracted mentions
     * @param check which check gave the reasons
     * @param handling what happens to a mention that check rejects
     * @return one entry per rejected mention, in mention order
     */
    private fun rejectedMentions(
        chunkId: String,
        propositionIndex: Int,
        proposition: SuggestedProposition,
        reasons: Map<Int, String>,
        check: RejectedMention.Check,
        handling: RejectedMentionHandling,
    ): List<RejectedMention> = reasons.entries.sortedBy { it.key }.map { (position, reason) ->
        RejectedMention(
            chunkId = chunkId,
            propositionIndex = propositionIndex,
            propositionText = proposition.text,
            mention = proposition.mentions[position],
            reason = reason,
            check = check,
            handling = handling,
        )
    }

    /**
     * Keeps the mentions at the given positions of a proposition.
     *
     * @param proposition the proposition
     * @param positions positions in its mentions, in ascending order
     * @return the same proposition when every position is kept, otherwise a copy with only those mentions
     */
    private fun keep(proposition: SuggestedProposition, positions: List<Int>): SuggestedProposition =
        if (positions.size == proposition.mentions.size) proposition
        else proposition.copy(mentions = positions.map { proposition.mentions[it] })

    /**
     * Puts propositions into a chunk's proposition list.
     *
     * @param base the list the propositions came from
     * @param propositions the propositions, one per proposition of [base]
     * @return [base] when every proposition is the same instance, otherwise a copy holding the propositions
     */
    private fun rebuild(base: SuggestedPropositions, propositions: List<SuggestedProposition>): SuggestedPropositions =
        if (propositions.zip(base.propositions).all { (a, b) -> a === b }) base
        else base.copy(propositions = propositions)

    /**
     * Decides, once per span and type for the whole chunk, which suggested id the mentions grounding
     * dropped pass on to the resolver candidates.
     *
     * Resolution gives every mention of one span and type the same id, so the decision is made per
     * span and type, never per proposition. A span and type gets an id only when:
     * - every dropped mention of it that carries an id carries the same one, and that id belongs
     *   to a known entity of the context;
     * - no candidate of it holds an id of its own;
     * - the filter, unless it only reports, accepts the id in every proposition that would receive
     *   it. It is asked about each such proposition with all the passed-on ids in place; an id it
     *   turns down anywhere is withdrawn from the whole chunk, and the propositions are asked again
     *   with the ids that remain until the filter accepts them all.
     *
     * @param verdicts the verdicts on each proposition
     * @param context the analysis context, whose known entities decide which ids may pass on
     * @param filter the mention filter, or null for none
     * @param filterHandling what happens to a mention the filter rejects
     * @return the id to pass on for each span and type
     */
    private fun hints(
        verdicts: List<Verdicts>,
        context: SourceAnalysisContext,
        filter: MentionFilter?,
        filterHandling: RejectedMentionHandling,
    ): Map<MentionKey, String> {
        val knownIds = context.knownEntities.mapNotNull { known -> (known.id as String?)?.takeIf { it.isNotBlank() } }.toSet()
        if (knownIds.isEmpty()) return emptyMap()
        val held = verdicts.flatMap { verdict -> verdict.resolvable.map { verdict.proposition.mentions[it] } }
            .filterNot { it.suggestedId.isNullOrBlank() }
            .map { MentionKey.from(it) }
            .toSet()
        val offered = verdicts.flatMap { verdict -> verdict.grounding.keys.sorted().map { verdict.proposition.mentions[it] } }
            .mapNotNull { mention -> mention.suggestedId?.takeIf { it.isNotBlank() }?.let { MentionKey.from(mention) to it } }
            .groupBy({ it.first }, { it.second })
            .filterKeys { it !in held }
            .mapNotNull { (key, ids) -> ids.distinct().singleOrNull()?.takeIf { it in knownIds }?.let { key to it } }
            .toMap()
        if (offered.isEmpty() || filter == null || filterHandling == RejectedMentionHandling.KEEP) return offered
        val accepted = offered.toMutableMap()
        while (accepted.isNotEmpty()) {
            val refused = verdicts.flatMap { refusedIn(it, accepted, filter, context) }.toSet()
            if (refused.isEmpty()) break
            accepted.keys.removeAll(refused)
        }
        return accepted
    }

    /**
     * Asks the filter about one proposition with the passed-on ids in place and finds the spans
     * and types whose id it turns down.
     *
     * The filter sees the same mentions it judged before, with every id that would reach this
     * proposition added. A span and type is turned down when the filter rejects a mention that
     * took its id. When the filter rejects a mention it had accepted without the ids, every span
     * and type that gave this proposition an id is turned down.
     *
     * @param verdict the verdicts on the proposition
     * @param ids the ids that would be passed on, by span and type
     * @param filter the mention filter
     * @param context the analysis context
     * @return the spans and types whose id is turned down, empty when the proposition takes no id
     */
    private fun refusedIn(
        verdict: Verdicts,
        ids: Map<MentionKey, String>,
        filter: MentionFilter,
        context: SourceAnalysisContext,
    ): Set<MentionKey> {
        val taking = verdict.resolvable
            .filter { MentionKey.from(verdict.proposition.mentions[it]) in ids }
            .associateWith { MentionKey.from(verdict.proposition.mentions[it]) }
        if (taking.isEmpty()) return emptySet()
        val asked = verdict.proposition.copy(mentions = verdict.surviving.map { position ->
            val mention = verdict.proposition.mentions[position]
            taking[position]?.let { mention.copy(suggestedId = ids.getValue(it)) } ?: mention
        })
        val answers = filter.checkedVerdicts(asked, context)
        val refused = mutableSetOf<MentionKey>()
        verdict.surviving.forEachIndexed { index, position ->
            if (answers[index].isValid) return@forEachIndexed
            val key = taking[position]
            when {
                key != null -> refused += key
                position !in verdict.filter -> refused += taking.values
            }
        }
        return refused
    }

    /**
     * Gives a candidate proposition's mentions the ids passed on for their span and type.
     *
     * @param proposition the candidate proposition
     * @param hints the id to pass on for each span and type
     * @return the same proposition when no hint applies, otherwise a copy with the hinted ids
     */
    private fun withHints(proposition: SuggestedProposition, hints: Map<MentionKey, String>): SuggestedProposition {
        if (hints.isEmpty() || proposition.mentions.none { MentionKey.from(it) in hints }) return proposition
        return proposition.copy(mentions = proposition.mentions.map { mention ->
            hints[MentionKey.from(mention)]?.let { mention.copy(suggestedId = it) } ?: mention
        })
    }
}

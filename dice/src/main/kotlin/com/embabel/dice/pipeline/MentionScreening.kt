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
     * @property grounding the reasons the grounding check gave, keyed by position in the extracted mentions
     * @property filter the reasons the filter gave, keyed by position in the extracted mentions
     * @property dropped the positions either check removes from the proposition
     * @property unresolved the positions that stay on the proposition but are not resolved
     */
    private class Verdicts(
        val proposition: SuggestedProposition,
        val grounding: Map<Int, String>,
        val filter: Map<Int, String>,
        groundingHandling: RejectedMentionHandling,
        filterHandling: RejectedMentionHandling,
    ) {
        val dropped: Set<Int> = positions(RejectedMentionHandling.DROP, groundingHandling, filterHandling)
        val unresolved: Set<Int> = positions(RejectedMentionHandling.LEAVE_UNRESOLVED, groundingHandling, filterHandling) - dropped

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
     * taken off, as the model wrote them. Under DROP, a mention that grounding dropped passes its
     * suggested id on when that id belongs to a known entity of the context: the candidates that
     * share its span and type get it when none of them holds an id of its own. The filter is asked
     * again about each candidate given an id that way, and a candidate it rejects keeps no id.
     *
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
            Verdicts(proposition, groundingRejected, filterRejected, groundingHandling, filterHandling)
        }
        val rejected = verdicts.flatMapIndexed { index, verdict ->
            rejectedMentions(extracted.chunkId, index, verdict.proposition, verdict.grounding, RejectedMention.Check.GROUNDING, groundingHandling)
        } + verdicts.flatMapIndexed { index, verdict ->
            rejectedMentions(extracted.chunkId, index, verdict.proposition, verdict.filter, RejectedMention.Check.FILTER, filterHandling)
        }
        val resultPropositions = verdicts.map { verdict ->
            keep(verdict.proposition, verdict.proposition.mentions.indices.filterNot { it in verdict.dropped })
        }
        val candidatePropositions = verdicts.zip(resultPropositions) { verdict, kept ->
            if (verdict.unresolved.isEmpty()) kept
            else keep(verdict.proposition, verdict.proposition.mentions.indices.filterNot { it in verdict.dropped || it in verdict.unresolved })
        }
        val hinted = if (dropUngrounded) withIdHints(candidatePropositions, verdicts, context, filter) else candidatePropositions
        val unresolved = verdicts.map { verdict ->
            if (verdict.unresolved.isEmpty()) emptySet()
            else verdict.proposition.mentions.indices.filterNot { it in verdict.dropped }
                .withIndex().filter { it.value in verdict.unresolved }.map { it.index }.toSet()
        }
        val result = rebuild(extracted, resultPropositions)
        val candidates = rebuild(result, hinted)
        return ScreeningOutcome(result, candidates, rejected, unresolved)
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
     * Gives candidates the suggested id that a mention dropped by grounding carried, when that id
     * belongs to a known entity.
     *
     * The first dropped mention of a span and type with such an id supplies it. A span and type
     * that some candidate already holds with a non-blank id of its own gets no hint. When there is
     * a filter, it is asked about each hinted candidate with the id in place, and a candidate it
     * rejects keeps no id.
     *
     * @param candidates the candidates for resolution, one per proposition
     * @param verdicts the verdicts on each proposition
     * @param context the analysis context, whose known entities decide which ids may pass on
     * @param filter the mention filter, or null for none
     * @return [candidates] when no hint applies, otherwise a copy with the hinted ids
     */
    private fun withIdHints(
        candidates: List<SuggestedProposition>,
        verdicts: List<Verdicts>,
        context: SourceAnalysisContext,
        filter: MentionFilter?,
    ): List<SuggestedProposition> {
        val knownIds = context.knownEntities.mapNotNull { known -> (known.id as String?)?.takeIf { it.isNotBlank() } }.toSet()
        if (knownIds.isEmpty()) return candidates
        val held = candidates.flatMap { it.mentions }
            .filterNot { it.suggestedId.isNullOrBlank() }
            .map { MentionKey.from(it) }
            .toSet()
        val hints = verdicts.flatMap { verdict -> verdict.grounding.keys.sorted().map { verdict.proposition.mentions[it] } }
            .mapNotNull { mention -> mention.suggestedId?.takeIf { it in knownIds }?.let { MentionKey.from(mention) to it } }
            .filterNot { (key, _) -> key in held }
            .distinctBy { (key, _) -> key }
            .toMap()
        if (hints.isEmpty()) return candidates
        return candidates.map { proposition -> withHints(proposition, hints, filter, context) }
    }

    /**
     * Gives one candidate proposition's mentions the ids passed on for their span and type, and
     * lets the filter turn down any of them.
     *
     * The filter is asked about the whole proposition with the ids in place. A hinted mention it
     * rejects keeps no id, and when it rejects any other mention, which it had accepted before,
     * none of the proposition's mentions gets an id.
     *
     * @param proposition the candidate proposition
     * @param hints the id to pass on for each span and type
     * @param filter the mention filter, or null for none
     * @param context the analysis context
     * @return the same proposition when no hint applies, otherwise a copy with the hinted ids
     */
    private fun withHints(
        proposition: SuggestedProposition,
        hints: Map<MentionKey, String>,
        filter: MentionFilter?,
        context: SourceAnalysisContext,
    ): SuggestedProposition {
        val positions = proposition.mentions.indices.filter { MentionKey.from(proposition.mentions[it]) in hints }
        if (positions.isEmpty()) return proposition
        val hinted = proposition.copy(mentions = proposition.mentions.mapIndexed { index, mention ->
            if (index in positions) mention.copy(suggestedId = hints.getValue(MentionKey.from(mention))) else mention
        })
        if (filter == null) return hinted
        // Ask about the whole proposition, so a filter that compares mentions sees the ids together.
        // When an id makes the filter turn down a mention it had accepted, no id is passed on here.
        val verdicts = filter.checkedVerdicts(hinted, context)
        if (verdicts.indices.any { it !in positions && !verdicts[it].isValid }) return proposition
        val accepted = positions.filter { verdicts[it].isValid }.toSet()
        if (accepted.isEmpty()) return proposition
        return proposition.copy(mentions = proposition.mentions.mapIndexed { index, mention ->
            if (index in accepted) hinted.mentions[index] else mention
        })
    }
}

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
     * The verdicts on one proposition's mentions.
     *
     * @property proposition the proposition as extracted
     * @property judged the proposition as the filter judged it, with any recovered ids in place
     * @property grounding the reasons the grounding check gave, keyed by position in the extracted mentions
     * @property filter the reasons the filter gave, keyed by position in the extracted mentions
     */
    private class Verdicts(
        val proposition: SuggestedProposition,
        val judged: SuggestedProposition,
        val grounding: Map<Int, String>,
        val filter: Map<Int, String>,
    )

    /**
     * Screens the mentions of one chunk's propositions.
     *
     * The filter judges each proposition with the mentions the grounding check dropped already
     * taken off. A mention that grounding dropped under DROP passes its suggested id to the
     * remaining mentions that share its span and type when none of them has an id. That happens
     * before the filter runs, so the filter judges the mentions with the ids they will be
     * resolved with.
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
        val checked = extracted.propositions.map { proposition ->
            rejections(grounding, proposition, proposition.mentions.indices.toList(), context)
        }
        val groundingVerdicts = withConflictingIds(extracted.propositions, checked)
        val surviving = extracted.propositions.mapIndexed { index, proposition ->
            val all = proposition.mentions.indices.toList()
            if (dropUngrounded) all.filterNot { it in groundingVerdicts[index] } else all
        }
        val hints = if (dropUngrounded) idHints(extracted.propositions, groundingVerdicts, surviving) else emptyMap()
        val verdicts = extracted.propositions.mapIndexed { index, proposition ->
            val judged = withHints(proposition, hints, surviving[index])
            val filterRejected = filter?.let { rejections(it, keep(judged, surviving[index]), surviving[index], context) }.orEmpty()
            Verdicts(proposition, judged, groundingVerdicts[index], filterRejected)
        }
        val rejected = verdicts.flatMapIndexed { index, verdict ->
            rejectedMentions(extracted.chunkId, index, verdict.proposition, verdict.grounding, RejectedMention.Check.GROUNDING, groundingHandling)
        } + verdicts.flatMapIndexed { index, verdict ->
            rejectedMentions(extracted.chunkId, index, verdict.judged, verdict.filter, RejectedMention.Check.FILTER, filterHandling)
        }
        val resultPropositions = verdicts.map { verdict ->
            val dropped = removed(verdict, RejectedMentionHandling.DROP, groundingHandling, filterHandling)
            keep(verdict.proposition, verdict.proposition.mentions.indices.filterNot { it in dropped })
        }
        val candidatePropositions = verdicts.zip(resultPropositions) { verdict, kept ->
            val dropped = removed(verdict, RejectedMentionHandling.DROP, groundingHandling, filterHandling)
            val unresolved = removed(verdict, RejectedMentionHandling.LEAVE_UNRESOLVED, groundingHandling, filterHandling)
            if ((unresolved - dropped).isEmpty() && verdict.judged === verdict.proposition) kept
            else keep(verdict.judged, verdict.judged.mentions.indices.filterNot { it in dropped || it in unresolved })
        }
        val unresolved = verdicts.map { verdict ->
            val dropped = removed(verdict, RejectedMentionHandling.DROP, groundingHandling, filterHandling)
            val left = removed(verdict, RejectedMentionHandling.LEAVE_UNRESOLVED, groundingHandling, filterHandling) - dropped
            if (left.isEmpty()) emptySet()
            else verdict.proposition.mentions.indices.filterNot { it in dropped }
                .withIndex().filter { it.value in left }.map { it.index }.toSet()
        }
        val result = rebuild(extracted, resultPropositions)
        val candidates = rebuild(result, candidatePropositions)
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
        val results = judge.validateMentions(given, context)
        check(results.size == given.mentions.size) {
            "${judge::class.java.name} returned ${results.size} results for ${given.mentions.size} mentions"
        }
        return results.withIndex()
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
     * Collects the positions that either check rejected under one handling.
     *
     * @param verdict the verdicts on a proposition
     * @param handling the handling to collect
     * @param groundingHandling the grounding check's handling
     * @param filterHandling the filter's handling
     * @return the positions in the extracted mentions
     */
    private fun removed(
        verdict: Verdicts,
        handling: RejectedMentionHandling,
        groundingHandling: RejectedMentionHandling,
        filterHandling: RejectedMentionHandling,
    ): Set<Int> =
        (if (groundingHandling == handling) verdict.grounding.keys else emptySet()) +
            (if (filterHandling == handling) verdict.filter.keys else emptySet())

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
     * Rejects mentions that are grounded only through a known entity when their span and type
     * carry different suggested ids in the chunk.
     *
     * Resolution gives every mention with the same span and type one id, so only one of those ids
     * can survive. A mention whose span is in its own text is grounded whatever id it ends up with.
     * A mention grounded only because its id or name points at a known entity the text names is
     * not, once its span and type might resolve to another entity.
     *
     * @param propositions the propositions as extracted
     * @param checked the grounding check's reasons for each proposition, keyed by mention position
     * @return [checked] when nothing changes, otherwise a copy with the extra rejections
     */
    private fun withConflictingIds(
        propositions: List<SuggestedProposition>,
        checked: List<Map<Int, String>>,
    ): List<Map<Int, String>> {
        val idsByKey = propositions.flatMapIndexed { index, proposition ->
            proposition.mentions.withIndex().filter { it.index !in checked[index] }.map { it.value }
        }
            .mapNotNull { mention -> mention.suggestedId?.takeIf { it.isNotBlank() }?.let { MentionKey.from(mention) to it } }
            .groupBy({ it.first }, { it.second })
        val conflicting = idsByKey.filterValues { it.distinct().size > 1 }.keys
        if (conflicting.isEmpty()) return checked
        return propositions.mapIndexed { index, proposition ->
            val extra = proposition.mentions.withIndex()
                .filter { (position, mention) ->
                    position !in checked[index] &&
                        MentionKey.from(mention) in conflicting &&
                        MentionGrounding.reasonUngrounded(mention.span, proposition.text) != null
                }
                .associate { (position, _) -> position to UngroundedMention.Reason.SPAN_NOT_IN_PROPOSITION.name }
            if (extra.isEmpty()) checked[index] else checked[index] + extra
        }
    }

    /**
     * Finds the suggested ids that mentions grounding dropped can pass on.
     *
     * The first dropped mention of a span and type supplies the id. A span and type that some
     * remaining mention already holds with a non-blank id gets no hint. The id is carried as the
     * model gave it.
     *
     * @param propositions the propositions as extracted
     * @param grounding the grounding check's reasons for each proposition, keyed by mention position
     * @param surviving for each proposition, the positions of the mentions grounding left in place
     * @return the id to pass on for each span and type
     */
    private fun idHints(
        propositions: List<SuggestedProposition>,
        grounding: List<Map<Int, String>>,
        surviving: List<List<Int>>,
    ): Map<MentionKey, String> {
        val held = propositions.flatMapIndexed { index, proposition -> surviving[index].map { proposition.mentions[it] } }
            .filterNot { it.suggestedId.isNullOrBlank() }
            .map { MentionKey.from(it) }
            .toSet()
        return propositions.flatMapIndexed { index, proposition ->
            grounding[index].keys.sorted().map { proposition.mentions[it] }
        }
            .mapNotNull { mention -> mention.suggestedId?.takeIf { it.isNotBlank() }?.let { MentionKey.from(mention) to it } }
            .filterNot { (key, _) -> key in held }
            .distinctBy { (key, _) -> key }
            .toMap()
    }

    /**
     * Gives the mentions at the given positions the ids passed on for their span and type.
     *
     * @param proposition the proposition
     * @param hints the id to pass on for each span and type
     * @param positions the positions of the mentions that may take a hint
     * @return the same proposition when no hint applies, otherwise a copy with the hinted ids
     */
    private fun withHints(
        proposition: SuggestedProposition,
        hints: Map<MentionKey, String>,
        positions: List<Int>,
    ): SuggestedProposition {
        if (hints.isEmpty() || positions.none { MentionKey.from(proposition.mentions[it]) in hints }) return proposition
        val open = positions.toSet()
        return proposition.copy(mentions = proposition.mentions.mapIndexed { index, mention ->
            if (index !in open) mention else hints[MentionKey.from(mention)]?.let { mention.copy(suggestedId = it) } ?: mention
        })
    }
}

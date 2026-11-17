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
import com.embabel.dice.common.resolver.searcher.NormalizedNameCandidateSearcher
import com.embabel.dice.common.validation.ValidationResult
import com.embabel.dice.proposition.MentionKey
import com.embabel.dice.proposition.SuggestedProposition
import com.embabel.dice.proposition.SuggestedPropositions
import org.slf4j.LoggerFactory

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

    private val logger = LoggerFactory.getLogger(MentionScreening::class.java)

    /**
     * The verdicts on one proposition's mentions, and what they mean for each mention.
     *
     * @property proposition the proposition as extracted
     * @property grounding the reasons the grounding check gave, keyed by position in the extracted mentions
     * @property filter the reasons the filter gave, keyed by position in the extracted mentions
     * @property kept the positions that stay on the proposition
     * @property resolvable the positions offered to entity resolution
     * @property unresolved the positions, counted within [kept], that stay on the proposition unresolved
     */
    private class Verdicts(
        val proposition: SuggestedProposition,
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
            Verdicts(proposition, groundingRejected, filterRejected, groundingHandling, filterHandling)
        }
        val rejected = verdicts.flatMapIndexed { index, verdict ->
            rejectedMentions(chunkId, index, verdict.proposition, verdict.grounding, RejectedMention.Check.GROUNDING, groundingHandling)
        } + verdicts.flatMapIndexed { index, verdict ->
            rejectedMentions(chunkId, index, verdict.proposition, verdict.filter, RejectedMention.Check.FILTER, filterHandling)
        }
        val resultPropositions = verdicts.map { keep(it.proposition, it.kept) }
        val hints = if (dropUngrounded) hints(chunkId, verdicts, context) else emptyMap()
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
     * - every dropped mention of it that carries an id carries the same one;
     * - that id belongs to a known entity of the context whose name or an alias is the span,
     *   compared the way [com.embabel.dice.common.filter.GroundedMentionFilter] compares names;
     * - no other span and type would take the same id;
     * - no candidate of it holds an id of its own.
     *
     * So the id can only choose among the known entities that the span itself names, which entity
     * resolution would match by name anyway. It never points a mention at something its own text
     * does not name, and the filter, which has judged every mention as written, is not asked again.
     *
     * @param chunkId the id of the chunk, for the debug line
     * @param verdicts the verdicts on each proposition
     * @param context the analysis context, whose known entities decide which ids may pass on
     * @return the id to pass on for each span and type
     */
    private fun hints(chunkId: String, verdicts: List<Verdicts>, context: SourceAnalysisContext): Map<MentionKey, String> {
        val namesById = context.knownEntities
            .mapNotNull { known ->
                val id = (known.id as String?)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val names = (listOf<String?>(known.name) + known.aliases).filterNotNull().filter { it.isNotBlank() }
                    .map { NormalizedNameCandidateSearcher.normalizeName(it) }
                id to names
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, names) -> names.flatten() }
        if (namesById.isEmpty()) return emptyMap()
        val held = verdicts.flatMap { verdict -> verdict.resolvable.map { verdict.proposition.mentions[it] } }
            .filterNot { it.suggestedId.isNullOrBlank() }
            .map { MentionKey.from(it) }
            .toSet()
        val carried = verdicts.flatMap { verdict -> verdict.grounding.keys.sorted().map { verdict.proposition.mentions[it] } }
            .mapNotNull { mention -> mention.suggestedId?.takeIf { it.isNotBlank() }?.let { MentionKey.from(mention) to it } }
            .groupBy({ it.first }, { it.second })
        val offered = carried
            .mapNotNull { (key, ids) -> ids.distinct().singleOrNull()?.let { key to it } }
            .filter { (key, id) -> namesSpan(namesById[id], key.span) }
        val shared = offered.groupBy({ it.second }, { it.first }).filterValues { it.size > 1 }.keys
        val passed = offered.filter { (key, id) -> id !in shared && key !in held }.toMap()
        if (carried.isNotEmpty()) {
            logger.debug(
                "Chunk {}: dropped mentions carried ids for {} span(s) and type(s), {} passed on, {} withheld",
                chunkId, carried.size, passed.size, carried.size - passed.size,
            )
        }
        return passed
    }

    /**
     * Tells whether one of a known entity's names is the span, after name normalization and
     * ignoring case.
     *
     * @param names the known entity's normalized names, or null when the id is not a known entity's
     * @param span the span of the mentions that would take the id
     * @return true when a name matches
     */
    private fun namesSpan(names: List<String>?, span: String): Boolean {
        if (names.isNullOrEmpty()) return false
        val normalized = NormalizedNameCandidateSearcher.normalizeName(span)
        return normalized.isNotBlank() && names.any { it.equals(normalized, ignoreCase = true) }
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

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

import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.common.filter.MentionFilter
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionExtractor
import com.embabel.dice.proposition.SuggestedProposition
import com.embabel.dice.proposition.SuggestedPropositions
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A test extractor that returns the same propositions for every chunk and records what it was
 * handed. It ignores its mention filter argument, so any filtering a test sees is the pipeline's.
 *
 * The record lists are safe to append to from several threads.
 *
 * @param suggested the propositions returned for every chunk, under that chunk's id
 */
class SuggestingPropositionExtractor(private val suggested: SuggestedPropositions) : PropositionExtractor {

    /** Every proposition list this extractor returned, in call order. */
    val extracted: MutableList<SuggestedPropositions> = CopyOnWriteArrayList()

    /** The mention filter argument of every call to [toSuggestedEntities], in call order. */
    val filtersSeen: MutableList<MentionFilter?> = CopyOnWriteArrayList()

    companion object {

        /**
         * Builds an extractor that returns the given propositions for every chunk.
         *
         * @param propositions the propositions to return
         * @return the extractor
         */
        @JvmStatic
        fun of(vararg propositions: SuggestedProposition): SuggestingPropositionExtractor =
            SuggestingPropositionExtractor(SuggestedPropositions(chunkId = "unset", propositions = propositions.toList()))
    }

    override fun extract(chunk: Chunk, context: SourceAnalysisContext): SuggestedPropositions {
        val result = suggested.copy(chunkId = chunk.id)
        extracted.add(result)
        return result
    }

    override fun toSuggestedEntities(
        suggestedPropositions: SuggestedPropositions,
        context: SourceAnalysisContext,
        sourceText: String?,
        mentionFilter: MentionFilter?,
    ): SuggestedEntities {
        filtersSeen.add(mentionFilter)
        val entities = suggestedPropositions.propositions.flatMap { it.mentions }.map {
            SuggestedEntity(
                labels = listOf(it.type),
                name = it.span,
                summary = it.span,
                chunkId = suggestedPropositions.chunkId,
                id = it.suggestedId,
            )
        }
        return SuggestedEntities(suggestedEntities = entities, sourceText = sourceText)
    }

    override fun resolvePropositions(
        suggestedPropositions: SuggestedPropositions,
        resolutions: Resolutions<SuggestedEntityResolution>,
        context: SourceAnalysisContext,
    ): List<Proposition> {
        val nameToId = resolutions.resolutions.mapNotNull { r -> r.recommended?.let { r.suggested.name.lowercase() to it.id } }.toMap()
        return suggestedPropositions.propositions.map { sp ->
            Proposition(
                contextId = context.contextId,
                text = sp.text,
                mentions = sp.mentions.map { m ->
                    EntityMention(span = m.span, type = m.type, role = MentionRole.valueOf(m.role), resolvedId = nameToId[m.span.lowercase()])
                },
                confidence = sp.confidence,
                grounding = listOf(suggestedPropositions.chunkId),
            )
        }
    }
}

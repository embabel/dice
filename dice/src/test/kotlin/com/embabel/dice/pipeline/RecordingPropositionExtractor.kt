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
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.common.filter.MentionFilter
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionExtractor
import com.embabel.dice.proposition.SuggestedPropositions

/**
 * Keeps every chunk it is given, so a test can check what the pipeline sent. It suggests nothing
 * and resolves each chunk to one proposition grounded in the chunk id, so a test can check the
 * grounding the pipeline adds.
 */
class RecordingPropositionExtractor : PropositionExtractor {

    /** The chunks passed to [extract], in call order. */
    val seenChunks: MutableList<Chunk> = mutableListOf()

    override fun extract(chunk: Chunk, context: SourceAnalysisContext): SuggestedPropositions {
        seenChunks += chunk
        return SuggestedPropositions(chunk.id, emptyList())
    }

    override fun toSuggestedEntities(
        suggestedPropositions: SuggestedPropositions,
        context: SourceAnalysisContext,
        sourceText: String?,
        mentionFilter: MentionFilter?,
    ): SuggestedEntities = SuggestedEntities(suggestedEntities = emptyList(), sourceText = sourceText)

    override fun resolvePropositions(
        suggestedPropositions: SuggestedPropositions,
        resolutions: Resolutions<SuggestedEntityResolution>,
        context: SourceAnalysisContext,
    ): List<Proposition> = listOf(
        Proposition(
            contextId = context.contextId,
            text = "A fact from ${suggestedPropositions.chunkId}",
            mentions = emptyList(),
            confidence = 0.9,
            grounding = listOf(suggestedPropositions.chunkId),
        ),
    )
}

internal fun processTextIsNotNull(pipeline: PropositionPipeline, context: SourceAnalysisContext): ChunkPropositionResult =
    pipeline.processText("t", "s", context)

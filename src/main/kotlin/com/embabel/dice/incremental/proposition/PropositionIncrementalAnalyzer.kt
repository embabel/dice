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
package com.embabel.dice.incremental.proposition

import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.incremental.AbstractIncrementalAnalyzer
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.incremental.IncrementalSource
import com.embabel.dice.incremental.IncrementalSourceFormatter
import com.embabel.dice.incremental.WindowConfig
import com.embabel.dice.pipeline.ChunkPropositionResult
import com.embabel.dice.pipeline.PropositionPipeline
import org.slf4j.LoggerFactory

/**
 * Incremental analyzer that produces [com.embabel.dice.pipeline.ChunkPropositionResult] using a [com.embabel.dice.pipeline.PropositionPipeline].
 *
 * Example usage:
 * ```kotlin
 * val analyzer = PropositionIncrementalAnalyzer(
 *     pipeline = PropositionPipeline.withExtractor(extractor),
 *     historyStore = myHistoryStore,
 *     formatter = MessageFormatter.INSTANCE,
 * )
 *
 * val source = ConversationSource(conversation)
 * val result = analyzer.analyze(source, context)
 * ```
 *
 * @param T The type of items in the source
 * @param pipeline The proposition extraction pipeline
 * @param historyStore Tracks processing history
 * @param formatter Formats source items to text
 * @param config Window and trigger configuration
 */
class PropositionIncrementalAnalyzer<T>(
    private val pipeline: PropositionPipeline,
    historyStore: ChunkHistoryStore,
    formatter: IncrementalSourceFormatter<T>,
    config: WindowConfig = WindowConfig(),
) : AbstractIncrementalAnalyzer<T, ChunkPropositionResult>(historyStore, formatter, config) {

    private val logger = LoggerFactory.getLogger(PropositionIncrementalAnalyzer::class.java)

    override fun processChunk(
        chunk: Chunk,
        context: SourceAnalysisContext,
    ): ChunkPropositionResult = pipeline.processChunk(chunk, context)

    override fun onProcessed(
        source: IncrementalSource<T>,
        startIndex: Int,
        endIndex: Int,
        result: ChunkPropositionResult,
    ) {
        logger.info(
            "Analyzed source {} [{}-{}]: {} propositions extracted",
            source.id, startIndex, endIndex, result.propositions.size
        )
    }
}

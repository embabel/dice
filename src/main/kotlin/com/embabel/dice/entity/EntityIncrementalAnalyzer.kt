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
package com.embabel.dice.entity

import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.incremental.AbstractIncrementalAnalyzer
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.incremental.IncrementalSource
import com.embabel.dice.incremental.IncrementalSourceFormatter
import com.embabel.dice.incremental.WindowConfig
import org.slf4j.LoggerFactory

/**
 * Incremental analyzer that extracts entities (without propositions) using an [EntityPipeline].
 *
 * This is a simpler alternative to [com.embabel.dice.incremental.proposition.PropositionIncrementalAnalyzer]
 * when you only need entity extraction from conversations or other incremental sources.
 *
 * Example usage:
 * ```kotlin
 * val entityExtractor = LlmEntityExtractor.withLlm(llmOptions).withAi(ai)
 * val pipeline = EntityPipeline.withExtractor(entityExtractor)
 *
 * val analyzer = EntityIncrementalAnalyzer(
 *     pipeline = pipeline,
 *     historyStore = myHistoryStore,
 *     formatter = MessageFormatter.INSTANCE,
 * )
 *
 * val source = ConversationSource(conversation)
 * val result = analyzer.analyze(source, context)
 *
 * // Persist extracted entities
 * result?.persist(entityRepository)
 * ```
 *
 * @param T The type of items in the source
 * @param pipeline The entity extraction pipeline
 * @param historyStore Tracks processing history
 * @param formatter Formats source items to text
 * @param config Window and trigger configuration
 */
class EntityIncrementalAnalyzer<T>(
    private val pipeline: EntityPipeline,
    historyStore: ChunkHistoryStore,
    formatter: IncrementalSourceFormatter<T>,
    config: WindowConfig = WindowConfig(),
) : AbstractIncrementalAnalyzer<T, ChunkEntityResult>(historyStore, formatter, config) {

    private val logger = LoggerFactory.getLogger(EntityIncrementalAnalyzer::class.java)

    override fun processChunk(
        chunk: Chunk,
        context: SourceAnalysisContext,
    ): ChunkEntityResult = pipeline.processChunk(chunk, context)

    override fun onProcessed(
        source: IncrementalSource<T>,
        startIndex: Int,
        endIndex: Int,
        result: ChunkEntityResult,
    ) {
        val stats = result.entityExtractionStats
        logger.info(
            "Analyzed source {} [{}-{}]: {} entities extracted ({} new, {} updated)",
            source.id, startIndex, endIndex,
            stats.total, stats.newCount, stats.updatedCount
        )
    }
}

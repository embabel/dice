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
import com.embabel.dice.common.ContentHasher
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.filter.MentionFilter
import com.embabel.dice.common.resolver.ChainedEntityResolver
import com.embabel.dice.common.resolver.InMemoryEntityResolver
import com.embabel.dice.common.resolver.KnownEntityResolver
import com.embabel.dice.common.support.Sha256ContentHasher
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.incremental.ProcessedChunkRecord
import com.embabel.dice.proposition.PropositionExtractor
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.revision.PropositionReviser
import com.embabel.dice.proposition.revision.RevisionResult
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Pipeline for extracting propositions from chunks.
 * Coordinates extraction and entity resolution.
 *
 * Example usage:
 * ```kotlin
 * val pipeline = PropositionPipeline
 *     .withExtractor(LlmPropositionExtractor(ai))
 *     .withRevision(reviser, propositionRepository)  // Optional
 *
 * val result = pipeline.process(chunks, context)
 * ```
 *
 * This pipeline does NOT persist anything. It returns a [PropositionResults]
 * containing all extracted entities and propositions. The caller is responsible for
 * persisting these to the appropriate repositories.
 *
 * When a [PropositionReviser] is configured with a [PropositionRepository], the pipeline
 * will compare new propositions against existing ones and classify them as new, merged,
 * reinforced, or contradicted.
 */
class PropositionPipeline private constructor(
    private val extractor: PropositionExtractor,
    private val reviser: PropositionReviser? = null,
    private val propositionRepository: PropositionRepository? = null,
    private val mentionFilter: MentionFilter? = null,
) {

    companion object {

        /**
         * Create a new pipeline with the given extractor.
         *
         * @param extractor The proposition extractor to use
         * @return A new pipeline instance
         */
        @JvmStatic
        fun withExtractor(extractor: PropositionExtractor): PropositionPipeline =
            PropositionPipeline(extractor)
    }

    private val logger = LoggerFactory.getLogger(PropositionPipeline::class.java)

    /** Whether revision is enabled for this pipeline */
    val hasRevision: Boolean get() = reviser != null

    /**
     * Add a reviser to compare new propositions against existing ones.
     * When enabled, propositions are classified as new, merged, reinforced, or contradicted.
     *
     * @param reviser The proposition reviser to use
     * @param propositionRepository Repository containing existing propositions to compare against
     * @return A new pipeline instance with revision enabled
     */
    fun withRevision(reviser: PropositionReviser, propositionRepository: PropositionRepository): PropositionPipeline =
        PropositionPipeline(extractor, reviser, propositionRepository, mentionFilter)

    /**
     * Add a mention filter to validate entity mentions before creating entities.
     * When enabled, low-quality mentions (vague references, overly long spans, etc.)
     * are filtered out before entity resolution.
     *
     * @param filter The mention filter to use
     * @return A new pipeline instance with mention filtering enabled
     */
    fun withMentionFilter(filter: MentionFilter): PropositionPipeline =
        PropositionPipeline(extractor, reviser, propositionRepository, filter)

    /**
     * Process a single chunk through the pipeline.
     * Extracts propositions and resolves entities.
     *
     * If a reviser is configured, propositions are compared against existing ones
     * and classified. Otherwise, propositions are returned without revision.
     *
     * Note: This method does NOT persist anything. The caller should persist
     * entities and propositions from the returned result.
     *
     * @param chunk The chunk to process
     * @param context Configuration including schema and entity resolver
     * @return Processing result with propositions, entities, and optional revision results
     */
    fun processChunk(
        chunk: Chunk,
        context: SourceAnalysisContext,
    ): ChunkPropositionResult {
        logger.debug("Processing chunk: {}", chunk.id)

        // Step 1: Extract propositions from chunk
        val suggestedPropositions = extractor.extract(chunk, context)
        logger.debug("Extracted {} propositions", suggestedPropositions.propositions.size)

        // Step 2: Convert mentions to suggested entities (include source text for context)
        val suggestedEntities = extractor.toSuggestedEntities(
            suggestedPropositions,
            context,
            chunk.text,
            mentionFilter
        )

        logger.debug("Created {} suggested entities", suggestedEntities.suggestedEntities.size)

        // Step 3: Resolve entities using existing resolver (wrapped with known entities)
        val resolver = KnownEntityResolver.withKnownEntities(context.knownEntities, context.entityResolver)
        val resolutions = resolver.resolve(suggestedEntities, context.schema)
        logger.debug("Resolved {} entities", resolutions.resolutions.size)

        // Step 4: Apply resolutions to create final propositions
        val propositions = extractor.resolvePropositions(suggestedPropositions, resolutions, context)
        logger.debug("Created {} propositions", propositions.size)

        // Step 5: Optionally revise propositions against existing ones
        val revisionResults = if (reviser != null && propositionRepository != null) {
            val results = reviser.reviseAll(propositions, propositionRepository)
            logger.debug(
                "Revised {} propositions: {} new, {} merged, {} reinforced, {} contradicted",
                results.size,
                results.count { it is RevisionResult.New },
                results.count { it is RevisionResult.Merged },
                results.count { it is RevisionResult.Reinforced },
                results.count { it is RevisionResult.Contradicted },
            )
            results
        } else {
            emptyList()
        }

        return ChunkPropositionResult(
            chunkId = chunk.id,
            suggestedPropositions = suggestedPropositions,
            entityResolutions = resolutions,
            propositions = propositions,
            revisionResults = revisionResults,
        )
    }

    /**
     * Process a text once, with hash-based deduplication.
     * Ideal for one-shot ingestion of documents, notes, or other static text.
     *
     * @param text The text to process
     * @param sourceId Identifier for the source (used for chunk metadata and bookmark tracking)
     * @param context Analysis context with schema, entity resolver, etc.
     * @param historyStore Tracks what's been processed; null to skip dedup
     * @param contentHasher Strategy for computing content hashes
     * @return Result with propositions and entities, or null if already processed
     */
    @JvmOverloads
    fun processOnce(
        text: String,
        sourceId: String,
        context: SourceAnalysisContext,
        historyStore: ChunkHistoryStore? = null,
        contentHasher: ContentHasher = Sha256ContentHasher,
        additionalGrounding: List<String> = emptyList(),
    ): ChunkPropositionResult? {
        if (historyStore != null) {
            val hash = contentHasher.hash(text)
            if (historyStore.isProcessed(context.contextId, hash)) {
                logger.debug("Content already processed (hash: {})", hash.take(8))
                return null
            }
            // Use sourceId AS the chunk id, not just parentId. This
            // makes proposition.grounding carry the caller's stable
            // sourceId (e.g. `email:<threadId>`, `file:<name>`,
            // `url:<href>`) instead of an opaque UUID. Downstream
            // wiring (GroundingWiringService) can then resolve that id
            // directly to a stored entity for one-hop provenance.
            // Backward-compatible: callers that pass UUID-shaped
            // sourceIds get UUID chunk ids exactly as before.
            val chunk = Chunk.create(text = text, parentId = sourceId, id = sourceId)
            val result = processChunk(chunk, context)
            historyStore.recordProcessed(
                ProcessedChunkRecord(
                    contextId = context.contextId,
                    contentHash = hash,
                    sourceId = sourceId,
                    startIndex = 0,
                    endIndex = 1,
                    processedAt = Instant.now(),
                )
            )
            return result.withAdditionalGrounding(additionalGrounding)
        }
        // Same id-as-sourceId convention as the history-store branch:
        // proposition.grounding carries the caller's stable sourceId so
        // GroundingWiringService can resolve it to a source entity.
        val chunk = Chunk.create(text = text, parentId = sourceId, id = sourceId)
        return processChunk(chunk, context).withAdditionalGrounding(additionalGrounding)
    }

    /**
     * Process multiple chunks through the pipeline.
     *
     * For cross-chunk entity resolution, the context's EntityResolver is wrapped with
     * an InMemoryEntityResolver via MultiEntityResolver. This ensures entities discovered
     * in earlier chunks can be recognized in later chunks without external persistence.
     *
     * @param chunks The chunks to process
     * @param context Configuration including schema
     * @return Aggregated processing results
     */
    fun process(
        chunks: List<Chunk>,
        context: SourceAnalysisContext,
    ): PropositionResults {
        logger.info("Processing {} chunks{}", chunks.size, if (reviser != null) " with revision" else "")

        // Wrap the resolver with InMemoryEntityResolver for cross-chunk entity resolution.
        // Order: user's resolver first (for pre-existing entities), then in-memory (for this run's entities)
        val crossChunkResolver = ChainedEntityResolver(
            listOf(context.entityResolver, InMemoryEntityResolver())
        )
        val crossChunkContext = context.copy(entityResolver = crossChunkResolver)

        val chunkResults = chunks.map { chunk ->
            processChunk(chunk, crossChunkContext)
        }

        val allPropositions = chunkResults.flatMap { it.propositions }

        val result = PropositionResults(
            chunkResults = chunkResults,
            allPropositions = allPropositions,
        )

        if (result.hasRevision) {
            val stats = result.propositionExtractionStats
            logger.info(
                "Extracted {} propositions from {} chunks: {} new, {} generalized, {} merged, {} reinforced, {} contradicted ({} fully resolved)",
                allPropositions.size,
                chunks.size,
                stats.newCount,
                stats.generalizedCount,
                stats.mergedCount,
                stats.reinforcedCount,
                stats.contradictedCount,
                allPropositions.count { it.isFullyResolved() }
            )
        } else {
            logger.info(
                "Extracted {} propositions from {} chunks ({} fully resolved)",
                allPropositions.size,
                chunks.size,
                allPropositions.count { it.isFullyResolved() }
            )
        }

        return result
    }
}

/**
 * Merge [ids] into the `grounding` of every proposition this result will
 * persist — both the plain extracted list and, when revision ran, the
 * revised propositions actually saved (`revisedPropositionsToPersist`).
 *
 * Used by [PropositionPipeline.processOnce]'s `additionalGrounding` to let a
 * caller ground an answer in the source records it came from, on top of the
 * primary `sourceId`. Grounding ids that resolve to a stored entity become
 * `(:Proposition)-[:GROUNDED_IN]->(:entity)` edges in the downstream grounding
 * pass — the same mechanism the primary `sourceId` already uses. No-op for an
 * empty list (the back-compat default).
 */
internal fun ChunkPropositionResult.withAdditionalGrounding(ids: List<String>): ChunkPropositionResult =
    if (ids.isEmpty()) this
    else copy(
        propositions = propositions.map { it.withGrounding(ids) },
        revisionResults = revisionResults.map { it.withAdditionalGrounding(ids) },
    )

/**
 * Add grounding to the proposition(s) a [RevisionResult] persists. Only the
 * proposition sourced from THIS text is enriched — a `Contradicted`'s
 * pre-existing `original` (whose confidence is merely reduced) keeps its own
 * provenance.
 */
internal fun RevisionResult.withAdditionalGrounding(ids: List<String>): RevisionResult =
    if (ids.isEmpty()) this
    else when (this) {
        is RevisionResult.New -> copy(proposition = proposition.withGrounding(ids))
        is RevisionResult.Merged -> copy(revised = revised.withGrounding(ids))
        is RevisionResult.Reinforced -> copy(revised = revised.withGrounding(ids))
        is RevisionResult.Contradicted -> copy(new = new.withGrounding(ids))
        is RevisionResult.Generalized -> copy(proposition = proposition.withGrounding(ids))
    }

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
import com.embabel.dice.common.DiceEventListener
import com.embabel.dice.common.ExtractionBatchCompleted
import com.embabel.dice.common.PropositionContradicted
import com.embabel.dice.common.PropositionDiscovered
import com.embabel.dice.common.PropositionGeneralized
import com.embabel.dice.common.PropositionMerged
import com.embabel.dice.common.PropositionReinforced
import com.embabel.dice.common.PropositionRoutedToReview
import com.embabel.dice.common.SafeDiceEventListener
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.filter.MentionFilter
import com.embabel.dice.common.resolver.ChainedEntityResolver
import com.embabel.dice.common.resolver.InMemoryEntityResolver
import com.embabel.dice.common.resolver.KnownEntityResolver
import com.embabel.dice.common.support.Sha256ContentHasher
import com.embabel.dice.incremental.BookmarkKey
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.incremental.HashKey
import com.embabel.dice.incremental.ProcessedChunkRecord
import com.embabel.dice.proposition.PropositionExtractor
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.SuggestedPropositions
import com.embabel.dice.proposition.revision.PropositionReviser
import com.embabel.dice.proposition.revision.RevisionResult
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Pipeline for extracting propositions from chunks.
 * Coordinates extraction and entity resolution.
 *
 * ## Construction
 *
 * There is no public constructor. The only entry point is the companion factory
 * [withExtractor], which seeds a pipeline with a [PropositionExtractor]. From there,
 * configure the pipeline with the fluent copy-builders, each of which returns a new
 * instance:
 * - [withRevision] — compare new propositions against existing ones
 * - [withMentionFilter] — drop low-quality entity mentions before resolution
 * - [withEventListener] — observe pipeline events
 * - [withExecutionStrategy] — control how the extraction stage is dispatched
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
 * ## This pipeline does NOT persist anything
 *
 * [process], [processChunk], and [processOnce] all return **UNSAVED** results. The pipeline
 * writes nothing to any repository on its own. To make results durable, the caller MUST
 * persist them explicitly via
 * [PersistablePropositions.persist][PersistablePropositions.persist], passing a
 * `propositionRepository` and a `namedEntityDataRepository`, within the caller's own
 * transaction scope. If you do not call `persist`, nothing is stored — the returned result
 * is discarded when it goes out of scope.
 *
 * When a [PropositionReviser] is configured with a [PropositionRepository], the pipeline
 * will compare new propositions against existing ones and classify them as new, merged,
 * reinforced, or contradicted. Revision still does not persist — the classified results
 * must be persisted by the caller as above.
 */
class PropositionPipeline private constructor(
    private val extractor: PropositionExtractor,
    private val reviser: PropositionReviser? = null,
    private val propositionRepository: PropositionRepository? = null,
    private val mentionFilter: MentionFilter? = null,
    eventListener: DiceEventListener = DiceEventListener.DEV_NULL,
    private val executionStrategy: ExtractionExecutionStrategy = SerialExtractionStrategy,
) {

    /** The original listener as supplied — propagated verbatim through `with*` copies to avoid wrapping it multiple times. */
    private val rawEventListener: DiceEventListener = eventListener

    /** The listener used for all emissions, wrapped in [SafeDiceEventListener] so a throwing listener can never abort a run. */
    private val eventListener: DiceEventListener = SafeDiceEventListener(eventListener)

    companion object {

        /**
         * Starting point for building a pipeline — seed it with a [PropositionExtractor], then
         * chain additional configuration via the `with*` methods.
         *
         * @param extractor the extractor to use for proposition extraction
         * @return a new pipeline instance
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
        PropositionPipeline(extractor, reviser, propositionRepository, mentionFilter, rawEventListener, executionStrategy)

    /**
     * Add a mention filter to validate entity mentions before creating entities.
     * When enabled, low-quality mentions (vague references, overly long spans, etc.)
     * are filtered out before entity resolution.
     *
     * @param filter The mention filter to use
     * @return A new pipeline instance with mention filtering enabled
     */
    fun withMentionFilter(filter: MentionFilter): PropositionPipeline =
        PropositionPipeline(extractor, reviser, propositionRepository, filter, rawEventListener, executionStrategy)

    /**
     * Register a listener for pipeline events.
     *
     * When set, [process] emits one aggregate [ExtractionBatchCompleted] per run, and — when a
     * reviser is also configured — [processChunk] emits one per-proposition "candidate" event per
     * [RevisionResult] (revision-only). These candidate events are **pre-persistence** signals:
     * the canonical durable signal is `PropositionPersisted`, emitted by the repository decorator.
     *
     * Defaults to [DiceEventListener.DEV_NULL]; behavior is unchanged when no listener is set.
     *
     * @param listener The listener to receive [com.embabel.dice.common.DiceEvent]s
     * @return A new pipeline instance with the listener registered
     */
    fun withEventListener(listener: DiceEventListener): PropositionPipeline =
        PropositionPipeline(extractor, reviser, propositionRepository, mentionFilter, listener, executionStrategy)

    /**
     * Set the [ExtractionExecutionStrategy] used to dispatch the stateless per-chunk
     * extraction stage of [process].
     *
     * Defaults to [SerialExtractionStrategy] (the fully sequential default).
     *  [ParallelExtractionStrategy] and [BatchedExtractionStrategy] only affect
     * the extraction stage; the resolver-touching resolution stage always stays serial, so
     * cross-chunk entity identity is preserved regardless of strategy.
     *
     * Enabling Parallel/Batched(`batchSize > 1`) in production is gated on verifying extractor
     * thread-safety — see [ExtractionExecutionStrategy] for the thread-safety verification requirement.
     *
     * @param strategy the execution strategy to use
     * @return A new pipeline instance with the strategy registered
     */
    fun withExecutionStrategy(strategy: ExtractionExecutionStrategy): PropositionPipeline =
        PropositionPipeline(extractor, reviser, propositionRepository, mentionFilter, rawEventListener, strategy)

    /**
     * Carries the resolver-free output of the extraction stage for a single chunk so it can be produced
     * concurrently. The resolution stage picks this up serially to do entity resolution. See [process].
     */
    private data class ExtractionStageResult(
        val chunk: Chunk,
        val suggestedPropositions: SuggestedPropositions,
        val suggestedEntities: SuggestedEntities,
    )

    /**
     * Extraction stage — extract propositions and convert mentions to suggested entities.
     * Touches no entity resolver, so it is safe to run concurrently across chunks.
     */
    private fun extractStage(chunk: Chunk, context: SourceAnalysisContext): ExtractionStageResult {
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

        return ExtractionStageResult(chunk, suggestedPropositions, suggestedEntities)
    }

    /**
     * Resolution stage — resolve entities through the shared cross-chunk resolver, apply resolutions,
     * and optionally revise against existing propositions. Must stay serial so all chunks share
     * the same entity identity within a single [process] run.
     */
    private fun resolveStage(
        extraction: ExtractionStageResult,
        context: SourceAnalysisContext,
    ): ChunkPropositionResult.Success {
        val (chunk, suggestedPropositions, suggestedEntities) = extraction

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
            // Emit one pre-persistence candidate event per RevisionResult (revision-only).
            // These are early signals; the canonical durable event is PropositionPersisted,
            // emitted by the repository decorator when the result is actually saved.
            results.forEach { revision ->
                val event = when (revision) {
                    is RevisionResult.New -> PropositionDiscovered(revision.proposition)
                    is RevisionResult.Merged -> PropositionMerged(revision.original, revision.revised)
                    is RevisionResult.Reinforced -> PropositionReinforced(revision.original, revision.revised)
                    is RevisionResult.Contradicted -> PropositionContradicted(
                        revision.original.contextId,
                        revision.original,
                        revision.new,
                    )
                    is RevisionResult.Generalized -> PropositionGeneralized(
                        revision.proposition,
                        revision.generalizes,
                    )
                }
                eventListener.onEvent(event)
                // A contradiction against a pinned original leaves the pin intact (conflict
                // protection). Surface a review signal alongside the contradiction event so the
                // contested pin can be explicitly resolved or unpinned, rather than silently
                // accumulating contradicting evidence.
                if (revision is RevisionResult.Contradicted && revision.original.pinned) {
                    eventListener.onEvent(
                        PropositionRoutedToReview(
                            revision.original,
                            reason = "pinned proposition contradicted by newer evidence; resolve the conflict or unpin",
                        )
                    )
                }
            }
            results
        } else {
            emptyList()
        }

        return ChunkPropositionResult.Success(
            chunkId = chunk.id,
            suggestedPropositions = suggestedPropositions,
            entityResolutions = resolutions,
            propositions = propositions,
            revisionResults = revisionResults,
        )
    }

    /**
     * Process a single chunk through the pipeline.
     * Extracts propositions and resolves entities.
     *
     * If a reviser is configured, propositions are compared against existing ones
     * and classified. Otherwise, propositions are returned without revision.
     *
     * Note: This method does NOT persist anything — it returns UNSAVED results. The caller
     * must persist the returned entities and propositions via
     * [PersistablePropositions.persist][PersistablePropositions.persist] within its own
     * transaction scope; nothing is written to any repository otherwise.
     *
     * Single-chunk semantics are unchanged: this runs extraction then resolution serially on the
     * calling thread and propagates any extraction exception (it never produces a
     * [ChunkPropositionResult.Failed] — only the batch [process] does).
     *
     * @param chunk The chunk to process
     * @param context Configuration including schema and entity resolver
     * @return Processing result with propositions, entities, and optional revision results
     */
    fun processChunk(
        chunk: Chunk,
        context: SourceAnalysisContext,
    ): ChunkPropositionResult =
        resolveStage(extractStage(chunk, context), context)

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
     *
     * Note: This method does NOT persist anything — it returns UNSAVED results. The caller
     * must persist the returned entities and propositions via
     * [PersistablePropositions.persist][PersistablePropositions.persist] within its own
     * transaction scope; nothing is written to any repository otherwise.
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
            if (historyStore.isProcessed(HashKey(context.contextId, hash))) {
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
                    bookmarkKey = BookmarkKey(context.contextId, sourceId),
                    hashKey = HashKey(context.contextId, hash),
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

        // Extraction stage — resolver-free extraction, dispatched via the execution strategy (parallelizable).
        // A failed extraction yields a null slot; input order is preserved by the strategy.
        // Capture the cause per chunk so we can produce a typed Failed result in the resolution stage.
        // ConcurrentHashMap so a concurrent execution strategy can record failures safely without an
        // external lock; the resolution stage reads it only after every extraction has been joined.
        val failures = ConcurrentHashMap<String, Throwable>()
        val extractions: List<ExtractionStageResult?> =
            executionStrategy.execute(chunks) { chunk ->
                runCatching { extractStage(chunk, crossChunkContext) }
                    .getOrElse { e ->
                        logger.warn("Extraction failed for chunk {}: {}", chunk.id, e.message, e)
                        failures[chunk.id] = e
                        throw e // let the strategy's runCatching map this to a null slot
                    }
            }

        // Resolution stage — always serial, so all chunks share the same entity identity.
        // Resolve each non-null extraction through the shared crossChunkResolver; map null
        // slots to typed ChunkPropositionResult.Failed.
        val chunkResults: List<ChunkPropositionResult> = extractions.mapIndexed { i, extraction ->
            val chunkId = chunks[i].id
            if (extraction != null) {
                // Isolate per-chunk: a throw in resolution, revision, or event emission surfaces
                // as a Failed result for this chunk only, never aborting the whole run.
                runCatching { resolveStage(extraction, crossChunkContext) }
                    .getOrElse { e ->
                        logger.warn("Resolution failed for chunk {}: {}", chunkId, e.message, e)
                        ChunkPropositionResult.failed(chunkId, e)
                    }
            } else {
                val cause = failures[chunkId]
                if (cause != null) {
                    ChunkPropositionResult.failed(chunkId, cause)
                } else {
                    ChunkPropositionResult.Failed(chunkId, "Extraction failed")
                }
            }
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

        // Emit exactly one aggregate event per process() carrying the run's stats.
        eventListener.onEvent(ExtractionBatchCompleted(result.propositionExtractionStats))

        return result
    }
}

/**
 * Adds [ids] to the grounding of every proposition this result will persist — both the
 * plain extracted list and, when revision ran, the revised propositions that will actually
 * be saved.
 *
 * Used by [PropositionPipeline.processOnce]'s `additionalGrounding` so callers can attach
 * the source records a proposition came from, beyond the primary `sourceId`. Grounding ids
 * that resolve to a stored entity become `GROUNDED_IN` edges in the downstream provenance
 * pass, using the same mechanism as the primary `sourceId`. No-op when the list is empty.
 */
internal fun ChunkPropositionResult.withAdditionalGrounding(ids: List<String>): ChunkPropositionResult =
    if (ids.isEmpty()) this
    else when (this) {
        // Only a successful result carries propositions to ground; Failed has nothing to enrich.
        is ChunkPropositionResult.Success -> copy(
            propositions = propositions.map { it.withGrounding(ids) },
            revisionResults = revisionResults.map { it.withAdditionalGrounding(ids) },
        )
        is ChunkPropositionResult.Failed -> this
    }

/**
 * Adds grounding to the proposition(s) a [RevisionResult] will persist. Only the proposition
 * sourced from the current text is enriched — a `Contradicted` result's pre-existing `original`
 * (whose confidence is merely reduced) keeps its own provenance unchanged.
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

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
import com.embabel.dice.common.NewEntity
import com.embabel.dice.common.VetoedEntity
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
import com.embabel.dice.common.UngroundedMentionsFound
import com.embabel.dice.common.filter.GroundedMentionFilter
import com.embabel.dice.common.filter.MentionFilter
import com.embabel.dice.common.resolver.ChainedEntityResolver
import com.embabel.dice.common.resolver.InMemoryEntityResolver
import com.embabel.dice.common.resolver.KnownEntityResolver
import com.embabel.dice.common.support.Sha256ContentHasher
import com.embabel.dice.incremental.BookmarkKey
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.incremental.HashKey
import com.embabel.dice.incremental.ProcessedChunkRecord
import com.embabel.dice.proposition.MentionKey
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionExtractor
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.SuggestedPropositions
import com.embabel.dice.provenance.ContentAddressedLocator
import com.embabel.dice.provenance.ProvenanceEntry
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
 * - [withUngroundedMentions] — keep or drop mentions whose span isn't in their proposition
 * - [withMentionGrounding]: replace the check that decides whether a mention is grounded
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
    private val ungroundedMentions: UngroundedMentionHandling = UngroundedMentionHandling.KEEP,
    private val groundingCheck: MentionFilter = GroundedMentionFilter(),
    private val mentionFilterHandling: RejectedMentionHandling = RejectedMentionHandling.LEAVE_UNRESOLVED,
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

    /**
     * Maps the pipeline's handling of ungrounded mentions to the handling of a grounding rejection.
     *
     * @return the handling for mentions the grounding check rejects
     */
    private fun groundingHandling(): RejectedMentionHandling = when (ungroundedMentions) {
        UngroundedMentionHandling.KEEP -> RejectedMentionHandling.KEEP
        UngroundedMentionHandling.DROP -> RejectedMentionHandling.DROP
    }

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
        PropositionPipeline(extractor, reviser, propositionRepository, mentionFilter, rawEventListener, executionStrategy, ungroundedMentions, groundingCheck, mentionFilterHandling)

    /**
     * Add a mention filter to validate entity mentions before creating entities.
     * A mention the filter rejects is not resolved and stays on its proposition.
     * The pipeline applies the filter for every extractor and hands the extractor no filter.
     *
     * @param filter The mention filter to use
     * @return A new pipeline instance with mention filtering enabled
     */
    fun withMentionFilter(filter: MentionFilter): PropositionPipeline =
        withMentionFilter(filter, RejectedMentionHandling.LEAVE_UNRESOLVED)

    /**
     * Add a mention filter and choose what happens to a mention it rejects.
     *
     * With [RejectedMentionHandling.KEEP] the rejection is only reported, and the mention is
     * resolved and stays on its proposition. With [RejectedMentionHandling.LEAVE_UNRESOLVED] the
     * mention is not offered to the entity resolver and stays on its proposition. With
     * [RejectedMentionHandling.DROP] the mention is not offered to the entity resolver and is
     * taken off its proposition. The proposition itself is always kept.
     *
     * The pipeline applies the filter for every extractor and hands the extractor no filter.
     * Every rejection is listed in [ChunkPropositionResult.rejectedMentions].
     *
     * @param filter The mention filter to use
     * @param handling What happens to a mention the filter rejects
     * @return A new pipeline instance with that filter and handling
     */
    fun withMentionFilter(filter: MentionFilter, handling: RejectedMentionHandling): PropositionPipeline =
        PropositionPipeline(extractor, reviser, propositionRepository, filter, rawEventListener, executionStrategy, ungroundedMentions, groundingCheck, handling)

    /**
     * Replace the check that decides whether a mention is grounded. The default is
     * [GroundedMentionFilter]. [withUngroundedMentions] still chooses whether a mention the check
     * rejects is kept or dropped.
     *
     * The check answers one question: is the mention supported by the text of its proposition.
     * Its rejections are reported as ungrounded mentions, with reason
     * [UngroundedMention.Reason.BLANK_SPAN] for a blank span and
     * [UngroundedMention.Reason.SPAN_NOT_IN_PROPOSITION] for any other. A rule about anything
     * else goes in [withMentionFilter].
     *
     * @param check The grounding check to use
     * @return A new pipeline instance with that grounding check
     */
    fun withMentionGrounding(check: MentionFilter): PropositionPipeline =
        PropositionPipeline(extractor, reviser, propositionRepository, mentionFilter, rawEventListener, executionStrategy, ungroundedMentions, check, mentionFilterHandling)

    /**
     * Register a listener for pipeline events.
     *
     * When set, [process] emits one aggregate [ExtractionBatchCompleted] per run, and — when a
     * reviser is also configured — [processChunk] emits one per-proposition "candidate" event per
     * [RevisionResult] (revision-only). These candidate events are **pre-persistence** signals:
     * the canonical durable signal is `PropositionPersisted`, emitted by the repository decorator.
     * Each chunk in which a mention's span is not in its own proposition's text also emits one [UngroundedMentionsFound].
     * Every rejected mention, from the grounding check or the mention filter, is also listed on the
     * chunk result in [ChunkPropositionResult.rejectedMentions].
     *
     * Defaults to [DiceEventListener.DEV_NULL]; behavior is unchanged when no listener is set.
     *
     * @param listener The listener to receive [com.embabel.dice.common.DiceEvent]s
     * @return A new pipeline instance with the listener registered
     */
    fun withEventListener(listener: DiceEventListener): PropositionPipeline =
        PropositionPipeline(extractor, reviser, propositionRepository, mentionFilter, listener, executionStrategy, ungroundedMentions, groundingCheck, mentionFilterHandling)

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
        PropositionPipeline(extractor, reviser, propositionRepository, mentionFilter, rawEventListener, strategy, ungroundedMentions, groundingCheck, mentionFilterHandling)

    /**
     * Choose what happens to a mention whose span doesn't appear in the text of the proposition
     * it's attached to.
     *
     * With [UngroundedMentionHandling.KEEP], the default, such mentions go through exactly as the
     * extractor returned them: they are resolved like any other mention and stay on the
     * proposition. With [UngroundedMentionHandling.DROP] they are taken off their proposition
     * before entity resolution, so they can't be resolved, minted as new entities, or left on
     * the result. The proposition itself is always kept, even if it loses every mention.
     *
     * Either way, the ungrounded mentions are reported to the event listener as one
     * [UngroundedMentionsFound] per chunk, and listed on the chunk result in
     * [ChunkPropositionResult.rejectedMentions].
     *
     * When a dropped mention carried a suggested id and no remaining mention with the same span
     * and type has one, the resolver candidates for that span and type get that id.
     *
     * @param handling whether to keep or drop ungrounded mentions
     * @return A new pipeline instance with that handling
     */
    fun withUngroundedMentions(handling: UngroundedMentionHandling): PropositionPipeline =
        PropositionPipeline(extractor, reviser, propositionRepository, mentionFilter, rawEventListener, executionStrategy, handling, groundingCheck, mentionFilterHandling)

    /**
     * Carries the resolver-free output of the extraction stage for a single chunk so it can be produced
     * concurrently. The resolution stage picks this up serially to do entity resolution. See [process].
     */
    private data class ExtractionStageResult(
        val chunk: Chunk,
        val suggestedPropositions: SuggestedPropositions,
        val suggestedEntities: SuggestedEntities,
        val ungrounded: List<UngroundedMention>,
        val rejected: List<RejectedMention>,
        val unresolved: List<Set<Int>>,
    )

    /**
     * Extraction stage — extract propositions and convert mentions to suggested entities.
     * Touches no entity resolver, so it is safe to run concurrently across chunks.
     */
    private fun extractStage(chunk: Chunk, context: SourceAnalysisContext): ExtractionStageResult {
        logger.debug("Processing chunk: {}", chunk.id)

        // Step 1: Extract propositions from chunk
        val extracted = extractor.extract(chunk, context)
        logger.debug("Extracted {} propositions", extracted.propositions.size)

        // Check every mention against its own proposition before any of them can become a
        // resolver candidate. In DROP mode the ungrounded mentions leave both the propositions
        // and the candidates for entity creation. In KEEP mode nothing changes but the report.
        // The caller's filter runs here too, so the extractor is handed no filter and each
        // mention is judged by the filter once.
        val screening = MentionScreening.screen(
            extracted = extracted,
            context = context,
            grounding = groundingCheck,
            groundingHandling = groundingHandling(),
            filter = mentionFilter,
            filterHandling = mentionFilterHandling,
        )

        // Step 2: Convert mentions to suggested entities (include source text for context)
        val suggestedEntities = extractor.toSuggestedEntities(
            screening.candidates,
            context,
            chunk.text,
            null
        )
        logger.debug("Created {} suggested entities", suggestedEntities.suggestedEntities.size)

        val rejected = screening.rejected.map { if (it.chunkId == chunk.id) it else it.copy(chunkId = chunk.id) }
        return ExtractionStageResult(chunk, screening.result, suggestedEntities, screening.ungrounded, rejected, screening.unresolved)
    }

    /**
     * Logs one line of counts for the mentions rejected in a chunk.
     *
     * The line is a warning when any rejected mention was dropped or left unresolved, and a debug
     * message when every rejected mention was only reported. Spans and reasons are logged at debug
     * level only, because a span is source text and a reason can quote it.
     *
     * @param chunkId the id of the chunk
     * @param rejected every rejection of the chunk, from the grounding check and the mention filter
     */
    private fun logRejections(chunkId: String, rejected: List<RejectedMention>) {
        if (rejected.isEmpty()) return
        val grounding = rejected.filter { it.check == RejectedMention.Check.GROUNDING }
        val filter = rejected.filter { it.check == RejectedMention.Check.FILTER }
        val message = "Chunk {}: {} mention(s) rejected (grounding: {} rejected, {} dropped; filter: {} rejected, {} left unresolved, {} dropped)"
        val arguments = arrayOf<Any>(
            chunkId,
            rejected.size,
            grounding.size,
            grounding.count { it.handling == RejectedMentionHandling.DROP },
            filter.size,
            filter.count { it.handling == RejectedMentionHandling.LEAVE_UNRESOLVED },
            filter.count { it.handling == RejectedMentionHandling.DROP },
        )
        if (rejected.any { it.handling != RejectedMentionHandling.KEEP }) {
            logger.warn(message, *arguments)
        } else {
            logger.debug(message, *arguments)
        }
        rejected.forEach {
            logger.debug("Rejected mention '{}' in chunk {} ({}, {}): {}", it.mention.span, chunkId, it.check, it.handling, it.reason)
        }
    }

    /**
     * Resolution stage — resolve entities through the shared cross-chunk resolver, apply resolutions,
     * and optionally revise against existing propositions. Must stay serial so all chunks share
     * the same entity identity within a single [process] run.
     */
    /**
     * Takes the resolved id off each mention that a check left unresolved.
     *
     * Resolution assigns ids by span and type, so a rejected mention would otherwise share the id
     * of an accepted mention with the same span and type. An extractor returns the resolved
     * propositions and their mentions in the screened order, and when the output agrees with that
     * order, by text and by each mention's span, type and role, they are paired by position. For an
     * extractor that reorders its output they are paired by content instead: a proposition by its
     * text and its mentions' span, type and role, and a mention by those fields and its rank among
     * mentions that share them. A resolved proposition with no screened twin is left as it is.
     *
     * @param propositions the propositions the extractor resolved
     * @param screened the screened propositions they were resolved from
     * @param unresolved for each screened proposition, the positions of mentions to leave unresolved
     * @return [propositions] when nothing changes, otherwise a copy without those ids
     */
    private fun leaveUnresolved(
        propositions: List<Proposition>,
        screened: SuggestedPropositions,
        unresolved: List<Set<Int>>,
    ): List<Proposition> {
        if (unresolved.all { it.isEmpty() }) return propositions
        if (inScreenedOrder(propositions, screened)) {
            return propositions.mapIndexed { index, proposition ->
                val positions = unresolved.getOrNull(index).orEmpty()
                if (positions.none { proposition.mentions[it].resolvedId != null }) proposition
                else proposition.copy(mentions = proposition.mentions.mapIndexed { position, mention ->
                    if (position in positions) mention.copy(resolvedId = null) else mention
                })
            }
        }
        val pending = screened.propositions.mapIndexed { index, proposition ->
            val marks = proposition.mentions.map { markOf(it.span, it.type, it.toEntityMention().role) }
            Triple(proposition.text, marks.sortedBy { it.toString() }, ranked(marks, unresolved.getOrNull(index).orEmpty()))
        }.toMutableList()
        return propositions.map { proposition ->
            val marks = proposition.mentions.map { markOf(it.span, it.type, it.role) }
            val signature = marks.sortedBy { it.toString() }
            val match = pending.indexOfFirst { it.first == proposition.text && it.second == signature }
            if (match < 0) return@map proposition
            val clear = pending.removeAt(match).third
            if (clear.isEmpty()) return@map proposition
            val all = ranked(marks, marks.indices.toSet())
            val positions = marks.indices.filter { all[it] in clear && proposition.mentions[it].resolvedId != null }.toSet()
            if (positions.isEmpty()) proposition
            else proposition.copy(mentions = proposition.mentions.mapIndexed { position, mention ->
                if (position in positions) mention.copy(resolvedId = null) else mention
            })
        }
    }

    /**
     * Tells whether resolved propositions line up with the screened ones by position.
     *
     * @param propositions the propositions the extractor resolved
     * @param screened the screened propositions
     * @return true when each resolved proposition has the text of the screened one at its position
     * and the same mentions, by span, type and role, in the same order
     */
    private fun inScreenedOrder(propositions: List<Proposition>, screened: SuggestedPropositions): Boolean =
        propositions.size == screened.propositions.size &&
            propositions.zip(screened.propositions).all { (resolved, suggested) ->
                resolved.text == suggested.text &&
                    resolved.mentions.map { markOf(it.span, it.type, it.role) } ==
                    suggested.mentions.map { markOf(it.span, it.type, it.toEntityMention().role) }
            }

    /**
     * A mention's span, type and role, compared the way resolution compares span and type.
     *
     * @property span the span, lower case and trimmed
     * @property type the type, lower case and trimmed
     * @property role the role
     */
    private data class MentionMark(val span: String, val type: String, val role: MentionRole)

    /**
     * Builds a mention's mark from its span, type and resolved role.
     *
     * @param span the span as written
     * @param type the type as written
     * @param role the role as resolution sets it
     * @return the normalized mark
     */
    private fun markOf(span: String, type: String, role: MentionRole): MentionMark =
        MentionMark(span.lowercase().trim(), type.lowercase().trim(), role)

    /**
     * Names the mentions at the given positions by their mark and by how many earlier mentions
     * share that mark.
     *
     * @param marks the mark of each mention, in order
     * @param positions the positions to name
     * @return one (mark, rank) pair per named position, in position order
     */
    private fun ranked(marks: List<MentionMark>, positions: Set<Int>): List<Pair<MentionMark, Int>> {
        val seen = mutableMapOf<MentionMark, Int>()
        return marks.mapIndexedNotNull { position, mark ->
            val rank = seen.merge(mark, 1, Int::plus)!! - 1
            if (position in positions) mark to rank else null
        }
    }

    private fun resolveStage(
        extraction: ExtractionStageResult,
        context: SourceAnalysisContext,
    ): ChunkPropositionResult.Success {
        val (chunk, suggestedPropositions, suggestedEntities) = extraction

        // Reported here rather than in the extraction stage because this stage is always serial,
        // so listeners are never called from several threads at once.
        logRejections(chunk.id, extraction.rejected)
        if (extraction.ungrounded.isNotEmpty()) {
            eventListener.onEvent(
                UngroundedMentionsFound(context.contextId, chunk.id, extraction.ungrounded, ungroundedMentions)
            )
        }

        // Step 3: Resolve entities using existing resolver (wrapped with known entities)
        val resolver = KnownEntityResolver.withKnownEntities(context.knownEntities, context.entityResolver)
        val rawResolutions = resolver.resolve(suggestedEntities, context.schema)
        // Minting gate: unless this analysis opted in (SourceAnalysisContext.mintNewEntities),
        // a mention the resolver could not match is VETOED, not minted — the mention stays
        // unresolved (no resolvedId), so no phantom entity node is persisted and no edge can
        // be projected to one. Matches to EXISTING entities are unaffected.
        val resolutions = if (context.mintNewEntities) {
            // Stamp caller-supplied base properties (ownership/scoping) onto every
            // minted entity; stamped values win over extractor-supplied keys.
            if (context.mintedEntityProperties.isEmpty()) rawResolutions else rawResolutions.copy(
                resolutions = rawResolutions.resolutions.map { r ->
                    if (r is NewEntity) NewEntity(
                        r.suggested.copy(properties = r.suggested.properties + context.mintedEntityProperties),
                    ) else r
                },
            )
        } else rawResolutions.copy(
            resolutions = rawResolutions.resolutions.map { r ->
                if (r is NewEntity) VetoedEntity(r.suggested) else r
            },
        )
        if (!context.mintNewEntities) {
            val wouldMint = rawResolutions.resolutions.count { it is NewEntity }
            if (wouldMint > 0) {
                logger.debug("Minting disabled for this analysis: vetoed {} unmatched mention(s)", wouldMint)
            }
        }
        logger.debug("Resolved {} entities", resolutions.resolutions.size)

        // Step 4: Apply resolutions to create final propositions, each stamped with provenance
        // back to its source chunk. Done before revision so merges union the entries and a brand
        // new proposition keeps its grounding.
        val propositions = stampProvenance(
            leaveUnresolved(
                extractor.resolvePropositions(suggestedPropositions, resolutions, context),
                suggestedPropositions,
                extraction.unresolved,
            ),
            chunk,
            context,
        )
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
            rejectedMentions = extraction.rejected,
        )
    }

    /**
     * Stamp each proposition with a [ProvenanceEntry] linking it back to the chunk it came from.
     *
     * The locator is the caller's [SourceAnalysisContext.sourceLocator] when known (a file, a URI,
     * a connector record); otherwise it falls back to a [ContentAddressedLocator] over the chunk
     * text, which is always available and grounds the fact in the exact content it was read from.
     * Either way the entry also carries the chunk id and a content hash, so a consumer can trace a
     * proposition to its source without the source needing to be a stored entity first.
     *
     * When the caller also supplied a [SourceAnalysisContext.sourceRevision], its opaque revision
     * string rides along on the entry. The context has already checked that the revision names this
     * locator, so a fallback content-addressed locator never picks up somebody else's revision.
     */
    private fun stampProvenance(
        propositions: List<Proposition>,
        chunk: Chunk,
        context: SourceAnalysisContext,
    ): List<Proposition> {
        if (propositions.isEmpty()) return propositions
        val contentHash = Sha256ContentHasher.hash(chunk.text)
        val entry = ProvenanceEntry(
            locator = context.sourceLocator ?: ContentAddressedLocator(contentHash),
            chunkId = chunk.id,
            contentHash = contentHash,
            sourceRevision = context.sourceRevision?.sourceRevision,
        )
        return propositions.map { it.withProvenanceEntries(listOf(entry)) }
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
     * Processes a text as a single chunk, without any deduplication. Use this when no history
     * store is involved, since the result is never null.
     *
     * Like [processOnce], this records nothing and returns an UNSAVED result. The caller must
     * persist it via [PersistablePropositions.persist][PersistablePropositions.persist].
     *
     * The source id becomes the chunk id, so the grounding of each proposition carries it.
     *
     * @param text The text to process
     * @param sourceId Identifier for the source, used as both the chunk id and the parent id
     * @param context Analysis context with schema, entity resolver, etc.
     * @param additionalGrounding Extra ids to add to the grounding of each proposition
     * @return Result with propositions and entities
     */
    @JvmOverloads
    fun processText(
        text: String,
        sourceId: String,
        context: SourceAnalysisContext,
        additionalGrounding: List<String> = emptyList(),
    ): ChunkPropositionResult {
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
        //
        // Only do this when minting is actually enabled. InMemoryEntityResolver remembers every
        // entity it's asked to resolve so a later chunk's mention comes back as ExistingEntity
        // rather than minting a duplicate - but that memory only makes sense if the entity it
        // remembers is actually going to be created. When minting is disabled, resolveStage vetoes
        // every NewEntity instead of minting it, so nothing the in-memory resolver remembers will
        // ever exist in any repository. Wrapping the resolver anyway would let a later chunk's
        // mention resolve to ExistingEntity pointing at an id that was never created and will never
        // be found - the entity equivalent of a dangling pointer.
        val crossChunkResolver = if (context.mintNewEntities) {
            ChainedEntityResolver(listOf(context.entityResolver, InMemoryEntityResolver()))
        } else {
            context.entityResolver
        }
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

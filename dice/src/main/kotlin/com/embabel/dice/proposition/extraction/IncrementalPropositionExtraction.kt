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
package com.embabel.dice.proposition.extraction

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntity
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.chat.Message
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.KnownEntity
import com.embabel.dice.common.Relations
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.SourceAnalysisRequestEvent
import com.embabel.dice.common.resolver.KnownEntityResolver
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.incremental.IncrementalAnalyzer
import com.embabel.dice.incremental.MessageFormatter
import com.embabel.dice.incremental.WindowConfig
import com.embabel.dice.incremental.proposition.PropositionIncrementalAnalyzer
import com.embabel.dice.pipeline.ChunkPropositionResult
import com.embabel.dice.pipeline.PropositionPipeline
import com.embabel.dice.projection.graph.GraphProjectionService
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.provenance.SourceLocator
import com.embabel.dice.provenance.SourceRevisionRef
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import java.io.InputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Function

/**
 * Generic async proposition extraction from incremental sources (conversations, message streams, etc.).
 * Uses [IncrementalAnalyzer] for windowed, deduplicated processing.
 *
 * This is NOT a Spring component — consuming applications create it as a `@Bean`
 * so that `@EventListener` and `@Async` are honoured by the Spring container.
 *
 * @param contextIdProvider maps the event's [NamedEntity] user to the context ID
 *        used for proposition storage. Defaults to [NamedEntity.getId].
 * @param promptVariablesProvider optional function to add extra template variables
 *        to the [SourceAnalysisContext] built for each extraction.
 */
open class IncrementalPropositionExtraction @JvmOverloads constructor(
    private val propositionPipeline: PropositionPipeline,
    chunkHistoryStore: ChunkHistoryStore,
    private val dataDictionary: DataDictionary,
    private val relations: Relations,
    private val propositionRepository: PropositionRepository,
    private val entityRepository: NamedEntityDataRepository,
    private val entityResolver: EntityResolver,
    private val graphProjectionService: GraphProjectionService,
    properties: PropositionExtractionProperties,
    private val contextIdProvider: Function<NamedEntity, String> = Function { it.id },
    private val promptVariablesProvider: Function<NamedEntity, Map<String, Any>> = Function { emptyMap() },
    /**
     * Per-extraction known entities the LLM should be aware of beyond
     * the current user. Called with (user, sourceId) so consumers can
     * surface chunk-specific candidates — email senders for a thread,
     * closed-vocabulary catalogs (Hobby, ServiceCategory) the
     * extractor should recognise, or recently-resolved entities.
     *
     * Without this the LLM only sees the current user as a known
     * entity and tends to emit propositions with a single SUBJECT
     * mention — which the [GraphProjectionService] cannot materialise
     * into edges because there is no OBJECT mention to link to.
     */
    private val extraKnownEntitiesProvider: (NamedEntity, String) -> List<NamedEntity> = { _, _ -> emptyList() },
    /**
     * Optional grounding wiring — if non-null, runs after each
     * `persistAndProject` and materialises
     * `(:Proposition)-[:GROUNDED_IN]->(:<entity>)` edges for any
     * grounding id that resolves to a stored entity. Defaults to no-op
     * for backward compatibility; existing consumers see no behaviour
     * change. Pass a [com.embabel.dice.projection.grounding.GroundingWiringService]
     * to opt in.
     */
    private val groundingWiringService: com.embabel.dice.projection.grounding.GroundingWiringService? = null,
    /**
     * Alternate NAME forms for the current user (nicknames, former
     * names) — NOT alternate email addresses. Called with the event's
     * user; the returned aliases are threaded into the current-user
     * [KnownEntity] so the [KnownEntityResolver] binds an alt-name chunk
     * mention (e.g. "Tom" for "Thomas") to the user instead of letting
     * it escalate to a heuristic match that mints a separate external
     * Person. Defaults to a no-op returning no aliases, so existing
     * consumers see no behaviour change.
     */
    private val currentUserAliasesProvider: (NamedEntity) -> List<String> = { _ -> emptyList() },
    /**
     * Instance default for [SourceAnalysisContext.mintNewEntities], applied to
     * every analysis this extractor runs (the event path has no per-call hook;
     * [rememberText] callers can override per call). FALSE by default: a
     * mention the resolver cannot match to an existing entity stays unresolved
     * instead of minting a phantom node.
     */
    private val mintNewEntitiesDefault: Boolean = false,
    /**
     * Base properties stamped onto entities minted for [user]'s analyses —
     * e.g. ownership/scoping fields a multi-tenant deployment requires on new
     * nodes. Only consulted when minting is enabled for the analysis. See
     * [SourceAnalysisContext.mintedEntityProperties].
     */
    private val mintedEntityPropertiesProvider: (NamedEntity) -> Map<String, Any> = { _ -> emptyMap() },
) {
    private val analyzer: IncrementalAnalyzer<Message, ChunkPropositionResult> =
        PropositionIncrementalAnalyzer(
            propositionPipeline,
            chunkHistoryStore,
            MessageFormatter.INSTANCE,
            WindowConfig(properties.windowSize, properties.overlapSize, properties.triggerInterval),
        )

    private val windowConfig = WindowConfig(properties.windowSize, properties.overlapSize, properties.triggerInterval)
    private val extractionLock = ReentrantLock()
    private val pendingEvents = ConcurrentLinkedQueue<SourceAnalysisRequestEvent>()
    private val inFlightCount = AtomicInteger(0)

    /**
     * Synchronous listener — runs on the publisher's thread during publishEvent().
     * Increments the in-flight counter BEFORE the async handler is dispatched,
     * so [isIdle] can see events that are in Spring's executor queue.
     */
    @EventListener
    open fun trackEvent(event: SourceAnalysisRequestEvent) {
        inFlightCount.incrementAndGet()
    }

    @Async
    @EventListener
    open fun onSourceAnalysisRequestEvent(event: SourceAnalysisRequestEvent) {
        extractPropositions(event)
    }

    /**
     * Returns true when no extraction is running, no events are queued,
     * and no events are in-flight (in Spring's async executor queue).
     */
    open val isIdle: Boolean
        get() = inFlightCount.get() <= 0 && pendingEvents.isEmpty() && !extractionLock.isLocked

    open fun extractPropositions(event: SourceAnalysisRequestEvent) {
        pendingEvents.add(event)
        processPendingEvents()
    }

    /**
     * Extract propositions from a file via Tika and persist them.
     * Requires `embabel-agent-rag-tika` on the classpath.
     */
    open fun rememberFile(inputStream: InputStream, filename: String, user: NamedEntity) =
        rememberFileInternal(inputStream, filename, user)

    /**
     * Extract propositions from a file and ground them in the caller's typed source.
     *
     * A non-null [sourceRevision] is a host assertion that the locator's revision covers
     * the full extracted file aggregate. DICE cannot infer aggregate revision coverage.
     */
    @JvmOverloads
    open fun rememberFile(
        inputStream: InputStream,
        filename: String,
        user: NamedEntity,
        sourceLocator: SourceLocator,
        sourceRevision: SourceRevisionRef? = null,
    ) =
        rememberFileInternal(inputStream, filename, user, sourceLocator, sourceRevision)

    private fun rememberFileInternal(
        inputStream: InputStream,
        filename: String,
        user: NamedEntity,
        sourceLocator: SourceLocator? = null,
        sourceRevision: SourceRevisionRef? = null,
    ) {
        try {
            val reader = com.embabel.agent.rag.ingestion.TikaHierarchicalContentReader()
            val document = reader.parseContent(inputStream, "remember://$filename")

            val text = document.leaves().joinToString("\n\n") { it.text }.trim()
            if (text.isEmpty()) {
                logger.info("No text extracted from file: {}", filename)
                return
            }

            rememberTextInternal(
                text = text,
                sourceId = "remember:$filename",
                user = user,
                sourceLocator = sourceLocator,
                sourceRevision = sourceRevision,
            )
        } catch (e: Exception) {
            logger.warn("Failed to learn file: {}", filename, e)
        }
    }

    /**
     * Extract propositions from raw text and persist them.
     *
     * @param additionalGrounding extra source-record ids to ground the
     *   resulting propositions in, on top of [sourceId]. Ids that resolve to a
     *   stored entity become `(:Proposition)-[:GROUNDED_IN]->(:entity)` edges —
     *   e.g. a chat-recovery answer synthesised from `email:<threadId>` and a
     *   connected-service record can attribute back to both. Empty (default)
     *   preserves prior behaviour.
     * @param perspective optional per-call extraction perspective. `null` (default)
     *   leaves the extractor's own perspective in force — zero behaviour change.
     *   A caller can pass e.g. [ExtractionPerspective.NON_USER_RELATIONSHIPS] to
     *   opt this one source into mining non-user relationships.
     * @param mintNewEntities per-call override for whether unmatched mentions may
     *   be persisted as NEW entities. `null` (default) uses the extractor
     *   instance's default; see [SourceAnalysisContext.mintNewEntities].
     */
    @JvmOverloads
    open fun rememberText(
        text: String,
        sourceId: String,
        user: NamedEntity,
        additionalGrounding: List<String> = emptyList(),
        perspective: ExtractionPerspective? = null,
        mintNewEntities: Boolean? = null,
    ) =
        rememberTextInternal(
            text = text,
            sourceId = sourceId,
            user = user,
            additionalGrounding = additionalGrounding,
            perspective = perspective,
            mintNewEntities = mintNewEntities,
        )

    /**
     * Extract propositions from raw text and ground them in the caller's typed source.
     *
     * [sourceId] remains the exact caller-supplied chunk/grounding identifier. A non-null
     * [sourceRevision] is a host assertion that [sourceLocator]'s revision covers the full
     * text aggregate; DICE cannot derive revision coverage from untyped identifiers or
     * [additionalGrounding].
     */
    @JvmOverloads
    open fun rememberText(
        text: String,
        sourceId: String,
        user: NamedEntity,
        sourceLocator: SourceLocator,
        sourceRevision: SourceRevisionRef? = null,
        additionalGrounding: List<String> = emptyList(),
        perspective: ExtractionPerspective? = null,
        mintNewEntities: Boolean? = null,
    ) =
        rememberTextInternal(
            text = text,
            sourceId = sourceId,
            user = user,
            sourceLocator = sourceLocator,
            sourceRevision = sourceRevision,
            additionalGrounding = additionalGrounding,
            perspective = perspective,
            mintNewEntities = mintNewEntities,
        )

    private fun rememberTextInternal(
        text: String,
        sourceId: String,
        user: NamedEntity,
        sourceLocator: SourceLocator? = null,
        sourceRevision: SourceRevisionRef? = null,
        additionalGrounding: List<String> = emptyList(),
        perspective: ExtractionPerspective? = null,
        mintNewEntities: Boolean? = null,
    ) {
        val context = buildContext(
            user = user,
            sourceId = sourceId,
            perspective = perspective,
            mintNewEntities = mintNewEntities,
            sourceLocator = sourceLocator,
            sourceRevision = sourceRevision,
        )
        val result = propositionPipeline.processOnce(
            text, sourceId, context, additionalGrounding = additionalGrounding,
        )

        if (result != null && result.propositions.isNotEmpty()) {
            logger.info(result.infoString(true, 1))
            persistAndProject(result)
            logAllPropositions(contextIdProvider.apply(user))
            logger.info("Remembered source: {}", sourceId)
        } else {
            logger.info("No propositions extracted from source: {}", sourceId)
        }
    }

    // -- internal ---------------------------------------------------------

    private fun processPendingEvents() {
        if (!extractionLock.tryLock()) {
            logger.debug("Extraction in progress, {} event(s) queued", pendingEvents.size)
            return
        }
        try {
            var next = pendingEvents.poll()
            while (next != null) {
                processEvent(next)
                next = pendingEvents.poll()
            }
        } finally {
            extractionLock.unlock()
        }
        if (pendingEvents.isNotEmpty()) {
            processPendingEvents()
        }
    }

    private fun processEvent(event: SourceAnalysisRequestEvent) {
        try {
            val source = event.incrementalSource()
            if (source.size < windowConfig.overlapSize) {
                logger.info(
                    "Source {} has {} items, need at least {} for extraction",
                    source.id, source.size, windowConfig.overlapSize,
                )
                return
            }

            val context = buildContext(
                user = event.user,
                sourceId = source.id,
                sourceLocator = event.sourceLocator(),
                sourceRevision = event.sourceRevision(),
            )
            logger.info(
                "Context relations count: {}, injected relations count: {}",
                context.relations.size(), relations.size(),
            )

            val result = analyzer.analyze(source, context) ?: run {
                logger.info("Analysis skipped (not ready or already processed)")
                return
            }

            if (result.propositions.isEmpty()) {
                logger.info("Analysis completed but no propositions extracted")
                return
            }

            logger.info(result.infoString(true, 1))
            persistAndProject(result)
            logAllPropositions(contextIdProvider.apply(event.user))
        } catch (e: Exception) {
            logger.warn("Failed to extract propositions", e)
        } finally {
            inFlightCount.decrementAndGet()
        }
    }

    private fun buildContext(
        user: NamedEntity,
        sourceId: String = "",
        perspective: ExtractionPerspective? = null,
        mintNewEntities: Boolean? = null,
        sourceLocator: SourceLocator? = null,
        sourceRevision: SourceRevisionRef? = null,
    ): SourceAnalysisContext {
        val aliases = try {
            currentUserAliasesProvider(user)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("[buildContext] currentUserAliasesProvider interrupted for {}", sourceId, e)
            emptyList()
        } catch (e: Exception) {
            logger.warn("[buildContext] currentUserAliasesProvider threw for {}", sourceId, e)
            emptyList()
        }
        val currentUser = KnownEntity.asCurrentUser(user, aliases)
        val extras = try {
            extraKnownEntitiesProvider(user, sourceId)
                .filter { it.id != user.id }
                .map { KnownEntity.of(it).withRole("Candidate entity for this source") }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            logger.warn("[buildContext] extraKnownEntitiesProvider interrupted for {}", sourceId, e)
            emptyList()
        } catch (e: Exception) {
            logger.warn("[buildContext] extraKnownEntitiesProvider threw for {}", sourceId, e)
            emptyList()
        }
        val allKnown = listOf(currentUser) + extras

        var ctx = SourceAnalysisContext
            .withContextId(contextIdProvider.apply(user))
            .withEntityResolver(
                KnownEntityResolver.withKnownEntities(allKnown, entityResolver),
            )
            .withSchema(dataDictionary)
            .withRelations(relations)
            .withKnownEntities(*allKnown.toTypedArray())

        val extra = promptVariablesProvider.apply(user)
        if (extra.isNotEmpty()) {
            ctx = ctx.withPromptVariables(extra)
        }
        // Per-call perspective (e.g. NON_USER_RELATIONSHIPS) overrides the
        // extractor instance default only when explicitly supplied. The event
        // path never passes one, so its behaviour is unchanged.
        if (perspective != null) {
            ctx = ctx.withPerspective(perspective)
        }
        // Per-call override wins; otherwise the instance default applies (the
        // event path always uses the instance default).
        ctx = ctx.withMintNewEntities(mintNewEntities ?: mintNewEntitiesDefault)
        if (ctx.mintNewEntities) {
            val stamped = try {
                mintedEntityPropertiesProvider(user)
            } catch (e: Exception) {
                logger.warn("mintedEntityPropertiesProvider failed for {}: {}", user.name, e.message)
                emptyMap()
            }
            if (stamped.isNotEmpty()) {
                ctx = ctx.withMintedEntityProperties(stamped)
            }
        }
        if (sourceLocator != null) {
            ctx = ctx.withSourceLocator(sourceLocator)
        }
        if (sourceRevision != null) {
            ctx = ctx.withSourceRevision(sourceRevision)
        }
        return ctx
    }

    private fun persistAndProject(result: ChunkPropositionResult) {
        val propsToSave = result.propositionsToPersist()
        val referencedEntityIds = propsToSave
            .flatMap { it.mentions }
            .mapNotNull { it.resolvedId }
            .toSet()
        val newEntitiesToSave = result.newEntities().count { it.id in referencedEntityIds }

        val stats = result.propositionExtractionStats
        val newProps = stats.newCount
        val updatedProps = stats.mergedCount + stats.reinforcedCount

        for (entity in result.newEntities()) {
            logger.info("New entity: name='{}', labels={}", entity.name, entity.labels())
        }
        for (entity in result.updatedEntities()) {
            logger.info("Updated entity: name='{}', labels={}", entity.name, entity.labels())
        }

        result.persist(propositionRepository, entityRepository)
        if (newProps > 0 || updatedProps > 0 || newEntitiesToSave > 0) {
            logger.info(
                "Persisted: {} new propositions, {} updated propositions, {} new entities",
                newProps, updatedProps, newEntitiesToSave,
            )
        } else {
            logger.info("No new data to persist (all propositions were duplicates)")
        }

        val projectionResult = graphProjectionService.projectAndPersist(propsToSave)
        val persistenceResult = projectionResult.second
        if (persistenceResult.persistedCount > 0) {
            logger.info(
                "Projected {} semantic relationships from propositions",
                persistenceResult.persistedCount,
            )
        }
        // Optional grounding pass — turns the `grounding: List<String>`
        // on each freshly-saved proposition into actual
        // `(:Proposition)-[:GROUNDED_IN]->(:<entity>)` edges when the
        // ids resolve to stored entities. No-op when no wiring service
        // was supplied (default for backward compatibility).
        groundingWiringService?.wire(propsToSave)
    }

    private fun logAllPropositions(contextId: String) {
        val all = propositionRepository.findByContextIdValue(contextId)
        val sorted = all.sortedBy { it.text }
        logger.info("All propositions in context {} ({} total):", contextId, sorted.size)
        for (p in sorted) {
            logger.info("  [{}] confidence={} '{}'", p.status, p.confidence, p.text)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(IncrementalPropositionExtraction::class.java)
    }
}

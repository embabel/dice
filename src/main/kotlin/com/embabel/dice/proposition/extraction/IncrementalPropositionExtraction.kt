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
open class IncrementalPropositionExtraction(
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
    open fun rememberFile(inputStream: InputStream, filename: String, user: NamedEntity) {
        try {
            val reader = com.embabel.agent.rag.ingestion.TikaHierarchicalContentReader()
            val document = reader.parseContent(inputStream, "remember://$filename")

            val text = document.leaves().joinToString("\n\n") { it.text }.trim()
            if (text.isEmpty()) {
                logger.info("No text extracted from file: {}", filename)
                return
            }

            rememberText(text, "remember:$filename", user)
        } catch (e: Exception) {
            logger.warn("Failed to learn file: {}", filename, e)
        }
    }

    /**
     * Extract propositions from raw text and persist them.
     */
    open fun rememberText(text: String, sourceId: String, user: NamedEntity) {
        val context = buildContext(user)
        val result = propositionPipeline.processOnce(text, sourceId, context)

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

            val context = buildContext(event.user)
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

    private fun buildContext(user: NamedEntity): SourceAnalysisContext {
        var ctx = SourceAnalysisContext
            .withContextId(contextIdProvider.apply(user))
            .withEntityResolver(
                KnownEntityResolver.withKnownEntities(
                    listOf(KnownEntity.asCurrentUser(user)),
                    entityResolver,
                ),
            )
            .withSchema(dataDictionary)
            .withRelations(relations)
            .withKnownEntities(KnownEntity.asCurrentUser(user))

        val extra = promptVariablesProvider.apply(user)
        if (extra.isNotEmpty()) {
            ctx = ctx.withPromptVariables(extra)
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

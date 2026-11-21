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
import com.embabel.dice.audit.withExtractionAuditRun
import com.embabel.dice.common.NameNormalizer
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
import com.embabel.dice.proposition.PropositionPersistenceResult
import com.embabel.dice.proposition.PropositionRepository
import org.jetbrains.annotations.ApiStatus
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.core.task.TaskDecorator
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Function

/**
 * Generic async proposition extraction from incremental sources (conversations, message streams, etc.).
 * Uses [IncrementalAnalyzer] for windowed, deduplicated processing.
 *
 * This is not a Spring component. An application creates it as a `@Bean` so that its
 * `@EventListener` methods are registered and [close] runs when the context shuts down.
 *
 * A published [SourceAnalysisRequestEvent] is handed to an executor and the publisher returns at
 * once. By default that is a single thread DICE owns, with a bounded queue, so events waiting for
 * a running extraction never hold threads of the application's own pools. If the application
 * defines exactly one `TaskDecorator` bean, each event is decorated with it on the publisher's
 * thread, the way Spring Boot decorates its own executors. An application that needs a different
 * decorator, more queue room or its own lifecycle binds its own executor with [withEventExecutor].
 * Direct calls to [extractPropositions] run on the caller's thread.
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
) : AutoCloseable, BeanFactoryAware {

    /**
     * Where `(proposition, run)` links go, once a host has bound one with [withRunLineage].
     *
     * Volatile because it is written on whatever thread builds the extractor and read on the async
     * extraction threads. It is written exactly once — [runLineageBound] enforces that — so a
     * reader sees either the initial null or the bound store, never a value that later changes
     * underneath it.
     */
    @Volatile
    private var propositionRunLinkStore: PropositionRunLinkStore? = null

    /**
     * What happens when lineage cannot be written. Bound with the store, and volatile for the same
     * reason: written on the thread that builds the extractor, read on the extraction threads.
     */
    @Volatile
    private var lineageFailurePolicy: LineageFailurePolicy = LineageFailurePolicy.DEFAULT

    /** Whether [withRunLineage] has been called. Binding is one-time; see there for why. */
    private val runLineageBound = AtomicBoolean(false)

    /** The writer bound with [withPersistUnitWriter], or null to use the sequential one. */
    @Volatile
    private var persistUnitWriter: PersistUnitWriter? = null

    /** Whether [withPersistUnitWriter] has been called. Binding is one-time, as for run lineage. */
    private val persistUnitWriterBound = AtomicBoolean(false)

    /** The rules set with [withNameNormalizer] for matching a mention to a known entity. */
    @Volatile
    private var nameNormalizer: NameNormalizer = NameNormalizer.standard()

    /**
     * Choose the rules that decide when a mention names a known entity.
     *
     * The extractor matches mentions against the current user and the other known entities of a
     * source before it asks the entity resolver. Give it the normalizer the pipeline has, set
     * with `PropositionPipeline.withNameNormalizer`, so both agree on which names are the same.
     * Set it before the extractor starts handling events. The default is
     * [NameNormalizer.standard].
     *
     * Java:
     * ```java
     * NameNormalizer normalizer = PersonNameNormalizer.english().withPersonLabels("Person", "Contact");
     * extraction.withNameNormalizer(normalizer);
     * ```
     *
     * @param nameNormalizer the rules to use
     * @return this extractor
     */
    open fun withNameNormalizer(nameNormalizer: NameNormalizer): IncrementalPropositionExtraction {
        this.nameNormalizer = nameNormalizer
        return this
    }

    /**
     * Binds the store that records which extraction run produced which claims, and returns this
     * extractor so a bean method can do it in one expression.
     *
     * ```kotlin
     * IncrementalPropositionExtraction(pipeline, ..., properties).withRunLineage(linkStore)
     * ```
     *
     * **Why this is a method and not a constructor parameter.** Kotlin compiles a constructor with
     * default arguments into a single synthetic
     * `<init>(...every parameter..., int mask, DefaultConstructorMarker)`, and that is what a
     * precompiled Kotlin caller links against whenever it omits any argument. Appending a parameter
     * rewrites that descriptor, so every such caller would fail with `NoSuchMethodError` — and
     * `@JvmOverloads` does not save them, because it republishes the Java overloads that Kotlin
     * callers using defaults never touch. Adding a method adds API; appending a defaulted parameter
     * moves one. `RunLineageBinaryCompatibilityTest` pins the descriptor.
     *
     * **Binding is one-time, and a second call is rejected.** The field is read when an analysis
     * records lineage, not when it starts, so a later call would redirect or silently erase the
     * audit record of an extraction already in flight — the failure would be a missing or misfiled
     * lineage row long after the call that caused it. An audit surface should not be swappable at a
     * distance. Passing null is a binding like any other: it is how a host says "record none", and
     * it cannot later be upgraded, or "bound once" would depend on which value was passed.
     *
     * Bind before the extractor starts handling events.
     *
     * Lineage is only ever consulted for an analysis that carries a
     * [SourceAnalysisContext.currentRun]; without a run this store is never touched. EXPERIMENTAL.
     *
     * Binds [LineageFailurePolicy.DEFAULT]. Use the two-argument form to choose.
     *
     * @param propositionRunLinkStore The lineage store, or null to record no lineage.
     * @return this extractor.
     * @throws IllegalStateException if lineage has already been bound.
     */
    open fun withRunLineage(
        propositionRunLinkStore: PropositionRunLinkStore?,
    ): IncrementalPropositionExtraction =
        withRunLineage(propositionRunLinkStore, LineageFailurePolicy.DEFAULT)

    /**
     * Binds the lineage store and says what happens when a lineage write cannot be made.
     *
     * A separate overload, because a defaulted parameter on the one-argument form would move that
     * method's Kotlin descriptor — the whole reason lineage is bound by a method in the first place. `RunLineageBinaryCompatibilityTest` pins it.
     *
     * **A null store under [LineageFailurePolicy.STRICT] is a legitimate binding**, and it is how a
     * host says "record no lineage" while still refusing to run one. It only bites when an analysis
     * actually carries a run: with no store bound and a run set, [LineageFailurePolicy.STRICT]
     * fails that call. A host that passes runs must bind a store.
     *
     * @param propositionRunLinkStore The lineage store, or null to record no lineage.
     * @param policy What happens when lineage cannot be written. See [LineageFailurePolicy].
     * @return this extractor.
     * @throws IllegalStateException if lineage has already been bound.
     * Also thrown for a non-null store when a writer is bound with [withPersistUnitWriter].
     */
    open fun withRunLineage(
        propositionRunLinkStore: PropositionRunLinkStore?,
        policy: LineageFailurePolicy,
    ): IncrementalPropositionExtraction {
        check(propositionRunLinkStore == null || persistUnitWriter == null) {
            "a persist unit writer is bound on this extractor and records run links in its own store. " +
                "Pass null here and give the run link store to the writer"
        }
        check(runLineageBound.compareAndSet(false, true)) {
            "run lineage is already bound on this extractor; it binds once, before the extractor " +
                "starts handling events, so an analysis in flight cannot have its audit record " +
                "redirected or erased"
        }
        this.propositionRunLinkStore = propositionRunLinkStore
        this.lineageFailurePolicy = policy
        return this
    }

    /**
     * Binds the writer that stores each chunk's claims, entities, run link and structural
     * relationships, and returns this extractor.
     *
     * Projection and grounding run after the writer, over the claims it stored.
     * With no writer bound, the extractor uses [PersistUnitWriter.sequential] over the repositories
     * it was constructed with.
     *
     * A bound writer records run links in its own store, so a run link store cannot also be bound
     * with [withRunLineage]. The policy bound there still applies.
     * Reads this extractor makes go to the repositories it was constructed with, so the writer must
     * write to the same ones.
     * Binding is one-time. Bind before the extractor starts handling events.
     *
     * When the bound writer reports a failed write, storing the chunk throws what
     * [PersistOutcome.persistedOrThrow] throws. A cause that is a `RuntimeException` is thrown as it
     * is. A checked cause is wrapped in [PersistUnitFailedException], which carries the step that
     * failed and has the original as its cause. Every exception thrown for a bound writer's failed
     * write is unchecked.
     *
     * From Java:
     *
     * ```java
     * extraction.withRunLineage(null, LineageFailurePolicy.STRICT)
     *     .withPersistUnitWriter(PersistUnitWriter.inMemory(claims, entities, links));
     * ```
     *
     * From Kotlin:
     *
     * ```kotlin
     * extraction.withPersistUnitWriter(PersistUnitWriter.inMemory(claims, entities, links))
     * ```
     *
     * EXPERIMENTAL.
     *
     * @param writer The writer each chunk is stored through.
     * @return this extractor.
     * @throws IllegalStateException if a writer is already bound, or a run link store is bound with
     * [withRunLineage].
     */
    @ApiStatus.Experimental
    open fun withPersistUnitWriter(writer: PersistUnitWriter): IncrementalPropositionExtraction {
        check(propositionRunLinkStore == null) {
            "a run link store is bound on this extractor. A bound writer records run links in its own " +
                "store, so give that store to the writer and call withRunLineage(null, policy) here"
        }
        check(persistUnitWriterBound.compareAndSet(false, true)) {
            "a persist unit writer is already bound on this extractor. Bind it once, before the " +
                "extractor starts handling events"
        }
        this.persistUnitWriter = writer
        return this
    }

    private val analyzer: PropositionIncrementalAnalyzer<Message> =
        PropositionIncrementalAnalyzer(
            propositionPipeline,
            chunkHistoryStore,
            MessageFormatter.INSTANCE,
            WindowConfig(properties.windowSize, properties.overlapSize, properties.triggerInterval),
        )

    private val windowConfig = WindowConfig(properties.windowSize, properties.overlapSize, properties.triggerInterval)
    // Fair, so callers already waiting run in the order they arrived, as the old queue ran them.
    // A newer snapshot of a source can then never move the bookmark past an older one still waiting.
    private val extractionLock = ReentrantLock(true)
    private val pendingEvents = ConcurrentLinkedQueue<SourceAnalysisRequestEvent>()
    private val inFlightCount = AtomicInteger(0)

    /** The executor bound with [withEventExecutor], or null to use [ownEventExecutor]. */
    @Volatile
    private var boundEventExecutor: Executor? = null

    /** DICE's own event thread, created on the first published event when none is bound. */
    @Volatile
    private var ownEventExecutor: ExecutorService? = null

    /** Guards creating and closing [ownEventExecutor], and binding with [withEventExecutor]. */
    private val eventExecutorGuard = Any()

    /**
     * How long a direct caller waits for another extraction before giving up with
     * [ExtractionBusyException]: 30 seconds, matching SQLite's default busy timeout. A field rather
     * than a constant only so a test can shorten it.
     */
    private var directCallLockWaitMillis: Long = 30_000L

    /** True on a thread while it runs a published event, which may wait for the lock without limit. */
    private val runningPublishedEvent = ThreadLocal<Boolean>()

    /** Set by [close]. Once set, published events are refused instead of starting a new thread. */
    private var eventExecutorClosed = false

    /**
     * The application's `TaskDecorator`, when it has exactly one, applied to every event handed to
     * DICE's own thread, the way Spring Boot applies it to its own executors.
     */
    @Volatile
    private var taskDecorator: TaskDecorator? = null

    /**
     * Picks up the application's `TaskDecorator`, if it defines exactly one, so a context it carries,
     * such as a tenant or a no-logging marker, reaches DICE's own event thread. Spring calls this
     * when the extractor is a bean.
     */
    override fun setBeanFactory(beanFactory: BeanFactory) {
        taskDecorator = beanFactory.getBeanProvider(TaskDecorator::class.java).getIfUnique()
    }

    /**
     * Runs published events on [executor] instead of DICE's own thread.
     *
     * Use it when the work needs a `TaskDecorator` other than the application's single
     * `TaskDecorator` bean, which DICE's own thread already applies, or when you want to size the
     * queue yourself. Give extraction an executor
     * of its own: if the executor that runs published events is also one that a running extraction
     * waits on, such as the one DICE's concurrent chunk extraction uses, the two can wait for each
     * other forever. DICE does not shut a bound executor down; its owner does.
     *
     * The executor must throw `RejectedExecutionException` when it cannot take an event, as Spring's
     * `ThreadPoolTaskExecutor` does by default. One that drops tasks silently, such as a pool with
     * `DiscardPolicy`, loses the event without a log, and [isIdle] then never turns true. Published
     * events run outside any transaction a subclass declares on [onSourceAnalysisRequestEvent]; to
     * keep claims and the window in one transaction, call [extractPropositions] from your own
     * transactional method instead.
     *
     * Bind once, before the extractor handles its first published event.
     *
     * Java:
     * ```java
     * ThreadPoolTaskExecutor events = new ThreadPoolTaskExecutor();
     * events.setCorePoolSize(1);
     * events.setMaxPoolSize(1);
     * events.setQueueCapacity(500);
     * events.setTaskDecorator(tenantDecorator);
     * events.initialize();
     * extraction.withEventExecutor(events);
     * ```
     *
     * EXPERIMENTAL.
     *
     * @param executor Runs each published event.
     * @return this extractor.
     * @throws IllegalStateException if an executor is already bound, or a published event has
     *         already started DICE's own thread.
     */
    @ApiStatus.Experimental
    open fun withEventExecutor(executor: Executor): IncrementalPropositionExtraction {
        synchronized(eventExecutorGuard) {
            check(boundEventExecutor == null && ownEventExecutor == null) {
                "the event executor is already set on this extractor. Bind it once, before the " +
                    "extractor handles its first published event"
            }
            boundEventExecutor = executor
        }
        return this
    }

    /**
     * Counts a published event before it is handed to the executor, so [isIdle] sees events still
     * waiting there. [onSourceAnalysisRequestEvent] calls it, so counting and handing off happen in
     * the same listener and another listener cannot come between them.
     */
    open fun trackEvent(event: SourceAnalysisRequestEvent) {
        inFlightCount.incrementAndGet()
    }

    /**
     * Hands a published event to the event executor and returns at once. On that thread the event
     * waits for any extraction already running, then runs. If the executor refuses the event, it is
     * logged at `error` and dropped; the next event for the same source analyses the window again.
     * A failure while it runs is logged at `error`, because no caller is waiting for it.
     */
    @EventListener
    open fun onSourceAnalysisRequestEvent(event: SourceAnalysisRequestEvent) {
        trackEvent(event)
        // Releases the count [trackEvent] took, exactly once, whichever path ends the event: it runs,
        // the decorator around it fails, or it is never submitted.
        val released = AtomicBoolean(false)
        val release = { if (released.compareAndSet(false, true)) inFlightCount.decrementAndGet() }
        val publisher = Thread.currentThread()
        val task = Runnable {
            // An executor that runs the task inline, such as a pool with CallerRunsPolicy, runs it on
            // the publisher's thread, which may hold a transaction. It then waits like a direct caller.
            if (Thread.currentThread() !== publisher) runningPublishedEvent.set(true)
            try {
                extractPropositions(event)
            } catch (e: Exception) {
                logger.error("Extraction failed for a published SourceAnalysisRequestEvent", e)
            } finally {
                runningPublishedEvent.remove()
                release()
            }
        }
        try {
            val executor = eventExecutor()
            val decorated = if (executor === boundEventExecutor) task else taskDecorator?.decorate(task) ?: task
            executor.execute {
                try {
                    decorated.run()
                } finally {
                    release()
                }
            }
        } catch (e: Exception) {
            release()
            logger.error("A SourceAnalysisRequestEvent could not be handed to the event executor, so it was dropped", e)
        }
    }

    /**
     * Stops handing published events off, and stops DICE's own event thread if it was started.
     * Events already queued there get up to 30 seconds to finish, then the thread is interrupted.
     * A bound executor is not shut down, but no new events go to it. Spring calls this when the
     * context closes.
     */
    override fun close() {
        val own = synchronized(eventExecutorGuard) {
            eventExecutorClosed = true
            ownEventExecutor
        } ?: return
        own.shutdown()
        try {
            if (!own.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("Extraction events still running after {} seconds; interrupting them", CLOSE_TIMEOUT_SECONDS)
                countDiscarded(own.shutdownNow())
            }
        } catch (e: InterruptedException) {
            countDiscarded(own.shutdownNow())
            Thread.currentThread().interrupt()
        }
    }

    /** Releases the count of each queued event that shutdown removed before it ran. */
    private fun countDiscarded(discarded: List<Runnable>) {
        if (discarded.isEmpty()) return
        logger.warn("Dropping {} published event(s) still queued at shutdown", discarded.size)
        inFlightCount.addAndGet(-discarded.size)
    }

    private fun eventExecutor(): Executor = synchronized(eventExecutorGuard) {
        if (eventExecutorClosed) throw RejectedExecutionException("the extractor is closed")
        boundEventExecutor ?: ownEventExecutor ?: ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(EVENT_QUEUE_CAPACITY),
            // No inherited thread-locals: the first publisher's context must not stick to the thread
            // and leak into later events. The decorator carries each publisher's context per event.
            { task -> Thread(null, task, "dice-incremental-extraction", 0, false).apply { isDaemon = true } },
        ).also { ownEventExecutor = it }
    }

    /**
     * Returns true when no extraction is running, no events are queued,
     * and no events are in-flight (in Spring's async executor queue).
     */
    open val isIdle: Boolean
        get() = inFlightCount.get() <= 0 && pendingEvents.isEmpty() && !extractionLock.isLocked

    /**
     * Runs [event] on the calling thread, after any extraction already running.
     *
     * @param event The event to run.
     * @throws ExtractionBusyException if another extraction runs for more than 30 seconds while this
     *         call waits. The event was not run and can be retried.
     * @throws CancellationException if the thread is interrupted before [event] runs. The event is
     *         dropped and the interrupt flag stays set.
     */
    open fun extractPropositions(event: SourceAnalysisRequestEvent) {
        inFlightCount.incrementAndGet()
        if (extractionLock.isHeldByCurrentThread) {
            pendingEvents.add(event)
            logger.debug("Extraction requested from inside a drain, {} event(s) queued", pendingEvents.size)
            return
        }
        processOnThisThread(event)
    }

    /**
     * Extract propositions from a file via Tika and persist them.
     * Requires `embabel-agent-rag-tika` on the classpath.
     *
     * This is the signature that existed before requests, kept as its own declaration so it
     * stays overridable — see the note on [rememberText].
     */
    open fun rememberFile(
        inputStream: InputStream,
        filename: String,
        user: NamedEntity,
    ) = withRememberedFileText(inputStream, filename) { text ->
        // Deliberately the three-argument-era text signature. This is the dispatch this method
        // had before requests existed, so a subclass that overrides only that signature still
        // intercepts file ingestion the way it always did.
        rememberText(text, "remember:$filename", user, emptyList(), null, null)
    }

    /**
     * Extract propositions from a file via Tika, on the terms the [request] sets — the source it
     * was read from, the revision of that source, the content profile it runs under, the
     * extraction run it belongs to.
     *
     * A [request] that carries nothing hands straight back to the three-argument form, so a call
     * that looks like a pre-request call also dispatches like one.
     */
    open fun rememberFile(
        inputStream: InputStream,
        filename: String,
        user: NamedEntity,
        request: ExtractionRequest,
    ) {
        if (request.isEmpty) {
            rememberFile(inputStream, filename, user)
            return
        }
        withRememberedFileText(inputStream, filename) { text ->
            rememberText(
                text,
                "remember:$filename",
                user,
                emptyList(),
                null,
                null,
                request,
            )
        }
    }

    private fun withRememberedFileText(
        inputStream: InputStream,
        filename: String,
        remember: (String) -> Unit,
    ) {
        try {
            val reader = com.embabel.agent.rag.ingestion.TikaHierarchicalContentReader()
            val document = reader.parseContent(inputStream, "remember://$filename")

            val text = document.leaves().joinToString("\n\n") { it.text }.trim()
            if (text.isEmpty()) {
                logger.info("No text extracted from file: {}", filename)
                return
            }

            remember(text)
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
     *
     * This is the signature that existed before requests, and it stays its own declaration.
     * Growing it with a defaulted parameter would break subclasses: `@JvmOverloads` emits every
     * reduced-arity overload as `final`, so folding the new argument into this method would have
     * turned the six-argument form — the one a subclass overrides — into a final bridge.
     * Declaring the two shapes separately keeps both open. Unintercepted, every call lands on the
     * seven-argument
     * form below, so overriding that one sees everything; overriding this one sees everything a
     * subclass written before requests used to see, including file ingestion, which still
     * routes through here.
     */
    @JvmOverloads
    open fun rememberText(
        text: String,
        sourceId: String,
        user: NamedEntity,
        additionalGrounding: List<String> = emptyList(),
        perspective: ExtractionPerspective? = null,
        mintNewEntities: Boolean? = null,
    ) = rememberText(
        text,
        sourceId,
        user,
        additionalGrounding,
        perspective,
        mintNewEntities,
        ExtractionRequest.NONE,
    )

    /**
     * Extract propositions from raw text on the terms the [request] sets — the source it was read
     * from, the revision of that source, the content profile it runs under, the extraction run it
     * belongs to.
     *
     * Every other entry point funnels here, so this is the one method to override to see every
     * call. It is also where a new extraction dimension shows up: it arrives as a field on
     * [ExtractionRequest] and this signature stays as it is.
     *
     * [sourceId] keeps its old meaning — the exact caller-supplied chunk and grounding
     * identifier. DICE cannot read source identity or revision coverage out of an untyped
     * [sourceId] or out of [additionalGrounding], which is why the [request] carries them
     * explicitly.
     */
    open fun rememberText(
        text: String,
        sourceId: String,
        user: NamedEntity,
        additionalGrounding: List<String>,
        perspective: ExtractionPerspective?,
        mintNewEntities: Boolean?,
        request: ExtractionRequest,
    ) {
        val context = buildContext(
            user = user,
            sourceId = sourceId,
            perspective = perspective,
            mintNewEntities = mintNewEntities,
            request = request,
        )
        val result = propositionPipeline.processOnce(
            text, sourceId, context, additionalGrounding = additionalGrounding,
        )

        if (result != null && result.propositions.isNotEmpty()) {
            logger.info(result.infoString(true, 1))
            logStored(persistAndProject(result, context))
            logger.info("Remembered source: {}", sourceId)
        } else {
            logger.info("No propositions extracted from source: {}", sourceId)
        }
    }

    // -- internal ---------------------------------------------------------

    /**
     * Runs [event] on the calling thread under [extractionLock], then any events queued from inside
     * it on the same thread, and throws the first failure.
     *
     * Each caller runs its own event. A caller on another thread waits for the lock rather than
     * handing its event to the thread that holds it, so an event always runs on the thread, and
     * inside the transaction, of the caller that submitted it. One caller's failure can never roll
     * back another caller's work.
     *
     * A call made on the thread that already holds the lock, for example from a save listener, is
     * queued by [extractPropositions] and run here after the current window is recorded, so the
     * same window is not analysed twice. Those events belong to this caller, so their failures are
     * this caller's too. After the first failure only the events queued at that moment run, so a
     * listener that keeps queueing cannot hold the failure back. Each later failure is logged at
     * `warn`, and the first is rethrown unchanged.
     *
     * The thread's interrupt flag stops the run before the next event, and also stops a caller that
     * is waiting for the lock. If the caller's own event never ran, a [CancellationException] is
     * thrown so a host transaction can roll back. Events that do not run are dropped, and so are any events this run
     * queued but did not reach, so none of them runs on another caller's thread. Dropping one loses
     * nothing: an event only asks for the next window to be analysed, and the next event for that
     * source does the same, because a window is recorded only after its claims are stored.
     *
     * @param event The caller's event.
     */
    private fun processOnThisThread(event: SourceAnalysisRequestEvent) {
        try {
            if (runningPublishedEvent.get() == true) {
                // A published event holds no caller's transaction, so it can wait as long as it takes.
                extractionLock.lockInterruptibly()
            } else if (!extractionLock.tryLock(directCallLockWaitMillis, TimeUnit.MILLISECONDS)) {
                countDropped(1)
                throw ExtractionBusyException(
                    "Another extraction ran for more than ${directCallLockWaitMillis / 1000} seconds; " +
                        "this event was not run and can be retried",
                )
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            countDropped(1)
            throw CancellationException("Extraction interrupted while waiting for another run; its event was dropped")
        }
        var firstFailure: Exception? = null
        try {
            // How many more queued events this run may take. Unbounded until the first failure.
            var remaining = Int.MAX_VALUE
            var next: SourceAnalysisRequestEvent? = event
            // The caller's own event is the first one taken, whatever its identity, since a save
            // listener may queue the very same event object again.
            var ownEventStarted = false
            while (next != null) {
                if (Thread.currentThread().isInterrupted) {
                    countDropped(1)
                    if (!ownEventStarted) {
                        firstFailure = CancellationException("Extraction interrupted before its event ran; the event was dropped")
                    } else {
                        logger.info("Extraction interrupted, dropping the next event")
                    }
                    break
                }
                ownEventStarted = true
                try {
                    processEvent(next)
                } catch (e: Exception) {
                    if (firstFailure == null) remaining = pendingEvents.size
                    firstFailure = keepFirst(firstFailure, e)
                }
                if (remaining <= 0) break
                next = pendingEvents.poll()
                if (next != null && firstFailure != null) remaining--
            }
        } finally {
            // Whatever is still queued was queued by this run. Drop it here, so it never runs on
            // another caller's thread or inside another caller's transaction.
            var left = 0
            while (pendingEvents.poll() != null) left++
            if (left > 0) {
                logger.info("Dropping {} event(s) queued by this run that it did not reach", left)
                countDropped(left)
            }
            extractionLock.unlock()
        }
        firstFailure?.let { throw it }
    }

    /**
     * Balances the in-flight count for events that were dropped without being processed. Each call
     * to [extractPropositions] counts its event once, and processing or dropping it releases that.
     *
     * @param events How many events were dropped.
     */
    private fun countDropped(events: Int) {
        inFlightCount.addAndGet(-events)
    }

    /**
     * Returns the failure to rethrow at the end of a drain. The first failure wins and is not
     * changed, because a store can throw the same exception object on every call. A later one is
     * logged at `warn`.
     *
     * @param first The failure kept so far, or null.
     * @param later The failure just caught.
     * @return The failure to keep.
     */
    private fun keepFirst(first: Exception?, later: Exception): Exception {
        if (first == null) return later
        logger.warn("Extraction failed again after an earlier failure in the same drain", later)
        return first
    }

    /**
     * Processes one event: builds its context, analyses the next window, stores the claims, records
     * the window as processed, then projects and grounds what was stored.
     *
     * The window is recorded only after its claims are stored. If the store fails, the window stays
     * unrecorded and the next event for the same source analyses it again.
     *
     * A failure before the store step starts, while building the context or analysing the window, is
     * logged and swallowed, and the next event tries the window again. From the store step onwards
     * (storing, recording the window, projection and grounding) a failure is thrown to
     * [processOnThisThread], which passes it on to whoever called [extractPropositions]. A host that
     * runs extraction inside its own transaction has to see that failure to roll the transaction
     * back. A [LineageNotRecordedException] that escapes context building or analysis is thrown too,
     * and is logged at `error` with the run id. One that a provider throws inside context building
     * is caught there and logged like any other provider failure.
     *
     * Catching an [InterruptedException] clears the thread's interrupt flag, so whenever this method
     * catches one, or a write step failed with one, it sets the flag again and the drain stops.
     *
     * Every exception thrown from here is unchecked, so a Java caller can catch it and a Spring
     * `@Transactional` method rolls back on it by default. A failed write is thrown the way
     * [PersistOutcome.persistedOrThrow] throws it, with or without a writer bound: a
     * `RuntimeException` as it is, a checked cause inside a [PersistUnitFailedException] with its
     * step. Any other checked exception, from recording the window, projection or grounding, is
     * wrapped in a `RuntimeException` that keeps it as the cause.
     *
     * The writer's outcome decides whether the window is recorded after a failed write. With no
     * writer bound, a failure at [PersistStep.RUN_LINKS] with [PersistStep.PROPOSITIONS] left in the
     * store means the claims are stored and only their attribution is missing. The window is
     * recorded, so the same claims are not stored a second time, and the failure is thrown once the
     * window is recorded. Projection and grounding do not run. If recording the window fails too,
     * the run link failure is logged at `error` and added to the recording failure as a suppressed
     * exception. Every other failed write leaves the window unrecorded. With a writer bound, that is
     * every failed write, because the writer may have undone the claims.
     *
     * @param event The event to process.
     */
    private fun processEvent(event: SourceAnalysisRequestEvent) {
        var storeStarted = false
        var writeInterrupted = false
        var sourceId: String? = null
        try {
            val source = event.incrementalSource()
            sourceId = source.id
            if (source.size < windowConfig.overlapSize) {
                logger.info(
                    "Source {} has {} items, need at least {} for extraction",
                    source.id, source.size, windowConfig.overlapSize,
                )
                return
            }

            // The async path grounds propositions exactly the way a direct call does: whatever the
            // event carries becomes a request and goes through the same buildContext call.
            val context = buildContext(
                user = event.user,
                sourceId = source.id,
                request = ExtractionRequest(
                    sourceLocator = event.sourceLocator(),
                    sourceRevision = event.sourceRevision(),
                    profile = event.profile(),
                    currentRun = event.currentRun(),
                ),
            )
            logger.info(
                "Context relations count: {}, injected relations count: {}",
                context.relations.size(), relations.size(),
            )

            var stored: PropositionPersistenceResult? = null
            var deferredFailure: RuntimeException? = null
            val result = try {
                analyzer.analyzeThenRecord(source, context) { analysed ->
                    storeStarted = true
                    if (analysed.propositions.isNotEmpty()) {
                        logger.info(analysed.infoString(true, 1))
                        val boundWriter = persistUnitWriter
                        when (val outcome = writeUnit(analysed, context, boundWriter)) {
                            is PersistOutcome.Stored -> stored = outcome.persisted
                            is PersistOutcome.Failed -> {
                                // The flag is set again in the finally block, after the window is
                                // recorded, so an interruptible history store is not cut short.
                                writeInterrupted = outcome.cause is InterruptedException
                                val failure = thrownBy(outcome)
                                val claimsStored = boundWriter == null &&
                                    outcome.step == PersistStep.RUN_LINKS &&
                                    PersistStep.PROPOSITIONS in outcome.leftInStore
                                if (!claimsStored) throw failure
                                deferredFailure = failure
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                deferredFailure?.let { deferred ->
                    logger.error(
                        "Claims for source {} are stored but their run links failed, and the window " +
                            "could not be recorded",
                        source.id, deferred,
                    )
                    if (deferred !== e) e.addSuppressed(deferred)
                }
                throw e
            } ?: run {
                logger.info("Analysis skipped (not ready or already processed)")
                return
            }
            deferredFailure?.let { throw it }

            if (result.propositions.isEmpty()) {
                logger.info("Analysis completed but no propositions extracted")
                return
            }

            stored?.let {
                projectStored(it)
                logStored(it)
            }
        } catch (e: LineageNotRecordedException) {
            logger.error(
                "Lineage not recorded for extraction run {}; attribution was asked for and could " +
                    "not be recorded",
                e.key.runRef.runId, e,
            )
            throw e
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            if (storeStarted) {
                throw e as? RuntimeException
                    ?: RuntimeException("Extraction failed for source $sourceId after its store step started", e)
            }
            logger.warn("Failed to extract propositions", e)
        } finally {
            if (writeInterrupted) Thread.currentThread().interrupt()
            inFlightCount.decrementAndGet()
        }
    }

    /**
     * Returns the exception [PersistOutcome.persistedOrThrow] throws for a failed write.
     *
     * @param outcome The failed write.
     * @return A `RuntimeException` cause as it is, or a checked one inside a [PersistUnitFailedException].
     */
    private fun thrownBy(outcome: PersistOutcome.Failed): RuntimeException {
        try {
            outcome.persistedOrThrow()
        } catch (e: RuntimeException) {
            return e
        }
        throw IllegalStateException("A failed write returned a result")
    }

    private fun buildContext(
        user: NamedEntity,
        sourceId: String = "",
        perspective: ExtractionPerspective? = null,
        mintNewEntities: Boolean? = null,
        request: ExtractionRequest = ExtractionRequest.NONE,
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
                KnownEntityResolver.withKnownEntities(allKnown, entityResolver, nameNormalizer),
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
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.warn("[buildContext] mintedEntityPropertiesProvider interrupted for {}", sourceId, e)
                emptyMap()
            } catch (e: Exception) {
                logger.warn("mintedEntityPropertiesProvider failed for {}: {}", user.name, e.message)
                emptyMap()
            }
            if (stamped.isNotEmpty()) {
                ctx = ctx.withMintedEntityProperties(stamped)
            }
        }
        // The request is the one door everything the caller asked for comes through, so the
        // context and the request always agree about a call.
        request.sourceLocator?.let { ctx = ctx.withSourceLocator(it) }
        request.sourceRevision?.let { ctx = ctx.withSourceRevision(it) }
        // The profile is carried and never consulted. Nothing downstream of here reads it — that is
        // what "DICE holds profile identity and the host binds policy" means in code.
        request.profile?.let { ctx = ctx.withProfile(it) }
        // The run is read in exactly one place: persistAndProject writes a lineage link for it. It
        // changes nothing else about what gets stored. See persistAndProject.
        request.currentRun?.let { ctx = ctx.withCurrentRun(it) }
        return ctx
    }

    /**
     * Stores what an analysis produced through a [PersistUnitWriter], then projects and grounds it.
     *
     * The write is one [PersistUnit]: the claims, the entities they mention, the run link and the
     * structural relationships. With no writer bound, the sequential writer makes those calls one
     * after another on the repositories this extractor was constructed with.
     *
     * Projection and grounding run over the propositions the repository returned. A deduplicating
     * backend answers a fresh insert with the proposition it already holds, so the id extraction
     * minted can name a node that was never stored. The returned propositions are the stored ones.
     *
     * A run on the analysis adds the run link to the unit. It changes nothing else about what is
     * stored.
     *
     * With the sequential writer, a [LineageFailurePolicy.STRICT] lineage failure leaves the entities
     * and claims in the store, unattributed. Structural relationships, projection and grounding do
     * not run, and the [LineageNotRecordedException] is thrown. Inside a caller's transaction, those
     * writes commit or roll back with it.
     * Under [LineageFailurePolicy.LENIENT] the failure is logged as a warning and the rest runs.
     * A bound writer decides what a failed write leaves in the store.
     *
     * A failed write throws, and projection and grounding do not run. With no writer bound, the
     * exception is the one the failed step threw, whatever its type. With a writer bound, it is what
     * [PersistOutcome.persistedOrThrow] throws.
     *
     * A failure from projection or grounding is thrown too. Every failure here reaches the caller,
     * because a host that runs extraction inside its own transaction has to see it to roll the
     * transaction back.
     *
     * @param result What the analysis produced.
     * @param context The analysis the result belongs to. Its run, if any, is attributed.
     */
    private fun persistAndProject(
        result: ChunkPropositionResult,
        context: SourceAnalysisContext,
    ): PropositionPersistenceResult = storeUnit(result, context).also { projectStored(it) }

    /**
     * Writes what an analysis produced as one [PersistUnit] and returns what the store holds for its
     * claims. The failure rules are the ones described on [persistAndProject].
     *
     * @param result What the analysis produced.
     * @param context The analysis the result belongs to. Its run, if any, is attributed.
     * @return What the repository returned for the saved propositions.
     */
    private fun storeUnit(result: ChunkPropositionResult, context: SourceAnalysisContext): PropositionPersistenceResult {
        val boundWriter = persistUnitWriter
        // A bound writer's checked cause is wrapped in PersistUnitFailedException, so a Java caller
        // can catch it. With no writer bound the cause is thrown as it is, whatever its type.
        return when (val outcome = writeUnit(result, context, boundWriter)) {
            is PersistOutcome.Stored -> outcome.persisted
            is PersistOutcome.Failed -> if (boundWriter == null) throw outcome.cause else outcome.persistedOrThrow()
        }
    }

    /**
     * Writes what an analysis produced as one [PersistUnit] and returns how the write ended.
     *
     * With no writer bound, the sequential writer runs the steps on this extractor's repositories.
     * Run lineage is logged either way, and the saved counts are logged when the unit is stored.
     *
     * @param result What the analysis produced.
     * @param context The analysis the result belongs to. Its run, if any, is attributed.
     * @param boundWriter The writer bound when the caller started, or null for the sequential writer.
     * @return The writer's outcome.
     */
    private fun writeUnit(
        result: ChunkPropositionResult,
        context: SourceAnalysisContext,
        boundWriter: PersistUnitWriter?,
    ): PersistOutcome {
        val currentRun = context.currentRun
        val unit = PersistUnit.of(result).let { if (currentRun != null) recordRunLineage(it, context, currentRun) else it }
        val newEntitiesToSave = unit.newEntities.size

        val stats = result.propositionExtractionStats
        val newProps = stats.newCount
        val updatedProps = stats.mergedCount + stats.reinforcedCount

        for (entity in result.newEntities()) {
            logger.info("New entity: name='{}', labels={}", entity.name, entity.labels())
        }
        for (entity in result.updatedEntities()) {
            logger.info("Updated entity: name='{}', labels={}", entity.name, entity.labels())
        }

        val outcome = withExtractionAuditRun(context) {
            if (boundWriter == null) {
                // Built per call, because withRunLineage can bind the run link store after construction.
                // The lineage lines are logged between the run link and the structural relationships, so
                // an Error from the relationships cannot lose them.
                SequentialPersistUnitWriter(propositionRepository, entityRepository, propositionRunLinkStore) { key, runLinks, lineageFailure ->
                    logRunLineage(key.runRef.runId, runLinks, lineageFailure)
                }.write(unit)
            } else {
                val written = boundWriter.write(unit)
                if (currentRun != null) logRunLineage(currentRun.runId, written.runLinks, written.lineageFailure)
                written
            }
        }
        if (outcome is PersistOutcome.Failed) return outcome

        if (newProps > 0 || updatedProps > 0 || newEntitiesToSave > 0) {
            logger.info(
                "Persisted: {} new propositions, {} updated propositions, {} new entities",
                newProps, updatedProps, newEntitiesToSave,
            )
        } else {
            logger.info("No new data to persist (all propositions were duplicates)")
        }
        return outcome
    }

    /**
     * Projects the stored propositions into graph relationships, then grounds them when a grounding
     * service is set.
     *
     * @param persisted What the repository returned for the saved propositions.
     */
    private fun projectStored(persisted: PropositionPersistenceResult) {
        // The distinct view, not the positional one. Inputs that deduplicated together are one
        // stored proposition, and projecting or grounding it once per input inflates the records
        // written about that work even though the edges themselves are idempotent.
        val toWire = persisted.distinctCanonicalPropositions

        val projectionResult = graphProjectionService.projectAndPersist(toWire)
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
        groundingWiringService?.wire(toWire)
    }

    /**
     * Adds the run to the unit, so the writer links the unit's claims to it.
     *
     * @param unit What the chunk produced.
     * @param context The analysis the chunk belongs to. Its context id scopes the run.
     * @param currentRun The run the analysis carries.
     * @return The unit with the run and this extractor's lineage policy.
     */
    private fun recordRunLineage(unit: PersistUnit, context: SourceAnalysisContext, currentRun: ExtractionRunRef): PersistUnit =
        unit.withRun(ExtractionRunKey(context.contextId, currentRun), lineageFailurePolicy)

    /**
     * Logs how linking a unit's claims to its run went.
     *
     * A recorded link is logged with its count. A lineage failure is logged as a warning under
     * [LineageFailurePolicy.LENIENT] and not logged under [LineageFailurePolicy.STRICT], where it is
     * thrown.
     *
     * @param runId The id of the run the claims came from.
     * @param runLinks What the run link store returned, or null when no link call was made.
     * @param lineageFailure Set when a link was asked for and not recorded.
     */
    private fun logRunLineage(runId: String, runLinks: Int?, lineageFailure: LineageNotRecordedException?) {
        if (lineageFailure == null) {
            runLinks?.let { logger.info("Attributed {} propositions to extraction run {}", it, runId) }
        } else if (lineageFailurePolicy == LineageFailurePolicy.LENIENT) {
            logger.warn(
                "Lineage not recorded for extraction run {}; continuing under LENIENT policy",
                runId, lineageFailure,
            )
        }
    }

    /**
     * Logs the propositions this extraction stored, at `debug`. It uses what is already in memory,
     * so it reads nothing from the store and cannot fail or disturb the host's transaction.
     *
     * @param persisted What the repository returned for the saved propositions.
     */
    private fun logStored(persisted: PropositionPersistenceResult) {
        if (!logger.isDebugEnabled) return
        val stored = persisted.distinctCanonicalPropositions.sortedBy { it.text }
        logger.debug("Stored {} proposition(s):", stored.size)
        for (p in stored) {
            logger.debug("  [{}] confidence={} '{}'", p.status, p.confidence, p.text)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(IncrementalPropositionExtraction::class.java)

        /** How many published events DICE's own thread holds before it refuses more. */
        private const val EVENT_QUEUE_CAPACITY = 1_000

        /** How long [close] lets queued events finish before it interrupts them. */
        private const val CLOSE_TIMEOUT_SECONDS = 30L
    }
}

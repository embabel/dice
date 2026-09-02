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
import com.embabel.dice.proposition.PropositionPersistenceResult
import com.embabel.dice.proposition.PropositionRepository
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import java.io.InputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
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
     */
    open fun withRunLineage(
        propositionRunLinkStore: PropositionRunLinkStore?,
        policy: LineageFailurePolicy,
    ): IncrementalPropositionExtraction {
        check(runLineageBound.compareAndSet(false, true)) {
            "run lineage is already bound on this extractor; it binds once, before the extractor " +
                "starts handling events, so an analysis in flight cannot have its audit record " +
                "redirected or erased"
        }
        this.propositionRunLinkStore = propositionRunLinkStore
        this.lineageFailurePolicy = policy
        return this
    }
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
            persistAndProject(result, context)
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

            val result = analyzer.analyze(source, context) ?: run {
                logger.info("Analysis skipped (not ready or already processed)")
                return
            }

            if (result.propositions.isEmpty()) {
                logger.info("Analysis completed but no propositions extracted")
                return
            }

            logger.info(result.infoString(true, 1))
            persistAndProject(result, context)
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
     * Persists what an analysis produced, then projects and grounds it.
     *
     * **Everything downstream of the save runs over the propositions the repository returned, on
     * every call.** A deduplicating backend answers a fresh insert with the proposition that
     * already exists, so the id extraction minted can name a node that was never stored. Projecting
     * or grounding against that id writes an edge pointing at nothing. The propositions the save
     * handed back are the ones the store actually holds, and they are what the wiring passes get.
     *
     * **Audit metadata never changes product behaviour, so a run is not a switch.** This used to
     * take the canonical propositions only when the analysis carried a run, which made an audit
     * setting decide whether the graph was written correctly — a host that turned on extraction runs
     * silently got different edges, and a host that did not kept the phantom ones. Whether anyone is
     * recording lineage is a question about the audit trail and has no business changing what gets
     * stored. A run now adds one thing: the lineage write below. It subtracts and alters nothing.
     *
     * **Lineage is written last, after projection and grounding have both completed.** It is the
     * final step because it is the only one whose failure is allowed to be loud: under
     * [LineageFailurePolicy.STRICT] a lineage failure fails the whole operation, and putting it
     * anywhere earlier would mean raising out of the middle of the pipeline with the claims saved
     * and the graph half-written. Running it last means the state a STRICT failure leaves behind is
     * a complete one — claims persisted, structural edges wired, projection and grounding done, and
     * no `PRODUCED_BY_RUN` edge — so the only thing missing is the audit record the caller is being
     * told about. See [recordRunLineage] for exactly what that end state is.
     *
     * How durable any of it is depends on the caller: with no ambient transaction each write has
     * committed as it was made, and with one they are all still the caller's to commit or roll back.
     */
    private fun persistAndProject(result: ChunkPropositionResult, context: SourceAnalysisContext) {
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

        val currentRun = context.currentRun
        // Saving only; the structural edges follow immediately below. The two are separate calls
        // because the canonical propositions the save returns are what everything after it wires
        // against.
        val persisted = result.persistCanonicalPropositions(propositionRepository, entityRepository)
        // The distinct view, not the positional one. Inputs that deduplicated together are one
        // stored proposition, and projecting or grounding it once per input inflates the records
        // written about that work even though the edges themselves are idempotent.
        val toWire = persisted.distinctCanonicalPropositions

        result.wireStructuralRelationships(persisted, entityRepository)

        if (newProps > 0 || updatedProps > 0 || newEntitiesToSave > 0) {
            logger.info(
                "Persisted: {} new propositions, {} updated propositions, {} new entities",
                newProps, updatedProps, newEntitiesToSave,
            )
        } else {
            logger.info("No new data to persist (all propositions were duplicates)")
        }

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

        // Lineage goes last, once the claims are stored and the whole graph around them is written.
        // Under STRICT this call can fail the operation, and the state it leaves behind when it does
        // is a complete extraction that simply has no audit edge. See recordRunLineage.
        if (currentRun != null) {
            recordRunLineage(context, currentRun, persisted)
        }
    }

    /**
     * Attributes the canonical propositions to the run that produced them.
     *
     * **Loud by default.** A host that gives an extraction a run has asked for attribution, and
     * [LineageFailurePolicy.STRICT] — the default — treats "it could not be recorded" as a failure
     * of that call. Both ways attribution can go missing fail: no store bound with a run set, and a
     * link write that throws. [LineageFailurePolicy.LENIENT] logs each and carries on, for a host
     * that has decided in configuration that the claims outweigh their audit trail.
     *
     * This used to swallow every `RuntimeException` and return quietly on a missing store, which
     * meant the two failures an operator most needs to hear about — lineage wired wrong, and lineage
     * refusing writes — both reported success. See [LineageFailurePolicy] for why that is the wrong
     * default for an audit surface.
     *
     * **The end state a STRICT failure leaves behind, exactly.** This runs last, after structural
     * wiring, projection and grounding have all completed. So when it raises, the extraction itself
     * is finished and consistent: the canonical claims are persisted, their structural edges are
     * wired, the projection has run and grounding has run. The single thing missing is the
     * `PRODUCED_BY_RUN` edge. The operation is reported as failed, and what failed is the
     * attribution, with everything it was going to attribute already in place.
     *
     * That ordering is the point. Recording lineage earlier — behind the save, ahead of the fallible
     * passes — would attribute claims sooner, but a STRICT failure would then raise out of the
     * middle of the pipeline and leave the claims saved with projection and grounding silently
     * skipped: a partial state nobody declared. Attribution is a statement about work that is
     * finished, so it is made when the work is finished.
     *
     * [LineageFailurePolicy.LENIENT] reaches the same end state and reports success, with the
     * failure in the log.
     *
     * **What a raised failure costs depends on who owns the transaction.** With no ambient
     * transaction — the shape every entry point takes unless a host wraps it — everything above
     * committed as it was written, so the caller learns that a complete extraction is unattributed.
     * Inside a host's `@Transactional`, all of it shares that transaction's fate and the failure
     * rolls the whole extraction back, which is what strict attribution asks for. A lineage failure
     * raised by the database itself, below the store's own checks, has already terminated that
     * transaction either way, and no policy here can undo that.
     *
     * An analysis that saved nothing records nothing and is not a failure under either policy: there
     * is no claim for the audit to be missing.
     */
    private fun recordRunLineage(
        context: SourceAnalysisContext,
        currentRun: ExtractionRunRef,
        persisted: PropositionPersistenceResult,
    ) {
        val key = ExtractionRunKey(context.contextId, currentRun)
        val linkStore = propositionRunLinkStore
        if (linkStore == null) {
            // A wiring mistake, true of every call this extractor will ever make: this analysis asked
            // to be attributed and nothing can record it.
            onLineageFailure(
                key,
                LineageNotRecordedException(
                    key,
                    "analysis carries extraction run ${currentRun.runId} and no " +
                        "PropositionRunLinkStore is bound, so its propositions cannot be attributed; " +
                        "bind one with withRunLineage, or bind LineageFailurePolicy.LENIENT to accept " +
                        "the gap",
                ),
            )
            return
        }
        if (persisted.canonicalIds.isEmpty()) return
        try {
            val linked = linkStore.link(key, persisted.canonicalIds)
            logger.info("Attributed {} propositions to extraction run {}", linked, currentRun.runId)
        } catch (e: RuntimeException) {
            onLineageFailure(
                key,
                LineageNotRecordedException(
                    key,
                    "could not attribute ${persisted.canonicalIds.size} proposition(s) to extraction " +
                        "run ${currentRun.runId}",
                    e,
                ),
            )
        }
    }

    /**
     * Raises or logs, per the bound policy.
     *
     * The whole exception goes to the logger under [LineageFailurePolicy.LENIENT], with its class
     * and stack. A scope rejection means this analysis's context disagrees with the tenant its own
     * propositions were saved under, which is a pipeline bug; an outage looks quite different, and
     * the stack is what tells them apart.
     */
    private fun onLineageFailure(key: ExtractionRunKey, failure: LineageNotRecordedException) {
        when (lineageFailurePolicy) {
            LineageFailurePolicy.STRICT -> throw failure
            LineageFailurePolicy.LENIENT -> logger.warn(
                "Lineage not recorded for extraction run {}; continuing under LENIENT policy",
                key.runRef.runId, failure,
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

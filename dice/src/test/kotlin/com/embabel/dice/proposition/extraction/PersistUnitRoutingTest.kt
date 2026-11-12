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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntity
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository
import com.embabel.dice.common.NewEntity
import com.embabel.dice.common.Relations
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.pipeline.ChunkPropositionResult
import com.embabel.dice.pipeline.PropositionPipeline
import com.embabel.dice.projection.graph.GraphProjectionService
import com.embabel.dice.projection.graph.ProjectedRelationship
import com.embabel.dice.projection.graph.RelationshipPersistenceResult
import com.embabel.dice.projection.grounding.GroundingWiringService
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.ProjectionResults
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionPersistenceResult
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.SuggestedPropositions
import com.embabel.dice.proposition.store.InMemoryPropositionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import java.io.IOException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * How the extractor stores each chunk: through the sequential writer when none is bound, and through
 * the bound writer otherwise.
 */
class PersistUnitRoutingTest {

    private val tenant = ContextId("routing-tenant")

    private val runRef = ExtractionRunRef("routing-run")

    private val extracted = Proposition(
        id = "p-1",
        contextId = tenant,
        text = "Alice likes coffee",
        mentions = listOf(
            EntityMention(span = "Alice", type = "Person", resolvedId = "e-alice", role = MentionRole.SUBJECT),
        ),
        confidence = 0.9,
        grounding = listOf("chunk-1"),
    )

    private val result = ChunkPropositionResult.Success(
        chunkId = "chunk-1",
        suggestedPropositions = SuggestedPropositions("chunk-1", emptyList()),
        entityResolutions = Resolutions<SuggestedEntityResolution>(
            setOf("chunk-1"),
            listOf(
                NewEntity(SuggestedEntity(labels = listOf("Person"), name = "Alice", summary = "test", chunkId = "chunk-1", id = "e-alice")),
                NewEntity(SuggestedEntity(labels = listOf("Person"), name = "Bob", summary = "test", chunkId = "chunk-1", id = "e-bob")),
            ),
        ),
        propositions = listOf(extracted),
    )

    /**
     * A user whose id is the tenant.
     *
     * @return A relaxed mock user named Alice.
     */
    private fun user(): NamedEntity = mockk<NamedEntity>(relaxed = true).also {
        every { it.id } returns tenant.value
        every { it.name } returns "Alice"
    }

    /**
     * A projection service that projects nothing.
     *
     * @return A mocked projection service.
     */
    private fun projection(): GraphProjectionService = mockk<GraphProjectionService>().also {
        every { it.projectAndPersist(any()) } returns
            (ProjectionResults<ProjectedRelationship>(emptyList()) to RelationshipPersistenceResult(persistedCount = 0, failedCount = 0))
    }

    /**
     * A proposition repository mock that answers each save with the propositions it was given.
     *
     * @return A relaxed mocked repository.
     */
    private fun answeringRepository(): PropositionRepository = mockk<PropositionRepository>(relaxed = true).also {
        every { it.saveAllReturningCanonical(any()) } answers {
            val inputs = firstArg<Collection<Proposition>>().toList()
            PropositionPersistenceResult.of(inputs, inputs)
        }
    }

    /**
     * An extractor whose pipeline returns [result] for every call.
     *
     * @param propositionRepository The repository given to the constructor.
     * @param entityRepository The entity repository given to the constructor.
     * @param projection The projection service.
     * @param grounding The grounding service, or null for none.
     * @return An extractor with nothing bound.
     */
    private fun extraction(
        propositionRepository: PropositionRepository,
        entityRepository: NamedEntityDataRepository = mockk(relaxed = true),
        projection: GraphProjectionService = projection(),
        grounding: GroundingWiringService? = null,
    ): IncrementalPropositionExtraction {
        val pipeline = mockk<PropositionPipeline>()
        every { pipeline.processOnce(any(), any(), any(), any(), any(), any()) } returns result
        return IncrementalPropositionExtraction(
            propositionPipeline = pipeline,
            chunkHistoryStore = mockk(relaxed = true),
            dataDictionary = DataDictionary.fromClasses("routing"),
            relations = Relations.empty(),
            propositionRepository = propositionRepository,
            entityRepository = entityRepository,
            entityResolver = mockk(relaxed = true),
            graphProjectionService = projection,
            properties = PropositionExtractionProperties(windowSize = 1, overlapSize = 1, triggerInterval = 1),
            groundingWiringService = grounding,
        )
    }

    /**
     * Calls the request form of `rememberText`.
     *
     * @param currentRun The run the request names, or null for none.
     */
    private fun IncrementalPropositionExtraction.remember(currentRun: ExtractionRunRef? = runRef) =
        rememberText("Alice likes coffee", "source-1", user(), emptyList(), null, null, ExtractionRequest(currentRun = currentRun))

    /**
     * Runs [block] and collects what the extractor logged while it ran.
     *
     * @param block The call to observe.
     * @return The log events, in order.
     */
    private fun logged(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(IncrementalPropositionExtraction::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also {
            it.start()
            logger.addAppender(it)
        }
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
    }

    @Test
    fun `E1 with no writer bound the mentioned entity is saved and the calls run in order`() {
        val propositions = answeringRepository()
        val entities = mockk<NamedEntityDataRepository>(relaxed = true)
        val links = mockk<PropositionRunLinkStore>()
        every { links.link(any(), any<Collection<String>>()) } returns 1
        val projection = projection()
        val extraction = extraction(propositions, entities, projection).withRunLineage(links)

        extraction.remember()

        verify(exactly = 0) { entities.save(match { it.id == "e-bob" }) }
        verifyOrder {
            entities.save(match { it.id == "e-alice" })
            propositions.saveAllReturningCanonical(any())
            links.link(ExtractionRunKey(tenant, runRef), listOf("p-1"))
            entities.mergeRelationship(any(), any(), any())
            projection.projectAndPersist(any())
        }
    }

    @Test
    fun `E2 a bound writer receives one unit per call and the constructor repository is never written`() {
        val propositions = mockk<PropositionRepository>(relaxed = true)
        val units = mutableListOf<PersistUnit>()
        val writer = PersistUnitWriter { unit ->
            units += unit
            PersistOutcome.stored(PropositionPersistenceResult.of(unit.propositions, unit.propositions))
        }
        val extraction = extraction(propositions)
            .withRunLineage(null, LineageFailurePolicy.LENIENT)
            .withPersistUnitWriter(writer)

        extraction.remember(runRef)
        extraction.remember(null)

        assertThat(units).hasSize(2)
        assertThat(units[0].propositions).containsExactly(extracted)
        assertThat(units[0].run).isEqualTo(ExtractionRunKey(tenant, runRef))
        assertThat(units[0].lineagePolicy).isEqualTo(LineageFailurePolicy.LENIENT)
        assertThat(units[1].propositions).containsExactly(extracted)
        assertThat(units[1].run).isNull()
        verify(exactly = 0) { propositions.save(any()) }
        verify(exactly = 0) { propositions.saveAll(any()) }
        verify(exactly = 0) { propositions.saveAllReturningCanonical(any()) }
    }

    @Test
    fun `E3 a failed outcome throws its cause and skips projection and grounding`() {
        val cause = IllegalStateException("claims store down")
        val projection = projection()
        val grounding = mockk<GroundingWiringService>(relaxed = true)
        val extraction = extraction(answeringRepository(), projection = projection, grounding = grounding)
            .withPersistUnitWriter { PersistOutcome.failed(PersistStep.PROPOSITIONS, cause, setOf(PersistStep.PROPOSITIONS)) }

        assertThatThrownBy { extraction.remember() }.isSameAs(cause)
        verify(exactly = 0) { projection.projectAndPersist(any()) }
        verify(exactly = 0) { grounding.wire(any()) }
    }

    @Test
    fun `E4 a strict lineage failure through the in-memory writer leaves no claim, and through the default writer the claim stays`() {
        val schema = DataDictionary.fromClasses("routing")
        val runs = InMemoryExtractionRunStore()

        val claims = InMemoryPropositionRepository()
        val entities = InMemoryNamedEntityDataRepository(schema)
        val links = InMemoryPropositionRunLinkStore(runs, claims)
        val bound = extraction(claims, entities)
            .withRunLineage(null, LineageFailurePolicy.STRICT)
            .withPersistUnitWriter(PersistUnitWriter.inMemory(claims, entities, links))

        assertThatThrownBy { bound.remember() }.isInstanceOf(LineageNotRecordedException::class.java)
        assertThat(claims.findAll()).isEmpty()

        val defaultClaims = InMemoryPropositionRepository()
        val defaultEntities = InMemoryNamedEntityDataRepository(schema)
        val defaultLinks = InMemoryPropositionRunLinkStore(runs, defaultClaims)
        val unbound = extraction(defaultClaims, defaultEntities).withRunLineage(defaultLinks, LineageFailurePolicy.STRICT)

        assertThatThrownBy { unbound.remember() }.isInstanceOf(LineageNotRecordedException::class.java)
        assertThat(defaultClaims.findAll().map { it.id }).containsExactly("p-1")
    }

    @Test
    fun `E5 binding a writer returns the extractor, binds once, and excludes a run link store`() {
        val writer = PersistUnitWriter { error("never called") }
        val store = mockk<PropositionRunLinkStore>()

        val extraction = extraction(answeringRepository())
        assertThat(extraction.withPersistUnitWriter(writer)).isSameAs(extraction)
        assertThatThrownBy { extraction.withPersistUnitWriter(writer) }.isInstanceOf(IllegalStateException::class.java)

        val storeFirst = extraction(answeringRepository()).withRunLineage(store)
        assertThatThrownBy { storeFirst.withPersistUnitWriter(writer) }.isInstanceOf(IllegalStateException::class.java)

        val writerFirst = extraction(answeringRepository()).withPersistUnitWriter(writer)
        assertThatThrownBy { writerFirst.withRunLineage(store) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `E6 under lenient a lineage warning is logged and a relationship failure is thrown`() {
        val relationshipFailure = IllegalStateException("relationships down")
        val entities = mockk<NamedEntityDataRepository>(relaxed = true)
        every { entities.mergeRelationship(any(), any(), any()) } throws relationshipFailure
        val links = mockk<PropositionRunLinkStore>()
        every { links.link(any(), any<Collection<String>>()) } throws IllegalStateException("link store down")
        val extraction = extraction(answeringRepository(), entities).withRunLineage(links, LineageFailurePolicy.LENIENT)

        val logger = LoggerFactory.getLogger(IncrementalPropositionExtraction::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also {
            it.start()
            logger.addAppender(it)
        }
        try {
            assertThatThrownBy { extraction.remember() }.isSameAs(relationshipFailure)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }

        assertThat(appender.list)
            .anyMatch { it.level == Level.WARN && it.formattedMessage.startsWith("Lineage not recorded for extraction run") }
    }

    @Test
    fun `E7 a strict run with nothing bound names withRunLineage in the failure`() {
        val extraction = extraction(answeringRepository())

        assertThatThrownBy { extraction.remember() }
            .isInstanceOf(LineageNotRecordedException::class.java)
            .hasMessageContaining("bind one with withRunLineage")
    }

    @Test
    fun `E8 with no writer bound a checked exception from the claim save is thrown unchanged`() {
        val cause = IOException("claims store unreachable")
        val propositions = mockk<PropositionRepository>(relaxed = true)
        every { propositions.saveAllReturningCanonical(any()) } throws cause
        val projection = projection()
        val extraction = extraction(propositions, projection = projection)

        assertThatThrownBy { extraction.remember(null) }.isSameAs(cause)
        verify(exactly = 0) { projection.projectAndPersist(any()) }
    }

    @Test
    fun `E9 under lenient the lineage warning is logged when the relationships throw an Error`() {
        val relationshipError = Error("relationships died")
        val entities = mockk<NamedEntityDataRepository>(relaxed = true)
        every { entities.mergeRelationship(any(), any(), any()) } throws relationshipError
        val links = mockk<PropositionRunLinkStore>()
        every { links.link(any(), any<Collection<String>>()) } throws IllegalStateException("link store down")
        val extraction = extraction(answeringRepository(), entities).withRunLineage(links, LineageFailurePolicy.LENIENT)

        val events = logged { assertThatThrownBy { extraction.remember() }.isSameAs(relationshipError) }

        assertThat(events)
            .anyMatch { it.level == Level.WARN && it.formattedMessage.startsWith("Lineage not recorded for extraction run") }
    }

    @Test
    fun `E10 a bound writer's lenient lineage failure is logged as a warning`() {
        val key = ExtractionRunKey(tenant, runRef)
        val extraction = extraction(answeringRepository())
            .withRunLineage(null, LineageFailurePolicy.LENIENT)
            .withPersistUnitWriter { unit ->
                PersistOutcome.stored(
                    PropositionPersistenceResult.of(unit.propositions, unit.propositions),
                    null,
                    LineageNotRecordedException(key, "link store down"),
                )
            }

        val events = logged { extraction.remember() }

        assertThat(events)
            .anyMatch { it.level == Level.WARN && it.formattedMessage.startsWith("Lineage not recorded for extraction run") }
    }

    @Test
    fun `E11 with no writer bound a negative link count is logged and the chunk is stored`() {
        val links = mockk<PropositionRunLinkStore>()
        every { links.link(any(), any<Collection<String>>()) } returns -1
        val projection = projection()
        val extraction = extraction(answeringRepository(), projection = projection).withRunLineage(links)

        val events = logged { extraction.remember() }

        assertThat(events).anyMatch {
            it.level == Level.INFO && it.formattedMessage == "Attributed -1 propositions to extraction run routing-run"
        }
        verify(exactly = 1) { projection.projectAndPersist(any()) }
    }
}

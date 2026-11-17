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

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntity
import com.embabel.agent.rag.service.NamedEntityDataRepository
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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A chunk is stored under the bindings it read when it started.
 */
class PersistUnitBindingSnapshotTest {

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

    @Test
    fun `E4 a chunk in flight when lineage is bound is stored under the binding it started with`() {
        val links = mockk<PropositionRunLinkStore>()
        every { links.link(any(), any<Collection<String>>()) } returns 1
        val extraction = extraction(answeringRepository())
        val armed = AtomicBoolean(true)
        // The "New entity:" line is logged after the chunk read its bindings and before it writes.
        val bindWhileStoring = object : AppenderBase<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                if (event.formattedMessage.startsWith("New entity:") && armed.compareAndSet(true, false)) {
                    extraction.withRunLineage(links, LineageFailurePolicy.LENIENT)
                }
            }
        }
        val logger = LoggerFactory.getLogger(IncrementalPropositionExtraction::class.java) as Logger
        bindWhileStoring.start()
        logger.addAppender(bindWhileStoring)
        try {
            assertThatThrownBy { extraction.remember() }
                .isInstanceOf(LineageNotRecordedException::class.java)
                .hasMessageContaining("no PropositionRunLinkStore is bound")
            verify(exactly = 0) { links.link(any(), any<Collection<String>>()) }

            extraction.remember()
            verify(exactly = 1) { links.link(any(), any<Collection<String>>()) }
        } finally {
            logger.detachAppender(bindWhileStoring)
            bindWhileStoring.stop()
        }
    }
}

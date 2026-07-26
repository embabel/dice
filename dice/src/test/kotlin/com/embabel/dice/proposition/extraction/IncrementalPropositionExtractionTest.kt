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
import com.embabel.dice.common.Relations
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.SourceAnalysisRequestEvent
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.incremental.IncrementalSource
import com.embabel.dice.pipeline.ChunkPropositionResult
import com.embabel.dice.pipeline.PropositionPipeline
import com.embabel.dice.projection.graph.GraphProjectionService
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.provenance.SourceLocator
import com.embabel.dice.provenance.SourceRevisionRef
import com.embabel.dice.provenance.UriLocator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

class IncrementalPropositionExtractionTest {

    @Test
    fun `legacy and provenance aware JVM descriptors coexist`() {
        val rememberTextParameters = IncrementalPropositionExtraction::class.java.declaredMethods
            .filter { it.name == "rememberText" && !it.isSynthetic }
            .map { it.parameterTypes.toList() }
            .toSet()
        val rememberFileParameters = IncrementalPropositionExtraction::class.java.declaredMethods
            .filter { it.name == "rememberFile" && !it.isSynthetic }
            .map { it.parameterTypes.toList() }
            .toSet()

        val legacyTextPrefix = listOf(
            String::class.java,
            String::class.java,
            NamedEntity::class.java,
        )
        assertTrue(rememberTextParameters.contains(legacyTextPrefix))
        assertTrue(rememberTextParameters.contains(legacyTextPrefix + List::class.java))
        assertTrue(
            rememberTextParameters.contains(
                legacyTextPrefix + listOf(List::class.java, ExtractionPerspective::class.java),
            ),
        )
        assertTrue(
            rememberTextParameters.contains(
                legacyTextPrefix + listOf(
                    List::class.java,
                    ExtractionPerspective::class.java,
                    Boolean::class.javaObjectType,
                ),
            ),
        )

        val provenanceTextPrefix = legacyTextPrefix + SourceLocator::class.java
        assertTrue(rememberTextParameters.contains(provenanceTextPrefix))
        assertTrue(rememberTextParameters.contains(provenanceTextPrefix + SourceRevisionRef::class.java))
        assertTrue(
            rememberTextParameters.contains(
                provenanceTextPrefix + listOf(SourceRevisionRef::class.java, List::class.java),
            ),
        )
        assertTrue(
            rememberTextParameters.contains(
                provenanceTextPrefix + listOf(
                    SourceRevisionRef::class.java,
                    List::class.java,
                    ExtractionPerspective::class.java,
                    Boolean::class.javaObjectType,
                ),
            ),
        )

        val legacyFile = listOf(
            InputStream::class.java,
            String::class.java,
            NamedEntity::class.java,
        )
        assertTrue(rememberFileParameters.contains(legacyFile))
        assertTrue(rememberFileParameters.contains(legacyFile + SourceLocator::class.java))
        assertTrue(
            rememberFileParameters.contains(
                legacyFile + listOf(SourceLocator::class.java, SourceRevisionRef::class.java),
            ),
        )
    }

    @Test
    fun `new text overload retains exact typed and untyped inputs`() {
        val pipeline = pipelineReturningNoResult()
        val extraction = extraction(pipeline)
        val user = user()
        val locator = UriLocator("https://example.com/source")
        val revision = SourceRevisionRef(locator.key(), "r7")
        val perspective = mock<ExtractionPerspective>()
        val grounding = listOf("record:one", "record:two")

        extraction.rememberText(
            text = "source text",
            sourceId = "caller:source:r7",
            user = user,
            sourceLocator = locator,
            sourceRevision = revision,
            additionalGrounding = grounding,
            perspective = perspective,
            mintNewEntities = true,
        )

        val context = capturedContext(pipeline, "source text", "caller:source:r7", grounding)
        assertSame(locator, context.sourceLocator)
        assertSame(revision, context.sourceRevision)
        assertSame(perspective, context.perspective)
        assertEquals(true, context.mintNewEntities)
    }

    @Test
    fun `legacy text inputs never synthesize typed provenance`() {
        val pipeline = pipelineReturningNoResult()
        val extraction = extraction(pipeline)
        val grounding = listOf("revision:r9", "source:https://example.com/untyped")

        extraction.rememberText(
            text = "legacy text",
            sourceId = "untyped:r9",
            user = user(),
            additionalGrounding = grounding,
        )

        val context = capturedContext(pipeline, "legacy text", "untyped:r9", grounding)
        assertNull(context.sourceLocator)
        assertNull(context.sourceRevision)
    }

    @Test
    fun `mismatched typed provenance fails before pipeline invocation`() {
        val pipeline = pipelineReturningNoResult()
        val extraction = extraction(pipeline)
        val locator = UriLocator("https://example.com/source")

        assertThrows(IllegalArgumentException::class.java) {
            extraction.rememberText(
                text = "source text",
                sourceId = "exact-source",
                user = user(),
                sourceLocator = locator,
                sourceRevision = SourceRevisionRef("different-key", "r1"),
            )
        }
        verifyNoInteractions(pipeline)
    }

    @Test
    fun `legacy file retains remember source id without typed provenance`() {
        val pipeline = pipelineReturningNoResult()
        val extraction = extraction(pipeline)

        extraction.rememberFile(
            inputStream = ByteArrayInputStream("legacy file text".toByteArray()),
            filename = "legacy.txt",
            user = user(),
        )

        val contextCaptor = argumentCaptor<SourceAnalysisContext>()
        verify(pipeline).processOnce(
            any(),
            eq("remember:legacy.txt"),
            contextCaptor.capture(),
            anyOrNull(),
            any(),
            eq(emptyList()),
        )
        assertNull(contextCaptor.firstValue.sourceLocator)
        assertNull(contextCaptor.firstValue.sourceRevision)
    }

    @Test
    fun `provenance aware file retains remember source id and typed provenance`() {
        val pipeline = pipelineReturningNoResult()
        val extraction = extraction(pipeline)
        val locator = UriLocator("file:///notes/example.txt")
        val revision = SourceRevisionRef(locator.key(), "file-r2")

        extraction.rememberFile(
            inputStream = ByteArrayInputStream("file source text".toByteArray()),
            filename = "example.txt",
            user = user(),
            sourceLocator = locator,
            sourceRevision = revision,
        )

        val contextCaptor = argumentCaptor<SourceAnalysisContext>()
        verify(pipeline).processOnce(
            any(),
            eq("remember:example.txt"),
            contextCaptor.capture(),
            anyOrNull(),
            any(),
            eq(emptyList()),
        )
        assertSame(locator, contextCaptor.firstValue.sourceLocator)
        assertSame(revision, contextCaptor.firstValue.sourceRevision)
    }

    @Test
    fun `event provenance reaches the context observed by the pipeline`() {
        val pipeline = pipelineReturningNoResult()
        val extraction = extraction(pipeline)
        val source = mock<IncrementalSource<Message>>()
        whenever(source.id).thenReturn("event-source")
        whenever(source.size).thenReturn(1)
        val locator = UriLocator("https://example.com/event")
        val revision = SourceRevisionRef(locator.key(), "event-r1")
        val locatorCalls = AtomicInteger()
        val revisionCalls = AtomicInteger()
        val event = object : SourceAnalysisRequestEvent(this, user()) {
            override fun incrementalSource(): IncrementalSource<Message> = source

            override fun sourceLocator(): SourceLocator =
                locator.also { locatorCalls.incrementAndGet() }

            override fun sourceRevision(): SourceRevisionRef =
                revision.also { revisionCalls.incrementAndGet() }
        }

        extraction.extractPropositions(event)

        assertEquals(1, locatorCalls.get())
        assertEquals(1, revisionCalls.get())
        val contextCaptor = argumentCaptor<SourceAnalysisContext>()
        verify(pipeline).processChunk(any(), contextCaptor.capture())
        assertSame(locator, contextCaptor.firstValue.sourceLocator)
        assertSame(revision, contextCaptor.firstValue.sourceRevision)
    }

    private fun pipelineReturningNoResult(): PropositionPipeline =
        mock<PropositionPipeline>().also { pipeline ->
            whenever(
                pipeline.processOnce(
                    any(),
                    any(),
                    any(),
                    anyOrNull(),
                    any(),
                    any(),
                ),
            ).thenReturn(null)
            whenever(pipeline.processChunk(any(), any())).thenReturn(
                ChunkPropositionResult.Failed("event-source", "test result"),
            )
        }

    private fun extraction(pipeline: PropositionPipeline): IncrementalPropositionExtraction {
        val properties = mock<PropositionExtractionProperties>()
        whenever(properties.windowSize).thenReturn(1)
        whenever(properties.overlapSize).thenReturn(1)
        whenever(properties.triggerInterval).thenReturn(1)
        return IncrementalPropositionExtraction(
            propositionPipeline = pipeline,
            chunkHistoryStore = mock<ChunkHistoryStore>(),
            dataDictionary = mock<DataDictionary>(),
            relations = Relations.empty(),
            propositionRepository = mock<PropositionRepository>(),
            entityRepository = mock<NamedEntityDataRepository>(),
            entityResolver = mock<EntityResolver>(),
            graphProjectionService = mock<GraphProjectionService>(),
            properties = properties,
        )
    }

    private fun user(): NamedEntity =
        mock<NamedEntity>().also { user ->
            whenever(user.id).thenReturn("user-1")
            whenever(user.name).thenReturn("Test User")
        }

    private fun capturedContext(
        pipeline: PropositionPipeline,
        text: String,
        sourceId: String,
        grounding: List<String>,
    ): SourceAnalysisContext {
        val contextCaptor = argumentCaptor<SourceAnalysisContext>()
        verify(pipeline).processOnce(
            eq(text),
            eq(sourceId),
            contextCaptor.capture(),
            anyOrNull(),
            any(),
            eq(grounding),
        )
        return contextCaptor.firstValue
    }
}

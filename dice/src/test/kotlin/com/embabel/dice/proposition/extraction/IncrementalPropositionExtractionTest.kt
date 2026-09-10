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
import ch.qos.logback.classic.spi.ThrowableProxy
import ch.qos.logback.core.read.ListAppender
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
import org.slf4j.LoggerFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger

class IncrementalPropositionExtractionTest {

    @Test
    fun `entry point JVM descriptors are exact`() {
        val rememberTextParameters = declaredParameterLists("rememberText")
        val rememberFileParameters = declaredParameterLists("rememberFile")

        // Every descriptor that existed before requests is still here, and the request argument
        // only ever adds one descriptor on the end of each name.
        val legacyTextPrefix = listOf(
            String::class.java,
            String::class.java,
            NamedEntity::class.java,
        )
        val legacyTextFull = legacyTextPrefix + listOf(
            List::class.java,
            ExtractionPerspective::class.java,
            Boolean::class.javaObjectType,
        )
        val requestOnly = listOf(
            ExtractionRequest::class.java,
        )
        assertEquals(
            setOf(
                legacyTextPrefix,
                legacyTextPrefix + List::class.java,
                legacyTextPrefix + listOf(List::class.java, ExtractionPerspective::class.java),
                legacyTextFull,
                legacyTextFull + requestOnly,
            ),
            rememberTextParameters,
        )

        val legacyFile = listOf(
            InputStream::class.java,
            String::class.java,
            NamedEntity::class.java,
        )
        assertEquals(
            setOf(legacyFile, legacyFile + requestOnly),
            rememberFileParameters,
        )

        // Two entry points, and no third and fourth name to keep in step with them. Everything a
        // caller supplies beyond the arguments these methods always took rides on the request, so
        // a locator, a revision, a profile and a run reference add no method names and no arities.
        assertEquals(emptySet<List<Class<*>>>(), declaredParameterLists("rememberTextFromSource"))
        assertEquals(emptySet<List<Class<*>>>(), declaredParameterLists("rememberFileFromSource"))

        // The descriptor sets above are the whole published surface, and the run reference is a
        // field on the request, so no descriptor mentions it. This is the assertion that says the
        // slice adding the run changed no signature: if it had, one of these lists would name the
        // type.
        (rememberTextParameters + rememberFileParameters).forEach { parameters ->
            assertFalse(
                ExtractionRunRef::class.java in parameters,
                "a run reference must travel on the request, never as a parameter: $parameters",
            )
        }

        // A request-carrying call can never collapse onto one written without a request: the
        // request always arrives on the end, at an arity the other caller never fills.
        (rememberTextParameters + rememberFileParameters).forEach { parameters ->
            if (ExtractionRequest::class.java in parameters) {
                assertEquals(
                    requestOnly,
                    parameters.takeLast(1),
                    "the request must be the last parameter: $parameters",
                )
            }
        }
    }

    @Test
    fun `no field, parameter or return type outside the request carries what the request carries`() {
        // The point of the request object: a locator, a revision, a profile and a run reference
        // reach extraction through it and through nothing else. A future dimension added as a
        // loose parameter on an entry point would put the surface back where it started, so this
        // sweeps the whole class. The run is here because it is the first dimension to arrive
        // after the request existed, and it arrived without touching a single signature.
        val carried = setOf(
            SourceLocator::class.java,
            SourceRevisionRef::class.java,
            ExtractionContentProfileRef::class.java,
            ExtractionRunRef::class.java,
        )
        IncrementalPropositionExtraction::class.java.declaredMethods
            .filter { it.name.startsWith("remember") && '$' !in it.name }
            .forEach { method ->
                method.parameterTypes.forEach { parameter ->
                    assertFalse(
                        parameter in carried,
                        "${method.name} takes ${parameter.simpleName} directly; it belongs on the request",
                    )
                }
            }
    }

    @Test
    fun `the run reference reaches the context by one route and drives only the lineage write`() {
        // PR #94's review comment was that buildContext accepted a currentRun and put it on the
        // context while nothing read it, so the parameter came out and the type came out with it.
        // The run is back on the request, and now one thing does read it: persistAndProject writes
        // the lineage link. That is the whole of what a run does.
        //
        // The earlier form of this test asserted that persistAndProject never saw the context at
        // all, which was how "nothing consumes the run" stayed checkable. Lineage makes that false
        // by design. What replaces it is narrower and still mechanical: the context reaches exactly
        // one method that writes, and the only run-shaped thing reachable from there is the lineage
        // recorder. A second consumer would have to appear here.
        val persistAndProjectOverloads = IncrementalPropositionExtraction::class.java
            .declaredMethods.filter { it.name == "persistAndProject" }
        assertEquals(1, persistAndProjectOverloads.size, "persistAndProject must have exactly one overload")
        assertEquals(
            listOf(ChunkPropositionResult::class.java, SourceAnalysisContext::class.java),
            persistAndProjectOverloads.single().parameterTypes.toList(),
        )

        // Exactly one private method takes an ExtractionRunRef, and it is the lineage recorder. If a
        // second one appears, a run has grown a second effect and the operator rule that a run adds
        // a lineage write and nothing else no longer holds.
        val runConsumers = IncrementalPropositionExtraction::class.java.declaredMethods
            .filter { ExtractionRunRef::class.java in it.parameterTypes }
            .map { it.name }
            .toSet()
        assertEquals(
            setOf("recordRunLineage"),
            runConsumers,
            "a run drives the lineage write and nothing else",
        )

        // Second: the run reaches the context by exactly one route, the request, and reaches it
        // whole. A run named on a request is the run the pipeline sees, and nothing in between
        // rewrites, defaults or drops it.
        val pipeline = pipelineReturningNoResult()
        val extraction = extraction(pipeline)
        val run = ExtractionRunRef("run-1")

        extraction.rememberText(
            text = "run text",
            sourceId = "run:only",
            user = user(),
            additionalGrounding = emptyList(),
            perspective = null,
            mintNewEntities = null,
            request = ExtractionRequest(currentRun = run),
        )

        val context = capturedContext(pipeline, "run text", "run:only", emptyList())
        assertSame(run, context.currentRun)
        // A run needs no source and no profile of its own: the dimensions stay independent.
        assertNull(context.sourceLocator)
        assertNull(context.sourceRevision)
        assertNull(context.profile)
    }

    @Test
    fun `the entry point signatures that were overridable before requests still are`() {
        // @JvmOverloads emits every reduced-arity overload as final. Folding the new argument
        // into the existing declarations would therefore have turned each method's pre-change
        // maximum arity — the signature a subclass overrides — into a final bridge. Callers
        // would not have noticed; a subclass would have stopped compiling, and one already
        // compiled could fail verification at load. Each shape is its own declaration, and this
        // is the assertion that keeps it that way.
        val stillOpen = mapOf(
            "rememberText" to listOf(
                String::class.java,
                String::class.java,
                NamedEntity::class.java,
                List::class.java,
                ExtractionPerspective::class.java,
                Boolean::class.javaObjectType,
            ),
            "rememberFile" to listOf(
                InputStream::class.java,
                String::class.java,
                NamedEntity::class.java,
            ),
        )
        stillOpen.forEach { (name, parameters) ->
            val method = IncrementalPropositionExtraction::class.java
                .getMethod(name, *parameters.toTypedArray())
            assertFalse(
                Modifier.isFinal(method.modifiers),
                "$name${parameters.map { it.simpleName }} must stay overridable",
            )
        }

        // The request-taking forms are the single override point every call funnels through, so
        // they have to be open too.
        val requestOnly = arrayOf(
            ExtractionRequest::class.java,
        )
        stillOpen.forEach { (name, parameters) ->
            val method = IncrementalPropositionExtraction::class.java
                .getMethod(name, *(parameters.toTypedArray() + requestOnly))
            assertFalse(
                Modifier.isFinal(method.modifiers),
                "the request-taking $name must be overridable",
            )
        }

        // The reduced-arity overloads @JvmOverloads generates were final before this slice and
        // still are. Stating it pins that the surface is the one that was there, plus a request.
        val generatedBridges = listOf(
            "rememberText" to arrayOf<Class<*>>(
                String::class.java,
                String::class.java,
                NamedEntity::class.java,
            ),
            "rememberText" to arrayOf<Class<*>>(
                String::class.java,
                String::class.java,
                NamedEntity::class.java,
                List::class.java,
            ),
            "rememberText" to arrayOf<Class<*>>(
                String::class.java,
                String::class.java,
                NamedEntity::class.java,
                List::class.java,
                ExtractionPerspective::class.java,
            ),
        )
        generatedBridges.forEach { (name, parameters) ->
            val method = IncrementalPropositionExtraction::class.java.getMethod(name, *parameters)
            assertTrue(
                Modifier.isFinal(method.modifiers),
                "$name at ${parameters.size} arguments was a final bridge before this slice",
            )
        }
    }

    @Test
    fun `a subclass overriding the signatures that predate requests still intercepts every call`() {
        val pipeline = pipelineReturningNoResult()
        val seen = mutableListOf<String>()
        val extraction = object : IncrementalPropositionExtraction(
            propositionPipeline = pipeline,
            chunkHistoryStore = mock<ChunkHistoryStore>(),
            dataDictionary = mock<DataDictionary>(),
            relations = Relations.empty(),
            propositionRepository = mock<PropositionRepository>(),
            entityRepository = mock<NamedEntityDataRepository>(),
            entityResolver = mock<EntityResolver>(),
            graphProjectionService = mock<GraphProjectionService>(),
            properties = extractionProperties(),
        ) {
            // Written exactly as it would have been before this slice existed.
            override fun rememberText(
                text: String,
                sourceId: String,
                user: NamedEntity,
                additionalGrounding: List<String>,
                perspective: ExtractionPerspective?,
                mintNewEntities: Boolean?,
            ) {
                seen += "text:$sourceId"
            }

            override fun rememberFile(
                inputStream: InputStream,
                filename: String,
                user: NamedEntity,
            ) {
                seen += "file:$filename"
            }
        }

        extraction.rememberText("t", "three-args", user())
        extraction.rememberText("t", "six-args", user(), emptyList(), null, null)
        extraction.rememberFile(ByteArrayInputStream(byteArrayOf()), "legacy.txt", user())

        // The three-argument call goes through a final generated bridge, which dispatches
        // virtually to the six-argument method the subclass overrode.
        assertEquals(listOf("text:three-args", "text:six-args", "file:legacy.txt"), seen)
        verifyNoInteractions(pipeline)
    }

    @Test
    fun `a subclass overriding only the text entry points still intercepts file ingestion`() {
        // Before requests, the file entry point read the file and handed the text to the
        // six-argument text method, so a subclass overriding only that one intercepted file
        // ingestion too. Routing every file call to the request-taking text method would have
        // quietly taken that away: the override would still compile, still be called for direct
        // text calls, and silently stop seeing files.
        val pipeline = pipelineReturningNoResult()
        val seen = mutableListOf<String>()
        val extraction = object : IncrementalPropositionExtraction(
            propositionPipeline = pipeline,
            chunkHistoryStore = mock<ChunkHistoryStore>(),
            dataDictionary = mock<DataDictionary>(),
            relations = Relations.empty(),
            propositionRepository = mock<PropositionRepository>(),
            entityRepository = mock<NamedEntityDataRepository>(),
            entityResolver = mock<EntityResolver>(),
            graphProjectionService = mock<GraphProjectionService>(),
            properties = extractionProperties(),
        ) {
            override fun rememberText(
                text: String,
                sourceId: String,
                user: NamedEntity,
                additionalGrounding: List<String>,
                perspective: ExtractionPerspective?,
                mintNewEntities: Boolean?,
            ) {
                seen += "text:$sourceId"
            }

            override fun rememberText(
                text: String,
                sourceId: String,
                user: NamedEntity,
                additionalGrounding: List<String>,
                perspective: ExtractionPerspective?,
                mintNewEntities: Boolean?,
                request: ExtractionRequest,
            ) {
                seen += "request:$sourceId"
            }
        }
        val locator = UriLocator("file:///notes/dispatch.txt")
        val revision = SourceRevisionRef(locator.key(), "r1")

        extraction.rememberFile(
            ByteArrayInputStream("legacy file text".toByteArray()),
            "legacy.txt",
            user(),
        )
        // A request that carries nothing dispatches like the call it resembles.
        extraction.rememberFile(
            ByteArrayInputStream("legacy file text".toByteArray()),
            "empty-request.txt",
            user(),
            ExtractionRequest.NONE,
        )
        extraction.rememberFile(
            ByteArrayInputStream("source file text".toByteArray()),
            "source.txt",
            user(),
            ExtractionRequest(sourceLocator = locator, sourceRevision = revision),
        )

        assertEquals(
            listOf(
                "text:remember:legacy.txt",
                "text:remember:empty-request.txt",
                "request:remember:source.txt",
            ),
            seen,
        )
        verifyNoInteractions(pipeline)
    }

    @Test
    fun `a file call that carries a request goes to the request text entry point`() {
        // The other half of the routing rule: once there is something to carry, the six-argument
        // text signature cannot express it, so the call goes to the one that can.
        val pipeline = pipelineReturningNoResult()
        val seen = mutableListOf<String>()
        val extraction = object : IncrementalPropositionExtraction(
            propositionPipeline = pipeline,
            chunkHistoryStore = mock<ChunkHistoryStore>(),
            dataDictionary = mock<DataDictionary>(),
            relations = Relations.empty(),
            propositionRepository = mock<PropositionRepository>(),
            entityRepository = mock<NamedEntityDataRepository>(),
            entityResolver = mock<EntityResolver>(),
            graphProjectionService = mock<GraphProjectionService>(),
            properties = extractionProperties(),
        ) {
            override fun rememberText(
                text: String,
                sourceId: String,
                user: NamedEntity,
                additionalGrounding: List<String>,
                perspective: ExtractionPerspective?,
                mintNewEntities: Boolean?,
            ) {
                seen += "text:$sourceId"
            }

            override fun rememberText(
                text: String,
                sourceId: String,
                user: NamedEntity,
                additionalGrounding: List<String>,
                perspective: ExtractionPerspective?,
                mintNewEntities: Boolean?,
                request: ExtractionRequest,
            ) {
                seen += "request:$sourceId:${request.profile?.name}:${request.currentRun?.runId}"
            }
        }

        extraction.rememberFile(
            ByteArrayInputStream("legacy file text".toByteArray()),
            "profiled.txt",
            user(),
            ExtractionRequest(profile = ExtractionContentProfileRef("house-style", "v1")),
        )
        // A run on its own is something to carry too, so a request holding nothing else still
        // routes here and arrives whole.
        extraction.rememberFile(
            ByteArrayInputStream("legacy file text".toByteArray()),
            "run-only.txt",
            user(),
            ExtractionRequest(currentRun = ExtractionRunRef("run-1")),
        )

        assertEquals(
            listOf(
                "request:remember:profiled.txt:house-style:null",
                "request:remember:run-only.txt:null:run-1",
            ),
            seen,
        )
        verifyNoInteractions(pipeline)
    }

    @Test
    fun `the request text entry point retains exact typed and untyped inputs`() {
        val pipeline = pipelineReturningNoResult()
        val extraction = extraction(pipeline)
        val user = user()
        val locator = UriLocator("https://example.com/source")
        val revision = SourceRevisionRef(locator.key(), "r7")
        val profile = ExtractionContentProfileRef("house-style", "v1")
        val run = ExtractionRunRef("run-1")
        val perspective = mock<ExtractionPerspective>()
        val grounding = listOf("record:one", "record:two")

        extraction.rememberText(
            text = "source text",
            sourceId = "caller:source:r7",
            user = user,
            additionalGrounding = grounding,
            perspective = perspective,
            mintNewEntities = true,
            request = ExtractionRequest(
                sourceLocator = locator,
                sourceRevision = revision,
                profile = profile,
                currentRun = run,
            ),
        )

        // Every field a request can hold, on one call, arriving on the context unchanged.
        val context = capturedContext(pipeline, "source text", "caller:source:r7", grounding)
        assertSame(locator, context.sourceLocator)
        assertSame(revision, context.sourceRevision)
        assertSame(profile, context.profile)
        assertSame(run, context.currentRun)
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
    fun `mismatched typed provenance fails before the entry point is even called`() {
        // The request validates the pairing while it is being built, so a text call and a file
        // call are both unreachable with a mismatched pair — the caller never gets a request to
        // pass. That is stricter than checking inside each entry point, and it is why neither
        // entry point repeats the check.
        val pipeline = pipelineReturningNoResult()
        val textLocator = UriLocator("https://example.com/source")
        val fileLocator = UriLocator("file:///notes/example.txt")

        assertThrows(IllegalArgumentException::class.java) {
            ExtractionRequest(
                sourceLocator = textLocator,
                sourceRevision = SourceRevisionRef("different-key", "r1"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExtractionRequest(
                sourceLocator = fileLocator,
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
    fun `a file request retains remember source id and typed provenance`() {
        val pipeline = pipelineReturningNoResult()
        val extraction = extraction(pipeline)
        val locator = UriLocator("file:///notes/example.txt")
        val revision = SourceRevisionRef(locator.key(), "file-r2")

        extraction.rememberFile(
            inputStream = ByteArrayInputStream("file source text".toByteArray()),
            filename = "example.txt",
            user = user(),
            request = ExtractionRequest(sourceLocator = locator, sourceRevision = revision),
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
    fun `legacy generic Mockito shaped call sites remain uniquely resolvable`() {
        val extraction = mock<IncrementalPropositionExtraction>()
        val user = user()
        val four = GenericMatcherValues("four", "source-4", user, emptyList<String>())
        val five = GenericMatcherValues("five", "source-5", user, emptyList<String>(), null)
        val six = GenericMatcherValues("six", "source-6", user, emptyList<String>(), null, null)

        extraction.rememberText(four.any(), four.any(), four.any(), four.any())
        extraction.rememberText(five.any(), five.any(), five.any(), five.any(), five.any())
        extraction.rememberText(six.any(), six.any(), six.any(), six.any(), six.any(), six.any())

        verify(extraction).rememberText("four", "source-4", user, emptyList(), null, null)
        verify(extraction).rememberText("five", "source-5", user, emptyList(), null, null)
        verify(extraction).rememberText("six", "source-6", user, emptyList(), null, null)
    }

    @Test
    fun `Kotlin callable references and named calls resolve to one entry point each`() {
        val extraction = mock<IncrementalPropositionExtraction>()
        val user = user()
        val locator = UriLocator("https://example.com/callable")
        val revision = SourceRevisionRef(locator.key(), "callable-r1")
        val request = ExtractionRequest(sourceLocator = locator, sourceRevision = revision)
        val legacyText:
            (String, String, NamedEntity, List<String>, ExtractionPerspective?, Boolean?) -> Unit =
            extraction::rememberText
        val requestText:
            (
                String,
                String,
                NamedEntity,
                List<String>,
                ExtractionPerspective?,
                Boolean?,
                ExtractionRequest,
            ) -> Unit = extraction::rememberText
        val legacyFile: (InputStream, String, NamedEntity) -> Unit = extraction::rememberFile
        val requestFile: (InputStream, String, NamedEntity, ExtractionRequest) -> Unit =
            extraction::rememberFile

        legacyText("callable legacy", "callable-legacy", user, emptyList(), null, null)
        requestText("callable request", "callable-request", user, emptyList(), null, null, request)
        legacyFile(ByteArrayInputStream(byteArrayOf()), "callable-legacy.txt", user)
        requestFile(ByteArrayInputStream(byteArrayOf()), "callable-request.txt", user, request)
        extraction.rememberText(text = "named legacy", sourceId = "named-legacy", user = user)
        extraction.rememberText(
            text = "named request",
            sourceId = "named-request",
            user = user,
            additionalGrounding = emptyList(),
            perspective = null,
            mintNewEntities = null,
            request = request,
        )
        extraction.rememberFile(
            inputStream = ByteArrayInputStream(byteArrayOf()),
            filename = "named-legacy.txt",
            user = user,
        )
        extraction.rememberFile(
            inputStream = ByteArrayInputStream(byteArrayOf()),
            filename = "named-request.txt",
            user = user,
            request = request,
        )

        verify(extraction).rememberText("callable legacy", "callable-legacy", user, emptyList(), null, null)
        verify(extraction).rememberText(
            "callable request",
            "callable-request",
            user,
            emptyList(),
            null,
            null,
            request,
        )
        verify(extraction).rememberText("named legacy", "named-legacy", user)
        verify(extraction).rememberText(
            "named request",
            "named-request",
            user,
            emptyList(),
            null,
            null,
            request,
        )
    }

    @Test
    fun `both file entry points dispatch through an open text entry point`() {
        val pipeline = pipelineReturningNoResult()
        val extraction = spy(extraction(pipeline))
        val user = user()
        val locator = UriLocator("file:///notes/dispatch.txt")
        val revision = SourceRevisionRef(locator.key(), "dispatch-r1")
        val request = ExtractionRequest(sourceLocator = locator, sourceRevision = revision)
        doNothing().whenever(extraction).rememberText(
            any(),
            any(),
            any(),
            any(),
            anyOrNull(),
            anyOrNull(),
        )
        doNothing().whenever(extraction).rememberText(
            any(),
            any(),
            any(),
            any(),
            anyOrNull(),
            anyOrNull(),
            any(),
        )

        extraction.rememberFile(
            ByteArrayInputStream("legacy dispatch".toByteArray()),
            "legacy-dispatch.txt",
            user,
        )
        extraction.rememberFile(
            ByteArrayInputStream("request dispatch".toByteArray()),
            "request-dispatch.txt",
            user,
            request,
        )

        // A file call carrying nothing lands on the six-argument text method, the one a subclass
        // written before requests overrides. A file call carrying a request lands on the
        // request-taking method, the single override point every call funnels through.
        verify(extraction).rememberText(
            "legacy dispatch",
            "remember:legacy-dispatch.txt",
            user,
            emptyList(),
            null,
            null,
        )
        verify(extraction).rememberText(
            "request dispatch",
            "remember:request-dispatch.txt",
            user,
            emptyList(),
            null,
            null,
            request,
        )
        verifyNoInteractions(pipeline)
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

    @Test
    fun `an event whose revision names another source is warned and dropped, and the next event still runs`() {
        val pipeline = pipelineReturningNoResult()
        val extraction = extraction(pipeline)
        val logger = LoggerFactory.getLogger(IncrementalPropositionExtraction::class.java) as Logger
        val warnings = ListAppender<ILoggingEvent>().also {
            it.start()
            logger.addAppender(it)
        }

        val locator = UriLocator("https://example.com/mismatched")
        val mismatched = event(
            sourceId = "mismatched-source",
            locator = locator,
            // A publisher that built the ref against a different locator than the one it hands over.
            revision = SourceRevisionRef("uri:https://example.com/something-else", "r1"),
        )
        val sound = event(
            sourceId = "sound-source",
            locator = locator,
            revision = SourceRevisionRef(locator.key(), "r1"),
        )

        try {
            // The listener swallows it: extractPropositions is called from an @Async @EventListener,
            // so a throw here would surface only in the executor's uncaught handler.
            extraction.extractPropositions(mismatched)
            extraction.extractPropositions(sound)
        } finally {
            logger.detachAppender(warnings)
            warnings.stop()
        }

        // Exactly one call, and it carries the sound event's provenance — the bad event never
        // reached the pipeline, because the context rejected it while it was being built.
        val contextCaptor = argumentCaptor<SourceAnalysisContext>()
        verify(pipeline).processChunk(any(), contextCaptor.capture())
        assertSame(locator, contextCaptor.firstValue.sourceLocator)
        assertEquals("r1", contextCaptor.firstValue.sourceRevision?.sourceRevision)

        val warned = warnings.list.single { it.level == Level.WARN }
        assertEquals("Failed to extract propositions", warned.formattedMessage)
        assertInstanceOf(
            IllegalArgumentException::class.java,
            warned.throwableProxy?.let { (it as ThrowableProxy).throwable },
        )

        // The drain loop is not wedged: nothing is queued and the extraction lock was released.
        assertTrue(extraction.isIdle, "a rejected event must not leave the extractor busy")
    }

    @Test
    fun `a request carries profile, run and revision to the context with and without a source`() {
        val pipeline = pipelineReturningNoResult()
        val extraction = extraction(pipeline)
        val user = user()
        val locator = UriLocator("https://example.com/profiled")
        val revision = SourceRevisionRef(locator.key(), "r1")
        val profile = ExtractionContentProfileRef("house-style", "v1")
        val run = ExtractionRunRef("run-1")

        extraction.rememberText(
            text = "untyped text",
            sourceId = "untyped:profiled",
            user = user,
            additionalGrounding = emptyList(),
            perspective = null,
            mintNewEntities = null,
            request = ExtractionRequest(profile = profile, currentRun = run),
        )
        extraction.rememberText(
            text = "source text",
            sourceId = "source:profiled",
            user = user,
            additionalGrounding = emptyList(),
            perspective = null,
            mintNewEntities = null,
            request = ExtractionRequest(
                sourceLocator = locator,
                sourceRevision = revision,
                profile = profile,
                currentRun = run,
            ),
        )

        // A profile and a run need no source of their own: the dimensions stay independent on the
        // way in.
        val fromUntyped = capturedContext(pipeline, "untyped text", "untyped:profiled", emptyList())
        assertSame(profile, fromUntyped.profile)
        assertSame(run, fromUntyped.currentRun)
        assertNull(fromUntyped.sourceLocator)
        assertNull(fromUntyped.sourceRevision)

        val fromSource = capturedContext(pipeline, "source text", "source:profiled", emptyList())
        assertSame(profile, fromSource.profile)
        assertSame(run, fromSource.currentRun)
        assertSame(locator, fromSource.sourceLocator)
        assertSame(revision, fromSource.sourceRevision)
    }

    @Test
    fun `a request reaches the context through the file entry point`() {
        val pipeline = pipelineReturningNoResult()
        val extraction = extraction(pipeline)
        val user = user()
        val locator = UriLocator("file:///notes/profiled.txt")
        val revision = SourceRevisionRef(locator.key(), "r1")
        val profile = ExtractionContentProfileRef("house-style", "v1")
        val run = ExtractionRunRef("run-1")

        extraction.rememberFile(
            inputStream = ByteArrayInputStream("plain file text".toByteArray()),
            filename = "profile-only.txt",
            user = user,
            request = ExtractionRequest(profile = profile, currentRun = run),
        )
        extraction.rememberFile(
            inputStream = ByteArrayInputStream("source file text".toByteArray()),
            filename = "source-profiled.txt",
            user = user,
            request = ExtractionRequest(
                sourceLocator = locator,
                sourceRevision = revision,
                profile = profile,
                currentRun = run,
            ),
        )
        // Both file calls hand their text to the request-taking entry point, so the two contexts
        // the pipeline sees are the proof that carriage survives the file hop.

        val contextCaptor = argumentCaptor<SourceAnalysisContext>()
        verify(pipeline, times(2)).processOnce(
            any(),
            any(),
            contextCaptor.capture(),
            anyOrNull(),
            any(),
            eq(emptyList()),
        )
        contextCaptor.allValues.forEach { context ->
            assertSame(profile, context.profile)
            assertSame(run, context.currentRun)
        }
        val fromSource = contextCaptor.allValues.single { it.sourceLocator != null }
        assertSame(locator, fromSource.sourceLocator)
        assertSame(revision, fromSource.sourceRevision)
    }

    @Test
    fun `calls written without a request carry no profile and no run`() {
        val pipeline = pipelineReturningNoResult()
        val extraction = extraction(pipeline)
        val user = user()

        extraction.rememberText(text = "legacy text", sourceId = "legacy:plain", user = user)
        extraction.rememberFile(
            inputStream = ByteArrayInputStream("legacy file text".toByteArray()),
            filename = "legacy-plain.txt",
            user = user,
        )

        val contextCaptor = argumentCaptor<SourceAnalysisContext>()
        verify(pipeline, times(2)).processOnce(
            any(),
            any(),
            contextCaptor.capture(),
            anyOrNull(),
            any(),
            eq(emptyList()),
        )
        contextCaptor.allValues.forEach { context ->
            assertNull(context.profile)
            assertNull(context.currentRun)
        }
    }

    @Test
    fun `a profile and a run change nothing else about what the pipeline is asked to do`() {
        val pipeline = pipelineReturningNoResult()
        val extraction = extraction(pipeline)
        val user = user()
        val grounding = listOf("record:one")
        val profile = ExtractionContentProfileRef("house-style", "v1")
        val run = ExtractionRunRef("run-1")

        extraction.rememberText(
            text = "same text",
            sourceId = "same:id",
            user = user,
            additionalGrounding = grounding,
            perspective = ExtractionPerspective.USER,
            mintNewEntities = true,
        )
        extraction.rememberText(
            text = "same text",
            sourceId = "same:id",
            user = user,
            additionalGrounding = grounding,
            perspective = ExtractionPerspective.USER,
            mintNewEntities = true,
            request = ExtractionRequest(profile = profile, currentRun = run),
        )

        val contextCaptor = argumentCaptor<SourceAnalysisContext>()
        verify(pipeline, times(2)).processOnce(
            eq("same text"),
            eq("same:id"),
            contextCaptor.capture(),
            anyOrNull(),
            any(),
            eq(grounding),
        )
        val (plain, profiled) = contextCaptor.allValues

        // Comparing whole contexts is the point: they agree on every component but the two the
        // second call set. The resolver is substituted because buildContext constructs a fresh
        // one per call by design, so it is never the same instance twice.
        assertEquals(
            plain,
            profiled.copy(profile = null, currentRun = null, entityResolver = plain.entityResolver),
        )
        assertSame(profile, profiled.profile)
        assertSame(run, profiled.currentRun)
    }

    @Test
    fun `event profile and run reach the context observed by the pipeline`() {
        val pipeline = pipelineReturningNoResult()
        val extraction = extraction(pipeline)
        val source = mock<IncrementalSource<Message>>()
        whenever(source.id).thenReturn("event-source")
        whenever(source.size).thenReturn(1)
        val profile = ExtractionContentProfileRef("house-style", "v1")
        val run = ExtractionRunRef("run-1")
        val profileCalls = AtomicInteger()
        val runCalls = AtomicInteger()
        val event = object : SourceAnalysisRequestEvent(this, user()) {
            override fun incrementalSource(): IncrementalSource<Message> = source

            override fun profile(): ExtractionContentProfileRef =
                profile.also { profileCalls.incrementAndGet() }

            override fun currentRun(): ExtractionRunRef =
                run.also { runCalls.incrementAndGet() }
        }

        extraction.extractPropositions(event)

        // One read each: the async path builds one context through the same buildContext the
        // direct calls use, so there is nowhere else for a second read to happen.
        assertEquals(1, profileCalls.get())
        assertEquals(1, runCalls.get())
        val contextCaptor = argumentCaptor<SourceAnalysisContext>()
        verify(pipeline).processChunk(any(), contextCaptor.capture())
        assertSame(profile, contextCaptor.firstValue.profile)
        assertSame(run, contextCaptor.firstValue.currentRun)
        assertNull(contextCaptor.firstValue.sourceLocator)
    }

    @Test
    fun `an empty request asks for exactly what a call without one asks for`() {
        val pipeline = pipelineReturningNoResult()
        val extraction = extraction(pipeline)
        val user = user()
        val grounding = listOf("record:one")

        extraction.rememberText(
            text = "same text",
            sourceId = "same:id",
            user = user,
            additionalGrounding = grounding,
            perspective = ExtractionPerspective.USER,
            mintNewEntities = true,
        )
        extraction.rememberText(
            text = "same text",
            sourceId = "same:id",
            user = user,
            additionalGrounding = grounding,
            perspective = ExtractionPerspective.USER,
            mintNewEntities = true,
            request = ExtractionRequest.NONE,
        )

        val contextCaptor = argumentCaptor<SourceAnalysisContext>()
        verify(pipeline, times(2)).processOnce(
            eq("same text"),
            eq("same:id"),
            contextCaptor.capture(),
            anyOrNull(),
            any(),
            eq(grounding),
        )
        val (withoutRequest, withEmptyRequest) = contextCaptor.allValues

        // Whole contexts again. The resolver is substituted because buildContext constructs a
        // fresh one per call by design, so it is never the same instance twice.
        assertEquals(
            withoutRequest,
            withEmptyRequest.copy(entityResolver = withoutRequest.entityResolver),
        )
    }

    private fun declaredParameterLists(name: String): Set<List<Class<*>>> =
        IncrementalPropositionExtraction::class.java.declaredMethods
            .filter { it.name == name && !it.isSynthetic }
            .map { it.parameterTypes.toList() }
            .toSet()

    private fun event(
        sourceId: String,
        locator: SourceLocator,
        revision: SourceRevisionRef,
    ): SourceAnalysisRequestEvent {
        val source = mock<IncrementalSource<Message>>()
        whenever(source.id).thenReturn(sourceId)
        whenever(source.size).thenReturn(1)
        return object : SourceAnalysisRequestEvent(this, user()) {
            override fun incrementalSource(): IncrementalSource<Message> = source

            override fun sourceLocator(): SourceLocator = locator

            override fun sourceRevision(): SourceRevisionRef = revision
        }
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

    private fun extractionProperties(): PropositionExtractionProperties =
        mock<PropositionExtractionProperties>().also { properties ->
            whenever(properties.windowSize).thenReturn(1)
            whenever(properties.overlapSize).thenReturn(1)
            whenever(properties.triggerInterval).thenReturn(1)
        }

    private fun extraction(pipeline: PropositionPipeline): IncrementalPropositionExtraction {
        val properties = extractionProperties()
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

    /**
     * Matches Mockito's no-argument generic matcher signature while returning real values,
     * so the test executes Kotlin default-argument bridges without entering Mockito matcher state.
     */
    private class GenericMatcherValues(vararg values: Any?) {
        private val values = values.toList()
        private var index = 0

        @Suppress("UNCHECKED_CAST")
        fun <T> any(): T = values[index++] as T
    }
}

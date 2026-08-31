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
import com.embabel.dice.common.ConversationAnalysisRequestEvent
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
    fun `legacy and source aware JVM descriptors are exact`() {
        val rememberTextParameters = IncrementalPropositionExtraction::class.java.declaredMethods
            .filter { it.name == "rememberText" && !it.isSynthetic }
            .map { it.parameterTypes.toList() }
            .toSet()
        val rememberFileParameters = IncrementalPropositionExtraction::class.java.declaredMethods
            .filter { it.name == "rememberFile" && !it.isSynthetic }
            .map { it.parameterTypes.toList() }
            .toSet()
        val rememberTextFromSourceParameters = IncrementalPropositionExtraction::class.java.declaredMethods
            .filter { it.name == "rememberTextFromSource" && !it.isSynthetic }
            .map { it.parameterTypes.toList() }
            .toSet()
        val rememberFileFromSourceParameters = IncrementalPropositionExtraction::class.java.declaredMethods
            .filter { it.name == "rememberFileFromSource" && !it.isSynthetic }
            .map { it.parameterTypes.toList() }
            .toSet()

        // Every descriptor that existed before profiles is still here, and the profile
        // argument only ever adds one descriptor on the end of each name.
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
        val profileOnly = listOf(
            ExtractionContentProfileRef::class.java,
        )
        assertEquals(
            setOf(
                legacyTextPrefix,
                legacyTextPrefix + List::class.java,
                legacyTextPrefix + listOf(List::class.java, ExtractionPerspective::class.java),
                legacyTextFull,
                legacyTextFull + profileOnly,
            ),
            rememberTextParameters,
        )

        val sourceTextPrefix = legacyTextPrefix + SourceLocator::class.java
        val sourceTextFull = sourceTextPrefix + listOf(
            SourceRevisionRef::class.java,
            List::class.java,
            ExtractionPerspective::class.java,
            Boolean::class.javaObjectType,
        )
        assertEquals(
            setOf(
                sourceTextPrefix,
                sourceTextPrefix + SourceRevisionRef::class.java,
                sourceTextPrefix + listOf(SourceRevisionRef::class.java, List::class.java),
                sourceTextPrefix + listOf(
                    SourceRevisionRef::class.java,
                    List::class.java,
                    ExtractionPerspective::class.java,
                ),
                sourceTextFull,
                sourceTextFull + profileOnly,
            ),
            rememberTextFromSourceParameters,
        )

        val legacyFile = listOf(
            InputStream::class.java,
            String::class.java,
            NamedEntity::class.java,
        )
        assertEquals(
            setOf(legacyFile, legacyFile + profileOnly),
            rememberFileParameters,
        )
        val sourceFileFull =
            legacyFile + listOf(SourceLocator::class.java, SourceRevisionRef::class.java)
        assertEquals(
            setOf(
                legacyFile + SourceLocator::class.java,
                sourceFileFull,
                sourceFileFull + profileOnly,
            ),
            rememberFileFromSourceParameters,
        )

        // A profile-aware call can never collapse onto a legacy one: a profile always arrives
        // on the end, at an arity a legacy caller never fills.
        val everyRememberParameterList = rememberTextParameters + rememberFileParameters +
            rememberTextFromSourceParameters + rememberFileFromSourceParameters
        everyRememberParameterList.forEach { parameters ->
            val hasProfile = ExtractionContentProfileRef::class.java in parameters
            if (hasProfile) {
                assertEquals(
                    profileOnly,
                    parameters.takeLast(1),
                    "profile must be the last parameter: $parameters",
                )
            }
        }
    }

    @Test
    fun `no entry point, context, constructor, or field anywhere carries a run reference`() {
        // PR #94 review comment: buildContext accepted a currentRun and put it on the context.
        // persistAndProject — the method that actually saves the extracted propositions — takes
        // only a ChunkPropositionResult and never the context that would have carried it. No
        // consuming write exists on this branch (the durable run store is DICE #67/#98/#99), so
        // the fix removes the no-op parameter.
        //
        // persistAndProject has exactly one overload, and it is the one-argument shape: a second
        // overload taking the context (a run reference's only possible route back in) would slip
        // past a check that only confirms one particular arity exists.
        val persistAndProjectOverloads = IncrementalPropositionExtraction::class.java
            .declaredMethods.filter { it.name == "persistAndProject" }
        assertEquals(1, persistAndProjectOverloads.size, "persistAndProject must have exactly one overload")
        assertEquals(1, persistAndProjectOverloads.single().parameterCount)
        assertEquals(
            ChunkPropositionResult::class.java,
            persistAndProjectOverloads.single().parameterTypes.single(),
        )

        // The type itself is gone from the classpath, so there is nothing left for a caller to
        // depend on — not even a reference to it, let alone a call to a member of it.
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("com.embabel.dice.proposition.extraction.ExtractionRunRef")
        }

        // A run reference under another name (runRef, runId, AnalysisRunRef, ...) would satisfy a
        // sweep that only recognizes "currentRun"/"extractionRun" literally, so this checks every
        // declared member's name for "run" as a substring, not a fixed set of spellings. Verified
        // before writing this: none of the four carriers has a legitimate declared method, field,
        // or constructor parameter whose name contains "run" today, confirmed by a throwaway
        // reflection dump run against the built classes, so this sweep starts from a clean
        // baseline and any future match is either a reintroduced run reference or something that
        // needs an explicit, named exclusion (there are none right now).
        //
        // Types are checked via the *generic* signature (genericType / genericReturnType /
        // genericParameterTypes), not the erased Class. An erased check sees `List` for a field
        // declared `List<AnalysisRunRef>` and would miss it; `Type.toString()` on a generic
        // signature includes the type argument, so the same substring match catches a run
        // reference hidden inside a collection or other generic wrapper. Confirmed empirically,
        // same as the name sweep: zero matches on the current classes.
        //
        // Constructor parameters are checked by type only, not name. The JVM does not preserve
        // real parameter names in these classes' compiled constructors (reflection reports them
        // as arg0, arg1, ...), so a name-based check on a constructor parameter would silently
        // never fire; claiming otherwise here would be the same overclaim this test exists to
        // avoid making about other code.
        val carriers = listOf(
            IncrementalPropositionExtraction::class.java,
            SourceAnalysisContext::class.java,
            SourceAnalysisRequestEvent::class.java,
            ConversationAnalysisRequestEvent::class.java,
        )
        val runInName = Regex("(?i)run")
        fun suspectType(type: java.lang.reflect.Type) = runInName.containsMatchIn(type.toString())
        carriers.forEach { type ->
            type.declaredMethods.forEach { method ->
                assertFalse(runInName.containsMatchIn(method.name), "${type.simpleName}.${method.name}: name mentions run")
                assertFalse(
                    method.genericParameterTypes.any(::suspectType) || suspectType(method.genericReturnType),
                    "${type.simpleName}.${method.name}: a parameter or return type mentions run",
                )
            }
            type.declaredFields.forEach { field ->
                assertFalse(runInName.containsMatchIn(field.name), "${type.simpleName}.${field.name}: name mentions run")
                assertFalse(suspectType(field.genericType), "${type.simpleName}.${field.name}: field type mentions run")
            }
            type.declaredConstructors.forEach { constructor ->
                assertFalse(
                    constructor.genericParameterTypes.any(::suspectType),
                    "${type.simpleName} constructor $constructor: a parameter type mentions run",
                )
            }
        }
    }

    @Test
    fun `the entry point signatures that were overridable before profiles still are`() {
        // @JvmOverloads emits every reduced-arity overload as final. Folding the new argument
        // into the existing declarations would therefore have turned each method's pre-change
        // maximum arity — the signature a subclass overrides — into a final bridge. Callers
        // would not have noticed; a subclass would have stopped compiling, and one already
        // compiled could fail verification at load. Each shape is its own declaration instead,
        // and this is the assertion that keeps it that way.
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
            "rememberTextFromSource" to listOf(
                String::class.java,
                String::class.java,
                NamedEntity::class.java,
                SourceLocator::class.java,
                SourceRevisionRef::class.java,
                List::class.java,
                ExtractionPerspective::class.java,
                Boolean::class.javaObjectType,
            ),
            "rememberFileFromSource" to listOf(
                InputStream::class.java,
                String::class.java,
                NamedEntity::class.java,
                SourceLocator::class.java,
                SourceRevisionRef::class.java,
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

        // The new maximum-arity forms are the single override point every call funnels through,
        // so they have to be open too.
        val profileOnly = arrayOf(
            ExtractionContentProfileRef::class.java,
        )
        stillOpen.forEach { (name, parameters) ->
            val method = IncrementalPropositionExtraction::class.java
                .getMethod(name, *(parameters.toTypedArray() + profileOnly))
            assertFalse(
                Modifier.isFinal(method.modifiers),
                "the profile-aware $name must be overridable",
            )
        }

        // The reduced-arity overloads @JvmOverloads generates were final before this slice and
        // still are. Stating it pins that the fix restored the previous surface exactly rather
        // than widening it.
        val generatedBridges = listOf(
            "rememberText" to arrayOf<Class<*>>(
                String::class.java,
                String::class.java,
                NamedEntity::class.java,
            ),
            "rememberTextFromSource" to arrayOf<Class<*>>(
                String::class.java,
                String::class.java,
                NamedEntity::class.java,
                SourceLocator::class.java,
            ),
            "rememberFileFromSource" to arrayOf<Class<*>>(
                InputStream::class.java,
                String::class.java,
                NamedEntity::class.java,
                SourceLocator::class.java,
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
    fun `a subclass overriding the pre-profile signatures still intercepts every call`() {
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
    fun `a subclass overriding only the pre-profile text methods still intercepts file ingestion`() {
        // Before profiles, the file entry points read the file and handed the text to the
        // pre-profile text methods, so a subclass overriding only those intercepted file
        // ingestion too. Routing the file paths to the wide text methods would have quietly
        // taken that away: the override would still compile, still be called for direct text
        // calls, and silently stop seeing files.
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

            override fun rememberTextFromSource(
                text: String,
                sourceId: String,
                user: NamedEntity,
                sourceLocator: SourceLocator,
                sourceRevision: SourceRevisionRef?,
                additionalGrounding: List<String>,
                perspective: ExtractionPerspective?,
                mintNewEntities: Boolean?,
            ) {
                seen += "textFromSource:$sourceId"
            }
        }
        val locator = UriLocator("file:///notes/dispatch.txt")
        val revision = SourceRevisionRef(locator.key(), "r1")

        extraction.rememberFile(
            ByteArrayInputStream("legacy file text".toByteArray()),
            "legacy.txt",
            user(),
        )
        // A wide call carrying no reference dispatches like the pre-profile call it resembles.
        extraction.rememberFile(
            ByteArrayInputStream("legacy file text".toByteArray()),
            "wide-null.txt",
            user(),
            null,
        )
        extraction.rememberFileFromSource(
            ByteArrayInputStream("source file text".toByteArray()),
            "source.txt",
            user(),
            locator,
            revision,
        )
        extraction.rememberFileFromSource(
            ByteArrayInputStream("source file text".toByteArray()),
            "wide-null-source.txt",
            user(),
            locator,
            revision,
            null,
        )

        assertEquals(
            listOf(
                "text:remember:legacy.txt",
                "text:remember:wide-null.txt",
                "textFromSource:remember:source.txt",
                "textFromSource:remember:wide-null-source.txt",
            ),
            seen,
        )
        verifyNoInteractions(pipeline)
    }

    @Test
    fun `a file call that carries a profile goes to the wide text entry point`() {
        // The other half of the routing rule: once there is a reference to carry, the legacy
        // text signature cannot express it, so the call has to go wide.
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
                seen += "legacy:$sourceId"
            }

            override fun rememberText(
                text: String,
                sourceId: String,
                user: NamedEntity,
                additionalGrounding: List<String>,
                perspective: ExtractionPerspective?,
                mintNewEntities: Boolean?,
                profile: ExtractionContentProfileRef?,
            ) {
                seen += "wide:$sourceId:${profile?.name}"
            }
        }

        extraction.rememberFile(
            ByteArrayInputStream("legacy file text".toByteArray()),
            "profiled.txt",
            user(),
            ExtractionContentProfileRef("house-style", "v1"),
        )

        assertEquals(listOf("wide:remember:profiled.txt:house-style"), seen)
        verifyNoInteractions(pipeline)
    }

    @Test
    fun `new text entry point retains exact typed and untyped inputs`() {
        val pipeline = pipelineReturningNoResult()
        val extraction = extraction(pipeline)
        val user = user()
        val locator = UriLocator("https://example.com/source")
        val revision = SourceRevisionRef(locator.key(), "r7")
        val perspective = mock<ExtractionPerspective>()
        val grounding = listOf("record:one", "record:two")

        extraction.rememberTextFromSource(
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
            extraction.rememberTextFromSource(
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
    fun `mismatched file provenance fails before parsing or pipeline invocation`() {
        val pipeline = pipelineReturningNoResult()
        val extraction = extraction(pipeline)
        val locator = UriLocator("file:///notes/example.txt")

        assertThrows(IllegalArgumentException::class.java) {
            extraction.rememberFileFromSource(
                inputStream = ByteArrayInputStream("file source text".toByteArray()),
                filename = "example.txt",
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
    fun `source aware file retains remember source id and typed provenance`() {
        val pipeline = pipelineReturningNoResult()
        val extraction = extraction(pipeline)
        val locator = UriLocator("file:///notes/example.txt")
        val revision = SourceRevisionRef(locator.key(), "file-r2")

        extraction.rememberFileFromSource(
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
    fun `Kotlin callable references and named calls distinguish legacy and source entry points`() {
        val extraction = mock<IncrementalPropositionExtraction>()
        val user = user()
        val locator = UriLocator("https://example.com/callable")
        val revision = SourceRevisionRef(locator.key(), "callable-r1")
        val legacyText:
            (String, String, NamedEntity, List<String>, ExtractionPerspective?, Boolean?) -> Unit =
            extraction::rememberText
        val sourceText:
            (
                String,
                String,
                NamedEntity,
                SourceLocator,
                SourceRevisionRef?,
                List<String>,
                ExtractionPerspective?,
                Boolean?,
            ) -> Unit = extraction::rememberTextFromSource
        val legacyFile: (InputStream, String, NamedEntity) -> Unit = extraction::rememberFile
        val sourceFile:
            (InputStream, String, NamedEntity, SourceLocator, SourceRevisionRef?) -> Unit =
            extraction::rememberFileFromSource

        legacyText("callable legacy", "callable-legacy", user, emptyList(), null, null)
        sourceText("callable source", "callable-source", user, locator, revision, emptyList(), null, null)
        legacyFile(ByteArrayInputStream(byteArrayOf()), "callable-legacy.txt", user)
        sourceFile(ByteArrayInputStream(byteArrayOf()), "callable-source.txt", user, locator, revision)
        extraction.rememberText(text = "named legacy", sourceId = "named-legacy", user = user)
        extraction.rememberTextFromSource(
            text = "named source",
            sourceId = "named-source",
            user = user,
            sourceLocator = locator,
        )
        extraction.rememberFile(
            inputStream = ByteArrayInputStream(byteArrayOf()),
            filename = "named-legacy.txt",
            user = user,
        )
        extraction.rememberFileFromSource(
            inputStream = ByteArrayInputStream(byteArrayOf()),
            filename = "named-source.txt",
            user = user,
            sourceLocator = locator,
        )

        verify(extraction).rememberText("callable legacy", "callable-legacy", user, emptyList(), null, null)
        verify(extraction).rememberTextFromSource(
            "callable source",
            "callable-source",
            user,
            locator,
            revision,
            emptyList(),
            null,
            null,
        )
        verify(extraction).rememberText("named legacy", "named-legacy", user)
        verify(extraction).rememberTextFromSource("named source", "named-source", user, locator)
    }

    @Test
    fun `legacy and source file entry points dispatch through their open text entry points`() {
        val pipeline = pipelineReturningNoResult()
        val extraction = spy(extraction(pipeline))
        val user = user()
        val locator = UriLocator("file:///notes/dispatch.txt")
        val revision = SourceRevisionRef(locator.key(), "dispatch-r1")
        doNothing().whenever(extraction).rememberText(
            any(),
            any(),
            any(),
            any(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
        )
        doNothing().whenever(extraction).rememberTextFromSource(
            any(),
            any(),
            any(),
            any(),
            anyOrNull(),
            any(),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
        )

        extraction.rememberFile(
            ByteArrayInputStream("legacy dispatch".toByteArray()),
            "legacy-dispatch.txt",
            user,
        )
        extraction.rememberFileFromSource(
            ByteArrayInputStream("source dispatch".toByteArray()),
            "source-dispatch.txt",
            user,
            locator,
            revision,
        )

        // Both file entry points land on the maximum-arity text method, which is the single
        // override point every call funnels through.
        verify(extraction).rememberText(
            "legacy dispatch",
            "remember:legacy-dispatch.txt",
            user,
            emptyList(),
            null,
            null,
            null,
        )
        verify(extraction).rememberTextFromSource(
            "source dispatch",
            "remember:source-dispatch.txt",
            user,
            locator,
            revision,
            emptyList(),
            null,
            null,
            null,
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
    fun `profile reaches the context through both text entry points`() {
        val pipeline = pipelineReturningNoResult()
        val extraction = extraction(pipeline)
        val user = user()
        val locator = UriLocator("https://example.com/profiled")
        val revision = SourceRevisionRef(locator.key(), "r1")
        val profile = ExtractionContentProfileRef("house-style", "v1")

        extraction.rememberText(
            text = "legacy text",
            sourceId = "legacy:profiled",
            user = user,
            additionalGrounding = emptyList(),
            perspective = null,
            mintNewEntities = null,
            profile = profile,
        )
        extraction.rememberTextFromSource(
            text = "source text",
            sourceId = "source:profiled",
            user = user,
            sourceLocator = locator,
            sourceRevision = revision,
            additionalGrounding = emptyList(),
            perspective = null,
            mintNewEntities = null,
            profile = profile,
        )

        val fromLegacy = capturedContext(pipeline, "legacy text", "legacy:profiled", emptyList())
        assertSame(profile, fromLegacy.profile)
        assertNull(fromLegacy.sourceLocator)

        val fromSource = capturedContext(pipeline, "source text", "source:profiled", emptyList())
        assertSame(profile, fromSource.profile)
        assertSame(revision, fromSource.sourceRevision)
    }

    @Test
    fun `profile reaches the context through both file entry points`() {
        val pipeline = pipelineReturningNoResult()
        val extraction = extraction(pipeline)
        val user = user()
        val locator = UriLocator("file:///notes/profiled.txt")
        val profile = ExtractionContentProfileRef("house-style", "v1")

        extraction.rememberFile(
            inputStream = ByteArrayInputStream("legacy file text".toByteArray()),
            filename = "legacy-profiled.txt",
            user = user,
            profile = profile,
        )
        extraction.rememberFileFromSource(
            inputStream = ByteArrayInputStream("source file text".toByteArray()),
            filename = "source-profiled.txt",
            user = user,
            sourceLocator = locator,
            sourceRevision = SourceRevisionRef(locator.key(), "r1"),
            profile = profile,
        )
        // Both file calls land on the profile-aware text entry point, so the two contexts the
        // pipeline sees are the proof that carriage survives the file hop.

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
        }
    }

    @Test
    fun `legacy calls carry no profile`() {
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
        }
    }

    @Test
    fun `a profile changes nothing else about what the pipeline is asked to do`() {
        val pipeline = pipelineReturningNoResult()
        val extraction = extraction(pipeline)
        val user = user()
        val grounding = listOf("record:one")
        val profile = ExtractionContentProfileRef("house-style", "v1")

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
            profile = profile,
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

        // Comparing whole contexts is the point: they agree on every component but the one the
        // second call set. The resolver is substituted because buildContext constructs a fresh
        // one per call by design, so it is never the same instance twice.
        assertEquals(
            plain,
            profiled.copy(profile = null, entityResolver = plain.entityResolver),
        )
        assertSame(profile, profiled.profile)
    }

    @Test
    fun `event profile reaches the context observed by the pipeline`() {
        val pipeline = pipelineReturningNoResult()
        val extraction = extraction(pipeline)
        val source = mock<IncrementalSource<Message>>()
        whenever(source.id).thenReturn("event-source")
        whenever(source.size).thenReturn(1)
        val profile = ExtractionContentProfileRef("house-style", "v1")
        val profileCalls = AtomicInteger()
        val event = object : SourceAnalysisRequestEvent(this, user()) {
            override fun incrementalSource(): IncrementalSource<Message> = source

            override fun profile(): ExtractionContentProfileRef =
                profile.also { profileCalls.incrementAndGet() }
        }

        extraction.extractPropositions(event)

        // One read: the async path builds one context through the same buildContext the direct
        // calls use, so there is nowhere else for a second read to happen.
        assertEquals(1, profileCalls.get())
        val contextCaptor = argumentCaptor<SourceAnalysisContext>()
        verify(pipeline).processChunk(any(), contextCaptor.capture())
        assertSame(profile, contextCaptor.firstValue.profile)
        assertNull(contextCaptor.firstValue.sourceLocator)
    }

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

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
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.Relations
import com.embabel.dice.incremental.ChunkHistoryStore
import com.embabel.dice.pipeline.PropositionPipeline
import com.embabel.dice.projection.graph.GraphProjectionService
import com.embabel.dice.proposition.PropositionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The extraction surface as it stands on `main`, pinned twice over.
 *
 * `main` declares exactly two entry points: `rememberFile(InputStream, String, NamedEntity)` and
 * `@JvmOverloads rememberText(text, sourceId, user, additionalGrounding = emptyList(),
 * perspective = null, mintNewEntities = null)`. This file calls every shape a caller could have
 * written against those two, and overrides both from a subclass — so it stops compiling if either
 * signature moves, and fails if a call stops reaching the override. The Java half of the same
 * claim lives in `SourceRevisionJavaInteropTest` and `ExtractionProfileJavaInteropTest`.
 */
class PreRequestEntryPointPinTest {

    @Test
    fun `every call a caller could write before requests still compiles and reaches the override`() {
        val pipeline = mock<PropositionPipeline>()
        val extraction = PreRequestSubclass(pipeline)
        val user = user()
        val input: InputStream = ByteArrayInputStream("file bytes".toByteArray())

        // Positional, at every arity @JvmOverloads publishes.
        extraction.rememberText("text", "three", user)
        extraction.rememberText("text", "four", user, listOf("record:one"))
        extraction.rememberText("text", "five", user, listOf("record:one"), ExtractionPerspective.USER)
        extraction.rememberText(
            "text",
            "six",
            user,
            listOf("record:one"),
            ExtractionPerspective.USER,
            true,
        )
        // Named, including out of order, which only resolves against the declared parameter names.
        extraction.rememberText(text = "text", sourceId = "named", user = user)
        extraction.rememberText(
            sourceId = "named-full",
            user = user,
            text = "text",
            mintNewEntities = null,
            perspective = null,
            additionalGrounding = emptyList(),
        )
        extraction.rememberFile(input, "legacy.txt", user)
        extraction.rememberFile(inputStream = input, filename = "named.txt", user = user)

        assertEquals(
            listOf(
                "text:three",
                "text:four",
                "text:five",
                "text:six",
                "text:named",
                "text:named-full",
                "file:legacy.txt",
                "file:named.txt",
            ),
            extraction.seen,
        )
        // Every one of those was intercepted, so nothing reached extraction proper.
        verifyNoInteractions(pipeline)
    }

    @Test
    fun `the entry point descriptors are the ones main publishes, plus one request form each`() {
        // Kotlin compiles the file entry points' lambdas into methods named `rememberFile$lambda$N`
        // that reflection does not report as synthetic, so they are excluded by name.
        val published = IncrementalPropositionExtraction::class.java.declaredMethods
            .filter { it.name.startsWith("remember") && '$' !in it.name && !it.isSynthetic }
            .map { it.name to it.parameterTypes.toList() }
            .toSet()

        val textPrefix = listOf(String::class.java, String::class.java, NamedEntity::class.java)
        val textFull = textPrefix + listOf(
            List::class.java,
            ExtractionPerspective::class.java,
            Boolean::class.javaObjectType,
        )
        val filePrefix = listOf(InputStream::class.java, String::class.java, NamedEntity::class.java)
        assertEquals(
            setOf(
                "rememberText" to textPrefix,
                "rememberText" to (textPrefix + List::class.java),
                "rememberText" to (textPrefix + listOf(List::class.java, ExtractionPerspective::class.java)),
                "rememberText" to textFull,
                "rememberText" to (textFull + ExtractionRequest::class.java),
                "rememberFile" to filePrefix,
                "rememberFile" to (filePrefix + ExtractionRequest::class.java),
            ),
            published,
        )
    }

    private fun user(): NamedEntity =
        mock<NamedEntity>().also { user ->
            whenever(user.id).thenReturn("user-1")
            whenever(user.name).thenReturn("Test User")
        }

    /**
     * A subclass written before requests existed, overriding the two signatures that were open
     * then. Compiling it is half the assertion; the recorded calls are the other half.
     */
    private class PreRequestSubclass(pipeline: PropositionPipeline) : IncrementalPropositionExtraction(
        propositionPipeline = pipeline,
        chunkHistoryStore = mock<ChunkHistoryStore>(),
        dataDictionary = mock<DataDictionary>(),
        relations = Relations.empty(),
        propositionRepository = mock<PropositionRepository>(),
        entityRepository = mock<NamedEntityDataRepository>(),
        entityResolver = mock<EntityResolver>(),
        graphProjectionService = mock<GraphProjectionService>(),
        properties = mock<PropositionExtractionProperties>().also { properties ->
            whenever(properties.windowSize).thenReturn(1)
            whenever(properties.overlapSize).thenReturn(1)
            whenever(properties.triggerInterval).thenReturn(1)
        },
    ) {

        val seen = mutableListOf<String>()

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
}

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
package com.embabel.dice.pipeline

import com.embabel.agent.core.ContextId
import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.KnownEntity
import com.embabel.dice.common.Relations
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionExtractor
import com.embabel.dice.proposition.extraction.ExtractionPerspective
import com.embabel.dice.provenance.ContentAddressedLocator
import com.embabel.dice.provenance.ProvenanceEntry
import com.embabel.dice.provenance.SourceRevisionRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions

class SourceRevisionPipelineTest {

    @Test
    fun `matching source revision is stamped onto provenance`() {
        val locator = ContentAddressedLocator("source-content")
        val context = context(sourceLocator = locator)
            .withSourceRevision(SourceRevisionRef(locator.key(), "revision-7"))
        val entry = stampProvenance(context)

        assertSame(locator, entry.locator)
        assertEquals("revision-7", entry.sourceRevision)
    }

    @Test
    fun `missing locator and mismatched key fail before extractor use`() {
        val extractor = mock(PropositionExtractor::class.java)
        val missingLocator = SourceRevisionRef("source-key", "revision-1")

        assertThrows(IllegalArgumentException::class.java) {
            context().withSourceRevision(missingLocator)
        }

        val locator = ContentAddressedLocator("source-content")
        assertThrows(IllegalArgumentException::class.java) {
            context(sourceLocator = locator)
                .withSourceRevision(SourceRevisionRef("different-key", "revision-1"))
        }
        verifyNoInteractions(extractor)
    }

    @Test
    fun `context refinements retain locator revision and other dimensions`() {
        val locator = ContentAddressedLocator("source-content")
        val revision = SourceRevisionRef(locator.key(), "revision-2")
        val knownEntity = mock(KnownEntity::class.java)
        val perspective = mock(ExtractionPerspective::class.java)
        val refined = context(sourceLocator = locator)
            .withSourceRevision(revision)
            .withKnownEntities(knownEntity)
            .withRelations(Relations.empty())
            .withPromptVariables(mapOf("audience" to "test"))
            .withPerspective(perspective)
            .withMintNewEntities(true)
            .withMintedEntityProperties(mapOf("tenant" to "one"))

        assertSame(locator, refined.sourceLocator)
        assertSame(revision, refined.sourceRevision)
        assertEquals(listOf(knownEntity), refined.knownEntities)
        assertEquals(mapOf("audience" to "test"), refined.promptVariables)
        assertSame(perspective, refined.perspective)
        assertEquals(true, refined.mintNewEntities)
        assertEquals(mapOf("tenant" to "one"), refined.mintedEntityProperties)
    }

    @Test
    fun `null source revision preserves content addressed fallback`() {
        val entry = stampProvenance(context())

        assertInstanceOf(ContentAddressedLocator::class.java, entry.locator)
        assertNull(entry.sourceRevision)
    }

    private fun context(sourceLocator: ContentAddressedLocator? = null): SourceAnalysisContext =
        SourceAnalysisContext(
            schema = mock(DataDictionary::class.java),
            entityResolver = mock(EntityResolver::class.java),
            contextId = ContextId("source-revision-test"),
            sourceLocator = sourceLocator,
        )

    @Suppress("UNCHECKED_CAST")
    private fun stampProvenance(context: SourceAnalysisContext): ProvenanceEntry {
        val extractor = mock(PropositionExtractor::class.java)
        val pipeline = PropositionPipeline.withExtractor(extractor)
        val proposition = mock(Proposition::class.java)
        lateinit var captured: List<ProvenanceEntry>
        doAnswer { invocation ->
            captured = invocation.getArgument(0)
            proposition
        }.`when`(proposition).withProvenanceEntries(anyList())

        val method = PropositionPipeline::class.java.getDeclaredMethod(
            "stampProvenance",
            List::class.java,
            Chunk::class.java,
            SourceAnalysisContext::class.java,
        )
        method.isAccessible = true
        val stamped = method.invoke(
            pipeline,
            listOf(proposition),
            Chunk.create(text = "source text", parentId = "source", id = "chunk"),
            context,
        ) as List<Proposition>

        assertEquals(listOf(proposition), stamped)
        return captured.single()
    }
}

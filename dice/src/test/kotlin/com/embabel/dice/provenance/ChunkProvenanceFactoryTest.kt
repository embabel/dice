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
package com.embabel.dice.provenance

import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.model.Chunk
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.resolver.AlwaysCreateEntityResolver
import com.embabel.dice.text2graph.builder.Person
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ChunkProvenanceFactoryTest {

    private val schema = com.embabel.agent.core.DataDictionary.fromClasses("test", Person::class.java)
    private val context = SourceAnalysisContext(
        schema = schema,
        entityResolver = AlwaysCreateEntityResolver,
        contextId = ContextId("ctx"),
    )

    @Test
    fun `uses default source locator from context`() {
        val locator = UriLocator("https://example.com/doc")
        val ctx = context.withDefaultSourceLocator(locator)
        val chunk = Chunk(id = "chunk-1", text = "text", metadata = emptyMap(), parentId = "")

        val entry = ChunkProvenanceFactory.entryFor(chunk, ctx, contentHash = "abc")

        assertEquals(locator, entry.locator)
        assertEquals("chunk-1", entry.chunkId)
        assertEquals("abc", entry.contentHash)
    }

    @Test
    fun `parses connector-style chunk ids`() {
        val chunk = Chunk(id = "email:thread-9", text = "text", metadata = emptyMap(), parentId = "")

        val locator = ChunkProvenanceFactory.resolveLocator(chunk, context)

        assertInstanceOf(ConnectorRef::class.java, locator)
        assertEquals(ConnectorRef("email", "thread-9"), locator)
    }

    @Test
    fun `parses uri chunk ids`() {
        val chunk = Chunk(
            id = "https://example.com/page",
            text = "text",
            metadata = emptyMap(),
            parentId = "",
        )

        val locator = ChunkProvenanceFactory.resolveLocator(chunk, context)

        assertEquals(UriLocator("https://example.com/page"), locator)
    }

    @Test
    fun `reads source metadata when present`() {
        val chunk = Chunk(
            id = "chunk-1",
            text = "text",
            metadata = mapOf("source_uri" to "https://docs.example.com/spec"),
            parentId = "",
        )

        val locator = ChunkProvenanceFactory.resolveLocator(chunk, context)

        assertEquals(UriLocator("https://docs.example.com/spec"), locator)
    }
}

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
package com.embabel.dice.entity

import com.embabel.dice.text2graph.support.Entities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * A model that has nothing to say about a property often writes it as null
 * rather than leaving it out. That must not cost us the whole extraction.
 */
class ExtractedEntitiesNullPropertyTest {

    private val reply = """
        {
          "entities": [
            {
              "labels": ["Tool"],
              "name": "embabel",
              "summary": "A command line tool",
              "properties": {"website": null, "language": "Python"}
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `a null property is dropped and the rest of the entity is kept`() {
        val extracted = jacksonObjectMapper().readValue<ExtractedEntities>(reply)

        val suggested = extracted.entities.single().toSuggestedEntity(chunkId = "chunk-1")

        assertEquals("embabel", suggested.name)
        assertEquals(mapOf<String, Any>("language" to "Python"), suggested.properties)
    }

    @Test
    fun `the source analyzer's reply drops a null property the same way`() {
        val extracted = jacksonObjectMapper().readValue<Entities>(reply)

        val suggested = extracted.entities.single().toSuggestedEntity(chunkId = "chunk-1")

        assertEquals(mapOf<String, Any>("language" to "Python"), suggested.properties)
    }
}

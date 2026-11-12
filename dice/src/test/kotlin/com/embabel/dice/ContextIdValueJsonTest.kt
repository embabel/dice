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
package com.embabel.dice

import com.embabel.dice.common.PropositionContradicted
import com.embabel.dice.common.UngroundedMentionsFound
import com.embabel.dice.incremental.BookmarkKey
import com.embabel.dice.incremental.HashKey
import com.embabel.dice.incremental.ProcessedChunkRecord
import com.embabel.dice.projection.memory.DreamLoopReport
import com.embabel.dice.projection.memory.collector.CollectorRunContext
import com.embabel.dice.spi.CollapseUndoCommand
import com.embabel.dice.spi.CollapseUndoContextMismatchException
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checks that the plain-string context id accessors stay out of the JSON form of the events.
 */
class ContextIdValueJsonTest {

    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    /**
     * Lists the top-level field names of a value once it has been serialized to JSON.
     *
     * @param value The object to serialize.
     * @return The field names, in no particular order.
     */
    private fun fieldNames(value: Any): Set<String> {
        val node: JsonNode = mapper.valueToTree(value)
        return node.fieldNames().asSequence().toSet()
    }

    @Test
    fun `an ungrounded mentions event serializes without a contextIdValue field`() {
        val names = fieldNames(ContextIdFixtures.ungroundedMentionsFound("acme"))

        assertTrue(names.contains("timestamp"), "expected a timestamp field, got $names")
        assertFalse(names.contains("contextIdValue"), "contextIdValue must not be serialized, got $names")
    }

    @Test
    fun `a contradiction event serializes without a contextIdValue field`() {
        val names = fieldNames(ContextIdFixtures.propositionContradicted("acme"))

        assertTrue(names.contains("timestamp"), "expected a timestamp field, got $names")
        assertFalse(names.contains("contextIdValue"), "contextIdValue must not be serialized, got $names")
    }

    @Test
    fun `chunk history keys and records serialize without a contextIdValue field`() {
        val keyNames = fieldNames(ContextIdFixtures.bookmarkKey("acme"))
        val hashNames = fieldNames(ContextIdFixtures.hashKey("acme"))
        val recordNames = fieldNames(ContextIdFixtures.processedChunkRecord("acme"))

        assertTrue(keyNames.contains("sourceId"), "expected a sourceId field, got $keyNames")
        assertFalse(keyNames.contains("contextIdValue"), "contextIdValue must not be serialized, got $keyNames")
        assertTrue(hashNames.contains("contentHash"), "expected a contentHash field, got $hashNames")
        assertFalse(hashNames.contains("contextIdValue"), "contextIdValue must not be serialized, got $hashNames")
        assertTrue(recordNames.contains("startIndex"), "expected a startIndex field, got $recordNames")
        assertFalse(recordNames.contains("contextIdValue"), "contextIdValue must not be serialized, got $recordNames")
    }

    @Test
    fun `a dream loop report serializes without a contextIdValue field`() {
        val names = fieldNames(ContextIdFixtures.dreamLoopReport("acme"))

        assertTrue(names.contains("totalExamined"), "expected a totalExamined field, got $names")
        assertFalse(names.contains("contextIdValue"), "contextIdValue must not be serialized, got $names")
    }

    @Test
    fun `a collapse undo command serializes without a contextIdValue field`() {
        val names = fieldNames(ContextIdFixtures.collapseUndoCommand("acme"))

        assertTrue(names.contains("survivorId"), "expected a survivorId field, got $names")
        assertFalse(names.contains("contextIdValue"), "contextIdValue must not be serialized, got $names")
    }

    @Test
    fun `every context id string getter is excluded from JSON`() {
        val getters: List<Pair<Class<*>, String>> = listOf(
            UngroundedMentionsFound::class.java to "getContextIdValue",
            PropositionContradicted::class.java to "getContextIdValue",
            BookmarkKey::class.java to "getContextIdValue",
            HashKey::class.java to "getContextIdValue",
            ProcessedChunkRecord::class.java to "getContextIdValue",
            DreamLoopReport::class.java to "getContextIdValue",
            CollectorRunContext::class.java to "getContextIdValue",
            CollapseUndoCommand::class.java to "getContextIdValue",
            CollapseUndoContextMismatchException::class.java to "getCommandedContextIdValue",
            CollapseUndoContextMismatchException::class.java to "getActualContextIdValue",
        )
        assertEquals(10, getters.size, "expected ten context id getters in the list")

        for ((type, methodName) in getters) {
            assertTrue(
                type.getMethod(methodName).isAnnotationPresent(JsonIgnore::class.java),
                "${type.name}.$methodName must carry @get:JsonIgnore so it stays out of JSON",
            )
        }
    }
}

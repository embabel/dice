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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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
}

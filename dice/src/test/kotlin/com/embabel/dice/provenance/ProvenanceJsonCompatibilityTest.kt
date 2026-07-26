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

import com.embabel.dice.proposition.Proposition
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProvenanceJsonCompatibilityTest {

    private val defaultMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())

    private val bundleReader = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    @Test
    fun `revisionless fixture loads a null source revision`() {
        val proposition = defaultMapper.readValue<Proposition>(fixture("proposition-revisionless.json"))
        val evidence = proposition.provenanceEntries.single()

        assertNull(evidence.sourceRevision)
        assertEquals("chunk-legacy", evidence.chunkId)
        assertEquals("uri:https://example.com/legacy", evidence.locator.key())
        assertEquals(
            proposition,
            defaultMapper.readValue<Proposition>(defaultMapper.writeValueAsString(proposition)),
        )
    }

    @Test
    fun `revision scalars round trip losslessly and literal null remains a value`() {
        val fixture = fixture("proposition-revisioned.json")
        val proposition = defaultMapper.readValue<Proposition>(fixture)

        assertTrue(fixture.contains("\"sourceRevision\": \"null\""))
        assertEquals(listOf("r1", "null"), proposition.provenanceEntries.map { it.sourceRevision })

        val roundTripped = defaultMapper.readValue<Proposition>(
            defaultMapper.writeValueAsString(proposition),
        )
        assertEquals(proposition, roundTripped)
        assertEquals("null", roundTripped.provenanceEntries.last().sourceRevision)
    }

    @Test
    fun `only the explicit bundle reader tolerates a future unknown field`() {
        val tree = defaultMapper.readTree(fixture("proposition-revisioned.json")) as ObjectNode
        (tree.withArray("provenanceEntries")[0] as ObjectNode)
            .put("futureEvidenceField", "future-value")
        val withFutureField = tree.toString()

        assertTrue(defaultMapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
        assertThrows(UnrecognizedPropertyException::class.java) {
            defaultMapper.readValue<Proposition>(withFutureField)
        }

        val imported = bundleReader.readValue<Proposition>(withFutureField)
        assertEquals(listOf("r1", "null"), imported.provenanceEntries.map { it.sourceRevision })
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/provenance/$name")) {
            "Missing provenance fixture: $name"
        }.readText()
}

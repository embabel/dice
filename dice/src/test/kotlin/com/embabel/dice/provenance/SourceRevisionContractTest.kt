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

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

class SourceRevisionContractTest {

    private val objectMapper = jacksonObjectMapper()
    private val locator = UriLocator("https://example.com/source")

    @Test
    fun `source revision ref preserves opaque values`() {
        val ref = SourceRevisionRef(
            sourceKey = locator.key(),
            sourceRevision = "null",
        )

        assertThat(ref.sourceKey).isEqualTo(locator.key())
        assertThat(ref.sourceRevision).isEqualTo("null")
        assertThat(objectMapper.readValue<SourceRevisionRef>(objectMapper.writeValueAsString(ref)))
            .isEqualTo(ref)
    }

    @Test
    fun `source revision ref rejects blank components`() {
        assertThatIllegalArgumentException()
            .isThrownBy { SourceRevisionRef(" ", "r1") }
        assertThatIllegalArgumentException()
            .isThrownBy { SourceRevisionRef(locator.key(), "\t") }
    }

    @Test
    fun `provenance revision participates in equality and deduplication`() {
        val revisionless = ProvenanceEntry(locator = locator)
        val sameRevisionless = ProvenanceEntry(locator = locator)
        val revisionOne = ProvenanceEntry(locator = locator, sourceRevision = "r1")
        val duplicateRevisionOne = ProvenanceEntry(locator = locator, sourceRevision = "r1")
        val revisionTwo = ProvenanceEntry(locator = locator, sourceRevision = "r2")

        assertThat(revisionless).isEqualTo(sameRevisionless)
        assertThat(revisionOne).isNotEqualTo(revisionTwo)
        assertThat(listOf(revisionOne, duplicateRevisionOne, revisionTwo).distinct())
            .containsExactly(revisionOne, revisionTwo)
    }

    @Test
    fun `provenance rejects a blank present revision`() {
        assertThatIllegalArgumentException()
            .isThrownBy { ProvenanceEntry(locator = locator, sourceRevision = " ") }
    }

    @Test
    fun `old and new provenance json preserve revision absence and value`() {
        val revisionless = ProvenanceEntry(locator = locator)
        val oldJson = objectMapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(
            revisionless
        ).apply {
            remove("sourceRevision")
        }
        val fromOldJson = objectMapper.treeToValue(oldJson, ProvenanceEntry::class.java)

        val revised = ProvenanceEntry(locator = locator, sourceRevision = "null")
        val fromNewJson = objectMapper.readValue<ProvenanceEntry>(
            objectMapper.writeValueAsString(revised)
        )

        assertThat(fromOldJson).isEqualTo(revisionless)
        assertThat(fromOldJson.sourceRevision).isNull()
        assertThat(fromNewJson).isEqualTo(revised)
        assertThat(fromNewJson.sourceRevision).isEqualTo("null")
        assertThat(fromNewJson).isNotEqualTo(fromOldJson)
    }
}

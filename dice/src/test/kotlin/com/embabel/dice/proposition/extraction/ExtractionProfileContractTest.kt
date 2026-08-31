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

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test

/**
 * What the new reference type promises: stable name-and-version identity for a profile, a
 * bounded string, and no interpretation of either component.
 */
class ExtractionProfileContractTest {

    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `profile ref preserves opaque name and version`() {
        // Values a host might plausibly mint, including punctuation and non-ASCII. DICE never
        // parses these, so nothing here is a reserved character.
        val ref = ExtractionContentProfileRef(
            name = "house/style:with spaces & 記号",
            version = "2026-08-31+build.7",
        )

        assertThat(ref.name).isEqualTo("house/style:with spaces & 記号")
        assertThat(ref.version).isEqualTo("2026-08-31+build.7")
        assertThat(
            objectMapper.readValue<ExtractionContentProfileRef>(objectMapper.writeValueAsString(ref)),
        ).isEqualTo(ref)
    }

    @Test
    fun `profile identity is name and version together`() {
        val v1 = ExtractionContentProfileRef("house-style", "v1")
        val sameV1 = ExtractionContentProfileRef("house-style", "v1")
        val v2 = ExtractionContentProfileRef("house-style", "v2")
        val otherName = ExtractionContentProfileRef("legal-review", "v1")

        assertThat(v1).isEqualTo(sameV1)
        assertThat(v1.hashCode()).isEqualTo(sameV1.hashCode())
        // Republishing a profile under a new version yields a distinct reference, so runs
        // attributed to the old version stay attributed to it.
        assertThat(v1).isNotEqualTo(v2)
        assertThat(v1).isNotEqualTo(otherName)
        assertThat(listOf(v1, sameV1, v2, otherName).distinct()).containsExactly(v1, v2, otherName)
    }

    @Test
    fun `profile ref rejects blank components`() {
        assertThatIllegalArgumentException()
            .isThrownBy { ExtractionContentProfileRef(" ", "v1") }
            .withMessageContaining("name")
        assertThatIllegalArgumentException()
            .isThrownBy { ExtractionContentProfileRef("house-style", "\t") }
            .withMessageContaining("version")
    }

    @Test
    fun `profile ref accepts its length caps and rejects one character more`() {
        val name = "n".repeat(ExtractionContentProfileRef.MAX_NAME_LENGTH)
        val version = "v".repeat(ExtractionContentProfileRef.MAX_VERSION_LENGTH)

        val atCap = ExtractionContentProfileRef(name, version)
        assertThat(atCap.name).hasSize(ExtractionContentProfileRef.MAX_NAME_LENGTH)
        assertThat(atCap.version).hasSize(ExtractionContentProfileRef.MAX_VERSION_LENGTH)

        assertThatIllegalArgumentException()
            .isThrownBy { ExtractionContentProfileRef(name + "n", version) }
            .withMessageContaining("name")
        assertThatIllegalArgumentException()
            .isThrownBy { ExtractionContentProfileRef(name, version + "v") }
            .withMessageContaining("version")
    }
}

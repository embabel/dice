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
package com.embabel.dice.proposition

import com.embabel.agent.core.ContextId
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// A real consumer registers modules (incl. JSR-310 for the Instant fields) on its mapper;
// findAndRegisterModules() picks up jackson-datatype-jsr310 from the classpath.

/**
 * Verifies that a [Proposition] round-trips cleanly through JSON. The class exposes computed
 * read-only accessors (`contextIdValue`, `revised`, `lastTouched`) with no matching constructor
 * parameters, so the mapper must tolerate unknown properties on read — if it doesn't,
 * deserialization fails. This pins that the canonical knowledge unit survives a default
 * Kotlin mapper round-trip.
 */
class PropositionJacksonRoundTripTest {

    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `a Proposition round-trips through JSON preserving its core fields`() {
        val original = Proposition(
            contextId = ContextId("ctx-round-trip"),
            text = "The substrate stays canonical",
            mentions = emptyList(),
            confidence = 0.8,
            decay = 0.1,
        ).withMetadataValue("dice.trust.score", 0.9)

        val json = mapper.writeValueAsString(original)
        val restored = mapper.readValue(json, Proposition::class.java)

        assertEquals(original.id, restored.id)
        assertEquals(original.contextId, restored.contextId)
        assertEquals(original.text, restored.text)
        assertEquals(original.confidence, restored.confidence)
        assertEquals(original.decay, restored.decay)
        assertEquals(original.status, restored.status)
        assertEquals(original.contentRevised, restored.contentRevised)
        assertEquals(original.metadata["dice.trust.score"], restored.metadata["dice.trust.score"])
    }
}

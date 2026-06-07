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
package com.embabel.dice.common

import com.embabel.agent.core.ContextId
import com.embabel.dice.proposition.EntityMention
import com.embabel.dice.proposition.MentionRole
import com.embabel.dice.proposition.Proposition
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The gate-decision events are declared on a `@JsonTypeInfo(use = CLASS)` interface, which is the
 * contract that lets a consumer persist or transport them polymorphically. This verifies that
 * contract holds: each event serialises with a type discriminator and deserialises back to its
 * concrete subtype through the [DiceEvent] interface, preserving the carried reason.
 */
class GateDiceEventSerializationTest {

    // Ignore unknown properties so the test exercises the gate-event polymorphism contract
    // (the @JsonTypeInfo discriminator on the DiceEvent interface) without coupling to the
    // nested Proposition type's own serialization shape, which carries a derived getter
    // (contextIdValue) that has no matching constructor parameter.
    private val mapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private fun proposition(): Proposition = Proposition(
        contextId = ContextId("ctx"),
        text = "Jim is an expert in GOAP",
        mentions = listOf(EntityMention(span = "Jim", type = "Person", role = MentionRole.SUBJECT)),
        confidence = 0.9,
    )

    @Test
    fun `a rejected event round-trips polymorphically through the DiceEvent interface`() {
        val original: DiceEvent = PropositionRejected(proposition(), "low confidence")

        val json = mapper.writeValueAsString(original)
        val restored = mapper.readValue(json, DiceEvent::class.java)

        assertInstanceOf(PropositionRejected::class.java, restored)
        assertEquals("low confidence", (restored as PropositionRejected).reason)
    }

    @Test
    fun `a routed-to-review event round-trips polymorphically through the DiceEvent interface`() {
        val original: DiceEvent = PropositionRoutedToReview(proposition(), "merge candidate")

        val restored = mapper.readValue(mapper.writeValueAsString(original), DiceEvent::class.java)

        assertInstanceOf(PropositionRoutedToReview::class.java, restored)
        assertEquals("merge candidate", (restored as PropositionRoutedToReview).reason)
    }

    @Test
    fun `a projection-skipped event round-trips polymorphically through the DiceEvent interface`() {
        val original: DiceEvent = PropositionProjectionSkipped(proposition(), "noisy")

        val restored = mapper.readValue(mapper.writeValueAsString(original), DiceEvent::class.java)

        assertInstanceOf(PropositionProjectionSkipped::class.java, restored)
        assertEquals("noisy", (restored as PropositionProjectionSkipped).reason)
    }

    @Test
    fun `serialised form carries a type discriminator so distinct subtypes are distinguishable`() {
        val rejectedJson = mapper.writeValueAsString(PropositionRejected(proposition(), "x") as DiceEvent)
        val reviewJson = mapper.writeValueAsString(PropositionRoutedToReview(proposition(), "x") as DiceEvent)

        assertTrue(rejectedJson.contains("PropositionRejected"))
        assertTrue(reviewJson.contains("PropositionRoutedToReview"))
    }
}

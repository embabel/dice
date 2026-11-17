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
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.SuggestedMention
import com.embabel.dice.proposition.SuggestedPropositions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the rejected mentions a chunk result carries and the compiled members of
 * [ChunkPropositionResult.Success] that callers link against.
 */
class ChunkPropositionResultRejectedMentionsTest {

    private val suggested = SuggestedPropositions("c", emptyList())
    private val resolutions = Resolutions<SuggestedEntityResolution>(setOf("c"), emptyList())
    private val rejected = RejectedMention(
        "c",
        0,
        "Alice met Bob",
        SuggestedMention("Carol", "Person"),
        "SPAN_NOT_IN_PROPOSITION",
        RejectedMention.Check.GROUNDING,
        RejectedMentionHandling.DROP,
    )
    private val proposition = Proposition(
        contextId = ContextId("test-context"),
        text = "Alice met Bob",
        mentions = emptyList(),
        confidence = 0.9,
        grounding = listOf("c"),
    )

    /**
     * Builds a successful result that carries the given rejected mentions.
     *
     * @param rejectedMentions the rejected mentions the result holds
     * @param chunkId the id of the chunk
     * @return a result with no propositions
     */
    private fun success(
        rejectedMentions: List<RejectedMention>,
        chunkId: String = "c",
    ): ChunkPropositionResult.Success =
        ChunkPropositionResult.Success(chunkId, suggested, resolutions, emptyList(), emptyList(), rejectedMentions)

    @Test
    fun `E1 the four and five argument constructors and the factory give an empty list and six arguments hold it`() {
        assertEquals(
            emptyList<RejectedMention>(),
            ChunkPropositionResult.Success("c", suggested, resolutions, emptyList()).rejectedMentions,
        )
        assertEquals(
            emptyList<RejectedMention>(),
            ChunkPropositionResult.Success("c", suggested, resolutions, emptyList(), emptyList()).rejectedMentions,
        )
        assertEquals(
            emptyList<RejectedMention>(),
            ChunkPropositionResult.success("c", suggested, resolutions, emptyList()).rejectedMentions,
        )
        assertEquals(listOf(rejected), success(listOf(rejected)).rejectedMentions)
    }

    @Test
    fun `E2 copy with new propositions keeps the rejected mentions and changes only the propositions`() {
        val original = success(listOf(rejected))

        val copied = original.copy(propositions = listOf(proposition))

        assertEquals(listOf(rejected), copied.rejectedMentions)
        assertEquals(listOf(proposition), copied.propositions)
        assertEquals(original.chunkId, copied.chunkId)
        assertEquals(original.suggestedPropositions, copied.suggestedPropositions)
        assertEquals(original.entityResolutions, copied.entityResolutions)
        assertEquals(original.revisionResults, copied.revisionResults)
    }

    @Test
    fun `E3 results that differ only in rejected mentions are not equal and equal arguments give equal results`() {
        assertNotEquals(success(listOf(rejected)), success(emptyList()))
        assertEquals(success(listOf(rejected)), success(listOf(rejected)))
        assertEquals(success(listOf(rejected)).hashCode(), success(listOf(rejected)).hashCode())
    }

    @Test
    fun `E4 destructuring gives the five original properties in order`() {
        val result = ChunkPropositionResult.Success("c", suggested, resolutions, listOf(proposition), emptyList(), listOf(rejected))

        val (chunkId, suggestedPropositions, entityResolutions, propositions, revisionResults) = result

        assertEquals("c", chunkId)
        assertEquals(suggested, suggestedPropositions)
        assertEquals(resolutions, entityResolutions)
        assertEquals(listOf(proposition), propositions)
        assertEquals(emptyList<Any>(), revisionResults)
    }

    @Test
    fun `E5 a failed result read through the interface has no rejected mentions`() {
        val failed: ChunkPropositionResult = ChunkPropositionResult.Failed("c", "boom")

        assertEquals(emptyList<RejectedMention>(), failed.rejectedMentions)
    }

    @Test
    fun `E6 the run result lists every chunk's rejected mentions in chunk order with their own chunk ids`() {
        val first = rejected.copy(chunkId = "a")
        val second = rejected.copy(chunkId = "b", propositionIndex = 2)
        val a = success(listOf(first), chunkId = "a")
        val b = success(listOf(second), chunkId = "b")
        val failed = ChunkPropositionResult.Failed("f", "boom")

        val results = PropositionResults(listOf(a, failed, b), emptyList())

        assertEquals(listOf(first, second), results.rejectedMentions)
        assertEquals(listOf("a", "b"), results.rejectedMentions.map { it.chunkId })
    }

    @Test
    fun `E7 adding grounding to a result keeps its rejected mentions`() {
        val result = ChunkPropositionResult.Success(
            "c", suggested, resolutions, listOf(proposition), emptyList(), listOf(rejected),
        )

        val grounded = result.withAdditionalGrounding(listOf("email:7"))

        assertEquals(listOf(rejected), grounded.rejectedMentions)
        assertEquals(listOf("c", "email:7"), grounded.propositions.single().grounding)
    }

    @Test
    fun `E8 the compiled constructors copy and component methods are where they were`() {
        val success = ChunkPropositionResult.Success::class.java
        val string = String::class.java
        val suggestedType = SuggestedPropositions::class.java
        val resolutionsType = Resolutions::class.java
        val list = List::class.java
        val int = Int::class.javaPrimitiveType!!
        val marker = Class.forName("kotlin.jvm.internal.DefaultConstructorMarker")
        val five = listOf(string, suggestedType, resolutionsType, list, list)

        val published = success.constructors.map { it.parameterTypes.toList() }
        assertTrue(five in published, "five argument constructor missing from $published")
        assertTrue(five.take(4) in published, "four argument constructor missing from $published")

        val synthetic = success.declaredConstructors.filter { it.isSynthetic }.map { it.parameterTypes.toList() }
        assertTrue(five + listOf(int, marker) in synthetic, "synthetic default constructor missing from $synthetic")

        val components = (1..5).map { success.getMethod("component$it").returnType }
        assertEquals(listOf(string, suggestedType, resolutionsType, list, list), components)
        assertFalse(success.methods.any { it.name == "component6" }, "component6 is published")

        val copy = success.getMethod("copy", *five.toTypedArray())
        assertEquals(success, copy.returnType)

        val copyDefault = success.declaredMethods.single { it.name == "copy\$default" }
        assertTrue(copyDefault.isSynthetic, "copy\$default is not synthetic")
        assertTrue(java.lang.reflect.Modifier.isStatic(copyDefault.modifiers), "copy\$default is not static")
        assertEquals(
            listOf(success) + five + listOf(int, Any::class.java),
            copyDefault.parameterTypes.toList(),
        )
    }

    @Test
    fun `E9 toString names the class and lists the rejected mentions`() {
        val text = success(listOf(rejected)).toString()

        assertTrue(text.startsWith("Success(chunkId=c, "), text)
        assertTrue(text.contains("rejectedMentions="), text)
    }

    @Test
    fun `E10 a rejected mention is a value and copy changes only the named field`() {
        val same = RejectedMention(
            "c",
            0,
            "Alice met Bob",
            SuggestedMention("Carol", "Person"),
            "SPAN_NOT_IN_PROPOSITION",
            RejectedMention.Check.GROUNDING,
            RejectedMentionHandling.DROP,
        )
        assertEquals(rejected, same)
        assertEquals(rejected.hashCode(), same.hashCode())

        val moved = rejected.copy(propositionIndex = 1)

        assertNotEquals(rejected, moved)
        assertEquals(1, moved.propositionIndex)
        assertEquals(rejected, moved.copy(propositionIndex = 0))
    }
}

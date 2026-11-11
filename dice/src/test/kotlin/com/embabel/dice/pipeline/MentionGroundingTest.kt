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

import com.embabel.dice.pipeline.UngroundedMention.Reason
import com.embabel.dice.proposition.SuggestedMention
import com.embabel.dice.proposition.SuggestedProposition
import com.embabel.dice.proposition.SuggestedPropositions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MentionGroundingTest {

    private fun reason(span: String, text: String): Reason? =
        MentionGrounding.reasonUngrounded(span, text)

    private fun assertGrounded(span: String, text: String) =
        assertNull(reason(span, text), "expected <$span> to be grounded in <$text>")

    private fun mention(span: String, type: String = "Person", suggestedId: String? = null, role: String = "OTHER") =
        SuggestedMention(span = span, type = type, suggestedId = suggestedId, role = role)

    private fun proposition(text: String, vararg mentions: SuggestedMention) =
        SuggestedProposition(text = text, mentions = mentions.toList(), confidence = 0.9)

    @Test
    fun `E1 empty and whitespace-only spans are blank`() {
        assertEquals(Reason.BLANK_SPAN, reason("", "Bob likes tea"))
        assertEquals(Reason.BLANK_SPAN, reason("   ", "Bob likes tea"))
        assertEquals(Reason.BLANK_SPAN, reason("\t\u00a0", "Bob likes tea"))
    }

    @Test
    fun `E2 a span missing from the text is not in the proposition`() {
        assertEquals(Reason.SPAN_NOT_IN_PROPOSITION, reason("Alice", "Bob likes tea"))
    }

    @Test
    fun `E3 case is ignored`() {
        assertGrounded("OPENAI", "OpenAI ships a model")
    }

    @Test
    fun `E4 a decomposed accent matches its composed form both ways`() {
        assertGrounded("Jos\u00e9", "Jose\u0301 lives in Lima")
        assertGrounded("Jose\u0301", "Jos\u00e9 lives in Lima")
    }

    @Test
    fun `E5 full-width letters match their plain forms`() {
        assertGrounded("\uff21\uff43\uff4d\uff45", "Acme hired Bob")
    }

    @Test
    fun `E6 any kind and amount of whitespace matches a single space`() {
        val text = "She moved to New York"
        assertGrounded("New  York", text)
        assertGrounded("New\u00a0York", text)
        assertGrounded("New\u2028York", text)
        assertGrounded(" New York ", text)
        assertGrounded("New York", "She moved to New\nYork")
    }

    @Test
    fun `E7 curly and modifier apostrophes match a straight one`() {
        assertGrounded("O\u2019Brien", "O'Brien runs the lab")
        assertGrounded("O\u02bcBrien", "O'Brien runs the lab")
    }

    @Test
    fun `E8 a span inside a longer word is grounded`() {
        assertGrounded("Ann", "Annabel sings")
    }

    @Test
    fun `E9 only the mention's own proposition counts`() {
        val alice = mention("Alice")
        val suggested = SuggestedPropositions(
            chunkId = "chunk-1",
            propositions = listOf(
                proposition("Bob likes tea", alice),
                proposition("Alice likes jazz"),
            ),
        )
        val outcome = MentionGrounding.check(suggested)
        assertEquals(listOf(UngroundedMention("Bob likes tea", alice, Reason.SPAN_NOT_IN_PROPOSITION)), outcome.ungrounded)
        assertTrue(outcome.grounded.propositions[0].mentions.isEmpty())
    }

    @Test
    fun `E10 non-breaking hyphens and en dashes match a plain hyphen`() {
        assertGrounded("Coca-Cola", "Coca\u2011Cola raised prices")
        assertGrounded("Coca-Cola", "Coca\u2013Cola raised prices")
    }

    @Test
    fun `E11 zero-width spaces and soft hyphens are ignored`() {
        assertGrounded("Acme", "Ac\u200bme hired Bob")
        assertGrounded("Acme", "Ac\u00adme hired Bob")
    }

    @Test
    fun `E12 a dotted capital I matches a plain lower-case i`() {
        assertGrounded("istanbul", "\u0130stanbul is large")
    }

    @Test
    fun `E13 accents are significant`() {
        assertEquals(Reason.SPAN_NOT_IN_PROPOSITION, reason("Beyonce", "Beyonc\u00e9 sings"))
    }

    @Test
    fun `E14 suggestedId plays no part`() {
        val alice = mention("Alice", suggestedId = "person-1")
        val missing = MentionGrounding.check(SuggestedPropositions("c", listOf(proposition("Bob likes tea", alice))))
        assertEquals(listOf(UngroundedMention("Bob likes tea", alice, Reason.SPAN_NOT_IN_PROPOSITION)), missing.ungrounded)
        val present = MentionGrounding.check(SuggestedPropositions("c", listOf(proposition("Alice likes tea", alice))))
        assertTrue(present.ungrounded.isEmpty())
        assertEquals(listOf(alice), present.grounded.propositions[0].mentions)
    }

    @Test
    fun `I1 every proposition comes back in order even when it loses all its mentions`() {
        val suggested = SuggestedPropositions(
            chunkId = "chunk-1",
            propositions = listOf(
                proposition("Bob likes tea", mention("Bob")),
                proposition("Carol paints", mention("Zed"), mention("Yan")),
                proposition("Dan runs", mention("Dan")),
            ),
        )
        val outcome = MentionGrounding.check(suggested)
        assertEquals(listOf("Bob likes tea", "Carol paints", "Dan runs"), outcome.grounded.propositions.map { it.text })
        assertEquals(emptyList<SuggestedMention>(), outcome.grounded.propositions[1].mentions)
    }

    @Test
    fun `I2 grounded mentions keep their order, duplicates and fields`() {
        val bob = mention("Bob", type = "Person", suggestedId = "person-bob", role = "SUBJECT")
        val tea = mention("tea", type = "Drink", suggestedId = null, role = "OBJECT")
        val bobAgain = mention("Bob", type = "Person", suggestedId = "person-bob", role = "SUBJECT")
        val suggested = SuggestedPropositions(
            chunkId = "chunk-1",
            propositions = listOf(proposition("Bob likes tea", bob, mention("Alice"), tea, bobAgain)),
        )
        val kept = MentionGrounding.check(suggested).grounded.propositions[0].mentions
        assertEquals(listOf(bob, tea, bobAgain), kept)
        assertEquals("person-bob", kept[0].suggestedId)
        assertEquals("SUBJECT", kept[0].role)
        assertEquals("Drink", kept[1].type)
        assertEquals("OBJECT", kept[1].role)
    }

    @Test
    fun `I3 only mentions change and chunkId is kept`() {
        val input = SuggestedProposition(
            text = "Bob likes tea",
            mentions = listOf(mention("Bob"), mention("Alice")),
            confidence = 0.7,
            decay = 0.2,
            importance = 0.8,
            reasoning = "stated directly",
            grounding = listOf("chunk-9"),
            source = "custom_source",
        )
        val suggested = SuggestedPropositions(chunkId = "chunk-9", propositions = listOf(input))
        val outcome = MentionGrounding.check(suggested)
        assertEquals("chunk-9", outcome.grounded.chunkId)
        assertEquals(input.copy(mentions = listOf(mention("Bob"))), outcome.grounded.propositions[0])
    }

    @Test
    fun `I4 ungrounded lists every rejected mention in encounter order`() {
        val zed = mention("Zed")
        val blank = mention("  ")
        val yan = mention("Yan")
        val suggested = SuggestedPropositions(
            chunkId = "chunk-1",
            propositions = listOf(
                proposition("Bob likes tea", mention("Bob"), zed, blank),
                proposition("Carol paints", yan, mention("Carol")),
            ),
        )
        val ungrounded = MentionGrounding.check(suggested).ungrounded
        assertEquals(
            listOf(
                UngroundedMention("Bob likes tea", zed, Reason.SPAN_NOT_IN_PROPOSITION),
                UngroundedMention("Bob likes tea", blank, Reason.BLANK_SPAN),
                UngroundedMention("Carol paints", yan, Reason.SPAN_NOT_IN_PROPOSITION),
            ),
            ungrounded,
        )
        assertSame(zed, ungrounded[0].mention)
        assertSame(blank, ungrounded[1].mention)
        assertSame(yan, ungrounded[2].mention)
    }

    @Test
    fun `I5 nothing ungrounded returns the very same instance`() {
        val suggested = SuggestedPropositions(
            chunkId = "chunk-1",
            propositions = listOf(
                proposition("Bob likes tea", mention("Bob"), mention("tea")),
                proposition("Alice likes jazz", mention("Alice")),
            ),
        )
        val outcome = MentionGrounding.check(suggested)
        assertSame(suggested, outcome.grounded)
        assertTrue(outcome.ungrounded.isEmpty())
    }
}

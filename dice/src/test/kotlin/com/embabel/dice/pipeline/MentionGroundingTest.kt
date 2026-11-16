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

    /**
     * Asserts that a span is not in a text.
     *
     * @param span the raw span
     * @param text the raw proposition text
     */
    private fun assertNotGrounded(span: String, text: String) =
        assertEquals(Reason.SPAN_NOT_IN_PROPOSITION, reason(span, text), "expected <$span> not to be grounded in <$text>")

    /**
     * Builds a string from code points, so the file stays plain ASCII.
     *
     * @param cp the code points
     * @return the string they spell
     */
    private fun s(vararg cp: Int) = String(cp, 0, cp.size)

    /** Every apostrophe look-alike that folds to a straight apostrophe. */
    private val apostrophes: List<Int> = listOf(
        0x2018, 0x2019, 0x201A, 0x201B, 0x2032, 0x2035, 0x02B9, 0x02BB, 0x02BC, 0x00B4, 0x0060,
    )

    /** Every double-quote look-alike that folds to a straight double quote. */
    private val doubleQuotes: List<Int> = listOf(0x201C, 0x201D, 0x201E, 0x201F, 0x2033, 0x2036, 0x02BA)

    /** Every dash that folds to a plain hyphen. */
    private val dashes: List<Int> = listOf(
        0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2015, 0x2212, 0x05BE, 0x2E3A, 0x301C, 0x30A0, 0xFE58, 0xFE63, 0xFF0D,
    )

    /** Every invisible character that is dropped before comparing. */
    private val invisibles: List<Int> = listOf(
        0x00AD, 0x200B, 0x200C, 0x200D, 0x200E, 0x200F, 0x2060, 0xFEFF, 0x061C, 0x202A, 0x202E, 0x2066, 0x2069,
        0xE0001, 0x034F, 0x0640, 0x115F, 0x1160, 0x3164, 0xFFA0, 0x180B, 0x180D, 0x180F, 0xFE00, 0xFE0F, 0xE0100,
        0xE01EF,
    )

    /**
     * Asserts that a look-alike and its plain form match each other in both directions.
     *
     * @param variant the look-alike code point
     * @param plain the plain character it folds to
     */
    private fun assertMatchesPlain(variant: Int, plain: Char) {
        val code = "U+%04X".format(variant)
        assertNull(reason("Ab" + plain + "Cd", "See Ab" + s(variant) + "Cd now"), "$code should match its plain form")
        assertNull(reason("Ab" + s(variant) + "Cd", "See Ab" + plain + "Cd now"), "plain form should match $code")
    }

    /**
     * Asserts that a code point is ignored inside a span and inside a text.
     *
     * @param c the code point
     */
    private fun assertIgnored(c: Int) {
        val code = "U+%04X".format(c)
        assertNull(reason("AbCd", "See Ab" + s(c) + "Cd now"), "$code inside the text should be ignored")
        assertNull(reason("Ab" + s(c) + "Cd", "See AbCd now"), "$code inside the span should be ignored")
    }

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
    fun `E8 a span inside a longer word is not grounded`() {
        assertNotGrounded("Ann", "Annabel sings")
        assertNotGrounded("US", "The focus is here")
        assertNotGrounded("Al", "He also left")
        assertNotGrounded("5", "There are 15 items")
        assertNotGrounded("GPT", "GPT4 is fast")
        val ivan = s(0x418, 0x432, 0x430, 0x43D)
        assertNotGrounded(ivan, ivan + s(0x43E, 0x432) + " x")
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
    fun `E15 an acute-accent apostrophe matches a straight one both ways`() {
        assertGrounded("O'Brien", "O\u00b4Brien runs the lab")
        assertGrounded("O\u00b4Brien", "O'Brien runs the lab")
    }

    @Test
    fun `E16 an invisible character between a letter and its accent is ignored`() {
        assertGrounded("Jos\u00e9", "Jose\u200b\u0301 lives in Lima")
    }

    @Test
    fun `E17 every listed quote and dash matches its plain form in both directions`() {
        apostrophes.forEach { assertMatchesPlain(it, '\'') }
        doubleQuotes.forEach { assertMatchesPlain(it, '"') }
        dashes.forEach { assertMatchesPlain(it, '-') }
    }

    @Test
    fun `E18 every listed invisible character is ignored inside a span or a text`() {
        invisibles.forEach { assertIgnored(it) }
    }

    @Test
    fun `E19 every dash punctuation code point matches a hyphen and every format code point is ignored`() {
        val codePoints = (0..0x10FFFF).filter { it !in 0xD800..0xDFFF }
        val dashWalk = codePoints.filter { Character.getType(it) == Character.DASH_PUNCTUATION.toInt() }
        val formatWalk = codePoints.filter { Character.getType(it) == Character.FORMAT.toInt() }
        dashWalk.forEach { assertMatchesPlain(it, '-') }
        formatWalk.forEach { assertIgnored(it) }
        assertTrue(dashWalk.isNotEmpty(), "the dash walk visited no code point")
        assertTrue(formatWalk.isNotEmpty(), "the format walk visited no code point")
    }

    @Test
    fun `E20 a span at word edges is grounded`() {
        assertGrounded("Brien", "O'Brien runs the lab")
        assertGrounded("Ann", "Ann's book")
        assertGrounded("Coca", "Coca-Cola raised prices")
        assertGrounded("Al", "He also met Al")
        assertGrounded("Bob likes tea", "Bob likes tea")
    }

    @Test
    fun `E21 scripts without spaces between words or with attached particles need no word edge`() {
        val toyota = s(0x30C8, 0x30E8, 0x30BF)
        assertGrounded(toyota, toyota + s(0x81EA, 0x52D5, 0x8ECA, 0x306F))
        val krungThep = s(0x0E01, 0x0E23, 0x0E38, 0x0E07, 0x0E40, 0x0E17, 0x0E1E)
        assertGrounded(krungThep, s(0x0E09, 0x0E31, 0x0E19, 0x0E01, 0x0E34, 0x0E19, 0x0E02, 0x0E49, 0x0E32, 0x0E27, 0x0E17, 0x0E35, 0x0E48) + krungThep)
        assertGrounded(s(0x5317, 0x4EAC), s(0x6211, 0x559C, 0x6B22, 0x5317, 0x4EAC, 0x7684, 0x79CB, 0x5929))
        val samsung = s(0xC0BC, 0xC131)
        assertGrounded(samsung, samsung + s(0xC740) + " x")
        val david = s(0x5D3, 0x5D5, 0x5D3)
        assertGrounded(david, s(0x5D5) + david + " x")
        val muhammad = s(0x645, 0x62D, 0x645, 0x62F)
        assertGrounded(muhammad, s(0x648) + muhammad + " x")
        assertGrounded(muhammad, s(0x645, 0x62D, 0x640, 0x645, 0x62F) + " x")
        assertGrounded("Sony", "Sony" + s(0x306E, 0x88FD, 0x54C1))
        assertGrounded(s(0x20000), "a" + s(0x20000, 0x20001))
        val disney = s(0x30C7, 0x30A3, 0x30BA, 0x30CB, 0x30FC)
        assertGrounded(disney, disney + "100" + s(0x5468, 0x5E74))
        val user = s(0x30E6, 0x30FC, 0x30B6, 0x30FC)
        assertGrounded(user, user + "ID" + s(0x3092, 0x5165, 0x529B))
        val sony = s(0x30BD, 0x30CB, 0x30FC)
        assertGrounded(sony, sony + "2024" + s(0x5E74))
        val coffee = s(0x30B3, 0x30FC, 0x30D2, 0x30FC)
        assertGrounded(coffee, coffee + "2" + s(0x676F))
        val addisAbaba = s(0x12A0, 0x12F2, 0x1235, 0x20, 0x12A0, 0x1260, 0x1263)
        assertGrounded(addisAbaba, s(0x1260) + addisAbaba + " x")
        val chennai = s(0x0B9A, 0x0BC6, 0x0BA9, 0x0BCD, 0x0BA9, 0x0BC8)
        assertGrounded(chennai, chennai + s(0x0BAF, 0x0BBF, 0x0BB2, 0x0BCD) + " x")
        val dhaka = s(0x09A2, 0x09BE, 0x0995, 0x09BE)
        assertGrounded(dhaka, dhaka + s(0x09AF, 0x09BC) + " x")
    }

    @Test
    fun `E22 case is folded fully`() {
        assertGrounded("STRASSE", "Die Stra" + s(0xDF) + "e ist lang")
        assertGrounded("Stra" + s(0xDF) + "e", "DIE STRASSE IST LANG")
        assertGrounded("STRA" + s(0x1E9E) + "E", "die strasse ist lang")
        assertGrounded(s(0x39F, 0x394, 0x39F, 0x3A3), "h " + s(0x3BF, 0x3B4, 0x3BF, 0x3C3) + " x")
        assertGrounded(s(0x3BF, 0x3B4, 0x3BF, 0x3C2), "H " + s(0x39F, 0x394, 0x39F, 0x3A3) + " X")
    }

    @Test
    fun `E23 a symbol that composes to letters or digits does not join the word beside it`() {
        val t = "Acme" + s(0x2122) + " launched a phone"
        assertGrounded("Acme", t)
        assertGrounded("Acme", "Acme" + s(0x2120) + " offers support")
        assertGrounded("Einstein", "Einstein" + s(0xB9) + " said so")
        assertGrounded("Smith", "Smith" + s(0x2460) + " wrote it")
        assertGrounded("Acme" + s(0x2122), "Acme" + s(0x2122, 0xFE0F) + " rocks")
        assertGrounded("555", s(0x2121) + "555 1234")
        assertGrounded("AcmeTM", t)
        assertGrounded("Acme" + s(0x2122), "AcmeTM launched a phone")
        assertNotGrounded("Acm", t)
        assertNotGrounded("5", "15" + s(0x2122) + " x")
    }

    @Test
    fun `E24 a letter with a combining mark that has no composed form is a different letter`() {
        assertNotGrounded("x", "a x" + s(0x302) + " b")
    }

    @Test
    fun `E25 prepared texts and spans give the same answers as raw strings`() {
        val cases = listOf("Bob" to "Bob likes tea", "US" to "The focus is here", "  " to "Bob likes tea")
        val expected = listOf(null, Reason.SPAN_NOT_IN_PROPOSITION, Reason.BLANK_SPAN)
        cases.zip(expected).forEach { (case, answer) ->
            val (span, text) = case
            assertEquals(answer, reason(span, text))
            assertEquals(answer, MentionGrounding.reasonUngrounded(span, MentionGrounding.prepare(text)))
            assertEquals(
                answer,
                MentionGrounding.reasonUngrounded(MentionGrounding.prepare(span), MentionGrounding.prepare(text)),
            )
        }
        assertTrue(MentionGrounding.isBlank(s(0x200B, 0x200D)))
        assertTrue(MentionGrounding.sameName(MentionGrounding.prepare("Stra" + s(0xDF) + "e"), MentionGrounding.prepare("STRASSE")))
        assertFalse(MentionGrounding.sameName(MentionGrounding.prepare(""), MentionGrounding.prepare("")))
    }

    @Test
    fun `E26 an inflected or plural form is a different word`() {
        assertNotGrounded("Anna", "Annas Hund ist alt")
        assertNotGrounded("Apple", "Apples are red")
        assertNotGrounded("Nokia", "Nokian osake nousi")
        assertNotGrounded("Budapest", "Peter Budapesten el")
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

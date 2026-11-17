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
package com.embabel.dice.common.filter

import com.embabel.agent.rag.model.NamedEntity
import com.embabel.dice.common.KnownEntity
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.filter.MentionFixtures.context
import com.embabel.dice.common.filter.MentionFixtures.knownPerson
import com.embabel.dice.common.filter.MentionFixtures.mention
import com.embabel.dice.common.filter.MentionFixtures.proposition
import com.embabel.dice.common.validation.ValidationResult
import com.embabel.dice.common.validation.ValidationResult.Valid
import com.embabel.dice.pipeline.MentionGrounding
import com.embabel.dice.pipeline.PreparedText
import com.embabel.dice.proposition.SuggestedMention
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class GroundedMentionFilterTest {

    private val filter = GroundedMentionFilter()

    private val thomas = knownPerson("user-1", "Thomas", "Tom")

    private val notInText = ValidationResult.Invalid("SPAN_NOT_IN_PROPOSITION")

    private val blank = ValidationResult.Invalid("BLANK_SPAN")

    private fun judge(context: SourceAnalysisContext, text: String, vararg mentions: SuggestedMention): List<ValidationResult> =
        filter.validateMentions(proposition(text, *mentions), context)

    @Test
    fun `E1 isValid accepts a span in the text at word edges and nothing else`() {
        assertTrue(filter.isValid(mention("Alice"), "Alice met Bob"))
        assertFalse(filter.isValid(mention("Carol"), "Alice met Bob"))
        assertFalse(filter.isValid(mention("  "), "Alice met Bob"))
        assertFalse(filter.isValid(mention("Al"), "He also left"))
    }

    @Test
    fun `E2 rejectionReason reports only a blank span`() {
        assertEquals("BLANK_SPAN", filter.rejectionReason(mention("  ")))
        assertNull(filter.rejectionReason(mention("Carol")))
        assertNull(filter.rejectionReason(mention("Alice")))
    }

    @Test
    fun `E3 validateMentions without known entities gives the span verdicts`() {
        assertEquals(
            listOf(Valid, notInText, blank),
            judge(context(), "Alice met Bob", mention("Alice"), mention("Carol"), mention("  ")),
        )
    }

    @Test
    fun `E4 an id alone does not ground a mention, even of a known entity the text names`() {
        assertEquals(listOf(notInText), judge(context(thomas), "Tom moved to Paris", mention("Thomas Smith", "user-1")))
    }

    @Test
    fun `E5 a mention with the name or alias of a known entity the text names is grounded`() {
        assertEquals(listOf(Valid), judge(context(thomas), "Tom moved to Paris", mention("Thomas")))
        assertEquals(listOf(Valid), judge(context(thomas), "Thomas moved to Paris", mention("Tom")))
    }

    @Test
    fun `E6 a known entity the text does not name grounds nothing`() {
        assertEquals(listOf(notInText), judge(context(thomas), "Bob likes tea", mention("Thomas", "user-1")))
    }

    @Test
    fun `E7 the text names a known entity only at word edges`() {
        assertEquals(
            listOf(notInText),
            judge(context(knownPerson("p-al", "Al")), "He also left", mention("Albert", "p-al")),
        )
    }

    @Test
    fun `E8 an id that matches no known entity grounds nothing`() {
        assertEquals(
            listOf(notInText),
            judge(context(thomas), "Tom moved to Paris", mention("Thomas Smith", "someone-else")),
        )
    }

    @Test
    fun `E9 a blank span stays blank with the id of a named known entity`() {
        assertEquals(listOf(blank), judge(context(thomas), "Tom moved to Paris", mention("  ", "user-1")))
    }

    @Test
    fun `E10 names are compared ignoring case`() {
        assertEquals(listOf(Valid), judge(context(thomas), "Tom moved to Paris", mention("THOMAS")))
    }

    @Test
    fun `E11 blank and null values ground nothing and do not throw`() {
        val blankId = context(knownPerson("", "Thomas", "Tom"))
        assertEquals(listOf(notInText), judge(blankId, "Tom moved to Paris", mention("Zed", "")))
        assertEquals(listOf(notInText), judge(blankId, "Tom moved to Paris", mention("Zed", "  ")))

        val nulls = context(KnownEntity(Mockito.mock(NamedEntity::class.java), "A known person", listOf("Tom", "Tommy", "  ")))
        assertEquals(listOf(Valid), judge(nulls, "Tom moved to Paris", mention("Tommy")))
        assertEquals(listOf(notInText), judge(nulls, "Tom moved to Paris", mention("Zed")))

        val blankAliases = context(knownPerson("p-x", "Xavier", "", "  "))
        assertEquals(listOf(notInText), judge(blankAliases, "Bob likes tea", mention("Zed", "p-x")))
    }

    @Test
    fun `E12 the text is prepared once and known entity names only when needed`() {
        val prepared = mutableListOf<String>()
        val recording: (String) -> PreparedText = { prepared.add(it); MentionGrounding.prepare(it) }
        val known = listOf(thomas)

        val zeds = proposition("Tom moved to Paris", *Array(40) { mention("Zed$it") })
        KnownEntityGrounding.reasons(zeds, lazy { KnownEntityGrounding.knownNames(known, recording) }, recording)
        assertEquals(1, prepared.count { it == "Tom moved to Paris" })
        assertTrue(prepared.count { it == "Thomas" } <= 2)
        assertTrue(prepared.count { it == "Tom" } <= 2)

        prepared.clear()
        KnownEntityGrounding.reasons(proposition("Tom moved to Paris"), lazy { KnownEntityGrounding.knownNames(known, recording) }, recording)
        assertEquals(emptyList<String>(), prepared)

        prepared.clear()
        KnownEntityGrounding.reasons(proposition("Tom moved to Paris", mention("Tom"), mention("Paris")), lazy { KnownEntityGrounding.knownNames(known, recording) }, recording)
        assertFalse("Thomas" in prepared)
    }

    @Test
    fun `E13 one result per mention in order with duplicates`() {
        assertEquals(
            listOf(Valid, notInText, Valid),
            judge(context(), "Alice met Bob", mention("Alice"), mention("Carol"), mention("Alice")),
        )
    }

    @Test
    fun `E14 the id and the name must belong to the entity the text names`() {
        val known = context(thomas, knownPerson("user-2", "Alice"))
        assertEquals(listOf(notInText), judge(known, "Alice moved to Paris", mention("Thomas Smith", "user-1")))
        assertEquals(listOf(notInText), judge(known, "Alice moved to Paris", mention("Thomas")))
        assertEquals(listOf(notInText), judge(known, "Alice moved to Paris", mention("Ally", "user-2")))
    }

    @Test
    fun `E15 a concept the text only implies stays ungrounded`() {
        assertEquals(
            listOf(notInText),
            judge(context(knownPerson("h-1", "Cycling")), "Alice cycles", mention("Cycling", "h-1")),
        )
    }

    @Test
    fun `E16 names are compared the way the resolver compares them`() {
        assertEquals(
            listOf(Valid, Valid),
            judge(context(thomas), "Tom moved to Paris", mention("Dr. Thomas"), mention("Thomas Jr.")),
        )
    }

    @Test
    fun `E17 a name shared with a known entity the text does not name grounds nothing`() {
        val known = context(knownPerson("p-bob", "Bob", "Pat"), knownPerson("p-alice", "Alice", "Pat"))
        assertEquals(listOf(notInText), judge(known, "Alice waved", mention("Pat")))
        assertEquals(listOf(notInText), judge(known, "Alice waved", mention("Pat", "p-alice")))
        assertEquals(listOf(Valid), judge(known, "Alice waved to Bob", mention("Pat")))
        assertEquals(listOf(notInText), judge(known, "Alice waved", mention("Ally", "p-alice")))
    }

    @Test
    fun `E18 a span grounds through a known entity only when entity resolution would match the name`() {
        val known = context(knownPerson("p-heinz", "Heinz", "Stra\u00DFe"))
        assertEquals(listOf(notInText), judge(known, "Heinz moved to Paris", mention("STRASSE")))
        assertEquals(listOf(Valid), judge(known, "Heinz moved to Paris", mention("STRA\u00DFE")))
    }

    @Test
    fun `L6 known entity names are prepared once for all the propositions that share them`() {
        val prepared = mutableListOf<String>()
        val recording: (String) -> PreparedText = { prepared.add(it); MentionGrounding.prepare(it) }
        val names = lazy { KnownEntityGrounding.knownNames(listOf(thomas), recording) }

        repeat(3) { KnownEntityGrounding.reasons(proposition("Tom moved to Paris", mention("Zed")), names, recording) }

        assertEquals(1, prepared.count { it == "Thomas" })
        assertEquals(1, prepared.count { it == "Tom" })
    }

    @Test
    fun `L6 the filter gives the same verdicts when it reuses prepared names`() {
        val filter = GroundedMentionFilter()
        val known = context(thomas)
        val first = filter.validateMentions(proposition("Tom moved to Paris", mention("Thomas")), known)
        val second = filter.validateMentions(proposition("Tom moved to Paris", mention("Thomas")), known)
        val other = filter.validateMentions(proposition("Tom moved to Paris", mention("Thomas")), context())
        assertEquals(listOf(Valid), first)
        assertEquals(first, second)
        assertEquals(listOf(notInText), other)
    }

    @Test
    fun `L5 only known entities that share the missed mention's name are looked for in the text`() {
        val untouchable = object : AbstractList<PreparedText>() {
            override val size: Int get() = throw AssertionError("looked for Bob in the text")
            override fun get(index: Int): PreparedText = throw AssertionError("looked for Bob in the text")
        }
        val names = lazy {
            KnownEntityGrounding.knownNames(listOf(thomas)) + KnownEntityGrounding.KnownNames(listOf("Bob"), untouchable)
        }

        val reasons = KnownEntityGrounding.reasons(proposition("Tom moved to Paris", mention("Thomas"), mention("Zed")), names)

        assertEquals(listOf(null, com.embabel.dice.pipeline.UngroundedMention.Reason.SPAN_NOT_IN_PROPOSITION), reasons)
    }

    @Test
    fun `S3 the filter sees known entities added to the same list in place`() {
        val known = mutableListOf(knownPerson("p-bob", "Bob"))
        val analysis = SourceAnalysisContext(
            schema = com.embabel.agent.core.DataDictionary.fromClasses("test"),
            entityResolver = com.embabel.dice.common.resolver.AlwaysCreateEntityResolver,
            contextId = com.embabel.agent.core.ContextId("mention-test"),
            knownEntities = known,
        )
        val filter = GroundedMentionFilter()
        val tomMoved = proposition("Tom moved to Paris", mention("Thomas"))

        assertEquals(listOf(notInText), filter.validateMentions(tomMoved, analysis))
        known.add(thomas)
        assertEquals(listOf(Valid), filter.validateMentions(tomMoved, analysis))
    }
}

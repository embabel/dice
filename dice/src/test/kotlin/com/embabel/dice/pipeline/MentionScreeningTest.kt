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

import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.filter.GroundedMentionFilter
import com.embabel.dice.common.filter.MentionFilter
import com.embabel.dice.common.filter.MentionFixtures.context
import com.embabel.dice.common.filter.MentionFixtures.knownPerson
import com.embabel.dice.common.filter.MentionFixtures.mention
import com.embabel.dice.common.filter.MentionFixtures.proposition
import com.embabel.dice.common.validation.ValidationResult
import com.embabel.dice.pipeline.RejectedMention.Check
import com.embabel.dice.pipeline.RejectedMentionHandling.DROP
import com.embabel.dice.pipeline.RejectedMentionHandling.KEEP
import com.embabel.dice.pipeline.RejectedMentionHandling.LEAVE_UNRESOLVED
import com.embabel.dice.pipeline.UngroundedMention.Reason
import com.embabel.dice.proposition.SuggestedMention
import com.embabel.dice.proposition.SuggestedProposition
import com.embabel.dice.proposition.SuggestedPropositions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MentionScreeningTest {

    /** A filter that rejects the mentions at the given positions of each proposition it is given. */
    private class PositionFilter(vararg val positions: Int) : MentionFilter {
        override fun isValid(mention: SuggestedMention, propositionText: String): Boolean = true

        override fun validateMentions(proposition: SuggestedProposition, context: SourceAnalysisContext): List<ValidationResult> =
            proposition.mentions.indices.map { if (it in positions) ValidationResult.Invalid("no") else ValidationResult.Valid }
    }

    /** A filter that rejects every mention a predicate picks, with the given reason. */
    private class PickingFilter(val reason: String = "no", val picks: (SuggestedMention) -> Boolean) : MentionFilter {
        override fun isValid(mention: SuggestedMention, propositionText: String): Boolean = true

        override fun validateMentions(proposition: SuggestedProposition, context: SourceAnalysisContext): List<ValidationResult> =
            proposition.mentions.map { if (picks(it)) ValidationResult.Invalid(reason) else ValidationResult.Valid }
    }

    /** A filter that records every proposition it is asked about and answers like another filter. */
    private class RecordingFilter(val answers: MentionFilter = PositionFilter()) : MentionFilter {
        val seen = mutableListOf<SuggestedProposition>()

        override fun isValid(mention: SuggestedMention, propositionText: String): Boolean = true

        override fun validateMentions(proposition: SuggestedProposition, context: SourceAnalysisContext): List<ValidationResult> {
            seen.add(proposition)
            return answers.validateMentions(proposition, context)
        }
    }

    /** A filter that fails the test when it is asked about anything. */
    private class ThrowingFilter : MentionFilter {
        override fun isValid(mention: SuggestedMention, propositionText: String): Boolean = true

        override fun validateMentions(proposition: SuggestedProposition, context: SourceAnalysisContext): List<ValidationResult> =
            throw AssertionError("asked about <${proposition.text}>")
    }

    /** A filter that answers with one result too few. */
    private class ShortFilter : MentionFilter {
        override fun isValid(mention: SuggestedMention, propositionText: String): Boolean = true

        override fun validateMentions(proposition: SuggestedProposition, context: SourceAnalysisContext): List<ValidationResult> =
            proposition.mentions.drop(1).map { ValidationResult.Valid }
    }

    /**
     * Screens with the given checks and the context of no known entity.
     *
     * @param suggested the propositions to screen
     * @param grounding the grounding check
     * @param groundingHandling the handling of grounding rejections
     * @param filter the filter, or null
     * @param filterHandling the handling of filter rejections
     * @return the outcome
     */
    private fun screen(
        suggested: SuggestedPropositions,
        grounding: MentionFilter = GroundedMentionFilter(),
        groundingHandling: RejectedMentionHandling = DROP,
        filter: MentionFilter? = null,
        filterHandling: RejectedMentionHandling = LEAVE_UNRESOLVED,
        analysisContext: SourceAnalysisContext = context(),
    ): ScreeningOutcome =
        MentionScreening.screen(suggested, analysisContext, grounding, groundingHandling, filter, filterHandling)

    /**
     * Builds one chunk's propositions.
     *
     * @param propositions the propositions
     * @return them under the chunk id "chunk-1"
     */
    private fun chunk(vararg propositions: SuggestedProposition) = SuggestedPropositions("chunk-1", propositions.toList())

    @Test
    fun `E1 grounding KEEP with no filter returns the extracted instance and reports the mention`() {
        val extracted = chunk(proposition("Bob likes tea", mention("Alice")))
        val outcome = screen(extracted, groundingHandling = KEEP)
        assertSame(extracted, outcome.result)
        assertSame(extracted, outcome.candidates)
        assertEquals(1, outcome.rejected.size)
        assertEquals(KEEP, outcome.rejected[0].handling)
    }

    @Test
    fun `E2 grounding DROP takes the mention off and keeps its proposition`() {
        val bob = mention("Bob")
        val outcome = screen(chunk(proposition("Bob likes tea", bob, mention("Alice"))))
        assertEquals(listOf("Bob likes tea"), outcome.result.propositions.map { it.text })
        assertEquals(listOf(bob), outcome.result.propositions[0].mentions)
        assertEquals(listOf(bob), outcome.candidates.propositions[0].mentions)
    }

    @Test
    fun `E3 a filter rejection under LEAVE_UNRESOLVED stays on the proposition and is no candidate`() {
        val bob = mention("Bob")
        val outcome = screen(chunk(proposition("Bob likes tea", bob)), filter = PositionFilter(0), filterHandling = LEAVE_UNRESOLVED)
        assertEquals(listOf(bob), outcome.result.propositions[0].mentions)
        assertEquals(emptyList<SuggestedMention>(), outcome.candidates.propositions[0].mentions)
    }

    @Test
    fun `E4 a filter rejection under DROP is in neither list and under KEEP is in both and reported`() {
        val bob = mention("Bob")
        val extracted = chunk(proposition("Bob likes tea", bob))
        val dropped = screen(extracted, filter = PositionFilter(0), filterHandling = DROP)
        assertEquals(emptyList<SuggestedMention>(), dropped.result.propositions[0].mentions)
        assertEquals(emptyList<SuggestedMention>(), dropped.candidates.propositions[0].mentions)
        val kept = screen(extracted, filter = PositionFilter(0), filterHandling = KEEP)
        assertEquals(listOf(bob), kept.result.propositions[0].mentions)
        assertEquals(listOf(bob), kept.candidates.propositions[0].mentions)
        assertEquals(listOf(Check.FILTER to KEEP), kept.rejected.map { it.check to it.handling })
        assertSame(bob, kept.rejected[0].mention)
    }

    @Test
    fun `E5 rejected lists grounding rejections then filter rejections in encounter order`() {
        val zed = mention("Zed")
        val bob = mention("Bob")
        val yan = mention("Yan")
        val xi = mention("Xi")
        val carol = mention("Carol")
        val extracted = chunk(proposition("Bob likes tea", zed, bob, yan), proposition("Carol paints", xi, carol))
        val outcome = screen(extracted, groundingHandling = KEEP, filter = PositionFilter(1), filterHandling = DROP)
        val notIn = Reason.SPAN_NOT_IN_PROPOSITION.name
        assertEquals(
            listOf(
                RejectedMention("chunk-1", 0, "Bob likes tea", zed, notIn, Check.GROUNDING, KEEP),
                RejectedMention("chunk-1", 0, "Bob likes tea", yan, notIn, Check.GROUNDING, KEEP),
                RejectedMention("chunk-1", 1, "Carol paints", xi, notIn, Check.GROUNDING, KEEP),
                RejectedMention("chunk-1", 0, "Bob likes tea", bob, "no", Check.FILTER, DROP),
                RejectedMention("chunk-1", 1, "Carol paints", carol, "no", Check.FILTER, DROP),
            ),
            outcome.rejected,
        )
        listOf(zed, yan, xi, bob, carol).zip(outcome.rejected).forEach { (expected, entry) -> assertSame(expected, entry.mention) }
    }

    @Test
    fun `E6 a filter removes one of two equal mentions by position`() {
        val first = mention("Bob")
        val second = mention("Bob")
        val outcome = screen(chunk(proposition("Bob met Bob", first, second)), filter = PositionFilter(1), filterHandling = DROP)
        val kept = outcome.result.propositions[0].mentions
        assertEquals(1, kept.size)
        assertSame(first, kept[0])
    }

    @Test
    fun `E7 grounding KEEP and a filter that rejects the same mention report it once per check`() {
        val alice = mention("Alice")
        val outcome = screen(chunk(proposition("Bob likes tea", alice)), groundingHandling = KEEP, filter = PositionFilter(0))
        assertEquals(listOf(Check.GROUNDING, Check.FILTER), outcome.rejected.map { it.check })
        outcome.rejected.forEach { assertSame(alice, it.mention) }
    }

    @Test
    fun `E8 the filter sees the proposition without the mentions grounding dropped`() {
        val bob = mention("Bob")
        val tea = mention("tea")
        val recorder = RecordingFilter()
        screen(chunk(proposition("Bob likes tea", mention("Alice"), bob, tea)), filter = recorder)
        assertEquals(1, recorder.seen.size)
        assertEquals(2, recorder.seen[0].mentions.size)
        assertSame(bob, recorder.seen[0].mentions[0])
        assertSame(tea, recorder.seen[0].mentions[1])
    }

    @Test
    fun `E9 each check is asked once per proposition that has mentions`() {
        val grounding = RecordingFilter(GroundedMentionFilter())
        val filter = RecordingFilter()
        val extracted = chunk(
            proposition("Bob likes tea", mention("Bob")),
            proposition("Carol paints", mention("Carol")),
            proposition("It rains"),
            proposition("Dan runs", mention("Dan")),
        )
        screen(extracted, grounding = grounding, filter = filter)
        assertEquals(3, grounding.seen.size)
        assertEquals(3, filter.seen.size)
    }

    @Test
    fun `E10 a check that returns too few results throws with its class name`() {
        val extracted = chunk(proposition("Bob likes tea", mention("Bob"), mention("tea")))
        val fromGrounding = assertThrows(IllegalStateException::class.java) { screen(extracted, grounding = ShortFilter()) }
        assertTrue(fromGrounding.message!!.contains(ShortFilter::class.java.name), fromGrounding.message)
        val fromFilter = assertThrows(IllegalStateException::class.java) { screen(extracted, filter = ShortFilter()) }
        assertTrue(fromFilter.message!!.contains(ShortFilter::class.java.name), fromFilter.message)
    }

    @Test
    fun `E11 a dropped mention passes its id to the candidates with its key`() {
        val extracted = chunk(
            proposition("Bob likes tea", mention("Alice", "p1")),
            proposition("Alice likes jazz", mention("Alice")),
        )
        val outcome = screen(extracted, analysisContext = context(knownPerson("p1", "Alice Smith")))
        assertEquals("p1", outcome.candidates.propositions[1].mentions[0].suggestedId)
        assertNull(outcome.result.propositions[1].mentions[0].suggestedId)
    }

    @Test
    fun `E12 no hint when a candidate with the key has an id and none from a filter rejection`() {
        val held = screen(
            chunk(
                proposition("Bob likes tea", mention("Alice", "p1")),
                proposition("Alice likes jazz", mention("Alice", "p2"), mention("Alice")),
            ),
        )
        assertEquals(listOf("p2", null), held.candidates.propositions[1].mentions.map { it.suggestedId })
        val filtered = screen(
            chunk(
                proposition("Alice likes tea", mention("Alice", "p1")),
                proposition("Alice likes jazz", mention("Alice")),
            ),
            filter = PickingFilter { it.suggestedId == "p1" },
            filterHandling = DROP,
        )
        assertNull(filtered.candidates.propositions[1].mentions[0].suggestedId)
    }

    @Test
    fun `E13 the event reason of another grounding check is a placement reason`() {
        val zed = mention("Zed")
        val blank = mention("  ")
        val outcome = screen(chunk(proposition("Bob likes tea", zed, blank)), grounding = PickingFilter("custom") { true })
        assertEquals(listOf(Reason.SPAN_NOT_IN_PROPOSITION, Reason.BLANK_SPAN), outcome.ungrounded.map { it.reason })
        assertEquals(listOf("custom", "custom"), outcome.rejected.map { it.reason })
        val shipped = screen(chunk(proposition("Bob likes tea", zed, blank)))
        assertEquals(
            listOf(Reason.SPAN_NOT_IN_PROPOSITION, Reason.BLANK_SPAN),
            shipped.ungrounded.map { it.reason },
        )
        assertEquals(shipped.rejected.map { it.reason }, shipped.ungrounded.map { it.reason.name })
    }

    @Test
    fun `E14 a mention naming a known entity the text names survives grounding DROP`() {
        val thomas = mention("Thomas")
        val extracted = chunk(proposition("Tom moved to Paris", thomas))
        val outcome = screen(extracted, analysisContext = context(knownPerson("user-1", "Thomas", "Tom")))
        assertSame(extracted, outcome.result)
        assertTrue(outcome.rejected.isEmpty())
        assertEquals(listOf(thomas), outcome.candidates.propositions[0].mentions)
    }

    @Test
    fun `E15 the filter's positions refer to the list grounding left`() {
        val alice = mention("Alice")
        val bob = mention("Bob")
        val carol = mention("Carol")
        val outcome = screen(chunk(proposition("Bob met Carol", alice, bob, carol)), filter = PositionFilter(0), filterHandling = DROP)
        assertEquals(listOf(carol), outcome.result.propositions[0].mentions)
        assertSame(carol, outcome.result.propositions[0].mentions[0])
        assertEquals(listOf(Check.GROUNDING, Check.FILTER), outcome.rejected.map { it.check })
        assertSame(alice, outcome.rejected[0].mention)
        assertSame(bob, outcome.rejected[1].mention)
    }

    @Test
    fun `E16 a blank id supplies no hint and a blank candidate id counts as none`() {
        val blankDropped = screen(
            chunk(proposition("Bob likes tea", mention("Alice", "")), proposition("Alice likes jazz", mention("Alice"))),
        )
        assertNull(blankDropped.candidates.propositions[1].mentions[0].suggestedId)
        val blankCandidate = screen(
            chunk(proposition("Bob likes tea", mention("Alice", "p1")), proposition("Alice likes jazz", mention("Alice", ""))),
            analysisContext = context(knownPerson("p1", "Alice Smith")),
        )
        assertEquals("p1", blankCandidate.candidates.propositions[1].mentions[0].suggestedId)
    }

    @Test
    fun `E17 each entry holds the chunk id and the proposition index`() {
        val extracted = SuggestedPropositions(
            "chunk-7",
            listOf(proposition("Bob likes tea", mention("Bob")), proposition("Carol paints", mention("Zed"), mention("Carol"))),
        )
        val outcome = screen(extracted, groundingHandling = KEEP, filter = PickingFilter { it.span == "Carol" })
        assertEquals(
            listOf(Triple(Check.GROUNDING, "chunk-7", 1), Triple(Check.FILTER, "chunk-7", 1)),
            outcome.rejected.map { Triple(it.check, it.chunkId, it.propositionIndex) },
        )
    }

    @Test
    fun `E18 a proposition with no mentions goes to no check and an emptied one skips the filter`() {
        val empty = chunk(proposition("It rains"))
        val outcome = screen(empty, grounding = ThrowingFilter(), filter = ThrowingFilter())
        assertSame(empty, outcome.result)
        val emptied = screen(chunk(proposition("Bob likes tea", mention("Alice"))), filter = ThrowingFilter())
        assertEquals(emptyList<SuggestedMention>(), emptied.result.propositions[0].mentions)
    }

    /**
     * Screens the way the pipeline does in DROP mode with no filter.
     *
     * @param suggested the propositions to screen
     * @return the outcome
     */
    private fun screenDropping(suggested: SuggestedPropositions): ScreeningOutcome =
        MentionScreening.screen(suggested, context(), GroundedMentionFilter(), DROP, null, LEAVE_UNRESOLVED)

    @Test
    fun `ME9 only the mention's own proposition counts`() {
        val alice = mention("Alice")
        val suggested = SuggestedPropositions(
            chunkId = "chunk-1",
            propositions = listOf(
                proposition("Bob likes tea", alice),
                proposition("Alice likes jazz"),
            ),
        )
        val outcome = screenDropping(suggested)
        assertEquals(listOf(UngroundedMention("Bob likes tea", alice, Reason.SPAN_NOT_IN_PROPOSITION)), outcome.ungrounded)
        assertTrue(outcome.result.propositions[0].mentions.isEmpty())
    }

    @Test
    fun `ME14 suggestedId plays no part`() {
        val alice = mention("Alice", "person-1")
        val missing = screenDropping(SuggestedPropositions("c", listOf(proposition("Bob likes tea", alice))))
        assertEquals(listOf(UngroundedMention("Bob likes tea", alice, Reason.SPAN_NOT_IN_PROPOSITION)), missing.ungrounded)
        val present = screenDropping(SuggestedPropositions("c", listOf(proposition("Alice likes tea", alice))))
        assertTrue(present.ungrounded.isEmpty())
        assertEquals(listOf(alice), present.result.propositions[0].mentions)
    }

    @Test
    fun `MI1 every proposition comes back in order even when it loses all its mentions`() {
        val suggested = SuggestedPropositions(
            chunkId = "chunk-1",
            propositions = listOf(
                proposition("Bob likes tea", mention("Bob")),
                proposition("Carol paints", mention("Zed"), mention("Yan")),
                proposition("Dan runs", mention("Dan")),
            ),
        )
        val outcome = screenDropping(suggested)
        assertEquals(listOf("Bob likes tea", "Carol paints", "Dan runs"), outcome.result.propositions.map { it.text })
        assertEquals(emptyList<SuggestedMention>(), outcome.result.propositions[1].mentions)
    }

    @Test
    fun `MI2 grounded mentions keep their order, duplicates and fields`() {
        val bob = SuggestedMention(span = "Bob", type = "Person", suggestedId = "person-bob", role = "SUBJECT")
        val tea = SuggestedMention(span = "tea", type = "Drink", suggestedId = null, role = "OBJECT")
        val bobAgain = SuggestedMention(span = "Bob", type = "Person", suggestedId = "person-bob", role = "SUBJECT")
        val suggested = SuggestedPropositions(
            chunkId = "chunk-1",
            propositions = listOf(proposition("Bob likes tea", bob, mention("Alice"), tea, bobAgain)),
        )
        val kept = screenDropping(suggested).result.propositions[0].mentions
        assertEquals(listOf(bob, tea, bobAgain), kept)
        assertEquals("person-bob", kept[0].suggestedId)
        assertEquals("SUBJECT", kept[0].role)
        assertEquals("Drink", kept[1].type)
        assertEquals("OBJECT", kept[1].role)
    }

    @Test
    fun `MI3 only mentions change and chunkId is kept`() {
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
        val outcome = screenDropping(suggested)
        assertEquals("chunk-9", outcome.result.chunkId)
        assertEquals(input.copy(mentions = listOf(mention("Bob"))), outcome.result.propositions[0])
    }

    @Test
    fun `MI4 ungrounded lists every rejected mention in encounter order`() {
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
        val ungrounded = screenDropping(suggested).ungrounded
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
    fun `MI5 nothing ungrounded returns the very same instance`() {
        val suggested = SuggestedPropositions(
            chunkId = "chunk-1",
            propositions = listOf(
                proposition("Bob likes tea", mention("Bob"), mention("tea")),
                proposition("Alice likes jazz", mention("Alice")),
            ),
        )
        val outcome = screenDropping(suggested)
        assertSame(suggested, outcome.result)
        assertTrue(outcome.ungrounded.isEmpty())
    }

    @Test
    fun `L2 a dropped mention passes on only the id of a known entity`() {
        val madeUp = screen(
            chunk(proposition("Bob likes tea", mention("Alice", "made-up")), proposition("Alice likes jazz", mention("Alice"))),
        )
        assertNull(madeUp.candidates.propositions[1].mentions[0].suggestedId)
        val known = screen(
            chunk(proposition("Bob likes tea", mention("Alice", "p-alice")), proposition("Alice likes jazz", mention("Alice"))),
            analysisContext = context(knownPerson("p-alice", "Alice Smith")),
        )
        assertEquals("p-alice", known.candidates.propositions[1].mentions[0].suggestedId)
    }

    @Test
    fun `L3 a mention the filter takes away from resolution does not block a hint`() {
        val outcome = screen(
            chunk(
                proposition("Alice likes tea", mention("Alice", "p-x")),
                proposition("Bob likes tea", mention("Alice", "p-alice")),
                proposition("Alice likes jazz", mention("Alice")),
            ),
            filter = PickingFilter { it.suggestedId == "p-x" },
            filterHandling = LEAVE_UNRESOLVED,
            analysisContext = context(knownPerson("p-alice", "Alice Smith")),
        )
        assertEquals("p-alice", outcome.candidates.propositions.last().mentions.single().suggestedId)
    }

    @Test
    fun `L4 a filter rejection reports the mention as the model wrote it`() {
        val plain = mention("Alice")
        val outcome = screen(
            chunk(proposition("Bob likes tea", mention("Alice", "p-alice")), proposition("Alice likes jazz", plain)),
            filter = PickingFilter { it.suggestedId == "p-alice" },
            filterHandling = LEAVE_UNRESOLVED,
            analysisContext = context(knownPerson("p-alice", "Alice Smith")),
        )
        assertTrue(outcome.rejected.none { it.check == Check.FILTER }, "rejected: ${outcome.rejected}")
        assertNull(outcome.candidates.propositions[1].mentions.single().suggestedId)
        assertSame(plain, outcome.result.propositions[1].mentions.single())
    }

    @Test
    fun `L11 the filter judges a passed-on id with the whole proposition in view`() {
        val noSharedIds = object : MentionFilter {
            override fun isValid(mention: SuggestedMention, propositionText: String): Boolean = true
            override fun validateMentions(proposition: SuggestedProposition, context: SourceAnalysisContext): List<ValidationResult> {
                val ids = proposition.mentions.mapNotNull { it.suggestedId }
                return proposition.mentions.map {
                    if (it.suggestedId != null && ids.count { id -> id == it.suggestedId } > 1) ValidationResult.Invalid("shared id")
                    else ValidationResult.Valid
                }
            }
        }
        val outcome = screen(
            chunk(
                proposition("Bob slept", mention("Alice", "k")),
                proposition("Alice met Bob", mention("Alice"), mention("Bob", "k")),
            ),
            filter = noSharedIds,
            analysisContext = context(knownPerson("k", "Alice Smith")),
        )
        assertEquals(listOf(null, "k"), outcome.candidates.propositions[1].mentions.map { it.suggestedId })
    }
}

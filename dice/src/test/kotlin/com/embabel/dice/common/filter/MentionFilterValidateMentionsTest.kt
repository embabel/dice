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

import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.filter.MentionFixtures.context
import com.embabel.dice.common.filter.MentionFixtures.knownPerson
import com.embabel.dice.common.filter.MentionFixtures.mention
import com.embabel.dice.common.filter.MentionFixtures.proposition
import com.embabel.dice.common.validation.ValidationResult
import com.embabel.dice.proposition.SuggestedMention
import com.embabel.dice.proposition.SuggestedProposition
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MentionFilterValidateMentionsTest {

    /** Rejects the listed spans with the given reason through [isValid] and [rejectionReason]. */
    private class RejectSpans(private val reasons: Map<String, String?>) : MentionFilter {
        val seenTexts = mutableListOf<String>()
        override fun isValid(mention: SuggestedMention, propositionText: String): Boolean {
            seenTexts += propositionText
            return mention.span !in reasons
        }
        override fun rejectionReason(mention: SuggestedMention): String? = reasons[mention.span]
    }

    /** Overrides only [validateMentions] and records what it is handed. */
    private class Recording(private val rejects: Set<String> = emptySet()) : MentionFilter {
        val handed = mutableListOf<SuggestedProposition>()
        var calls = 0
        override fun isValid(mention: SuggestedMention, propositionText: String) = true
        override fun validateMentions(proposition: SuggestedProposition, context: SourceAnalysisContext): List<ValidationResult> {
            calls++
            handed += proposition
            return proposition.mentions.map {
                if (it.span in rejects) ValidationResult.Invalid("rejected ${it.span}") else ValidationResult.Valid
            }
        }
    }

    private object AcceptAll : MentionFilter {
        override fun isValid(mention: SuggestedMention, propositionText: String) = true
    }

    private val ctx = context()
    private val abcb = proposition("A met B and C and B", mention("A"), mention("B"), mention("C"), mention("B"))

    private fun rejectB() = RejectSpans(mapOf("B" to "no B"))
    private fun rejectC() = RejectSpans(mapOf("C" to "no C"))

    @Test
    fun `E1 default method gives one result per mention in order with the filter's reasons`() {
        val filter = RejectSpans(mapOf("B" to "no B", "C" to null))
        val results = filter.validateMentions(abcb, ctx)
        assertEquals(
            listOf(
                ValidationResult.Valid,
                ValidationResult.Invalid("no B"),
                ValidationResult.Invalid("rejected by a mention filter"),
                ValidationResult.Invalid("no B"),
            ),
            results,
        )
    }

    @Test
    fun `E2 default method passes the proposition text to isValid`() {
        val filter = RejectSpans(emptyMap())
        filter.validateMentions(abcb, ctx)
        assertEquals(List(4) { "A met B and C and B" }, filter.seenTexts)
    }

    @Test
    fun `E3 composite takes the first rejection in filter order for each mention`() {
        val results = CompositeMentionFilter(listOf(rejectB(), rejectC())).validateMentions(abcb, ctx)
        assertEquals(
            listOf(
                ValidationResult.Valid,
                ValidationResult.Invalid("no B"),
                ValidationResult.Invalid("no C"),
                ValidationResult.Invalid("no B"),
            ),
            results,
        )
    }

    @Test
    fun `E4 composite calls validateMentions of each child`() {
        val known = context(knownPerson("user-1", "Thomas", "Tom"))
        val knownOnly = object : MentionFilter {
            override fun isValid(mention: SuggestedMention, propositionText: String) = true
            override fun validateMentions(proposition: SuggestedProposition, context: SourceAnalysisContext) =
                proposition.mentions.map {
                    if (context.knownEntities.any { e -> e.name == it.span }) ValidationResult.Valid
                    else ValidationResult.Invalid("not a known entity")
                }
        }
        val p = proposition("Thomas met Zed", mention("Thomas"), mention("Zed"))
        val results = CompositeMentionFilter(listOf(AcceptAll, knownOnly)).validateMentions(p, known)
        assertEquals(listOf(ValidationResult.Valid, ValidationResult.Invalid("not a known entity")), results)
    }

    @Test
    fun `E5 composite throws when a child returns too few results`() {
        val short = object : MentionFilter {
            override fun isValid(mention: SuggestedMention, propositionText: String) = true
            override fun validateMentions(proposition: SuggestedProposition, context: SourceAnalysisContext) =
                emptyList<ValidationResult>()
        }
        val e = assertThrows(IllegalStateException::class.java) {
            CompositeMentionFilter(listOf(short)).validateMentions(abcb, ctx)
        }
        assertTrue(e.message!!.contains(short::class.java.name), e.message)
        assertTrue(e.message!!.contains("0 results for 4 mentions"), e.message)
    }

    @Test
    fun `E6 observable counts each result once and returns the delegate's list`() {
        val registry = SimpleMeterRegistry()
        val delegate = RejectSpans(mapOf("B" to "no B"))
        val p = proposition("A met B and C", mention("A"), mention("B"), mention("C"))
        val results = ObservableMentionFilter(delegate, registry).validateMentions(p, ctx)
        assertEquals(delegate.validateMentions(p, ctx), results)
        assertEquals(2.0, registry.counter("dice.mentions.validated", "entity.type", "Person", "valid", "true").count())
        assertEquals(1.0, registry.counter("dice.mentions.validated", "entity.type", "Person", "valid", "false").count())
    }

    @Test
    fun `E7 a proposition with no mentions gives an empty list`() {
        val p = proposition("Nothing here")
        assertEquals(emptyList<ValidationResult>(), AcceptAll.validateMentions(p, ctx))
        assertEquals(emptyList<ValidationResult>(), CompositeMentionFilter(listOf(rejectB(), rejectC())).validateMentions(p, ctx))
        assertEquals(emptyList<ValidationResult>(), ObservableMentionFilter(AcceptAll, SimpleMeterRegistry()).validateMentions(p, ctx))
    }

    @Test
    fun `E8 a later child sees only the mentions still valid`() {
        val second = Recording()
        CompositeMentionFilter(listOf(rejectB(), second)).validateMentions(abcb, ctx)
        assertEquals(listOf("A", "C"), second.handed.single().mentions.map { it.span })

        val acceptingSecond = Recording()
        CompositeMentionFilter(listOf(AcceptAll, acceptingSecond)).validateMentions(abcb, ctx)
        assertSame(abcb, acceptingSecond.handed.single())

        val neverAsked = Recording()
        val rejectAll = RejectSpans(mapOf("A" to "no", "B" to "no", "C" to "no"))
        CompositeMentionFilter(listOf(rejectAll, neverAsked)).validateMentions(abcb, ctx)
        assertEquals(0, neverAsked.calls)
    }

    @Test
    fun `E9 an observable child placed second counts only what it is asked about`() {
        val registry = SimpleMeterRegistry()
        CompositeMentionFilter(listOf(rejectB(), ObservableMentionFilter(AcceptAll, registry))).validateMentions(abcb, ctx)
        val total = registry.counter("dice.mentions.validated", "entity.type", "Person", "valid", "true").count() +
            registry.counter("dice.mentions.validated", "entity.type", "Person", "valid", "false").count()
        assertEquals(2.0, total)
    }
}

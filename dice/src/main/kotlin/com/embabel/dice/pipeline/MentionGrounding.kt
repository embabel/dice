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

import com.embabel.dice.proposition.SuggestedMention
import com.embabel.dice.proposition.SuggestedPropositions
import java.text.Normalizer

/** What the pipeline does with a mention whose span is not in its own proposition's text. */
enum class UngroundedMentionHandling { KEEP, DROP }

/**
 * A mention the extraction model attached to a proposition that does not name it.
 *
 * @property propositionText the text of the proposition the mention was attached to
 * @property mention the mention exactly as the model suggested it
 * @property reason why the mention was judged ungrounded
 */
data class UngroundedMention(
    val propositionText: String,
    val mention: SuggestedMention,
    val reason: Reason,
) {
    /** Why a mention was judged ungrounded. */
    enum class Reason {
        /** The span has nothing left in it once whitespace and invisible characters are gone. */
        BLANK_SPAN,

        /** The span is not anywhere in the proposition's text. */
        SPAN_NOT_IN_PROPOSITION,
    }
}

/**
 * The result of checking a chunk's propositions: the propositions with only their grounded
 * mentions left, plus every mention that was taken out.
 */
internal data class GroundingOutcome(
    val grounded: SuggestedPropositions,
    val ungrounded: List<UngroundedMention>,
)

/**
 * Checks that each suggested mention's span actually appears in the text of the proposition it
 * is attached to.
 *
 * Both strings are normalised first so that harmless differences don't count against a mention:
 * composed versus decomposed accents, full-width letters, curly quotes and apostrophes, the
 * various dash characters, invisible characters such as zero-width spaces and soft hyphens, and
 * any kind or amount of whitespace. Case is ignored, but accents are not: "Beyonce" is not
 * found in "Beyonc\u00e9".
 *
 * This is plain containment, so a span inside a longer word counts as present ("Ann" is found
 * in "Annabel"). That is deliberate: the check is only meant to catch spans that are not in the
 * text at all, not to second-guess where a word starts or ends.
 *
 * Only a mention's own proposition counts. A span that appears in a different proposition from
 * the same chunk is still ungrounded.
 */
internal object MentionGrounding {

    private val WHITESPACE_RUN = Regex("[" + "\\s" + "\\p{Z}" + 0x85.toChar() + "]+")

    private val INVISIBLE = setOf('\u00ad', '\u200b', '\u2060', '\ufeff')

    private val LOOKALIKES = mapOf(
        '\u2018' to '\'',
        '\u2019' to '\'',
        '\u201b' to '\'',
        '\u02bc' to '\'',
        '\u2032' to '\'',
        '\u00b4' to '\'',
        '\u201c' to '"',
        '\u201d' to '"',
        '\u2010' to '-',
        '\u2011' to '-',
        '\u2012' to '-',
        '\u2013' to '-',
        '\u2212' to '-',
    )

    /** Returns null when the span is in the text; otherwise why it is not. */
    fun reasonUngrounded(span: String, propositionText: String): UngroundedMention.Reason? {
        val normalisedSpan = normalise(span)
        if (normalisedSpan.isEmpty()) return UngroundedMention.Reason.BLANK_SPAN
        val normalisedText = normalise(propositionText)
        return if (normalisedText.contains(normalisedSpan, ignoreCase = true)) {
            null
        } else {
            UngroundedMention.Reason.SPAN_NOT_IN_PROPOSITION
        }
    }

    /**
     * Splits every proposition's mentions into grounded and ungrounded ones. Every proposition
     * comes back, in order, even if it loses all its mentions. When nothing is ungrounded the
     * input is returned untouched.
     */
    fun check(suggested: SuggestedPropositions): GroundingOutcome {
        val ungrounded = mutableListOf<UngroundedMention>()
        val propositions = suggested.propositions.map { proposition ->
            val kept = mutableListOf<SuggestedMention>()
            proposition.mentions.forEach { mention ->
                val reason = reasonUngrounded(mention.span, proposition.text)
                if (reason == null) {
                    kept.add(mention)
                } else {
                    ungrounded.add(UngroundedMention(proposition.text, mention, reason))
                }
            }
            if (kept.size == proposition.mentions.size) {
                proposition
            } else {
                proposition.copy(mentions = kept.toList())
            }
        }
        if (ungrounded.isEmpty()) return GroundingOutcome(suggested, emptyList())
        return GroundingOutcome(
            grounded = suggested.copy(propositions = propositions),
            ungrounded = ungrounded.toList(),
        )
    }

    /**
     * Folds look-alikes and drops invisible characters on both sides of composing. Before, because
     * composing would otherwise split an acute-accent apostrophe into a space and an accent, and an
     * invisible character would keep a letter apart from its accent. After, because composing can
     * itself produce a look-alike, such as a non-breaking hyphen turning into a plain Unicode hyphen.
     */
    private fun normalise(text: String): String {
        val composed = Normalizer.normalize(foldAndStrip(text), Normalizer.Form.NFKC)
        return foldAndStrip(composed).replace(WHITESPACE_RUN, " ").trim()
    }

    /** Drops invisible characters and swaps each look-alike for its plain form. */
    private fun foldAndStrip(text: String): String = buildString(text.length) {
        text.forEach { c ->
            if (c !in INVISIBLE) append(LOOKALIKES[c] ?: c)
        }
    }
}

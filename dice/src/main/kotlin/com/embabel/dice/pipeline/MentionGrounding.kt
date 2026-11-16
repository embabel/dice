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
import java.util.BitSet
import java.util.EnumSet
import java.util.Locale

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
 * A text folded once, so any number of spans can be looked up in it.
 *
 * @property folded the text after folding
 * @property softEdges offsets in [folded] that sit beside a symbol spelled out as letters or digits
 */
internal class PreparedText internal constructor(internal val folded: String, internal val softEdges: BitSet)

/**
 * Checks that each suggested mention's span appears as whole words in the text of the proposition
 * it is attached to.
 *
 * Both strings are folded the same way before they are compared. Format characters such as
 * zero-width spaces and soft hyphens are dropped, along with a few other characters that never
 * change a word, such as the Arabic tatweel and the variation selectors. Every dash and the minus
 * sign become a hyphen, and curly or modifier quotes become straight ones. Both strings then go
 * through NFKC, which composes accents and turns full-width letters into plain ones. Any run of
 * whitespace becomes one space, and case is folded fully, so "STRASSE" matches the German
 * spelling with a sharp s. Accents are significant in words.
 *
 * A span counts only where both of its ends sit at a word edge. An end splits a word when the
 * characters on both sides of it are digits, or letters or marks in Latin, Cyrillic, Greek,
 * Armenian or Georgian. Those scripts put spaces between words, so "US" is not found in "focus"
 * and "Apple" is not found in "Apples". In every other script a span is found anywhere inside the
 * text, since many of those scripts attach prefixes, particles or case endings to a name, or write
 * no spaces at all. A symbol that NFKC spells out as letters or digits, such as a trade mark sign
 * or a superscript digit, leaves a word edge on each side of it.
 *
 * Only a mention's own proposition counts. A span that appears in a different proposition from
 * the same chunk is still ungrounded.
 */
internal object MentionGrounding {

    private val WHITESPACE_RUN = Regex("[" + "\\s" + "\\p{Z}" + 0x85.toChar() + "]+")
    private const val EDGE_MARK = 0x1F
    private const val MINUS_SIGN = 0x2212
    private val APOSTROPHES = intArrayOf(0x2018, 0x2019, 0x201A, 0x201B, 0x2032, 0x2035, 0x02B9, 0x02BB, 0x02BC, 0x00B4, 0x0060)
    private val DOUBLE_QUOTES = intArrayOf(0x201C, 0x201D, 0x201E, 0x201F, 0x2033, 0x2036, 0x02BA)
    private val IGNORED = intArrayOf(0x034F, 0x0640, 0x115F, 0x1160, 0x3164, 0xFFA0)
    private val FINAL_SIGMA = 0x03C2.toChar()
    private val SIGMA = 0x03C3.toChar()
    private val DOTTED_SMALL_I = "i" + 0x0307.toChar()
    private val SPACED_SCRIPTS: Set<Character.UnicodeScript> = EnumSet.of(
        Character.UnicodeScript.LATIN, Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.GREEK,
        Character.UnicodeScript.ARMENIAN, Character.UnicodeScript.GEORGIAN,
    )

    /**
     * Folds a text so spans can be looked up in it.
     *
     * @param text the raw text
     * @return the folded text with the offsets of its soft edges
     */
    fun prepare(text: String): PreparedText {
        val composed = Normalizer.normalize(foldAndStrip(text, markSymbols = true), Normalizer.Form.NFKC)
        val marked = foldCase(foldAndStrip(composed, markSymbols = false).replace(WHITESPACE_RUN, " ").trim())
        val softEdges = BitSet()
        val folded = buildString(marked.length) {
            marked.forEach { c -> if (c.code == EDGE_MARK) softEdges.set(length) else append(c) }
        }
        return PreparedText(folded, softEdges)
    }

    /**
     * Tells whether a span has nothing left once it is folded.
     *
     * @param span the raw span
     * @return true when the folded span is empty
     */
    fun isBlank(span: String): Boolean = prepare(span).folded.isEmpty()

    /**
     * Tells whether two prepared texts are the same name.
     *
     * @param a the first prepared text
     * @param b the second prepared text
     * @return true when both are equal after folding, and false when either is empty
     */
    fun sameName(a: PreparedText, b: PreparedText): Boolean = a.folded.isNotEmpty() && a.folded == b.folded

    /**
     * Finds why a prepared span is not grounded in a prepared text.
     *
     * @param span the prepared span
     * @param text the prepared proposition text
     * @return null when the span is in the text as whole words, otherwise the reason it is not
     */
    fun reasonUngrounded(span: PreparedText, text: PreparedText): UngroundedMention.Reason? = when {
        span.folded.isEmpty() -> UngroundedMention.Reason.BLANK_SPAN
        containsWhole(text, span.folded) -> null
        else -> UngroundedMention.Reason.SPAN_NOT_IN_PROPOSITION
    }

    /**
     * Finds why a raw span is not grounded in a prepared text.
     *
     * @param span the raw span
     * @param text the prepared proposition text
     * @return null when the span is in the text as whole words, otherwise the reason it is not
     */
    fun reasonUngrounded(span: String, text: PreparedText): UngroundedMention.Reason? =
        reasonUngrounded(prepare(span), text)

    /**
     * Finds why a raw span is not grounded in a raw proposition text.
     *
     * @param span the raw span
     * @param propositionText the raw proposition text
     * @return null when the span is in the text as whole words, otherwise the reason it is not
     */
    fun reasonUngrounded(span: String, propositionText: String): UngroundedMention.Reason? =
        reasonUngrounded(prepare(span), prepare(propositionText))

    /**
     * Splits every proposition's mentions into grounded and ungrounded ones. Every proposition
     * comes back, in order, even if it loses all its mentions. When nothing is ungrounded the
     * input is returned untouched. Each proposition's text is prepared once, and a proposition
     * without mentions is not prepared at all.
     *
     * @param suggested the propositions suggested for one chunk
     * @return the propositions with their grounded mentions, and every mention taken out
     */
    fun check(suggested: SuggestedPropositions): GroundingOutcome {
        val ungrounded = mutableListOf<UngroundedMention>()
        val propositions = suggested.propositions.map { proposition ->
            if (proposition.mentions.isEmpty()) return@map proposition
            val text = prepare(proposition.text)
            val kept = mutableListOf<SuggestedMention>()
            proposition.mentions.forEach { mention ->
                val reason = reasonUngrounded(mention.span, text)
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
     * Tells whether a code point is dropped before comparing.
     *
     * @param cp the code point
     * @return true for format characters, fillers and variation selectors
     */
    private fun isIgnored(cp: Int): Boolean =
        Character.getType(cp) == Character.FORMAT.toInt() || cp in IGNORED ||
            cp in 0x180B..0x180F || cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF

    /**
     * Tells whether a code point can be part of a word.
     *
     * @param cp the code point
     * @return true for a letter, a digit or a mark
     */
    private fun isWordCharacter(cp: Int): Boolean = Character.isLetterOrDigit(cp) || when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    /**
     * Tells whether a symbol turns into letters or digits under NFKC.
     *
     * @param cp the code point
     * @return true when the code point is no word character and NFKC makes it start or end with a letter or digit
     */
    private fun spellsOutAsWord(cp: Int): Boolean {
        if (cp < 0xA0 || isWordCharacter(cp)) return false
        val own = String(Character.toChars(cp))
        val composed = Normalizer.normalize(own, Normalizer.Form.NFKC)
        return composed != own &&
            (Character.isLetterOrDigit(composed.codePointAt(0)) || Character.isLetterOrDigit(composed.codePointBefore(composed.length)))
    }

    /**
     * Drops ignored characters, folds dashes and quotes, and can put an edge marker around each
     * symbol that spells out as a word.
     *
     * @param text the text to fold
     * @param markSymbols whether to mark symbols that spell out as a word
     * @return the folded text
     */
    private fun foldAndStrip(text: String, markSymbols: Boolean): String = buildString(text.length) {
        text.codePoints().forEach { cp ->
            when {
                isIgnored(cp) -> Unit
                Character.getType(cp) == Character.DASH_PUNCTUATION.toInt() || cp == MINUS_SIGN -> append('-')
                cp in APOSTROPHES -> append('\'')
                cp in DOUBLE_QUOTES -> append('"')
                markSymbols && cp == EDGE_MARK -> append(' ')
                markSymbols && spellsOutAsWord(cp) -> {
                    appendCodePoint(EDGE_MARK)
                    appendCodePoint(cp)
                    appendCodePoint(EDGE_MARK)
                }
                else -> appendCodePoint(cp)
            }
        }
    }

    /**
     * Folds case as lower, upper, then lower, so letters that expand under upper case match
     * their expanded spelling.
     *
     * @param text the text to fold
     * @return the case-folded text
     */
    private fun foldCase(text: String): String = Normalizer.normalize(
        text.lowercase(Locale.ROOT).uppercase(Locale.ROOT).lowercase(Locale.ROOT)
            .replace(FINAL_SIGMA, SIGMA)
            .replace(DOTTED_SMALL_I, "i"),
        Normalizer.Form.NFKC,
    )

    /**
     * Finds the script of the code point at an index. A code point in the Common or Inherited
     * script takes the script of the word character before it.
     *
     * @param text the folded text
     * @param index the offset of the code point
     * @return the script that decides whether the code point needs a word boundary
     */
    private tailrec fun scriptAt(text: String, index: Int): Character.UnicodeScript {
        val script = Character.UnicodeScript.of(text.codePointAt(index))
        val borrowed = script == Character.UnicodeScript.COMMON || script == Character.UnicodeScript.INHERITED
        if (index == 0 || !borrowed) return script
        // Borrow only from a word character directly before this one, never across a space.
        val previous = text.offsetByCodePoints(index, -1)
        return if (isWordCharacter(text.codePointAt(previous))) scriptAt(text, previous) else script
    }

    /**
     * Tells whether the code point at an index belongs to a word that spaces delimit.
     *
     * @param text the folded text
     * @param index the offset of the code point
     * @return true for a digit, or a word character in a script that puts spaces between words
     */
    private fun needsBoundary(text: String, index: Int): Boolean {
        val cp = text.codePointAt(index)
        return Character.isDigit(cp) || (isWordCharacter(cp) && scriptAt(text, index) in SPACED_SCRIPTS)
    }

    /**
     * Tells whether an offset in a prepared text falls inside a word.
     *
     * @param text the prepared text
     * @param offset the offset between two code points
     * @return true when the offset is no soft edge and the code points on both sides need a boundary
     */
    private fun splitsWord(text: PreparedText, offset: Int): Boolean =
        offset > 0 && offset < text.folded.length && !text.softEdges.get(offset) &&
            needsBoundary(text.folded, text.folded.offsetByCodePoints(offset, -1)) && needsBoundary(text.folded, offset)

    /**
     * Tells whether a folded span occurs in a prepared text with neither end splitting a word.
     *
     * @param text the prepared text
     * @param span the folded span
     * @return true when at least one occurrence sits at word edges
     */
    private fun containsWhole(text: PreparedText, span: String): Boolean =
        generateSequence(text.folded.indexOf(span)) { text.folded.indexOf(span, it + 1) }
            .takeWhile { it >= 0 }
            .any { start -> !splitsWord(text, start) && !splitsWord(text, start + span.length) }
}

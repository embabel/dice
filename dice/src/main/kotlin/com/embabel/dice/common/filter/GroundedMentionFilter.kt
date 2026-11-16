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

import com.embabel.dice.common.KnownEntity
import com.embabel.dice.common.SourceAnalysisContext
import com.embabel.dice.common.resolver.searcher.NormalizedNameCandidateSearcher
import com.embabel.dice.common.validation.ValidationResult
import com.embabel.dice.pipeline.MentionGrounding
import com.embabel.dice.pipeline.PreparedText
import com.embabel.dice.pipeline.UngroundedMention
import com.embabel.dice.proposition.SuggestedMention
import com.embabel.dice.proposition.SuggestedProposition

/**
 * A mention filter that accepts a mention only when its proposition's text names it.
 *
 * A mention is grounded when its span appears in the proposition text at word edges. Case,
 * whitespace, dashes and quotes are folded before the comparison.
 *
 * With the analysis context, a mention is also grounded when it names a known entity and the text
 * names that same entity. The text names a known entity when the entity's name or one of its
 * aliases appears in the text at word edges. The mention names the entity when its suggested id is
 * the entity's id, or when its span and one of the entity's names are equal after name
 * normalization, ignoring case, which is how entity resolution matches a known entity. The extraction prompt asks for the canonical name with the entity's
 * id, so with Thomas known under the alias Tom, the mention "Thomas" on the text
 * "Tom moved to Paris" is grounded.
 *
 * Entity resolution matches a known entity by name first, so a span that is the name of several
 * known entities is grounded only when the text names every one of them. With Bob and Alice both
 * known as Pat, the mention "Pat" on "Alice waved" is ungrounded, with or without Alice's id,
 * because resolution could link it to Bob.
 *
 * A mention that names something the text only implies is rejected. The mention "Cycling" on
 * "Alice cycles" is ungrounded even with the id of a known entity called Cycling. A blank span is
 * never grounded, whatever its suggested id.
 *
 * The rejection reasons are the names of [UngroundedMention.Reason]: `BLANK_SPAN` and
 * `SPAN_NOT_IN_PROPOSITION`. [rejectionReason] gets no proposition text, so it reports only a
 * blank span and returns null for every other mention.
 */
class GroundedMentionFilter : MentionFilter {

    override fun isValid(mention: SuggestedMention, propositionText: String): Boolean =
        MentionGrounding.reasonUngrounded(mention.span, propositionText) == null

    override fun rejectionReason(mention: SuggestedMention): String? =
        if (MentionGrounding.isBlank(mention.span)) UngroundedMention.Reason.BLANK_SPAN.name else null

    override fun validateMentions(proposition: SuggestedProposition, context: SourceAnalysisContext): List<ValidationResult> =
        KnownEntityGrounding.reasons(proposition, context.knownEntities)
            .map { reason -> if (reason == null) ValidationResult.Valid else ValidationResult.Invalid(reason.name) }
}

/**
 * Judges a proposition's mentions against its text and the known entities that the text names.
 */
internal object KnownEntityGrounding {

    /**
     * Finds why each mention of a proposition is ungrounded.
     *
     * The text is prepared once per proposition. The known entities are looked at only when a
     * mention fails the span check, and then once for the whole proposition.
     *
     * @param proposition the proposition whose mentions are judged
     * @param knownEntities the known entities of the analysis
     * @param prepare folds a text so spans can be looked up in it
     * @return one reason per mention in order, null for a grounded mention
     */
    fun reasons(
        proposition: SuggestedProposition,
        knownEntities: List<KnownEntity>,
        prepare: (String) -> PreparedText = MentionGrounding::prepare,
    ): List<UngroundedMention.Reason?> {
        if (proposition.mentions.isEmpty()) return emptyList()
        val text = prepare(proposition.text)
        val known by lazy { knownEntities.mapNotNull { knownForms(it, text, prepare) } }
        return proposition.mentions.map { mention ->
            val reason = MentionGrounding.reasonUngrounded(prepare(mention.span), text)
            if (reason == UngroundedMention.Reason.SPAN_NOT_IN_PROPOSITION && namesOneOf(mention, known)) null else reason
        }
    }

    /**
     * A known entity's id and name forms, and whether the proposition text names it.
     *
     * @property id the entity's id, or null when it is blank
     * @property names the entity's name forms after name normalization
     * @property inText true when one of the entity's names is in the text at word edges
     */
    private class KnownForms(val id: String?, val names: List<String>, val inText: Boolean)

    /**
     * Collects a known entity's id and names, and checks whether the text names the entity.
     *
     * @param known the known entity
     * @param text the prepared proposition text
     * @param prepare folds a text so spans can be looked up in it
     * @return the entity's id, names and whether the text names it, or null when it has no non-blank name
     */
    private fun knownForms(known: KnownEntity, text: PreparedText, prepare: (String) -> PreparedText): KnownForms? {
        val forms = (listOf<String?>(known.name) + known.aliases).filterNotNull().filter { it.isNotBlank() }
        if (forms.isEmpty()) return null
        val id: String? = known.id
        return KnownForms(
            id = id?.takeIf { it.isNotBlank() },
            names = forms.map { NormalizedNameCandidateSearcher.normalizeName(it) }.filter { it.isNotBlank() },
            inText = forms.any { MentionGrounding.reasonUngrounded(prepare(it), text) == null },
        )
    }

    /**
     * Tells whether a mention names a known entity that the text names, and only such an entity.
     *
     * Entity resolution matches a known entity by name before it looks at a suggested id, and picks
     * the first one when several share the name. So when the mention's name belongs to any known
     * entity, it is grounded only if every known entity with that name is one the text names.
     * Otherwise its suggested id must belong to a known entity the text names.
     *
     * @param mention the mention
     * @param known the known entities of the analysis with their names
     * @return true when the mention can only resolve to a known entity the text names
     */
    private fun namesOneOf(mention: SuggestedMention, known: List<KnownForms>): Boolean {
        if (known.none { it.inText }) return false
        val name = NormalizedNameCandidateSearcher.normalizeName(mention.span)
        val sameName = if (name.isBlank()) emptyList() else known.filter { forms -> forms.names.any { it.equals(name, ignoreCase = true) } }
        if (sameName.isNotEmpty()) return sameName.all { it.inText }
        val id = mention.suggestedId?.takeIf { it.isNotBlank() } ?: return false
        return known.any { it.inText && it.id == id }
    }
}

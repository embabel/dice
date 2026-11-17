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
import java.util.IdentityHashMap

/**
 * A mention filter that accepts a mention only when its proposition's text names it.
 *
 * A mention is grounded when its span appears in the proposition text at word edges. Case,
 * whitespace, dashes and quotes are folded before the comparison.
 *
 * With the analysis context, a mention is also grounded when it names a known entity and the text
 * names that same entity. The text names a known entity when the entity's name or one of its
 * aliases appears in the text at word edges. The mention names the entity when its span and one of
 * the entity's names are equal after name normalization, ignoring case, which is how entity
 * resolution matches a known entity. With Thomas known under the alias Tom, the mention "Thomas" on
 * the text "Tom moved to Paris" is grounded. A suggested id alone grounds nothing, because entity
 * resolution matches known entities by name and does not follow the id.
 *
 * When several known entities share the span's name, entity resolution picks one by how its labels
 * overlap the mention's type, which the text says nothing about, so the span is grounded only when
 * the text names every one of them. With Bob and Alice both known as Pat, the mention "Pat" on
 * "Alice waved" is ungrounded, with or without Alice's id, because resolution could link it to Bob.
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

    /**
     * The names and aliases of the known entities last seen, copied as strings, and their prepared
     * names. They are reused while the names stay the same, so a list, an entity or an alias list
     * changed in place is prepared again.
     */
    @Volatile
    private var lastKnown: Pair<List<List<String?>>, KnownEntityGrounding.KnownIndex>? = null

    override fun validateMentions(proposition: SuggestedProposition, context: SourceAnalysisContext): List<ValidationResult> =
        KnownEntityGrounding.reasons(proposition, lazy { knownNames(context.knownEntities) })
            .map { reason -> if (reason == null) ValidationResult.Valid else ValidationResult.Invalid(reason.name) }

    /**
     * Prepares the names of the known entities once for as long as they stay the same, so a chunk's
     * propositions, which share one analysis context, do not prepare them again.
     *
     * @param knownEntities the known entities of the analysis
     * @return their prepared names, indexed by name
     */
    private fun knownNames(knownEntities: List<KnownEntity>): KnownEntityGrounding.KnownIndex {
        lastKnown?.let { (seen, names) -> if (sameNames(seen, knownEntities)) return names }
        val snapshot = knownEntities.map { listOf<String?>(it.name) + it.aliases }
        return KnownEntityGrounding.knownNames(knownEntities).also { lastKnown = snapshot to it }
    }

    /**
     * Tells whether the known entities still have the names and aliases copied earlier, without
     * copying them again. The same list object is not enough, because a caller may change it in
     * place.
     *
     * @param seen the names and aliases copied earlier, one list per entity, name first
     * @param knownEntities the known entities now
     * @return true when every name and alias is the same, in the same order
     */
    private fun sameNames(seen: List<List<String?>>, knownEntities: List<KnownEntity>): Boolean {
        if (seen.size != knownEntities.size) return false
        for (index in seen.indices) {
            val names = seen[index]
            val known = knownEntities[index]
            val aliases = known.aliases
            if (names.size != aliases.size + 1 || names[0] != known.name) return false
            for (alias in aliases.indices) if (names[alias + 1] != aliases[alias]) return false
        }
        return true
    }
}

/**
 * Judges a proposition's mentions against its text and the known entities that the text names.
 */
internal object KnownEntityGrounding {

    /**
     * Finds why each mention of a proposition is ungrounded.
     *
     * The text is prepared once per proposition. A mention that fails the span check is compared by
     * name with the known entities, and only the known entities that share its name are looked for
     * in the text, each at most once per proposition.
     *
     * @param proposition the proposition whose mentions are judged
     * @param knownNames the prepared names of the known entities, read only when a mention misses
     * @param prepare folds a text so spans can be looked up in it
     * @return one reason per mention in order, null for a grounded mention
     */
    fun reasons(
        proposition: SuggestedProposition,
        knownNames: Lazy<List<KnownNames>>,
        prepare: (String) -> PreparedText = MentionGrounding::prepare,
    ): List<UngroundedMention.Reason?> {
        if (proposition.mentions.isEmpty()) return emptyList()
        val text = prepare(proposition.text)
        val named = IdentityHashMap<KnownNames, Boolean>()
        val inText = { known: KnownNames ->
            named.getOrPut(known) { known.forms.any { form -> MentionGrounding.reasonUngrounded(form, text) == null } }
        }
        return proposition.mentions.map { mention ->
            val reason = MentionGrounding.reasonUngrounded(prepare(mention.span), text)
            if (reason == UngroundedMention.Reason.SPAN_NOT_IN_PROPOSITION && namesOneOf(mention, KnownIndex.of(knownNames.value), inText)) null else reason
        }
    }

    /**
     * A known entity's names, normalized for comparing with a span and prepared for finding in a text.
     *
     * @property names the entity's name forms after name normalization
     * @property forms the entity's name forms prepared for lookup in a text
     */
    class KnownNames(val names: List<String>, val forms: List<PreparedText>)

    /**
     * The prepared names of the known entities, with an index from each name to the entities that
     * carry it.
     *
     * Names are indexed by a key under which two names are equal exactly when they are equal
     * ignoring case, the comparison entity resolution uses.
     *
     * @property entries the prepared names, one entry per known entity with a name
     */
    class KnownIndex(private val entries: List<KnownNames>) : AbstractList<KnownNames>() {
        private val byName: Map<String, List<KnownNames>> =
            entries.flatMap { entry -> entry.names.distinctBy(::caseKey).map { caseKey(it) to entry } }
                .groupBy({ it.first }, { it.second })

        override val size: Int get() = entries.size

        override fun get(index: Int): KnownNames = entries[index]

        /**
         * Finds the known entities that carry a name.
         *
         * @param name a normalized name
         * @return the entities with that name, ignoring case, in their order
         */
        fun named(name: String): List<KnownNames> = byName[caseKey(name)].orEmpty()

        companion object {
            /**
             * Wraps prepared names in an index, or returns them when they already are one.
             *
             * @param names the prepared names
             * @return the index
             */
            fun of(names: List<KnownNames>): KnownIndex = names as? KnownIndex ?: KnownIndex(names)

            /**
             * Folds a name one code point at a time, so that two names get the same key exactly
             * when they are equal ignoring case, as String.equals(other, ignoreCase = true) decides
             * it, letters outside the Basic Multilingual Plane included.
             *
             * @param name the name
             * @return its key
             */
            private fun caseKey(name: String): String = buildString(name.length) {
                name.codePoints().forEach { appendCodePoint(Character.toLowerCase(Character.toUpperCase(it))) }
            }
        }
    }

    /**
     * Prepares the names of the known entities that have at least one non-blank name.
     *
     * @param knownEntities the known entities of the analysis
     * @param prepare folds a text so spans can be looked up in it
     * @return the prepared names, one entry per known entity with a name, indexed by name
     */
    fun knownNames(
        knownEntities: List<KnownEntity>,
        prepare: (String) -> PreparedText = MentionGrounding::prepare,
    ): KnownIndex = KnownIndex(knownEntities.mapNotNull { known ->
        val forms = (listOf<String?>(known.name) + known.aliases).filterNotNull().filter { it.isNotBlank() }
        if (forms.isEmpty()) null
        else KnownNames(
            names = forms.map { NormalizedNameCandidateSearcher.normalizeName(it) }.filter { it.isNotBlank() },
            forms = forms.map(prepare),
        )
    })

    /**
     * Tells whether a mention names a known entity that the text names, and only such entities.
     *
     * Entity resolution links a mention to a known entity with the same name, preferring one whose
     * labels overlap the mention's type and otherwise taking the first. Which one it picks depends on
     * labels the text says nothing about, so the mention is grounded only when its name belongs to a
     * known entity and every known entity with that name is one the text names.
     *
     * @param mention the mention
     * @param known the prepared names of the known entities, indexed by name
     * @param inText tells whether the text names a known entity
     * @return true when the mention can only resolve to a known entity the text names
     */
    private fun namesOneOf(mention: SuggestedMention, known: KnownIndex, inText: (KnownNames) -> Boolean): Boolean {
        val name = NormalizedNameCandidateSearcher.normalizeName(mention.span)
        if (name.isBlank()) return false
        val sameName = known.named(name)
        return sameName.isNotEmpty() && sameName.all(inText)
    }
}

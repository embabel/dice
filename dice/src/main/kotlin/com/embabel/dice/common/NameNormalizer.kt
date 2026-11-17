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
package com.embabel.dice.common

/**
 * Reduces an entity's name to the form two spellings of the same entity share, so they can be
 * compared.
 *
 * Entity resolution and the grounding check compare names after normalizing them. Two names
 * that normalize to the same text, ignoring case, are treated as the same name. What counts as
 * "the same" depends on your data: honorifics, name order and initials differ between languages
 * and between people, companies and products. Supply your own normalizer when the built-in rules
 * do not fit.
 *
 * Use one normalizer everywhere names are compared. `PropositionPipeline.withNameNormalizer` and
 * `EntityPipeline.withNameNormalizer` set it for the whole run. A resolver or searcher you build
 * yourself takes the same normalizer through its own `withNameNormalizer`.
 *
 * Java:
 * ```java
 * // The built-in rules, tuned
 * NameNormalizer german = PersonNameNormalizer.english()
 *     .withPersonLabels("Person", "Contact")
 *     .withTitles("Herr", "Frau", "Dr", "Prof");
 *
 * // Your own rule
 * NameNormalizer custom = (name, labels) -> name.strip();
 * ```
 *
 * Kotlin:
 * ```kotlin
 * val german = PersonNameNormalizer {
 *     personLabels("Person", "Contact")
 *     titles("Herr", "Frau", "Dr", "Prof")
 * }
 * val custom = NameNormalizer { name, _ -> name.trim() }
 * ```
 */
fun interface NameNormalizer {

    /**
     * Normalizes one name.
     *
     * @param name the name as written
     * @param labels the type labels of the entity the name belongs to
     * @return the normalized name; blank when nothing is left to compare
     */
    fun normalize(name: String, labels: Collection<String>): String

    companion object {

        private val WHITESPACE_ONLY: NameNormalizer = NameNormalizer { name, _ -> collapseWhitespace(name) }

        /**
         * The normalizer used when none is supplied: [PersonNameNormalizer.english].
         *
         * A name whose entity is labelled `Person` loses a leading English title, a trailing
         * generational suffix and a single-letter middle initial, and "Last, First" is turned
         * around. Every other name only has its whitespace collapsed, so "Series A Round" and
         * "Series B Round" stay different.
         *
         * @return the standard normalizer
         */
        @JvmStatic
        fun standard(): NameNormalizer = PersonNameNormalizer.english()

        /**
         * Trims a name and collapses runs of whitespace, and changes nothing else.
         *
         * @return a normalizer with no language-specific rules
         */
        @JvmStatic
        fun whitespaceOnly(): NameNormalizer = WHITESPACE_ONLY
    }
}

private val WHITESPACE = Regex("[\\s\\p{Z}]+")

/**
 * Trims a name and collapses each run of whitespace, of any kind, to one space.
 *
 * @param name the name as written
 * @return the name with single spaces
 */
private fun collapseWhitespace(name: String): String = name.replace(WHITESPACE, " ").trim()

/** Scopes the [PersonNameNormalizer] block, so its calls cannot leak into a nested block. */
@DslMarker
@Target(AnnotationTarget.CLASS)
annotation class NameNormalizerDsl

/**
 * The built-in name rules: tidy up a person's name, leave every other name alone.
 *
 * For an entity that carries one of the person labels, the normalizer
 * - turns "Last, First" around,
 * - removes a leading title, with or without a dot,
 * - removes a trailing suffix, with or without a dot,
 * - removes a single capital letter standing between two longer name parts.
 *
 * Every other name only has its whitespace collapsed. Letters are recognized in any script, so
 * "José M García" is treated like "Lynda M Coker". Titles and suffixes are matched ignoring case.
 *
 * Each `with` step returns a new normalizer and leaves this one unchanged.
 *
 * Java:
 * ```java
 * NameNormalizer normalizer = PersonNameNormalizer.english()
 *     .withPersonLabels("Person", "Contact", "Employee")
 *     .withTitles("Herr", "Frau", "Dr", "Prof")
 *     .withMiddleInitialsKept();
 * ```
 *
 * Kotlin:
 * ```kotlin
 * val normalizer = PersonNameNormalizer {
 *     personLabels("Person", "Contact", "Employee")
 *     titles("Herr", "Frau", "Dr", "Prof")
 *     stripMiddleInitials = false
 * }
 * ```
 */
class PersonNameNormalizer private constructor(private val rules: Rules) : NameNormalizer {

    /**
     * @property personLabels the labels that mark a person, as comparison keys; null for every name
     * @property titles the titles removed from the start of a name, without their dot
     * @property suffixes the suffixes removed from the end of a name, without their dot
     * @property stripMiddleInitials whether a single-letter middle initial is removed
     * @property reorderLastFirst whether "Last, First" is turned around
     */
    private data class Rules(
        val personLabels: Set<String>?,
        val titles: List<String>,
        val suffixes: List<String>,
        val stripMiddleInitials: Boolean,
        val reorderLastFirst: Boolean,
    )

    private val title: Regex? = edgePattern(rules.titles) { "^(?:$it)\\.?\\s+" }
    private val suffix: Regex? = edgePattern(rules.suffixes) { "\\s+(?:$it)\\.?$" }

    override fun normalize(name: String, labels: Collection<String>): String {
        var s = collapseWhitespace(name)
        val people = rules.personLabels
        if (people != null && labels.none { labelKey(it) in people }) return s
        if (rules.reorderLastFirst) {
            REVERSED.matchEntire(s)?.let { s = "${it.groupValues[2]} ${it.groupValues[1]}".trim() }
        }
        title?.let { s = s.replace(it, "") }
        suffix?.let { s = s.replace(it, "") }
        if (rules.stripMiddleInitials) s = s.replace(MIDDLE_INITIAL, " ")
        return collapseWhitespace(s)
    }

    /**
     * Applies the person rules to entities that carry one of these labels.
     *
     * Labels are compared ignoring case and any package prefix, so `com.acme.Person` counts as
     * `Person`.
     *
     * @param labels the labels that mark a person in your schema
     * @return a normalizer for those labels
     */
    fun withPersonLabels(vararg labels: String): PersonNameNormalizer =
        PersonNameNormalizer(rules.copy(personLabels = labels.map(::labelKey).toSet()))

    /**
     * Applies the person rules to every name, whatever its entity's labels.
     *
     * This suits data where every entity is a person. It is also how names were normalized
     * before the rules could be chosen.
     *
     * @return a normalizer that treats every name as a person's name
     */
    fun forEveryName(): PersonNameNormalizer = PersonNameNormalizer(rules.copy(personLabels = null))

    /**
     * Replaces the titles removed from the start of a name.
     *
     * @param titles the titles, without a trailing dot; none to keep every title
     * @return a normalizer with those titles
     */
    fun withTitles(vararg titles: String): PersonNameNormalizer =
        PersonNameNormalizer(rules.copy(titles = cleaned(titles)))

    /**
     * Replaces the suffixes removed from the end of a name.
     *
     * @param suffixes the suffixes, without a trailing dot; none to keep every suffix
     * @return a normalizer with those suffixes
     */
    fun withSuffixes(vararg suffixes: String): PersonNameNormalizer =
        PersonNameNormalizer(rules.copy(suffixes = cleaned(suffixes)))

    /**
     * Keeps single-letter middle initials, so "Lynda M Coker" and "Lynda Coker" stay different.
     *
     * @return a normalizer that leaves middle initials in place
     */
    fun withMiddleInitialsKept(): PersonNameNormalizer =
        PersonNameNormalizer(rules.copy(stripMiddleInitials = false))

    /**
     * Keeps "Last, First" as written. Use this where a comma in a name does not mean the family
     * name comes first.
     *
     * @return a normalizer that leaves the order of a name alone
     */
    fun withNameOrderKept(): PersonNameNormalizer =
        PersonNameNormalizer(rules.copy(reorderLastFirst = false))

    override fun equals(other: Any?): Boolean = other is PersonNameNormalizer && other.rules == rules

    override fun hashCode(): Int = rules.hashCode()

    override fun toString(): String = "PersonNameNormalizer(" +
        "personLabels=${rules.personLabels ?: "every name"}, titles=${rules.titles}, suffixes=${rules.suffixes}, " +
        "stripMiddleInitials=${rules.stripMiddleInitials}, reorderLastFirst=${rules.reorderLastFirst})"

    /**
     * The settings of a [PersonNameNormalizer] block. It starts from [english] and exists only
     * inside the block.
     */
    @NameNormalizerDsl
    class Builder internal constructor() {
        private var normalizer: PersonNameNormalizer = ENGLISH

        /** Whether a single-letter middle initial is removed. */
        var stripMiddleInitials: Boolean = true

        /** Whether "Last, First" is turned around. */
        var reorderLastFirst: Boolean = true

        /** Applies the person rules to entities that carry one of these labels. */
        fun personLabels(vararg labels: String) {
            normalizer = normalizer.withPersonLabels(*labels)
        }

        /** Applies the person rules to every name, whatever its entity's labels. */
        fun everyName() {
            normalizer = normalizer.forEveryName()
        }

        /** Replaces the titles removed from the start of a name. */
        fun titles(vararg titles: String) {
            normalizer = normalizer.withTitles(*titles)
        }

        /** Replaces the suffixes removed from the end of a name. */
        fun suffixes(vararg suffixes: String) {
            normalizer = normalizer.withSuffixes(*suffixes)
        }

        /**
         * Turns the settings of the block into a normalizer, through the same steps a chain uses.
         *
         * @return the normalizer the block describes
         */
        internal fun build(): PersonNameNormalizer {
            var built = normalizer
            if (!stripMiddleInitials) built = built.withMiddleInitialsKept()
            if (!reorderLastFirst) built = built.withNameOrderKept()
            return built
        }
    }

    companion object {

        /** The label that marks a person unless you choose others. */
        const val PERSON_LABEL: String = "Person"

        private val REVERSED = Regex("^([^,]+),\\s*(.+)$")

        // One capital letter, with or without a dot, between two name parts of two or more
        // characters.
        private val MIDDLE_INITIAL = Regex("(?<=[\\p{L}\\p{N}_]{2})\\s+\\p{Lu}\\.?\\s+(?=[\\p{L}\\p{N}_]{2})")

        private val ENGLISH = PersonNameNormalizer(
            Rules(
                personLabels = setOf(labelKey(PERSON_LABEL)),
                titles = listOf("Mr", "Mrs", "Ms", "Dr", "Prof"),
                suffixes = listOf("Jr", "Sr", "II", "III", "IV"),
                stripMiddleInitials = true,
                reorderLastFirst = true,
            ),
        )

        /**
         * The rules for names as English-language directories and mail systems write them,
         * applied to entities labelled `Person`.
         *
         * Titles: Mr, Mrs, Ms, Dr, Prof. Suffixes: Jr, Sr, II, III, IV.
         *
         * @return the English rules, ready to use or to adjust with the `with` steps
         */
        @JvmStatic
        fun english(): PersonNameNormalizer = ENGLISH

        /**
         * Builds a normalizer from a block of settings, starting from [english].
         *
         * @param block the settings to change
         * @return the normalizer
         */
        @JvmSynthetic
        operator fun invoke(block: Builder.() -> Unit): PersonNameNormalizer = Builder().apply(block).build()

        /**
         * Reduces a label to the form labels are compared in.
         *
         * @param label a type label, with or without a package
         * @return the label's simple name in lower case
         */
        private fun labelKey(label: String): String = label.substringAfterLast('.').lowercase()

        /**
         * Tidies a list of titles or suffixes given by a caller.
         *
         * @param words the words as given
         * @return the words trimmed, without a trailing dot, with blanks and repeats removed
         */
        private fun cleaned(words: Array<out String>): List<String> =
            words.map { it.trim().removeSuffix(".") }.filter { it.isNotEmpty() }.distinct()

        /**
         * Builds the pattern that finds one of the words at one end of a name, ignoring case.
         * Longer words are tried first, so "Mrs" is not read as "Mr".
         *
         * @param words the titles or suffixes to find
         * @param pattern wraps the alternatives in the rest of the pattern
         * @return the pattern, or null when there are no words to find
         */
        private fun edgePattern(words: List<String>, pattern: (String) -> String): Regex? =
            if (words.isEmpty()) null
            else Regex(pattern(words.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }), RegexOption.IGNORE_CASE)
    }
}

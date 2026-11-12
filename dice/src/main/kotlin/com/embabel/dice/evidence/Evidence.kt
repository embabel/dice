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
package com.embabel.dice.evidence

import com.embabel.common.core.types.ZeroToOne
import com.embabel.dice.spi.ConflictType
import java.util.Collections
import java.util.Objects

/**
 * The method that worked a conclusion out from recorded claims, with its rule and settings.
 *
 * Each `with` method returns a copy. Parameters keep the order they were added in.
 *
 * ```java
 * DerivationRule rule = DerivationRule.of("prolog")
 *     .withRuleId("grandparent")
 *     .withExpression("grandparent(X, Y)")
 *     .withParameter("maxHops", "3");
 * ```
 */
class DerivationRule private constructor(
    /** What did the deriving, such as `abstraction` or `prolog`. */
    val method: String,
    /** The rule's own id. Null when the rule has none. */
    val ruleId: String?,
    /** The rule text, such as `grandparent(X, Y)`. Null when there is none. */
    val expression: String?,
    parameters: Map<String, String>,
) {

    /** Named settings of the method, such as `maxHops` to `3`, in the order added. */
    val parameters: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(parameters))

    init {
        require(method.isNotBlank()) { "method must not be blank" }
        require(ruleId == null || ruleId.isNotBlank()) { "ruleId must not be blank" }
        require(expression == null || expression.isNotBlank()) { "expression must not be blank" }
        require(this.parameters.keys.all { it.isNotBlank() }) { "parameter names must not be blank" }
    }

    /**
     * Returns a copy with [ruleId] set.
     *
     * @param ruleId the rule's own id
     * @return the updated rule
     * @throws IllegalArgumentException if [ruleId] is blank
     */
    fun withRuleId(ruleId: String): DerivationRule = DerivationRule(method, ruleId, expression, parameters)

    /**
     * Returns a copy with [expression] set.
     *
     * @param expression the rule text
     * @return the updated rule
     * @throws IllegalArgumentException if [expression] is blank
     */
    fun withExpression(expression: String): DerivationRule = DerivationRule(method, ruleId, expression, parameters)

    /**
     * Returns a copy with the parameter [name] set to [value]. A parameter already present keeps its place.
     *
     * @param name the parameter name
     * @param value the parameter value. May be empty.
     * @return the updated rule
     * @throws IllegalArgumentException if [name] is blank
     */
    fun withParameter(name: String, value: String): DerivationRule =
        DerivationRule(method, ruleId, expression, parameters + (name to value))

    /** Two rules are equal when every property is equal. */
    override fun equals(other: Any?): Boolean =
        other is DerivationRule && method == other.method && ruleId == other.ruleId &&
            expression == other.expression && parameters == other.parameters

    /** Hash code over the same properties that [equals] compares. */
    override fun hashCode(): Int = Objects.hash(method, ruleId, expression, parameters)

    /** Returns the method, rule id, expression and parameters. */
    override fun toString(): String =
        "DerivationRule(method=$method, ruleId=$ruleId, expression=$expression, parameters=$parameters)"

    companion object {

        /** The `abstraction` method, with no rule id, expression or parameters. */
        @JvmField
        val ABSTRACTION: DerivationRule = DerivationRule("abstraction", null, null, emptyMap())

        /**
         * Creates a rule for a method, with no rule id, expression or parameters.
         *
         * @param method what did the deriving. Trimmed before it is stored.
         * @return the rule
         * @throws IllegalArgumentException if [method] is blank
         */
        @JvmStatic
        fun of(method: String): DerivationRule = DerivationRule(method.trim(), null, null, emptyMap())
    }
}

/**
 * The result of a lookup. Its kind says what the lookup found.
 *
 * | Kind | The lookup found |
 * |---|---|
 * | [Recorded] | claims as stored |
 * | [Derived] | a conclusion worked out from recorded claims |
 * | [Missing] | nothing to return |
 * | [Conflicting] | claims that disagree with each other |
 *
 * The interface is sealed, so a Java `switch` and a Kotlin `when` over the four kinds need no fallback branch.
 *
 * ```java
 * String text = switch (evidence) {
 *     case Evidence.Recorded recorded -> recorded.getItems().size() + " claims";
 *     case Evidence.Derived derived -> derived.getConclusion();
 *     case Evidence.Missing missing -> "nothing found";
 *     case Evidence.Conflicting conflicting -> "claims disagree";
 * };
 * ```
 *
 * ```kotlin
 * val text = when (evidence) {
 *     is Evidence.Recorded -> "${evidence.items.size} claims"
 *     is Evidence.Derived -> evidence.conclusion
 *     is Evidence.Missing -> "nothing found"
 *     is Evidence.Conflicting -> "claims disagree"
 * }
 * ```
 */
sealed interface Evidence {

    /**
     * Claims found as stored. Never empty.
     *
     * A caller gets this kind when the lookup returns at least one stored claim and does not report a disagreement.
     */
    class Recorded private constructor(items: List<EvidenceItem>) : Evidence {

        /** The claims found, in the order the lookup returned them. */
        val items: List<EvidenceItem> = java.util.List.copyOf(items)

        init {
            require(this.items.isNotEmpty()) { "items must not be empty" }
        }

        /** Two values are equal when their items are equal. */
        override fun equals(other: Any?): Boolean = other is Recorded && items == other.items

        /** Hash code over the same properties that [equals] compares. */
        override fun hashCode(): Int = items.hashCode()

        /** Returns the items. */
        override fun toString(): String = "Recorded(items=$items)"

        internal companion object {

            /**
             * Calls the private constructor for the [Evidence.recorded] factory.
             *
             * @param items the claims found
             * @return the new value
             */
            @JvmSynthetic
            internal fun create(items: List<EvidenceItem>): Recorded = Recorded(items)
        }
    }

    /**
     * A conclusion worked out from recorded claims.
     *
     * A caller gets this kind when the lookup answers with a conclusion that a rule, such as an abstraction or
     * a logic query, worked out from claims in the store.
     */
    class Derived private constructor(
        /** The conclusion text. */
        val conclusion: String,
        /** Confidence in the conclusion, from 0.0 to 1.0. */
        val confidence: ZeroToOne,
        /** The method and rule that worked the conclusion out. */
        val rule: DerivationRule,
        inputIds: List<String>,
        inputs: List<EvidenceItem>,
        /** The citation of the conclusion when it is itself stored as a claim. Null otherwise. */
        val citation: Citation?,
    ) : Evidence {

        /** Ids of the claims the conclusion was worked out from. Never empty. */
        val inputIds: List<String> = java.util.List.copyOf(inputIds)

        /** The claims named in [inputIds] that were loaded. May be empty. */
        val inputs: List<EvidenceItem> = java.util.List.copyOf(inputs)

        init {
            require(conclusion.isNotBlank()) { "conclusion must not be blank" }
            require(confidence in 0.0..1.0) { "confidence must be between 0.0 and 1.0, was $confidence" }
            require(this.inputIds.isNotEmpty()) { "inputIds must not be empty" }
            require(this.inputIds.all { it.isNotBlank() }) { "input ids must not be blank" }
        }

        /**
         * Returns a copy with [inputs] replaced. The list is copied. [inputIds] is left as it is.
         *
         * @param inputs the loaded claims the conclusion was worked out from
         * @return the updated value
         */
        fun withInputs(inputs: List<EvidenceItem>): Derived =
            Derived(conclusion, confidence, rule, inputIds, inputs, citation)

        /**
         * Returns a copy with [citation] set.
         *
         * @param citation the citation of the stored conclusion
         * @return the updated value
         */
        fun withCitation(citation: Citation): Derived =
            Derived(conclusion, confidence, rule, inputIds, inputs, citation)

        /**
         * Two values are equal when every property is equal. Confidence compares by its boxed value,
         * so `0.0` and `-0.0` differ.
         */
        override fun equals(other: Any?): Boolean =
            other is Derived && conclusion == other.conclusion && Objects.equals(confidence, other.confidence) &&
                rule == other.rule && inputIds == other.inputIds && inputs == other.inputs &&
                citation == other.citation

        /** Hash code over the same properties that [equals] compares. */
        override fun hashCode(): Int = Objects.hash(conclusion, confidence, rule, inputIds, inputs, citation)

        /** Returns the confidence, rule, input ids and citation. The conclusion text is left out. */
        override fun toString(): String =
            "Derived(confidence=$confidence, rule=$rule, inputIds=$inputIds, citation=$citation)"

        internal companion object {

            /**
             * Calls the private constructor for the [Evidence.derived] factory.
             *
             * @param conclusion the conclusion text
             * @param confidence confidence in the conclusion
             * @param rule the method and rule that worked the conclusion out
             * @param inputIds ids of the claims the conclusion was worked out from
             * @return the new value, with no inputs and no citation
             */
            @JvmSynthetic
            internal fun create(
                conclusion: String,
                confidence: ZeroToOne,
                rule: DerivationRule,
                inputIds: List<String>,
            ): Derived = Derived(conclusion, confidence, rule, inputIds, emptyList(), null)
        }
    }

    /**
     * Nothing to return for this search.
     *
     * A caller gets this kind when the lookup has no claim and no conclusion to return. That includes a search
     * whose results were all filtered out.
     */
    class Missing private constructor(
        /** What the lookup searched for. */
        val searched: Searched,
    ) : Evidence {

        /** Two values are equal when their searches are equal. */
        override fun equals(other: Any?): Boolean = other is Missing && searched == other.searched

        /** Hash code over the same properties that [equals] compares. */
        override fun hashCode(): Int = searched.hashCode()

        /** Returns the search. */
        override fun toString(): String = "Missing(searched=$searched)"

        internal companion object {

            /**
             * Calls the private constructor for the [Evidence.missing] factory.
             *
             * @param searched what the lookup searched for
             * @return the new value
             */
            @JvmSynthetic
            internal fun create(searched: Searched): Missing = Missing(searched)
        }
    }

    /**
     * Claims that disagree with each other.
     *
     * A caller gets this kind when the lookup finds stored claims that cannot all hold, and reports how they
     * conflict in [conflictType].
     */
    class Conflicting private constructor(
        items: List<EvidenceItem>,
        /** How the claims conflict. */
        val conflictType: ConflictType,
    ) : Evidence {

        /** The claims that disagree. At least two, each with a different claim id. */
        val items: List<EvidenceItem> = java.util.List.copyOf(items)

        init {
            require(this.items.size >= 2) { "a conflict needs at least two items, was ${this.items.size}" }
            require(this.items.map { it.citation.propositionId }.toSet().size == this.items.size) {
                "conflicting items must have different claim ids"
            }
        }

        /** Two values are equal when their items and conflict types are equal. */
        override fun equals(other: Any?): Boolean =
            other is Conflicting && items == other.items && conflictType == other.conflictType

        /** Hash code over the same properties that [equals] compares. */
        override fun hashCode(): Int = Objects.hash(items, conflictType)

        /** Returns the items and the conflict type. */
        override fun toString(): String = "Conflicting(items=$items, conflictType=$conflictType)"

        internal companion object {

            /**
             * Calls the private constructor for the [Evidence.conflicting] factory.
             *
             * @param items the claims that disagree
             * @param conflictType how the claims conflict
             * @return the new value
             */
            @JvmSynthetic
            internal fun create(items: List<EvidenceItem>, conflictType: ConflictType): Conflicting =
                Conflicting(items, conflictType)
        }
    }

    companion object {

        /**
         * Creates a [Recorded] value. The list is copied.
         *
         * @param items the claims found
         * @return the new value
         * @throws IllegalArgumentException if [items] is empty
         */
        @JvmStatic
        fun recorded(items: List<EvidenceItem>): Recorded = Recorded.create(items)

        /**
         * Creates a [Derived] value with no loaded inputs and no citation. The list is copied.
         *
         * @param conclusion the conclusion text
         * @param confidence confidence in the conclusion, from 0.0 to 1.0
         * @param rule the method and rule that worked the conclusion out
         * @param inputIds ids of the claims the conclusion was worked out from
         * @return the new value
         * @throws IllegalArgumentException if [conclusion] is blank, [confidence] is NaN or outside 0.0 to 1.0,
         *   or [inputIds] is empty or holds a blank id
         */
        @JvmStatic
        fun derived(conclusion: String, confidence: ZeroToOne, rule: DerivationRule, inputIds: List<String>): Derived =
            Derived.create(conclusion, confidence, rule, inputIds)

        /**
         * Creates a [Missing] value.
         *
         * @param searched what the lookup searched for
         * @return the new value
         */
        @JvmStatic
        fun missing(searched: Searched): Missing = Missing.create(searched)

        /**
         * Creates a [Conflicting] value. The list is copied.
         *
         * @param items the claims that disagree
         * @param conflictType how the claims conflict
         * @return the new value
         * @throws IllegalArgumentException if [items] has fewer than two items or two items with the same claim id
         */
        @JvmStatic
        fun conflicting(items: List<EvidenceItem>, conflictType: ConflictType): Conflicting =
            Conflicting.create(items, conflictType)

        /**
         * Recorded when there is at least one item, otherwise Missing for what was searched.
         *
         * @param items the claims found. May be empty.
         * @param searched what the lookup searched for
         * @return a [Recorded] holding [items], or a [Missing] holding [searched] when [items] is empty
         */
        @JvmStatic
        fun of(items: List<EvidenceItem>, searched: Searched): Evidence =
            if (items.isEmpty()) missing(searched) else recorded(items)
    }
}

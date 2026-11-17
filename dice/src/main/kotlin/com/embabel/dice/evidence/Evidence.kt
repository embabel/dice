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
import org.jetbrains.annotations.ApiStatus
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
@ApiStatus.Experimental
class DerivationRule private constructor(
    /** The derivation method, such as `prolog`. Lower case. */
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

    /** Two rules are equal when every property is equal. Equality ignores order. */
    override fun equals(other: Any?): Boolean =
        other is DerivationRule && method == other.method && ruleId == other.ruleId &&
            expression == other.expression && parameters == other.parameters

    override fun hashCode(): Int = Objects.hash(method, ruleId, expression, parameters)

    /**
     * Returns the method, the rule id, whether there is an expression, and the parameter names. The expression
     * and the parameter values are left out.
     */
    override fun toString(): String =
        "DerivationRule(method=$method, ruleId=$ruleId, hasExpression=${expression != null}, " +
            "parameterNames=${parameters.keys})"

    companion object {

        /**
         * Creates a rule for a method, with no rule id, expression or parameters.
         *
         * @param method the derivation method. Trimmed and lower-cased before it is stored.
         * @return the rule
         * @throws IllegalArgumentException if [method] is blank
         */
        @JvmStatic
        fun of(method: String): DerivationRule = DerivationRule(method.trim().lowercase(), null, null, emptyMap())
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
 * | [Superseded] | a claim that replaced earlier claims |
 *
 * [Conflicting] and [Superseded] are both a [Disagreement], which carries the conflict type. The interface is
 * sealed, so a Java `switch` and a Kotlin `when` over the five kinds need no fallback branch.
 *
 * ```java
 * String text = switch (evidence) {
 *     case Evidence.Recorded recorded -> recorded.getItems().size() + " claims";
 *     case Evidence.Derived derived -> derived.getConclusion();
 *     case Evidence.Missing missing -> "nothing to return";
 *     case Evidence.Conflicting conflicting -> "claims disagree";
 *     case Evidence.Superseded superseded -> "a claim was replaced";
 * };
 * ```
 *
 * ```kotlin
 * val text = when (evidence) {
 *     is Evidence.Recorded -> "${evidence.items.size} claims"
 *     is Evidence.Derived -> evidence.conclusion
 *     is Evidence.Missing -> "nothing to return"
 *     is Evidence.Conflicting -> "claims disagree"
 *     is Evidence.Superseded -> "a claim was replaced"
 * }
 * ```
 */
@ApiStatus.Experimental
sealed interface Evidence {

    /**
     * Claims found as stored. Never empty.
     *
     * A caller gets this kind when the lookup returns at least one stored claim and does not report a disagreement.
     */
    class Recorded private constructor(items: List<EvidenceItem>) : Evidence {

        /** The claims found, in the order the lookup returned them. */
        val items: List<EvidenceItem> = EvidenceChecks.copyOf("items", items)

        init {
            require(this.items.isNotEmpty()) { "items must not be empty" }
        }

        override fun equals(other: Any?): Boolean =
            other is Recorded && EvidenceItem.sameItems(items, other.items)

        override fun hashCode(): Int = items.hashCode()

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
     * A conclusion worked out at lookup time, with no stored claim behind it.
     *
     * A caller gets this kind when an [EvidenceProvider] answers a rule or logic query with a conclusion it
     * worked out from claims in the store. A stored claim, including an abstraction, converts to an
     * [EvidenceItem] in [Recorded].
     */
    class Derived private constructor(
        /** The conclusion text. */
        val conclusion: String,
        /** The confidence the rule computed, from 0.0 to 1.0. */
        val confidence: ZeroToOne,
        /** The method and rule that worked the conclusion out. */
        val rule: DerivationRule,
        inputIds: List<String>,
        inputs: List<EvidenceItem>,
    ) : Evidence {

        /** Ids of the claims the conclusion was worked out from. Never empty. */
        val inputIds: List<String> = EvidenceChecks.copyOf("inputIds", inputIds)

        /**
         * The loaded input claims, as the caller supplied them. May be empty. Each one cites an id in [inputIds],
         * and no two cite the same id.
         */
        val inputs: List<EvidenceItem> = EvidenceChecks.copyOf("inputs", inputs)

        init {
            require(conclusion.isNotBlank()) { "conclusion must not be blank" }
            require(confidence in 0.0..1.0) { "confidence must be between 0.0 and 1.0, was $confidence" }
            require(this.inputIds.isNotEmpty()) { "inputIds must not be empty" }
            require(this.inputIds.all { it.isNotBlank() }) { "input ids must not be blank" }
            EvidenceChecks.requireLoadedClaimsNamed("inputs", this.inputs, "inputIds", this.inputIds)
        }

        /**
         * Returns a copy with [inputs] replaced. The list is copied and stored as given. [inputIds] is left as it is.
         * Each input must cite an id in [inputIds]. A subset of the ids is accepted.
         *
         * @param inputs the loaded claims the conclusion was worked out from
         * @return the updated value
         * @throws IllegalArgumentException if [inputs] holds a null, an input cites an id outside [inputIds], or two
         *   inputs cite the same id
         */
        fun withInputs(inputs: List<EvidenceItem>): Derived = Derived(conclusion, confidence, rule, inputIds, inputs)

        /**
         * Two values are equal when every property is equal. Confidences compare by their boxed value,
         * so `0.0` and `-0.0` differ.
         */
        override fun equals(other: Any?): Boolean =
            other is Derived && conclusion == other.conclusion && Objects.equals(confidence, other.confidence) &&
                rule == other.rule && inputIds == other.inputIds && EvidenceItem.sameItems(inputs, other.inputs)

        override fun hashCode(): Int = Objects.hash(conclusion, confidence, rule, inputIds, inputs)

        /** Returns the confidence, rule and input ids. The conclusion text is left out. */
        override fun toString(): String = "Derived(confidence=$confidence, rule=$rule, inputIds=$inputIds)"

        internal companion object {

            /**
             * Calls the private constructor for the [Evidence.derived] factory.
             *
             * @param conclusion the conclusion text
             * @param confidence confidence in the conclusion
             * @param rule the method and rule that worked the conclusion out
             * @param inputIds ids of the claims the conclusion was worked out from
             * @return the new value, with no inputs
             */
            @JvmSynthetic
            internal fun create(
                conclusion: String,
                confidence: ZeroToOne,
                rule: DerivationRule,
                inputIds: List<String>,
            ): Derived = Derived(conclusion, confidence, rule, inputIds, emptyList())
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

        override fun equals(other: Any?): Boolean = other is Missing && searched == other.searched

        override fun hashCode(): Int = searched.hashCode()

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
     * Stored claims that do not agree: either they conflict, or one replaced the others.
     *
     * A Java `switch` and a Kotlin `when` over the two kinds need no fallback branch.
     *
     * ```java
     * String text = switch (disagreement) {
     *     case Evidence.Conflicting conflicting -> conflicting.getItems().size() + " claims disagree";
     *     case Evidence.Superseded superseded -> superseded.getPrevious().size() + " claims replaced";
     * };
     * ```
     */
    sealed interface Disagreement : Evidence {

        /** How the claims disagree. */
        val conflictType: ConflictType
    }

    /**
     * Claims that disagree with each other.
     *
     * A caller gets this kind when the lookup finds stored claims that cannot all hold, and reports how they
     * conflict in [conflictType]. The conflict type is [ConflictType.Contradiction] or [ConflictType.Custom].
     */
    class Conflicting private constructor(
        items: List<EvidenceItem>,
        /** How the claims conflict: [ConflictType.Contradiction] or [ConflictType.Custom]. */
        override val conflictType: ConflictType,
    ) : Disagreement {

        /** The claims that disagree. At least two, and no two cite the same claim id in the same context. */
        val items: List<EvidenceItem> = EvidenceChecks.copyOf("items", items)

        init {
            require(this.items.size >= 2) { "a conflict needs at least two items, was ${this.items.size}" }
            val conflict = when (conflictType) {
                ConflictType.Contradiction, is ConflictType.Custom -> true
                ConflictType.Revision, ConflictType.WorldProgression -> false
            }
            require(conflict) { "conflictType $conflictType is a replacement, use Evidence.superseded" }
            EvidenceChecks.requireDistinctClaims(
                this.items.mapIndexed { index, item -> "items[$index]" to item },
            )
        }

        override fun equals(other: Any?): Boolean =
            other is Conflicting && conflictType == other.conflictType && EvidenceItem.sameItems(items, other.items)

        override fun hashCode(): Int = Objects.hash(items, conflictType)

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

    /**
     * A claim that replaced earlier claims.
     *
     * A caller gets this kind when a stored claim holds in place of claims it corrected or that the world moved
     * past. The conflict type is [ConflictType.Revision] or [ConflictType.WorldProgression]. A custom conflict
     * is [Conflicting], because a custom label does not say which claim holds.
     */
    class Superseded private constructor(
        /** The claim that holds. */
        val current: EvidenceItem,
        previous: List<EvidenceItem>,
        /** How [current] replaced the first claim in [previous]: [ConflictType.Revision] or [ConflictType.WorldProgression]. */
        override val conflictType: ConflictType,
    ) : Disagreement {

        /**
         * The claims [current] replaced, newest first, so the first one is the claim [current] replaced. Never
         * empty, and no two of these and [current] cite the same claim id in the same context.
         */
        val previous: List<EvidenceItem> = EvidenceChecks.copyOf("previous", previous)

        init {
            require(this.previous.isNotEmpty()) { "previous must not be empty" }
            val conflictKind = when (conflictType) {
                ConflictType.Revision, ConflictType.WorldProgression -> null
                ConflictType.Contradiction -> "a conflict"
                is ConflictType.Custom -> "a custom conflict"
            }
            require(conflictKind == null) { "conflictType $conflictType is $conflictKind, use Evidence.conflicting" }
            EvidenceChecks.requireDistinctClaims(
                listOf("current" to current) +
                    this.previous.mapIndexed { index, item -> "previous[$index]" to item },
            )
        }

        override fun equals(other: Any?): Boolean =
            other is Superseded && conflictType == other.conflictType &&
                EvidenceItem.sameItems(listOf(current) + previous, listOf(other.current) + other.previous)

        override fun hashCode(): Int = Objects.hash(current, previous, conflictType)

        override fun toString(): String =
            "Superseded(current=$current, previous=$previous, conflictType=$conflictType)"

        internal companion object {

            /**
             * Calls the private constructor for the [Evidence.superseded] factory.
             *
             * @param current the claim that holds
             * @param previous the claims it replaced, newest first
             * @param conflictType how the current claim replaced the first previous claim
             * @return the new value
             */
            @JvmSynthetic
            internal fun create(
                current: EvidenceItem,
                previous: List<EvidenceItem>,
                conflictType: ConflictType,
            ): Superseded = Superseded(current, previous, conflictType)
        }
    }

    companion object {

        /**
         * Creates a [Recorded] value. The list is copied.
         *
         * @param items the claims found
         * @return the new value
         * @throws IllegalArgumentException if [items] is empty or holds a null
         */
        @JvmStatic
        fun recorded(items: List<EvidenceItem>): Recorded = Recorded.create(items)

        /**
         * Creates a [Derived] value with no loaded inputs. The list is copied.
         *
         * @param conclusion the conclusion text
         * @param confidence confidence in the conclusion, from 0.0 to 1.0
         * @param rule the method and rule that worked the conclusion out
         * @param inputIds ids of the claims the conclusion was worked out from
         * @return the new value
         * @throws IllegalArgumentException if [conclusion] is blank, [confidence] is NaN or outside 0.0 to 1.0,
         *   or [inputIds] is empty or holds a null or blank id
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
         * @throws IllegalArgumentException if [items] holds a null, has fewer than two items, or has two items
         *   that cite the same claim id in the same context, or if [conflictType] is [ConflictType.Revision] or
         *   [ConflictType.WorldProgression], which [superseded] takes
         */
        @JvmStatic
        fun conflicting(items: List<EvidenceItem>, conflictType: ConflictType): Conflicting =
            Conflicting.create(items, conflictType)

        /**
         * Creates a [Superseded] value. The list is copied.
         *
         * @param current the claim that holds
         * @param previous the claims [current] replaced, newest first
         * @param conflictType how [current] replaced the first claim in [previous]
         * @return the new value
         * @throws IllegalArgumentException if [previous] is empty or holds a null, if two of [current] and
         *   [previous] cite the same claim id in the same context, or if [conflictType] is
         *   [ConflictType.Contradiction] or a [ConflictType.Custom], which [conflicting] takes
         */
        @JvmStatic
        fun superseded(current: EvidenceItem, previous: List<EvidenceItem>, conflictType: ConflictType): Superseded =
            Superseded.create(current, previous, conflictType)

        /**
         * Returns [Recorded] when [items] is not empty, otherwise [Missing] for [searched].
         *
         * @param items the claims found. May be empty.
         * @param searched what the lookup searched for
         * @return a [Recorded] holding [items], or a [Missing] holding [searched] when [items] is empty
         * @throws IllegalArgumentException if [items] holds a null
         */
        @JvmStatic
        fun of(items: List<EvidenceItem>, searched: Searched): Evidence =
            if (items.isEmpty()) missing(searched) else recorded(items)
    }
}

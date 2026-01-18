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
package com.embabel.dice.common.validation

import com.embabel.agent.core.PropertyValidationRule

/**
 * Type-safe validation rule for entity mentions.
 *
 * Each rule is a strongly-typed class with compile-time checking.
 * Rules can be composed using [AllOf] and [AnyOf] combinators.
 *
 * Implements [PropertyValidationRule] to enable type-safe schema definitions:
 * ```kotlin
 * ValidatedPropertyDefinition(
 *     name = "name",
 *     validationRules = listOf(
 *         NoVagueReferences(),
 *         LengthConstraint(maxLength = 150)
 *     )
 * )
 * ```
 */
sealed interface MentionValidationRule : PropertyValidationRule {
    /**
     * Validate a mention span.
     * @param mention The text span to validate
     * @return Validation result indicating success or failure with reason
     */
    fun validate(mention: String): ValidationResult

    /**
     * Human-readable description of this rule.
     */
    override val description: String

    // Implement PropertyValidationRule interface
    override fun isValid(mention: String): Boolean = validate(mention).isValid

    override fun failureReason(mention: String): String? = validate(mention).reason
}

/**
 * Rejects empty or whitespace-only mentions.
 */
object NotBlank : MentionValidationRule {
    override val description: String = "Must not be blank"

    override fun validate(mention: String): ValidationResult {
        return if (mention.isBlank()) {
            ValidationResult.Invalid("Mention is blank or whitespace-only")
        } else {
            ValidationResult.Valid
        }
    }
}

/**
 * Rejects mentions starting with vague demonstrative pronouns or articles.
 *
 * @property excludedStarters List of lowercase prefixes to reject (e.g., "this", "that", "the")
 */
data class NoVagueReferences(
    val excludedStarters: List<String> = listOf("this", "that", "these", "those", "the", "an", "a")
) : MentionValidationRule {

    override val description: String =
        "Must not start with: ${excludedStarters.joinToString(", ")}"

    override fun validate(mention: String): ValidationResult {
        val lowerMention = mention.lowercase().trim()
        val matched = excludedStarters.find { starter ->
            lowerMention.startsWith(starter.lowercase().trim() + " ") ||
            lowerMention == starter.lowercase().trim()
        }

        return if (matched != null) {
            ValidationResult.Invalid("Vague reference: starts with '$matched'")
        } else {
            ValidationResult.Valid
        }
    }
}

/**
 * Enforces minimum and/or maximum length constraints.
 *
 * @property minLength Minimum allowed length in characters (null = no minimum)
 * @property maxLength Maximum allowed length in characters (null = no maximum)
 */
data class LengthConstraint(
    val minLength: Int? = null,
    val maxLength: Int? = null
) : MentionValidationRule {

    init {
        require(minLength == null || minLength > 0) { "minLength must be positive" }
        require(maxLength == null || maxLength > 0) { "maxLength must be positive" }
        require(minLength == null || maxLength == null || minLength <= maxLength) {
            "minLength ($minLength) must be <= maxLength ($maxLength)"
        }
    }

    override val description: String = buildString {
        append("Length must be ")
        when {
            minLength != null && maxLength != null -> append("between $minLength and $maxLength")
            minLength != null -> append("at least $minLength")
            maxLength != null -> append("at most $maxLength")
        }
        append(" characters")
    }

    override fun validate(mention: String): ValidationResult {
        val length = mention.length

        minLength?.let {
            if (length < it) {
                return ValidationResult.Invalid("Too short: $length < $it chars")
            }
        }

        maxLength?.let {
            if (length > it) {
                return ValidationResult.Invalid("Too long: $length > $it chars")
            }
        }

        return ValidationResult.Valid
    }
}

/**
 * Validates against a regex pattern.
 *
 * @property pattern The regex pattern to match
 * @property patternDescription Human-readable description of the pattern
 */
data class PatternConstraint(
    val pattern: Regex,
    val patternDescription: String = pattern.pattern
) : MentionValidationRule {

    override val description: String = "Must match pattern: $patternDescription"

    override fun validate(mention: String): ValidationResult {
        return if (mention.matches(pattern)) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid("Does not match pattern: $patternDescription")
        }
    }
}

/**
 * Requires minimum number of words (useful for person names, etc.).
 *
 * @property minWords Minimum number of words required
 * @property wordSeparator Regex pattern for splitting words (default: whitespace)
 */
data class MinWordCount(
    val minWords: Int,
    val wordSeparator: Regex = Regex("\\s+")
) : MentionValidationRule {

    init {
        require(minWords > 0) { "minWords must be positive" }
    }

    override val description: String = "Must contain at least $minWords word(s)"

    override fun validate(mention: String): ValidationResult {
        val wordCount = mention.trim().split(wordSeparator).filter { it.isNotEmpty() }.size
        return if (wordCount >= minWords) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid("Only $wordCount word(s), need at least $minWords")
        }
    }
}

/**
 * Rejects generic nouns for specific entity types.
 *
 * Example: "The person" or "A company" are too vague for entity names.
 *
 * @property invalidPatterns List of generic patterns to reject
 */
data class EntityTypeGuard(
    val invalidPatterns: List<String>
) : MentionValidationRule {

    override val description: String =
        "Must not match generic patterns: ${invalidPatterns.joinToString(", ")}"

    override fun validate(mention: String): ValidationResult {
        val lowerMention = mention.lowercase().trim()
        val matched = invalidPatterns.find { pattern ->
            lowerMention == pattern.lowercase().trim() ||
            lowerMention.startsWith(pattern.lowercase().trim() + " ")
        }

        return if (matched != null) {
            ValidationResult.Invalid("Generic reference: matches pattern '$matched'")
        } else {
            ValidationResult.Valid
        }
    }
}

/**
 * Composite rule that requires ALL child rules to pass.
 *
 * @property rules List of rules that must all pass
 */
data class AllOf(
    val rules: List<MentionValidationRule>
) : MentionValidationRule {

    constructor(vararg rules: MentionValidationRule) : this(rules.toList())

    override val description: String =
        "Must satisfy all: ${rules.joinToString("; ") { it.description }}"

    override fun validate(mention: String): ValidationResult {
        rules.forEach { rule ->
            val result = rule.validate(mention)
            if (!result.isValid) {
                return result  // Fail fast on first invalid
            }
        }
        return ValidationResult.Valid
    }
}

/**
 * Composite rule that requires AT LEAST ONE child rule to pass.
 *
 * @property rules List of rules where at least one must pass
 */
data class AnyOf(
    val rules: List<MentionValidationRule>
) : MentionValidationRule {

    constructor(vararg rules: MentionValidationRule) : this(rules.toList())

    override val description: String =
        "Must satisfy at least one: ${rules.joinToString(" OR ") { it.description }}"

    override fun validate(mention: String): ValidationResult {
        val failures = mutableListOf<String>()
        rules.forEach { rule ->
            val result = rule.validate(mention)
            if (result.isValid) {
                return ValidationResult.Valid  // Success on first valid
            }
            result.reason?.let { failures.add(it) }
        }
        return ValidationResult.Invalid("None of the alternatives passed: ${failures.joinToString("; ")}")
    }
}

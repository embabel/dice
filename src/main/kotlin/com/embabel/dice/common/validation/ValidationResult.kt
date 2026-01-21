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

/**
 * Validation result for entity mentions.
 *
 * This sealed class represents the outcome of validating an entity mention
 * against a set of validation rules.
 */
sealed class ValidationResult {
    /**
     * Indicates the mention passed validation.
     */
    object Valid : ValidationResult()

    /**
     * Indicates the mention failed validation.
     * @property reason Human-readable explanation of why validation failed
     */
    data class Invalid(val reason: String) : ValidationResult()

    /**
     * True if this result represents a valid mention.
     */
    val isValid: Boolean get() = this is Valid
}

/**
 * Extension property to get the failure reason if invalid, null if valid.
 */
val ValidationResult.reason: String? get() = (this as? ValidationResult.Invalid)?.reason

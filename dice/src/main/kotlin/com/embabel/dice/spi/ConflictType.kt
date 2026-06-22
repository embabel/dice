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
package com.embabel.dice.spi

/**
 * Classifies the nature of a conflict between an incoming proposition and an
 * existing one that it appears to contradict.
 *
 * Not every clash is a genuine contradiction. The same surface-level disagreement
 * ("Alice works at Globex" vs "Alice works at Acme") can mean different things:
 * the world genuinely changed ([WorldProgression]), the new statement is a
 * correction of the old ([Revision]), or the two statements are mutually
 * exclusive truths ([Contradiction]). Distinguishing these lets the reviser
 * decide whether to supersede, replace, or flag a clash rather than always
 * treating it as an irreconcilable contradiction.
 *
 * This is a closed discriminated union so consumers can exhaustively pattern-match,
 * with [Custom] as the open escape hatch for domain-specific classifications.
 */
sealed interface ConflictType {

    /**
     * The incoming proposition corrects/replaces the existing one as a more
     * accurate statement of the same fact (e.g. fixing a typo'd value).
     */
    data object Revision : ConflictType

    /**
     * The two propositions are mutually exclusive truths and genuinely conflict.
     * This is the conservative default classification.
     */
    data object Contradiction : ConflictType

    /**
     * The world changed: both statements were true at different times, and the
     * incoming one reflects the newer state (e.g. Alice changed employers).
     */
    data object WorldProgression : ConflictType

    /**
     * A consumer-defined conflict classification.
     *
     * @property label A stable, human-readable label identifying the custom kind
     */
    data class Custom(val label: String) : ConflictType {
        init {
            require(label.isNotBlank()) { "Custom conflict label must not be blank" }
        }
    }
}

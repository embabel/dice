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
package com.embabel.dice.projection.memory.collector

import com.embabel.dice.proposition.Proposition

/**
 * Small shared bits the signal scorers in this package all need. Kept to exactly what's reused
 * more than once — nothing here is a general-purpose utility belt.
 */

/**
 * Stable ids for a proposition's entity mentions: the resolved id when the mention was linked to
 * a known entity, otherwise a `type:span` fallback so two unresolved mentions of "the same" thing
 * still compare equal.
 */
internal fun mentionIds(p: Proposition): Set<String> =
    p.mentions.map { it.resolvedId ?: "${it.type}:${it.span}" }.toSet()

/** A proposition's text, lower-cased and trimmed, so surface comparisons ignore case and padding. */
internal fun normalizedText(p: Proposition): String = p.text.trim().lowercase()

/**
 * Jaccard overlap of two sets: shared members over combined members, in 0..1. Two empty sets
 * score 0, not 1 — there's no positive evidence of overlap, just an absence of data on both
 * sides. Callers that want "no data" to mean something different (e.g. abstain rather than 0)
 * need to check emptiness themselves before calling this.
 */
internal fun jaccard(a: Set<*>, b: Set<*>): Double {
    if (a.isEmpty() && b.isEmpty()) return 0.0
    val intersection = a.intersect(b).size
    val union = a.size + b.size - intersection
    return if (union == 0) 0.0 else intersection.toDouble() / union
}

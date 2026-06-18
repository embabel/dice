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

import com.embabel.dice.common.support.DefaultCanonicalNameSelector

/**
 * Picks the best canonical display name from a set of candidates for a
 * single entity. The complement of
 * [NormalizedNameCandidateSearcher.normalizeName] — which normalizes
 * names for *matching* — this is for normalizing what we *write* to
 * the graph.
 *
 * **Why a separate concept from the searchers.** The matching path
 * needs every candidate to hash to the same bucket so a query for
 * `"Dr. Hunter Hordern"` finds an entity stored as `"Hunter Hordern"`.
 * The write path needs to *choose* one of the surface forms it has
 * seen for that entity to store as the canonical name. Different
 * direction, different problem.
 *
 * **Typical inputs.** Consumers gather every name they have for an
 * entity from every source they know — email From-headers, file
 * metadata, free-text mentions, account display names, parsed
 * identifiers (e.g. `"hunter.hordern@…"` → `"Hunter Hordern"`) — and
 * pass them in. The selector picks the form most likely to be the
 * canonical human-recognizable rendering.
 *
 * **Scoring intuition (default implementation).**
 *  - Multi-word forms beat single-word (full name vs first-only).
 *  - Mixed-case beats all-lower (proper noun vs username).
 *  - All-caps gets a small penalty (yelling vs natural-case).
 *  - Forms containing `@` are heavily penalized — that's a raw
 *    email, not a name.
 *  - Forms containing digits get a small penalty — likely a handle.
 *  - Length is a tiebreaker.
 *
 * All scoring weights are constants in [com.embabel.dice.common.support.DefaultCanonicalNameSelector];
 * tune by extension or by providing your own implementation.
 *
 * **Strategy, not utility.** Following the Hibernate-style naming-strategy
 * pattern: define an interface, provide a sensible default, let
 * downstreams swap. For most callers `DefaultCanonicalNameSelector` is
 * what you want; provide your own only when domain-specific scoring
 * is necessary (e.g. preferring company-internal names over public
 * forms).
 */
interface CanonicalNameSelector {

    /**
     * Pick the best canonical name from [candidates]. Inputs are
     * normalized via [NormalizedNameCandidateSearcher.normalizeName]
     * before scoring so titles ("Dr.", "Mr.") and suffixes ("Jr.")
     * don't artificially boost. Blank, null, and effectively-identical
     * candidates are deduped.
     *
     * @return the best canonical form, or `null` when every candidate
     *   is null/blank after cleaning. Callers should fall back to
     *   whatever name they already had on the entity in that case.
     */
    fun select(candidates: Collection<String?>): String?

    /** Convenience varargs overload — same semantics as [select]. */
    fun select(vararg candidates: String?): String? = select(candidates.toList())

    companion object {
        /** The shared default implementation. */
        @JvmStatic
        fun default(): CanonicalNameSelector = DefaultCanonicalNameSelector
    }
}

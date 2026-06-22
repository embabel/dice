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

import com.embabel.dice.proposition.Proposition

/**
 * Policy SPI: assigns a trust score to a proposition.
 *
 * Trust is an *advisory* signal layered on top of confidence: it lets consumers
 * weight propositions by how much they trust the source ([authorityTier]) and how
 * a clash with existing knowledge was classified ([conflictType]), without DICE
 * core hard-coding any particular trust model. The cached score is later consumed
 * by query filters and gates (a downstream phase).
 *
 * The returned score is a value in `[0.0, 1.0]`, where `1.0` is fully trusted and
 * `0.0` is untrusted. Out-of-range scores from custom implementations are advisory
 * only and must not be relied on for access control.
 *
 * Consumers may supply their own implementation; the shipped default
 * [NeutralTrustScorer] is deliberately trivial so behaviour is preserved until a
 * consumer opts into a real trust model.
 */
fun interface TrustScorer {

    /**
     * Score the trustworthiness of a proposition.
     *
     * This is the single abstract method of the SAM interface and so takes all
     * arguments explicitly; the [score] extension overloads supply `null` defaults
     * for the optional [authorityTier] and [conflictType] at call sites.
     *
     * @param proposition The proposition to score
     * @param authorityTier Source-authority tier for the proposition, or `null` if
     *   authority is not being considered
     * @param conflictType Classification of any conflict this proposition was
     *   involved in, or `null` if it was not in conflict
     * @return a trust score in `[0.0, 1.0]` (`1.0` == fully trusted)
     */
    fun score(
        proposition: Proposition,
        authorityTier: AuthorityTier?,
        conflictType: ConflictType?,
    ): Double

    /**
     * Score a proposition when you have no authority or conflict context to pass.
     *
     * Declared as a default method rather than an extension function so Java callers
     * can invoke it as a normal instance method.
     *
     * @param proposition The proposition to score
     * @return a trust score in `[0.0, 1.0]`
     */
    fun score(proposition: Proposition): Double =
        score(proposition, null, null)

    /**
     * Score a proposition given its authority tier but no conflict context.
     *
     * Declared as a default method rather than an extension function so Java callers
     * can invoke it as a normal instance method.
     *
     * @param proposition The proposition to score
     * @param authorityTier Source-authority tier, or `null`
     * @return a trust score in `[0.0, 1.0]`
     */
    fun score(
        proposition: Proposition,
        authorityTier: AuthorityTier?,
    ): Double = score(proposition, authorityTier, null)
}

/**
 * Default [TrustScorer] that trusts every proposition equally, returning `1.0`
 * regardless of the proposition, its authority tier, or any conflict.
 *
 * This is the behaviour-preserving default: with it installed, trust scoring is a
 * no-op and no proposition is ever down-weighted. It exists so the trust seam is
 * present without changing any current behaviour until a consumer supplies a real
 * scorer.
 */
object NeutralTrustScorer : TrustScorer {

    override fun score(
        proposition: Proposition,
        authorityTier: AuthorityTier?,
        conflictType: ConflictType?,
    ): Double = 1.0
}

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
package com.embabel.dice.storage

import com.embabel.agent.core.ContextId
import com.embabel.dice.common.DiceMetadataKeys
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionQuery.OrderBy
import com.embabel.dice.proposition.PropositionStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Cross-backend contract for [PropositionStore.query]: the same [PropositionQuery] must return the
 * same results whichever backend is injected. Each subclass supplies a store and inherits the whole
 * suite, so a divergence between the in-memory and graph implementations fails at authoring time
 * instead of in production.
 *
 * The focus is the two filters whose semantics are easy to drift per backend:
 * - the trust gate ([PropositionQuery.minTrustScore]), which is fail-open — an unscored proposition
 *   passes — and
 * - the "revised" clock, which keys on last-touched (the later of content/metadata revision), not the
 *   decay anchor, so a metadata-only touch counts as a revision.
 */
abstract class AbstractPropositionStoreContractTest {

    /** A fresh, empty store for the test about to run. */
    protected abstract fun store(): PropositionStore

    private val ctx = ContextId("contract-ctx")

    private fun prop(
        text: String,
        trustScore: Double? = null,
        contentRevised: Instant = Instant.now(),
        metadataRevised: Instant = contentRevised,
    ): Proposition = Proposition(
        contextId = ctx,
        text = text,
        mentions = emptyList(),
        confidence = 0.9,
        contentRevised = contentRevised,
        metadataRevised = metadataRevised,
        metadata = trustScore?.let { mapOf(DiceMetadataKeys.TRUST_SCORE to it) } ?: emptyMap(),
    )

    // ---- minTrustScore: honoured identically, and fail-open for unscored propositions ----

    @Test
    fun `minTrustScore excludes low-trust but keeps high-trust and unscored`() {
        val store = store()
        val low = store.save(prop("low trust", trustScore = 0.2))
        val high = store.save(prop("high trust", trustScore = 0.8))
        val unscored = store.save(prop("unscored"))

        val result = store.query(PropositionQuery.forContextId(ctx).withMinTrustScore(0.5))

        // high passes the gate; unscored passes (fail-open); low is dropped.
        assertEquals(setOf(high.id, unscored.id), result.map { it.id }.toSet())
        assertEquals(false, result.any { it.id == low.id })
    }

    // ---- "revised" semantics key on lastTouched (a metadata-only touch counts as a revision) ----

    private val old = Instant.parse("2020-01-01T00:00:00Z")
    private val mid = Instant.parse("2021-01-01T00:00:00Z")
    private val recent = Instant.parse("2023-01-01T00:00:00Z")
    private val cutoff = Instant.parse("2022-01-01T00:00:00Z")

    @Test
    fun `revisedAfter filters on lastTouched, so a recent metadata touch keeps an old-content proposition`() {
        val store = store()
        // Content last changed in 2020 but metadata was touched in 2023 → lastTouched = 2023.
        val touched = store.save(prop("old content, recent metadata", contentRevised = old, metadataRevised = recent))
        // Both clocks in 2021 → lastTouched = 2021.
        val stale = store.save(prop("untouched since 2021", contentRevised = mid, metadataRevised = mid))

        val result = store.query(PropositionQuery.forContextId(ctx).withRevisedAfter(cutoff))

        // Keyed on contentRevised, `touched` (2020) would be wrongly excluded; on lastTouched it stays.
        assertEquals(setOf(touched.id), result.map { it.id }.toSet())
        assertEquals(false, result.any { it.id == stale.id })
    }

    @Test
    fun `REVISED_DESC orders by lastTouched`() {
        val store = store()
        val touched = store.save(prop("old content, recent metadata", contentRevised = old, metadataRevised = recent))
        val stale = store.save(prop("untouched since 2021", contentRevised = mid, metadataRevised = mid))

        val result = store.query(PropositionQuery.forContextId(ctx).withOrderBy(OrderBy.REVISED_DESC))

        // lastTouched: touched (2023) before stale (2021). Keyed on contentRevised this would reverse.
        assertEquals(listOf(touched.id, stale.id), result.map { it.id })
    }

    // ---- count(query): a filtered count agrees with the size of the same query's results ----

    @Test
    fun `count(query) equals the number of matching propositions on both backends`() {
        val store = store()
        store.save(propWithConfidence("high a", 0.9))
        store.save(propWithConfidence("high b", 0.8))
        store.save(propWithConfidence("low", 0.2))

        val query = PropositionQuery.forContextId(ctx).withMinEffectiveConfidence(0.5)
        // The pushed-down count must equal what a full materialise-and-size would return.
        assertEquals(store.query(query).size, store.count(query))
        assertEquals(2, store.count(query))
    }

    @Test
    fun `count(query) is capped by the query's limit on both backends`() {
        val store = store()
        store.save(propWithConfidence("high a", 0.9))
        store.save(propWithConfidence("high b", 0.8))
        store.save(propWithConfidence("high c", 0.7))

        // Three matches, but the query caps at two — count must mirror query().size, which truncates
        // to the limit. A server-side count that ignored the cap would return 3.
        val query = PropositionQuery.forContextId(ctx).withMinEffectiveConfidence(0.5).withLimit(2)
        assertEquals(store.query(query).size, store.count(query))
        assertEquals(2, store.count(query))
    }

    // ---- belowEffectiveConfidence: strict upper bound, mirroring minEffectiveConfidence ----

    @Test
    fun `belowEffectiveConfidence keeps only propositions strictly under the threshold`() {
        val store = store()
        val low = store.save(propWithConfidence("low", 0.2))
        val high = store.save(propWithConfidence("high", 0.9))

        // Fresh saves (age 0) don't decay, so effective confidence == confidence on both backends.
        val result = store.query(PropositionQuery.forContextId(ctx).withBelowEffectiveConfidence(0.5))

        assertEquals(setOf(low.id), result.map { it.id }.toSet())
        assertEquals(false, result.any { it.id == high.id })
    }

    // ---- keywordOverlap: case-insensitive term overlap, ranked by overlap count ----

    @Test
    fun `keywordOverlap matches case-insensitively and ranks by overlap`() {
        val store = store()
        val canva = store.save(propWithConfidence("I love Canva design", 0.9))
        val both = store.save(propWithConfidence("Canva and Figma both", 0.9))
        store.save(propWithConfidence("something unrelated", 0.9))

        val base = PropositionQuery.forContextId(ctx)
        // Tokens are lower-case; the propositions capitalise "Canva"/"Figma" — matching must ignore case.
        val result = store.keywordOverlap(base, listOf("canva", "figma"), limit = 10)

        assertEquals(setOf(canva.id, both.id), result.map { it.id }.toSet())
        // `both` contains two of the tokens, so it must rank ahead of the single-token hit.
        assertEquals(both.id, result.first().id)
    }

    @Test
    fun `keywordOverlap returns nothing for empty tokens`() {
        val store = store()
        store.save(propWithConfidence("anything", 0.9))
        assertEquals(emptyList<String>(), store.keywordOverlap(PropositionQuery.forContextId(ctx), emptyList(), 10).map { it.id })
    }

    private fun propWithConfidence(text: String, confidence: Double): Proposition =
        Proposition(contextId = ctx, text = text, mentions = emptyList(), confidence = confidence)
}

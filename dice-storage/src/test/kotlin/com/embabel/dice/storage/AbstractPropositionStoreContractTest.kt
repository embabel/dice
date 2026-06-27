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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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

    // ---- saveIfAbsent: insert-once by id, identical across backends ----

    @Test
    fun `saveIfAbsent stores a new proposition and then skips the same id without overwriting`() {
        val store = store()
        val first = store.saveIfAbsent(prop("first write"))
        // Absent → stored and returned.
        assertNotNull(first)
        assertEquals(1, store.count())

        // Same id, different content → must not overwrite, must return null.
        val again = store.saveIfAbsent(first!!.copy(text = "second write"))
        assertNull(again)
        assertEquals(1, store.count())
        assertEquals("first write", store.findById(first.id)!!.text)
    }

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
}

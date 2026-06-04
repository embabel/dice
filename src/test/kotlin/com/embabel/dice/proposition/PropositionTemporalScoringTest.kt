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
package com.embabel.dice.proposition

import com.embabel.agent.core.ContextId
import com.embabel.dice.temporal.TemporalMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * `effectiveConfidenceAt`: a fact is *dated* iff its [TemporalMetadata.validFrom]
 * is set. Dated facts use their valid window (closed = no decay, open-ended =
 * still decays); everything else — including facts that only carry a source date
 * ([TemporalMetadata.observedAt]) — decays from `revised`.
 */
class PropositionTemporalScoringTest {

    private val ctx = ContextId("t")
    private fun t(iso: String): Instant = Instant.parse(iso)
    private fun prop(confidence: Double = 0.8, decay: Double = 0.5) =
        Proposition(contextId = ctx, text = "x", mentions = emptyList(), confidence = confidence, decay = decay)

    @Test
    fun `no temporal block decays from revised`() {
        val p = prop().copy(revised = t("2026-01-01T00:00:00Z"))
        assertEquals(0.8, p.effectiveConfidenceAt(t("2026-01-01T00:00:00Z")), 1e-9)
        assertTrue(p.effectiveConfidenceAt(t("2026-12-01T00:00:00Z")) < 0.8)
    }

    @Test
    fun `source-date-only fact (observedAt, no validFrom) is NOT dated and still decays`() {
        // The embabel/dice#26 case: we know when it was said, not a valid window.
        val p = prop().copy(revised = t("2026-06-03T00:00:00Z"))
            .withTemporal(TemporalMetadata(observedAt = t("2026-04-20T00:00:00Z")))
        // validFrom is null → decaying, anchored on revised, exactly like no temporal block.
        assertEquals(0.8, p.effectiveConfidenceAt(t("2026-06-03T00:00:00Z")), 1e-9)
        assertTrue(p.effectiveConfidenceAt(t("2026-12-01T00:00:00Z")) < 0.8)
    }

    @Test
    fun `closed window is full inside, zero outside, and never decays`() {
        val p = prop().withTemporal(
            TemporalMetadata(validFrom = t("2018-01-01T00:00:00Z"), validTo = t("2022-01-01T00:00:00Z")),
        )
        assertEquals(0.8, p.effectiveConfidenceAt(t("2020-06-01T00:00:00Z")), 1e-9)   // inside → full, no decay
        assertEquals(0.0, p.effectiveConfidenceAt(t("2017-06-01T00:00:00Z")), 1e-9)   // before
        assertEquals(0.0, p.effectiveConfidenceAt(t("2022-01-01T00:00:00Z")), 1e-9)   // validTo exclusive
        assertEquals(0.0, p.effectiveConfidenceAt(t("2023-01-01T00:00:00Z")), 1e-9)   // after
    }

    @Test
    fun `open-ended window is full at start and decays afterwards`() {
        val p = prop().withTemporal(TemporalMetadata(validFrom = t("2023-01-01T00:00:00Z")))
        assertEquals(0.8, p.effectiveConfidenceAt(t("2023-01-01T00:00:00Z")), 1e-9)
        assertTrue(p.effectiveConfidenceAt(t("2024-06-01T00:00:00Z")) < 0.8)            // still decays
        assertEquals(0.0, p.effectiveConfidenceAt(t("2022-06-01T00:00:00Z")), 1e-9)     // before start
    }

    @Test
    fun `invalidation zeroes a fact in any mode`() {
        val dated = prop().withTemporal(
            TemporalMetadata(validFrom = t("2023-01-01T00:00:00Z"), invalidatedAt = t("2024-01-01T00:00:00Z")),
        )
        assertTrue(dated.effectiveConfidenceAt(t("2023-06-01T00:00:00Z")) > 0.0)
        assertEquals(0.0, dated.effectiveConfidenceAt(t("2024-06-01T00:00:00Z")), 1e-9)

        // invalidation also zeroes a non-dated (decaying) fact
        val decaying = prop().withTemporal(TemporalMetadata(observedAt = t("2023-01-01T00:00:00Z"), invalidatedAt = t("2024-01-01T00:00:00Z")))
        assertEquals(0.0, decaying.effectiveConfidenceAt(t("2024-06-01T00:00:00Z")), 1e-9)
    }
}

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
package com.embabel.dice.temporal

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Every field of [TemporalMetadata] is optional — you attach only what is known.
 */
class TemporalMetadataNullableTest {

    private fun t(iso: String) = Instant.parse(iso)

    @Test
    fun `source-date-only metadata is valid (observedAt, no valid window)`() {
        val tm = TemporalMetadata(observedAt = t("2026-04-20T00:00:00Z"))
        // No valid window → it's "current" at any time (only invalidation/window could gate it).
        assertTrue(tm.isCurrentAsOf(t("2026-06-01T00:00:00Z")))
        assertTrue(tm.isCurrentAsOf(t("2020-01-01T00:00:00Z")))
    }

    @Test
    fun `null validFrom means no lower bound`() {
        val tm = TemporalMetadata(validTo = t("2022-01-01T00:00:00Z"))  // "valid until 2022, start unknown"
        assertTrue(tm.isCurrentAsOf(t("1999-01-01T00:00:00Z")))   // no lower bound
        assertFalse(tm.isCurrentAsOf(t("2022-06-01T00:00:00Z")))  // past validTo
    }

    @Test
    fun `empty metadata is current everywhere except where invalidated`() {
        assertTrue(TemporalMetadata().isCurrentAsOf(t("2026-01-01T00:00:00Z")))
        val invalidated = TemporalMetadata(invalidatedAt = t("2026-01-01T00:00:00Z"))
        assertFalse(invalidated.isCurrentAsOf(t("2026-02-01T00:00:00Z")))
    }
}

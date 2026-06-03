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

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.temporal.ChronoUnit

class TemporalMetadataTest {

    private val t0 = Instant.parse("2026-01-01T00:00:00Z")
    private val t1 = Instant.parse("2026-02-01T00:00:00Z")
    private val t2 = Instant.parse("2026-03-01T00:00:00Z")
    private val t3 = Instant.parse("2026-04-01T00:00:00Z")

    @Test
    fun `isCurrentAsOf true inside open-ended window`() {
        val tm = TemporalMetadata(observedAt = t0, validFrom = t1)
        assertTrue(tm.isCurrentAsOf(t1))
        assertTrue(tm.isCurrentAsOf(t2))
    }

    @Test
    fun `isCurrentAsOf false before validFrom`() {
        val tm = TemporalMetadata(observedAt = t0, validFrom = t1)
        assertFalse(tm.isCurrentAsOf(t0))
    }

    @Test
    fun `isCurrentAsOf respects closed window`() {
        val tm = TemporalMetadata(observedAt = t0, validFrom = t1, validTo = t2)
        assertTrue(tm.isCurrentAsOf(t1))
        assertFalse(tm.isCurrentAsOf(t2), "validTo is exclusive")
        assertFalse(tm.isCurrentAsOf(t3))
    }

    @Test
    fun `invalidatedAt short-circuits currency`() {
        val tm = TemporalMetadata(observedAt = t0, validFrom = t1, invalidatedAt = t2)
        assertTrue(tm.isCurrentAsOf(t1))
        assertFalse(tm.isCurrentAsOf(t2), "at the invalidation instant it is no longer current")
        assertFalse(tm.isCurrentAsOf(t3))
    }

    @Test
    fun `isCurrent uses now`() {
        val tm = TemporalMetadata(
            observedAt = t0,
            validFrom = Instant.now().minus(1, ChronoUnit.DAYS),
        )
        assertTrue(tm.isCurrent())
    }

    @Test
    fun `validTo before validFrom rejected`() {
        assertThrows<IllegalArgumentException> {
            TemporalMetadata(observedAt = t0, validFrom = t2, validTo = t1)
        }
    }

    @Test
    fun `validTo equal to validFrom allowed`() {
        val tm = TemporalMetadata(observedAt = t0, validFrom = t1, validTo = t1)
        assertEquals(t1, tm.validTo)
    }
}

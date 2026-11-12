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
package com.embabel.dice.store

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for [SimilarityScore], the one similarity scale every store reports.
 *
 * Clauses:
 * - E1 each factory converts its native scale to a cosine.
 * - E2 a value outside the range is rejected.
 * - E3 rounding within one millionth of an end is moved to that end.
 * - E4 scores compare by closeness and equal scores hash the same.
 */
class SimilarityScoreTest {

    @Test
    fun `E1 each factory converts its native scale to a cosine`() {
        assertEquals(0.83, SimilarityScore.ofCosine(0.83).cosine)
        assertEquals(0.75, SimilarityScore.ofCosineDistance(0.25).cosine)
        assertEquals(1.0, SimilarityScore.ofCosineDistance(0.0).cosine)
        assertEquals(-1.0, SimilarityScore.ofCosineDistance(2.0).cosine)
        assertEquals(1.0, SimilarityScore.ofUnitInterval(1.0).cosine)
        assertEquals(0.0, SimilarityScore.ofUnitInterval(0.5).cosine)
        assertEquals(-1.0, SimilarityScore.ofUnitInterval(0.0).cosine)
        assertEquals(0.75, SimilarityScore.ofCosine(0.5).unitInterval)
    }

    @Test
    fun `E2 a value outside the range is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { SimilarityScore.ofCosine(1.5) }
        assertThrows(IllegalArgumentException::class.java) { SimilarityScore.ofCosine(-1.5) }
        assertThrows(IllegalArgumentException::class.java) { SimilarityScore.ofCosine(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { SimilarityScore.ofCosine(Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { SimilarityScore.ofCosineDistance(2.5) }
        assertThrows(IllegalArgumentException::class.java) { SimilarityScore.ofCosineDistance(-0.5) }
        assertThrows(IllegalArgumentException::class.java) { SimilarityScore.ofCosineDistance(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { SimilarityScore.ofUnitInterval(1.2) }
        assertThrows(IllegalArgumentException::class.java) { SimilarityScore.ofUnitInterval(-0.2) }
    }

    @Test
    fun `E3 rounding within one millionth of an end is moved to that end`() {
        assertEquals(1.0, SimilarityScore.ofCosine(1.0000001).cosine)
        assertEquals(-1.0, SimilarityScore.ofCosine(-1.0000001).cosine)
        assertEquals(-1.0, SimilarityScore.ofCosineDistance(2.0000001).cosine)
        assertEquals(1.0, SimilarityScore.ofCosineDistance(-0.0000001).cosine)
        assertEquals(1.0, SimilarityScore.ofUnitInterval(1.0000001).cosine)
        assertThrows(IllegalArgumentException::class.java) { SimilarityScore.ofCosine(1.00001) }
        assertThrows(IllegalArgumentException::class.java) { SimilarityScore.ofCosineDistance(2.00001) }
        assertThrows(IllegalArgumentException::class.java) { SimilarityScore.ofUnitInterval(1.00001) }
    }

    @Test
    fun `E4 scores compare by closeness and equal scores hash the same`() {
        assertTrue(SimilarityScore.ofCosine(0.9) > SimilarityScore.ofCosine(0.7))
        val sorted = listOf(0.7, -0.2, 0.9).map { SimilarityScore.ofCosine(it) }.sorted().map { it.cosine }
        assertEquals(listOf(-0.2, 0.7, 0.9), sorted)
        assertEquals(SimilarityScore.ofCosine(0.0), SimilarityScore.ofCosine(-0.0))
        assertEquals(SimilarityScore.ofCosine(0.0).hashCode(), SimilarityScore.ofCosine(-0.0).hashCode())
        assertNotEquals(SimilarityScore.ofCosine(0.5), SimilarityScore.ofCosine(0.6))
    }
}

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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checks the conversion between a COSINE index score and a cosine similarity.
 *
 * - E1 For every `s` in 0..1, `toCosine(s)` equals `2.0 * s - 1.0` bit for bit.
 * - E2 For `s` outside 0..1, infinities included, `toCosine(s)` is the nearest end, -1.0 or 1.0.
 * - E3 `toCosine(NaN)` is `NaN` and does not throw.
 * - E4 For `c` in (0, 1], `toIndexFloor(c)` equals `(1.0 + c) / 2.0` bit for bit. For `c` above 1,
 *   infinity included, it returns `c`.
 */
class CosineIndexScoresTest {

    @Test
    fun `E1 an index score from 0 to 1 converts to twice the score minus one exactly`() {
        for (s in listOf(0.0, 0.25, 0.5, 0.75, 1.0, 0.1, 0.9, 0.3333333333333333, 0.8421)) {
            assertEquals((2.0 * s - 1.0).toBits(), CosineIndexScores.toCosine(s).toBits(), "score $s")
        }
    }

    @Test
    fun `E2 an index score outside 0 to 1 converts to the nearest end`() {
        assertEquals(1.0, CosineIndexScores.toCosine(1.0000002))
        assertEquals(-1.0, CosineIndexScores.toCosine(-0.0000002))
        assertEquals(1.0, CosineIndexScores.toCosine(Double.POSITIVE_INFINITY))
        assertEquals(-1.0, CosineIndexScores.toCosine(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `E3 a NaN index score stays NaN`() {
        assertTrue(CosineIndexScores.toCosine(Double.NaN).isNaN())
    }

    @Test
    fun `E4 a cosine floor converts to the scale of the index`() {
        assertEquals(((1.0 + 0.6) / 2.0).toBits(), CosineIndexScores.toIndexFloor(0.6).toBits())
        assertEquals(1.0, CosineIndexScores.toIndexFloor(1.0))
        assertEquals(1.5, CosineIndexScores.toIndexFloor(1.5))
        assertEquals(Double.POSITIVE_INFINITY, CosineIndexScores.toIndexFloor(Double.POSITIVE_INFINITY))
    }
}

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
package com.embabel.dice.proposition.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Locale

class ScopedReadRulesTest {

    @Test
    fun `code point order puts U+FF61 before U+10000 where UTF-16 order puts it after`() {
        val halfwidthStop = "｡"
        val linearB = "𐀀"
        assertTrue(ScopedReadRules.compareCodePoints(halfwidthStop, linearB) < 0)
        assertTrue(ScopedReadRules.compareCodePoints(linearB, halfwidthStop) > 0)
        assertTrue(halfwidthStop > linearB, "Kotlin's own comparison works on UTF-16 units")
        assertEquals(listOf(halfwidthStop, linearB), listOf(linearB, halfwidthStop).sortedWith(ScopedReadRules.CODE_POINT_ORDER))
    }

    @Test
    fun `code point order breaks a shared prefix by length and treats equal strings as equal`() {
        assertTrue(ScopedReadRules.compareCodePoints("a", "ab") < 0)
        assertTrue(ScopedReadRules.compareCodePoints("ab", "a") > 0)
        assertEquals(0, ScopedReadRules.compareCodePoints("𐀀x", "𐀀x"))
        assertTrue(ScopedReadRules.CODE_POINT_ORDER.compare("ctx", "ctx-2") < 0)
    }

    @Test
    fun `cosine of float vectors scores equal, orthogonal, zero and opposite vectors`() {
        assertEquals(1.0, ScopedReadRules.cosineSimilarity(floatArrayOf(1f, 2f, 3f), floatArrayOf(1f, 2f, 3f)), 1e-12)
        assertEquals(0.0, ScopedReadRules.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)))
        assertEquals(0.0, ScopedReadRules.cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)))
        assertEquals(-1.0, ScopedReadRules.cosineSimilarity(floatArrayOf(1f, 2f), floatArrayOf(-1f, -2f)), 1e-12)
        assertEquals(1.0, ScopedReadRules.cosineSimilarity(floatArrayOf(Float.MAX_VALUE), floatArrayOf(Float.MAX_VALUE)))
        assertThrows<IllegalArgumentException> { ScopedReadRules.cosineSimilarity(floatArrayOf(1f), floatArrayOf(1f, 0f)) }
    }

    @Test
    fun `cosine of graph store embeddings scores equal, orthogonal, zero and opposite vectors`() {
        assertEquals(1.0, ScopedReadRules.cosineSimilarity(listOf(1.0, 2.0, 3.0), listOf(1.0, 2.0, 3.0)), 1e-12)
        assertEquals(0.0, ScopedReadRules.cosineSimilarity(listOf(1.0, 0.0), listOf(0.0, 1.0)))
        assertEquals(0.0, ScopedReadRules.cosineSimilarity(listOf(0.0, 0.0), listOf(1.0, 1.0)))
        assertEquals(-1.0, ScopedReadRules.cosineSimilarity(listOf(1.0, 2.0), listOf(-1.0, -2.0)), 1e-12)
        assertEquals(
            ScopedReadRules.cosineSimilarity(floatArrayOf(0.3f, -0.7f, 0.1f), floatArrayOf(0.2f, 0.4f, -0.9f)),
            ScopedReadRules.cosineSimilarity(listOf(0.3f.toDouble(), -0.7f.toDouble(), 0.1f.toDouble()), listOf(0.2f.toDouble(), 0.4f.toDouble(), -0.9f.toDouble())),
        )
        assertThrows<IllegalArgumentException> { ScopedReadRules.cosineSimilarity(listOf(1.0), listOf(1.0, 0.0)) }
    }

    @Test
    fun `keyword tokens trim, lowercase in the root locale, drop blanks and keep the first of each duplicate`() {
        assertEquals(listOf("alice", "bob"), ScopedReadRules.keywordTokens(listOf("  ALICE ", "", "   ", "Bob", "alice", "BOB ")))
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr"))
            assertEquals(listOf("i"), ScopedReadRules.keywordTokens(listOf("I")))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `keyword hits count normalised tokens found in the text whatever its case`() {
        assertEquals(2, ScopedReadRules.keywordHits("Alice met BOB in Paris", listOf("alice", "bob", "carol")))
        assertEquals(0, ScopedReadRules.keywordHits("Alice", emptyList()))
    }
}

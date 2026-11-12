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
package com.embabel.dice.conformance

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract for [ConformanceEmbeddings.embed].
 *
 * R1 The marker is the text after the last " @".
 * E1 A marker of `none` embeds to all zeros.
 * E2 A decimal marker `d` embeds to cos(d) and sin(d) in components 0 and 1, and zeros after them.
 * E3 Two texts with equal markers and different labels embed to equal vectors.
 * E4 A text with no " @", or a marker that is neither `none` nor a number, is rejected, and the message contains the text.
 * E5 A dimension count below MIN_DIMENSIONS is rejected.
 */
class ConformanceEmbeddingsTest {

    private val tolerance = 1e-6f

    @Test
    fun `E1 a text without direction embeds to all zeros`() {
        val vector = ConformanceEmbeddings.embed(ConformanceEmbeddings.textWithoutDirection("query"), 5)

        assertArrayEquals(FloatArray(5), vector)
    }

    @Test
    fun `E2 a text at an angle embeds to the cosine and sine of that angle`() {
        val dimensions = 5
        // Expected cos and sine of each angle, in degrees.
        val expected = listOf(
            Triple(0.0, 1f, 0f),
            Triple(60.0, 0.5f, 0.8660254f),
            Triple(90.0, 0f, 1f),
            Triple(180.0, -1f, 0f),
        )
        expected.forEach { (degrees, cosine, sine) ->
            val vector = ConformanceEmbeddings.embed(ConformanceEmbeddings.text("query", degrees), dimensions)

            assertEquals(dimensions, vector.size)
            assertEquals(cosine, vector[0], tolerance)
            assertEquals(sine, vector[1], tolerance)
            assertArrayEquals(FloatArray(dimensions - 2), vector.copyOfRange(2, dimensions))
        }
        // R1: a label that contains " @" does not move the marker.
        assertEquals(0.5f, ConformanceEmbeddings.embed("a @ b @60.0", 3)[0], tolerance)
    }

    @Test
    fun `E3 two labels at one angle embed to equal vectors`() {
        val first = ConformanceEmbeddings.embed(ConformanceEmbeddings.text("alpha", 45.0), 4)
        val second = ConformanceEmbeddings.embed(ConformanceEmbeddings.text("beta", 45.0), 4)

        assertArrayEquals(first, second)
        assertEquals(0.70710677f, first[0], tolerance)
    }

    @Test
    fun `E4 a text with no marker or an unreadable marker is rejected`() {
        listOf("no marker", "label @north").forEach { text ->
            val error = assertThrows(IllegalArgumentException::class.java) {
                ConformanceEmbeddings.embed(text, 4)
            }

            assertTrue(error.message!!.contains(text), "message should name the text: ${error.message}")
        }
    }

    @Test
    fun `E5 fewer than two dimensions are rejected`() {
        val text = ConformanceEmbeddings.text("query", 0.0)

        listOf(0, 1).forEach { dimensions ->
            assertThrows(IllegalArgumentException::class.java) {
                ConformanceEmbeddings.embed(text, dimensions)
            }
        }
        assertEquals(ConformanceEmbeddings.MIN_DIMENSIONS, ConformanceEmbeddings.embed(text, 2).size)
    }
}

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

import org.jetbrains.annotations.ApiStatus
import kotlin.math.cos
import kotlin.math.sin

/**
 * Builds test texts and their embeddings, so a suite knows how close each stored item sits to a query.
 *
 * A text ends in a marker: a space, an `@` and an angle in degrees, or the word `none` for a text
 * with no direction. The store under test embeds each text with [embed]. The angle places the
 * vector in a plane, so the cosine between two texts is the cosine of the difference between their
 * angles. The suite can then compute every expected similarity exactly.
 *
 * Java:
 * ```java
 * float[] v = ConformanceEmbeddings.embed(ConformanceEmbeddings.text("query", 0.0), 8);
 * ```
 *
 * Kotlin:
 * ```kotlin
 * val v = ConformanceEmbeddings.embed(ConformanceEmbeddings.text("query", 0.0), 8)
 * ```
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
object ConformanceEmbeddings {

    /** The smallest number of dimensions [embed] accepts. */
    const val MIN_DIMENSIONS: Int = 2

    /** The separator that starts a marker. The marker is the text after the last separator. */
    private const val MARKER_PREFIX = " @"

    /** The marker for a text with no direction. */
    private const val NO_DIRECTION = "none"

    /**
     * Returns a text whose embedding points [degrees] away from zero degrees.
     *
     * @param label the name of the text, which does not change its embedding
     * @param degrees the angle in degrees
     * @return the text, ending in the angle marker
     */
    @JvmStatic
    fun text(label: String, degrees: Double): String = "$label @$degrees"

    /**
     * Returns a text whose embedding is all zeros.
     *
     * @param label the name of the text, which does not change its embedding
     * @return the text, ending in the `none` marker
     */
    @JvmStatic
    fun textWithoutDirection(label: String): String = "$label @$NO_DIRECTION"

    /**
     * Returns the embedding of a text. Component 0 holds the cosine of the marker's angle, component 1
     * holds the sine, and the rest are zero. A `none` marker gives all zeros.
     *
     * @param text a text that ends in a marker
     * @param dimensions the length of the vector
     * @return the embedding
     * @throws IllegalArgumentException if [dimensions] is below [MIN_DIMENSIONS], if the text has no
     * marker, or if the marker is neither `none` nor a finite number
     */
    @JvmStatic
    fun embed(text: String, dimensions: Int): FloatArray {
        require(dimensions >= MIN_DIMENSIONS) {
            "dimensions must be at least $MIN_DIMENSIONS but was $dimensions"
        }
        val markerStart = text.lastIndexOf(MARKER_PREFIX)
        require(markerStart >= 0) { "Text has no ' @' marker: $text" }
        val marker = text.substring(markerStart + MARKER_PREFIX.length)
        val vector = FloatArray(dimensions)
        if (marker == NO_DIRECTION) {
            return vector
        }
        val degrees = marker.toDoubleOrNull()
        require(degrees != null && degrees.isFinite()) {
            "Marker '$marker' is neither 'none' nor a number, in text: $text"
        }
        val radians = Math.toRadians(degrees)
        vector[0] = cos(radians).toFloat()
        vector[1] = sin(radians).toFloat()
        return vector
    }
}

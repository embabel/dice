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

import org.jetbrains.annotations.ApiStatus

/**
 * How close two embeddings are, as cosine similarity. The range is -1.0 to 1.0, where 1.0 is the
 * same direction, 0.0 is orthogonal and -1.0 is opposite. Higher is closer. The range is wider
 * than the 0 to 1 some libraries use, and [unitInterval] gives that form.
 *
 * Every store reports similarity on this scale, so a threshold tuned against one store means the
 * same on another. A store converts the number its database returns with the matching factory.
 *
 * | The database returns | Factory |
 * |---|---|
 * | Cosine similarity, -1 to 1 | [ofCosine] |
 * | Cosine distance, 0 closest and 2 farthest | [ofCosineDistance] |
 * | A 0 to 1 score, 1 closest and 0.5 orthogonal | [ofUnitInterval] |
 *
 * A store whose index uses another metric converts to cosine similarity first.
 *
 * ```java
 * SimilarityScore floor = SimilarityScore.ofCosine(0.7);
 * boolean close = match.getScore().compareTo(floor) >= 0;
 *
 * SimilarityScore.ofCosineDistance(0.25).getCosine(); // 0.75
 * SimilarityScore.ofCosine(1.5);                      // throws IllegalArgumentException
 * ```
 *
 * ```kotlin
 * val close = matches.filter { it.score >= SimilarityScore.ofCosine(0.7) }
 * ```
 *
 * EXPERIMENTAL. This API may change before 1.0.
 *
 * @property cosine The cosine similarity, from -1.0 to 1.0.
 */
@ApiStatus.Experimental
class SimilarityScore private constructor(val cosine: Double) : Comparable<SimilarityScore> {

    /** The same score mapped onto 0 to 1 as `(1 + cosine) / 2`, for libraries that expect that range. */
    val unitInterval: Double get() = (1.0 + cosine) / 2.0

    override fun compareTo(other: SimilarityScore): Int = cosine.compareTo(other.cosine)

    override fun equals(other: Any?): Boolean = other is SimilarityScore && other.cosine == cosine

    override fun hashCode(): Int = cosine.hashCode()

    override fun toString(): String = "SimilarityScore(cosine=$cosine)"

    companion object {

        /** How far past an end of a range floating-point rounding in a database may land. */
        private const val ROUNDING_SLACK = 1e-6

        /**
         * A score from a cosine similarity.
         *
         * @param cosine From -1.0 to 1.0. A value within one millionth outside that range is
         *   treated as rounding and moved to the nearest end.
         * @return The score.
         * @throws IllegalArgumentException when [cosine] is not a number or is outside the range.
         */
        @JvmStatic
        fun ofCosine(cosine: Double): SimilarityScore {
            require(cosine >= -1.0 - ROUNDING_SLACK && cosine <= 1.0 + ROUNDING_SLACK) {
                "A cosine similarity is between -1.0 and 1.0, but was $cosine"
            }
            // Adding 0.0 turns -0.0 into 0.0, so equal scores have equal hash codes.
            return SimilarityScore(cosine.coerceIn(-1.0, 1.0) + 0.0)
        }

        /**
         * A score from a cosine distance.
         *
         * @param distance From 0.0 (closest) to 2.0 (farthest). A value within one millionth
         *   outside that range is treated as rounding and moved to the nearest end.
         * @return The score, `1 - distance`.
         * @throws IllegalArgumentException when [distance] is not a number or is outside the range.
         */
        @JvmStatic
        fun ofCosineDistance(distance: Double): SimilarityScore {
            require(distance >= -ROUNDING_SLACK && distance <= 2.0 + ROUNDING_SLACK) {
                "A cosine distance is between 0.0 and 2.0, but was $distance"
            }
            return ofCosine(1.0 - distance.coerceIn(0.0, 2.0))
        }

        /**
         * A score from a 0 to 1 similarity.
         *
         * @param score From 0.0 (opposite) through 0.5 (orthogonal) to 1.0 (closest). A value
         *   within one millionth outside that range is treated as rounding and moved to the
         *   nearest end.
         * @return The score, `2 * score - 1`.
         * @throws IllegalArgumentException when [score] is not a number or is outside the range.
         */
        @JvmStatic
        fun ofUnitInterval(score: Double): SimilarityScore {
            require(score >= -ROUNDING_SLACK && score <= 1.0 + ROUNDING_SLACK) {
                "A zero to one similarity is between 0.0 and 1.0, but was $score"
            }
            return ofCosine(2.0 * score.coerceIn(0.0, 1.0) - 1.0)
        }
    }
}

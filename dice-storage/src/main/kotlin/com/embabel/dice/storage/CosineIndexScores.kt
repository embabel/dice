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

import com.embabel.dice.store.SimilarityScore

/**
 * Converts between the score of a COSINE vector index and the cosine similarity
 * [DrivinePropositionRepository] reports. The index scores as `(1 + cosine) / 2`. The arithmetic
 * is [SimilarityScore]'s. The cluster query computes the same `2 * score - 1` in Cypher, in the
 * database.
 */
internal object CosineIndexScores {

    /**
     * Converts an index score to a cosine similarity.
     *
     * @param indexScore The score the index returns. A score below 0 or above 1 is held to that
     *   end first.
     * @return `2 * indexScore - 1`, from -1 to 1. `NaN` when [indexScore] is `NaN`.
     */
    fun toCosine(indexScore: Double): Double =
        if (indexScore.isNaN()) indexScore else SimilarityScore.ofUnitInterval(indexScore.coerceIn(0.0, 1.0)).cosine

    /**
     * Converts a caller's cosine floor to the scale the index compares against.
     *
     * @param cosineFloor The lowest cosine similarity to return. The repository passes a floor
     *   above 0.
     * @return `(1 + cosineFloor) / 2`. A floor above 1 is returned as it is, which is above every
     *   index score from 0 to 1.
     */
    fun toIndexFloor(cosineFloor: Double): Double =
        if (cosineFloor > 1.0) cosineFloor else SimilarityScore.ofCosine(cosineFloor).unitInterval
}

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

import com.embabel.common.core.types.SimilarityResult
import com.embabel.dice.proposition.CodePointOrder
import com.embabel.dice.proposition.Proposition

/**
 * Highest score first; equal scores fall back to context, then proposition id, both by code point,
 * so results repeat and break ties the way the graph stores do.
 */
internal val similarityOrder: Comparator<SimilarityResult<Proposition>> =
    compareByDescending<SimilarityResult<Proposition>> { it.score }
        .thenBy(CodePointOrder) { it.match.contextId.value }.thenBy(CodePointOrder) { it.match.id }

/** Rejects a query vector that is empty or has a NaN or infinite component. */
internal fun requireUsableVector(vector: FloatArray) {
    require(vector.isNotEmpty() && vector.all { it.isFinite() }) {
        "Vectors must have a positive dimension and finite components"
    }
}

/**
 * Whether a stored vector can be scored against a query of [dimension] components: the same length,
 * every component finite. An all-zero vector is usable and scores zero. Scoped search skips the rest,
 * as the graph store does, since they are normal while embeddings are being rebuilt.
 */
internal fun isUsableVector(vector: FloatArray, dimension: Int): Boolean =
    vector.size == dimension && vector.all { it.isFinite() }

/**
 * Cosine similarity of two vectors of the same length; see [ScopedReadRules.cosineSimilarity].
 * A NaN or infinite component gives a NaN score, which no threshold admits, so plain vector search
 * and clustering skip that row as they always have. Scoped search rejects such vectors before
 * calling this.
 */
internal fun cosineSimilarity(a: FloatArray, b: FloatArray): Double = ScopedReadRules.cosineSimilarity(a, b)

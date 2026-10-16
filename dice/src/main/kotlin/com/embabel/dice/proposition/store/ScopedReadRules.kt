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

import org.jetbrains.annotations.ApiStatus
import java.util.Locale

/**
 * The small rules every scoped read follows, whichever store runs it: how ties are ordered, how
 * vectors are scored and how search tokens are matched. Every store applies them the same way, so a
 * read gives the same rows in the same order on the in-memory store and on a graph database. A store
 * written in Kotlin or Java should call these rather than keep its own copy.
 *
 * EXPERIMENTAL: the shape may change before 1.0.
 */
@ApiStatus.Experimental
object ScopedReadRules {

    /**
     * Compares two strings by Unicode code point, the order graph databases use for stored text.
     * On a shared prefix the shorter string comes first. Plain string comparison in Kotlin and Java
     * works on UTF-16 units instead, which puts characters above U+FFFF before U+E000..U+FFFF.
     * A lone surrogate sorts as its own value.
     */
    @JvmStatic
    fun compareCodePoints(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length) {
            val x = a.codePointAt(i)
            val y = b.codePointAt(i)
            if (x != y) return x.compareTo(y)
            i += Character.charCount(x)
        }
        return a.length.compareTo(b.length)
    }

    /**
     * [compareCodePoints] as a comparator. When rows tie on the requested order, a store compares
     * their context IDs with it first, then their proposition IDs.
     */
    @JvmField
    val CODE_POINT_ORDER: Comparator<String> = Comparator { a, b -> compareCodePoints(a, b) }

    /**
     * Cosine similarity of two vectors of the same length. Sums run in Double so very large or very
     * small components neither overflow nor vanish, a zero vector scores 0.0, and the result is
     * clamped to [-1, 1] so rounding never leaves that range. A NaN or infinite component gives NaN.
     *
     * @throws IllegalArgumentException if the vectors differ in length
     */
    @JvmStatic
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        require(a.size == b.size) { "Vectors must have the same dimension" }
        return cosine(a.size, { a[it].toDouble() }, { b[it].toDouble() })
    }

    /**
     * The same cosine for embeddings a graph store reads back as lists of numbers, with the same
     * maths as the [FloatArray] version.
     *
     * @throws IllegalArgumentException if the vectors differ in length
     */
    @JvmStatic
    fun cosineSimilarity(a: List<Double>, b: List<Double>): Double {
        require(a.size == b.size) { "Vectors must have the same dimension" }
        return cosine(a.size, { a[it] }, { b[it] })
    }

    private inline fun cosine(size: Int, a: (Int) -> Double, b: (Int) -> Double): Double {
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in 0 until size) {
            val x = a(i)
            val y = b(i)
            dot += x * y
            normA += x * x
            normB += y * y
        }
        val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denominator == 0.0) 0.0 else (dot / denominator).coerceIn(-1.0, 1.0)
    }

    /**
     * Turns raw search tokens into the ones a keyword read matches: each is trimmed and lowercased
     * in the root locale, so the result never depends on the machine's language, then blank tokens
     * are dropped and repeats are removed, keeping the first.
     */
    @JvmStatic
    fun keywordTokens(tokens: List<String>): List<String> =
        tokens.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct()

    /**
     * How many of [tokens] appear somewhere in [text], ignoring case. The tokens should already have
     * been through [keywordTokens].
     */
    @JvmStatic
    fun keywordHits(text: String, tokens: List<String>): Int {
        val lowered = text.lowercase(Locale.ROOT)
        return tokens.count { it in lowered }
    }
}

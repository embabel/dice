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
package com.embabel.dice.evidence

/**
 * Name of a retriever, such as the vector index or the graph walk that produced a hit.
 *
 * Names are trimmed and lower-cased, then must match `[a-z0-9][a-z0-9-]*`.
 *
 * ```java
 * RetrieverId id = RetrieverId.of(" Vector ");  // equal to RetrieverId.VECTOR
 * ```
 */
class RetrieverId private constructor(
    /** The normalized retriever name. */
    val name: String,
) {

    override fun equals(other: Any?): Boolean = other is RetrieverId && name == other.name

    override fun hashCode(): Int = name.hashCode()

    /** Returns the normalized retriever name. */
    override fun toString(): String = name

    companion object {

        private val NAME_PATTERN = Regex("[a-z0-9][a-z0-9-]*")

        /** Retriever backed by a vector index. */
        @JvmField
        val VECTOR: RetrieverId = RetrieverId("vector")

        /** Retriever that matches on keywords in the claim text. */
        @JvmField
        val KEYWORD: RetrieverId = RetrieverId("keyword")

        /** Retriever that matches on the entities named in the query. */
        @JvmField
        val ENTITY: RetrieverId = RetrieverId("entity")

        /** Retriever that reaches claims by walking the graph from a starting entity. */
        @JvmField
        val GRAPH_WALK: RetrieverId = RetrieverId("graph-walk")

        /** Retriever that selects claims by time. */
        @JvmField
        val TEMPORAL: RetrieverId = RetrieverId("temporal")

        /**
         * Creates a retriever id from a name.
         *
         * @param name the retriever name, trimmed and lower-cased before the check
         * @return the retriever id
         * @throws IllegalArgumentException if the normalized name is empty or contains characters other than
         *   letters, digits and hyphens, or starts with a hyphen
         */
        @JvmStatic
        fun of(name: String): RetrieverId {
            val normalized = name.trim().lowercase()
            require(NAME_PATTERN.matches(normalized)) {
                "retriever name must match [a-z0-9][a-z0-9-]*, was '$name'"
            }
            return RetrieverId(normalized)
        }
    }
}

/**
 * One retriever's hit for one claim: the rank it gave, its score, and the graph path it walked.
 *
 * Fields a retriever does not produce are null or empty. A non-empty [entityPath] always
 * satisfies `hops == entityPath.size - 1`.
 *
 * ```java
 * Retrieval hit = Retrieval.of(RetrieverId.VECTOR).withRank(1).withScore(0.87);
 *
 * Retrieval walked = Retrieval.of(RetrieverId.GRAPH_WALK)
 *     .withEntityPath(List.of("alice", "acme", "berlin"));  // hops == 2
 * ```
 */
class Retrieval private constructor(
    /** The retriever that produced this hit. */
    val retriever: RetrieverId,
    /** Position of the claim in the retriever's results, starting at 1. Null when the retriever does not rank. */
    val rank: Int?,
    /** The retriever's own score for the claim. Null when the retriever has none. */
    val score: Double?,
    /** Graph distance from the starting entity. Null when no graph walk was involved. */
    val hops: Int?,
    entityPath: List<String>,
) {

    /** Entity ids walked from the starting entity to the last one. Empty when the path is not known. */
    val entityPath: List<String> = java.util.List.copyOf(entityPath)

    init {
        require(rank == null || rank >= 1) { "rank starts at 1, was $rank" }
        require(score == null || score.isFinite()) { "score must be finite, was $score" }
        require(hops == null || hops >= 0) { "hops must not be negative, was $hops" }
        require(this.entityPath.all { it.isNotBlank() }) { "entity ids must not be blank" }
        require(this.entityPath.isEmpty() || hops == this.entityPath.size - 1) {
            "hops must equal entityPath.size - 1, was $hops for ${this.entityPath.size} entities"
        }
    }

    /**
     * Returns a copy with [rank] set.
     *
     * @param rank the position in the retriever's results, starting at 1
     * @return the updated retrieval
     * @throws IllegalArgumentException if [rank] is less than 1
     */
    fun withRank(rank: Int): Retrieval = Retrieval(retriever, rank, score, hops, entityPath)

    /**
     * Returns a copy with [score] set.
     *
     * @param score the retriever's score for the claim
     * @return the updated retrieval
     * @throws IllegalArgumentException if [score] is NaN or infinite
     */
    fun withScore(score: Double): Retrieval = Retrieval(retriever, rank, score, hops, entityPath)

    /**
     * Returns a copy with [hops] set.
     *
     * A retrieval with a non-empty [entityPath] keeps its hops equal to the path length minus one,
     * so the new value must match that.
     *
     * @param hops the graph distance from the starting entity
     * @return the updated retrieval
     * @throws IllegalArgumentException if [hops] is negative, or a path is set and [hops] does not match it
     */
    fun withHops(hops: Int): Retrieval = Retrieval(retriever, rank, score, hops, entityPath)

    /**
     * Returns a copy with the walked path set. Also sets [hops] to the number of steps in the path.
     *
     * @param entityPath the entity ids from the starting entity to the last one
     * @return the updated retrieval
     * @throws IllegalArgumentException if [entityPath] is empty or contains a blank id
     */
    fun withEntityPath(entityPath: List<String>): Retrieval {
        require(entityPath.isNotEmpty()) { "entityPath must not be empty" }
        return Retrieval(retriever, rank, score, entityPath.size - 1, entityPath)
    }

    /**
     * Two retrievals are equal when every property is equal. Scores compare by their boxed
     * `Double` value, so `0.0` and `-0.0` differ.
     */
    override fun equals(other: Any?): Boolean =
        other is Retrieval && retriever == other.retriever && rank == other.rank &&
                java.util.Objects.equals(score, other.score) && hops == other.hops &&
                entityPath == other.entityPath

    /** Hash code over the same properties that [equals] compares. */
    override fun hashCode(): Int = java.util.Objects.hash(retriever, rank, score, hops, entityPath)

    /** Returns a short summary of the retriever, rank, score and hops. */
    override fun toString(): String = "Retrieval(retriever=$retriever, rank=$rank, score=$score, hops=$hops)"

    companion object {

        /**
         * Creates a retrieval for [retriever] with no rank, score, hops or path.
         *
         * @param retriever the retriever that produced the hit
         * @return the retrieval
         */
        @JvmStatic
        fun of(retriever: RetrieverId): Retrieval = Retrieval(retriever, null, null, null, emptyList())
    }
}

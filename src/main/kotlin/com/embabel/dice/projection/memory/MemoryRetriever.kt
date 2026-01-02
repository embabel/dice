package com.embabel.dice.projection.memory

import com.embabel.dice.common.EntityRequest
import com.embabel.dice.proposition.Proposition
import java.time.Instant

/**
 * Retrieves propositions with memory semantics.
 * Combines multiple retrieval strategies: similarity, entity overlap, recency.
 */
interface MemoryRetriever {

    /**
     * Recall propositions relevant to a query.
     * Combines: vector similarity + entity overlap + recency.
     *
     * @param query The search query
     * @param scope Memory scope for filtering
     * @param topK Maximum number of results
     * @return Relevant propositions ordered by relevance
     */
    fun recall(
        query: String,
        scope: MemoryScope,
        topK: Int = 10,
    ): List<Proposition>

    /**
     * Recall everything known about an entity.
     *
     * @param entityId The entity to retrieve information about
     * @param scope Memory scope for filtering
     * @return All propositions mentioning this entity
     */
    fun recallAbout(
        entityId: EntityRequest,
        scope: MemoryScope,
    ): List<Proposition>

    /**
     * Recall propositions by memory type.
     *
     * @param memoryType The type of memory to retrieve
     * @param scope Memory scope for filtering
     * @param topK Maximum number of results
     * @return Propositions of the specified type
     */
    fun recallByType(
        memoryType: MemoryType,
        scope: MemoryScope,
        topK: Int = 20,
    ): List<Proposition>

    /**
     * Recall recent propositions.
     *
     * @param scope Memory scope for filtering
     * @param since Only include propositions after this time
     * @param limit Maximum number of results
     * @return Recent propositions ordered by time (most recent first)
     */
    fun recallRecent(
        scope: MemoryScope,
        since: Instant = Instant.now().minusSeconds(3600),
        limit: Int = 20,
    ): List<Proposition>
}


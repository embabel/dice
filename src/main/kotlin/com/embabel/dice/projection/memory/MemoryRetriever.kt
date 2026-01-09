package com.embabel.dice.projection.memory

import com.embabel.agent.rag.service.RetrievableIdentifier
import com.embabel.dice.common.KnowledgeType
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
     * @param forEntity Entity to scope the retrieval to
     * @param topK Maximum number of results
     * @return Relevant propositions ordered by relevance
     */
    fun recall(
        query: String,
        forEntity: RetrievableIdentifier,
        topK: Int = 10,
    ): List<Proposition>

    /**
     * Recall everything known about an entity.
     *
     * @param entityId The entity to retrieve information about
     * @return All propositions mentioning this entity
     */
    fun recallAbout(
        entityId: RetrievableIdentifier,
    ): List<Proposition>

    /**
     * Recall propositions by knowledge type for an entity.
     *
     * @param knowledgeType The type of knowledge to retrieve
     * @param forEntity Entity to scope the retrieval to
     * @param topK Maximum number of results
     * @return Propositions of the specified type
     */
    fun recallByType(
        knowledgeType: KnowledgeType,
        forEntity: RetrievableIdentifier,
        topK: Int = 20,
    ): List<Proposition>

    /**
     * Recall recent propositions for an entity.
     *
     * @param forEntity Entity to scope the retrieval to
     * @param since Only include propositions after this time
     * @param limit Maximum number of results
     * @return Recent propositions ordered by time (most recent first)
     */
    fun recallRecent(
        forEntity: RetrievableIdentifier,
        since: Instant = Instant.now().minusSeconds(3600),
        limit: Int = 20,
    ): List<Proposition>
}

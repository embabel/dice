package com.embabel.dice.common.resolver

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.SimpleNamedEntityData
import com.embabel.agent.rag.service.CoreSearchOperations
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.common.*
import org.slf4j.LoggerFactory

/**
 * Entity resolver that uses [CoreSearchOperations] to find existing entities
 * in a backing store (e.g., Lucene index, vector database).
 *
 * Supports multiple search strategies:
 * - **Find by ID**: If the suggested entity has an ID, try exact lookup first
 * - **Text Search**: Uses Lucene query syntax for name matching
 * - **Vector Search**: Semantic similarity search using embeddings
 *
 * Match evaluation is delegated to configurable [MatchStrategy] implementations,
 * allowing customization of how search results are evaluated for equivalence.
 *
 * @param searchOperations The search operations backend
 * @param config Configuration for search behavior and thresholds
 * @param matchStrategies Strategies for evaluating if a search result matches a suggestion.
 *                        Strategies are tried in order; first match wins.
 */
class CoreSearchOperationsEntityResolver(
    private val searchOperations: CoreSearchOperations,
    private val config: Config = Config(),
    private val matchStrategies: List<MatchStrategy> = defaultMatchStrategies(),
) : EntityResolver {

    private val logger = LoggerFactory.getLogger(CoreSearchOperationsEntityResolver::class.java)

    /**
     * Configuration for search behavior.
     */
    data class Config(
        /**
         * Whether to use find-by-ID when suggested entity has an ID.
         */
        val useFindById: Boolean = true,

        /**
         * Whether to use text search for name matching.
         */
        val useTextSearch: Boolean = true,

        /**
         * Whether to use vector search for semantic matching.
         */
        val useVectorSearch: Boolean = false,

        /**
         * Minimum similarity score (0.0-1.0) for text search results.
         */
        val textSearchThreshold: Double = 0.5,

        /**
         * Minimum similarity score (0.0-1.0) for vector search results.
         */
        val vectorSearchThreshold: Double = 0.7,

        /**
         * Maximum number of candidates to retrieve from search.
         */
        val topK: Int = 10,

        /**
         * Whether to use fuzzy matching in text search queries.
         * Adds `~` suffix for Lucene fuzzy matching.
         */
        val useFuzzyTextSearch: Boolean = true,
    )

    override fun resolve(
        suggestedEntities: SuggestedEntities,
        schema: DataDictionary
    ): Resolutions<SuggestedEntityResolution> {
        val resolutions = suggestedEntities.suggestedEntities.map { suggested ->
            resolveEntity(suggested, schema)
        }
        return Resolutions(
            chunkIds = suggestedEntities.chunkIds,
            resolutions = resolutions,
        )
    }

    private fun resolveEntity(
        suggested: SuggestedEntity,
        schema: DataDictionary
    ): SuggestedEntityResolution {
        // Strategy 1: Try find by ID if available
        if (config.useFindById && suggested.id != null) {
            val existing = findById(suggested.id)
            if (existing != null && isMatch(suggested, existing, schema)) {
                logger.debug("Found exact ID match for '{}': {}", suggested.name, existing.id)
                return ExistingEntity(suggested, existing)
            }
        }

        // Strategy 2: Try text search
        if (config.useTextSearch) {
            val textMatches = textSearch(suggested)
            for (result in textMatches) {
                if (isMatch(suggested, result.match, schema)) {
                    logger.debug(
                        "Found text search match for '{}': {} (score: {})",
                        suggested.name, result.match.name, result.score
                    )
                    return ExistingEntity(suggested, result.match)
                }
            }
        }

        // Strategy 3: Try vector search
        if (config.useVectorSearch) {
            val vectorMatches = vectorSearch(suggested)
            for (result in vectorMatches) {
                if (isMatch(suggested, result.match, schema)) {
                    logger.debug(
                        "Found vector search match for '{}': {} (score: {})",
                        suggested.name, result.match.name, result.score
                    )
                    return ExistingEntity(suggested, result.match)
                }
            }
        }

        // No match found - create new entity
        logger.debug("No match found for '{}', creating new entity", suggested.name)
        return NewEntity(suggested)
    }

    private fun findById(id: String): NamedEntityData? {
        return try {
            searchOperations.findById(id, SimpleNamedEntityData::class.java)
        } catch (e: Exception) {
            logger.warn("Error finding entity by ID '{}': {}", id, e.message)
            null
        }
    }

    private fun textSearch(suggested: SuggestedEntity): List<SimilarityResult<SimpleNamedEntityData>> {
        val query = buildTextQuery(suggested)
        val request = TextSimilaritySearchRequest(
            query = query,
            similarityThreshold = config.textSearchThreshold,
            topK = config.topK,
        )
        return try {
            searchOperations.textSearch(request, SimpleNamedEntityData::class.java)
        } catch (e: Exception) {
            logger.warn("Text search failed for '{}': {}", suggested.name, e.message)
            emptyList()
        }
    }

    private fun vectorSearch(suggested: SuggestedEntity): List<SimilarityResult<SimpleNamedEntityData>> {
        // Use name + summary for semantic search
        val query = "${suggested.name} ${suggested.summary}"
        val request = TextSimilaritySearchRequest(
            query = query,
            similarityThreshold = config.vectorSearchThreshold,
            topK = config.topK,
        )
        return try {
            searchOperations.vectorSearch(request, SimpleNamedEntityData::class.java)
        } catch (e: Exception) {
            logger.warn("Vector search failed for '{}': {}", suggested.name, e.message)
            emptyList()
        }
    }

    /**
     * Build a Lucene query for text search.
     * Uses various techniques for flexible matching:
     * - Exact phrase matching for the full name
     * - Individual term matching for name parts
     * - Fuzzy matching if enabled
     */
    private fun buildTextQuery(suggested: SuggestedEntity): String {
        val name = suggested.name.trim()
        val parts = mutableListOf<String>()

        // Exact phrase match (highest priority)
        parts.add("\"$name\"^2")

        // Individual terms from the name
        val terms = name.split(Regex("\\s+")).filter { it.length >= 2 }
        for (term in terms) {
            // Escape special Lucene characters
            val escaped = escapeLucene(term)
            parts.add(escaped)

            // Add fuzzy matching
            if (config.useFuzzyTextSearch && term.length >= 4) {
                parts.add("$escaped~")
            }
        }

        return parts.joinToString(" OR ")
    }

    /**
     * Escape special Lucene query syntax characters.
     */
    private fun escapeLucene(text: String): String {
        val specialChars = setOf('+', '-', '&', '|', '!', '(', ')', '{', '}', '[', ']', '^', '"', '~', '*', '?', ':', '\\', '/')
        val sb = StringBuilder()
        for (c in text) {
            if (c in specialChars) {
                sb.append('\\')
            }
            sb.append(c)
        }
        return sb.toString()
    }

    /**
     * Check if a search result matches the suggested entity using configured strategies.
     */
    private fun isMatch(
        suggested: SuggestedEntity,
        candidate: NamedEntityData,
        schema: DataDictionary
    ): Boolean = matchStrategies.evaluate(suggested, candidate, schema)
}

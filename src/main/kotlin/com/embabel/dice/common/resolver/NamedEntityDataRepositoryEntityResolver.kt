package com.embabel.dice.common.resolver

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.common.*
import org.slf4j.LoggerFactory

/**
 * Entity resolver that uses [NamedEntityDataRepository] to find existing entities
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
 * @param repository The search operations backend
 * @param config Configuration for search behavior and thresholds
 * @param matchStrategies Strategies for evaluating if a search result matches a suggestion.
 *                        Strategies are tried in order; first match wins.
 */
class NamedEntityDataRepositoryEntityResolver @JvmOverloads constructor(
    private val repository: NamedEntityDataRepository,
    private val config: Config = Config(),
    private val matchStrategies: List<MatchStrategy> = defaultMatchStrategies(),
) : EntityResolver {

    private val logger = LoggerFactory.getLogger(NamedEntityDataRepositoryEntityResolver::class.java)

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
        logger.info(
            "Resolving {} suggested entities from chunks {}",
            suggestedEntities.suggestedEntities.size,
            suggestedEntities.chunkIds
        )

        val resolutions = suggestedEntities.suggestedEntities.map { suggested ->
            resolveEntity(suggested, schema)
        }

        val existingCount = resolutions.count { it is ExistingEntity }
        val newCount = resolutions.count { it is NewEntity }
        val vetoedCount = resolutions.count { it is VetoedEntity }
        logger.info(
            "Entity resolution complete: {} matched existing, {} new, {} vetoed (creation not permitted)",
            existingCount,
            newCount,
            vetoedCount
        )

        resolutions.filterIsInstance<ExistingEntity>().forEach { existing ->
            logger.debug(
                "  Matched '{}' ({}) -> existing '{}' (id={})",
                existing.suggested.name,
                existing.suggested.labels.firstOrNull() ?: "Entity",
                existing.existing.name,
                existing.existing.id
            )
        }
        resolutions.filterIsInstance<NewEntity>().forEach { newEntity ->
            logger.debug(
                "  New entity: '{}' ({}) id={}",
                newEntity.suggested.name,
                newEntity.suggested.labels.firstOrNull() ?: "Entity",
                newEntity.suggested.suggestedEntity.id
            )
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
        // Check if this type allows creation
        val creationPermitted = isCreationPermitted(suggested, schema)

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

        // No match found with standard thresholds
        // If creation is not permitted, try harder with relaxed matching
        if (!creationPermitted) {
            val relaxedMatch = tryRelaxedMatching(suggested, schema)
            if (relaxedMatch != null) {
                logger.debug(
                    "Found relaxed match for non-creatable type '{}': {}",
                    suggested.name, relaxedMatch.name
                )
                return ExistingEntity(suggested, relaxedMatch)
            }

            // Still no match - veto this entity since we can't create it
            logger.info(
                "No match found for '{}' ({}) and creation not permitted - vetoing",
                suggested.name, suggested.labels.firstOrNull() ?: "Entity"
            )
            return VetoedEntity(suggested)
        }

        // No match found - create new entity
        logger.debug("No match found for '{}', creating new entity", suggested.name)
        return NewEntity(suggested)
    }

    /**
     * Check if the suggested entity's type allows creation of new instances.
     */
    private fun isCreationPermitted(suggested: SuggestedEntity, schema: DataDictionary): Boolean {
        val labels = suggested.labels.map { it.substringAfterLast('.') }.toSet()
        val domainType = schema.domainTypeForLabels(labels) ?: return true // Unknown types default to creatable

        return try {
            domainType.creationPermitted
        } catch (e: Exception) {
            // Property might not exist in older versions
            true
        }
    }

    /**
     * Try to find a match with relaxed criteria for types that don't allow creation.
     * Uses lower thresholds and accepts any label-compatible match.
     */
    private fun tryRelaxedMatching(
        suggested: SuggestedEntity,
        schema: DataDictionary
    ): NamedEntityData? {
        // Try text search with lower threshold
        if (config.useTextSearch) {
            val query = buildTextQuery(suggested)
            val relaxedRequest = TextSimilaritySearchRequest(
                query = query,
                similarityThreshold = config.textSearchThreshold * 0.5, // Half the normal threshold
                topK = config.topK * 2, // Get more candidates
            )
            try {
                val results = repository.textSearch(relaxedRequest)
                // Accept any label-compatible match
                for (result in results) {
                    if (isLabelCompatible(suggested, result.match, schema)) {
                        return result.match
                    }
                }
            } catch (e: Exception) {
                logger.debug("Relaxed text search failed for '{}': {}", suggested.name, e.message)
            }
        }

        // Try vector search with lower threshold
        if (config.useVectorSearch) {
            val query = "${suggested.name} ${suggested.summary}"
            val relaxedRequest = TextSimilaritySearchRequest(
                query = query,
                similarityThreshold = config.vectorSearchThreshold * 0.5,
                topK = config.topK * 2,
            )
            try {
                val results = repository.vectorSearch(relaxedRequest)
                for (result in results) {
                    if (isLabelCompatible(suggested, result.match, schema)) {
                        return result.match
                    }
                }
            } catch (e: Exception) {
                logger.debug("Relaxed vector search failed for '{}': {}", suggested.name, e.message)
            }
        }

        return null
    }

    /**
     * Check if labels are compatible (either matching or in a type hierarchy).
     */
    private fun isLabelCompatible(
        suggested: SuggestedEntity,
        candidate: NamedEntityData,
        schema: DataDictionary
    ): Boolean {
        val suggestedLabels = suggested.labels.map { it.substringAfterLast('.') }.toSet()
        val candidateLabels = candidate.labels().map { it.substringAfterLast('.') }.toSet()

        // Direct match
        if (suggestedLabels.intersect(candidateLabels).isNotEmpty()) {
            return true
        }

        // Check type hierarchy
        val suggestedType = schema.domainTypeForLabels(suggestedLabels)
        val candidateType = schema.domainTypeForLabels(candidateLabels)

        if (suggestedType != null && candidateType != null) {
            // Check if one is a subtype of the other
            return suggestedType.isAssignableFrom(candidateType) ||
                    candidateType.isAssignableFrom(suggestedType)
        }

        return false
    }

    private fun findById(id: String): NamedEntityData? {
        return try {
            repository.findById(id)
        } catch (e: Exception) {
            logger.warn("Error finding entity by ID '{}': {}", id, e.message)
            null
        }
    }

    private fun textSearch(suggested: SuggestedEntity): List<SimilarityResult<NamedEntityData>> {
        val query = buildTextQuery(suggested)
        val request = TextSimilaritySearchRequest(
            query = query,
            similarityThreshold = config.textSearchThreshold,
            topK = config.topK,
        )
        return try {
            repository.textSearch(request)
        } catch (e: Exception) {
            logger.warn("Text search failed for '{}': {}", suggested.name, e.message)
            emptyList()
        }
    }

    private fun vectorSearch(suggested: SuggestedEntity): List<SimilarityResult<NamedEntityData>> {
        // Use name + summary for semantic search
        val query = "${suggested.name} ${suggested.summary}"
        val request = TextSimilaritySearchRequest(
            query = query,
            similarityThreshold = config.vectorSearchThreshold,
            topK = config.topK,
        )
        return try {
            repository.vectorSearch(request)
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
        val specialChars =
            setOf('+', '-', '&', '|', '!', '(', ')', '{', '}', '[', ']', '^', '"', '~', '*', '?', ':', '\\', '/')
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

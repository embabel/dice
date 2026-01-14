package com.embabel.dice.common.resolver

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.ExistingEntity
import com.embabel.dice.common.NewEntity
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.common.VetoedEntity
import com.embabel.dice.common.resolver.matcher.ChainedEntityMatchingStrategy
import com.embabel.dice.common.resolver.matcher.LlmCandidateBakeoff
import org.slf4j.LoggerFactory

/**
 * Resolution level indicating which strategy resolved an entity.
 * Lower levels are faster/cheaper; higher levels use more resources.
 */
enum class ResolutionLevel {
    /** Exact name match in repository - no LLM */
    EXACT_MATCH,

    /** Heuristic match strategies (normalized, fuzzy) - no LLM */
    HEURISTIC_MATCH,

    /** High-confidence embedding similarity - no LLM */
    EMBEDDING_MATCH,

    /** Simple yes/no LLM verification */
    LLM_VERIFICATION,

    /** Full LLM comparison of multiple candidates */
    LLM_BAKEOFF,

    /** No match found at any level */
    NO_MATCH,
}

/**
 * Result of a resolution attempt at a specific level.
 */
data class LevelResult(
    val level: ResolutionLevel,
    val resolution: SuggestedEntityResolution?,
    val confidence: Double,
    val candidatesConsidered: Int = 0,
)

/**
 * Configuration for hierarchical resolution behavior.
 */
data class HierarchicalConfig(
    /**
     * Minimum embedding similarity to accept without LLM verification.
     */
    val embeddingAutoAcceptThreshold: Double = 0.95,

    /**
     * Minimum embedding similarity to consider as a candidate.
     */
    val embeddingCandidateThreshold: Double = 0.7,

    /**
     * Maximum candidates to retrieve from search.
     */
    val topK: Int = 10,

    /**
     * Whether to use text search in addition to vector search.
     */
    val useTextSearch: Boolean = true,

    /**
     * Whether to use vector search.
     */
    val useVectorSearch: Boolean = true,

    /**
     * Skip LLM entirely - use only heuristic matching.
     */
    val heuristicOnly: Boolean = false,

    /**
     * Confidence threshold to stop escalating and accept result.
     */
    val earlyTerminationThreshold: Double = 0.9,
)

/**
 * Entity resolver that escalates through levels of resolution strategies,
 * stopping as soon as a confident match is found.
 *
 * Resolution levels (in order):
 * 1. **Exact Match**: Direct name lookup - instant, no LLM
 * 2. **Heuristic Match**: Normalized/fuzzy strategies on search results - fast, no LLM
 * 3. **Embedding Match**: High-confidence vector similarity - fast, no LLM
 * 4. **LLM Verification**: Simple yes/no check for single candidate - 1 cheap LLM call
 * 5. **LLM Bakeoff**: Full comparison of multiple candidates - 1 LLM call with more context
 *
 * This approach minimizes LLM calls by handling easy cases with fast heuristics
 * and only escalating to LLM for genuinely ambiguous resolutions.
 *
 * @param repository The entity repository for search operations
 * @param matchStrategies Heuristic strategies for Level 2 matching
 * @param llmBakeoff Optional LLM bakeoff for Levels 4-5 (if null, stops at Level 3)
 * @param contextCompressor Optional compressor for reducing context size in LLM calls
 * @param config Configuration for thresholds and behavior
 */
class HierarchicalEntityResolver(
    private val repository: NamedEntityDataRepository,
    private val matchStrategies: ChainedEntityMatchingStrategy = defaultMatchStrategies(),
    private val llmBakeoff: LlmCandidateBakeoff? = null,
    private val contextCompressor: ContextCompressor? = null,
    private val config: HierarchicalConfig = HierarchicalConfig(),
) : EntityResolver {

    private val logger = LoggerFactory.getLogger(HierarchicalEntityResolver::class.java)

    override fun resolve(
        suggestedEntities: SuggestedEntities,
        schema: DataDictionary
    ): Resolutions<SuggestedEntityResolution> {
        logger.info(
            "Hierarchical resolution of {} entities from chunks {}",
            suggestedEntities.suggestedEntities.size,
            suggestedEntities.chunkIds
        )

        val sourceText = suggestedEntities.sourceText
        val levelCounts = mutableMapOf<ResolutionLevel, Int>()

        val resolutions = suggestedEntities.suggestedEntities.map { suggested ->
            val result = resolveWithEscalation(suggested, schema, sourceText)
            levelCounts.merge(result.level, 1, Int::plus)
            result.resolution ?: createNewOrVeto(suggested, schema)
        }

        logger.info(
            "Hierarchical resolution complete: {}",
            levelCounts.entries.joinToString(", ") { "${it.key}=${it.value}" }
        )

        return Resolutions(
            chunkIds = suggestedEntities.chunkIds,
            resolutions = resolutions,
        )
    }

    /**
     * Resolve a single entity, escalating through levels until confident.
     */
    private fun resolveWithEscalation(
        suggested: SuggestedEntity,
        schema: DataDictionary,
        sourceText: String?,
    ): LevelResult {
        // Level 1: Exact match by ID or name
        tryExactMatch(suggested)?.let { existing ->
            logger.debug("L1 EXACT: '{}' -> '{}'", suggested.name, existing.name)
            return LevelResult(
                level = ResolutionLevel.EXACT_MATCH,
                resolution = ExistingEntity(suggested, existing),
                confidence = 1.0,
            )
        }

        // Gather candidates from search
        val candidates = gatherCandidates(suggested)
        if (candidates.isEmpty()) {
            logger.debug("No candidates found for '{}'", suggested.name)
            return LevelResult(ResolutionLevel.NO_MATCH, null, 0.0)
        }

        // Level 2: Heuristic matching on candidates
        for (candidate in candidates) {
            if (matchStrategies.matches(suggested, candidate.match, schema)) {
                logger.debug(
                    "L2 HEURISTIC: '{}' -> '{}' (score: {})",
                    suggested.name, candidate.match.name, candidate.score
                )
                return LevelResult(
                    level = ResolutionLevel.HEURISTIC_MATCH,
                    resolution = ExistingEntity(suggested, candidate.match),
                    confidence = candidate.score,
                    candidatesConsidered = candidates.size,
                )
            }
        }

        // Level 3: High-confidence embedding match (must also pass label compatibility)
        val topCandidate = candidates.first()
        if (topCandidate.score >= config.embeddingAutoAcceptThreshold &&
            isLabelCompatible(suggested, topCandidate.match, schema)
        ) {
            logger.debug(
                "L3 EMBEDDING: '{}' -> '{}' (score: {} >= {})",
                suggested.name, topCandidate.match.name,
                topCandidate.score, config.embeddingAutoAcceptThreshold
            )
            return LevelResult(
                level = ResolutionLevel.EMBEDDING_MATCH,
                resolution = ExistingEntity(suggested, topCandidate.match),
                confidence = topCandidate.score,
                candidatesConsidered = candidates.size,
            )
        }

        // Stop here if heuristic-only mode or no LLM configured
        if (config.heuristicOnly || llmBakeoff == null) {
            logger.debug("No LLM available/enabled for '{}'", suggested.name)
            return LevelResult(ResolutionLevel.NO_MATCH, null, 0.0, candidates.size)
        }

        // Filter candidates by label compatibility before LLM calls
        val compatibleCandidates = filterByLabelCompatibility(suggested, candidates, schema)
        if (compatibleCandidates.isEmpty()) {
            logger.debug("No label-compatible candidates for '{}'", suggested.name)
            return LevelResult(ResolutionLevel.NO_MATCH, null, 0.0, candidates.size)
        }

        // Compress context for LLM calls
        val compressedContext = contextCompressor?.compress(sourceText, suggested.name)
            ?: sourceText

        // Level 4: Single candidate - simple LLM verification
        if (compatibleCandidates.size == 1) {
            val verified = llmBakeoff.selectBestMatch(
                suggested,
                compatibleCandidates,
                compressedContext,
            )
            if (verified != null) {
                logger.debug("L4 LLM_VERIFY: '{}' -> '{}'", suggested.name, verified.name)
                return LevelResult(
                    level = ResolutionLevel.LLM_VERIFICATION,
                    resolution = ExistingEntity(suggested, verified),
                    confidence = 0.85,
                    candidatesConsidered = 1,
                )
            }
            return LevelResult(ResolutionLevel.NO_MATCH, null, 0.0, 1)
        }

        // Level 5: Multiple candidates - full LLM bakeoff
        val bestMatch = llmBakeoff.selectBestMatch(
            suggested,
            compatibleCandidates,
            compressedContext,
        )
        if (bestMatch != null) {
            logger.debug(
                "L5 LLM_BAKEOFF: '{}' -> '{}' (from {} candidates)",
                suggested.name, bestMatch.name, compatibleCandidates.size
            )
            return LevelResult(
                level = ResolutionLevel.LLM_BAKEOFF,
                resolution = ExistingEntity(suggested, bestMatch),
                confidence = 0.8,
                candidatesConsidered = compatibleCandidates.size,
            )
        }

        return LevelResult(ResolutionLevel.NO_MATCH, null, 0.0, compatibleCandidates.size)
    }

    /**
     * Try to find an exact match by ID or normalized name.
     */
    private fun tryExactMatch(suggested: SuggestedEntity): NamedEntityData? {
        // Try by ID first
        suggested.id?.let { id ->
            try {
                repository.findById(id)?.let { return it }
            } catch (e: Exception) {
                logger.debug("ID lookup failed for '{}': {}", id, e.message)
            }
        }

        // Try exact name match via text search
        val exactQuery = "\"${suggested.name}\""
        try {
            val results = repository.textSearch(
                TextSimilaritySearchRequest(
                    query = exactQuery,
                    similarityThreshold = 0.99,
                    topK = 1,
                )
            )
            results.firstOrNull()?.let { result ->
                if (result.match.name.equals(suggested.name, ignoreCase = true)) {
                    return result.match
                }
            }
        } catch (e: Exception) {
            logger.debug("Exact name search failed for '{}': {}", suggested.name, e.message)
        }

        return null
    }

    /**
     * Gather candidates from text and/or vector search.
     */
    private fun gatherCandidates(suggested: SuggestedEntity): List<SimilarityResult<NamedEntityData>> {
        val allCandidates = mutableListOf<SimilarityResult<NamedEntityData>>()

        if (config.useTextSearch) {
            try {
                val query = buildTextQuery(suggested.name)
                val results = repository.textSearch(
                    TextSimilaritySearchRequest(
                        query = query,
                        similarityThreshold = 0.5,
                        topK = config.topK,
                    )
                )
                allCandidates.addAll(results)
            } catch (e: Exception) {
                logger.debug("Text search failed for '{}': {}", suggested.name, e.message)
            }
        }

        if (config.useVectorSearch) {
            try {
                val query = "${suggested.name} ${suggested.summary}"
                val results = repository.vectorSearch(
                    TextSimilaritySearchRequest(
                        query = query,
                        similarityThreshold = config.embeddingCandidateThreshold,
                        topK = config.topK,
                    )
                )
                allCandidates.addAll(results)
            } catch (e: Exception) {
                logger.debug("Vector search failed for '{}': {}", suggested.name, e.message)
            }
        }

        // Deduplicate and sort by score
        return allCandidates
            .distinctBy { it.match.id }
            .sortedByDescending { it.score }
            .take(config.topK)
    }

    private fun buildTextQuery(name: String): String {
        val parts = mutableListOf<String>()
        parts.add("\"$name\"^2")  // Exact phrase boost
        name.split(Regex("\\s+")).filter { it.length >= 2 }.forEach { term ->
            parts.add(term)
            if (term.length >= 4) parts.add("$term~")  // Fuzzy
        }
        return parts.joinToString(" OR ")
    }

    /**
     * Check if candidate labels are compatible with suggested entity labels.
     * Uses the LabelCompatibilityStrategy logic.
     */
    private fun isLabelCompatible(
        suggested: SuggestedEntity,
        candidate: NamedEntityData,
        schema: DataDictionary
    ): Boolean {
        val strategy =
            matchStrategies.findStrategy<com.embabel.dice.common.resolver.matcher.LabelCompatibilityStrategy>()
        return strategy?.evaluate(suggested, candidate, schema) != MatchResult.NoMatch
    }

    /**
     * Filter candidates to only those with compatible labels.
     */
    private fun filterByLabelCompatibility(
        suggested: SuggestedEntity,
        candidates: List<SimilarityResult<NamedEntityData>>,
        schema: DataDictionary
    ): List<SimilarityResult<NamedEntityData>> {
        return candidates.filter { isLabelCompatible(suggested, it.match, schema) }
    }

    private fun createNewOrVeto(
        suggested: SuggestedEntity,
        schema: DataDictionary
    ): SuggestedEntityResolution {
        val labels = suggested.labels.map { it.substringAfterLast('.') }.toSet()
        val domainType = schema.domainTypeForLabels(labels)
        val creationPermitted = domainType?.creationPermitted ?: true

        return if (creationPermitted) {
            NewEntity(suggested)
        } else {
            VetoedEntity(suggested)
        }
    }

    companion object {
        /**
         * Create a hierarchical resolver with sensible defaults.
         */
        @JvmStatic
        fun create(
            repository: NamedEntityDataRepository,
            llmBakeoff: LlmCandidateBakeoff? = null,
        ): HierarchicalEntityResolver {
            return HierarchicalEntityResolver(
                repository = repository,
                matchStrategies = defaultMatchStrategies(),
                llmBakeoff = llmBakeoff,
                contextCompressor = ContextCompressor.default(),
            )
        }
    }
}

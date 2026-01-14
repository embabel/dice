package com.embabel.dice.common.resolver

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.ExistingEntity
import com.embabel.dice.common.NewEntity
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.common.VetoedEntity
import com.embabel.dice.common.resolver.matcher.LlmCandidateBakeoff
import com.embabel.dice.common.resolver.searcher.DefaultCandidateSearchers
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
 * Entity resolver that escalates through a chain of [CandidateSearcher]s,
 * stopping as soon as a confident match is found.
 *
 * Architecture:
 * - Each [CandidateSearcher] performs its own search and returns candidates
 * - If a searcher returns a confident match, resolution stops early
 * - Otherwise, candidates are accumulated for LLM arbitration
 * - LLM is the final arbiter, receiving all accumulated candidates
 *
 * Default search order (cheapest first):
 * 1. **Exact Match**: Direct ID/name lookup - instant, no LLM
 * 2. **Text Search**: Full-text search with heuristic matching - fast, no LLM
 * 3. **Vector Search**: High-confidence embedding similarity - moderate, no LLM
 * 4. **LLM Arbitration**: If no confident match, LLM decides from all candidates
 *
 * This approach minimizes LLM calls by handling easy cases with fast searchers
 * and only escalating to LLM for genuinely ambiguous resolutions.
 *
 * @param searchers The candidate searchers, ordered cheapest-first
 * @param llmArbiter Optional LLM to arbitrate when no confident match found (if null, creates new entity)
 * @param contextCompressor Optional compressor for reducing context size in LLM calls
 * @param config Configuration for behavior
 */
class EscalatingEntityResolver(
    private val searchers: List<CandidateSearcher>,
    private val llmArbiter: LlmCandidateBakeoff? = null,
    private val contextCompressor: ContextCompressor? = null,
    private val config: Config = Config(),
) : EntityResolver {

    /**
     * Configuration for escalating resolution behavior.
     */
    data class Config(
        /**
         * Skip LLM entirely - use only searchers.
         */
        val heuristicOnly: Boolean = false,
    )

    private val logger = LoggerFactory.getLogger(EscalatingEntityResolver::class.java)

    override fun resolve(
        suggestedEntities: SuggestedEntities,
        schema: DataDictionary
    ): Resolutions<SuggestedEntityResolution> {
        logger.info(
            "Escalating resolution of {} entities from chunks {}",
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
            "Escalating resolution complete: {}",
            levelCounts.entries.joinToString(", ") { "${it.key}=${it.value}" }
        )

        return Resolutions(
            chunkIds = suggestedEntities.chunkIds,
            resolutions = resolutions,
        )
    }

    /**
     * Resolve a single entity, escalating through searchers until confident.
     */
    private fun resolveWithEscalation(
        suggested: SuggestedEntity,
        schema: DataDictionary,
        sourceText: String?,
    ): LevelResult {
        val allCandidates = mutableListOf<NamedEntityData>()

        // Run each searcher in order
        for ((index, searcher) in searchers.withIndex()) {
            val result = searcher.search(suggested, schema)
            allCandidates.addAll(result.candidates)

            // If we got a confident match, return early
            if (result.confident != null) {
                val level = levelForSearcherIndex(index)
                logger.debug(
                    "{}: '{}' -> '{}' (searcher: {})",
                    level, suggested.name, result.confident.name, searcher::class.simpleName
                )
                return LevelResult(
                    level = level,
                    resolution = ExistingEntity(suggested, result.confident),
                    confidence = 0.95,
                    candidatesConsidered = allCandidates.size,
                )
            }
        }

        // No confident match from any searcher
        if (allCandidates.isEmpty()) {
            logger.debug("No candidates found for '{}'", suggested.name)
            return LevelResult(ResolutionLevel.NO_MATCH, null, 0.0)
        }

        // Deduplicate candidates
        val uniqueCandidates = allCandidates.distinctBy { it.id }

        // Stop here if heuristic-only mode or no LLM configured
        if (config.heuristicOnly || llmArbiter == null) {
            logger.debug("No LLM available/enabled for '{}' ({} candidates)", suggested.name, uniqueCandidates.size)
            return LevelResult(ResolutionLevel.NO_MATCH, null, 0.0, uniqueCandidates.size)
        }

        // Compress context for LLM call
        val compressedContext = contextCompressor?.compress(sourceText, suggested.name)
            ?: sourceText

        // Wrap candidates in SimilarityResult for LLM arbiter
        val candidateResults = uniqueCandidates.map {
            com.embabel.common.core.types.SimilarityResult(it, 0.8)
        }

        // LLM arbitration
        val level = if (uniqueCandidates.size == 1) {
            ResolutionLevel.LLM_VERIFICATION
        } else {
            ResolutionLevel.LLM_BAKEOFF
        }

        val bestMatch = llmArbiter.selectBestMatch(suggested, candidateResults, compressedContext)
        if (bestMatch != null) {
            logger.debug(
                "{}: '{}' -> '{}' (from {} candidates)",
                level, suggested.name, bestMatch.name, uniqueCandidates.size
            )
            return LevelResult(
                level = level,
                resolution = ExistingEntity(suggested, bestMatch),
                confidence = 0.8,
                candidatesConsidered = uniqueCandidates.size,
            )
        }

        return LevelResult(ResolutionLevel.NO_MATCH, null, 0.0, uniqueCandidates.size)
    }

    private fun levelForSearcherIndex(index: Int): ResolutionLevel {
        return when (index) {
            0 -> ResolutionLevel.EXACT_MATCH
            1 -> ResolutionLevel.HEURISTIC_MATCH
            2 -> ResolutionLevel.EMBEDDING_MATCH
            else -> ResolutionLevel.HEURISTIC_MATCH
        }
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
         * Create an escalating resolver with default searchers.
         *
         * @param repository The entity repository for search operations
         * @param llmArbiter Optional LLM to arbitrate when no confident match found
         */
        @JvmStatic
        fun create(
            repository: NamedEntityDataRepository,
            llmArbiter: LlmCandidateBakeoff? = null,
        ): EscalatingEntityResolver {
            return EscalatingEntityResolver(
                searchers = DefaultCandidateSearchers.create(repository),
                llmArbiter = llmArbiter,
                contextCompressor = ContextCompressor.default(),
            )
        }

        /**
         * Create an escalating resolver without vector search.
         *
         * @param repository The entity repository for search operations
         * @param llmArbiter Optional LLM to arbitrate when no confident match found
         */
        @JvmStatic
        fun withoutVector(
            repository: NamedEntityDataRepository,
            llmArbiter: LlmCandidateBakeoff? = null,
        ): EscalatingEntityResolver {
            return EscalatingEntityResolver(
                searchers = DefaultCandidateSearchers.withoutVector(repository),
                llmArbiter = llmArbiter,
                contextCompressor = ContextCompressor.default(),
            )
        }
    }
}

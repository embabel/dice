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
import com.embabel.dice.common.resolver.searcher.DefaultCandidateSearchers
import org.slf4j.LoggerFactory

/**
 * Which strategy successfully resolved an entity. Lower levels are faster and cheaper;
 * higher levels pull in the LLM.
 */
enum class ResolutionLevel {
    /** Exact name match against the repository — no LLM needed. */
    EXACT_MATCH,

    /** Heuristic strategies (normalized name, fuzzy) — no LLM needed. */
    HEURISTIC_MATCH,

    /** High-confidence embedding similarity — no LLM needed. */
    EMBEDDING_MATCH,

    /** Single-candidate LLM yes/no verification. */
    LLM_VERIFICATION,

    /** LLM picks the best match from several candidates. */
    LLM_BAKEOFF,

    /** No match found at any level. */
    NO_MATCH,
}

/**
 * The outcome of a single resolution attempt, including which level resolved it
 * and how many candidates were considered.
 */
data class LevelResult(
    val level: ResolutionLevel,
    val resolution: SuggestedEntityResolution?,
    val confidence: Double,
    val candidatesConsidered: Int = 0,
)

/**
 * Entity resolver that walks a chain of [CandidateSearcher]s from cheapest to most
 * expensive, stopping as soon as one returns a confident match. If no searcher is
 * confident, the accumulated candidates go to an optional LLM bakeoff; if that also
 * finds nothing, a new entity is minted (or vetoed if the schema forbids creation).
 *
 * Default search order:
 * 1. Exact name match — instant, no LLM
 * 2. Full-text / heuristic match — fast, no LLM
 * 3. Embedding similarity — moderate cost, no LLM
 * 4. LLM arbitration — only for genuinely ambiguous cases
 *
 * This is the recommended resolver for production. For dev/seed scenarios where you
 * never need to match against existing nodes, [AlwaysCreateEntityResolver] is simpler.
 * Use the [create][Companion.create] factory for the full chain (including vector search)
 * or [withoutVector][Companion.withoutVector] for stores without a vector index.
 *
 * @param searchers Candidate searchers in cheapest-first order
 * @param candidateBakeoff Optional LLM selector used when no searcher is confident; if null, a new entity is created
 * @param contextCompressor Optional compressor to trim source text before passing it to the bakeoff
 * @param config Behavior toggles, e.g. forcing heuristic-only mode
 */
class EscalatingEntityResolver(
    private val searchers: List<CandidateSearcher>,
    private val candidateBakeoff: CandidateBakeoff? = null,
    private val contextCompressor: ContextCompressor? = null,
    private val config: Config = Config(),
) : EntityResolver {

    /**
     * Behavior toggles for the escalating resolver.
     */
    data class Config(
        /** When true, the LLM bakeoff is never called — resolution stops at the searcher tier. */
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
     * Try to resolve one entity, walking searchers cheapest-first and stopping at the first confident hit.
     */
    private fun resolveWithEscalation(
        suggested: SuggestedEntity,
        schema: DataDictionary,
        sourceText: String?,
    ): LevelResult {
        val allCandidates = mutableListOf<NamedEntityData>()

        // Run each searcher in order
        for (searcher in searchers) {
            val result = searcher.search(suggested, schema)
            allCandidates.addAll(result.candidates)

            // If we got a confident match, return early
            if (result.confident != null) {
                val level = searcher.resolutionLevel
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
        if (config.heuristicOnly || candidateBakeoff == null) {
            logger.debug("No LLM available/enabled for '{}' ({} candidates)", suggested.name, uniqueCandidates.size)
            return LevelResult(ResolutionLevel.NO_MATCH, null, 0.0, uniqueCandidates.size)
        }

        // Compress context for LLM call
        val compressedContext = contextCompressor?.compress(sourceText, suggested.name)
            ?: sourceText

        // Wrap candidates in SimilarityResult for LLM candidateBakeoff
        val candidateResults = uniqueCandidates.map {
            com.embabel.common.core.types.SimilarityResult(it, 0.8)
        }

        // LLM arbitration
        val level = if (uniqueCandidates.size == 1) {
            ResolutionLevel.LLM_VERIFICATION
        } else {
            ResolutionLevel.LLM_BAKEOFF
        }

        val bestMatch = candidateBakeoff.selectBestMatch(suggested, candidateResults, compressedContext)
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

    /**
     * Return a copy with the specified candidate bakeoff.
     */
    fun withCandidateBakeoff(bakeoff: CandidateBakeoff): EscalatingEntityResolver =
        EscalatingEntityResolver(
            searchers = searchers,
            candidateBakeoff = bakeoff,
            contextCompressor = contextCompressor,
            config = config,
        )

    companion object {
        /**
         * Build a resolver with the full default searcher chain (exact → heuristic → vector → LLM).
         *
         * @param repository Entity repository used by the searchers
         * @param candidateBakeoff Optional LLM selector for ambiguous cases; null means mint a new entity
         */
        @JvmStatic
        fun create(
            repository: NamedEntityDataRepository,
            candidateBakeoff: CandidateBakeoff? = null,
        ): EscalatingEntityResolver {
            return EscalatingEntityResolver(
                searchers = DefaultCandidateSearchers.create(repository),
                candidateBakeoff = candidateBakeoff,
                contextCompressor = ContextCompressor.default(),
            )
        }

        /**
         * Build a resolver without the vector/embedding searcher — useful when the store
         * has no vector index.
         *
         * @param repository Entity repository used by the searchers
         * @param candidateBakeoff Optional LLM selector for ambiguous cases; null means mint a new entity
         */
        @JvmStatic
        fun withoutVector(
            repository: NamedEntityDataRepository,
            candidateBakeoff: CandidateBakeoff? = null,
        ): EscalatingEntityResolver {
            return EscalatingEntityResolver(
                searchers = DefaultCandidateSearchers.withoutVector(repository),
                candidateBakeoff = candidateBakeoff,
                contextCompressor = ContextCompressor.default(),
            )
        }
    }
}

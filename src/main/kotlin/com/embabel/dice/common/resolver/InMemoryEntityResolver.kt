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
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.ExistingEntity
import com.embabel.dice.common.NewEntity
import com.embabel.dice.common.Resolutions
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.common.resolver.searcher.FuzzyNameCandidateSearcher
import com.embabel.dice.common.resolver.searcher.NormalizedNameCandidateSearcher
import kotlin.math.min

/**
 * Entity resolver that remembers entities it's been asked to resolve
 * and tries to reuse them.
 * Useful for deduplicating entities within a single session.
 *
 * Uses name matching including:
 * - Label compatibility checking
 * - Case-insensitive exact matching
 * - Name normalization (removing titles/suffixes)
 * - Partial name matching (e.g., "Holmes" matches "Sherlock Holmes")
 * - Levenshtein distance for fuzzy matching
 *
 * @param config Configuration for matching thresholds
 */
class InMemoryEntityResolver(
    private val config: Config = Config(),
) : EntityResolver {

    data class Config(
        /**
         * Maximum Levenshtein distance (as a ratio of the shorter name length)
         * to consider two names as matching. Default is 0.2 (20%).
         */
        val maxDistanceRatio: Double = 0.2,
        /**
         * Minimum name length to apply fuzzy matching.
         * Short names are more prone to false positives.
         */
        val minLengthForFuzzy: Int = 4,
        /**
         * Minimum part length for partial name matching.
         */
        val minPartLength: Int = 4,
    )

    private val resolvedEntities = mutableMapOf<String, NamedEntityData>()

    override fun resolve(
        suggestedEntities: SuggestedEntities,
        schema: DataDictionary,
    ): Resolutions<SuggestedEntityResolution> {
        val resolutions = suggestedEntities.suggestedEntities.map { suggested ->
            val existingMatch = findMatch(suggested, schema)
            if (existingMatch != null) {
                ExistingEntity(suggested, existingMatch)
            } else {
                val newEntity = suggested.suggestedEntity
                resolvedEntities[newEntity.id] = newEntity
                NewEntity(suggested)
            }
        }
        return Resolutions(
            chunkIds = suggestedEntities.chunkIds,
            resolutions = resolutions,
        )
    }

    /**
     * Find a matching existing entity for the suggested entity.
     */
    private fun findMatch(suggested: SuggestedEntity, schema: DataDictionary): NamedEntityData? {
        for ((_, existing) in resolvedEntities) {
            if (isMatch(suggested, existing, schema)) {
                return existing
            }
        }
        return null
    }

    private fun isMatch(suggested: SuggestedEntity, existing: NamedEntityData, @Suppress("UNUSED_PARAMETER") schema: DataDictionary): Boolean {
        // Check label compatibility - require at least one matching label
        val suggestedLabels = suggested.labels.map { it.lowercase() }.toSet()
        val existingLabels = existing.labels().map { it.lowercase() }.toSet()
        if (suggestedLabels.intersect(existingLabels).isEmpty()) {
            return false
        }

        // Exact name match (case-insensitive)
        if (suggested.name.equals(existing.name, ignoreCase = true)) {
            return true
        }

        // Normalized name match
        val normalizedSuggested = NormalizedNameCandidateSearcher.normalizeName(suggested.name)
        val normalizedExisting = NormalizedNameCandidateSearcher.normalizeName(existing.name)
        if (normalizedSuggested.equals(normalizedExisting, ignoreCase = true)) {
            return true
        }

        // Partial name match (single word matching multi-word)
        if (isPartialMatch(normalizedSuggested, normalizedExisting)) {
            return true
        }

        // Fuzzy match (Levenshtein distance)
        if (isFuzzyMatch(normalizedSuggested, normalizedExisting)) {
            return true
        }

        return false
    }

    private fun isPartialMatch(name1: String, name2: String): Boolean {
        val parts1 = name1.lowercase().split(Regex("\\s+"))
        val parts2 = name2.lowercase().split(Regex("\\s+"))

        // Single word matching multi-word name
        if (parts1.size == 1 && parts2.size > 1) {
            val singleName = parts1[0]
            if (singleName.length >= config.minPartLength) {
                return parts2.any { it.equals(singleName, ignoreCase = true) && it.length >= config.minPartLength }
            }
        }
        if (parts2.size == 1 && parts1.size > 1) {
            val singleName = parts2[0]
            if (singleName.length >= config.minPartLength) {
                return parts1.any { it.equals(singleName, ignoreCase = true) && it.length >= config.minPartLength }
            }
        }
        return false
    }

    private fun isFuzzyMatch(name1: String, name2: String): Boolean {
        val lower1 = name1.lowercase()
        val lower2 = name2.lowercase()

        val minLength = min(lower1.length, lower2.length)
        if (minLength < config.minLengthForFuzzy) {
            return false
        }

        val distance = FuzzyNameCandidateSearcher.levenshteinDistance(lower1, lower2)
        val maxAllowedDistance = (minLength * config.maxDistanceRatio).toInt()

        return distance <= maxAllowedDistance
    }

    /**
     * Clear all resolved entities from memory.
     */
    fun clear() {
        resolvedEntities.clear()
    }

    /**
     * Get the number of resolved entities in memory.
     */
    fun size(): Int = resolvedEntities.size
}

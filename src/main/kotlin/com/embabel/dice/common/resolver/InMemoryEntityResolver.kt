package com.embabel.dice.common.resolver

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.core.DomainType
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.dice.common.EntityResolver
import com.embabel.dice.common.ExistingEntity
import com.embabel.dice.common.NewEntity
import com.embabel.dice.common.SuggestedEntities
import com.embabel.dice.common.SuggestedEntity
import com.embabel.dice.common.SuggestedEntityResolution
import com.embabel.dice.text2graph.*
import kotlin.collections.iterator
import kotlin.math.min

/**
 * Entity resolver that remembers entities it's been asked to resolve
 * and tries to reuse them.
 * Does probabilistic matching by entity name using:
 * - Case-insensitive exact matching
 * - Regex pattern matching for common variations
 * - Levenshtein distance for fuzzy matching
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
        val suggestedName = suggested.name
        val suggestedLabels = suggested.labels.toSet()

        for ((_, existing) in resolvedEntities) {
            // Only match entities with compatible labels (including type hierarchy)
            if (!labelsCompatible(suggestedLabels, existing.labels(), schema)) {
                continue
            }

            if (namesMatch(suggestedName, existing.name)) {
                return existing
            }
        }
        return null
    }

    /**
     * Check if labels are compatible.
     * Labels are compatible if:
     * 1. They share at least one label in common (case-insensitive), OR
     * 2. One type is a subtype of the other in the schema hierarchy
     *    (e.g., Criminal extends Person, so Criminal is compatible with Person), OR
     * 3. Both types share a common parent (e.g., Doctor and Detective both extend Person)
     */
    private fun labelsCompatible(labels1: Set<String>, labels2: Set<String>, schema: DataDictionary): Boolean {
        // Normalize labels to simple names for comparison (handles fully qualified like com.example.Person)
        val simple1 = labels1.map { it.substringAfterLast('.') }.filter { it != "Entity" }.toSet()
        val simple2 = labels2.map { it.substringAfterLast('.') }.filter { it != "Entity" }.toSet()

        // Direct label match (case-insensitive) - try both original and simple names
        if (labels1.any { l1 -> labels2.any { l2 -> l1.equals(l2, ignoreCase = true) } }) {
            return true
        }
        if (simple1.any { l1 -> simple2.any { l2 -> l1.equals(l2, ignoreCase = true) } }) {
            return true
        }

        // Check type hierarchy using schema - ONLY use simple labels since schema lookup
        // with fully qualified names can be unreliable
        val type1 = schema.domainTypeForLabels(simple1)
        val type2 = schema.domainTypeForLabels(simple2)

        if (type1 != null && type2 != null) {
            // Check if type1 is a subtype of type2 or vice versa
            if (isSubtypeOf(type1, type2, schema) || isSubtypeOf(type2, type1, schema)) {
                return true
            }
            // Check if they share a common parent (siblings like Doctor and Detective)
            if (shareCommonParent(type1, type2)) {
                return true
            }
        }

        return false
    }

    /**
     * Check if two types share a common parent (are siblings in the hierarchy).
     * E.g., Doctor and Detective both extend Person.
     */
    private fun shareCommonParent(type1: DomainType, type2: DomainType): Boolean {
        val parents1 = getAllParents(type1)
        val parents2 = getAllParents(type2)
        return parents1.any { p1 -> parents2.any { p2 -> p1.name.equals(p2.name, ignoreCase = true) } }
    }

    /**
     * Get all parents (ancestors) of a type.
     */
    private fun getAllParents(type: DomainType): Set<DomainType> {
        val parents = mutableSetOf<DomainType>()
        for (parent in type.parents) {
            parents.add(parent)
            parents.addAll(getAllParents(parent))
        }
        return parents
    }

    /**
     * Check if type1 is a subtype of type2 (type1 extends type2).
     */
    private fun isSubtypeOf(
        type1: DomainType,
        type2: DomainType,
        schema: DataDictionary
    ): Boolean {
        // Check direct parents
        for (parent in type1.parents) {
            if (parent.name.equals(type2.name, ignoreCase = true)) {
                return true
            }
            // Recursively check parent's parents
            if (isSubtypeOf(parent, type2, schema)) {
                return true
            }
        }
        return false
    }

    /**
     * Check if two names match using multiple strategies:
     * 1. Case-insensitive exact match
     * 2. Normalized match (ignoring common suffixes/prefixes)
     * 3. Partial name match (e.g., "Holmes" matches "Sherlock Holmes")
     * 4. Levenshtein distance for fuzzy matching
     */
    private fun namesMatch(name1: String, name2: String): Boolean {
        // Case-insensitive exact match
        if (name1.equals(name2, ignoreCase = true)) {
            return true
        }

        val normalized1 = normalizeName(name1)
        val normalized2 = normalizeName(name2)

        // Normalized exact match
        if (normalized1.equals(normalized2, ignoreCase = true)) {
            return true
        }

        // Partial name match - check if one name is a significant part of the other
        // e.g., "Holmes" matches "Sherlock Holmes", "Savage" matches "Victor Savage"
        if (isPartialNameMatch(normalized1, normalized2)) {
            return true
        }

        // Fuzzy matching using Levenshtein distance
        val minLength = min(normalized1.length, normalized2.length)
        if (minLength >= config.minLengthForFuzzy) {
            val distance = levenshteinDistance(normalized1.lowercase(), normalized2.lowercase())
            val maxAllowedDistance = (minLength * config.maxDistanceRatio).toInt()
            if (distance <= maxAllowedDistance) {
                return true
            }
        }

        return false
    }

    /**
     * Check if one name is a partial match of another.
     * Matches cases like "Holmes" to "Sherlock Holmes" or "Victor Savage" to "Savage".
     * The shorter name must be at least 4 characters to avoid false positives.
     */
    private fun isPartialNameMatch(name1: String, name2: String): Boolean {
        val parts1 = name1.lowercase().split(Regex("\\s+"))
        val parts2 = name2.lowercase().split(Regex("\\s+"))

        // If one is a single word and the other is multi-word,
        // check if the single word matches the last name (most common case)
        if (parts1.size == 1 && parts2.size > 1) {
            val singleName = parts1[0]
            // Must be at least 4 chars to avoid matching "Mr" etc
            if (singleName.length >= 4) {
                // Check if it matches any significant part of the full name
                return parts2.any { it.equals(singleName, ignoreCase = true) && it.length >= 4 }
            }
        }
        if (parts2.size == 1 && parts1.size > 1) {
            val singleName = parts2[0]
            if (singleName.length >= 4) {
                return parts1.any { it.equals(singleName, ignoreCase = true) && it.length >= 4 }
            }
        }

        return false
    }

    /**
     * Normalize a name by removing common variations.
     */
    private fun normalizeName(name: String): String {
        return name
            .trim()
            // Remove common titles
            .replace(Regex("^(Mr\\.?|Mrs\\.?|Ms\\.?|Dr\\.?|Prof\\.?)\\s+", RegexOption.IGNORE_CASE), "")
            // Remove common suffixes
            .replace(Regex("\\s+(Jr\\.?|Sr\\.?|II|III|IV)$", RegexOption.IGNORE_CASE), "")
            // Normalize whitespace
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Calculate the Levenshtein distance between two strings.
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length

        if (m == 0) return n
        if (n == 0) return m

        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,      // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }

        return dp[m][n]
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
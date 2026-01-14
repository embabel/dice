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
package com.embabel.dice.projection.memory

import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import java.time.Instant
import java.util.*

/**
 * Consolidates session propositions into long-term memory.
 * End-of-session process that decides what to keep, reinforce, or discard.
 */
interface MemoryConsolidator {

    /**
     * Consolidate session propositions into long-term memory.
     *
     * - High importance → promote to long-term
     * - Matches existing → reinforce confidence
     * - Low importance → discard
     *
     * @param sessionPropositions Propositions from the current session
     * @param existingPropositions Existing long-term propositions
     * @return Consolidation result with promoted, reinforced, discarded, and merged propositions
     */
    fun consolidate(
        sessionPropositions: List<Proposition>,
        existingPropositions: List<Proposition>,
    ): ConsolidationResult
}

/**
 * Result of memory consolidation.
 *
 * @property promoted Session propositions that became long-term memories
 * @property reinforced Existing propositions that were reinforced by session data
 * @property discarded Session propositions that were too low importance to keep
 * @property merged Propositions that were merged from multiple sources
 */
data class ConsolidationResult(
    val promoted: List<Proposition>,
    val reinforced: List<Proposition>,
    val discarded: List<Proposition>,
    val merged: List<PropositionMerge>,
) {
    /** Total number of propositions that will be stored */
    val storedCount: Int get() = promoted.size + reinforced.size + merged.size
}

/**
 * Represents a merge of multiple propositions into one.
 *
 * @property sources The original propositions that were merged
 * @property result The merged proposition
 */
data class PropositionMerge(
    val sources: List<Proposition>,
    val result: Proposition,
)

/**
 * Default implementation of MemoryConsolidator.
 *
 * @param promotionThreshold Minimum confidence to promote to long-term
 * @param similarityThreshold Minimum similarity to consider propositions as duplicates
 * @param reinforcementBoost Confidence boost when reinforcing existing propositions
 */
class DefaultMemoryConsolidator(
    private val promotionThreshold: Double = 0.6,
    private val similarityThreshold: Double = 0.7,
    private val reinforcementBoost: Double = 0.1,
) : MemoryConsolidator {

    override fun consolidate(
        sessionPropositions: List<Proposition>,
        existingPropositions: List<Proposition>,
    ): ConsolidationResult {
        val promoted = mutableListOf<Proposition>()
        val reinforced = mutableListOf<Proposition>()
        val discarded = mutableListOf<Proposition>()
        val merged = mutableListOf<PropositionMerge>()

        for (sessionProp in sessionPropositions) {
            // Find similar existing propositions
            val similar = findSimilarPropositions(sessionProp, existingPropositions)

            when {
                similar.isNotEmpty() -> {
                    // Reinforce or merge with existing
                    val bestMatch = similar.maxByOrNull { it.second }!!.first
                    val similarity = similar.maxOf { it.second }

                    if (similarity > 0.9) {
                        // Very similar - reinforce the existing one
                        val reinforcedProp = reinforceProposition(bestMatch, sessionProp)
                        reinforced.add(reinforcedProp)
                    } else {
                        // Somewhat similar - merge them
                        val mergedProp = mergePropositions(listOf(bestMatch, sessionProp))
                        merged.add(PropositionMerge(
                            sources = listOf(bestMatch, sessionProp),
                            result = mergedProp,
                        ))
                    }
                }

                sessionProp.confidence >= promotionThreshold -> {
                    // High confidence, no similar existing - promote to long-term
                    promoted.add(sessionProp.copy(status = PropositionStatus.ACTIVE))
                }

                else -> {
                    // Low confidence, no match - discard
                    discarded.add(sessionProp)
                }
            }
        }

        return ConsolidationResult(
            promoted = promoted,
            reinforced = reinforced,
            discarded = discarded,
            merged = merged,
        )
    }

    /**
     * Find existing propositions similar to the given one.
     * Returns pairs of (proposition, similarity score).
     */
    private fun findSimilarPropositions(
        proposition: Proposition,
        existingPropositions: List<Proposition>,
    ): List<Pair<Proposition, Double>> {
        return existingPropositions
            .map { existing -> existing to calculateSimilarity(proposition, existing) }
            .filter { (_, similarity) -> similarity >= similarityThreshold }
            .sortedByDescending { (_, similarity) -> similarity }
    }

    /**
     * Calculate similarity between two propositions.
     * Uses text overlap and entity overlap.
     */
    private fun calculateSimilarity(a: Proposition, b: Proposition): Double {
        // Text similarity (Jaccard)
        val aWords = a.text.lowercase().split(Regex("\\s+")).toSet()
        val bWords = b.text.lowercase().split(Regex("\\s+")).toSet()
        val textSimilarity = if (aWords.isEmpty() && bWords.isEmpty()) 1.0
        else aWords.intersect(bWords).size.toDouble() / aWords.union(bWords).size

        // Entity overlap
        val aEntities = a.mentions.mapNotNull { it.resolvedId }.toSet()
        val bEntities = b.mentions.mapNotNull { it.resolvedId }.toSet()
        val entitySimilarity = if (aEntities.isEmpty() && bEntities.isEmpty()) 0.5
        else if (aEntities.isEmpty() || bEntities.isEmpty()) 0.0
        else aEntities.intersect(bEntities).size.toDouble() / aEntities.union(bEntities).size

        // Weighted combination
        return (textSimilarity * 0.7) + (entitySimilarity * 0.3)
    }

    /**
     * Reinforce an existing proposition with evidence from a session proposition.
     * Boosts confidence and updates grounding.
     */
    private fun reinforceProposition(
        existing: Proposition,
        session: Proposition,
    ): Proposition {
        val newConfidence = (existing.confidence + reinforcementBoost).coerceAtMost(1.0)
        val newGrounding = (existing.grounding + session.grounding).distinct()

        return existing.copy(
            confidence = newConfidence,
            grounding = newGrounding,
            revised = Instant.now(),
        )
    }

    /**
     * Merge multiple propositions into one.
     * Takes the highest confidence text and combines grounding.
     */
    private fun mergePropositions(propositions: List<Proposition>): Proposition {
        val bestProp = propositions.maxByOrNull { it.confidence }!!
        val allGrounding = propositions.flatMap { it.grounding }.distinct()
        val avgConfidence = propositions.map { it.confidence }.average()

        return bestProp.copy(
            id = UUID.randomUUID().toString(),
            confidence = avgConfidence,
            grounding = allGrounding,
            created = Instant.now(),
            revised = Instant.now(),
        )
    }
}

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
import com.embabel.dice.proposition.revision.ReinforcementRule
import com.embabel.dice.proposition.revision.reinforce
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
     * A session proposition whose evidence the stored one already holds leaves it unchanged.
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
 * @property reinforced Stored propositions that a session proposition matched, one entry each, with every match's evidence folded in order. An entry equals the stored proposition when no match brought new evidence.
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
 * Default implementation of MemoryConsolidator. It decides, for each session proposition, whether to
 * reinforce a stored one, merge with it, promote the session proposition or discard it.
 *
 * A similarity above 0.9 reinforces the stored proposition. A similarity from `similarityThreshold`
 * up to 0.9 merges the two. Any other session proposition is promoted when its confidence is at or
 * above `promotionThreshold`, and discarded otherwise.
 *
 * Reinforcement raises the stored confidence by `reinforcementBoost`, capped at 1.0, and never
 * lowers it. The session proposition's grounding, provenance entries and source ids are folded in,
 * and the reinforce count goes up by one. Decay and last-accessed time do not change. When the
 * stored proposition already holds all of the session proposition's evidence, it is returned
 * unchanged.
 *
 * @param promotionThreshold 0.0 or greater. A value above 1.0 never promotes.
 * @param similarityThreshold 0.0 or greater. A value above 1.0 never matches.
 * @param reinforcementBoost Added to the stored confidence when a session proposition reinforces it, from 0.0 to 1.0. The result is capped at 1.0.
 * @throws IllegalArgumentException when a threshold is negative or NaN, or the boost is outside 0.0 to 1.0
 */
class DefaultMemoryConsolidator(
    private val promotionThreshold: Double = 0.6,
    private val similarityThreshold: Double = 0.7,
    private val reinforcementBoost: Double = 0.1,
) : MemoryConsolidator {

    init {
        require(promotionThreshold >= 0.0) { "promotionThreshold must be 0.0 or greater, was $promotionThreshold" }
        require(similarityThreshold >= 0.0) { "similarityThreshold must be 0.0 or greater, was $similarityThreshold" }
        require(reinforcementBoost in 0.0..1.0) { "reinforcementBoost must be between 0.0 and 1.0, was $reinforcementBoost" }
    }

    /** A session proposition that matches a stored one gives a fixed confidence lift, capped at 1. */
    private val reinforcementRule = ReinforcementRule(
        confidence = { existing, _ -> (existing.confidence + reinforcementBoost).coerceAtMost(1.0) },
    )

    override fun consolidate(
        sessionPropositions: List<Proposition>,
        existingPropositions: List<Proposition>,
    ): ConsolidationResult {
        val promoted = mutableListOf<Proposition>()
        // Saving two copies of one stored proposition keeps only one. Each match folds into the latest copy.
        val latestById = LinkedHashMap<String, Proposition>()
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
                    val current = latestById[bestMatch.id] ?: bestMatch

                    if (similarity > 0.9) {
                        // Very similar - reinforce the existing one
                        latestById[bestMatch.id] = reinforceProposition(current, sessionProp)
                    } else {
                        // Somewhat similar - merge them
                        val mergedProp = mergePropositions(listOf(current, sessionProp))
                        merged.add(PropositionMerge(
                            sources = listOf(current, sessionProp),
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
            reinforced = latestById.values.toList(),
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
     * Strengthens an existing proposition with evidence from a session proposition.
     *
     * @param existing The stored proposition
     * @param session The session proposition that backs it up
     * @return The stored proposition when it already holds the evidence, otherwise a reinforced copy
     */
    private fun reinforceProposition(
        existing: Proposition,
        session: Proposition,
    ): Proposition = reinforce(existing, session, reinforcementRule)

    /**
     * Merges similar propositions into one new proposition. The text and fields come from the most
     * confident source, and its grounding, provenance entries and source ids are combined from all sources.
     *
     * @param propositions The stored and session propositions to merge
     * @return A new proposition with the average confidence and one timestamp for all four times
     */
    private fun mergePropositions(propositions: List<Proposition>): Proposition {
        val bestProp = propositions.maxByOrNull { it.confidence }!!
        val now = Instant.now()
        return bestProp.copy(
            id = UUID.randomUUID().toString(),
            confidence = propositions.map { it.confidence }.average(),
            grounding = propositions.flatMap { it.grounding }.distinct(),
            provenanceEntries = propositions.flatMap { it.provenanceEntries }.distinct(),
            sourceIds = propositions.flatMap { it.sourceIds }.distinct(),
            created = now,
            contentRevised = now,
            metadataRevised = now,
            lastAccessed = now,
        )
    }
}

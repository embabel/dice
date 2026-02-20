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
package com.embabel.dice.proposition.revision

import com.embabel.agent.api.common.Ai
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.core.types.SimilarityCutoff
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.embabel.common.util.trim
import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionQuery
import com.embabel.dice.proposition.PropositionRepository
import com.embabel.dice.proposition.PropositionStatus
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Locale

/**
 * Proposition needing LLM classification — produced by [retrieveAndFastPath]
 * when neither canonical match nor auto-merge can resolve it.
 */
internal data class PendingClassification(
    val newProposition: Proposition,
    val candidates: List<Proposition>,
)

/**
 * LLM-based implementation of PropositionReviser.
 * Uses structured output to classify and revise propositions.
 *
 * Example usage:
 * ```kotlin
 * val reviser = LlmPropositionReviser
 *     .withLlm(llmOptions)
 *     .withAi(ai)
 *     .withAutoMergeThreshold(0.95)
 *     .withClassifyBatchSize(15)
 *     .withClassifyLlm(cheaperLlmOptions)  // optional: cheaper model for classification
 * ```
 *
 * @param llmOptions LLM configuration
 * @param ai AI service for LLM calls
 * @param topK Number of similar propositions to retrieve for classification
 * @param similarityThreshold Minimum similarity threshold - skip LLM if no candidates above this
 * @param minSimilarityForReinforce Minimum LLM-reported similarity to accept SIMILAR classification (default 0.7)
 * @param decayK Decay constant for time-based confidence reduction
 * @param autoMergeThreshold Embedding similarity at or above which propositions are auto-merged without LLM. Set to 1.1 to disable.
 * @param classifyBatchSize Maximum number of propositions to classify in a single LLM call
 * @param entityOverlapFilter When true, candidates that share no entity mentions with the new proposition are
 *   filtered out before LLM classification. This eliminates UNRELATED candidates cheaply via set intersection
 *   instead of an LLM call. Propositions with no entity mentions bypass this filter.
 * @param classifyLlmOptions Optional separate LLM configuration for classification calls. When null (default),
 *   uses the main [llmOptions]. Classification is a structured categorization task that can often use a
 *   smaller/cheaper model than extraction without loss of quality.
 */
data class LlmPropositionReviser(
    private val llmOptions: LlmOptions,
    private val ai: Ai,
    override val topK: Int = 5,
    override val similarityThreshold: Double = 0.5,
    private val minSimilarityForReinforce: Double = 0.7,
    private val decayK: Double = 2.0,
    private val autoMergeThreshold: Double = 0.95,
    private val classifyBatchSize: Int = 15,
    private val entityOverlapFilter: Boolean = true,
    private val classifyLlmOptions: LlmOptions? = null,
) : PropositionReviser, SimilarityCutoff {

    companion object {

        @JvmStatic
        fun withLlm(llm: LlmOptions): Builder = Builder(llm)

        class Builder(private val llmOptions: LlmOptions) {

            fun withAi(ai: Ai): LlmPropositionReviser =
                LlmPropositionReviser(
                    llmOptions = llmOptions,
                    ai = ai,
                )
        }
    }

    private val logger = LoggerFactory.getLogger(LlmPropositionReviser::class.java)

    /** LLM options used for classification calls — falls back to [llmOptions] when [classifyLlmOptions] is null. */
    private val effectiveClassifyLlm: LlmOptions get() = classifyLlmOptions ?: llmOptions

    /**
     * Canonicalize proposition text for cheap string comparison.
     * Strips case, punctuation, and extra whitespace so that
     * "Claudia Carter has been at Meridian Labs for about 3 years."
     * matches "Claudia Carter has been at Meridian Labs for about 3 years"
     * without an LLM call.
     */
    private fun canonicalize(text: String): String =
        text.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * Set the number of similar propositions to retrieve for classification.
     */
    fun withTopK(topK: Int): LlmPropositionReviser =
        copy(topK = topK)

    /**
     * Set the minimum similarity threshold.
     * Candidates below this threshold are skipped (no LLM call).
     */
    fun withSimilarityThreshold(threshold: Double): LlmPropositionReviser =
        copy(similarityThreshold = threshold)

    /**
     * Set the minimum similarity score for SIMILAR classifications to be accepted.
     * If the LLM classifies as SIMILAR but with a score below this threshold,
     * the classification is treated as UNRELATED.
     */
    fun withMinSimilarityForReinforce(threshold: Double): LlmPropositionReviser =
        copy(minSimilarityForReinforce = threshold)

    /**
     * Set the decay constant for time-based confidence reduction.
     */
    fun withDecayK(k: Double): LlmPropositionReviser =
        copy(decayK = k)

    /**
     * Set the auto-merge threshold. Embedding similarity at or above this
     * value causes automatic merging without an LLM call. Set to 1.1 to disable.
     */
    fun withAutoMergeThreshold(threshold: Double): LlmPropositionReviser =
        copy(autoMergeThreshold = threshold)

    /**
     * Set the batch size for LLM classification calls.
     */
    fun withClassifyBatchSize(size: Int): LlmPropositionReviser =
        copy(classifyBatchSize = size)

    /**
     * Enable or disable the entity-overlap pre-filter.
     * When enabled, candidates that share no entity mentions with the new proposition
     * are filtered out before LLM classification, saving LLM calls.
     */
    fun withEntityOverlapFilter(enabled: Boolean): LlmPropositionReviser =
        copy(entityOverlapFilter = enabled)

    /**
     * Set a separate LLM for classification calls. Classification is a structured
     * categorization task (pick from 5 labels) that can use a cheaper/faster model
     * than extraction without loss of quality.
     *
     * Example:
     * ```kotlin
     * val reviser = LlmPropositionReviser
     *     .withLlm(LlmOptions("gpt-4o"))        // extraction model
     *     .withAi(ai)
     *     .withClassifyLlm(LlmOptions("gpt-4o-mini"))  // cheaper for classification
     * ```
     */
    fun withClassifyLlm(llm: LlmOptions): LlmPropositionReviser =
        copy(classifyLlmOptions = llm)

    /**
     * Deduplicate a batch of propositions by canonical text, then use fast-path
     * (canonical match + auto-merge) where possible and batch the rest into
     * as few LLM calls as possible.
     */
    override fun reviseAll(
        propositions: List<Proposition>,
        repository: PropositionRepository,
    ): List<RevisionResult> {
        // 1. Canonical text dedup within the batch
        val seen = mutableSetOf<String>()
        val deduped = propositions.filter { seen.add(canonicalize(it.text)) }
        val dropped = propositions.size - deduped.size
        if (dropped > 0) {
            logger.info("Batch dedup: dropped {} of {} propositions with identical canonical text", dropped, propositions.size)
        }

        // 2. For each proposition: embedding search + fast paths
        val results = mutableListOf<Pair<Int, RevisionResult>>()
        val pending = mutableListOf<Pair<Int, PendingClassification>>()

        for ((index, prop) in deduped.withIndex()) {
            when (val outcome = retrieveAndFastPath(prop, repository)) {
                is RevisionResult -> results.add(index to outcome)
                is PendingClassification -> pending.add(index to outcome)
            }
        }

        // 3. Batch classify remaining propositions
        if (pending.isNotEmpty()) {
            logger.info(
                "Batch classify: {} of {} propositions need LLM (batch size {})",
                pending.size, deduped.size, classifyBatchSize
            )
            val pendingItems = pending.map { it.second }
            val batchResults = classifyBatch(pendingItems, repository)
            for ((i, result) in batchResults.withIndex()) {
                results.add(pending[i].first to result)
            }
        }

        // Return in original order
        return results.sortedBy { it.first }.map { it.second }
    }

    /**
     * Retrieve candidates and attempt fast-path resolution.
     * Returns a [RevisionResult] if resolved, or a [PendingClassification] if LLM is needed.
     */
    internal fun retrieveAndFastPath(
        newProposition: Proposition,
        repository: PropositionRepository,
    ): Any {
        // Fast path 1: exact canonical text match — runs before vector search
        // because it's cheaper (no embedding call) and catches duplicates that
        // vector indexes miss due to eventual consistency / index lag.
        val newCanonical = canonicalize(newProposition.text)
        val contextProps = repository.query(
            PropositionQuery(
                contextId = newProposition.contextId,
                status = PropositionStatus.ACTIVE,
            )
        )
        val canonicalMatch = contextProps.find { canonicalize(it.text) == newCanonical }
        if (canonicalMatch != null) {
            val original = repository.findById(canonicalMatch.id) ?: canonicalMatch
            val merged = mergePropositions(original, newProposition)
            logger.debug("Canonical text match: {}", newProposition.text.take(60))
            return RevisionResult.Merged(original, merged)
        }

        // Fast path 2: vector similarity search
        val similarWithScores = repository.findSimilarWithScores(
            TextSimilaritySearchRequest(
                query = newProposition.text,
                topK = topK,
                similarityThreshold = similarityThreshold,
            ),
            PropositionQuery(
                contextId = newProposition.contextId,
                status = PropositionStatus.ACTIVE,
            ),
        )

        if (similarWithScores.isEmpty()) {
            logger.debug("New proposition (no canonical or embedding match): {}", newProposition.text)
            return RevisionResult.New(newProposition)
        }

        logger.debug(
            "Found {} candidates above {} similarity for: {}",
            similarWithScores.size, similarityThreshold, newProposition.text.take(50)
        )

        // Fast path 3: auto-merge at high embedding similarity
        val topCandidate = similarWithScores.first()
        if (topCandidate.score >= autoMergeThreshold) {
            val original = repository.findById(topCandidate.match.id) ?: topCandidate.match
            val merged = mergePropositions(original, newProposition)
            logger.debug(
                "Auto-merge (embedding score {} >= {}): {}",
                topCandidate.score, autoMergeThreshold, newProposition.text.take(60)
            )
            return RevisionResult.Merged(original, merged)
        }

        // Apply decay for ranking
        val decayed = similarWithScores.map { it.match.withDecayApplied(decayK) }

        // Entity-overlap pre-filter: skip LLM for candidates that share no entities
        // with the new proposition. Only applied when the new proposition has mentions
        // (otherwise we can't determine overlap and must fall through to LLM).
        val candidates = if (entityOverlapFilter && newProposition.mentions.isNotEmpty()) {
            val filtered = decayed.filter { hasEntityOverlap(newProposition, it) }
            val eliminated = decayed.size - filtered.size
            if (eliminated > 0) {
                logger.debug(
                    "Entity-overlap filter eliminated {} of {} candidates for: {}",
                    eliminated, decayed.size, newProposition.text.take(60)
                )
            }
            if (filtered.isEmpty()) {
                logger.debug("All candidates eliminated by entity-overlap filter, treating as new: {}", newProposition.text.take(60))
                return RevisionResult.New(newProposition)
            }
            filtered
        } else {
            decayed
        }

        return PendingClassification(newProposition, candidates)
    }

    /**
     * Classify a batch of pending propositions using as few LLM calls as possible.
     * Groups items into chunks of [classifyBatchSize] and makes one LLM call per chunk.
     */
    internal fun classifyBatch(
        items: List<PendingClassification>,
        repository: PropositionRepository,
    ): List<RevisionResult> {
        if (items.isEmpty()) return emptyList()

        // Single item — use the existing single-proposition classify path
        if (items.size == 1) {
            val item = items.first()
            val classified = classify(item.newProposition, item.candidates)
            return listOf(classifiedToResult(item.newProposition, classified, repository))
        }

        val allResults = mutableListOf<RevisionResult>()

        for (chunk in items.chunked(classifyBatchSize)) {
            // Build template data for the batch — use integer indices instead of UUIDs
            val itemsData = chunk.map { item ->
                mapOf(
                    "newProposition" to mapOf(
                        "text" to item.newProposition.text,
                        "confidence" to item.newProposition.confidence,
                        "reasoning" to (item.newProposition.reasoning ?: "N/A"),
                    ),
                    "candidates" to item.candidates.mapIndexed { idx, p ->
                        mapOf(
                            "id" to idx.toString(),
                            "text" to p.text,
                            "confidence" to p.effectiveConfidence(),
                        )
                    },
                )
            }

            val response = ai
                .withLlm(effectiveClassifyLlm)
                .withId("classify-propositions-batch")
                .creating(BatchClassificationResponse::class.java)
                .fromTemplate(
                    "dice/classify_propositions_batch",
                    mapOf("items" to itemsData)
                )

            logger.info(
                "Batch classified {} propositions in one LLM call",
                chunk.size,
            )

            // Map batch response back to RevisionResults
            for ((chunkIndex, item) in chunk.withIndex()) {
                val propClassifications = response.propositions
                    .find { it.propositionIndex == chunkIndex }

                if (propClassifications == null) {
                    logger.warn(
                        "No classification returned for batch index {}, treating as new: {}",
                        chunkIndex, item.newProposition.text.take(60)
                    )
                    allResults.add(RevisionResult.New(item.newProposition))
                    continue
                }

                // Map integer indices back to actual candidates
                val classified = propClassifications.classifications.mapNotNull { classification ->
                    val idx = classification.propositionId.toIntOrNull()
                    val candidate = if (idx != null && idx in item.candidates.indices) {
                        item.candidates[idx]
                    } else {
                        logger.warn("Invalid candidate index '{}' in batch classification response", classification.propositionId)
                        return@mapNotNull null
                    }
                    ClassifiedProposition(
                        proposition = candidate,
                        relation = parseRelation(classification.relation),
                        similarity = classification.similarity.coerceIn(0.0, 1.0),
                        reasoning = classification.reasoning,
                    )
                }

                allResults.add(classifiedToResult(item.newProposition, classified, repository))
            }
        }

        return allResults
    }

    /**
     * Single-proposition revise — uses [retrieveAndFastPath] then falls back to
     * single-proposition LLM classify for backward compatibility.
     */
    override fun revise(
        newProposition: Proposition,
        repository: PropositionRepository,
    ): RevisionResult {
        return when (val outcome = retrieveAndFastPath(newProposition, repository)) {
            is RevisionResult -> outcome
            is PendingClassification -> {
                val classified = classify(outcome.newProposition, outcome.candidates)
                classifiedToResult(outcome.newProposition, classified, repository)
            }
            else -> RevisionResult.New(newProposition)
        }
    }

    /**
     * Convert classified propositions into a RevisionResult.
     */
    private fun classifiedToResult(
        newProposition: Proposition,
        classified: List<ClassifiedProposition>,
        repository: PropositionRepository,
    ): RevisionResult {
        val identical = classified.find { it.relation == PropositionRelation.IDENTICAL }
        val contradictory = classified.find { it.relation == PropositionRelation.CONTRADICTORY }
        val generalizes = classified.filter { it.relation == PropositionRelation.GENERALIZES }
        val mostSimilar = classified
            .filter { it.relation == PropositionRelation.SIMILAR && it.similarity >= minSimilarityForReinforce }
            .maxByOrNull { it.similarity }

        // Log rejected SIMILAR classifications for debugging
        val rejectedSimilar = classified.filter {
            it.relation == PropositionRelation.SIMILAR && it.similarity < minSimilarityForReinforce
        }
        if (rejectedSimilar.isNotEmpty()) {
            logger.debug(
                "Rejected {} SIMILAR classifications with low similarity (< {}): {}",
                rejectedSimilar.size,
                minSimilarityForReinforce,
                rejectedSimilar.map { "${it.proposition.id.take(8)}=${it.similarity}" }
            )
        }

        return when {
            identical != null -> {
                val original = repository.findById(identical.proposition.id)
                    ?: identical.proposition
                val merged = mergePropositions(original, newProposition)
                logger.debug("Merged: {} + {} -> {}", original.text, newProposition.text, merged.text)
                RevisionResult.Merged(original, merged)
            }

            contradictory != null -> {
                val original = repository.findById(contradictory.proposition.id)
                    ?: contradictory.proposition
                val reducedConfidence = (original.confidence * 0.3).coerceAtLeast(0.05)
                // Accelerate decay so contradicted propositions fade faster
                val acceleratedDecay = (original.decay + 0.15).coerceAtMost(1.0)
                val contradicted = original
                    .withConfidence(reducedConfidence)
                    .withStatus(PropositionStatus.CONTRADICTED)
                    .copy(decay = acceleratedDecay, lastAccessed = Instant.now())
                logger.debug(
                    "Contradicted: {} (conf: {}, decay: {}) vs new: {}",
                    original.text, reducedConfidence, acceleratedDecay, newProposition.text
                )
                RevisionResult.Contradicted(contradicted, newProposition)
            }

            generalizes.isNotEmpty() -> {
                val generalizedProps = generalizes.map { it.proposition }
                logger.debug(
                    "Generalized: {} generalizes {} existing propositions",
                    newProposition.text, generalizedProps.size
                )
                RevisionResult.Generalized(newProposition, generalizedProps)
            }

            mostSimilar != null -> {
                val original = repository.findById(mostSimilar.proposition.id)
                    ?: mostSimilar.proposition
                val revised = reinforceProposition(original, newProposition)
                logger.debug("Reinforced: {} -> {}", original.text, revised.text)
                RevisionResult.Reinforced(original, revised)
            }

            else -> {
                logger.debug("New proposition (unrelated): {}", newProposition.text)
                RevisionResult.New(newProposition)
            }
        }
    }

    override fun classify(
        newProposition: Proposition,
        candidates: List<Proposition>,
    ): List<ClassifiedProposition> {
        if (candidates.isEmpty()) return emptyList()

        // Use integer indices instead of UUIDs to prevent LLM hallucination of IDs.
        // UUIDs are 36 chars that the LLM can corrupt; integer indices are 1-2 chars.
        val candidateData = candidates.mapIndexed { idx, p ->
            mapOf(
                "id" to idx.toString(),
                "text" to p.text,
                "confidence" to p.effectiveConfidence(),
            )
        }

        val response = ai
            .withLlm(effectiveClassifyLlm)
            .withId("classify-proposition")
            .creating(ClassificationResponse::class.java)
            .fromTemplate(
                "dice/classify_proposition",
                mapOf(
                    "newProposition" to mapOf(
                        "text" to newProposition.text,
                        "confidence" to newProposition.confidence,
                        "reasoning" to (newProposition.reasoning ?: "N/A"),
                    ),
                    "candidates" to candidateData,
                )
            )
        logger.info(
            "Classified proposition {} against {} candidates:\n\t{}",
            trim(s = newProposition.text, max = 60, keepRight = 3),
            candidates.size,
            response.classifications.joinToString { "${it.propositionId}=${it.relation}" }
        )

        // Map integer indices back to actual candidates
        return response.classifications.mapNotNull { classification ->
            val idx = classification.propositionId.toIntOrNull()
            val candidate = if (idx != null && idx in candidates.indices) {
                candidates[idx]
            } else {
                logger.warn("Invalid candidate index '{}' in classification response, skipping", classification.propositionId)
                return@mapNotNull null
            }
            ClassifiedProposition(
                proposition = candidate,
                relation = parseRelation(classification.relation),
                similarity = classification.similarity.coerceIn(0.0, 1.0),
                reasoning = classification.reasoning,
            )
        }
    }

    /**
     * Check whether two propositions share at least one entity mention,
     * using resolved IDs when available and falling back to case-insensitive span comparison.
     * Returns true if either proposition has no mentions (can't determine overlap).
     */
    internal fun hasEntityOverlap(a: Proposition, b: Proposition): Boolean {
        if (a.mentions.isEmpty() || b.mentions.isEmpty()) return true
        for (mentionA in a.mentions) {
            for (mentionB in b.mentions) {
                if (mentionA.resolvedId != null && mentionB.resolvedId != null) {
                    if (mentionA.resolvedId == mentionB.resolvedId) return true
                } else if (mentionA.span.equals(mentionB.span, ignoreCase = true)) {
                    return true
                }
            }
        }
        return false
    }

    private fun parseRelation(relation: String): PropositionRelation =
        when (relation.uppercase()) {
            "IDENTICAL" -> PropositionRelation.IDENTICAL
            "SIMILAR" -> PropositionRelation.SIMILAR
            "CONTRADICTORY" -> PropositionRelation.CONTRADICTORY
            "GENERALIZES" -> PropositionRelation.GENERALIZES
            else -> PropositionRelation.UNRELATED
        }

    /**
     * Merge two propositions that express identical information.
     * Combines grounding, boosts confidence, uses most recent text.
     */
    private fun mergePropositions(existing: Proposition, new: Proposition): Proposition {
        // Boost confidence when we see the same information again
        val boostedConfidence = (existing.confidence + new.confidence * 0.3).coerceAtMost(0.99)
        // Slow decay — repeated confirmation means the fact is durable
        val slowedDecay = (existing.decay * 0.7).coerceAtLeast(0.0)
        // Combine grounding
        val combinedGrounding = (existing.grounding + new.grounding).distinct()

        return existing.copy(
            confidence = boostedConfidence,
            decay = slowedDecay,
            grounding = combinedGrounding,
            reinforceCount = existing.reinforceCount + 1,
            revised = Instant.now(),
            lastAccessed = Instant.now(),
        )
    }

    /**
     * Reinforce an existing proposition with new supporting evidence.
     * Slightly boosts confidence and adds grounding.
     */
    private fun reinforceProposition(existing: Proposition, new: Proposition): Proposition {
        // Smaller confidence boost for similar (not identical)
        val boostedConfidence = (existing.confidence + new.confidence * 0.1).coerceAtMost(0.95)
        // Slow decay slightly — corroborating evidence extends shelf life
        val slowedDecay = (existing.decay * 0.85).coerceAtLeast(0.0)
        // Combine grounding
        val combinedGrounding = (existing.grounding + new.grounding).distinct()

        return existing.copy(
            confidence = boostedConfidence,
            decay = slowedDecay,
            grounding = combinedGrounding,
            reinforceCount = existing.reinforceCount + 1,
            revised = Instant.now(),
            lastAccessed = Instant.now(),
        )
    }
}

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
package com.embabel.dice.proposition

import com.embabel.agent.core.ContextId
import com.embabel.agent.rag.model.Retrievable
import com.embabel.common.core.types.ZeroToOne
import java.time.Instant
import java.util.*

/**
 * The lifecycle status of a proposition.
 */
enum class PropositionStatus {

    /** Current belief, actively used for queries and projections */
    ACTIVE,

    /** Refined by a more specific proposition */
    SUPERSEDED,

    /** Conflicting evidence reduced confidence to ~0 */
    CONTRADICTED,

    /** Successfully projected to typed graph (Neo4j/Prolog) */
    PROMOTED
}

/**
 * A proposition is a natural language statement with typed entity mentions.
 * Propositions are the system of record - all other representations
 * (Neo4j relationships, Prolog facts, vector embeddings) derive from them.
 *
 * **Design: One Proposition = One Relationship**
 *
 * Each proposition should express a single fact with at most two entity mentions
 * (SUBJECT and OBJECT). This maps cleanly to graph relationships during promotion.
 * Complex sentences with multiple relationships should be extracted as multiple
 * propositions during the extraction phase.
 *
 * @property id Unique identifier for this proposition
 * @property contextId The context in which this proposition is relevant
 * @property text The statement in natural language (e.g., "Jim is an expert in GOAP")
 * @property mentions Entity references within the text (typically 1-2, with SUBJECT/OBJECT roles)
 * @property confidence LLM-generated certainty (0.0-1.0)
 * @property decay Staleness rate (0.0-1.0). High decay = information becomes stale quickly
 * @property importance How much this fact matters (0.0-1.0). Orthogonal to confidence.
 * @property reasoning LLM explanation for why this was extracted
 * @property grounding Chunk IDs that support this proposition
 * @property created When the proposition was first created
 * @property revised When the proposition was last updated
 * @property status Current lifecycle status
 * @property level Abstraction level: 0 = raw observation, 1+ = derived abstraction
 * @property sourceIds IDs of propositions this was abstracted from (empty for level 0)
 * @property reinforceCount How many times this proposition has been merged or reinforced.
 *   Provides a frequency/importance signal complementary to confidence and decay.
 */
data class Proposition(
    override val id: String = UUID.randomUUID().toString(),
    val contextId: ContextId,
    val text: String,
    override val mentions: List<EntityMention>,
    override val confidence: ZeroToOne,
    override val decay: ZeroToOne = 0.0,
    override val importance: ZeroToOne = 0.5,
    val reasoning: String? = null,
    override val grounding: List<String> = emptyList(),
    val created: Instant = Instant.now(),
    val revised: Instant = Instant.now(),
    val status: PropositionStatus = PropositionStatus.ACTIVE,
    val level: Int = 0,
    val sourceIds: List<String> = emptyList(),
    val reinforceCount: Int = 0,
    override val metadata: Map<String, Any> = emptyMap(),
    override val uri: String? = null,
) : Derivation, ReferencesEntities, Retrievable {

    /**
     * Java-friendly accessor for contextId value.
     */
    @get:JvmName("getContextIdValue")
    val contextIdValue: String get() = contextId.value

    companion object {

        /**
         * @Semantic annotation key for the natural language predicate of the proposition.
         */
        const val PREDICATE = "predicate"

        /**
         * Java-friendly factory method to create a Proposition.
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            id: String,
            contextIdValue: String,
            text: String,
            mentions: List<EntityMention>,
            confidence: Double,
            decay: Double,
            importance: Double = 0.5,
            reasoning: String?,
            grounding: List<String>,
            created: Instant,
            revised: Instant,
            status: PropositionStatus,
            level: Int = 0,
            sourceIds: List<String> = emptyList(),
            reinforceCount: Int = 0,
            metadata: Map<String, Any> = emptyMap(),
            uri: String? = null,
        ): Proposition = Proposition(
            id = id,
            contextId = ContextId(contextIdValue),
            text = text,
            mentions = mentions,
            confidence = confidence,
            decay = decay,
            importance = importance,
            reasoning = reasoning,
            grounding = grounding,
            created = created,
            revised = revised,
            status = status,
            level = level,
            sourceIds = sourceIds,
            reinforceCount = reinforceCount,
            metadata = metadata,
            uri = uri,
        )
    }

    init {
        require(confidence in 0.0..1.0) { "Confidence must be between 0.0 and 1.0" }
        require(decay in 0.0..1.0) { "Decay must be between 0.0 and 1.0" }
        require(importance in 0.0..1.0) { "Importance must be between 0.0 and 1.0" }
        require(level >= 0) { "Level must be non-negative" }
        require(level == 0 || sourceIds.isNotEmpty()) { "Abstracted propositions (level > 0) must have sourceIds" }
        require(reinforceCount >= 0) { "reinforceCount must be non-negative" }
    }

    override fun embeddableValue(): String {
        return text
    }

    override fun infoString(verbose: Boolean?, indent: Int): String {
        val mentionStr = mentions.joinToString(", ") { it.infoString(verbose) }
        return if (verbose == true) {
            "Proposition(text=\"$text\", mentions=[$mentionStr], conf=$confidence, importance=$importance, status=$status)"
        } else {
            "Proposition(\"$text\" [$mentionStr])"
        }
    }

    /**
     * Create a copy with updated mentions.
     */
    fun withResolvedMentions(resolvedMentions: List<EntityMention>): Proposition =
        copy(mentions = resolvedMentions, revised = Instant.now())

    /**
     * Create a copy with updated status.
     */
    fun withStatus(newStatus: PropositionStatus): Proposition =
        copy(status = newStatus, revised = Instant.now())

    /**
     * Create a copy with adjusted confidence.
     */
    fun withConfidence(newConfidence: Double): Proposition {
        require(newConfidence in 0.0..1.0) { "Confidence must be between 0.0 and 1.0" }
        return copy(confidence = newConfidence, revised = Instant.now())
    }

    /**
     * Create a copy with additional grounding.
     */
    fun withGrounding(chunkIds: List<String>): Proposition =
        copy(grounding = (grounding + chunkIds).distinct(), revised = Instant.now())

    /**
     * Calculate the effective confidence after applying time-based decay.
     * Uses exponential decay formula from GUM paper: γ = exp(-decay * k * age_days)
     *
     * @param k Decay rate multiplier (default 2.0 from GUM paper)
     * @return Effective confidence after decay
     */
    fun effectiveConfidence(k: Double = 2.0): Double =
        effectiveConfidenceAt(Instant.now(), k)

    /**
     * Calculate the effective confidence as of a specific point in time.
     * Useful for historical analysis where you want to know what the
     * confidence was at a past date, not relative to now.
     *
     * @param asOf The point in time to calculate confidence for
     * @param k Decay rate multiplier (default 2.0 from GUM paper)
     * @return Effective confidence after decay as of the given time
     */
    fun effectiveConfidenceAt(asOf: Instant, k: Double = 2.0): Double {
        val ageInDays = java.time.Duration.between(revised, asOf).toDays().toDouble()
            .coerceAtLeast(0.0)  // Don't apply negative decay for future dates
        val gamma = kotlin.math.exp(-decay * k * ageInDays)
        return confidence * gamma
    }

    /**
     * Create a copy with decay applied to confidence.
     * Useful for retrieval ranking where older propositions should be weighted less.
     *
     * @param k Decay rate multiplier (default 2.0 from GUM paper)
     * @return Proposition with decayed confidence
     */
    fun withDecayApplied(k: Double = 2.0): Proposition {
        val effectiveConf = effectiveConfidence(k).coerceIn(0.0, 1.0)
        return copy(confidence = effectiveConf)
    }
}

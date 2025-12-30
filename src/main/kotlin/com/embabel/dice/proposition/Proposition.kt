package com.embabel.dice.proposition

import com.embabel.common.core.types.HasInfoString
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
 * @property text The statement in natural language (e.g., "Jim is an expert in GOAP")
 * @property mentions Entity references within the text (typically 1-2, with SUBJECT/OBJECT roles)
 * @property confidence LLM-generated certainty (0.0-1.0)
 * @property decay Staleness rate (0.0-1.0). High decay = information becomes stale quickly
 * @property reasoning LLM explanation for why this was extracted
 * @property grounding Chunk IDs that support this proposition
 * @property created When the proposition was first created
 * @property revised When the proposition was last updated
 * @property status Current lifecycle status
 */
data class Proposition(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    override val mentions: List<EntityMention>,
    override val confidence: ZeroToOne,
    override val decay: ZeroToOne = 0.0,
    val reasoning: String? = null,
    override val grounding: List<String> = emptyList(),
    val created: Instant = Instant.now(),
    val revised: Instant = Instant.now(),
    val status: PropositionStatus = PropositionStatus.ACTIVE,
) : Derivation, ReferencesEntities, HasInfoString {

    init {
        require(confidence in 0.0..1.0) { "Confidence must be between 0.0 and 1.0" }
        require(decay in 0.0..1.0) { "Decay must be between 0.0 and 1.0" }
    }

    override fun infoString(verbose: Boolean?, indent: Int): String {
        val mentionStr = mentions.joinToString(", ") { it.infoString(verbose) }
        return if (verbose == true) {
            "Proposition(text=\"$text\", mentions=[$mentionStr], conf=$confidence, status=$status)"
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
    fun effectiveConfidence(k: Double = 2.0): Double {
        val ageInDays = java.time.Duration.between(revised, Instant.now()).toDays().toDouble()
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

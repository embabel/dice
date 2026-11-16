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
package com.embabel.dice.evidence

import com.embabel.common.core.types.ZeroToOne
import com.embabel.dice.proposition.PropositionStatus
import com.embabel.dice.temporal.TemporalMetadata
import java.time.Instant
import java.util.Objects

/**
 * Restricts an EvidenceItem block to the innermost builder, so an outer builder is not reached by accident.
 */
@DslMarker
annotation class EvidenceDsl

/**
 * One claim handed to a caller that assembles model context, with its citation, confidence and status.
 *
 * Build it with the `with` chain in Java, or with a block in Kotlin. Both give equal values.
 *
 * ```java
 * EvidenceItem item = EvidenceItem.of("Alice moved to Berlin in 2024.", Citation.of("prop-42", "ctx-main"), 0.9)
 *     .withScore(0.83)
 *     .withRetrieval(Retrieval.of(RetrieverId.VECTOR).withRank(1).withScore(0.87));
 * ```
 *
 * ```kotlin
 * val item = EvidenceItem("Alice moved to Berlin in 2024.", Citation.of("prop-42", ContextId("ctx-main")), 0.9) {
 *     score = 0.83
 *     retrieval(Retrieval.of(RetrieverId.VECTOR).withRank(1).withScore(0.87))
 * }
 * ```
 *
 * Confidence values are checked to lie between 0.0 and 1.0. Scores must be finite. The `with` methods
 * return a new item and leave this one unchanged.
 */
class EvidenceItem private constructor(
    /** The claim text, as it is shown to a model. Stored as given. */
    val text: String,
    /** Where the claim came from. */
    val citation: Citation,
    /** Confidence as stored. */
    val confidence: ZeroToOne,
    /** Confidence after decay and validity are applied. Equal to [confidence] until it is changed. */
    val effectiveConfidence: ZeroToOne,
    /** Lifecycle status of the claim. */
    val status: PropositionStatus,
    /** When the claim was first recorded. Null when not known. */
    val recordedAt: Instant?,
    /**
     * When the text, mentions or confidence of the claim last changed after it was recorded. A merge, a
     * reinforcement and a contradiction change confidence, so they count. Null when no change is recorded.
     */
    val revisedAt: Instant?,
    derivedFromIds: List<String>,
    derivedFrom: List<EvidenceItem>,
    /** How many times the claim has been merged or reinforced. 0 when not known. */
    val reinforceCount: Int,
    temporal: TemporalMetadata?,
    /** The final ranking score. Null when there is none. */
    val score: Double?,
    retrievals: List<Retrieval>,
) {

    /**
     * Ids of the claims this one was abstracted from. Empty for an observation. For a stored abstraction
     * these are the ids in [Evidence.Derived.inputIds]: the claims it was abstracted from.
     */
    val derivedFromIds: List<String> = EvidenceChecks.copyOf("derivedFromIds", derivedFromIds)

    /**
     * The loaded claims this one was abstracted from. May be empty, or hold only some of [derivedFromIds].
     * Each one cites an id in [derivedFromIds], and no two cite the same id.
     */
    val derivedFrom: List<EvidenceItem> = EvidenceChecks.copyOf("derivedFrom", derivedFrom)

    /** True when [derivedFromIds] is not empty. */
    val isDerived: Boolean = this.derivedFromIds.isNotEmpty()

    /**
     * When the claim holds in the world and which claims it replaces or contradicts. Null when not known.
     * The item keeps its own copy of the two id lists, so a change to the caller's lists does not reach it.
     */
    val temporal: TemporalMetadata? = temporal?.let {
        it.copy(
            supersedes = EvidenceChecks.copyOf("temporal.supersedes", it.supersedes),
            contradicts = EvidenceChecks.copyOf("temporal.contradicts", it.contradicts),
        )
    }

    /** Each retriever that found the claim, in the order they were added. */
    val retrievals: List<Retrieval> = EvidenceChecks.copyOf("retrievals", retrievals)

    init {
        require(text.isNotBlank()) { "text must not be blank" }
        requireZeroToOne("confidence", confidence)
        requireZeroToOne("effectiveConfidence", effectiveConfidence)
        require(score == null || score.isFinite()) { "score must be finite, was $score" }
        require(reinforceCount >= 0) { "reinforceCount must not be negative, was $reinforceCount" }
        require(this.derivedFromIds.all { it.isNotBlank() }) { "derived ids must not be blank" }
        EvidenceChecks.requireLoadedClaimsNamed("derivedFrom", this.derivedFrom, "derivedFromIds", this.derivedFromIds)
    }

    /**
     * Returns a copy with [effectiveConfidence] set.
     *
     * @param effectiveConfidence confidence after decay and validity are applied, from 0.0 to 1.0
     * @return the updated item
     * @throws IllegalArgumentException if [effectiveConfidence] is NaN or outside 0.0 to 1.0
     */
    fun withEffectiveConfidence(effectiveConfidence: ZeroToOne): EvidenceItem =
        rebuild(effectiveConfidence = effectiveConfidence)

    /**
     * Returns a copy with [status] set.
     *
     * @param status lifecycle status of the claim
     * @return the updated item
     */
    fun withStatus(status: PropositionStatus): EvidenceItem =
        rebuild(status = status)

    /**
     * Returns a copy with [recordedAt] set.
     *
     * @param recordedAt when the claim was first recorded
     * @return the updated item
     */
    fun withRecordedAt(recordedAt: Instant): EvidenceItem =
        rebuild(recordedAt = recordedAt)

    /**
     * Returns a copy with [revisedAt] set.
     *
     * @param revisedAt when the content of the claim last changed
     * @return the updated item
     */
    fun withRevisedAt(revisedAt: Instant): EvidenceItem =
        rebuild(revisedAt = revisedAt)

    /**
     * Returns a copy with [derivedFromIds] replaced. The list is copied. It must still name every claim in
     * [derivedFrom].
     *
     * @param derivedFromIds ids of the claims this one was abstracted from
     * @return the updated item
     * @throws IllegalArgumentException if any id is null or blank, or a claim in [derivedFrom] cites an id
     *   outside the new list
     */
    fun withDerivedFromIds(derivedFromIds: List<String>): EvidenceItem =
        rebuild(derivedFromIds = derivedFromIds)

    /**
     * Returns a copy with [derivedFrom] replaced. The list is copied. When this item has no [derivedFromIds],
     * the copy takes its ids from the loaded claims, in order. Otherwise each loaded claim must cite one of the
     * existing ids, and a subset of them is accepted.
     *
     * @param derivedFrom the loaded claims this one was abstracted from
     * @return the updated item
     * @throws IllegalArgumentException if [derivedFrom] holds a null, a loaded claim cites an id outside
     *   [derivedFromIds], or two loaded claims cite the same id
     */
    fun withDerivedFrom(derivedFrom: List<EvidenceItem>): EvidenceItem {
        val loaded = EvidenceChecks.copyOf("derivedFrom", derivedFrom)
        return rebuild(derivedFromIds = EvidenceChecks.idsForLoaded(derivedFromIds, loaded), derivedFrom = loaded)
    }

    /**
     * Returns a copy with [reinforceCount] set.
     *
     * @param reinforceCount how many times the claim has been merged or reinforced
     * @return the updated item
     * @throws IllegalArgumentException if [reinforceCount] is negative
     */
    fun withReinforceCount(reinforceCount: Int): EvidenceItem =
        rebuild(reinforceCount = reinforceCount)

    /**
     * Returns a copy with [temporal] set. The item keeps its own copy of the two id lists.
     *
     * @param temporal when the claim holds in the world and which claims it replaces or contradicts
     * @return the updated item
     * @throws IllegalArgumentException if either id list holds a null
     */
    fun withTemporal(temporal: TemporalMetadata): EvidenceItem =
        rebuild(temporal = temporal)

    /**
     * Returns a copy with [score] set.
     *
     * @param score the final ranking score
     * @return the updated item
     * @throws IllegalArgumentException if [score] is NaN or infinite
     */
    fun withScore(score: Double): EvidenceItem =
        rebuild(score = score)

    /**
     * Returns a copy with [retrieval] added after the existing retrievals.
     *
     * @param retrieval the hit from one retriever
     * @return the updated item
     */
    fun withRetrieval(retrieval: Retrieval): EvidenceItem =
        rebuild(retrievals = retrievals + retrieval)

    /**
     * Returns a copy with the retrievals replaced. The list is copied.
     *
     * @param retrievals the hits from each retriever, in the order to keep
     * @return the updated item
     * @throws IllegalArgumentException if [retrievals] holds a null
     */
    fun withRetrievals(retrievals: List<Retrieval>): EvidenceItem =
        rebuild(retrievals = retrievals)

    /**
     * Two items are equal when every property is equal. Doubles compare by their boxed value,
     * so `0.0` and `-0.0` differ.
     */
    override fun equals(other: Any?): Boolean =
        other is EvidenceItem && text == other.text && citation == other.citation &&
            Objects.equals(confidence, other.confidence) &&
            Objects.equals(effectiveConfidence, other.effectiveConfidence) &&
            status == other.status && recordedAt == other.recordedAt &&
            revisedAt == other.revisedAt && derivedFromIds == other.derivedFromIds &&
            derivedFrom == other.derivedFrom && reinforceCount == other.reinforceCount &&
            temporal == other.temporal && Objects.equals(score, other.score) && retrievals == other.retrievals

    override fun hashCode(): Int =
        Objects.hash(
            text, citation, confidence, effectiveConfidence, status,
            recordedAt, revisedAt, derivedFromIds, derivedFrom, reinforceCount, temporal, score, retrievals,
        )

    /**
     * Builds a copy of this item with the given parts replaced.
     *
     * @param effectiveConfidence confidence after decay and validity are applied
     * @param status lifecycle status of the claim
     * @param recordedAt when the claim was first recorded
     * @param revisedAt when the content of the claim last changed
     * @param derivedFromIds ids of the claims this one was abstracted from
     * @param derivedFrom the loaded claims this one was abstracted from
     * @param reinforceCount how many times the claim has been merged or reinforced
     * @param temporal when the claim holds in the world and which claims it replaces or contradicts
     * @param score the final ranking score
     * @param retrievals the hits from each retriever
     * @return the new item
     * @throws IllegalArgumentException if any part is rejected by the item's checks
     */
    private fun rebuild(
        effectiveConfidence: ZeroToOne = this.effectiveConfidence,
        status: PropositionStatus = this.status,
        recordedAt: Instant? = this.recordedAt,
        revisedAt: Instant? = this.revisedAt,
        derivedFromIds: List<String> = this.derivedFromIds,
        derivedFrom: List<EvidenceItem> = this.derivedFrom,
        reinforceCount: Int = this.reinforceCount,
        temporal: TemporalMetadata? = this.temporal,
        score: Double? = this.score,
        retrievals: List<Retrieval> = this.retrievals,
    ): EvidenceItem =
        create(
            text, citation, confidence, effectiveConfidence, status, recordedAt, revisedAt,
            derivedFromIds, derivedFrom, reinforceCount, temporal, score, retrievals,
        )

    /** Returns the claim id, confidence, status and score. The text is left out. */
    override fun toString(): String =
        "EvidenceItem(propositionId=${citation.propositionId}, confidence=$confidence, status=$status, score=$score)"

    companion object {

        /**
         * Creates an item with the given text, citation and confidence. Its effective confidence
         * starts equal to [confidence], its status is ACTIVE, and the other optional values are null or empty.
         *
         * @param text the claim text, as it is shown to a model
         * @param citation where the claim came from
         * @param confidence confidence as stored, from 0.0 to 1.0
         * @return the new item
         * @throws IllegalArgumentException if [text] is blank or [confidence] is NaN or outside 0.0 to 1.0
         */
        @JvmStatic
        fun of(text: String, citation: Citation, confidence: ZeroToOne): EvidenceItem =
            create(
                text, citation, confidence, confidence, PropositionStatus.ACTIVE,
                null, null, emptyList(), emptyList(), 0, null, null, emptyList(),
            )

        /**
         * Builds an item from every part. Every way of making an item goes through here.
         *
         * @param text the claim text, as it is shown to a model
         * @param citation where the claim came from
         * @param confidence confidence as stored, from 0.0 to 1.0
         * @param effectiveConfidence confidence after decay and validity are applied, from 0.0 to 1.0
         * @param status lifecycle status of the claim
         * @param recordedAt when the claim was first recorded, or null
         * @param revisedAt when the content of the claim last changed, or null
         * @param derivedFromIds ids of the claims this one was abstracted from
         * @param derivedFrom the loaded claims this one was abstracted from, each citing an id in [derivedFromIds]
         * @param reinforceCount how many times the claim has been merged or reinforced
         * @param temporal when the claim holds in the world and which claims it replaces or contradicts, or null
         * @param score the final ranking score, or null
         * @param retrievals the hits from each retriever, in order
         * @return the new item
         * @throws IllegalArgumentException if any part is rejected by the item's checks
         */
        @JvmSynthetic
        internal fun create(
            text: String,
            citation: Citation,
            confidence: ZeroToOne,
            effectiveConfidence: ZeroToOne,
            status: PropositionStatus,
            recordedAt: Instant?,
            revisedAt: Instant?,
            derivedFromIds: List<String>,
            derivedFrom: List<EvidenceItem>,
            reinforceCount: Int,
            temporal: TemporalMetadata?,
            score: Double?,
            retrievals: List<Retrieval>,
        ): EvidenceItem =
            EvidenceItem(
                text, citation, confidence, effectiveConfidence, status, recordedAt, revisedAt,
                derivedFromIds, derivedFrom, reinforceCount, temporal, score, retrievals,
            )

        /**
         * Creates an item in Kotlin, setting the optional values in [block].
         *
         * @param text the claim text, as it is shown to a model
         * @param citation where the claim came from
         * @param confidence confidence as stored, from 0.0 to 1.0
         * @param block sets the optional values on an [EvidenceItemBuilder]
         * @return the new item
         * @throws IllegalArgumentException if any value is rejected by the item's checks
         */
        @JvmSynthetic
        operator fun invoke(
            text: String,
            citation: Citation,
            confidence: ZeroToOne,
            block: EvidenceItemBuilder.() -> Unit = {},
        ): EvidenceItem =
            EvidenceItemBuilder().apply(block).build(text, citation, confidence)

        /**
         * Checks that a confidence value lies between 0.0 and 1.0.
         *
         * @param name the property name shown in the error message
         * @param value the confidence to check
         * @throws IllegalArgumentException if [value] is NaN or outside 0.0 to 1.0
         */
        private fun requireZeroToOne(name: String, value: ZeroToOne) {
            require(value in 0.0..1.0) { "$name must be between 0.0 and 1.0, was $value" }
        }
    }
}

/**
 * The receiver of the block passed to `EvidenceItem(text, citation, confidence) { }`, for Kotlin callers.
 * Java callers use the `with` chain on [EvidenceItem].
 *
 * Each property left unset keeps the value from [EvidenceItem.of]. [retrieval] calls add up in call order.
 */
@EvidenceDsl
class EvidenceItemBuilder internal constructor() {

    /** Confidence after decay and validity are applied, from 0.0 to 1.0. */
    var effectiveConfidence: ZeroToOne? = null

    /** Lifecycle status of the claim. */
    var status: PropositionStatus? = null

    /** When the claim was first recorded. */
    var recordedAt: Instant? = null

    /**
     * When the text, mentions or confidence of the claim last changed after it was recorded. A merge, a
     * reinforcement and a contradiction change confidence, so they count.
     */
    var revisedAt: Instant? = null

    /** Ids of the claims this one was abstracted from. Left unset or empty, they come from [derivedFrom]. */
    var derivedFromIds: List<String>? = null

    /** The loaded claims this one was abstracted from. Each must cite an id in [derivedFromIds]. */
    var derivedFrom: List<EvidenceItem>? = null

    /** How many times the claim has been merged or reinforced. */
    var reinforceCount: Int? = null

    /** When the claim holds in the world and which claims it replaces or contradicts. */
    var temporal: TemporalMetadata? = null

    /** The final ranking score. */
    var score: Double? = null

    private val retrievals = mutableListOf<Retrieval>()

    /**
     * Adds a retriever's hit for the claim. Hits keep the order of the calls.
     *
     * @param retrieval the hit from one retriever
     */
    fun retrieval(retrieval: Retrieval) {
        retrievals.add(retrieval)
    }

    /**
     * Builds the item from the values set on this builder, with one construction.
     *
     * @param text the claim text, as it is shown to a model
     * @param citation where the claim came from
     * @param confidence confidence as stored, from 0.0 to 1.0
     * @return the new item
     * @throws IllegalArgumentException if a value is rejected by the item's checks
     */
    internal fun build(text: String, citation: Citation, confidence: ZeroToOne): EvidenceItem {
        val loaded = EvidenceChecks.copyOf("derivedFrom", derivedFrom ?: emptyList())
        return EvidenceItem.create(
            text, citation, confidence, effectiveConfidence ?: confidence, status ?: PropositionStatus.ACTIVE,
            recordedAt, revisedAt, EvidenceChecks.idsForLoaded(derivedFromIds ?: emptyList(), loaded), loaded,
            reinforceCount ?: 0, temporal, score, retrievals,
        )
    }
}

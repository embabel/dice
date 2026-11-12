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
    /** Confidence after time decay. Equal to [confidence] until it is changed. */
    val effectiveConfidence: ZeroToOne,
    /** Lifecycle status of the claim. */
    val status: PropositionStatus,
    /** When the claim was first recorded. Null when not known. */
    val recordedAt: Instant?,
    /** When the content of the claim last changed. Null when not known. */
    val revisedAt: Instant?,
    derivedFromIds: List<String>,
    /** The final ranking score. Null when there is none. */
    val score: Double?,
    retrievals: List<Retrieval>,
) {

    /** Ids of the claims this one was abstracted from. Empty for an observation. */
    val derivedFromIds: List<String> = java.util.List.copyOf(derivedFromIds)

    /** True when [derivedFromIds] is not empty. */
    val isDerived: Boolean = this.derivedFromIds.isNotEmpty()

    /** Each retriever that found the claim, in the order they were added. */
    val retrievals: List<Retrieval> = java.util.List.copyOf(retrievals)

    init {
        require(text.isNotBlank()) { "text must not be blank" }
        requireZeroToOne("confidence", confidence)
        requireZeroToOne("effectiveConfidence", effectiveConfidence)
        require(score == null || score.isFinite()) { "score must be finite, was $score" }
        require(this.derivedFromIds.all { it.isNotBlank() }) { "derived ids must not be blank" }
    }

    /**
     * Returns a copy with [effectiveConfidence] set.
     *
     * @param effectiveConfidence confidence after time decay, from 0.0 to 1.0
     * @return the updated item
     * @throws IllegalArgumentException if [effectiveConfidence] is NaN or outside 0.0 to 1.0
     */
    fun withEffectiveConfidence(effectiveConfidence: ZeroToOne): EvidenceItem =
        EvidenceItem(
            text, citation, confidence, effectiveConfidence, status,
            recordedAt, revisedAt, derivedFromIds, score, retrievals,
        )

    /**
     * Returns a copy with [status] set.
     *
     * @param status lifecycle status of the claim
     * @return the updated item
     */
    fun withStatus(status: PropositionStatus): EvidenceItem =
        EvidenceItem(
            text, citation, confidence, effectiveConfidence, status,
            recordedAt, revisedAt, derivedFromIds, score, retrievals,
        )

    /**
     * Returns a copy with [recordedAt] set.
     *
     * @param recordedAt when the claim was first recorded
     * @return the updated item
     */
    fun withRecordedAt(recordedAt: Instant): EvidenceItem =
        EvidenceItem(
            text, citation, confidence, effectiveConfidence, status,
            recordedAt, revisedAt, derivedFromIds, score, retrievals,
        )

    /**
     * Returns a copy with [revisedAt] set.
     *
     * @param revisedAt when the content of the claim last changed
     * @return the updated item
     */
    fun withRevisedAt(revisedAt: Instant): EvidenceItem =
        EvidenceItem(
            text, citation, confidence, effectiveConfidence, status,
            recordedAt, revisedAt, derivedFromIds, score, retrievals,
        )

    /**
     * Returns a copy with [derivedFromIds] replaced. The list is copied.
     *
     * @param derivedFromIds ids of the claims this one was abstracted from
     * @return the updated item
     * @throws IllegalArgumentException if any id is blank
     */
    fun withDerivedFromIds(derivedFromIds: List<String>): EvidenceItem =
        EvidenceItem(
            text, citation, confidence, effectiveConfidence, status,
            recordedAt, revisedAt, derivedFromIds, score, retrievals,
        )

    /**
     * Returns a copy with [score] set.
     *
     * @param score the final ranking score
     * @return the updated item
     * @throws IllegalArgumentException if [score] is NaN or infinite
     */
    fun withScore(score: Double): EvidenceItem =
        EvidenceItem(
            text, citation, confidence, effectiveConfidence, status,
            recordedAt, revisedAt, derivedFromIds, score, retrievals,
        )

    /**
     * Returns a copy with [retrieval] added after the existing retrievals.
     *
     * @param retrieval the hit from one retriever
     * @return the updated item
     */
    fun withRetrieval(retrieval: Retrieval): EvidenceItem =
        EvidenceItem(
            text, citation, confidence, effectiveConfidence, status,
            recordedAt, revisedAt, derivedFromIds, score, retrievals + retrieval,
        )

    /**
     * Returns a copy with the retrievals replaced. The list is copied.
     *
     * @param retrievals the hits from each retriever, in the order to keep
     * @return the updated item
     */
    fun withRetrievals(retrievals: List<Retrieval>): EvidenceItem =
        EvidenceItem(
            text, citation, confidence, effectiveConfidence, status,
            recordedAt, revisedAt, derivedFromIds, score, retrievals,
        )

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
            Objects.equals(score, other.score) && retrievals == other.retrievals

    override fun hashCode(): Int =
        Objects.hash(
            text, citation, confidence, effectiveConfidence, status,
            recordedAt, revisedAt, derivedFromIds, score, retrievals,
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
            EvidenceItem(
                text, citation, confidence, confidence, PropositionStatus.ACTIVE,
                null, null, emptyList(), null, emptyList(),
            )

        /**
         * Creates an item in Kotlin, setting the optional values in [block].
         *
         * @param text the claim text, as it is shown to a model
         * @param citation where the claim came from
         * @param confidence confidence as stored, from 0.0 to 1.0
         * @param block sets the optional values on an [EvidenceItemBuilder]
         * @return the new item
         * @throws IllegalArgumentException if any value is rejected by its `with` method
         */
        @JvmSynthetic
        operator fun invoke(
            text: String,
            citation: Citation,
            confidence: ZeroToOne,
            block: EvidenceItemBuilder.() -> Unit = {},
        ): EvidenceItem =
            EvidenceItemBuilder().apply(block).applyTo(of(text, citation, confidence))

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
 * The receiver of the block passed to `EvidenceItem(text, citation, confidence) { }`. Kotlin only.
 * Java callers use the `with` chain on [EvidenceItem].
 *
 * Each property left unset keeps the value from [EvidenceItem.of]. [retrieval] calls add up in call order.
 */
@EvidenceDsl
class EvidenceItemBuilder internal constructor() {

    /** Confidence after time decay, from 0.0 to 1.0. */
    var effectiveConfidence: ZeroToOne? = null

    /** Lifecycle status of the claim. */
    var status: PropositionStatus? = null

    /** When the claim was first recorded. */
    var recordedAt: Instant? = null

    /** When the content of the claim last changed. */
    var revisedAt: Instant? = null

    /** Ids of the claims this one was abstracted from. */
    var derivedFromIds: List<String>? = null

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
     * Applies the values set on this builder to [base] through the matching `with` methods.
     *
     * @param base the item to start from
     * @return the item with the set values applied
     * @throws IllegalArgumentException if a value is rejected by its `with` method
     */
    internal fun applyTo(base: EvidenceItem): EvidenceItem =
        retrievals.fold(
            base
                .let { item -> effectiveConfidence?.let(item::withEffectiveConfidence) ?: item }
                .let { item -> status?.let(item::withStatus) ?: item }
                .let { item -> recordedAt?.let(item::withRecordedAt) ?: item }
                .let { item -> revisedAt?.let(item::withRevisedAt) ?: item }
                .let { item -> derivedFromIds?.let(item::withDerivedFromIds) ?: item }
                .let { item -> score?.let(item::withScore) ?: item },
        ) { item, retrieval -> item.withRetrieval(retrieval) }
}

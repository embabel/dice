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
package com.embabel.dice.store

import com.embabel.dice.proposition.Proposition
import com.embabel.dice.proposition.PropositionStatus
import org.jetbrains.annotations.ApiStatus
import java.time.Instant
import java.util.Collections
import java.util.EnumSet

/**
 * Which propositions a scoped read keeps. Criteria name no context. A [ScopedReadSpec] names the
 * contexts, and only reads that take a spec accept criteria.
 *
 * ```java
 * ReadCriteria active = ReadCriteria.empty()
 *     .withStatus(PropositionStatus.ACTIVE)
 *     .withMinConfidence(0.6);
 * ```
 *
 * ```kotlin
 * val active = ReadCriteria {
 *     statuses = setOf(PropositionStatus.ACTIVE)
 *     minConfidence = 0.6
 * }
 * ```
 *
 * A proposition is kept when it meets every criterion that is set. [ReadCriterion] names the
 * stored value each criterion compares. Criteria that set nothing keep every proposition the
 * spec allows. Each `with` step returns new criteria with one criterion set, in place of the
 * value it had. A step given null throws `NullPointerException`.
 *
 * A store reads the properties and builds its own query from them. It calls [requireOnly] first,
 * with the criteria it applies, so a criterion it does not apply throws and is never skipped.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 *
 * @property entityId See [ReadCriterion.ENTITY_ID].
 * @property minLevel See [ReadCriterion.MIN_LEVEL].
 * @property maxLevel See [ReadCriterion.MAX_LEVEL].
 * @property createdAfter See [ReadCriterion.CREATED_AFTER].
 * @property createdBefore See [ReadCriterion.CREATED_BEFORE].
 * @property revisedAfter See [ReadCriterion.REVISED_AFTER].
 * @property revisedBefore See [ReadCriterion.REVISED_BEFORE].
 * @property accessedAfter See [ReadCriterion.ACCESSED_AFTER].
 * @property accessedBefore See [ReadCriterion.ACCESSED_BEFORE].
 * @property effectiveConfidenceAsOf The instant effective confidence is computed at, or null for
 *   one reading of the store's clock. Only the criteria of a read carry it. A spec filter must
 *   not.
 * @property minReinforceCount See [ReadCriterion.MIN_REINFORCE_COUNT].
 * @property pinned See [ReadCriterion.PINNED].
 */
@ApiStatus.Experimental
class ReadCriteria private constructor(
    val entityId: String?,
    anyEntityIds: Collection<String>?,
    allEntityIds: Collection<String>?,
    statuses: Collection<PropositionStatus>?,
    val minLevel: Int?,
    val maxLevel: Int?,
    val createdAfter: Instant?,
    val createdBefore: Instant?,
    val revisedAfter: Instant?,
    val revisedBefore: Instant?,
    val accessedAfter: Instant?,
    val accessedBefore: Instant?,
    minConfidence: Double?,
    minEffectiveConfidence: Double?,
    belowEffectiveConfidence: Double?,
    val effectiveConfidenceAsOf: Instant?,
    decayK: Double,
    minImportance: Double?,
    val minReinforceCount: Int?,
    minTrustScore: Double?,
    val pinned: Boolean?,
) {

    /** See [ReadCriterion.ANY_ENTITY_IDS]. Read-only, never empty. */
    val anyEntityIds: List<String>? = anyEntityIds?.let { requireEntityIds("anyEntityIds", it) }

    /** See [ReadCriterion.ALL_ENTITY_IDS]. Read-only, never empty. */
    val allEntityIds: List<String>? = allEntityIds?.let { requireEntityIds("allEntityIds", it) }

    /** See [ReadCriterion.STATUSES]. Read-only, never empty. */
    val statuses: Set<PropositionStatus>? = statuses?.let { requireStatuses(it) }

    /** See [ReadCriterion.MIN_CONFIDENCE]. */
    val minConfidence: Double? = requireFinite("minConfidence", minConfidence)

    /** See [ReadCriterion.MIN_EFFECTIVE_CONFIDENCE]. */
    val minEffectiveConfidence: Double? = requireFinite("minEffectiveConfidence", minEffectiveConfidence)

    /** See [ReadCriterion.BELOW_EFFECTIVE_CONFIDENCE]. */
    val belowEffectiveConfidence: Double? = requireFinite("belowEffectiveConfidence", belowEffectiveConfidence)

    /** The decay rate used for effective confidence. Zero or more. See [ReadCriterion.DECAY_K]. */
    val decayK: Double = requireDecayK(decayK)

    /** See [ReadCriterion.MIN_IMPORTANCE]. */
    val minImportance: Double? = requireFinite("minImportance", minImportance)

    /** See [ReadCriterion.MIN_TRUST_SCORE]. */
    val minTrustScore: Double? = requireFinite("minTrustScore", minTrustScore)

    init {
        require(entityId == null || entityId.isNotBlank()) { "entityId must not be blank" }
    }

    /**
     * The criteria that are set, in the order [ReadCriterion] declares them. [ReadCriterion.DECAY_K]
     * is one of them when [decayK] differs from [DEFAULT_DECAY_K]. Read-only.
     */
    val specified: Set<ReadCriterion>
        get() = Collections.unmodifiableSet(
            criterionValues()
                .filter { it.second != null }
                .mapTo(EnumSet.noneOf(ReadCriterion::class.java)) { it.first },
        )

    /**
     * Checks that every criterion that is set is one a store applies. A store calls it on the
     * criteria of a read before it reads, and [ScopedReadSpec.requireOnly] calls it on every
     * filter.
     *
     * ```java
     * criteria.requireOnly(APPLIED, StoreCapability.SCOPED_READS);
     * ```
     *
     * @param supported The criteria the store applies.
     * @param capability The capability the read belongs to, carried by the exception.
     * @throws UnsupportedCapabilityException naming the first of [specified] that is not in
     *   [supported].
     */
    fun requireOnly(supported: Set<ReadCriterion>, capability: StoreCapability) {
        val unsupported = specified.firstOrNull { it !in supported } ?: return
        throw UnsupportedCapabilityException(capability, "the store cannot apply the criterion $unsupported")
    }

    /**
     * Keeps propositions that mention this entity.
     *
     * @throws IllegalArgumentException when [entityId] is blank.
     */
    fun withEntityId(entityId: String): ReadCriteria = copy(entityId = entityId)

    /**
     * Keeps propositions that mention at least one of these entities.
     *
     * @throws IllegalArgumentException when [entityIds] is empty or holds a null or blank id.
     */
    fun withAnyEntityIds(entityIds: Collection<String>): ReadCriteria = copy(anyEntityIds = entityIds)

    /**
     * Keeps propositions that mention at least one of these entities.
     *
     * @throws IllegalArgumentException when an id is blank, or an id in [more] is null.
     */
    fun withAnyEntity(first: String, vararg more: String): ReadCriteria = withAnyEntityIds(listOf(first, *more))

    /**
     * Keeps propositions that mention every one of these entities.
     *
     * @throws IllegalArgumentException when [entityIds] is empty or holds a null or blank id.
     */
    fun withAllEntityIds(entityIds: Collection<String>): ReadCriteria = copy(allEntityIds = entityIds)

    /**
     * Keeps propositions that mention every one of these entities.
     *
     * @throws IllegalArgumentException when an id is blank, or an id in [more] is null.
     */
    fun withAllEntities(first: String, vararg more: String): ReadCriteria = withAllEntityIds(listOf(first, *more))

    /** Keeps propositions with this status. */
    fun withStatus(status: PropositionStatus): ReadCriteria = copy(statuses = listOf(status))

    /**
     * Keeps propositions whose status is one of these.
     *
     * @throws IllegalArgumentException when [statuses] is empty or holds null.
     */
    fun withStatuses(statuses: Collection<PropositionStatus>): ReadCriteria = copy(statuses = statuses)

    /**
     * Keeps propositions whose status is one of these.
     *
     * @throws IllegalArgumentException when a status in [more] is null.
     */
    fun withStatuses(first: PropositionStatus, vararg more: PropositionStatus): ReadCriteria =
        withStatuses(listOf(first, *more))

    /** Keeps propositions at this level or above. */
    fun withMinLevel(minLevel: Int): ReadCriteria = copy(minLevel = minLevel)

    /** Keeps propositions at this level or below. */
    fun withMaxLevel(maxLevel: Int): ReadCriteria = copy(maxLevel = maxLevel)

    /** Keeps propositions created at or after this instant. */
    fun withCreatedAfter(createdAfter: Instant): ReadCriteria = copy(createdAfter = createdAfter)

    /** Keeps propositions created at or before this instant. */
    fun withCreatedBefore(createdBefore: Instant): ReadCriteria = copy(createdBefore = createdBefore)

    /** Keeps propositions created from [start] to [end], both included. */
    fun withCreatedBetween(start: Instant, end: Instant): ReadCriteria =
        copy(createdAfter = start, createdBefore = end)

    /** Keeps propositions last revised at or after this instant. */
    fun withRevisedAfter(revisedAfter: Instant): ReadCriteria = copy(revisedAfter = revisedAfter)

    /** Keeps propositions last revised at or before this instant. */
    fun withRevisedBefore(revisedBefore: Instant): ReadCriteria = copy(revisedBefore = revisedBefore)

    /** Keeps propositions last revised from [start] to [end], both included. */
    fun withRevisedBetween(start: Instant, end: Instant): ReadCriteria =
        copy(revisedAfter = start, revisedBefore = end)

    /** Keeps propositions last accessed at or after this instant. */
    fun withAccessedAfter(accessedAfter: Instant): ReadCriteria = copy(accessedAfter = accessedAfter)

    /** Keeps propositions last accessed at or before this instant. */
    fun withAccessedBefore(accessedBefore: Instant): ReadCriteria = copy(accessedBefore = accessedBefore)

    /** Keeps propositions last accessed from [start] to [end], both included. */
    fun withAccessedBetween(start: Instant, end: Instant): ReadCriteria =
        copy(accessedAfter = start, accessedBefore = end)

    /**
     * Keeps propositions whose stored confidence is at least this.
     *
     * @throws IllegalArgumentException when [threshold] is not a finite number.
     */
    fun withMinConfidence(threshold: Double): ReadCriteria = copy(minConfidence = threshold)

    /**
     * Keeps propositions whose effective confidence is at least this.
     *
     * @throws IllegalArgumentException when [threshold] is not a finite number.
     */
    fun withMinEffectiveConfidence(threshold: Double): ReadCriteria = copy(minEffectiveConfidence = threshold)

    /**
     * Keeps propositions whose effective confidence is below this.
     *
     * @throws IllegalArgumentException when [threshold] is not a finite number.
     */
    fun withBelowEffectiveConfidence(threshold: Double): ReadCriteria = copy(belowEffectiveConfidence = threshold)

    /** Computes effective confidence as of this instant. Only the criteria of a read carry it. */
    fun withEffectiveConfidenceAsOf(asOf: Instant): ReadCriteria = copy(effectiveConfidenceAsOf = asOf)

    /**
     * Sets the decay rate used for effective confidence.
     *
     * @throws IllegalArgumentException when [decayK] is negative or not a finite number.
     */
    fun withDecayK(decayK: Double): ReadCriteria = copy(decayK = decayK)

    /**
     * Keeps propositions whose importance is at least this.
     *
     * @throws IllegalArgumentException when [threshold] is not a finite number.
     */
    fun withMinImportance(threshold: Double): ReadCriteria = copy(minImportance = threshold)

    /** Keeps propositions reinforced at least this many times. */
    fun withMinReinforceCount(count: Int): ReadCriteria = copy(minReinforceCount = count)

    /**
     * Keeps propositions whose trust score is at least this, and the ones with no trust score.
     *
     * @throws IllegalArgumentException when [threshold] is not a finite number.
     */
    fun withMinTrustScore(threshold: Double): ReadCriteria = copy(minTrustScore = threshold)

    /** Keeps pinned propositions only when true, and unpinned ones only when false. */
    fun withPinned(pinned: Boolean): ReadCriteria = copy(pinned = pinned)

    /**
     * Builds criteria that differ from these in the named criteria.
     *
     * Each parameter is the criterion of the same name, and defaults to this value's.
     *
     * @return The new criteria, checked the way every criteria value is.
     */
    private fun copy(
        entityId: String? = this.entityId,
        anyEntityIds: Collection<String>? = this.anyEntityIds,
        allEntityIds: Collection<String>? = this.allEntityIds,
        statuses: Collection<PropositionStatus>? = this.statuses,
        minLevel: Int? = this.minLevel,
        maxLevel: Int? = this.maxLevel,
        createdAfter: Instant? = this.createdAfter,
        createdBefore: Instant? = this.createdBefore,
        revisedAfter: Instant? = this.revisedAfter,
        revisedBefore: Instant? = this.revisedBefore,
        accessedAfter: Instant? = this.accessedAfter,
        accessedBefore: Instant? = this.accessedBefore,
        minConfidence: Double? = this.minConfidence,
        minEffectiveConfidence: Double? = this.minEffectiveConfidence,
        belowEffectiveConfidence: Double? = this.belowEffectiveConfidence,
        effectiveConfidenceAsOf: Instant? = this.effectiveConfidenceAsOf,
        decayK: Double = this.decayK,
        minImportance: Double? = this.minImportance,
        minReinforceCount: Int? = this.minReinforceCount,
        minTrustScore: Double? = this.minTrustScore,
        pinned: Boolean? = this.pinned,
    ): ReadCriteria = ReadCriteria(
        entityId = entityId,
        anyEntityIds = anyEntityIds,
        allEntityIds = allEntityIds,
        statuses = statuses,
        minLevel = minLevel,
        maxLevel = maxLevel,
        createdAfter = createdAfter,
        createdBefore = createdBefore,
        revisedAfter = revisedAfter,
        revisedBefore = revisedBefore,
        accessedAfter = accessedAfter,
        accessedBefore = accessedBefore,
        minConfidence = minConfidence,
        minEffectiveConfidence = minEffectiveConfidence,
        belowEffectiveConfidence = belowEffectiveConfidence,
        effectiveConfidenceAsOf = effectiveConfidenceAsOf,
        decayK = decayK,
        minImportance = minImportance,
        minReinforceCount = minReinforceCount,
        minTrustScore = minTrustScore,
        pinned = pinned,
    )

    /**
     * Every criterion with its value. Equality, the text form and [specified] are all built from
     * this one list.
     *
     * @return Each criterion and its value, in declaration order. The value is null when the
     *   criterion is not set, and the decay rate counts as set only when it differs from
     *   [DEFAULT_DECAY_K].
     */
    private fun criterionValues(): List<Pair<ReadCriterion, Any?>> = listOf(
        ReadCriterion.ENTITY_ID to entityId,
        ReadCriterion.ANY_ENTITY_IDS to anyEntityIds,
        ReadCriterion.ALL_ENTITY_IDS to allEntityIds,
        ReadCriterion.STATUSES to statuses,
        ReadCriterion.MIN_LEVEL to minLevel,
        ReadCriterion.MAX_LEVEL to maxLevel,
        ReadCriterion.CREATED_AFTER to createdAfter,
        ReadCriterion.CREATED_BEFORE to createdBefore,
        ReadCriterion.REVISED_AFTER to revisedAfter,
        ReadCriterion.REVISED_BEFORE to revisedBefore,
        ReadCriterion.ACCESSED_AFTER to accessedAfter,
        ReadCriterion.ACCESSED_BEFORE to accessedBefore,
        ReadCriterion.MIN_CONFIDENCE to minConfidence,
        ReadCriterion.MIN_EFFECTIVE_CONFIDENCE to minEffectiveConfidence,
        ReadCriterion.BELOW_EFFECTIVE_CONFIDENCE to belowEffectiveConfidence,
        ReadCriterion.EFFECTIVE_CONFIDENCE_AS_OF to effectiveConfidenceAsOf,
        ReadCriterion.DECAY_K to decayK.takeIf { it != DEFAULT_DECAY_K },
        ReadCriterion.MIN_IMPORTANCE to minImportance,
        ReadCriterion.MIN_REINFORCE_COUNT to minReinforceCount,
        ReadCriterion.MIN_TRUST_SCORE to minTrustScore,
        ReadCriterion.PINNED to pinned,
    )

    override fun equals(other: Any?): Boolean = other is ReadCriteria && other.criterionValues() == criterionValues()

    override fun hashCode(): Int = criterionValues().hashCode()

    override fun toString(): String =
        criterionValues().filter { it.second != null }
            .joinToString(prefix = "ReadCriteria(", postfix = ")") { "${it.first}=${it.second}" }

    companion object {

        /** The decay rate of criteria that do not set one. */
        const val DEFAULT_DECAY_K: Double = Proposition.DEFAULT_DECAY_K

        /**
         * @return Criteria that set nothing, to chain `with` steps on. They keep every
         *   proposition the spec allows.
         */
        @JvmStatic
        fun empty(): ReadCriteria = from(ReadCriteriaBuilder())

        /**
         * Builds criteria in a block. Java uses [empty] and the `with` chain.
         *
         * @param block Sets the criteria.
         * @return The criteria.
         * @throws IllegalArgumentException for the values the chain rejects.
         */
        @JvmSynthetic
        operator fun invoke(block: ReadCriteriaBuilder.() -> Unit): ReadCriteria =
            from(ReadCriteriaBuilder().apply(block))

        /**
         * Reads the criteria a builder collected.
         *
         * @param builder The builder of a block, or a new one for criteria that set nothing.
         * @return The criteria, checked the way every criteria value is.
         */
        private fun from(builder: ReadCriteriaBuilder): ReadCriteria = ReadCriteria(
            entityId = builder.entityId,
            anyEntityIds = builder.anyEntityIds,
            allEntityIds = builder.allEntityIds,
            statuses = builder.statuses,
            minLevel = builder.minLevel,
            maxLevel = builder.maxLevel,
            createdAfter = builder.createdAfter,
            createdBefore = builder.createdBefore,
            revisedAfter = builder.revisedAfter,
            revisedBefore = builder.revisedBefore,
            accessedAfter = builder.accessedAfter,
            accessedBefore = builder.accessedBefore,
            minConfidence = builder.minConfidence,
            minEffectiveConfidence = builder.minEffectiveConfidence,
            belowEffectiveConfidence = builder.belowEffectiveConfidence,
            effectiveConfidenceAsOf = builder.effectiveConfidenceAsOf,
            decayK = builder.decayK,
            minImportance = builder.minImportance,
            minReinforceCount = builder.minReinforceCount,
            minTrustScore = builder.minTrustScore,
            pinned = builder.pinned,
        )
    }
}

/**
 * One criterion of [ReadCriteria]. Each entry names the stored value of a
 * [com.embabel.dice.proposition.Proposition] the criterion compares, so every store keeps the
 * same propositions for the same criteria.
 *
 * A store lists the entries it applies and passes them to [ReadCriteria.requireOnly] and
 * [ScopedReadSpec.requireOnly] before it reads.
 *
 * ```java
 * private static final Set<ReadCriterion> APPLIED =
 *     EnumSet.of(ReadCriterion.ENTITY_ID, ReadCriterion.STATUSES, ReadCriterion.MIN_CONFIDENCE);
 *
 * criteria.requireOnly(APPLIED, StoreCapability.SCOPED_READS);
 * ```
 *
 * The store names each entry. It does not use `EnumSet.allOf` or `values()`, because an entry
 * added later would then pass unchecked and be skipped.
 *
 * Effective confidence is the value [com.embabel.dice.proposition.Proposition.effectiveConfidenceAt]
 * returns for the instant of the call and the `decayK` of the same criteria. That function is the
 * definition. A store that computes effective confidence in a database returns the same number
 * for the same proposition.
 *
 * Entries are added over time. A Kotlin `when` and a Java `switch` expression over this enum
 * keep an `else` or `default` branch.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
enum class ReadCriterion {

    /** Kept when a mention of the proposition has this `resolvedId`. */
    ENTITY_ID,

    /** Kept when a mention of the proposition has a `resolvedId` that is in the list. */
    ANY_ENTITY_IDS,

    /** Kept when every id in the list is the `resolvedId` of a mention of the proposition. */
    ALL_ENTITY_IDS,

    /** Kept when `status` is in the set. */
    STATUSES,

    /** Kept when `level` is at least the value. */
    MIN_LEVEL,

    /** Kept when `level` is at most the value. */
    MAX_LEVEL,

    /** Kept when `created` is at or after the instant. */
    CREATED_AFTER,

    /** Kept when `created` is at or before the instant. */
    CREATED_BEFORE,

    /**
     * Kept when `lastTouched` is at or after the instant. `lastTouched` is the later of the
     * content revision and the metadata revision.
     */
    REVISED_AFTER,

    /** Kept when `lastTouched` is at or before the instant. */
    REVISED_BEFORE,

    /** Kept when `lastAccessed` is at or after the instant. */
    ACCESSED_AFTER,

    /** Kept when `lastAccessed` is at or before the instant. */
    ACCESSED_BEFORE,

    /** Kept when `confidence`, the stored value, is at least the threshold. Decay plays no part. */
    MIN_CONFIDENCE,

    /** Kept when effective confidence is at least the threshold. */
    MIN_EFFECTIVE_CONFIDENCE,

    /** Kept when effective confidence is below the threshold. A value equal to it is dropped. */
    BELOW_EFFECTIVE_CONFIDENCE,

    /**
     * The instant effective confidence is computed at. It keeps and drops nothing by itself. Only
     * the criteria of a read carry it. Every store lists it as applied, because it changes nothing
     * in a store that applies no effective-confidence criterion or order.
     */
    EFFECTIVE_CONFIDENCE_AS_OF,

    /**
     * The decay rate effective confidence is computed with, when it differs from
     * [ReadCriteria.DEFAULT_DECAY_K]. It keeps and drops nothing by itself. Every store lists it
     * as applied, because it changes nothing in a store that applies no effective-confidence
     * criterion or order.
     */
    DECAY_K,

    /** Kept when `importance` is at least the threshold. */
    MIN_IMPORTANCE,

    /** Kept when `reinforceCount` is at least the value. */
    MIN_REINFORCE_COUNT,

    /**
     * Kept when the number stored in `metadata` under
     * [com.embabel.dice.common.DiceMetadataKeys.TRUST_SCORE] is at least the threshold. A
     * proposition with no number there, or with one that is not finite, is kept.
     */
    MIN_TRUST_SCORE,

    /** Kept when `pinned` equals the value. */
    PINNED,
}

/**
 * Collects the criteria of a `ReadCriteria { }` block. It exists only inside the block, and the
 * block returns immutable [ReadCriteria]. Each property has the meaning of the property of the
 * same name on [ReadCriteria], and null leaves that criterion unset.
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@StoreContractDsl
@ApiStatus.Experimental
class ReadCriteriaBuilder internal constructor() {
    var entityId: String? = null
    var anyEntityIds: Collection<String>? = null
    var allEntityIds: Collection<String>? = null
    var statuses: Collection<PropositionStatus>? = null
    var minLevel: Int? = null
    var maxLevel: Int? = null
    var createdAfter: Instant? = null
    var createdBefore: Instant? = null
    var revisedAfter: Instant? = null
    var revisedBefore: Instant? = null
    var accessedAfter: Instant? = null
    var accessedBefore: Instant? = null
    var minConfidence: Double? = null
    var minEffectiveConfidence: Double? = null
    var belowEffectiveConfidence: Double? = null
    var effectiveConfidenceAsOf: Instant? = null
    var decayK: Double = ReadCriteria.DEFAULT_DECAY_K
    var minImportance: Double? = null
    var minReinforceCount: Int? = null
    var minTrustScore: Double? = null
    var pinned: Boolean? = null
}

/**
 * Checks a list of entity ids and copies it into a read-only list.
 *
 * @param name The criterion the ids belong to, for the message.
 * @param entityIds The ids to check.
 * @return A read-only copy shared with no caller.
 * @throws IllegalArgumentException when [entityIds] is empty or holds a null or blank id.
 */
private fun requireEntityIds(name: String, entityIds: Collection<String>): List<String> {
    // A Java caller can pass a collection that holds null.
    val ids: List<String?> = entityIds.toList()
    require(ids.isNotEmpty()) { "$name must name at least one entity" }
    require(ids.none { it.isNullOrBlank() }) { "An entity id in $name must not be null or blank" }
    return Collections.unmodifiableList(ids.filterNotNull())
}

/**
 * Checks a collection of statuses and copies it into a read-only set.
 *
 * @param statuses The statuses to check.
 * @return A read-only copy shared with no caller.
 * @throws IllegalArgumentException when [statuses] is empty or holds null.
 */
private fun requireStatuses(statuses: Collection<PropositionStatus>): Set<PropositionStatus> {
    // A Java caller can pass a collection that holds null.
    val listed: List<PropositionStatus?> = statuses.toList()
    require(listed.isNotEmpty()) { "statuses must name at least one status" }
    require(listed.none { it == null }) { "A status must not be null" }
    return Collections.unmodifiableSet(LinkedHashSet(listed.filterNotNull()))
}

/**
 * Checks that a threshold, when set, is a finite number.
 *
 * @param name The criterion, for the message.
 * @param value The threshold, or null when it is not set.
 * @return The threshold, with negative zero turned into zero.
 * @throws IllegalArgumentException when [value] is NaN or infinite.
 */
private fun requireFinite(name: String, value: Double?): Double? {
    require(value == null || value.isFinite()) { "$name must be a finite number, but was $value" }
    // Adding 0.0 turns -0.0 into 0.0, so equal criteria have equal hash codes.
    return value?.plus(0.0)
}

/**
 * Checks a decay rate.
 *
 * @param decayK The decay rate.
 * @return The decay rate, with negative zero turned into zero.
 * @throws IllegalArgumentException when [decayK] is negative, NaN or infinite.
 */
private fun requireDecayK(decayK: Double): Double {
    require(decayK >= 0.0 && decayK.isFinite()) { "decayK must be a finite number of zero or more, but was $decayK" }
    // Adding 0.0 turns -0.0 into 0.0, so equal criteria have equal hash codes.
    return decayK + 0.0
}

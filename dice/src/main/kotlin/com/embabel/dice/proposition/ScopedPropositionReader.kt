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

import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import java.time.Instant

@DslMarker
internal annotation class ScopedReadLimitsDsl

enum class ReadOrder {
    NONE, EFFECTIVE_CONFIDENCE_DESC, CREATED_DESC, REVISED_DESC,
    LAST_ACCESSED_DESC, REINFORCE_COUNT_DESC, IMPORTANCE_DESC,
}

/** Finite allowances for a bound read and its downstream probe/eager consumers. */
class ScopedReadLimits(
    val maxResults: Int,
    val maxProbe: Int,
    val maxEager: Int,
    val vectorBudget: ExactVectorBudget,
) {
    init {
        if (listOf(maxResults, maxProbe, maxEager).any { it < 0 || it == Int.MAX_VALUE } ||
            maxProbe < maxResults || maxProbe < maxEager
        ) throw ScopedReadException("INVALID_SCOPED_READ")
    }

    fun withMaxResults(value: Int) = ScopedReadLimits(value, maxProbe, maxEager, vectorBudget)
    fun withMaxProbe(value: Int) = ScopedReadLimits(maxResults, value, maxEager, vectorBudget)
    fun withMaxEager(value: Int) = ScopedReadLimits(maxResults, maxProbe, value, vectorBudget)
    fun withVectorBudget(value: ExactVectorBudget) = ScopedReadLimits(maxResults, maxProbe, maxEager, value)

    data class Configuring internal constructor(
        private val maxResults: Int = 100,
        private val maxProbe: Int = 401,
        private val maxEager: Int = 100,
        private val vectorBudget: ExactVectorBudget = ExactVectorBudget.DEFAULT,
    ) {
        fun withMaxResults(value: Int): Configuring = copy(maxResults = value)
        fun withMaxProbe(value: Int): Configuring = copy(maxProbe = value)
        fun withMaxEager(value: Int): Configuring = copy(maxEager = value)
        fun withVectorBudget(value: ExactVectorBudget): Configuring = copy(vectorBudget = value)
        fun limits(): ScopedReadLimits = ScopedReadLimits(maxResults, maxProbe, maxEager, vectorBudget)
    }

    @ScopedReadLimitsDsl
    class Configuration internal constructor() {
        var maxResults: Int = 100
        var maxProbe: Int = 401
        var maxEager: Int = 100
        var vectorBudget: ExactVectorBudget = ExactVectorBudget.DEFAULT
    }

    companion object {
        @JvmField
        val DEFAULT = ScopedReadLimits(100, 401, 100, ExactVectorBudget.DEFAULT)

        @JvmStatic
        fun configuring(): Configuring = Configuring()

        @JvmSynthetic
        operator fun invoke(configure: Configuration.() -> Unit): ScopedReadLimits {
            val configuration = Configuration().apply(configure)
            return ScopedReadLimits(
                configuration.maxResults, configuration.maxProbe, configuration.maxEager, configuration.vectorBudget,
            )
        }
    }
}

/** Fixed public failure vocabulary; backend details are never attached as a cause. */
class ScopedReadException(code: String) : IllegalStateException(safeCode(code)) {
    val code: String = safeCode(code)

    companion object {
        private fun safeCode(code: String): String = when (code) {
            "SCOPED_READ_UNSUPPORTED", "SCOPED_VECTOR_UNSUPPORTED", "INVALID_SCOPED_READ",
            "SCOPED_READ_RESULT_INVALID", "SCOPED_READ_FAILED" -> code
            else -> "SCOPED_READ_FAILED"
        }
    }
}

/** Explicit opt-in: implementations must filter, rank, count and limit in their backend. */
interface ContextSetReadCapable {
    val supportsScopedReads: Boolean get() = true
    fun query(scope: ContextReadScope, spec: ScopedReadSpec, order: ReadOrder, limit: Int): List<Proposition>
    fun count(scope: ContextReadScope, spec: ScopedReadSpec): Long
    fun keywordOverlap(scope: ContextReadScope, spec: ScopedReadSpec, tokens: List<String>, limit: Int): List<Proposition>
}

interface ContextSetVectorSearchCapable : ContextSetReadCapable {
    val supportsScopedVectors: Boolean get() = true
    fun findSimilarWithScores(
        request: TextSimilaritySearchRequest,
        scope: ContextReadScope,
        spec: ScopedReadSpec,
        budget: ExactVectorBudget,
    ): List<SimilarityResult<Proposition>>
}

/** Immutable policy binding. Restrictions only narrow membership; snapshot freezes its clock. */
class ScopedPropositionReader private constructor(
    internal val store: PropositionStore,
    val scope: ContextReadScope,
    val limits: ScopedReadLimits,
    private val criteria: List<PropositionQuery>,
    private val grants: ScopedReadSpec,
) {
    fun withLimits(limits: ScopedReadLimits) = ScopedPropositionReader(store, scope, limits, criteria, grants)

    fun restrict(criteria: PropositionQuery): ScopedPropositionReader = scopedCall {
        validateCriteria(criteria)
        // Use the producer's defensive snapshot without giving an unfrozen query a clock yet.
        val copied = ScopedReadSpec.at(criteria.effectiveConfidenceAsOf ?: Instant.EPOCH, 2.0)
            .restrict(scope, criteria).fragments.single()
            .copy(effectiveConfidenceAsOf = criteria.effectiveConfidenceAsOf)
        ScopedPropositionReader(store, scope, limits, this.criteria + copied, grants)
    }

    fun includingOnly(keys: Collection<PropositionKey>): ScopedPropositionReader = scopedCall {
        ScopedPropositionReader(store, scope, limits, criteria, grants.includingOnly(scope, keys))
    }

    fun excluding(keys: Collection<PropositionKey>): ScopedPropositionReader = scopedCall {
        ScopedPropositionReader(store, scope, limits, criteria, grants.excluding(scope, keys))
    }

    fun snapshot(asOf: Instant, rankingDecayK: Double): ScopedReadSession = scopedCall {
        if (!rankingDecayK.isFinite() || rankingDecayK < 0) throw ScopedReadException("INVALID_SCOPED_READ")
        var spec = ScopedReadSpec.at(asOf, rankingDecayK)
        criteria.forEach { spec = spec.restrict(scope, it) }
        grants.includedKeys?.let { spec = spec.includingOnly(scope, it) }
        spec = spec.excluding(scope, grants.excludedKeys)
        ScopedReadSession(store, scope, spec, limits)
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun open(
            store: PropositionStore,
            scope: ContextReadScope,
            limits: ScopedReadLimits = ScopedReadLimits.DEFAULT,
        ): ScopedPropositionReader {
            requireScopedBackend(store)
            return ScopedPropositionReader(store, scope, limits, emptyList(), ScopedReadSpec.at(Instant.EPOCH, 2.0))
        }
    }
}

/** A reusable read session: every terminal operation observes the same filter/ranking instant. */
class ScopedReadSession internal constructor(
    private val store: PropositionStore,
    val scope: ContextReadScope,
    val spec: ScopedReadSpec,
    val limits: ScopedReadLimits,
) {
    fun restrict(criteria: PropositionQuery): ScopedReadSession = scopedCall {
        validateCriteria(criteria)
        ScopedReadSession(store, scope, spec.restrict(scope, criteria), limits)
    }

    fun includingOnly(keys: Collection<PropositionKey>): ScopedReadSession = scopedCall {
        ScopedReadSession(store, scope, spec.includingOnly(scope, keys), limits)
    }

    fun excluding(keys: Collection<PropositionKey>): ScopedReadSession = scopedCall {
        ScopedReadSession(store, scope, spec.excluding(scope, keys), limits)
    }

    fun query(order: ReadOrder, limit: Int): List<Proposition> = scopedCall {
        validateLimit(limit)
        val backend = requireScopedBackend(store)
        if (limit == 0) return@scopedCall emptyList()
        val results = backend.query(scope, spec, order, limit).toList()
        validateResults(results, limit)
        results
    }

    /** Count is membership only and is independent of every presentation allowance. */
    fun count(): Long = scopedCall {
        val count = requireScopedBackend(store).count(scope, spec)
        if (count < 0) throw ScopedReadException("SCOPED_READ_RESULT_INVALID")
        count
    }

    fun keywordOverlap(tokens: List<String>, limit: Int): List<Proposition> = scopedCall {
        validateLimit(limit)
        val backend = requireScopedBackend(store)
        val capturedTokens = tokens.toList()
        if (limit == 0 || capturedTokens.isEmpty()) return@scopedCall emptyList()
        val results = backend.keywordOverlap(scope, spec, capturedTokens, limit).toList()
        validateResults(results, limit)
        results
    }

    fun findSimilarWithScores(request: TextSimilaritySearchRequest): List<SimilarityResult<Proposition>> = scopedCall {
        validateLimit(request.topK)
        if (!request.similarityThreshold.isFinite() || request.similarityThreshold !in 0.0..1.0) {
            throw ScopedReadException("INVALID_SCOPED_READ")
        }
        requireScopedBackend(store)
        val backend = store as? ContextSetVectorSearchCapable
            ?: throw ScopedReadException("SCOPED_VECTOR_UNSUPPORTED")
        if (!backend.supportsScopedVectors) throw ScopedReadException("SCOPED_VECTOR_UNSUPPORTED")
        if (request.topK == 0) return@scopedCall emptyList()
        val results = backend.findSimilarWithScores(request, scope, spec, limits.vectorBudget).toList()
        validateResults(results.map { it.match }, request.topK)
        if (results.any { !it.score.isFinite() || it.score < request.similarityThreshold }) {
            throw ScopedReadException("SCOPED_READ_RESULT_INVALID")
        }
        results
    }

    private fun validateLimit(limit: Int) {
        if (limit < 0 || limit > limits.maxProbe) throw ScopedReadException("INVALID_SCOPED_READ")
    }

    private fun validateResults(results: List<Proposition>, limit: Int) {
        val keys = results.map { PropositionKey(it.contextId.value, it.id) }
        if (results.size > limit || keys.toSet().size != keys.size || results.indices.any { index ->
                val key = keys[index]
                key.contextIdValue !in scope.contextIdValues || key.propositionId.isBlank() ||
                    (spec.includedKeys != null && key !in spec.includedKeys) || key in spec.excludedKeys ||
                    spec.fragments.any { !it.matchesFilters(results[index], spec.asOf) }
            }
        ) throw ScopedReadException("SCOPED_READ_RESULT_INVALID")
    }
}

private fun requireScopedBackend(store: PropositionStore): ContextSetReadCapable = scopedCall {
    val backend = store as? ContextSetReadCapable ?: throw ScopedReadException("SCOPED_READ_UNSUPPORTED")
    if (!backend.supportsScopedReads) throw ScopedReadException("SCOPED_READ_UNSUPPORTED")
    backend
}

private fun validateCriteria(criteria: PropositionQuery) {
    val bounds = listOfNotNull(
        criteria.minConfidence, criteria.minEffectiveConfidence, criteria.belowEffectiveConfidence,
        criteria.minImportance, criteria.minTrustScore, criteria.decayK,
    )
    if (bounds.any { !it.isFinite() || it < 0 } ||
        listOfNotNull(criteria.minLevel, criteria.maxLevel, criteria.minReinforceCount).any { it < 0 }
    ) throw ScopedReadException("INVALID_SCOPED_READ")
}

private inline fun <T> scopedCall(operation: () -> T): T = try {
    operation()
} catch (ex: ScopedReadException) {
    throw ex
} catch (ex: FilteredVectorUnsupportedException) {
    throw ex
} catch (ex: ExactVectorBudgetExceededException) {
    throw ex
} catch (ex: ExactVectorDeadlineExceededException) {
    throw ex
} catch (ex: IllegalArgumentException) {
    throw ScopedReadException("INVALID_SCOPED_READ")
} catch (ex: Exception) {
    throw ScopedReadException("SCOPED_READ_FAILED")
}

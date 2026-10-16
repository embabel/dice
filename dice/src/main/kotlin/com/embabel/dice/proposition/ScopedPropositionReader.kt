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
import org.slf4j.LoggerFactory
import java.time.Instant

/** How much one scoped read may return, and how far an exact vector search may scan. */
class ScopedReadLimits(
    val maxRows: Int,
    val vectorBudget: ExactVectorBudget,
) {
    init {
        require(maxRows >= 0) { "maxRows must not be negative" }
    }

    fun withMaxRows(value: Int): ScopedReadLimits = ScopedReadLimits(value, vectorBudget)

    fun withVectorBudget(value: ExactVectorBudget): ScopedReadLimits = ScopedReadLimits(maxRows, value)

    /** The settings the Kotlin block fills in; anything left alone keeps its default. */
    class Configuration {
        var maxRows: Int = DEFAULT_MAX_ROWS
        var vectorBudget: ExactVectorBudget = ExactVectorBudget.DEFAULT
    }

    companion object {
        /** The most rows one call returns unless the host sets another cap. */
        const val DEFAULT_MAX_ROWS = 1000

        @JvmField
        val DEFAULT = ScopedReadLimits(DEFAULT_MAX_ROWS, ExactVectorBudget.DEFAULT)

        /** Kotlin entry point; Java callers use `DEFAULT` and the withers. */
        @JvmSynthetic
        operator fun invoke(configure: Configuration.() -> Unit): ScopedReadLimits {
            val configuration = Configuration().apply(configure)
            return DEFAULT.withMaxRows(configuration.maxRows).withVectorBudget(configuration.vectorBudget)
        }
    }
}

/** Why a scoped read failed. */
enum class ScopedReadFailure {
    /** The store cannot run scoped reads, or has them switched off. */
    SCOPED_READ_UNSUPPORTED,

    /** The store cannot run scoped vector search. */
    SCOPED_VECTOR_UNSUPPORTED,

    /** The caller asked for something the read does not allow. */
    INVALID_SCOPED_READ,

    /** The store returned rows the read did not allow. */
    SCOPED_READ_RESULT_INVALID,

    /** The store failed while reading; the cause says why. */
    SCOPED_READ_FAILED,

    /** Exact vector search found more eligible rows than its budget. */
    EXACT_VECTOR_BUDGET_EXCEEDED,

    /** Exact vector search ran out of time. */
    EXACT_VECTOR_DEADLINE_EXCEEDED,
}

/** A scoped read that could not run, or whose store broke the read's rules. The message is the failure's name. */
class ScopedReadException @JvmOverloads constructor(
    val failure: ScopedReadFailure,
    cause: Throwable? = null,
) : IllegalStateException(failure.name, cause)

/**
 * A store that runs scoped reads itself, filtering, ranking, counting and limiting in its own backend.
 *
 * Declaring this interface does not mean the store can serve scoped reads right now: a store may
 * declare it with the feature switched off, and a wrapping store declares it whatever it wraps.
 * [supportsScopedReads] is the answer. Check it after any `is` or `as?` test; a call on a store that
 * reports false fails with [ScopedReadFailure.SCOPED_READ_UNSUPPORTED].
 *
 * Every store keeps the same order for ties: rows that tie on the requested order come out by context
 * ID, then proposition ID, both compared by Unicode code point.
 */
interface ContextSetReadCapable {
    /** Whether this store can serve scoped reads now. */
    val supportsScopedReads: Boolean get() = true

    fun query(spec: ScopedReadSpec, clock: ReadClock, order: PropositionQuery.OrderBy, limit: Int): List<Proposition>

    fun count(spec: ScopedReadSpec, clock: ReadClock): Long

    fun keywordOverlap(spec: ScopedReadSpec, clock: ReadClock, tokens: List<String>, limit: Int): List<Proposition>
}

/**
 * A store that also runs scoped vector search. As with [ContextSetReadCapable], the type is not the
 * answer: [supportsScopedVectors] says whether vector search can run, and it is false for a store with
 * no embedding service. A call on a store that reports false fails with
 * [ScopedReadFailure.SCOPED_VECTOR_UNSUPPORTED].
 */
interface ContextSetVectorSearchCapable : ContextSetReadCapable {
    /** Whether this store can run scoped vector search now. */
    val supportsScopedVectors: Boolean get() = true

    fun findSimilarWithScores(
        request: TextSimilaritySearchRequest,
        spec: ScopedReadSpec,
        clock: ReadClock,
        budget: ExactVectorBudget,
    ): List<SimilarityResult<Proposition>>
}

/** A store bound to one read spec and its limits. Narrowing returns a new reader; snapshot fixes the clock. */
class ScopedPropositionReader private constructor(
    internal val store: PropositionStore,
    val spec: ScopedReadSpec,
    val limits: ScopedReadLimits,
) {
    /** The contexts this reader reads from. */
    val scope: ContextReadScope get() = spec.scope

    fun withLimits(limits: ScopedReadLimits) = ScopedPropositionReader(store, spec, limits)

    fun restrict(criteria: PropositionQuery): ScopedPropositionReader = validated {
        validateCriteria(criteria)
        ScopedPropositionReader(store, spec.restrict(criteria), limits)
    }

    fun includingOnly(keys: Collection<PropositionKey>): ScopedPropositionReader = validated {
        ScopedPropositionReader(store, spec.includingOnly(keys), limits)
    }

    fun excluding(keys: Collection<PropositionKey>): ScopedPropositionReader = validated {
        ScopedPropositionReader(store, spec.excluding(keys), limits)
    }

    /** Pairs this reader's spec with the instant and ranking decay every read in the session uses. */
    fun snapshot(asOf: Instant, rankingDecayK: Double): ScopedReadSession = validated {
        ScopedReadSession(store, spec, ReadClock(asOf, rankingDecayK), limits)
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
            return ScopedPropositionReader(store, ScopedReadSpec.of(scope), limits)
        }
    }
}

/** A reusable read session: every terminal operation reads with the same spec and the same clock. */
class ScopedReadSession internal constructor(
    private val store: PropositionStore,
    val spec: ScopedReadSpec,
    val clock: ReadClock,
    val limits: ScopedReadLimits,
) {
    /** The contexts this session reads from. */
    val scope: ContextReadScope get() = spec.scope

    fun restrict(criteria: PropositionQuery): ScopedReadSession = validated {
        validateCriteria(criteria)
        ScopedReadSession(store, spec.restrict(criteria), clock, limits)
    }

    fun includingOnly(keys: Collection<PropositionKey>): ScopedReadSession = validated {
        ScopedReadSession(store, spec.includingOnly(keys), clock, limits)
    }

    fun excluding(keys: Collection<PropositionKey>): ScopedReadSession = validated {
        ScopedReadSession(store, spec.excluding(keys), clock, limits)
    }

    fun query(order: PropositionQuery.OrderBy, limit: Int): List<Proposition> {
        validateLimit(limit)
        val backend = requireScopedBackend(store)
        if (limit == 0) return emptyList()
        val results = storeCall("query") { backend.query(spec, clock, order, limit).toList() }
        validateResults(results, limit)
        return results
    }

    /** Count is membership only and is independent of every presentation allowance. */
    fun count(): Long {
        val backend = requireScopedBackend(store)
        val count = storeCall("count") { backend.count(spec, clock) }
        if (count < 0) throw ScopedReadException(ScopedReadFailure.SCOPED_READ_RESULT_INVALID)
        return count
    }

    fun keywordOverlap(tokens: List<String>, limit: Int): List<Proposition> {
        validateLimit(limit)
        val backend = requireScopedBackend(store)
        val capturedTokens = tokens.toList()
        if (limit == 0 || capturedTokens.isEmpty()) return emptyList()
        val results = storeCall("keyword") { backend.keywordOverlap(spec, clock, capturedTokens, limit).toList() }
        validateResults(results, limit)
        return results
    }

    fun findSimilarWithScores(request: TextSimilaritySearchRequest): List<SimilarityResult<Proposition>> {
        validateLimit(request.topK)
        if (!request.similarityThreshold.isFinite() || request.similarityThreshold !in 0.0..1.0) {
            throw ScopedReadException(ScopedReadFailure.INVALID_SCOPED_READ)
        }
        requireScopedBackend(store)
        val backend = store as? ContextSetVectorSearchCapable
            ?: throw ScopedReadException(ScopedReadFailure.SCOPED_VECTOR_UNSUPPORTED)
        if (!storeCall("capability check") { backend.supportsScopedVectors }) {
            throw ScopedReadException(ScopedReadFailure.SCOPED_VECTOR_UNSUPPORTED)
        }
        if (request.topK == 0) return emptyList()
        val results = storeCall("vector") {
            backend.findSimilarWithScores(request, spec, clock, limits.vectorBudget).toList()
        }
        validateResults(results.map { it.match }, request.topK)
        if (results.any { !it.score.isFinite() || it.score < request.similarityThreshold }) {
            throw ScopedReadException(ScopedReadFailure.SCOPED_READ_RESULT_INVALID)
        }
        return results
    }

    private fun validateLimit(limit: Int) {
        if (limit < 0 || limit > limits.maxRows) throw ScopedReadException(ScopedReadFailure.INVALID_SCOPED_READ)
    }

    /**
     * Last line of defence against a store that ignores the read's rules: rows outside the scope,
     * outside the grants, excluded, duplicated, blank or too many. Criteria are not re-checked here,
     * because the store already applied them and its arithmetic can differ from Kotlin's at a boundary.
     */
    private fun validateResults(results: List<Proposition>, limit: Int) {
        val keys = results.map { PropositionKey(it.contextId.value, it.id) }
        if (results.size > limit || keys.toSet().size != keys.size || keys.any { key ->
                key.contextIdValue !in scope.contextIdValues || key.propositionId.isBlank() ||
                    (spec.includedKeys != null && key !in spec.includedKeys) || key in spec.excludedKeys
            }
        ) throw ScopedReadException(ScopedReadFailure.SCOPED_READ_RESULT_INVALID)
    }
}

private val logger = LoggerFactory.getLogger(ScopedPropositionReader::class.java)

private fun requireScopedBackend(store: PropositionStore): ContextSetReadCapable {
    val backend = store as? ContextSetReadCapable
        ?: throw ScopedReadException(ScopedReadFailure.SCOPED_READ_UNSUPPORTED)
    if (!storeCall("capability check") { backend.supportsScopedReads }) {
        throw ScopedReadException(ScopedReadFailure.SCOPED_READ_UNSUPPORTED)
    }
    return backend
}

private fun validateCriteria(criteria: PropositionQuery) {
    val bounds = listOfNotNull(
        criteria.minConfidence, criteria.minEffectiveConfidence, criteria.belowEffectiveConfidence,
        criteria.minImportance, criteria.minTrustScore, criteria.decayK,
    )
    if (bounds.any { !it.isFinite() || it < 0 } ||
        listOfNotNull(criteria.minLevel, criteria.maxLevel, criteria.minReinforceCount).any { it < 0 }
    ) throw ScopedReadException(ScopedReadFailure.INVALID_SCOPED_READ)
}

/** Caller input that fails validation is the caller's error. */
private inline fun <T> validated(build: () -> T): T = try {
    build()
} catch (ex: IllegalArgumentException) {
    throw ScopedReadException(ScopedReadFailure.INVALID_SCOPED_READ, ex)
}

/** A store failure keeps its cause and is logged, so an outage is visible to operators. */
private inline fun <T> storeCall(operation: String, call: () -> T): T = try {
    call()
} catch (ex: ScopedReadException) {
    throw ex
} catch (ex: Exception) {
    logger.warn("Scoped {} failed in the store", operation, ex)
    throw ScopedReadException(ScopedReadFailure.SCOPED_READ_FAILED, ex)
}

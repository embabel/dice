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
import java.time.Duration

/**
 * Passes scoped reads, scoped vector search and strict policy reads from a wrapping store to the
 * store it wraps.
 *
 * A wrapper declares the scoped capabilities whatever it wraps, so its type says nothing about
 * support. Each `supports` flag asks the wrapped store instead, and a call the wrapped store
 * cannot serve fails with the matching [ScopedReadFailure] before it reaches that store.
 */
internal class ScopedReadForwarder(private val target: Any) :
    ContextSetVectorSearchCapable,
    StrictPolicyReadCapable {
    override val supportsScopedReads: Boolean
        get() = (target as? ContextSetReadCapable)?.supportsScopedReads == true

    override val supportsScopedVectors: Boolean
        get() = (target as? ContextSetVectorSearchCapable)?.supportsScopedVectors == true

    override val supportsStrictPolicyReads: Boolean
        get() = (target as? StrictPolicyReadCapable)?.supportsStrictPolicyReads == true

    override fun query(
        spec: ScopedReadSpec,
        clock: ReadClock,
        order: PropositionQuery.OrderBy,
        limit: Int,
    ): List<Proposition> = reads().query(spec, clock, order, limit)

    override fun count(spec: ScopedReadSpec, clock: ReadClock): Long = reads().count(spec, clock)

    override fun keywordOverlap(
        spec: ScopedReadSpec,
        clock: ReadClock,
        tokens: List<String>,
        limit: Int,
    ): List<Proposition> = reads().keywordOverlap(spec, clock, tokens, limit)

    override fun findSimilarWithScores(
        request: TextSimilaritySearchRequest,
        spec: ScopedReadSpec,
        clock: ReadClock,
        budget: ExactVectorBudget,
    ): List<SimilarityResult<Proposition>> {
        val capable = target as? ContextSetVectorSearchCapable
        if (capable?.supportsScopedVectors != true) throw ScopedReadException(ScopedReadFailure.SCOPED_VECTOR_UNSUPPORTED)
        return capable.findSimilarWithScores(request, spec, clock, budget)
    }

    override fun queryByGrantOrder(
        spec: ScopedReadSpec,
        clock: ReadClock,
        rows: List<StrictReadRow>,
        limit: Int,
        timeout: Duration,
    ): List<Proposition> = strictReads().queryByGrantOrder(spec, clock, rows, limit, timeout)

    override fun keywordByApprovedText(
        spec: ScopedReadSpec,
        clock: ReadClock,
        rows: List<StrictReadRow>,
        tokens: List<String>,
        limit: Int,
        timeout: Duration,
    ): List<Proposition> = strictReads().keywordByApprovedText(spec, clock, rows, tokens, limit, timeout)

    private fun reads(): ContextSetReadCapable {
        val capable = target as? ContextSetReadCapable
        if (capable?.supportsScopedReads != true) throw ScopedReadException(ScopedReadFailure.SCOPED_READ_UNSUPPORTED)
        return capable
    }

    private fun strictReads(): StrictPolicyReadCapable {
        val capable = target as? StrictPolicyReadCapable
        if (capable?.supportsStrictPolicyReads != true) throw ScopedReadException(ScopedReadFailure.SCOPED_READ_UNSUPPORTED)
        return capable
    }
}

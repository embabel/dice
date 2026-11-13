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
import com.embabel.dice.proposition.PropositionQuery
import org.jetbrains.annotations.ApiStatus

/**
 * Query, count and keyword match held to a [ScopedReadSpec] and a [ReadBudget]. Every method
 * takes the spec, so a read with no scope cannot be written against this interface.
 *
 * ```java
 * ScopedReads reads = StoreCapabilities.find(store, ScopedReads.class);
 * if (reads != null) {
 *     List<Proposition> recent = reads.query(spec, ScopedReadSpec.criteria().orderedByCreated().withLimit(20), budget);
 *     int total = reads.count(spec, ScopedReadSpec.criteria());
 * }
 * ```
 *
 * The candidates of a call are the propositions whose context is in the spec's scope and that
 * match every filter of the spec and the call's own query. An implementation keeps these rules:
 *
 * 1. The store selects the candidates first, by the scope and by every filter. It orders, cuts
 *    to a limit, matches keywords and counts among the candidates only.
 * 2. A call whose query names a context the spec does not allow throws
 *    [ReadScopeViolationException] and reads nothing. [ScopedReadSpec.requireAllowed] is that
 *    check, and it selects nothing. The store never passes the call's query to a read that takes
 *    no spec. It compares context ids exactly, whatever the collation of its database.
 * 3. A proposition that is not a candidate is never returned or counted, and no value of it
 *    enters the order of a result.
 * 4. A limit above [ReadBudget.maxRows] throws [ReadBudgetExceededException]. So does a read
 *    with no limit that would return more rows than [ReadBudget.maxRows]. The store never returns
 *    a shortened result in place of the exception. A limit below 1 throws
 *    [IllegalArgumentException].
 * 5. A store that keeps its data in a database runs the scope, the filters, keyword matching and
 *    ranking, the ordering, the limit and the count there. It does not load rows into the JVM to
 *    finish any of them.
 * 6. Every field set on a filter or on the query is applied. Effective confidence is computed as
 *    of the query's `effectiveConfidenceAsOf`, or the time of the call when that is null, and one
 *    call uses that one time for the query and for every filter of the spec. A store that cannot
 *    apply a field throws [UnsupportedCapabilityException] carrying
 *    [StoreCapability.SCOPED_READS]. It never reads more contexts than the spec allows and never
 *    ignores a filter.
 * 7. Results that rank equally come back in the same order each time the same call runs on
 *    unchanged data.
 *
 * A store that implements this interface keeps every rule for every method and lists
 * [StoreCapability.SCOPED_READS]. Similarity search under a spec is [ScopedVectorSearch].
 *
 * EXPERIMENTAL. This API may change before 1.0.
 */
@ApiStatus.Experimental
interface ScopedReads {

    /**
     * Returns the candidates in the query's order, cut to the query's limit.
     *
     * @param spec Which propositions the read may return.
     * @param query Further criteria, the ordering and the limit of this read.
     * @param budget The most the read may take.
     * @return The propositions, in order. Empty when there is no candidate.
     * @throws ReadScopeViolationException when [query] names a context [spec] does not allow.
     * @throws ReadBudgetExceededException when the limit, or the rows a read with no limit would
     *   return, is more than the budget's row cap.
     * @throws UnsupportedCapabilityException when the store cannot apply a field of a filter.
     * @throws IllegalArgumentException when the limit is below 1.
     */
    fun query(spec: ScopedReadSpec, query: PropositionQuery, budget: ReadBudget): List<Proposition>

    /**
     * Counts the candidates.
     *
     * @param spec Which propositions the read may count.
     * @param query Further criteria of this read.
     * @return The number of candidates.
     * @throws ReadScopeViolationException when [query] names a context [spec] does not allow.
     * @throws UnsupportedCapabilityException when the store cannot apply a field of a filter.
     * @throws IllegalArgumentException when [query] carries an ordering or a limit.
     */
    fun count(spec: ScopedReadSpec, query: PropositionQuery): Int

    /**
     * Returns the candidates whose text contains at least one of the tokens, ignoring case. The
     * ones that contain more distinct tokens come first, then the query's order. The result is
     * cut to the query's limit.
     *
     * A token is literal text. It carries no operator and no wildcard. Tokens that are equal
     * ignoring case count once.
     *
     * @param spec Which propositions the read may return.
     * @param query Further criteria, the tie order and the limit of this read.
     * @param tokens The words to look for. An empty list returns no proposition.
     * @param budget The most the read may take.
     * @return The propositions, best match first.
     * @throws ReadScopeViolationException when [query] names a context [spec] does not allow.
     * @throws ReadBudgetExceededException when the limit, or the rows a read with no limit would
     *   return, is more than the budget's row cap.
     * @throws UnsupportedCapabilityException when the store cannot apply a field of a filter.
     * @throws IllegalArgumentException when a token is blank, or the limit is below 1.
     */
    fun matchKeywords(
        spec: ScopedReadSpec,
        query: PropositionQuery,
        tokens: List<String>,
        budget: ReadBudget,
    ): List<Proposition>
}

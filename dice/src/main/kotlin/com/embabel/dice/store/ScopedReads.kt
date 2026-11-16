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
import org.jetbrains.annotations.ApiStatus

/**
 * Query, count and keyword match held to a [ScopedReadSpec] and a [ReadBudget]. Every method
 * takes the spec, and its request names no context, so a read with no scope cannot be written
 * against this interface.
 *
 * ```java
 * ScopedReads reads = StoreCapabilities.find(store, ScopedReads.class);
 * if (reads != null) {
 *     ReadCriteria aboutAlice = ReadCriteria.empty().withEntityId("alice");
 *     List<Proposition> recent = reads.query(spec, ReadQuery.of(aboutAlice).orderedByCreated().withLimit(20), budget);
 *     int total = reads.count(spec, aboutAlice, budget);
 * }
 * ```
 *
 * The candidates of a call are the propositions whose context is one that
 * [ScopedReadSpec.scope] names and that meet every filter of the spec and the criteria of the
 * call's request. An implementation keeps these rules:
 *
 * 1. Selection first. The store selects the candidates by the contexts and by every filter. It
 *    orders, cuts to a limit, matches keywords and counts among the candidates only.
 * 2. Contexts. The store reads no context outside [ScopedReadSpec.scope]. It compares context
 *    ids exactly, whatever the collation of its database. It never runs a call's criteria
 *    through a read that takes no spec.
 * 3. Non-candidates. A proposition that is not a candidate is never returned or counted, and no
 *    value of it enters the order of a result.
 * 4. Check order. Every method makes its checks in this order and stops at the first that
 *    fails. First the spec, with [ScopedReadSpec.requireOnly]. Then the request, with its own
 *    `requireOnly`. Then the budget, with [ReadBudget.requireRows] on the request's limit when
 *    it has one. Only then does the store read. A row count the read produces is checked as
 *    soon as the store has it.
 * 5. Row cap. A read that needs more rows than [ReadBudget.maxRows] throws
 *    [ReadBudgetExceededException] and returns nothing. That covers a limit above the cap, a
 *    read with no limit that would return more rows than the cap, and a count above the cap.
 *    The store passes each row count to [ReadBudget.requireRows] as its database returned it
 *    and does not narrow it to an `int` first. It never returns a shortened result or a smaller
 *    count in place of the exception. A count is therefore never more than
 *    [ReadBudget.maxRows].
 * 6. Push-down. A store that keeps its data in a database runs the selection by context, the
 *    filters, keyword matching and ranking, the ordering, the limit and the count there. It
 *    also learns there that a read goes over the row cap: it asks for one row more than the
 *    cap, stops a count one past it, or counts in full. It does not load rows into the JVM to
 *    finish any of them.
 * 7. Criteria and orders. The store applies every criterion set on a filter or on the request's
 *    criteria, and the request's order, as [ReadCriterion] and [ReadOrder] define them. It
 *    passes the criteria and the orders it applies to the `requireOnly` checks of rule 4. For
 *    any other criterion or order those checks throw [UnsupportedCapabilityException] carrying
 *    [StoreCapability.SCOPED_READS]. The store never ignores a filter, a criterion or an order.
 *    Every store lists [ReadCriterion.EFFECTIVE_CONFIDENCE_AS_OF] and [ReadCriterion.DECAY_K],
 *    which change nothing in a store that applies no effective-confidence criterion or order.
 * 8. One instant. Effective confidence is [Proposition.effectiveConfidenceAt]. One call computes
 *    it at one instant: the `effectiveConfidenceAsOf` of the request's criteria, or, when that
 *    is null, one reading of the store's clock taken at the start of the call. The store binds
 *    that instant in every statement of the call, for the request's criteria, for every filter
 *    of the spec and for the order. Each filter uses its own `decayK`. Ordering by effective
 *    confidence uses the `decayK` of the request's criteria.
 * 9. Stable ties. Results that rank equally come back in the same order each time the same call
 *    runs on unchanged data.
 * 10. Keywords. The tokens of a [ReadKeywordQuery] are lower-cased literal text with no operator
 *    and no wildcard. A proposition contains a token when its text, lower-cased, has the token
 *    as a substring, lower-cased with `Locale.ROOT` when the store matches in the JVM. A store that lower-cases text in a database uses the database's function.
 *    That function and `Locale.ROOT` agree on ASCII letters, and the store's documentation says
 *    where they differ.
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
     * @param query Further criteria, the order and the limit of this read.
     * @param budget The most the read may take.
     * @return The propositions, in order. Empty when there is no candidate.
     * @throws ReadBudgetExceededException when the limit, or the rows a read with no limit would
     *   return, is more than the budget's row cap.
     * @throws UnsupportedCapabilityException when the store does not apply a criterion or the
     *   order.
     */
    fun query(spec: ScopedReadSpec, query: ReadQuery, budget: ReadBudget): List<Proposition>

    /**
     * Counts the candidates.
     *
     * @param spec Which propositions the read may count.
     * @param criteria Further criteria of this read.
     * @param budget The most the read may take. Its row cap is the most a count may reach.
     * @return The number of candidates, from 0 to the budget's row cap.
     * @throws ReadBudgetExceededException when there are more candidates than the budget's row
     *   cap.
     * @throws UnsupportedCapabilityException when the store does not apply a criterion.
     */
    fun count(spec: ScopedReadSpec, criteria: ReadCriteria, budget: ReadBudget): Int

    /**
     * Returns the candidates whose text contains at least one of the request's tokens. The ones
     * that contain more of the tokens come first, then the request's order. The result is cut to
     * the request's limit.
     *
     * @param spec Which propositions the read may return.
     * @param request The tokens, further criteria, the order among equal matches and the limit.
     * @param budget The most the read may take.
     * @return The propositions, best match first. Empty when no candidate contains a token.
     * @throws ReadBudgetExceededException when the limit, or the rows a read with no limit would
     *   return, is more than the budget's row cap.
     * @throws UnsupportedCapabilityException when the store does not apply a criterion or the
     *   order.
     */
    fun matchKeywords(spec: ScopedReadSpec, request: ReadKeywordQuery, budget: ReadBudget): List<Proposition>
}
